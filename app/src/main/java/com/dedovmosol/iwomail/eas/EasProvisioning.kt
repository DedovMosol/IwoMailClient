package com.dedovmosol.iwomail.eas

/**
 * Обработка Provisioning (политик безопасности) Exchange ActiveSync
 * 
 * Принцип SOLID (Single Responsibility): отвечает только за Provision
 * 
 * MS-ASPROV: Exchange ActiveSync Provisioning Protocol
 * https://docs.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-asprov
 */
class EasProvisioning(
    private val deviceId: String,
    private val easVersion: String
) {
    
    /**
     * Результат парсинга ответа Provision
     */
    data class ProvisionResponse(
        val policyKey: String?,
        val provisionStatus: Int?,
        val policyStatus: Int?
    )
    
    /**
     * Поддерживает ли версия EAS DeviceInformation в Provision
     * EAS 14.0+ (Exchange 2010+) поддерживает DeviceInformation
     * EAS 12.x (Exchange 2007) — не поддерживает
     */
    fun supportsDeviceInfo(): Boolean {
        val major = easVersion.substringBefore(".").toIntOrNull() ?: 12
        return major >= 14
    }
    
    /**
     * Создаёт XML запрос для Фазы 1 Provision (запрос политик)
     */
    fun buildPhase1Request(): String {
        val deviceInfoBlock = if (supportsDeviceInfo()) """
                <settings:DeviceInformation>
                    <settings:Set>
                        <settings:Model>Android</settings:Model>
                        <settings:IMEI>${deviceId.takeLast(15)}</settings:IMEI>
                        <settings:FriendlyName>Android Device</settings:FriendlyName>
                        <settings:OS>Android 12</settings:OS>
                        <settings:UserAgent>Android/12-EAS-2.0</settings:UserAgent>
                    </settings:Set>
                </settings:DeviceInformation>""" else ""
        
        return """<?xml version="1.0" encoding="UTF-8"?>
<Provision xmlns="Provision" xmlns:settings="Settings">$deviceInfoBlock
    <Policies>
        <Policy>
            <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
        </Policy>
    </Policies>
</Provision>""".trimIndent()
    }
    
    /**
     * Создаёт XML запрос для Фазы 2 Provision (подтверждение принятия политик)
     */
    fun buildPhase2Request(tempPolicyKey: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<Provision xmlns="Provision">
    <Policies>
        <Policy>
            <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
            <PolicyKey>$tempPolicyKey</PolicyKey>
            <Status>1</Status>
        </Policy>
    </Policies>
</Provision>""".trimIndent()
    }
    
    /**
     * Создаёт XML запрос Settings (информация об устройстве)
     */
    fun buildSettingsRequest(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<Settings xmlns="Settings">
    <DeviceInformation>
        <Set>
            <Model>Android</Model>
            <IMEI>${deviceId.takeLast(8)}</IMEI>
            <FriendlyName>Android Device</FriendlyName>
            <OS>Android 12</OS>
            <UserAgent>Android/12-EAS-2.0</UserAgent>
        </Set>
    </DeviceInformation>
</Settings>""".trimIndent()
    }
    
    /**
     * Парсит ответ сервера на Provision запрос
     */
    fun parseResponse(responseXml: String): ProvisionResponse {
        // PolicyKey всегда внутри Policy
        val policyKey = extractValue(responseXml, "PolicyKey")
        
        // Парсим Policy Status внутри блока Policy
        var policyStatus: Int? = null
        val policyBlock = "<Policy>(.*?)</Policy>".toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(responseXml)?.groupValues?.get(1)
        if (policyBlock != null) {
            policyStatus = "<Status>(\\d+)</Status>".toRegex()
                .find(policyBlock)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        // Парсим Provision Status — первый Status вне Policy блока
        val withoutPolicy = responseXml.replace("<Policy>.*?</Policy>".toRegex(RegexOption.DOT_MATCHES_ALL), "")
        var provisionStatus = "<Status>(\\d+)</Status>".toRegex()
            .find(withoutPolicy)?.groupValues?.get(1)?.toIntOrNull()
        
        // Fallback: если не нашли Provision Status, берём первый из всех
        if (provisionStatus == null) {
            provisionStatus = "<Status>(\\d+)</Status>".toRegex()
                .find(responseXml)?.groupValues?.get(1)?.toIntOrNull()
        }
        
        return ProvisionResponse(policyKey, provisionStatus, policyStatus)
    }
    
    /**
     * Валидирует результат Фазы 1 и возвращает ошибку если есть
     */
    fun validatePhase1(response: ProvisionResponse): String? {
        // Проверяем Provision Status
        // 1 = Success, 2 = Protocol error, 3 = Server error
        // 139, 141, 145 = специальные коды (EAS 14.0+)
        if (response.provisionStatus != null && response.provisionStatus != 1) {
            return "Provision phase 1 failed: ${getProvisionStatusDescription(response.provisionStatus)} " +
                   "(Status: ${response.provisionStatus}, EAS: $easVersion)"
        }
        
        // Проверяем Policy Status
        // 1 = Success, 2 = No policy (OK!), 3-5 = ошибки
        response.policyStatus?.let { status ->
            when (status) {
                1 -> {
                    // Success — PolicyKey должен быть
                    if (response.policyKey == null) {
                        return "PolicyKey not found in response"
                    }
                }
                2 -> {
                    // No policy for this client — это нормально!
                    // Возвращаем null (нет ошибки), вызывающий код обработает
                    return null
                }
                3, 4, 5 -> {
                    return "Provision phase 1 failed: ${getPolicyStatusDescription(status)} " +
                           "(Policy Status: $status, EAS: $easVersion)"
                }
            }
        }
        
        if (response.policyKey == null && response.policyStatus != 2) {
            return "PolicyKey not found in response"
        }
        
        return null // Нет ошибок
    }
    
    /**
     * Валидирует результат Фазы 2 и возвращает ошибку если есть
     */
    fun validatePhase2(response: ProvisionResponse, tempPolicyKey: String?): String? {
        // Проверяем Provision Status
        if (response.provisionStatus != null && response.provisionStatus != 1) {
            return "Provision phase 2 failed: ${getProvisionStatusDescription(response.provisionStatus)} " +
                   "(Status: ${response.provisionStatus}, EAS: $easVersion)"
        }
        
        // Проверяем Policy Status (если есть)
        // 1 = Success, 2 = No policy (OK), остальные — ошибки
        if (response.policyStatus != null && response.policyStatus != 1 && response.policyStatus != 2) {
            return "Provision phase 2 failed: ${getPolicyStatusDescription(response.policyStatus)} " +
                   "(Policy Status: ${response.policyStatus}, EAS: $easVersion)"
        }
        
        // Проверяем что есть PolicyKey
        val effectiveKey = response.policyKey ?: tempPolicyKey
        if (effectiveKey == null) {
            return "Provision phase 2 failed: no PolicyKey in response (EAS: $easVersion)"
        }
        
        return null // Нет ошибок
    }
    
    /**
     * Получает описание Provision Status кода
     */
    private fun getProvisionStatusDescription(status: Int): String = when (status) {
        1 -> "Success"
        2 -> "Protocol error"
        3 -> "General server error"
        139 -> "Client cannot fully comply with policy"
        141 -> "Device is not provisionable"
        145 -> "Client is externally managed"
        else -> "Unknown error"
    }
    
    /**
     * Получает описание Policy Status кода
     */
    private fun getPolicyStatusDescription(status: Int): String = when (status) {
        1 -> "Success"
        2 -> "No policy for this client"
        3 -> "Unknown PolicyType"
        4 -> "Policy data corrupted"
        5 -> "Wrong policy key"
        else -> "Unknown error"
    }
    
    /**
     * Извлекает значение XML тега
     */
    private fun extractValue(xml: String, tag: String): String? {
        val pattern = "<$tag>([^<]*)</$tag>".toRegex()
        return pattern.find(xml)?.groupValues?.get(1)
    }
    
    companion object {
        /** Фиктивный PolicyKey когда сервер не требует политик */
        const val NO_POLICY_KEY = "0"
    }
}

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
        val policyStatus: Int?,
        /** EAS-2: сервер прислал директиву удалённой очистки (MS-ASPROV 2.2.2.44). */
        val remoteWipeRequested: Boolean = false
    )
    
    /**
     * Поддерживает ли версия EAS элемент settings:DeviceInformation внутри Provision.
     * По MS-ASPROV он валиден только с EAS 14.1 (Exchange 2010 SP1+);
     * на 14.0 сервер может ответить Status=2 (protocol error) —
     * там информация об устройстве передаётся отдельной командой Settings.
     * EAS 12.x (Exchange 2007) — не поддерживает.
     */
    fun supportsDeviceInfo(): Boolean {
        val parts = easVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major > 14 || (major == 14 && minor >= 1)
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
        // Миграция на потоковый [EasXmlParser] (XmlPullParser) взамен regex/подстрок — устойчивее
        // к namespace, самозакрывающимся тегам и экранированию. Семантика сохранена и закреплена
        // EasProvisioningTest (в т.ч. РАЗНЫЕ Provision-level и Policy-level Status).

        // PolicyKey — лист внутри Policy.
        val policyKey = EasXmlParser.extractValue(responseXml, "PolicyKey")

        // Policy-level Status — Status ПЕРВОГО блока <Policy> (как прежний <Policy>(.*?)</Policy>).
        val policyStatus = EasXmlParser.collectElements(responseXml, "Policy")
            .firstOrNull()?.let { EasXmlParser.extractValue(it, "Status")?.toIntOrNull() }

        // Provision-level Status — верхний Status. По MS-ASPROV он идёт ПЕРВЫМ (до Policies),
        // т.е. это первый Status в документе; для валидных ответов Exchange 2007 SP2…2013 это
        // эквивалент прежнему «Status вне блока Policy, иначе первый Status вообще».
        val provisionStatus = EasXmlParser.extractValue(responseXml, "Status")?.toIntOrNull()

        // EAS-2: директива удалённой очистки — именно элемент <RemoteWipe>. hasElement сверяет имя
        // элемента целиком, поэтому <AccountOnlyRemoteWipe> НЕ срабатывает (как и прежняя regex).
        val remoteWipeRequested = EasXmlParser.hasElement(responseXml, "RemoteWipe")

        return ProvisionResponse(policyKey, provisionStatus, policyStatus, remoteWipeRequested)
    }

    /**
     * EAS-2: подтверждение директивы RemoteWipe (MS-ASPROV 3.2.5.1).
     * Клиент обязан ответить Provision-запросом со статусом применения очистки.
     * Status=1 — успешно применено. Отправляется ТОЛЬКО после фактической локальной очистки.
     */
    fun buildRemoteWipeAck(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<Provision xmlns="Provision">
    <RemoteWipe>
        <Status>1</Status>
    </RemoteWipe>
</Provision>""".trimIndent()
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
    
    companion object {
        /** Фиктивный PolicyKey когда сервер не требует политик */
        const val NO_POLICY_KEY = "0"
    }
}

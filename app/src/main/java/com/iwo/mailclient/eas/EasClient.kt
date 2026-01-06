package com.iwo.mailclient.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import android.util.Base64

/**
 * Exchange ActiveSync клиент
 * Поддерживает EAS 12.0, 12.1, 14.0, 14.1 для Exchange 2007+
 * Использует общий HttpClientProvider для предотвращения утечек памяти
 */
class EasClient(
    serverUrl: String,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val acceptAllCerts: Boolean = false,
    private val port: Int = 443,
    private val useHttps: Boolean = true,
    private val deviceIdSuffix: String = "", // Для стабильного deviceId и реального email
    initialPolicyKey: String? = null, // PolicyKey из предыдущей сессии
    private val certificatePath: String? = null // Путь к файлу сертификата сервера
) {
    private val wbxmlParser = WbxmlParser()
    private val client: OkHttpClient
    
    // Нормализуем URL - добавляем схему и порт
    private val normalizedServerUrl: String = normalizeUrl(serverUrl, port, useHttps)
    
    // Версия EAS - по умолчанию 12.1, но может быть изменена после OPTIONS
    private var easVersion = "12.1"
    // Приоритет версий (от новых к старым) - Exchange 2007+
    private val supportedVersions = listOf("14.1", "14.0", "12.1", "12.0")
    // Версии поддерживаемые сервером (заполняется после OPTIONS)
    private var serverSupportedVersions: List<String> = emptyList()
    // Флаг что версия уже определена
    private var versionDetected = false
    // Кэш ID папки заметок
    private var cachedNotesFolderId: String? = null
    // Кэш ID папки задач
    private var cachedTasksFolderId: String? = null
    // Кэш ID папки календаря
    private var cachedCalendarFolderId: String? = null
    // DeviceId должен быть стабильным для одного аккаунта
    private val deviceId = generateStableDeviceId(username, deviceIdSuffix)
    // DeviceType - как у Huawei
    private val deviceType = "Android"
    
    // PolicyKey для авторизованных запросов после Provision
    var policyKey: String? = initialPolicyKey
        private set

    // Результат создания папки
    data class FolderCreateResult(val serverId: String, val newSyncKey: String)
    
    /**
     * Устанавливает PolicyKey (например, из сохранённого аккаунта)
     */
    fun setPolicyKey(key: String?) {
        policyKey = key
    }
    
    /**
     * Определяет поддерживаемую версию EAS через OPTIONS запрос
     * Возвращает список поддерживаемых версий и выбирает лучшую
     */
    suspend fun detectEasVersion(): EasResult<String> {
        if (versionDetected && serverSupportedVersions.isNotEmpty()) {
            return EasResult.Success(easVersion)
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "$normalizedServerUrl/Microsoft-Server-ActiveSync"
                
                val request = Request.Builder()
                    .url(url)
                    .method("OPTIONS", null)
                    .header("Authorization", getAuthHeader())
                    .header("User-Agent", "Android/12-EAS-2.0")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.code == 200) {
                    val versionsHeader = response.header("MS-ASProtocolVersions") ?: ""
                    
                    if (versionsHeader.isNotEmpty()) {
                        serverSupportedVersions = versionsHeader.split(",").map { it.trim() }
                        val bestVersion = supportedVersions.firstOrNull { it in serverSupportedVersions }
                        
                        if (bestVersion != null) {
                            easVersion = bestVersion
                            versionDetected = true
                            EasResult.Success(easVersion)
                        } else {
                            easVersion = "12.1"
                            versionDetected = true
                            EasResult.Success(easVersion)
                        }
                    } else {
                        versionDetected = true
                        EasResult.Success(easVersion)
                    }
                } else if (response.code == 401) {
                    EasResult.Error("Ошибка авторизации (401)")
                } else {
                    versionDetected = true
                    EasResult.Success(easVersion)
                }
            } catch (_: Exception) {
                versionDetected = true
                EasResult.Success(easVersion)
            }
        }
    }
    
    /**
     * Возвращает информацию о сервере
     */
    fun getServerInfo(): Map<String, Any> {
        return mapOf(
            "easVersion" to easVersion,
            "serverVersions" to serverSupportedVersions,
            "versionDetected" to versionDetected
        )
    }
    
    init {
        // Используем общий HttpClientProvider для предотвращения утечек памяти
        // Каждый OkHttpClient создаёт connection pool и thread pool
        client = com.iwo.mailclient.network.HttpClientProvider.getClient(
            acceptAllCerts = acceptAllCerts,
            certificatePath = certificatePath
        )
    }
    
    companion object {
        // Предкомпилированные regex для производительности
        private val EMAIL_BRACKET_REGEX = "<([^>]+)>".toRegex()
        private val EWS_ITEM_ID_REGEX = """<t:ItemId Id="([^"]+)"""".toRegex()
        private val NOTES_CATEGORY_REGEX = "<notes:Category>(.*?)</notes:Category>".toRegex()
        private val CALENDAR_CATEGORY_REGEX = "<calendar:Category>(.*?)</calendar:Category>".toRegex()
        private val EWS_SUBJECT_REGEX = """<t:Subject>(.*?)</t:Subject>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_BODY_REGEX = """<t:Body[^>]*>(.*?)</t:Body>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_DATE_CREATED_REGEX = """<t:DateTimeCreated>(.*?)</t:DateTimeCreated>""".toRegex()
        private val EWS_EMAIL_ADDRESS_REGEX = """<t:EmailAddress>([^<]+)</t:EmailAddress>""".toRegex()
        private val EWS_TO_RECIPIENTS_REGEX = """<t:ToRecipients>(.*?)</t:ToRecipients>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_CC_RECIPIENTS_REGEX = """<t:CcRecipients>(.*?)</t:CcRecipients>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_MESSAGE_TEXT_REGEX = """<m:MessageText>(.*?)</m:MessageText>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_RESPONSE_CODE_REGEX = """<m:ResponseCode>(.*?)</m:ResponseCode>""".toRegex()
        
        // Дополнительные предкомпилированные regex
        private val MDN_DISPOSITION_REGEX = "Disposition-Notification-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE)
        private val MDN_RETURN_RECEIPT_REGEX = "Return-Receipt-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE)
        private val MDN_CONFIRM_READING_REGEX = "X-Confirm-Reading-To:\\s*<?([^>\\r\\n]+)>?".toRegex(RegexOption.IGNORE_CASE)
        private val BOUNDARY_REGEX = "boundary=\"?([^\"\\r\\n]+)\"?".toRegex(RegexOption.IGNORE_CASE)
        private val MOVE_RESPONSE_REGEX = "<Response>(.*?)</Response>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ITEM_OPS_GLOBAL_STATUS_REGEX = "<ItemOperations>.*?<Status>(\\d+)</Status>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ITEM_OPS_FETCH_STATUS_REGEX = "<Fetch>.*?<Status>(\\d+)</Status>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ITEM_OPS_DATA_REGEX = "<Data>(.*?)</Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ITEM_OPS_PROPS_DATA_REGEX = "<Properties>.*?<Data>(.*?)</Data>.*?</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val FOLDER_REGEX = "<Folder>(.*?)</Folder>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        // Кэш для extractValue regex паттернов (thread-safe)
        private val extractValueCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
        
        /**
         * Нормализует URL сервера - добавляет схему и порт
         */
        fun normalizeUrl(url: String, port: Int = 443, useHttps: Boolean = true): String {
            var trimmed = url.trim()
            
            // Если URL пустой - это ошибка конфигурации, но не падаем
            if (trimmed.isEmpty()) {
                android.util.Log.w("EasClient", "Empty server URL provided")
                return if (useHttps) "https://exchange.local:$port" else "http://exchange.local:$port"
            }
            
            // Убираем существующую схему если есть
            trimmed = trimmed.removePrefix("https://").removePrefix("http://")
            
            // Извлекаем только хост (без порта и пути)
            val hostOnly = trimmed
                .substringBefore("/")  // убираем путь
                .substringBefore(":")  // убираем порт если есть
                .trim()
            
            // Защита от пустого хоста
            if (hostOnly.isEmpty()) {
                android.util.Log.w("EasClient", "Empty host after URL parsing: $url")
                return if (useHttps) "https://exchange.local:$port" else "http://exchange.local:$port"
            }
            
            val scheme = if (useHttps) "https" else "http"
            return "$scheme://$hostOnly:$port"
        }
    }
    
    private fun generateStableDeviceId(username: String, suffix: String): String {
        // Генерируем СТАБИЛЬНЫЙ DeviceId на основе username
        // Это важно чтобы сервер не требовал Provision при каждом запросе
        val hash = (username + suffix).hashCode().toLong() and 0xFFFFFFFFL
        return "androidc${String.format("%010d", hash % 10000000000L)}"
    }
    
    @Suppress("unused")
    private fun generateDeviceId(): String {
        return "android${System.currentTimeMillis().toString(16)}"
    }
    
    private fun getAuthHeader(): String {
        val credentials = if (domain.isNotEmpty()) {
            "$domain\\$username:$password"
        } else {
            "$username:$password"
        }
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun buildUrl(command: String): String {
        val baseUrl = normalizedServerUrl.trimEnd('/')
        
        // Проверка что URL содержит схему
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            android.util.Log.e("EasClient", "Invalid baseUrl (no scheme): $baseUrl, normalizing...")
            val fixedUrl = if (useHttps) "https://$baseUrl" else "http://$baseUrl"
            return buildUrlInternal(fixedUrl, command)
        }
        
        return buildUrlInternal(baseUrl, command)
    }
    
    private fun buildUrlInternal(baseUrl: String, command: String): String {
        // User в URL должен быть с доменом (URL-encoded)
        val userParam = if (domain.isNotEmpty()) {
            java.net.URLEncoder.encode("$domain\\$username", "UTF-8")
        } else {
            username
        }
        // Используем /default.eas как в Huawei
        return "$baseUrl/Microsoft-Server-ActiveSync/default.eas?" +
            "Cmd=$command&" +
            "User=$userParam&" +
            "DeviceId=$deviceId&" +
            "DeviceType=$deviceType"
    }
    
    /**
     * Проверка подключения к серверу и определение версии EAS
     */
    suspend fun testConnection(): EasResult<String> {
        val versionResult = detectEasVersion()
        
        return when (versionResult) {
            is EasResult.Success -> {
                val info = buildString {
                    append("Подключение успешно!\n")
                    append("Версия EAS: $easVersion\n")
                    if (serverSupportedVersions.isNotEmpty()) {
                        append("Сервер поддерживает: ${serverSupportedVersions.joinToString(", ")}\n")
                    }
                    append("DeviceId: $deviceId")
                }
                EasResult.Success(info)
            }
            is EasResult.Error -> versionResult
        }
    }
    
    /**
     * Выбирает лучшую версию EAS из поддерживаемых сервером
     */
    private fun selectBestVersion(serverVersions: String) {
        // Сервер возвращает версии через запятую: "2.0,2.1,2.5,12.0,12.1"
        val versions = serverVersions.split(",").map { it.trim() }
        serverSupportedVersions = versions
        
        // Выбираем лучшую из наших поддерживаемых версий
        for (version in supportedVersions) {
            if (versions.contains(version)) {
                easVersion = version
                versionDetected = true
                return
            }
        }
        
        // Если ничего не нашли, оставляем 12.1 (для Exchange 2007)
        easVersion = "12.1"
        versionDetected = true
    }

    /**
     * Provisioning - получение политик безопасности от сервера
     * Требуется для EAS 12.0+ перед FolderSync
     */
    suspend fun provision(): EasResult<String> {
        val savedPolicyKey = policyKey
        policyKey = null
        
        // Фаза 1: Запрос политик
        val xml1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Provision xmlns="Provision">
                <Policies>
                    <Policy>
                        <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
                    </Policy>
                </Policies>
            </Provision>
        """.trimIndent()
        
        var tempPolicyKey: String? = null
        var provisionStatus: Int? = null
        
        val result1 = executeEasCommand("Provision", xml1) { responseXml ->
            tempPolicyKey = extractValue(responseXml, "PolicyKey")
            provisionStatus = extractValue(responseXml, "Status")?.toIntOrNull()
            tempPolicyKey
        }
        
        when (result1) {
            is EasResult.Success -> {
                if (tempPolicyKey == null) {
                    policyKey = savedPolicyKey
                    return EasResult.Error("PolicyKey not found in response")
                }
                // Status 1 = Success, 2 = Protocol error, 3 = General error
                if (provisionStatus != null && provisionStatus != 1) {
                    policyKey = savedPolicyKey
                    return EasResult.Error("Provision phase 1 failed with status: $provisionStatus")
                }
            }
            is EasResult.Error -> {
                policyKey = savedPolicyKey
                return result1
            }
        }
        
        // Фаза 2: Подтверждение принятия политик
        // Status = 1 означает "политики приняты"
        val xml2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Provision xmlns="Provision">
                <Policies>
                    <Policy>
                        <PolicyType>MS-EAS-Provisioning-WBXML</PolicyType>
                        <PolicyKey>$tempPolicyKey</PolicyKey>
                        <Status>1</Status>
                    </Policy>
                </Policies>
            </Provision>
        """.trimIndent()
        
        var finalKey: String? = null
        var finalStatus: Int? = null
        
        val result2 = executeEasCommand("Provision", xml2) { responseXml ->
            finalKey = extractValue(responseXml, "PolicyKey") ?: tempPolicyKey
            finalStatus = extractValue(responseXml, "Status")?.toIntOrNull()
            finalKey
        }
        
        when (result2) {
            is EasResult.Success -> {
                // Проверяем статус фазы 2
                // Status codes: 1=Success, 2=ProtocolError, 3=GeneralError, 4=DeviceNotProvisioned
                // 5=PolicyRefresh, 6=InvalidPolicyKey, 7=ExternallyManaged, 8=UnknownDeviceType
                if (finalStatus != null && finalStatus != 1) {
                    policyKey = savedPolicyKey
                    val statusDesc = when (finalStatus) {
                        2 -> "Protocol error"
                        3 -> "General error"
                        4 -> "Device not provisioned"
                        5 -> "Policy refresh required"
                        6 -> "Invalid policy key"
                        7 -> "Device externally managed"
                        8 -> "Unknown device type"
                        else -> "Unknown error"
                    }
                    return EasResult.Error("Provision failed: $statusDesc (Status: $finalStatus)")
                }
                policyKey = finalKey
            }
            is EasResult.Error -> {
                policyKey = savedPolicyKey
                return result2
            }
        }
        
        sendDeviceSettings()
        // Возвращаем finalKey, tempPolicyKey или ошибку если оба null
        val resultKey = finalKey ?: tempPolicyKey
        return if (resultKey != null) {
            EasResult.Success(resultKey)
        } else {
            EasResult.Error("Provision failed: no policy key received")
        }
    }
    
    /**
     * Отправляет информацию об устройстве (Settings command)
     * Требуется после Provision для Exchange 2007
     */
    private suspend fun sendDeviceSettings(): EasResult<Unit> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
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
            </Settings>
        """.trimIndent()
        
        return executeEasCommand("Settings", xml) { }
    }
    
    /**
     * Получает одно письмо из папки для верификации email (без сохранения в БД)
     * @param folderId ID папки (Отправленные или Входящие)
     * @return EasEmail или null если папка пустая
     */
    suspend fun fetchOneEmailForVerification(folderId: String): EasResult<EasEmail?> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Шаг 1: Получаем начальный SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$folderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(null) // Не удалось получить SyncKey
        }
        
        // Шаг 2: Запрашиваем 1 письмо
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$folderId</CollectionId>
                        <DeletesAsMoves>1</DeletesAsMoves>
                        <GetChanges/>
                        <WindowSize>1</WindowSize>
                        <Options>
                            <FilterType>0</FilterType>
                            <BodyPreference xmlns="AirSyncBase">
                                <Type>1</Type>
                                <TruncationSize>0</TruncationSize>
                            </BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            val response = parseSyncResponse(responseXml)
            response.emails.firstOrNull()
        }
    }
    
    /**
     * Синхронизация папок (FolderSync)
     * Для Exchange 2007 сначала делаем Provision если нет PolicyKey
     */
    suspend fun folderSync(syncKey: String = "0"): EasResult<FolderSyncResponse> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        if (syncKey == "0" || policyKey == null) {
            if (syncKey == "0") {
                policyKey = null
            }
            
            when (val provResult = provision()) {
                is EasResult.Success -> { }
                is EasResult.Error -> {
                    return EasResult.Error("Provision failed: ${provResult.message}")
                }
            }
        }
        
        val result = doFolderSync(syncKey)
        
        if (result is EasResult.Success && result.data.status == 1) {
            return result
        }
        
        val status = if (result is EasResult.Success) result.data.status else -1
        return EasResult.Error("Не удалось синхронизировать папки. Status: $status. DeviceId: $deviceId")
    }
    
    private suspend fun doFolderSync(syncKey: String): EasResult<FolderSyncResponse> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderSync xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
            </FolderSync>
        """.trimIndent()
        
        val skipPolicyKey = (policyKey == null)
        
        return executeEasCommand("FolderSync", xml, skipPolicyKey = skipPolicyKey) { responseXml ->
            parseFolderSyncResponse(responseXml)
        }
    }
    
    /**
     * Создание новой папки (FolderCreate)
     */
    suspend fun createFolder(
        displayName: String,
        parentId: String = "0",
        folderType: Int = 12,
        syncKey: String
    ): EasResult<FolderCreateResult> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderCreate xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
                <ParentId>$parentId</ParentId>
                <DisplayName>$displayName</DisplayName>
                <Type>$folderType</Type>
            </FolderCreate>
        """.trimIndent()
        
        return executeEasCommand("FolderCreate", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val serverId = extractValue(responseXml, "ServerId") ?: ""
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: syncKey
            
            if (status == 1 && serverId.isNotEmpty()) {
                FolderCreateResult(serverId, newSyncKey)
            } else {
                val errorMsg = when (status) {
                    2 -> "Папка с таким именем уже существует"
                    3 -> "Системную папку нельзя создать"
                    5 -> "Родительская папка не найдена"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey, попробуйте синхронизировать папки"
                    10 -> "Неверный формат запроса"
                    11 -> "Неизвестная ошибка"
                    else -> "Ошибка создания папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Удаление папки с сервера (FolderDelete)
     */
    suspend fun deleteFolder(
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderDelete xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
                <ServerId>$serverId</ServerId>
            </FolderDelete>
        """.trimIndent()
        
        return executeEasCommand("FolderDelete", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: syncKey
            
            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    3 -> "Системную папку нельзя удалить"
                    4 -> "Папка не существует"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey"
                    else -> "Ошибка удаления папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Переименование папки на сервере (FolderUpdate)
     */
    suspend fun renameFolder(
        serverId: String,
        newDisplayName: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FolderUpdate xmlns="FolderHierarchy">
                <SyncKey>$syncKey</SyncKey>
                <ServerId>$serverId</ServerId>
                <ParentId>0</ParentId>
                <DisplayName>$newDisplayName</DisplayName>
            </FolderUpdate>
        """.trimIndent()
        
        return executeEasCommand("FolderUpdate", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: syncKey
            
            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    2 -> "Папка с таким именем уже существует"
                    3 -> "Системную папку нельзя переименовать"
                    4 -> "Папка не существует"
                    5 -> "Родительская папка не существует"
                    6 -> "Ошибка сервера"
                    9 -> "Неверный SyncKey"
                    else -> "Ошибка переименования папки: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Перемещение писем между папками (MoveItems)
     */
    suspend fun moveItems(
        items: List<Pair<String, String>>,
        dstFolderId: String
    ): EasResult<Map<String, String>> {
        val movesXml = items.joinToString("") { (srcMsgId, srcFldId) ->
            "<Move><SrcMsgId>$srcMsgId</SrcMsgId><SrcFldId>$srcFldId</SrcFldId><DstFldId>$dstFolderId</DstFldId></Move>"
        }
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MoveItems xmlns="Move">
                $movesXml
            </MoveItems>
        """.trimIndent()
        
        return executeEasCommand("MoveItems", xml) { responseXml ->
            val results = mutableMapOf<String, String>()
            MOVE_RESPONSE_REGEX.findAll(responseXml).forEach { match ->
                val responseContent = match.groupValues[1]
                val srcMsgId = extractValue(responseContent, "SrcMsgId") ?: ""
                val status = extractValue(responseContent, "Status")?.toIntOrNull() ?: 0
                val dstMsgId = extractValue(responseContent, "DstMsgId") ?: ""
                
                // Status 3 = Success (STATUS_CODE_SUCCESS)
                // Status 1 = No source folder, 2 = No dest folder, 4 = Same folder
                if (status == 3 && dstMsgId.isNotEmpty()) {
                    results[srcMsgId] = dstMsgId
                }
            }
            results
        }
    }
    
    /**
     * Синхронизация писем (Sync)
     */
    suspend fun sync(
        collectionId: String,
        syncKey: String = "0",
        windowSize: Int = 100
    ): EasResult<SyncResponse> {
        val xml = if (syncKey == "0") {
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$collectionId</CollectionId>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        } else {
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$collectionId</CollectionId>
                            <DeletesAsMoves>1</DeletesAsMoves>
                            <GetChanges/>
                            <WindowSize>$windowSize</WindowSize>
                            <Options>
                                <FilterType>0</FilterType>
                                <BodyPreference xmlns="AirSyncBase">
                                    <Type>2</Type>
                                    <TruncationSize>51200</TruncationSize>
                                </BodyPreference>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        }
        
        val result = executeEasCommand("Sync", xml) { responseXml ->
            parseSyncResponse(responseXml)
        }
        
        if (result is EasResult.Error && result.message.contains("Пустой ответ")) {
            return EasResult.Success(SyncResponse(
                syncKey = syncKey,
                status = 1,
                moreAvailable = false,
                emails = emptyList()
            ))
        }
        
        if (result is EasResult.Error && result.message.contains("449")) {
            when (val provResult = provision()) {
                is EasResult.Success -> {
                    val retryResult = executeEasCommand("Sync", xml) { responseXml ->
                        parseSyncResponse(responseXml)
                    }
                    if (retryResult is EasResult.Success) {
                        return retryResult
                    }
                }
                is EasResult.Error -> { }
            }
        }
        
        return result
    }
    
    /**
     * Отправка письма (SendMail)
     */
    suspend fun sendMail(
        to: String,
        subject: String,
        body: String,
        cc: String = "",
        bcc: String = "",
        importance: Int = 1,
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false
    ): EasResult<Boolean> {
        // Проверка получателя
        if (to.isBlank() || !to.contains("@")) {
            return EasResult.Error("Неверный адрес получателя: $to")
        }
        
        if (!versionDetected) {
            detectEasVersion()
        }
        
        return withContext(Dispatchers.IO) {
        try {
            val mimeBytes = buildMimeMessageBytes(to, subject, body, cc, bcc, requestReadReceipt, requestDeliveryReceipt, importance)
            // Для EAS 14+ SaveInSent в URL, для EAS 12.x тоже поддерживается
            val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
            val url = buildUrl("SendMail") + "&SaveInSent=T"
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val request = requestBuilder.build()
            val response = executeRequest(request)
            
            if (response.isSuccessful || response.code == 200) {
                // Для SendMail успешный ответ — пустое тело
                // Если есть тело, это может быть WBXML с ошибкой
                val responseBody = response.body?.bytes()
                if (responseBody != null && responseBody.isNotEmpty()) {
                    // Проверяем на WBXML ошибку (первый байт 0x03 = WBXML)
                    if (responseBody[0] == 0x03.toByte()) {
                        // Это WBXML ответ, возможно ошибка
                        val xml = wbxmlParser.parse(responseBody)
                        val status = extractValue(xml, "Status")
                        if (status != null && status != "1") {
                            return@withContext EasResult.Error("Ошибка отправки: Status=$status")
                        }
                    }
                }
                EasResult.Success(true)
            } else {
                val errorBody = response.body?.string() ?: ""
                
                if (response.code == 449) {
                    when (val provResult = provision()) {
                        is EasResult.Success -> {
                            val retryRequest = Request.Builder()
                                .url(url)
                                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                                .header("Authorization", getAuthHeader())
                                .header("MS-ASProtocolVersion", easVersion)
                                .header("Content-Type", contentType)
                                .header("User-Agent", "Android/12-EAS-2.0")
                                .apply { policyKey?.let { header("X-MS-PolicyKey", it) } }
                                .build()
                            
                            val retryResponse = executeRequest(retryRequest)
                            if (retryResponse.isSuccessful) {
                                return@withContext EasResult.Success(true)
                            }
                        }
                        is EasResult.Error -> {}
                    }
                }
                
                EasResult.Error("Ошибка отправки: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка отправки: ${e.message}")
        }
        }
    }
    
    private fun buildMimeMessageBytes(to: String, subject: String, body: String, cc: String, bcc: String, requestReadReceipt: Boolean = false, requestDeliveryReceipt: Boolean = false, importance: Int = 1): ByteArray {
        // Используем реальный email из deviceIdSuffix (передаётся account.email)
        // Fallback на username@domain если deviceIdSuffix не содержит @
        val fromEmail = if (deviceIdSuffix.contains("@")) {
            deviceIdSuffix
        } else if (domain.isNotEmpty() && !username.contains("@")) {
            "$username@$domain"
        } else {
            username
        }
        
        // Генерируем Message-ID
        val messageId = "<${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
        
        // Форматируем дату по RFC 2822
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        
        val sb = StringBuilder()
        sb.append("Date: $date\r\n")
        sb.append("From: $fromEmail\r\n")
        sb.append("To: $to\r\n")
        if (cc.isNotEmpty()) {
            sb.append("Cc: $cc\r\n")
        }
        if (bcc.isNotEmpty()) {
            sb.append("Bcc: $bcc\r\n")
        }
        sb.append("Message-ID: $messageId\r\n")
        // Кодируем тему в UTF-8 Base64 для поддержки кириллицы
        val encodedSubject = "=?UTF-8?B?${Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        sb.append("Subject: $encodedSubject\r\n")
        // Приоритет письма (importance: 0=low, 1=normal, 2=high)
        if (importance == 2) {
            sb.append("X-Priority: 1\r\n")
            sb.append("Importance: high\r\n")
            sb.append("X-MSMail-Priority: High\r\n")
        } else if (importance == 0) {
            sb.append("X-Priority: 5\r\n")
            sb.append("Importance: low\r\n")
            sb.append("X-MSMail-Priority: Low\r\n")
        }
        // Запрос отчёта о прочтении (MDN)
        if (requestReadReceipt) {
            sb.append("Disposition-Notification-To: $fromEmail\r\n")
            sb.append("Return-Receipt-To: $fromEmail\r\n")
            sb.append("X-Confirm-Reading-To: $fromEmail\r\n")
        }
        // Запрос отчёта о доставке (DSN)
        if (requestDeliveryReceipt) {
            sb.append("X-MS-Exchange-Organization-DeliveryReportRequested: true\r\n")
            sb.append("Return-Path: $fromEmail\r\n")
        }
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(body)
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Строит MIME сообщение с вложениями (multipart/mixed)
     */
    private fun buildMimeWithAttachments(
        to: String, 
        subject: String, 
        body: String, 
        cc: String,
        bcc: String,
        attachments: List<Triple<String, String, ByteArray>>, // name, mimeType, data
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false,
        importance: Int = 1
    ): ByteArray {
        // Используем реальный email из deviceIdSuffix (передаётся account.email)
        val fromEmail = if (deviceIdSuffix.contains("@")) {
            deviceIdSuffix
        } else if (domain.isNotEmpty() && !username.contains("@")) {
            "$username@$domain"
        } else {
            username
        }
        
        val messageId = "<${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        val boundary = "----=_Part_${System.currentTimeMillis()}_${System.nanoTime()}"
        
        val sb = StringBuilder()
        sb.append("Date: $date\r\n")
        sb.append("From: $fromEmail\r\n")
        sb.append("To: $to\r\n")
        if (cc.isNotEmpty()) {
            sb.append("Cc: $cc\r\n")
        }
        if (bcc.isNotEmpty()) {
            sb.append("Bcc: $bcc\r\n")
        }
        sb.append("Message-ID: $messageId\r\n")
        val encodedSubject = "=?UTF-8?B?${Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        sb.append("Subject: $encodedSubject\r\n")
        // Приоритет письма (importance: 0=low, 1=normal, 2=high)
        if (importance == 2) {
            sb.append("X-Priority: 1\r\n")
            sb.append("Importance: high\r\n")
            sb.append("X-MSMail-Priority: High\r\n")
        } else if (importance == 0) {
            sb.append("X-Priority: 5\r\n")
            sb.append("Importance: low\r\n")
            sb.append("X-MSMail-Priority: Low\r\n")
        }
        // Запрос отчёта о прочтении (MDN)
        if (requestReadReceipt) {
            sb.append("Disposition-Notification-To: $fromEmail\r\n")
            sb.append("Return-Receipt-To: $fromEmail\r\n")
            sb.append("X-Confirm-Reading-To: $fromEmail\r\n")
        }
        // Запрос отчёта о доставке (DSN)
        if (requestDeliveryReceipt) {
            sb.append("X-MS-Exchange-Organization-DeliveryReportRequested: true\r\n")
            sb.append("Return-Path: $fromEmail\r\n")
        }
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n")
        sb.append("\r\n")
        
        // Текстовая часть
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append(body)
        sb.append("\r\n")
        
        // Вложения
        for ((name, mimeType, data) in attachments) {
            sb.append("--$boundary\r\n")
            val encodedName = "=?UTF-8?B?${Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
            sb.append("Content-Type: $mimeType; name=\"$encodedName\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"$encodedName\"\r\n")
            sb.append("\r\n")
            sb.append(Base64.encodeToString(data, Base64.DEFAULT))
            sb.append("\r\n")
        }
        
        sb.append("--$boundary--\r\n")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Результат загрузки тела письма с MDN информацией
     */
    data class EmailBodyResult(
        val body: String,
        val mdnRequestedBy: String? = null // Email для отправки MDN
    )
    
    /**
     * Загрузка полного тела письма с парсингом MDN заголовка
     * Использует withTimeoutOrNull для ограничения общего времени выполнения
     */
    suspend fun fetchEmailBodyWithMdn(collectionId: String, serverId: String): EasResult<EmailBodyResult> {
        // Общий timeout на все попытки загрузки тела (25 сек, чтобы уложиться в 30 сек внешний timeout)
        return withTimeoutOrNull(25_000L) {
            // Сначала пробуем получить полный MIME (Type=4) для парсинга заголовков
            val mimeXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ItemOperations xmlns="ItemOperations">
                    <Fetch>
                        <Store>Mailbox</Store>
                        <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                        <ServerId xmlns="AirSync">$serverId</ServerId>
                        <Options>
                            <BodyPreference xmlns="AirSyncBase">
                                <Type>4</Type>
                                <TruncationSize>1048576</TruncationSize>
                            </BodyPreference>
                        </Options>
                    </Fetch>
                </ItemOperations>
            """.trimIndent()
            
            val mimeResult = executeEasCommand("ItemOperations", mimeXml) { responseXml ->
                // Проверяем Status=8 (OBJECT_NOT_FOUND)
                val status = extractValue(responseXml, "Status")?.toIntOrNull()
                if (status == 8) {
                    return@executeEasCommand "OBJECT_NOT_FOUND"
                }
                extractValue(responseXml, "Data") ?: ""
            }
            
            // Проверяем на удалённое письмо
            if (mimeResult is EasResult.Success && mimeResult.data == "OBJECT_NOT_FOUND") {
                return@withTimeoutOrNull EasResult.Error("OBJECT_NOT_FOUND")
            }
            
            var mdnRequestedBy: String? = null
            var bodyContent = ""
            
            if (mimeResult is EasResult.Success && mimeResult.data.isNotEmpty()) {
                val mimeData = mimeResult.data
                
                // Парсим MDN заголовок из MIME
                mdnRequestedBy = parseMdnHeader(mimeData)
                
                // Извлекаем тело из MIME
                bodyContent = extractBodyFromMime(mimeData)
            }
            
            // Если MIME не сработал или тело пустое - fallback на обычный запрос
            if (bodyContent.isEmpty()) {
                // Пробуем HTML (Type=2)
                val htmlXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ItemOperations xmlns="ItemOperations">
                        <Fetch>
                            <Store>Mailbox</Store>
                            <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                            <ServerId xmlns="AirSync">$serverId</ServerId>
                            <Options>
                                <BodyPreference xmlns="AirSyncBase">
                                    <Type>2</Type>
                                    <TruncationSize>512000</TruncationSize>
                                </BodyPreference>
                            </Options>
                        </Fetch>
                    </ItemOperations>
                """.trimIndent()
                
                val htmlResult = executeEasCommand("ItemOperations", htmlXml) { responseXml ->
                    val status = extractValue(responseXml, "Status")?.toIntOrNull()
                    if (status == 8) {
                        return@executeEasCommand "OBJECT_NOT_FOUND"
                    }
                    extractValue(responseXml, "Data") 
                        ?: extractValue(responseXml, "Body")
                        ?: ""
                }
                
                if (htmlResult is EasResult.Success && htmlResult.data == "OBJECT_NOT_FOUND") {
                    return@withTimeoutOrNull EasResult.Error("OBJECT_NOT_FOUND")
                }
                
                if (htmlResult is EasResult.Success && htmlResult.data.isNotBlank()) {
                    bodyContent = htmlResult.data
                }
            }
            
            // Если HTML не сработал - пробуем plain text (Type=1)
            if (bodyContent.isEmpty()) {
                val plainXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ItemOperations xmlns="ItemOperations">
                        <Fetch>
                            <Store>Mailbox</Store>
                            <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                            <ServerId xmlns="AirSync">$serverId</ServerId>
                            <Options>
                                <BodyPreference xmlns="AirSyncBase">
                                    <Type>1</Type>
                                    <TruncationSize>512000</TruncationSize>
                                </BodyPreference>
                            </Options>
                        </Fetch>
                    </ItemOperations>
                """.trimIndent()
                
                val plainResult = executeEasCommand("ItemOperations", plainXml) { responseXml ->
                    val status = extractValue(responseXml, "Status")?.toIntOrNull()
                    if (status == 8) {
                        return@executeEasCommand "OBJECT_NOT_FOUND"
                    }
                    extractValue(responseXml, "Data") 
                        ?: extractValue(responseXml, "Body")
                        ?: ""
                }
                
                if (plainResult is EasResult.Success && plainResult.data == "OBJECT_NOT_FOUND") {
                    return@withTimeoutOrNull EasResult.Error("OBJECT_NOT_FOUND")
                }
                
                if (plainResult is EasResult.Success) {
                    bodyContent = plainResult.data
                } else if (plainResult is EasResult.Error) {
                    return@withTimeoutOrNull EasResult.Error(plainResult.message)
                }
            }
            
            EasResult.Success(EmailBodyResult(bodyContent, mdnRequestedBy))
        } ?: EasResult.Error("Timeout loading email body")
    }
    
    /**
     * Парсит заголовок Disposition-Notification-To из MIME данных
     */
    private fun parseMdnHeader(mimeData: String): String? {
        // Ищем заголовок в разных вариантах написания
        val patterns = listOf(
            MDN_DISPOSITION_REGEX,
            MDN_RETURN_RECEIPT_REGEX,
            MDN_CONFIRM_READING_REGEX
        )
        
        for (pattern in patterns) {
            val match = pattern.find(mimeData)
            if (match != null) {
                val email = match.groupValues[1].trim()
                // Извлекаем email если в формате "Name <email>"
                val emailMatch = EMAIL_BRACKET_REGEX.find(email)
                return emailMatch?.groupValues?.get(1) ?: email
            }
        }
        
        return null
    }
    
    /**
     * Извлекает тело письма из MIME данных
     */
    private fun extractBodyFromMime(mimeData: String): String {
        // Если это multipart - ищем text/html или text/plain часть
        if (mimeData.contains("Content-Type:", ignoreCase = true)) {
            // Ищем boundary
            val boundaryMatch = BOUNDARY_REGEX.find(mimeData)
            
            if (boundaryMatch != null) {
                val boundary = boundaryMatch.groupValues[1]
                val parts = mimeData.split("--$boundary")
                
                // Ищем HTML часть
                for (part in parts) {
                    if (part.contains("Content-Type: text/html", ignoreCase = true) ||
                        part.contains("Content-Type:text/html", ignoreCase = true)) {
                        // Извлекаем контент после пустой строки
                        val contentStart = part.indexOf("\r\n\r\n")
                        if (contentStart != -1) {
                            var content = part.substring(contentStart + 4).trim()
                            // Убираем закрывающий boundary если есть
                            if (content.endsWith("--")) {
                                content = content.dropLast(2).trim()
                            }
                            // Декодируем если base64
                            if (part.contains("Content-Transfer-Encoding: base64", ignoreCase = true)) {
                                try {
                                    content = String(Base64.decode(content.replace("\r\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: Exception) {}
                            }
                            // Декодируем quoted-printable
                            if (part.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                                content = decodeQuotedPrintable(content)
                            }
                            if (content.isNotBlank()) return content
                        }
                    }
                }
                
                // Fallback на text/plain
                for (part in parts) {
                    if (part.contains("Content-Type: text/plain", ignoreCase = true) ||
                        part.contains("Content-Type:text/plain", ignoreCase = true)) {
                        val contentStart = part.indexOf("\r\n\r\n")
                        if (contentStart != -1) {
                            var content = part.substring(contentStart + 4).trim()
                            if (content.endsWith("--")) {
                                content = content.dropLast(2).trim()
                            }
                            if (part.contains("Content-Transfer-Encoding: base64", ignoreCase = true)) {
                                try {
                                    content = String(Base64.decode(content.replace("\r\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: Exception) {}
                            }
                            if (part.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                                content = decodeQuotedPrintable(content)
                            }
                            if (content.isNotBlank()) return content
                        }
                    }
                }
            }
            
            // Не multipart - просто извлекаем тело после заголовков
            val bodyStart = mimeData.indexOf("\r\n\r\n")
            if (bodyStart != -1) {
                return mimeData.substring(bodyStart + 4).trim()
            }
        }
        
        // Возвращаем как есть если не удалось распарсить
        return mimeData
    }
    
    /**
     * Декодирует quoted-printable с корректной поддержкой UTF-8
     */
    private fun decodeQuotedPrintable(input: String): String {
        val text = input.replace("=\r\n", "").replace("=\n", "") // Soft line breaks
        val bytes = mutableListOf<Byte>()
        var i = 0
        
        while (i < text.length) {
            val c = text[i]
            if (c == '=' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3)
                try {
                    val byte = hex.toInt(16).toByte()
                    bytes.add(byte)
                    i += 3
                    continue
                } catch (_: Exception) {}
            }
            // Обычный ASCII символ - добавляем как байт
            bytes.add(c.code.toByte())
            i++
        }
        
        // Декодируем байты как UTF-8
        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            // Fallback на ISO-8859-1 если UTF-8 не сработал
            String(bytes.toByteArray(), Charsets.ISO_8859_1)
        }
    }
    
    /**
     * Загрузка полного тела письма (ленивая загрузка)
     * @deprecated Используйте fetchEmailBodyWithMdn для получения MDN информации
     */
    suspend fun fetchEmailBody(collectionId: String, serverId: String): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                    <ServerId xmlns="AirSync">$serverId</ServerId>
                    <Options>
                        <BodyPreference xmlns="AirSyncBase">
                            <Type>2</Type>
                            <TruncationSize>512000</TruncationSize>
                        </BodyPreference>
                    </Options>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        return executeEasCommand("ItemOperations", xml) { responseXml ->
            val body = extractValue(responseXml, "Data") 
                ?: extractValue(responseXml, "Body")
                ?: ""
            body
        }
    }
    
    /**
     * Отправка письма с вложениями
     */
    suspend fun sendMailWithAttachments(
        to: String,
        subject: String,
        body: String,
        cc: String = "",
        bcc: String = "",
        attachments: List<Triple<String, String, ByteArray>>,
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false,
        importance: Int = 1
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        val totalSize = attachments.sumOf { it.third.size }
        val maxAttachmentSize = 7 * 1024 * 1024
        if (totalSize > maxAttachmentSize) {
            val sizeMB = totalSize / 1024 / 1024
            return@withContext EasResult.Error("Размер вложений ($sizeMB МБ) превышает лимит сервера (7 МБ)")
        }
        
        try {
            val mimeBytes = buildMimeWithAttachments(to, subject, body, cc, bcc, attachments, requestReadReceipt, requestDeliveryReceipt, importance)
            
            val maxMimeSize = 10 * 1024 * 1024
            if (mimeBytes.size > maxMimeSize) {
                val sizeMB = mimeBytes.size / 1024 / 1024
                return@withContext EasResult.Error("Размер письма ($sizeMB МБ) превышает лимит сервера (10 МБ)")
            }
            
            val url = buildUrl("SendMail") + "&SaveInSent=T"
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val response = executeRequest(requestBuilder.build())
            
            if (response.isSuccessful || response.code == 200) {
                EasResult.Success(true)
            } else {
                val errorMsg = when (response.code) {
                    500 -> "Сервер отклонил письмо. Возможно, размер вложений превышает лимит сервера."
                    413 -> "Размер письма слишком большой для сервера"
                    else -> "Ошибка отправки: HTTP ${response.code}"
                }
                EasResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка отправки: ${e.message}")
        }
    }
    
    /**
     * Сохранение черновика в папку Drafts через Sync Add
     * Использует формат Email namespace для Exchange 2007+
     * 
     * ВАЖНО: EAS официально НЕ поддерживает Sync Add для Email!
     * Эта функция оставлена для экспериментов - некоторые серверы могут принять.
     * В текущей версии приложения черновики сохраняются только локально.
     */
    @Suppress("unused")
    suspend fun saveDraft(
        draftsFolderId: String,
        syncKey: String,
        to: String,
        subject: String,
        body: String,
        cc: String = ""
    ): EasResult<String> {
        // Сначала получаем актуальный SyncKey если передан "0"
        var currentSyncKey = syncKey
        if (currentSyncKey == "0" || currentSyncKey.isEmpty()) {
            val initXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>0</SyncKey>
                            <CollectionId>$draftsFolderId</CollectionId>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
            
            val initResult = executeEasCommand("Sync", initXml) { responseXml ->
                extractValue(responseXml, "SyncKey") ?: "0"
            }
            
            when (initResult) {
                is EasResult.Success -> currentSyncKey = initResult.data
                is EasResult.Error -> return EasResult.Error("Не удалось получить SyncKey: ${initResult.message}")
            }
            
            if (currentSyncKey == "0") {
                return EasResult.Error("Не удалось инициализировать синхронизацию папки черновиков")
            }
        }
        
        val clientId = "draft_${System.currentTimeMillis()}"
        
        // Формируем дату в формате ISO 8601
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val dateReceived = dateFormat.format(java.util.Date())
        
        // Экранируем XML спецсимволы
        val escapedTo = escapeXml(to)
        val escapedCc = escapeXml(cc)
        val escapedSubject = escapeXml(subject)
        val escapedBody = escapeXml(body)
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:email="Email" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$currentSyncKey</SyncKey>
                        <CollectionId>$draftsFolderId</CollectionId>
                        <Commands>
                            <Add>
                                <ClientId>$clientId</ClientId>
                                <ApplicationData>
                                    <email:To>$escapedTo</email:To>
                                    <email:Cc>$escapedCc</email:Cc>
                                    <email:Subject>$escapedSubject</email:Subject>
                                    <email:DateReceived>$dateReceived</email:DateReceived>
                                    <email:Read>1</email:Read>
                                    <email:Flag/>
                                    <airsyncbase:Body>
                                        <airsyncbase:Type>1</airsyncbase:Type>
                                        <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                                    </airsyncbase:Body>
                                </ApplicationData>
                            </Add>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: currentSyncKey
            
            if (status == 1) {
                newSyncKey
            } else {
                val errorMsg = when (status) {
                    3 -> "Неверный SyncKey, попробуйте ещё раз"
                    4 -> "Ошибка протокола"
                    5 -> "Ошибка сервера"
                    6 -> "Ошибка клиента"
                    7 -> "Конфликт"
                    8 -> "Объект не найден"
                    9 -> "Нет места"
                    else -> "Ошибка сохранения черновика: status=$status"
                }
                throw Exception(errorMsg)
            }
        }
    }
    
    /**
     * Экранирует XML спецсимволы
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    /**
     * Отправка отчёта о прочтении (MDN - Message Disposition Notification)
     * @param to email получателя отчёта (из заголовка Disposition-Notification-To)
     * @param originalSubject тема оригинального письма
     * @param originalMessageId Message-ID оригинального письма (опционально)
     */
    suspend fun sendMdn(
        to: String,
        originalSubject: String,
        originalMessageId: String? = null
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val mimeBytes = buildMdnMessage(to, originalSubject, originalMessageId)
            val url = buildUrl("SendMail") + "&SaveInSent=F" // Не сохраняем MDN в Отправленные
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val response = executeRequest(requestBuilder.build())
            
            if (response.isSuccessful || response.code == 200) {
                EasResult.Success(true)
            } else {
                EasResult.Error("Ошибка отправки MDN: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка отправки MDN: ${e.message}")
        }
    }
    
    /**
     * Строит MDN сообщение (multipart/report)
     */
    private fun buildMdnMessage(to: String, originalSubject: String, originalMessageId: String?): ByteArray {
        // Используем реальный email из deviceIdSuffix (передаётся account.email)
        val fromEmail = if (deviceIdSuffix.contains("@")) {
            deviceIdSuffix
        } else if (domain.isNotEmpty() && !username.contains("@")) {
            "$username@$domain"
        } else {
            username
        }
        
        val messageId = "<mdn.${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        val boundary = "----=_MDN_${System.currentTimeMillis()}"
        
        val sb = StringBuilder()
        sb.append("Date: $date\r\n")
        sb.append("From: $fromEmail\r\n")
        sb.append("To: $to\r\n")
        sb.append("Message-ID: $messageId\r\n")
        if (originalMessageId != null) {
            sb.append("In-Reply-To: $originalMessageId\r\n")
            sb.append("References: $originalMessageId\r\n")
        }
        val encodedSubject = "=?UTF-8?B?${Base64.encodeToString("Read: $originalSubject".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
        sb.append("Subject: $encodedSubject\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/report; report-type=disposition-notification; boundary=\"$boundary\"\r\n")
        sb.append("\r\n")
        
        // Часть 1: Человекочитаемое сообщение
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n")
        sb.append("Ваше сообщение было прочитано.\r\n")
        sb.append("Your message has been read.\r\n")
        sb.append("\r\n")
        
        // Часть 2: MDN (machine-readable)
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: message/disposition-notification\r\n")
        sb.append("\r\n")
        sb.append("Reporting-UA: iwo Mail Client; Android\r\n")
        sb.append("Final-Recipient: rfc822;$fromEmail\r\n")
        if (originalMessageId != null) {
            sb.append("Original-Message-ID: $originalMessageId\r\n")
        }
        sb.append("Disposition: manual-action/MDN-sent-manually; displayed\r\n")
        sb.append("\r\n")
        
        sb.append("--$boundary--\r\n")
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    /**
     * Скачивание вложения
     */
    suspend fun downloadAttachment(fileReference: String, collectionId: String? = null, serverId: String? = null): EasResult<ByteArray> {
        val itemOpsResult = downloadViaItemOperations(fileReference)
        if (itemOpsResult is EasResult.Success) {
            return itemOpsResult
        }
        
        if (collectionId != null && serverId != null) {
            val fetchResult = downloadViaItemOperationsFetchEmail(collectionId, serverId, fileReference)
            if (fetchResult is EasResult.Success) {
                return fetchResult
            }
        }
        
        val getAttResult = downloadViaGetAttachment(fileReference)
        if (getAttResult is EasResult.Success) {
            return getAttResult
        }
        
        // Если все методы не работают - возвращаем понятную ошибку
        return EasResult.Error("Сервер не поддерживает скачивание вложений через EAS.\n\nВозможные причины:\n• Политика безопасности запрещает скачивание\n• Вложение удалено с сервера\n\nПопробуйте открыть письмо в веб-интерфейсе OWA.")
    }
    
    /**
     * Альтернативный метод - Fetch всего письма и извлечение вложения
     */
    private suspend fun downloadViaItemOperationsFetchEmail(
        collectionId: String, 
        serverId: String,
        fileReference: String
    ): EasResult<ByteArray> {
        // Извлекаем индекс вложения из FileReference (формат: FolderId:ItemId:AttachmentIndex)
        val parts = fileReference.replace("%3a", ":").replace("%3A", ":").split(":")
        val attachmentIndex = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                    <ServerId xmlns="AirSync">$serverId</ServerId>
                    <Options>
                        <BodyPreference xmlns="AirSyncBase">
                            <Type>4</Type>
                        </BodyPreference>
                    </Options>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        val result = doItemOperationsFetch(xml)
        
        // Если получили данные, это может быть MIME письма с вложениями
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            // TODO: Парсить MIME и извлекать вложение по индексу
            // Пока возвращаем как есть
            return result
        }
        
        return result
    }
    
    private suspend fun downloadViaItemOperations(fileReference: String): EasResult<ByteArray> {
        // Пробуем оба варианта - декодированный и как есть
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) {
            fileReference
        }
        
        // Сначала пробуем с декодированным FileReference
        var result = tryItemOperations(decodedRef)
        if (result is EasResult.Success) {
            return result
        }
        
        // Если не сработало и FileReference был закодирован, пробуем как есть
        if (decodedRef != fileReference) {
            result = tryItemOperations(fileReference)
            if (result is EasResult.Success) {
                return result
            }
        }
        
        return result
    }
    
    private suspend fun tryItemOperations(fileRef: String): EasResult<ByteArray> {
        // Для Exchange 2007 EAS 12.1 формат FileReference: FolderId:ItemId:AttachmentIndex
        // Пробуем разные варианты XML структуры
        
        // Вариант 1: FileReference в namespace AirSyncBase (стандартный)
        val xml1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <FileReference xmlns="AirSyncBase">$fileRef</FileReference>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        var result = doItemOperationsFetch(xml1)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        // Вариант 2: FileReference без namespace (как в некоторых реализациях)
        val xml2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <FileReference>$fileRef</FileReference>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        result = doItemOperationsFetch(xml2)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        // Вариант 3: С Options для указания типа данных
        val xml3 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ItemOperations xmlns="ItemOperations">
                <Fetch>
                    <Store>Mailbox</Store>
                    <FileReference xmlns="AirSyncBase">$fileRef</FileReference>
                    <Options>
                        <Range>0-999999999</Range>
                    </Options>
                </Fetch>
            </ItemOperations>
        """.trimIndent()
        
        result = doItemOperationsFetch(xml3)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        return result
    }
    
    private suspend fun doItemOperationsFetch(xml: String): EasResult<ByteArray> {
        
        var result = executeEasCommand("ItemOperations", xml) { responseXml ->
            parseItemOperationsResponse(responseXml)
        }
        
        // Обработка HTTP 449
        if (result is EasResult.Error && result.message.contains("449")) {
            when (val provResult = provision()) {
                is EasResult.Success -> {
                    result = executeEasCommand("ItemOperations", xml) { responseXml ->
                        parseItemOperationsResponse(responseXml)
                    }
                }
                is EasResult.Error -> return EasResult.Error("Provision failed: ${provResult.message}")
            }
        }
        
        return when (result) {
            is EasResult.Success -> {
                val data = result.data
                if (data.isNotEmpty()) {
                    EasResult.Success(data)
                } else {
                    EasResult.Error("Вложение не найдено (Status=6)")
                }
            }
            is EasResult.Error -> EasResult.Error(result.message)
        }
    }
    
    private fun parseItemOperationsResponse(responseXml: String): ByteArray {
        // Проверяем общий статус
        val globalStatus = ITEM_OPS_GLOBAL_STATUS_REGEX.find(responseXml)?.groupValues?.get(1)
        // Проверяем статус внутри Fetch
        val fetchStatus = ITEM_OPS_FETCH_STATUS_REGEX.find(responseXml)?.groupValues?.get(1)
        // Status=1 - успех, Status=6 - не найдено
        if (fetchStatus != "1") {
            return ByteArray(0)
        }
        
        // Извлекаем данные - может быть в разных местах
        // Вариант 1: <Data>base64</Data>
        val dataMatch = ITEM_OPS_DATA_REGEX.find(responseXml)
        
        if (dataMatch != null) {
            val base64Data = dataMatch.groupValues[1].trim()
            try {
                return Base64.decode(base64Data, Base64.DEFAULT)
            } catch (e: Exception) {
            }
        }
        
        // Вариант 2: Данные могут быть в Properties/Data
        val propsMatch = ITEM_OPS_PROPS_DATA_REGEX.find(responseXml)
        if (propsMatch != null) {
            val base64Data = propsMatch.groupValues[1].trim()
            try {
                return Base64.decode(base64Data, Base64.DEFAULT)
            } catch (e: Exception) {
            }
        }
        return ByteArray(0)
    }
    
    private suspend fun downloadViaGetAttachment(fileReference: String): EasResult<ByteArray> {
        // Пробуем оба варианта - закодированный и декодированный
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) {
            fileReference
        }
        
        // Сначала пробуем с декодированным (как есть в URL)
        val result1 = tryGetAttachment(decodedRef)
        if (result1 is EasResult.Success) return result1
        
        // Потом с закодированным
        if (decodedRef != fileReference) {
            val result2 = tryGetAttachment(fileReference)
            if (result2 is EasResult.Success) return result2
        }
        
        return EasResult.Error("GetAttachment не поддерживается сервером (HTTP 501)")
    }
    
    private suspend fun tryGetAttachment(attachmentName: String): EasResult<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$normalizedServerUrl/Microsoft-Server-ActiveSync?" +
                    "Cmd=GetAttachment&AttachmentName=$attachmentName&User=$username&DeviceId=$deviceId&DeviceType=$deviceType"
                val requestBuilder = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", getAuthHeader())
                    .header("MS-ASProtocolVersion", easVersion)
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                policyKey?.let { requestBuilder.header("X-MS-PolicyKey", it) }
                
                var response = executeRequest(requestBuilder.build())
                // Обработка 449
                if (response.code == 449) {
                    when (val provResult = provision()) {
                        is EasResult.Success -> {
                            val retryBuilder = Request.Builder()
                                .url(url)
                                .get()
                                .header("Authorization", getAuthHeader())
                                .header("MS-ASProtocolVersion", easVersion)
                                .header("User-Agent", "Android/12-EAS-2.0")
                            policyKey?.let { retryBuilder.header("X-MS-PolicyKey", it) }
                            response = executeRequest(retryBuilder.build())
                        }
                        is EasResult.Error -> return@withContext EasResult.Error("Provision failed")
                    }
                }
                
                if (!response.isSuccessful) {
                    return@withContext EasResult.Error("HTTP ${response.code}: ${response.message}")
                }
                
                val data = response.body?.bytes()
                if (data == null || data.isEmpty()) {
                    return@withContext EasResult.Error("Пустой ответ")
                }
                EasResult.Success(data)
                
            } catch (e: Exception) {
                EasResult.Error("Ошибка: ${e.message}")
            }
        }
    }

    
    /**
     * Direct Push - Ping запрос для получения уведомлений о новых письмах
     * Сервер держит соединение открытым до таймаута или до появления изменений
     * 
     * @param folderIds список ServerId папок для мониторинга
     * @param heartbeatInterval интервал в секундах (60-3540, рекомендуется 900)
     * @return PingResult со статусом и списком изменённых папок
     * 
     * Статусы:
     * 1 - Таймаут, изменений нет
     * 2 - Есть изменения в папках (нужно синхронизировать)
     * 3 - Ошибка синтаксиса
     * 4 - Отсутствует heartbeat
     * 5 - Heartbeat слишком большой/маленький
     * 6 - Слишком много папок
     * 7 - Папка не существует
     * 8 - Общая ошибка
     */
    suspend fun ping(
        folderIds: List<String>,
        heartbeatInterval: Int = 900 // 15 минут по умолчанию
    ): EasResult<PingResult> {
        if (folderIds.isEmpty()) {
            return EasResult.Error("Нет папок для мониторинга")
        }
        
        val foldersXml = folderIds.joinToString("\n") { folderId ->
            """                <Folder>
                    <Id>$folderId</Id>
                    <Class>Email</Class>
                </Folder>"""
        }
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Ping xmlns="Ping">
                <HeartbeatInterval>$heartbeatInterval</HeartbeatInterval>
                <Folders>
$foldersXml
                </Folders>
            </Ping>
        """.trimIndent()
        
        return withContext(Dispatchers.IO) {
            try {
                val url = buildUrl("Ping")
                val wbxmlBody = wbxmlParser.generate(xml)
                
                // Создаём клиент с увеличенным таймаутом для Ping
                val pingClient = client.newBuilder()
                    .readTimeout((heartbeatInterval + 60).toLong(), TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .build()
                
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(wbxmlBody.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                    .header("Authorization", getAuthHeader())
                    .header("MS-ASProtocolVersion", easVersion)
                    .header("Content-Type", "application/vnd.ms-sync.wbxml")
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                policyKey?.let { key ->
                    requestBuilder.header("X-MS-PolicyKey", key)
                }
                
                val response = pingClient.newCall(requestBuilder.build()).execute()
                
                if (response.code == 449) {
                    return@withContext EasResult.Error("Требуется Provision (449)")
                }
                
                if (!response.isSuccessful) {
                    return@withContext EasResult.Error("HTTP ${response.code}: ${response.message}")
                }
                
                val responseBody = response.body?.bytes()
                
                if (responseBody == null || responseBody.isEmpty()) {
                    return@withContext EasResult.Success(PingResult(1, emptyList()))
                }
                
                val responseXml = wbxmlParser.parse(responseBody)
                val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 1
                val changedFolders = mutableListOf<String>()
                
                FOLDER_REGEX.findAll(responseXml).forEach { match ->
                    val folderId = extractValue(match.groupValues[1], "Id")
                    if (folderId != null) {
                        changedFolders.add(folderId)
                    }
                }
                
                EasResult.Success(PingResult(status, changedFolders))
                
            } catch (e: java.net.SocketTimeoutException) {
                EasResult.Success(PingResult(1, emptyList()))
            } catch (e: Exception) {
                EasResult.Error("Ошибка Ping: ${e.message}")
            }
        }
    }
    
    /**
     * Пометить письмо как прочитанное
     */
    suspend fun markAsRead(
        collectionId: String,
        serverId: String,
        syncKey: String,
        read: Boolean = true
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <Class>Email</Class>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$collectionId</CollectionId>
                        <Commands>
                            <Change>
                                <ServerId>$serverId</ServerId>
                                <ApplicationData>
                                    <Read xmlns="Email">${if (read) "1" else "0"}</Read>
                                </ApplicationData>
                            </Change>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            // Возвращаем новый SyncKey
            extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    /**
     * Удалить письмо (перемещает в корзину)
     */
    suspend fun deleteEmail(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <Class>Email</Class>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$collectionId</CollectionId>
                        <DeletesAsMoves>1</DeletesAsMoves>
                        <Commands>
                            <Delete>
                                <ServerId>$serverId</ServerId>
                            </Delete>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    /**
     * Окончательно удалить письмо (без перемещения в корзину)
     */
    suspend fun deleteEmailPermanently(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$collectionId</CollectionId>
                        <DeletesAsMoves>0</DeletesAsMoves>
                        <Commands>
                            <Delete>
                                <ServerId>$serverId</ServerId>
                            </Delete>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    private suspend fun <T> executeEasCommand(
        command: String,
        xml: String,
        skipPolicyKey: Boolean = false,
        parser: (String) -> T
    ): EasResult<T> = withContext(Dispatchers.IO) {
        try {
            val wbxml = wbxmlParser.generate(xml)
            
            val requestBuilder = Request.Builder()
                .url(buildUrl(command))
                .post(wbxml.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", "application/vnd.ms-sync.wbxml")
                .header("User-Agent", "Android/12-EAS-2.0")
            
            if (!skipPolicyKey) {
                policyKey?.let { key ->
                    requestBuilder.header("X-MS-PolicyKey", key)
                }
            }
            
            val request = requestBuilder.build()
            val response = executeRequest(request)
            
            if (response.code == 449) {
                response.body?.close()
                
                when (val provResult = provision()) {
                    is EasResult.Success -> {
                        val retryRequestBuilder = Request.Builder()
                            .url(buildUrl(command))
                            .post(wbxml.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                            .header("Authorization", getAuthHeader())
                            .header("MS-ASProtocolVersion", easVersion)
                            .header("Content-Type", "application/vnd.ms-sync.wbxml")
                            .header("User-Agent", "Android/12-EAS-2.0")
                        
                        policyKey?.let { key ->
                            retryRequestBuilder.header("X-MS-PolicyKey", key)
                        }
                        
                        val retryResponse = executeRequest(retryRequestBuilder.build())
                        
                        if (retryResponse.isSuccessful) {
                            val responseBody = retryResponse.body?.bytes()
                            if (responseBody != null && responseBody.isNotEmpty()) {
                                val responseXml = wbxmlParser.parse(responseBody)
                                return@withContext EasResult.Success(parser(responseXml))
                            } else {
                                return@withContext EasResult.Error("Пустой ответ от сервера после retry")
                            }
                        } else {
                            return@withContext EasResult.Error("HTTP ${retryResponse.code} после Provision")
                        }
                    }
                    is EasResult.Error -> {
                        return@withContext EasResult.Error("Provision failed: ${provResult.message}")
                    }
                }
            }
            
            if (response.isSuccessful) {
                val responseBody = response.body?.bytes()
                if (responseBody != null && responseBody.isNotEmpty()) {
                    val responseXml = wbxmlParser.parse(responseBody)
                    EasResult.Success(parser(responseXml))
                } else {
                    EasResult.Error("Пустой ответ от сервера")
                }
            } else {
                val errorBody = response.body?.string() ?: ""
                EasResult.Error("HTTP ${response.code}: ${response.message}")
            }
        } catch (e: Exception) {
            // Добавляем информацию о сертификате для отладки SSL ошибок
            val certInfo = if (certificatePath != null) " [cert: $certificatePath]" else ""
            EasResult.Error("Ошибка: ${e.javaClass.simpleName}: ${e.message}$certInfo")
        }
    }
    
    private suspend fun executeRequest(request: Request): Response = suspendCoroutine { cont ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
    
    private fun parseFolderSyncResponse(xml: String): FolderSyncResponse {
        val status = extractValue(xml, "Status")?.toIntOrNull() ?: 1
        val syncKey = extractValue(xml, "SyncKey") ?: "0"
        val folders = mutableListOf<EasFolder>()
        
        // Если Status != 1, возвращаем ошибку
        if (status != 1) {
            return FolderSyncResponse(syncKey, folders, status)
        }
        
        // Папки могут быть внутри <Add><Folder>...</Folder></Add> или напрямую <Add>...</Add>
        // Сначала пробуем найти <Folder> внутри <Add>
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        // Проверяем есть ли теги <Folder>
        val hasFolderTags = FOLDER_REGEX.containsMatchIn(xml)
        
        if (hasFolderTags) {
            // Старый формат: <Add><Folder>...</Folder></Add>
            FOLDER_REGEX.findAll(xml).forEach { match ->
                val folderXml = match.groupValues[1]
                parseFolder(folderXml)?.let { folders.add(it) }
            }
        } else {
            // Новый формат: данные папки напрямую внутри <Add>
            addPattern.findAll(xml).forEach { match ->
                val folderXml = match.groupValues[1]
                parseFolder(folderXml)?.let { folders.add(it) }
            }
        }
        return FolderSyncResponse(syncKey, folders, status)
    }
    
    private fun parseFolder(folderXml: String): EasFolder? {
        val serverId = extractValue(folderXml, "ServerId") ?: return null
        val displayName = extractValue(folderXml, "DisplayName") ?: ""
        val parentId = extractValue(folderXml, "ParentId") ?: "0"
        val type = extractValue(folderXml, "Type")?.toIntOrNull() ?: 1
        
        return EasFolder(serverId, displayName, parentId, type)
    }
    
    private fun parseSyncResponse(xml: String): SyncResponse {
        val syncKey = extractValue(xml, "SyncKey") ?: "0"
        val status = extractValue(xml, "Status")?.toIntOrNull() ?: 1
        val emails = mutableListOf<EasEmail>()
        val deletedIds = mutableListOf<String>()
        val changedEmails = mutableListOf<EasEmailChange>()
        
        // Проверяем наличие MoreAvailable (пустой тег)
        val moreAvailable = xml.contains("<MoreAvailable/>") || xml.contains("<MoreAvailable>")
        
        // Парсим добавленные письма
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val emailXml = match.groupValues[1]
            parseEmail(emailXml)?.let { emails.add(it) }
        }
        
        // Парсим изменённые письма (Change) - только Read и Flag
        val changePattern = "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        changePattern.findAll(xml).forEach { match ->
            val changeXml = match.groupValues[1]
            val serverId = extractValue(changeXml, "ServerId")
            if (serverId != null) {
                val readValue = extractValue(changeXml, "Read")
                val read = readValue?.let { it == "1" }
                
                // Flag status: 2 = flagged, 0 or absent = not flagged
                val flagStatus = extractValue(changeXml, "Status")
                val flagged = flagStatus?.let { it == "2" }
                
                if (read != null || flagged != null) {
                    changedEmails.add(EasEmailChange(serverId, read, flagged))
                }
            }
        }
        
        // Парсим удалённые письма (Delete и SoftDelete)
        val deletePattern = "<Delete>(.*?)</Delete>".toRegex(RegexOption.DOT_MATCHES_ALL)
        deletePattern.findAll(xml).forEach { match ->
            extractValue(match.groupValues[1], "ServerId")?.let { deletedIds.add(it) }
        }
        
        val softDeletePattern = "<SoftDelete>(.*?)</SoftDelete>".toRegex(RegexOption.DOT_MATCHES_ALL)
        softDeletePattern.findAll(xml).forEach { match ->
            extractValue(match.groupValues[1], "ServerId")?.let { deletedIds.add(it) }
        }
        
        return SyncResponse(syncKey, status, emails, moreAvailable, deletedIds, changedEmails)
    }
    
    private fun parseEmail(xml: String): EasEmail? {
        val serverId = extractValue(xml, "ServerId") ?: return null
        
        // Парсим вложения
        val attachments = mutableListOf<EasAttachment>()
        val attachmentPattern = "<Attachment>(.*?)</Attachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        attachmentPattern.findAll(xml).forEach { match ->
            val attXml = match.groupValues[1]
            val fileRef = extractValue(attXml, "FileReference")
            val displayName = extractValue(attXml, "DisplayName")
            val contentId = extractValue(attXml, "ContentId")
            val isInline = extractValue(attXml, "IsInline") == "1"
            if (fileRef != null && displayName != null) {
                attachments.add(EasAttachment(
                    fileReference = fileRef,
                    displayName = displayName,
                    contentType = extractValue(attXml, "ContentType") ?: "application/octet-stream",
                    estimatedSize = extractValue(attXml, "EstimatedDataSize")?.toLongOrNull() ?: 0,
                    isInline = isInline,
                    contentId = contentId
                ))
            }
        }
        
        return EasEmail(
            serverId = serverId,
            from = extractValue(xml, "From") ?: "",
            to = extractValue(xml, "To") ?: "",
            cc = extractValue(xml, "Cc") ?: "",
            subject = extractValue(xml, "Subject") ?: "(No subject)",
            dateReceived = extractValue(xml, "DateReceived") ?: "",
            read = extractValue(xml, "Read") == "1",
            importance = extractValue(xml, "Importance")?.toIntOrNull() ?: 1,
            body = extractValue(xml, "Data") ?: "",
            bodyType = extractValue(xml, "Type")?.toIntOrNull() ?: 1,
            attachments = attachments
        )
    }
    
    private fun extractValue(xml: String, tag: String): String? {
        val pattern = extractValueCache.computeIfAbsent(tag) {
            "<$tag>(.*?)</$tag>".toRegex(RegexOption.DOT_MATCHES_ALL)
        }
        return pattern.find(xml)?.groupValues?.get(1)
    }
    
    /**
     * Синхронизация контактов из папки Contacts на сервере Exchange
     * Возвращает список контактов
     */
    suspend fun syncContacts(): EasResult<List<GalContact>> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Сначала получаем список папок чтобы найти папку Contacts (type = 9)
        val foldersResult = folderSync("0")
        val contactsFolderId = when (foldersResult) {
            is EasResult.Success -> {
                foldersResult.data.folders.find { it.type == 9 }?.serverId
            }
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        if (contactsFolderId == null) {
            return EasResult.Error("Папка контактов не найдена")
        }
        
        // Шаг 1: Получаем начальный SyncKey для папки контактов
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$contactsFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        // Шаг 2: Запрашиваем контакты
        // Для Exchange 2003 (EAS 2.5) используем Truncation вместо BodyPreference
        // Для Exchange 2007+ (EAS 12.x+) используем BodyPreference
        val syncXml = if (easVersion.startsWith("2.")) {
            // EAS 2.5 (Exchange 2003)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>100</WindowSize>
                            <Options>
                                <Truncation>7</Truncation>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        } else if (easVersion.startsWith("12")) {
            // EAS 12.x (Exchange 2007/2007 SP1/SP2)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>100</WindowSize>
                            <Options>
                                <airsyncbase:BodyPreference>
                                    <airsyncbase:Type>1</airsyncbase:Type>
                                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                                </airsyncbase:BodyPreference>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        } else {
            // EAS 14.x+ (Exchange 2010+)
            """
                <?xml version="1.0" encoding="UTF-8"?>
                <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                    <Collections>
                        <Collection>
                            <SyncKey>$syncKey</SyncKey>
                            <CollectionId>$contactsFolderId</CollectionId>
                            <DeletesAsMoves/>
                            <GetChanges/>
                            <WindowSize>100</WindowSize>
                            <Options>
                                <airsyncbase:BodyPreference>
                                    <airsyncbase:Type>1</airsyncbase:Type>
                                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                                </airsyncbase:BodyPreference>
                            </Options>
                        </Collection>
                    </Collections>
                </Sync>
            """.trimIndent()
        }
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            parseContactsSyncResponse(responseXml)
        }
    }
    
    /**
     * Создание заметки на сервере Exchange
     * EAS 14.x — через Sync Add
     * EAS 12.x — через EWS CreateItem
     */
    suspend fun createNote(subject: String, body: String): EasResult<String> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            createNoteEas(subject, body)
        } else {
            createNoteEws(subject, body)
        }
    }
    
    /**
     * Создание заметки через EAS (Exchange 2010+)
     */
    private suspend fun createNoteEas(subject: String, body: String): EasResult<String> {
        // Получаем папку Notes
        val notesFolderId = cachedNotesFolderId ?: run {
            val foldersResult = folderSync("0")
            when (foldersResult) {
                is EasResult.Success -> {
                    val folderId = foldersResult.data.folders.find { it.type == 10 }?.serverId
                    if (folderId != null) cachedNotesFolderId = folderId
                    folderId
                }
                is EasResult.Error -> return EasResult.Error(foldersResult.message)
            }
        }
        
        if (notesFolderId == null) {
            return EasResult.Error("Папка Notes не найдена")
        }
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val escapedSubject = escapeXml(subject)
        val escapedBody = escapeXml(body)
        
        val createXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:notes="Notes">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                        <Commands>
                            <Add>
                                <ClientId>$clientId</ClientId>
                                <ApplicationData>
                                    <notes:Subject>$escapedSubject</notes:Subject>
                                    <airsyncbase:Body>
                                        <airsyncbase:Type>1</airsyncbase:Type>
                                        <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                                    </airsyncbase:Body>
                                    <notes:MessageClass>IPM.StickyNote</notes:MessageClass>
                                </ApplicationData>
                            </Add>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", createXml) { responseXml ->
            android.util.Log.d("EasClient", "createNoteEas response: ${responseXml.take(1000)}")
            val status = extractValue(responseXml, "Status")
            if (status == "1") {
                extractValue(responseXml, "ServerId") ?: clientId
            } else {
                throw Exception("Ошибка создания заметки: Status=$status")
            }
        }
    }
    
    /**
     * Создание заметки через EWS (Exchange 2007)
     */
    private suspend fun createNoteEws(subject: String, body: String): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                val escapedSubject = escapeXml(subject)
                val escapedBody = escapeXml(body)
                
                // Exchange 2007 использует Message с ItemClass для заметок
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:CreateItem MessageDisposition="SaveOnly">
                                <m:SavedItemFolderId>
                                    <t:DistinguishedFolderId Id="notes"/>
                                </m:SavedItemFolderId>
                                <m:Items>
                                    <t:Message>
                                        <t:ItemClass>IPM.StickyNote</t:ItemClass>
                                        <t:Subject>$escapedSubject</t:Subject>
                                        <t:Body BodyType="Text">$escapedBody</t:Body>
                                    </t:Message>
                                </m:Items>
                            </m:CreateItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                android.util.Log.d("EasClient", "createNoteEws: URL = $ewsUrl")
                
                // Пробуем NTLM аутентификацию
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth == null) {
                    android.util.Log.e("EasClient", "createNoteEws: NTLM handshake failed")
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                if (responseXml == null) {
                    android.util.Log.e("EasClient", "createNoteEws: executeNtlmRequest returned null")
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                android.util.Log.d("EasClient", "createNoteEws response: ${responseXml.take(1500)}")
                
                // Проверяем на ошибки
                if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                    android.util.Log.e("EasClient", "createNoteEws: Schema validation error")
                    return@withContext EasResult.Error("Ошибка схемы EWS")
                }
                
                // Извлекаем ItemId
                val itemId = EWS_ITEM_ID_REGEX.find(responseXml)?.groupValues?.get(1)
                
                if (itemId != null) {
                    EasResult.Success(itemId)
                } else if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                    EasResult.Success(java.util.UUID.randomUUID().toString())
                } else {
                    android.util.Log.e("EasClient", "createNoteEws: No ItemId and no success in response")
                    EasResult.Error("Не удалось создать заметку через EWS")
                }
            } catch (e: Exception) {
                android.util.Log.e("EasClient", "createNoteEws error", e)
                EasResult.Error(e.message ?: "Ошибка создания заметки через EWS")
            }
        }
    }
    
    /**
     * Удаление заметки на сервере Exchange
     */
    suspend fun deleteNote(serverId: String): EasResult<Boolean> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            deleteNoteEas(serverId)
        } else {
            deleteNoteEws(serverId)
        }
    }
    
    /**
     * Удаление заметки через EAS (Exchange 2010+)
     */
    private suspend fun deleteNoteEas(serverId: String): EasResult<Boolean> {
        val notesFolderId = cachedNotesFolderId ?: run {
            val foldersResult = folderSync("0")
            when (foldersResult) {
                is EasResult.Success -> {
                    val folderId = foldersResult.data.folders.find { it.type == 10 }?.serverId
                    if (folderId != null) cachedNotesFolderId = folderId
                    folderId
                }
                is EasResult.Error -> return EasResult.Error(foldersResult.message)
            }
        }
        
        if (notesFolderId == null) {
            return EasResult.Error("Папка Notes не найдена")
        }
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val deleteXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                        <Commands>
                            <Delete>
                                <ServerId>$serverId</ServerId>
                            </Delete>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", deleteXml) { responseXml ->
            val status = extractValue(responseXml, "Status")
            status == "1"
        }
    }
    
    /**
     * Удаление заметки через EWS (Exchange 2007)
     */
    private suspend fun deleteNoteEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                // Проверяем формат serverId - если это EAS формат (короткий), нужно найти EWS ItemId
                val ewsItemId = if (serverId.length < 50 || serverId.contains(":")) {
                    findEwsNoteItemId(ewsUrl, serverId)
                } else {
                    serverId
                }
                
                if (ewsItemId == null) {
                    return@withContext EasResult.Error("Не удалось найти заметку на сервере")
                }
                
                val escapedItemId = escapeXml(ewsItemId)
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:DeleteItem DeleteType="MoveToDeletedItems">
                                <m:ItemIds>
                                    <t:ItemId Id="$escapedItemId"/>
                                </m:ItemIds>
                            </m:DeleteItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // Пробуем NTLM аутентификацию (как в createNoteEws)
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"") || responseXml.contains("ErrorItemNotFound")) {
                    EasResult.Success(true)
                } else {
                    EasResult.Error("Не удалось удалить заметку")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка удаления заметки через EWS")
            }
        }
    }
    
    /**
     * Находит EWS ItemId заметки
     */
    private suspend fun findEwsNoteItemId(ewsUrl: String, easServerId: String): String? {
        val findRequest = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                           xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                <soap:Header>
                    <t:RequestServerVersion Version="Exchange2007_SP1"/>
                </soap:Header>
                <soap:Body>
                    <m:FindItem Traversal="Shallow">
                        <m:ItemShape>
                            <t:BaseShape>IdOnly</t:BaseShape>
                        </m:ItemShape>
                        <m:IndexedPageItemView MaxEntriesReturned="500" Offset="0" BasePoint="Beginning"/>
                        <m:ParentFolderIds>
                            <t:DistinguishedFolderId Id="notes"/>
                        </m:ParentFolderIds>
                    </m:FindItem>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
        
        // Используем только NTLM для Exchange 2007
        val ntlmAuth = performNtlmHandshake(ewsUrl, findRequest, "FindItem")
        if (ntlmAuth == null) {
            return null
        }
        
        val responseXml = executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
        if (responseXml == null) {
            return null
        }
        
        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
        val matches = itemIdPattern.findAll(responseXml).toList()
        
        val index = easServerId.substringAfter(":").toIntOrNull()?.minus(1) ?: 0
        return matches.getOrNull(index)?.groupValues?.get(1) ?: matches.firstOrNull()?.groupValues?.get(1)
    }
    
    /**
     * Обновление заметки на сервере Exchange
     */
    suspend fun updateNote(serverId: String, subject: String, body: String): EasResult<Boolean> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            updateNoteEas(serverId, subject, body)
        } else {
            updateNoteEws(serverId, subject, body)
        }
    }
    
    /**
     * Обновление заметки через EAS (Exchange 2010+)
     */
    private suspend fun updateNoteEas(serverId: String, subject: String, body: String): EasResult<Boolean> {
        val notesFolderId = cachedNotesFolderId ?: run {
            val foldersResult = folderSync("0")
            when (foldersResult) {
                is EasResult.Success -> {
                    val folderId = foldersResult.data.folders.find { it.type == 10 }?.serverId
                    if (folderId != null) cachedNotesFolderId = folderId
                    folderId
                }
                is EasResult.Error -> return EasResult.Error(foldersResult.message)
            }
        }
        
        if (notesFolderId == null) {
            return EasResult.Error("Папка Notes не найдена")
        }
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val escapedSubject = escapeXml(subject)
        val escapedBody = escapeXml(body)
        
        val updateXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:notes="Notes">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                        <Commands>
                            <Change>
                                <ServerId>$serverId</ServerId>
                                <ApplicationData>
                                    <notes:Subject>$escapedSubject</notes:Subject>
                                    <airsyncbase:Body>
                                        <airsyncbase:Type>1</airsyncbase:Type>
                                        <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                                    </airsyncbase:Body>
                                </ApplicationData>
                            </Change>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", updateXml) { responseXml ->
            val status = extractValue(responseXml, "Status")
            status == "1"
        }
    }
    
    /**
     * Обновление заметки через EWS (Exchange 2007)
     */
    private suspend fun updateNoteEws(serverId: String, subject: String, body: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                val escapedSubject = escapeXml(subject)
                val escapedBody = escapeXml(body)
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:UpdateItem MessageDisposition="SaveOnly" ConflictResolution="AlwaysOverwrite">
                                <m:ItemChanges>
                                    <t:ItemChange>
                                        <t:ItemId Id="$serverId"/>
                                        <t:Updates>
                                            <t:SetItemField>
                                                <t:FieldURI FieldURI="item:Subject"/>
                                                <t:PostItem>
                                                    <t:Subject>$escapedSubject</t:Subject>
                                                </t:PostItem>
                                            </t:SetItemField>
                                            <t:SetItemField>
                                                <t:FieldURI FieldURI="item:Body"/>
                                                <t:PostItem>
                                                    <t:Body BodyType="Text">$escapedBody</t:Body>
                                                </t:PostItem>
                                            </t:SetItemField>
                                        </t:Updates>
                                    </t:ItemChange>
                                </m:ItemChanges>
                            </m:UpdateItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // NTLM аутентификация
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "UpdateItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "UpdateItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                android.util.Log.d("EasClient", "updateNoteEws response: ${responseXml.take(500)}")
                
                EasResult.Success(responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\""))
            } catch (e: Exception) {
                android.util.Log.e("EasClient", "updateNoteEws error", e)
                EasResult.Error(e.message ?: "Ошибка обновления заметки через EWS")
            }
        }
    }
    
    /**
     * Синхронизация заметок из папки Notes на сервере Exchange
     * 
     * EAS 14.1+ (Exchange 2010 SP1+): стандартный Notes sync через type=10
     * EAS 12.x (Exchange 2007): fallback через email-подход (IPM.StickyNote)
     */
    suspend fun syncNotes(): EasResult<List<EasNote>> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        android.util.Log.d("EasClient", "syncNotes: EAS version = $easVersion")
        
        val foldersResult = folderSync("0")
        val folders = when (foldersResult) {
            is EasResult.Success -> foldersResult.data.folders
            is EasResult.Error -> {
                android.util.Log.e("EasClient", "syncNotes: folderSync failed: ${foldersResult.message}")
                return EasResult.Error(foldersResult.message)
            }
        }
        
        android.util.Log.d("EasClient", "syncNotes: Found ${folders.size} folders")
        folders.forEach { f ->
            android.util.Log.d("EasClient", "  Folder: type=${f.type}, name='${f.displayName}', id=${f.serverId}")
        }
        
        // Определяем версию EAS
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        android.util.Log.d("EasClient", "syncNotes: majorVersion = $majorVersion, using ${if (majorVersion >= 14) "Standard" else "Legacy"} mode")
        
        return if (majorVersion >= 14) {
            // EAS 14.1+ — стандартный Notes sync
            syncNotesStandard(folders)
        } else {
            // EAS 12.x (Exchange 2007) — fallback через email-подход
            syncNotesLegacy(folders)
        }
    }
    
    /**
     * Стандартная синхронизация заметок для EAS 14.1+ (Exchange 2010 SP1+)
     */
    private suspend fun syncNotesStandard(folders: List<EasFolder>): EasResult<List<EasNote>> {
        val notesFolderId = folders.find { it.type == 10 }?.serverId
            ?: return EasResult.Error("Папка Notes (type=10) не найдена на сервере")
        
        // Сохраняем для использования в create/update/delete
        cachedNotesFolderId = notesFolderId
        
        // Шаг 1: Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        when (val result = executeEasCommand("Sync", initialXml) { extractValue(it, "SyncKey") ?: "0" }) {
            is EasResult.Success -> syncKey = result.data
            is EasResult.Error -> return EasResult.Error(result.message)
        }
        
        if (syncKey == "0") return EasResult.Success(emptyList())
        
        // Шаг 2: Запрашиваем заметки
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                        <DeletesAsMoves/>
                        <GetChanges/>
                        <WindowSize>100</WindowSize>
                        <Options>
                            <airsyncbase:BodyPreference>
                                <airsyncbase:Type>1</airsyncbase:Type>
                                <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                            </airsyncbase:BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", syncXml) { parseNotesResponse(it, legacy = false) }
    }
    
    /**
     * Legacy синхронизация заметок для EAS 12.x (Exchange 2007)
     * Exchange 2007 не поддерживает Sync для Notes, используем Search
     */
    private suspend fun syncNotesLegacy(folders: List<EasFolder>): EasResult<List<EasNote>> {
        android.util.Log.d("EasClient", "syncNotesLegacy: Starting legacy notes sync")
        
        // Ищем папку Notes по имени
        val notesFolderId = folders.find { 
            it.displayName.equals("Notes", ignoreCase = true) ||
            it.displayName.equals("Заметки", ignoreCase = true)
        }?.serverId
        
        if (notesFolderId == null) {
            android.util.Log.w("EasClient", "syncNotesLegacy: Notes folder NOT FOUND! Available folders:")
            folders.forEach { f ->
                android.util.Log.w("EasClient", "  - '${f.displayName}' (type=${f.type})")
            }
            // Нет папки Notes — возвращаем пустой список без ошибки
            return EasResult.Success(emptyList())
        }
        
        android.util.Log.d("EasClient", "syncNotesLegacy: Found Notes folder: id=$notesFolderId")
        
        // Используем Search вместо Sync для Exchange 2007
        val searchXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Search xmlns="Search" xmlns:airsync="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Store>
                    <Name>Mailbox</Name>
                    <Query>
                        <And>
                            <airsync:CollectionId>$notesFolderId</airsync:CollectionId>
                            <FreeText>*</FreeText>
                        </And>
                    </Query>
                    <Options>
                        <Range>0-99</Range>
                        <airsyncbase:BodyPreference>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                        </airsyncbase:BodyPreference>
                    </Options>
                </Store>
            </Search>
        """.trimIndent()
        
        android.util.Log.d("EasClient", "syncNotesLegacy: Trying Search command...")
        
        val searchResult = executeEasCommand("Search", searchXml) { responseXml ->
            android.util.Log.d("EasClient", "syncNotesLegacy: Search response (first 2000 chars):\n${responseXml.take(2000)}")
            parseNotesSearchResponse(responseXml)
        }
        
        when (searchResult) {
            is EasResult.Success -> android.util.Log.d("EasClient", "syncNotesLegacy: Search returned ${searchResult.data.size} notes")
            is EasResult.Error -> android.util.Log.e("EasClient", "syncNotesLegacy: Search failed: ${searchResult.message}")
        }
        
        // Если Search не сработал — пробуем Sync как fallback
        if (searchResult is EasResult.Error || (searchResult is EasResult.Success && searchResult.data.isEmpty())) {
            android.util.Log.d("EasClient", "syncNotesLegacy: Search empty/failed, trying Sync fallback...")
            val syncResult = syncNotesLegacySync(notesFolderId)
            
            // Если Sync тоже не сработал — пробуем EWS
            if (syncResult is EasResult.Success && syncResult.data.isEmpty()) {
                android.util.Log.d("EasClient", "syncNotesLegacy: Sync empty, trying EWS fallback...")
                return syncNotesEws()
            }
            return syncResult
        }
        
        return searchResult
    }
    
    /**
     * EWS fallback для Notes на Exchange 2007
     * EWS поддерживает Notes даже когда EAS не поддерживает
     */
    private suspend fun syncNotesEws(): EasResult<List<EasNote>> {
        return withContext(Dispatchers.IO) {
            try {
                // Формируем URL для EWS - убираем путь ActiveSync
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                android.util.Log.d("EasClient", "syncNotesEws: Trying EWS at $ewsUrl")
                
                // SOAP запрос для получения заметок - Exchange 2007 формат
                // Используем IdOnly + AdditionalProperties чтобы получить Body
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                      <soap:Header>
                        <t:RequestServerVersion Version="Exchange2007_SP1"/>
                      </soap:Header>
                      <soap:Body>
                        <m:FindItem Traversal="Shallow">
                          <m:ItemShape>
                            <t:BaseShape>AllProperties</t:BaseShape>
                            <t:BodyType>Text</t:BodyType>
                            <t:AdditionalProperties>
                              <t:FieldURI FieldURI="item:Body"/>
                            </t:AdditionalProperties>
                          </m:ItemShape>
                          <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
                          <m:ParentFolderIds>
                            <t:DistinguishedFolderId Id="notes"/>
                          </m:ParentFolderIds>
                        </m:FindItem>
                      </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // Пробуем разные форматы авторизации
                val authHeaders = buildEwsAuthHeaders()
                
                for ((authName, authValue) in authHeaders) {
                    android.util.Log.d("EasClient", "syncNotesEws: Trying auth: $authName")
                    
                    val request = Request.Builder()
                        .url(ewsUrl)
                        .post(soapRequest.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                        .header("Authorization", authValue)
                        .header("Content-Type", "text/xml; charset=utf-8")
                        .header("SOAPAction", "\"http://schemas.microsoft.com/exchange/services/2006/messages/FindItem\"")
                        .header("User-Agent", "ExchangeServicesClient/15.00.0000.000")
                        .build()
                    
                    try {
                        val response = executeRequest(request)
                        
                        android.util.Log.d("EasClient", "syncNotesEws: $authName -> HTTP ${response.code}")
                        
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            android.util.Log.d("EasClient", "syncNotesEws: Response (first 2000 chars):\n${responseBody.take(2000)}")
                            
                            // Проверяем на ошибки SOAP
                            if (responseBody.contains("Fault") || responseBody.contains("ErrorAccessDenied")) {
                                android.util.Log.w("EasClient", "syncNotesEws: SOAP error in response")
                                continue
                            }
                            
                            // Парсим ответ EWS - получаем ItemId
                            val itemIds = parseEwsItemIds(responseBody)
                            android.util.Log.d("EasClient", "syncNotesEws: Found ${itemIds.size} item IDs")
                            
                            if (itemIds.isEmpty()) {
                                return@withContext EasResult.Success(emptyList())
                            }
                            
                            // Запрашиваем полные данные через GetItem
                            val notes = getEwsNotesWithBody(ewsUrl, authValue, itemIds)
                            android.util.Log.d("EasClient", "syncNotesEws: Got ${notes.size} notes with body")
                            
                            return@withContext EasResult.Success(notes)
                        } else if (response.code == 401) {
                            // Пробуем следующий формат авторизации
                            response.close()
                            continue
                        } else {
                            response.close()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("EasClient", "syncNotesEws: $authName failed: ${e.message}")
                    }
                }
                
                android.util.Log.e("EasClient", "syncNotesEws: All Basic auth methods failed, trying NTLM...")
                
                // Пробуем NTLM
                val ntlmResult = syncNotesEwsNtlm(ewsUrl)
                if (ntlmResult is EasResult.Success && ntlmResult.data.isNotEmpty()) {
                    return@withContext ntlmResult
                }
                
                android.util.Log.e("EasClient", "syncNotesEws: All auth methods failed")
                EasResult.Success(emptyList())
            } catch (e: Exception) {
                android.util.Log.e("EasClient", "syncNotesEws: Error: ${e.message}")
                EasResult.Success(emptyList())
            }
        }
    }
    
    /**
     * Извлекает ItemId из FindItem ответа
     */
    private fun parseEwsItemIds(xml: String): List<Pair<String, String>> {
        val items = mutableListOf<Pair<String, String>>() // Pair(ItemId, Subject)
        
        // Ищем все Message элементы с IPM.StickyNote
        val messagePattern = "<t:Message>(.*?)</t:Message>".toRegex(RegexOption.DOT_MATCHES_ALL)
        messagePattern.findAll(xml).forEach { match ->
            val messageXml = match.groupValues[1]
            
            // Проверяем ItemClass
            val itemClass = extractEwsValue(messageXml, "ItemClass") ?: ""
            if (!itemClass.contains("StickyNote", ignoreCase = true)) {
                return@forEach
            }
            
            val itemId = extractEwsAttribute(messageXml, "ItemId", "Id") ?: return@forEach
            val subject = extractEwsValue(messageXml, "Subject") ?: ""
            
            items.add(itemId to subject)
        }
        
        return items
    }
    
    /**
     * Получает заметки с телом через GetItem
     */
    private suspend fun getEwsNotesWithBody(ewsUrl: String, authHeader: String, itemIds: List<Pair<String, String>>): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        // Запрашиваем по 10 элементов за раз
        for (chunk in itemIds.chunked(10)) {
            val itemIdXml = chunk.joinToString("\n") { (id, _) ->
                """<t:ItemId Id="$id"/>"""
            }
            
            val getItemRequest = """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <soap:Header>
                    <t:RequestServerVersion Version="Exchange2007_SP1"/>
                  </soap:Header>
                  <soap:Body>
                    <m:GetItem>
                      <m:ItemShape>
                        <t:BaseShape>AllProperties</t:BaseShape>
                        <t:BodyType>Text</t:BodyType>
                      </m:ItemShape>
                      <m:ItemIds>
                        $itemIdXml
                      </m:ItemIds>
                    </m:GetItem>
                  </soap:Body>
                </soap:Envelope>
            """.trimIndent()
            
            val request = Request.Builder()
                .url(ewsUrl)
                .post(getItemRequest.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .header("Authorization", authHeader)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"http://schemas.microsoft.com/exchange/services/2006/messages/GetItem\"")
                .build()
            
            try {
                val response = executeRequest(request)
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    notes.addAll(parseEwsNotesResponse(responseBody))
                }
                response.close()
            } catch (e: Exception) {
                android.util.Log.w("EasClient", "getEwsNotesWithBody: Error: ${e.message}")
            }
        }
        
        return notes
    }
    
    /**
     * EWS запрос с NTLM аутентификацией
     */
    private suspend fun syncNotesEwsNtlm(ewsUrl: String): EasResult<List<EasNote>> {
        try {
            android.util.Log.d("EasClient", "syncNotesEwsNtlm: Starting NTLM handshake")
            
            // FindItem запрос - получаем ID и Subject
            val findItemRequest = """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                  <soap:Header>
                    <t:RequestServerVersion Version="Exchange2007_SP1"/>
                  </soap:Header>
                  <soap:Body>
                    <m:FindItem Traversal="Shallow">
                      <m:ItemShape>
                        <t:BaseShape>AllProperties</t:BaseShape>
                      </m:ItemShape>
                      <m:IndexedPageItemView MaxEntriesReturned="100" Offset="0" BasePoint="Beginning"/>
                      <m:ParentFolderIds>
                        <t:DistinguishedFolderId Id="notes"/>
                      </m:ParentFolderIds>
                    </m:FindItem>
                  </soap:Body>
                </soap:Envelope>
            """.trimIndent()
            
            // Получаем NTLM auth header
            val ntlmAuth = performNtlmHandshake(ewsUrl, findItemRequest, "FindItem")
            if (ntlmAuth == null) {
                return EasResult.Success(emptyList())
            }
            
            // Выполняем FindItem с NTLM
            val findResponse = executeNtlmRequest(ewsUrl, findItemRequest, ntlmAuth, "FindItem")
            if (findResponse == null) {
                return EasResult.Success(emptyList())
            }
            
            android.util.Log.d("EasClient", "syncNotesEwsNtlm: FindItem response (first 2000 chars):\n${findResponse.take(2000)}")
            
            // Парсим ID и Subject из FindItem
            val itemIds = parseEwsItemIds(findResponse)
            android.util.Log.d("EasClient", "syncNotesEwsNtlm: Found ${itemIds.size} items")
            
            if (itemIds.isEmpty()) {
                return EasResult.Success(emptyList())
            }
            
            // Получаем Body для каждой заметки через GetItem (по одной)
            val notes = mutableListOf<EasNote>()
            for ((itemId, subject) in itemIds) {
                val getItemRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                      <soap:Header>
                        <t:RequestServerVersion Version="Exchange2007_SP1"/>
                      </soap:Header>
                      <soap:Body>
                        <m:GetItem>
                          <m:ItemShape>
                            <t:BaseShape>Default</t:BaseShape>
                            <t:IncludeMimeContent>false</t:IncludeMimeContent>
                            <t:BodyType>Text</t:BodyType>
                          </m:ItemShape>
                          <m:ItemIds>
                            <t:ItemId Id="${escapeXml(itemId)}"/>
                          </m:ItemIds>
                        </m:GetItem>
                      </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                try {
                    val getItemAuth = performNtlmHandshake(ewsUrl, getItemRequest, "GetItem")
                    if (getItemAuth != null) {
                        val getResponse = executeNtlmRequest(ewsUrl, getItemRequest, getItemAuth, "GetItem")
                        if (getResponse != null) {
                            android.util.Log.d("EasClient", "syncNotesEwsNtlm: GetItem response for '$subject': ${getResponse.take(500)}")
                            
                            // Извлекаем Body из ответа
                            val body = extractEwsValue(getResponse, "Body") ?: ""
                            val lastModified = parseEwsDate(extractEwsValue(getResponse, "DateTimeCreated"))
                            
                            notes.add(EasNote(
                                serverId = itemId,
                                subject = subject.ifEmpty { "No subject" },
                                body = body,
                                categories = emptyList(),
                                lastModified = lastModified
                            ))
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("EasClient", "syncNotesEwsNtlm: GetItem failed for '$subject': ${e.message}")
                    // Добавляем заметку без Body
                    notes.add(EasNote(
                        serverId = itemId,
                        subject = subject.ifEmpty { "No subject" },
                        body = "",
                        categories = emptyList(),
                        lastModified = System.currentTimeMillis()
                    ))
                }
            }
            
            android.util.Log.d("EasClient", "syncNotesEwsNtlm: Got ${notes.size} notes")
            return EasResult.Success(notes)
            
        } catch (e: Exception) {
            android.util.Log.e("EasClient", "syncNotesEwsNtlm: Error: ${e.message}", e)
        }
        
        return EasResult.Success(emptyList())
    }
    
    /**
     * Выполняет NTLM handshake и возвращает auth header
     */
    private suspend fun performNtlmHandshake(ewsUrl: String, soapRequest: String, action: String): String? {
        // Шаг 1: Type 1 (Negotiate)
        val type1Message = createNtlmType1Message()
        val type1Base64 = Base64.encodeToString(type1Message, Base64.NO_WRAP)
        
        val request1 = Request.Builder()
            .url(ewsUrl)
            .post(soapRequest.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Authorization", "NTLM $type1Base64")
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"http://schemas.microsoft.com/exchange/services/2006/messages/$action\"")
            .header("Connection", "keep-alive")
            .build()
        
        val response1 = executeRequest(request1)
        
        if (response1.code != 401) {
            response1.close()
            return null
        }
        
        // Получаем Type 2 (Challenge)
        val wwwAuth = response1.header("WWW-Authenticate") ?: ""
        response1.close()
        
        val type2Base64 = wwwAuth
            .split(",")
            .map { it.trim() }
            .find { it.startsWith("NTLM ", ignoreCase = true) }
            ?.substringAfter("NTLM ")
            ?.trim()
        
        if (type2Base64.isNullOrEmpty()) {
            return null
        }
        
        val type2Message = Base64.decode(type2Base64, Base64.DEFAULT)
        
        // Шаг 2: Type 3 (Authenticate)
        val type3Message = createNtlmType3Message(type2Message)
        val type3Base64 = Base64.encodeToString(type3Message, Base64.NO_WRAP)
        
        return "NTLM $type3Base64"
    }
    
    /**
     * Выполняет запрос с NTLM auth header
     */
    private suspend fun executeNtlmRequest(ewsUrl: String, soapRequest: String, authHeader: String, action: String): String? {
        val request = Request.Builder()
            .url(ewsUrl)
            .post(soapRequest.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Authorization", authHeader)
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"http://schemas.microsoft.com/exchange/services/2006/messages/$action\"")
            .build()
        
        val response = executeRequest(request)
        
        return if (response.isSuccessful) {
            val body = response.body?.string()
            response.close()
            body
        } else {
            response.close()
            null
        }
    }
    
    /**
     * Пробует Basic аутентификацию для EWS запроса
     */
    private suspend fun tryBasicAuthEws(ewsUrl: String, soapRequest: String, action: String): String? {
        val request = Request.Builder()
            .url(ewsUrl)
            .post(soapRequest.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Authorization", getAuthHeader())
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"http://schemas.microsoft.com/exchange/services/2006/messages/$action\"")
            .build()
        
        return try {
            val response = executeRequest(request)
            if (response.isSuccessful) {
                val body = response.body?.string()
                response.close()
                body
            } else {
                response.close()
                null
            }
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * Создаёт NTLM Type 1 (Negotiate) сообщение
     */
    private fun createNtlmType1Message(): ByteArray {
        val domainBytes = domain.uppercase().toByteArray(Charsets.US_ASCII)
        val workstationBytes = "ANDROID".toByteArray(Charsets.US_ASCII)
        
        // Флаги NTLM
        val flags = 0x00000001 or  // Negotiate Unicode
                    0x00000002 or  // Negotiate OEM
                    0x00000004 or  // Request Target
                    0x00000200 or  // Negotiate NTLM
                    0x00008000 or  // Negotiate Always Sign
                    0x00080000 or  // Negotiate NTLM2 Key
                    0x02000000 or  // Negotiate 128
                    0x20000000     // Negotiate 56
        
        val message = java.io.ByteArrayOutputStream()
        
        // Signature "NTLMSSP\0"
        message.write("NTLMSSP".toByteArray(Charsets.US_ASCII))
        message.write(0)
        
        // Type 1 indicator
        writeInt32LE(message, 1)
        
        // Flags
        writeInt32LE(message, flags)
        
        // Domain (security buffer): length, allocated, offset
        val domainOffset = 32
        writeInt16LE(message, domainBytes.size)
        writeInt16LE(message, domainBytes.size)
        writeInt32LE(message, domainOffset)
        
        // Workstation (security buffer)
        val workstationOffset = domainOffset + domainBytes.size
        writeInt16LE(message, workstationBytes.size)
        writeInt16LE(message, workstationBytes.size)
        writeInt32LE(message, workstationOffset)
        
        // Domain and workstation data
        message.write(domainBytes)
        message.write(workstationBytes)
        
        return message.toByteArray()
    }
    
    /**
     * Создаёт NTLM Type 3 (Authenticate) сообщение с NTLMv2
     */
    private fun createNtlmType3Message(type2Message: ByteArray): ByteArray {
        // Извлекаем данные из Type 2
        val serverChallenge = type2Message.copyOfRange(24, 32)
        
        // Извлекаем target info из Type 2 (если есть)
        val targetInfo = extractTargetInfo(type2Message)
        
        // Подготавливаем данные
        val domainUnicode = domain.uppercase().toByteArray(Charsets.UTF_16LE)
        val userUnicode = username.toByteArray(Charsets.UTF_16LE)
        val workstationUnicode = "ANDROID".toByteArray(Charsets.UTF_16LE)
        
        // NTLMv2 вычисления
        val ntlmHash = createNtlmHash(password)
        val ntlmv2Hash = createNtlmv2Hash(ntlmHash, username, domain)
        
        // Создаём client challenge (8 random bytes)
        val clientChallenge = ByteArray(8)
        java.security.SecureRandom().nextBytes(clientChallenge)
        
        // Создаём blob для NTLMv2
        val blob = createNtlmv2Blob(clientChallenge, targetInfo)
        
        // Вычисляем NTLMv2 response
        val ntlmv2Response = createNtlmv2Response(ntlmv2Hash, serverChallenge, blob)
        
        // LMv2 response (упрощённый - используем client challenge)
        val lmv2Response = createLmv2Response(ntlmv2Hash, serverChallenge, clientChallenge)
        
        // Строим Type 3 сообщение
        val message = java.io.ByteArrayOutputStream()
        
        // Signature
        message.write("NTLMSSP".toByteArray(Charsets.US_ASCII))
        message.write(0)
        
        // Type 3 indicator
        writeInt32LE(message, 3)
        
        // Вычисляем смещения (header = 88 bytes для NTLMv2)
        val headerSize = 88
        var offset = headerSize
        
        // LM Response (security buffer)
        writeInt16LE(message, lmv2Response.size)
        writeInt16LE(message, lmv2Response.size)
        writeInt32LE(message, offset)
        offset += lmv2Response.size
        
        // NTLM Response (security buffer)
        writeInt16LE(message, ntlmv2Response.size)
        writeInt16LE(message, ntlmv2Response.size)
        writeInt32LE(message, offset)
        offset += ntlmv2Response.size
        
        // Domain (security buffer)
        writeInt16LE(message, domainUnicode.size)
        writeInt16LE(message, domainUnicode.size)
        writeInt32LE(message, offset)
        offset += domainUnicode.size
        
        // User (security buffer)
        writeInt16LE(message, userUnicode.size)
        writeInt16LE(message, userUnicode.size)
        writeInt32LE(message, offset)
        offset += userUnicode.size
        
        // Workstation (security buffer)
        writeInt16LE(message, workstationUnicode.size)
        writeInt16LE(message, workstationUnicode.size)
        writeInt32LE(message, offset)
        offset += workstationUnicode.size
        
        // Encrypted random session key (empty)
        writeInt16LE(message, 0)
        writeInt16LE(message, 0)
        writeInt32LE(message, offset)
        
        // Flags (NTLMv2)
        val flags = 0x00000001 or  // Unicode
                    0x00000200 or  // NTLM
                    0x00008000 or  // Always Sign
                    0x00080000 or  // NTLM2 Key
                    0x02000000 or  // 128-bit
                    0x20000000     // 56-bit
        writeInt32LE(message, flags)
        
        // Version (optional, 8 bytes)
        message.write(byteArrayOf(6, 1, 0, 0, 0, 0, 0, 15)) // Windows Vista
        
        // MIC (16 bytes, zeros for now)
        message.write(ByteArray(16))
        
        // Данные
        message.write(lmv2Response)
        message.write(ntlmv2Response)
        message.write(domainUnicode)
        message.write(userUnicode)
        message.write(workstationUnicode)
        
        return message.toByteArray()
    }
    
    /**
     * Извлекает Target Info из Type 2 сообщения
     */
    private fun extractTargetInfo(type2Message: ByteArray): ByteArray {
        if (type2Message.size < 48) return ByteArray(0)
        
        // Target Info находится по смещению из заголовка
        val targetInfoLen = readInt16LE(type2Message, 40)
        val targetInfoOffset = readInt32LE(type2Message, 44)
        
        if (targetInfoOffset + targetInfoLen > type2Message.size) return ByteArray(0)
        
        return type2Message.copyOfRange(targetInfoOffset, targetInfoOffset + targetInfoLen)
    }
    
    /**
     * Создаёт NTLMv2 hash
     */
    private fun createNtlmv2Hash(ntlmHash: ByteArray, user: String, domain: String): ByteArray {
        val identity = (user.uppercase() + domain.uppercase()).toByteArray(Charsets.UTF_16LE)
        return hmacMd5(ntlmHash, identity)
    }
    
    /**
     * Создаёт NTLMv2 blob
     */
    private fun createNtlmv2Blob(clientChallenge: ByteArray, targetInfo: ByteArray): ByteArray {
        val blob = java.io.ByteArrayOutputStream()
        
        // Blob signature
        blob.write(byteArrayOf(0x01, 0x01, 0x00, 0x00))
        
        // Reserved
        blob.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        
        // Timestamp (FILETIME - 100ns intervals since 1601)
        val now = System.currentTimeMillis()
        val filetime = (now + 11644473600000L) * 10000L
        for (i in 0..7) {
            blob.write((filetime shr (i * 8)).toInt() and 0xFF)
        }
        
        // Client challenge
        blob.write(clientChallenge)
        
        // Reserved
        blob.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        
        // Target info
        blob.write(targetInfo)
        
        // Reserved
        blob.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        
        return blob.toByteArray()
    }
    
    /**
     * Создаёт NTLMv2 response
     */
    private fun createNtlmv2Response(ntlmv2Hash: ByteArray, serverChallenge: ByteArray, blob: ByteArray): ByteArray {
        // Concatenate server challenge + blob
        val data = serverChallenge + blob
        
        // HMAC-MD5
        val ntProofStr = hmacMd5(ntlmv2Hash, data)
        
        // Response = NTProofStr + blob
        return ntProofStr + blob
    }
    
    /**
     * Создаёт LMv2 response
     */
    private fun createLmv2Response(ntlmv2Hash: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray {
        val data = serverChallenge + clientChallenge
        val hash = hmacMd5(ntlmv2Hash, data)
        return hash + clientChallenge
    }
    
    /**
     * HMAC-MD5
     */
    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacMD5")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }
    
    private fun readInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
    
    private fun readInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
    
    /**
     * Создаёт NTLM hash из пароля (MD4)
     */
    private fun createNtlmHash(password: String): ByteArray {
        val passwordUnicode = password.toByteArray(Charsets.UTF_16LE)
        
        // Пробуем стандартный MD4
        return try {
            val md4 = java.security.MessageDigest.getInstance("MD4")
            md4.digest(passwordUnicode)
        } catch (e: Exception) {
            // MD4 не доступен, используем свою реализацию
            android.util.Log.d("EasClient", "MD4 not available, using custom implementation")
            md4Hash(passwordUnicode)
        }
    }
    
    /**
     * Простая реализация MD4 для NTLM
     */
    private fun md4Hash(input: ByteArray): ByteArray {
        // Padding
        val originalLength = input.size
        val paddedLength = ((originalLength + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        System.arraycopy(input, 0, padded, 0, originalLength)
        padded[originalLength] = 0x80.toByte()
        
        // Length in bits (little-endian)
        val bitLength = originalLength.toLong() * 8
        for (i in 0..7) {
            padded[paddedLength - 8 + i] = (bitLength shr (i * 8)).toByte()
        }
        
        // Initial hash values
        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        
        // Process each 64-byte block
        for (blockStart in 0 until paddedLength step 64) {
            val x = IntArray(16)
            for (i in 0..15) {
                x[i] = (padded[blockStart + i * 4].toInt() and 0xFF) or
                       ((padded[blockStart + i * 4 + 1].toInt() and 0xFF) shl 8) or
                       ((padded[blockStart + i * 4 + 2].toInt() and 0xFF) shl 16) or
                       ((padded[blockStart + i * 4 + 3].toInt() and 0xFF) shl 24)
            }
            
            val aa = a; val bb = b; val cc = c; val dd = d
            
            // Round 1
            a = md4Round1(a, b, c, d, x[0], 3)
            d = md4Round1(d, a, b, c, x[1], 7)
            c = md4Round1(c, d, a, b, x[2], 11)
            b = md4Round1(b, c, d, a, x[3], 19)
            a = md4Round1(a, b, c, d, x[4], 3)
            d = md4Round1(d, a, b, c, x[5], 7)
            c = md4Round1(c, d, a, b, x[6], 11)
            b = md4Round1(b, c, d, a, x[7], 19)
            a = md4Round1(a, b, c, d, x[8], 3)
            d = md4Round1(d, a, b, c, x[9], 7)
            c = md4Round1(c, d, a, b, x[10], 11)
            b = md4Round1(b, c, d, a, x[11], 19)
            a = md4Round1(a, b, c, d, x[12], 3)
            d = md4Round1(d, a, b, c, x[13], 7)
            c = md4Round1(c, d, a, b, x[14], 11)
            b = md4Round1(b, c, d, a, x[15], 19)
            
            // Round 2
            a = md4Round2(a, b, c, d, x[0], 3)
            d = md4Round2(d, a, b, c, x[4], 5)
            c = md4Round2(c, d, a, b, x[8], 9)
            b = md4Round2(b, c, d, a, x[12], 13)
            a = md4Round2(a, b, c, d, x[1], 3)
            d = md4Round2(d, a, b, c, x[5], 5)
            c = md4Round2(c, d, a, b, x[9], 9)
            b = md4Round2(b, c, d, a, x[13], 13)
            a = md4Round2(a, b, c, d, x[2], 3)
            d = md4Round2(d, a, b, c, x[6], 5)
            c = md4Round2(c, d, a, b, x[10], 9)
            b = md4Round2(b, c, d, a, x[14], 13)
            a = md4Round2(a, b, c, d, x[3], 3)
            d = md4Round2(d, a, b, c, x[7], 5)
            c = md4Round2(c, d, a, b, x[11], 9)
            b = md4Round2(b, c, d, a, x[15], 13)
            
            // Round 3
            a = md4Round3(a, b, c, d, x[0], 3)
            d = md4Round3(d, a, b, c, x[8], 9)
            c = md4Round3(c, d, a, b, x[4], 11)
            b = md4Round3(b, c, d, a, x[12], 15)
            a = md4Round3(a, b, c, d, x[2], 3)
            d = md4Round3(d, a, b, c, x[10], 9)
            c = md4Round3(c, d, a, b, x[6], 11)
            b = md4Round3(b, c, d, a, x[14], 15)
            a = md4Round3(a, b, c, d, x[1], 3)
            d = md4Round3(d, a, b, c, x[9], 9)
            c = md4Round3(c, d, a, b, x[5], 11)
            b = md4Round3(b, c, d, a, x[13], 15)
            a = md4Round3(a, b, c, d, x[3], 3)
            d = md4Round3(d, a, b, c, x[11], 9)
            c = md4Round3(c, d, a, b, x[7], 11)
            b = md4Round3(b, c, d, a, x[15], 15)
            
            a += aa; b += bb; c += cc; d += dd
        }
        
        // Output
        val result = ByteArray(16)
        for (i in 0..3) {
            result[i] = (a shr (i * 8)).toByte()
            result[i + 4] = (b shr (i * 8)).toByte()
            result[i + 8] = (c shr (i * 8)).toByte()
            result[i + 12] = (d shr (i * 8)).toByte()
        }
        return result
    }
    
    private fun md4Round1(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = (b and c) or (b.inv() and d)
        return Integer.rotateLeft(a + f + x, s)
    }
    
    private fun md4Round2(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = (b and c) or (b and d) or (c and d)
        return Integer.rotateLeft(a + f + x + 0x5a827999, s)
    }
    
    private fun md4Round3(a: Int, b: Int, c: Int, d: Int, x: Int, s: Int): Int {
        val f = b xor c xor d
        return Integer.rotateLeft(a + f + x + 0x6ed9eba1, s)
    }
    
    private fun writeInt16LE(stream: java.io.ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
    }
    
    private fun writeInt32LE(stream: java.io.ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value shr 8) and 0xFF)
        stream.write((value shr 16) and 0xFF)
        stream.write((value shr 24) and 0xFF)
    }
    
    /**
     * Формирует список вариантов авторизации для EWS
     * Exchange 2007 может требовать разные форматы
     */
    private fun buildEwsAuthHeaders(): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        
        // 1. Стандартный Basic Auth (как для EAS)
        headers.add("Basic (domain\\user)" to getAuthHeader())
        
        // 2. Basic Auth с email как username (без домена)
        val emailAuth = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        headers.add("Basic (email)" to "Basic $emailAuth")
        
        // 3. Basic Auth с UPN форматом (user@domain)
        if (domain.isNotEmpty() && !username.contains("@")) {
            val upnAuth = Base64.encodeToString("$username@$domain:$password".toByteArray(), Base64.NO_WRAP)
            headers.add("Basic (UPN)" to "Basic $upnAuth")
        }
        
        // 4. Basic Auth только с именем пользователя (без домена в credentials)
        if (domain.isNotEmpty()) {
            val userOnly = username.substringAfterLast("\\")
            val userOnlyAuth = Base64.encodeToString("$userOnly:$password".toByteArray(), Base64.NO_WRAP)
            headers.add("Basic (user only)" to "Basic $userOnlyAuth")
        }
        
        return headers
    }
    
    /**
     * Парсит ответ EWS FindItem для Notes
     */
    private fun parseEwsNotesResponse(xml: String): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        // Ищем все PostItem или Message элементы (Notes в EWS)
        val itemPatterns = listOf(
            "<t:PostItem>(.*?)</t:PostItem>",
            "<t:Message>(.*?)</t:Message>",
            "<t:Item>(.*?)</t:Item>"
        )
        
        for (pattern in itemPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            regex.findAll(xml).forEach { match ->
                val itemXml = match.groupValues[1]
                
                // Проверяем ItemClass — должен быть IPM.StickyNote
                val itemClass = extractEwsValue(itemXml, "ItemClass") ?: ""
                if (itemClass.isNotEmpty() && !itemClass.contains("StickyNote", ignoreCase = true)) {
                    return@forEach
                }
                
                val itemId = extractEwsAttribute(itemXml, "ItemId", "Id") ?: return@forEach
                val subject = extractEwsValue(itemXml, "Subject") ?: ""
                val body = extractEwsValue(itemXml, "Body") ?: ""
                val lastModified = parseEwsDate(extractEwsValue(itemXml, "LastModifiedTime"))
                
                notes.add(EasNote(
                    serverId = itemId,
                    subject = subject.ifEmpty { "No subject" },
                    body = body,
                    categories = emptyList(),
                    lastModified = lastModified
                ))
            }
        }
        
        return notes
    }
    
    private fun extractEwsValue(xml: String, tag: String): String? {
        val patterns = listOf(
            "<t:$tag[^>]*>(.*?)</t:$tag>",
            "<$tag[^>]*>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
    
    private fun extractEwsAttribute(xml: String, tag: String, attr: String): String? {
        // Пробуем с namespace t:
        val pattern1 = "<t:$tag[^>]*$attr=\"([^\"]+)\"".toRegex()
        val match1 = pattern1.find(xml)
        if (match1 != null) return match1.groupValues[1]
        
        // Пробуем без namespace
        val pattern2 = "<$tag[^>]*$attr=\"([^\"]+)\"".toRegex()
        val match2 = pattern2.find(xml)
        return match2?.groupValues?.get(1)
    }
    
    private fun parseEwsDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val cleanDate = dateStr.substringBefore("Z").substringBefore("+")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(cleanDate)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
    
    /**
     * Fallback на Sync для legacy Notes (если Search не работает)
     * Для Exchange 2007 пробуем синхронизировать как email с фильтром IPM.StickyNote
     */
    private suspend fun syncNotesLegacySync(notesFolderId: String): EasResult<List<EasNote>> {
        android.util.Log.d("EasClient", "syncNotesLegacySync: === CALLED === folder=$notesFolderId")
        
        // Шаг 1: Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        when (val result = executeEasCommand("Sync", initialXml) { responseXml ->
            android.util.Log.d("EasClient", "syncNotesLegacySync: Initial response: ${responseXml.take(500)}")
            extractValue(responseXml, "SyncKey") ?: "0"
        }) {
            is EasResult.Success -> {
                syncKey = result.data
                android.util.Log.d("EasClient", "syncNotesLegacySync: Got initial SyncKey: $syncKey")
            }
            is EasResult.Error -> {
                android.util.Log.e("EasClient", "syncNotesLegacySync: Initial sync failed: ${result.message}")
                return EasResult.Success(emptyList())
            }
        }
        
        if (syncKey == "0") {
            android.util.Log.w("EasClient", "syncNotesLegacySync: SyncKey is 0, returning empty")
            return EasResult.Success(emptyList())
        }
        
        // Шаг 2: Запрашиваем элементы — пробуем разные варианты XML
        // Вариант 1: Минимальный Sync без Options
        val syncXml1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$notesFolderId</CollectionId>
                        <GetChanges/>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        android.util.Log.d("EasClient", "syncNotesLegacySync: Trying minimal Sync...")
        
        val result1 = executeEasCommandRaw("Sync", syncXml1)
        when (result1) {
            is EasResult.Success -> {
                val responseXml = result1.data
                android.util.Log.d("EasClient", "syncNotesLegacySync: Raw response (first 3000 chars):\n${responseXml.take(3000)}")
                
                // Проверяем есть ли Add элементы
                if (responseXml.contains("<Add>")) {
                    val notes = parseNotesResponse(responseXml, legacy = true)
                    android.util.Log.d("EasClient", "syncNotesLegacySync: Parsed ${notes.size} notes")
                    return EasResult.Success(notes)
                }
                
                // Проверяем Status
                val status = extractValue(responseXml, "Status")
                android.util.Log.d("EasClient", "syncNotesLegacySync: Status = $status")
                
                if (status == "1" && !responseXml.contains("<Add>")) {
                    // Успех но нет данных — папка пустая или не поддерживается
                    android.util.Log.w("EasClient", "syncNotesLegacySync: Status=1 but no Add elements. Exchange 2007 may not support Notes sync via EAS.")
                }
            }
            is EasResult.Error -> {
                android.util.Log.e("EasClient", "syncNotesLegacySync: Sync failed: ${result1.message}")
            }
        }
        
        return EasResult.Success(emptyList())
    }
    
    /**
     * Выполняет EAS команду и возвращает сырой XML ответ
     */
    private suspend fun executeEasCommandRaw(command: String, xmlBody: String): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(command)
                val wbxmlBody = wbxmlParser.generate(xmlBody)
                
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(wbxmlBody.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                    .header("Authorization", getAuthHeader())
                    .header("MS-ASProtocolVersion", easVersion)
                    .header("Content-Type", "application/vnd.ms-sync.wbxml")
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                if (policyKey != null) {
                    requestBuilder.header("X-MS-PolicyKey", policyKey!!)
                }
                
                val request = requestBuilder.build()
                val response = executeRequest(request)
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.bytes()
                    if (responseBody != null && responseBody.isNotEmpty()) {
                        val responseXml = wbxmlParser.parse(responseBody)
                        EasResult.Success(responseXml)
                    } else {
                        // Пустой ответ — возвращаем пустой XML
                        EasResult.Success("")
                    }
                } else {
                    EasResult.Error("HTTP ${response.code}")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Парсинг ответа Search для заметок
     */
    private fun parseNotesSearchResponse(xml: String): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        val resultPattern = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        resultPattern.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]
            
            val serverId = extractValue(resultXml, "LongId") 
                ?: extractValue(resultXml, "ServerId") 
                ?: return@forEach
            
            val propertiesPattern = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val propertiesMatch = propertiesPattern.find(resultXml) ?: return@forEach
            val propsXml = propertiesMatch.groupValues[1]
            
            // Проверяем MessageClass
            val messageClass = extractValue(propsXml, "MessageClass") ?: ""
            if (messageClass.isNotEmpty() && !messageClass.contains("StickyNote", ignoreCase = true)) {
                return@forEach
            }
            
            val subject = extractValue(propsXml, "Subject") ?: ""
            val body = extractNoteBody(propsXml)
            val lastModified = parseNoteDate(extractValue(propsXml, "DateReceived"))
            
            notes.add(EasNote(
                serverId = serverId,
                subject = subject.ifEmpty { "No subject" },
                body = body,
                categories = emptyList(),
                lastModified = lastModified
            ))
        }
        
        return notes
    }
    
    /**
     * Парсинг ответа Notes sync
     * @param legacy true для Exchange 2007 (фильтруем по IPM.StickyNote)
     */
    private fun parseNotesResponse(xml: String, legacy: Boolean): List<EasNote> {
        val notes = mutableListOf<EasNote>()
        
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val addXml = match.groupValues[1]
            val serverId = extractValue(addXml, "ServerId") ?: return@forEach
            
            val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val dataMatch = dataPattern.find(addXml) ?: return@forEach
            val dataXml = dataMatch.groupValues[1]
            
            // Для legacy режима фильтруем по MessageClass
            if (legacy) {
                val messageClass = extractValue(dataXml, "MessageClass") ?: ""
                if (!messageClass.contains("StickyNote", ignoreCase = true)) {
                    return@forEach
                }
            }
            
            val subject = extractNoteValue(dataXml, "Subject") 
                ?: extractValue(dataXml, "Subject") 
                ?: ""
            val body = extractNoteBody(dataXml)
            val categories = extractNoteCategories(dataXml)
            val lastModified = parseNoteDate(
                extractNoteValue(dataXml, "LastModifiedDate")
                    ?: extractValue(dataXml, "DateReceived")
            )
            
            notes.add(EasNote(
                serverId = serverId,
                subject = subject.ifEmpty { "No subject" },
                body = body,
                categories = categories,
                lastModified = lastModified
            ))
        }
        
        return notes
    }
    
    private fun extractNoteValue(xml: String, tag: String): String? {
        // Заметки используют namespace notes:
        val patterns = listOf(
            "<notes:$tag>(.*?)</notes:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
    
    private fun extractNoteBody(xml: String): String {
        // Тело заметки может быть в airsyncbase:Body/Data или notes:Body
        val bodyPatterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<notes:Body>(.*?)</notes:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return ""
    }
    
    private fun extractNoteCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesPattern = "<notes:Categories>(.*?)</notes:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val categoriesMatch = categoriesPattern.find(xml)
        if (categoriesMatch != null) {
            val categoriesXml = categoriesMatch.groupValues[1]
            NOTES_CATEGORY_REGEX.findAll(categoriesXml).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
        }
        return categories
    }
    
    private fun parseNoteDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            // Формат: 2025-01-15T10:30:00.000Z
            val cleanDate = dateStr.substringBefore("Z").substringBefore(".")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(cleanDate)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
    
    /**
     * Создание события календаря на сервере Exchange через EAS
     * Работает на всех версиях EAS (12.x и 14.x)
     */
    suspend fun createCalendarEvent(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String = "",
        body: String = "",
        allDayEvent: Boolean = false,
        reminder: Int = 15,
        busyStatus: Int = 2, // 0=Free, 1=Tentative, 2=Busy, 3=OOF
        sensitivity: Int = 0, // 0=Normal, 1=Personal, 2=Private, 3=Confidential
        attendees: List<String> = emptyList() // Список email участников
    ): EasResult<String> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        
        // Для Exchange 2007 (EAS 12.x) используем EWS
        return if (majorVersion >= 14) {
            createCalendarEventEas(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees)
        } else {
            createCalendarEventEws(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees)
        }
    }
    
    /**
     * Создание события календаря через EAS (Exchange 2010+)
     */
    private suspend fun createCalendarEventEas(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attendees: List<String>
    ): EasResult<String> {
        // Получаем папку календаря
        val foldersResult = folderSync("0")
        val calendarFolderId = when (foldersResult) {
            is EasResult.Success -> {
                foldersResult.data.folders.find { it.type == 8 }?.serverId
            }
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        if (calendarFolderId == null) {
            return EasResult.Error("Папка календаря не найдена")
        }
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        // Генерируем уникальный ClientId
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        
        // Форматируем даты в формат EAS (yyyyMMdd'T'HHmmss'Z')
        val dateFormat = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val startTimeStr = dateFormat.format(java.util.Date(startTime))
        val endTimeStr = dateFormat.format(java.util.Date(endTime))
        
        // Экранируем XML
        val escapedSubject = escapeXml(subject)
        val escapedLocation = escapeXml(location)
        val escapedBody = escapeXml(body)
        
        // MeetingStatus: 0=Appointment, 1=Meeting (organizer), 3=Meeting (attendee)
        val meetingStatus = if (attendees.isNotEmpty()) 1 else 0
        
        // Формируем блок участников для EAS
        val attendeesXml = if (attendees.isNotEmpty()) {
            buildString {
                append("<calendar:Attendees>")
                for (email in attendees) {
                    val escapedEmail = escapeXml(email.trim())
                    append("<calendar:Attendee>")
                    append("<calendar:Email>$escapedEmail</calendar:Email>")
                    append("<calendar:AttendeeType>1</calendar:AttendeeType>") // 1=Required
                    append("<calendar:AttendeeStatus>0</calendar:AttendeeStatus>") // 0=Response unknown
                    append("</calendar:Attendee>")
                }
                append("</calendar:Attendees>")
            }
        } else ""
        
        // Формируем XML для создания события
        val createXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                        <Commands>
                            <Add>
                                <ClientId>$clientId</ClientId>
                                <ApplicationData>
                                    <calendar:Subject>$escapedSubject</calendar:Subject>
                                    <calendar:StartTime>$startTimeStr</calendar:StartTime>
                                    <calendar:EndTime>$endTimeStr</calendar:EndTime>
                                    <calendar:Location>$escapedLocation</calendar:Location>
                                    <airsyncbase:Body>
                                        <airsyncbase:Type>1</airsyncbase:Type>
                                        <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                                    </airsyncbase:Body>
                                    <calendar:AllDayEvent>${if (allDayEvent) "1" else "0"}</calendar:AllDayEvent>
                                    <calendar:Reminder>$reminder</calendar:Reminder>
                                    <calendar:BusyStatus>$busyStatus</calendar:BusyStatus>
                                    <calendar:Sensitivity>$sensitivity</calendar:Sensitivity>
                                    <calendar:MeetingStatus>$meetingStatus</calendar:MeetingStatus>
                                    $attendeesXml
                                </ApplicationData>
                            </Add>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", createXml) { responseXml ->
            // Проверяем статус
            val status = extractValue(responseXml, "Status")
            if (status == "1") {
                // Ищем ServerId созданного события
                val serverId = extractValue(responseXml, "ServerId") ?: clientId
                serverId
            } else {
                throw Exception("Ошибка создания события: Status=$status")
            }
        }
    }
    
    /**
     * Создание события календаря через EWS (Exchange 2007)
     * Формат XML по официальной документации Microsoft:
     * https://docs.microsoft.com/en-us/exchange/client-developer/web-service-reference/createitem-operation-calendar-item
     */
    private suspend fun createCalendarEventEws(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attendees: List<String>
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                val escapedSubject = escapeXml(subject)
                val escapedLocation = escapeXml(location)
                val escapedBody = escapeXml(body)
                
                // Формат даты как в официальном примере MS: без Z на конце
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val startTimeStr = dateFormat.format(java.util.Date(startTime))
                val endTimeStr = dateFormat.format(java.util.Date(endTime))
                
                // Маппинг LegacyFreeBusyStatus
                val ewsBusyStatus = when (busyStatus) {
                    0 -> "Free"
                    1 -> "Tentative"
                    3 -> "OOF"
                    else -> "Busy"
                }
                
                // Если есть участники - это митинг, отправляем приглашения
                val sendInvitations = if (attendees.isNotEmpty()) "SendToAllAndSaveCopy" else "SendToNone"
                
                // Формируем блок участников по официальному примеру Microsoft
                val attendeesXml = if (attendees.isNotEmpty()) {
                    buildString {
                        append("<RequiredAttendees>")
                        for (email in attendees) {
                            val escapedEmail = escapeXml(email.trim())
                            append("<Attendee>")
                            append("<Mailbox>")
                            append("<EmailAddress>$escapedEmail</EmailAddress>")
                            append("</Mailbox>")
                            append("</Attendee>")
                        }
                        append("</RequiredAttendees>")
                    }
                } else ""
                
                // Формируем SOAP запрос ТОЧНО по официальному примеру Microsoft:
                // Внутри CalendarItem элементы БЕЗ префикса t:, используется xmlns на CalendarItem
                val soapRequest = buildString {
                    append("""<?xml version="1.0" encoding="utf-8"?>""")
                    append("""<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
                    append("""xmlns:xsd="http://www.w3.org/2001/XMLSchema" """)
                    append("""xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">""")
                    append("<soap:Body>")
                    append("""<CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """)
                    append("""SendMeetingInvitations="$sendInvitations">""")
                    append("<SavedItemFolderId>")
                    append("""<t:DistinguishedFolderId Id="calendar"/>""")
                    append("</SavedItemFolderId>")
                    append("<Items>")
                    // CalendarItem с xmlns - внутренние элементы без префикса (как в официальном примере MS)
                    append("""<t:CalendarItem xmlns="http://schemas.microsoft.com/exchange/services/2006/types">""")
                    append("<Subject>$escapedSubject</Subject>")
                    if (escapedBody.isNotBlank()) {
                        append("""<Body BodyType="Text">$escapedBody</Body>""")
                    }
                    if (reminder > 0) {
                        append("<ReminderIsSet>true</ReminderIsSet>")
                        append("<ReminderMinutesBeforeStart>$reminder</ReminderMinutesBeforeStart>")
                    } else {
                        append("<ReminderIsSet>false</ReminderIsSet>")
                    }
                    append("<Start>$startTimeStr</Start>")
                    append("<End>$endTimeStr</End>")
                    append("<IsAllDayEvent>$allDayEvent</IsAllDayEvent>")
                    append("<LegacyFreeBusyStatus>$ewsBusyStatus</LegacyFreeBusyStatus>")
                    if (escapedLocation.isNotBlank()) {
                        append("<Location>$escapedLocation</Location>")
                    }
                    // Добавляем участников если есть
                    append(attendeesXml)
                    append("</t:CalendarItem>")
                    append("</Items>")
                    append("</CreateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                android.util.Log.d("EasClient", "createCalendarEventEws: Request: $soapRequest")
                
                // Пробуем NTLM аутентификацию
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                android.util.Log.d("EasClient", "createCalendarEventEws: Response: ${responseXml?.take(2000)}")
                
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                // Проверяем на ошибки
                if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                    return@withContext EasResult.Error("Ошибка схемы EWS")
                }
                
                // Извлекаем ItemId
                val itemId = EWS_ITEM_ID_REGEX.find(responseXml)?.groupValues?.get(1)
                
                if (itemId != null) {
                    EasResult.Success(itemId)
                } else if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                    EasResult.Success("pending_sync_${System.currentTimeMillis()}")
                } else {
                    EasResult.Error("Не удалось создать событие через EWS")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка создания события через EWS")
            }
        }
    }
    
    /**
     * Удаление события календаря на сервере Exchange
     */
    suspend fun deleteCalendarEvent(serverId: String): EasResult<Boolean> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Получаем папку календаря
        val foldersResult = folderSync("0")
        val calendarFolderId = when (foldersResult) {
            is EasResult.Success -> {
                foldersResult.data.folders.find { it.type == 8 }?.serverId
            }
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        if (calendarFolderId == null) {
            return EasResult.Error("Папка календаря не найдена")
        }
        
        // Шаг 1: Получаем начальный SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        // Шаг 2: Делаем полную синхронизацию чтобы получить актуальный SyncKey
        val fullSyncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:AirSyncBase="AirSyncBase" xmlns:Calendar="Calendar">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                        <GetChanges>1</GetChanges>
                        <WindowSize>100</WindowSize>
                        <Options>
                            <AirSyncBase:BodyPreference>
                                <AirSyncBase:Type>1</AirSyncBase:Type>
                                <AirSyncBase:TruncationSize>0</AirSyncBase:TruncationSize>
                            </AirSyncBase:BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        val fullSyncResult = executeEasCommand("Sync", fullSyncXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: syncKey
        }
        
        when (fullSyncResult) {
            is EasResult.Success -> syncKey = fullSyncResult.data
            is EasResult.Error -> return EasResult.Error(fullSyncResult.message)
        }
        
        // Шаг 3: Формируем XML для удаления с актуальным SyncKey
        val deleteXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                        <Commands>
                            <Delete>
                                <ServerId>$serverId</ServerId>
                            </Delete>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", deleteXml) { responseXml ->
            val status = extractValue(responseXml, "Status")
            status == "1"
        }
    }
    
    /**
     * Удаление события календаря через EWS (для Exchange 2007)
     */
    private suspend fun deleteCalendarEventEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                // Проверяем формат serverId
                val itemInfo = if (serverId.length < 50 || serverId.contains(":")) {
                    findEwsCalendarItemIdWithChangeKey(ewsUrl, serverId)
                } else {
                    Pair(serverId, "")
                }
                
                if (itemInfo == null) {
                    return@withContext EasResult.Error("Не удалось найти событие на сервере")
                }
                
                val (ewsItemId, changeKey) = itemInfo
                val escapedItemId = escapeXml(ewsItemId)
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:DeleteItem DeleteType="MoveToDeletedItems" SendMeetingCancellations="SendToNone">
                                <m:ItemIds>
                                    <t:ItemId Id="$escapedItemId"/>
                                </m:ItemIds>
                            </m:DeleteItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // Используем только NTLM для Exchange 2007
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"") || responseXml.contains("ErrorItemNotFound")) {
                    return@withContext EasResult.Success(true)
                }
                
                // Если ошибка - возможно мы участник, а не организатор
                // Пробуем отклонить приглашение через DeclineItem
                if (changeKey.isNotEmpty()) {
                    val declineResult = declineAndDeleteMeeting(ewsUrl, ewsItemId, changeKey)
                    if (declineResult is EasResult.Success) {
                        return@withContext declineResult
                    }
                }
                
                EasResult.Error("Не удалось удалить событие")
            } catch (e: Exception) {
                EasResult.Error("Ошибка EWS: ${e.message}")
            }
        }
    }
    
    /**
     * Отклонить приглашение и удалить из календаря (для событий где мы участник)
     */
    private suspend fun declineAndDeleteMeeting(ewsUrl: String, itemId: String, changeKey: String): EasResult<Boolean> {
        val escapedItemId = escapeXml(itemId)
        val escapedChangeKey = escapeXml(changeKey)
        
        val soapRequest = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                           xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                <soap:Header>
                    <t:RequestServerVersion Version="Exchange2007_SP1"/>
                </soap:Header>
                <soap:Body>
                    <m:CreateItem MessageDisposition="SendAndSaveCopy">
                        <m:Items>
                            <t:DeclineItem>
                                <t:ReferenceItemId Id="$escapedItemId" ChangeKey="$escapedChangeKey"/>
                            </t:DeclineItem>
                        </m:Items>
                    </m:CreateItem>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
        
        val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
            ?: return EasResult.Error("NTLM аутентификация не удалась")
        
        val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
            ?: return EasResult.Error("Не удалось выполнить запрос")
        
        return if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
            EasResult.Success(true)
        } else {
            EasResult.Error("Не удалось отклонить приглашение")
        }
    }
    
    /**
     * Находит EWS ItemId события календаря (без ChangeKey)
     */
    private suspend fun findEwsCalendarItemId(ewsUrl: String, easServerId: String): String? {
        val result = findEwsCalendarItemIdWithChangeKey(ewsUrl, easServerId)
        return result?.first
    }
    
    /**
     * Находит EWS ItemId события календаря с ChangeKey
     * Возвращает пару (ItemId, ChangeKey)
     */
    private suspend fun findEwsCalendarItemIdWithChangeKey(ewsUrl: String, easServerId: String): Pair<String, String>? {
        // Если это уже EWS ItemId (длинная строка), пробуем получить ChangeKey через GetItem
        if (easServerId.length > 50 && !easServerId.contains(":")) {
            val getItemRequest = """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                    <soap:Header>
                        <t:RequestServerVersion Version="Exchange2007_SP1"/>
                    </soap:Header>
                    <soap:Body>
                        <m:GetItem>
                            <m:ItemShape>
                                <t:BaseShape>IdOnly</t:BaseShape>
                            </m:ItemShape>
                            <m:ItemIds>
                                <t:ItemId Id="${escapeXml(easServerId)}"/>
                            </m:ItemIds>
                        </m:GetItem>
                    </soap:Body>
                </soap:Envelope>
            """.trimIndent()
            
            val ntlmAuth = performNtlmHandshake(ewsUrl, getItemRequest, "GetItem")
            if (ntlmAuth != null) {
                val responseXml = executeNtlmRequest(ewsUrl, getItemRequest, ntlmAuth, "GetItem")
                if (responseXml != null) {
                    val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"\\s+ChangeKey=\"([^\"]+)\"".toRegex()
                    val match = itemIdPattern.find(responseXml)
                    if (match != null) {
                        return Pair(match.groupValues[1], match.groupValues[2])
                    }
                }
            }
            // Если не удалось получить ChangeKey, возвращаем без него
            return Pair(easServerId, "")
        }
        
        // Для коротких EAS serverId ищем все события и пытаемся найти по индексу
        val findRequest = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                           xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                <soap:Header>
                    <t:RequestServerVersion Version="Exchange2007_SP1"/>
                </soap:Header>
                <soap:Body>
                    <m:FindItem Traversal="Shallow">
                        <m:ItemShape>
                            <t:BaseShape>IdOnly</t:BaseShape>
                        </m:ItemShape>
                        <m:IndexedPageItemView MaxEntriesReturned="500" Offset="0" BasePoint="Beginning"/>
                        <m:ParentFolderIds>
                            <t:DistinguishedFolderId Id="calendar"/>
                        </m:ParentFolderIds>
                    </m:FindItem>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
        
        // Используем только NTLM для Exchange 2007
        val ntlmAuth = performNtlmHandshake(ewsUrl, findRequest, "FindItem")
        if (ntlmAuth == null) {
            return null
        }
        
        val responseXml = executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
        if (responseXml == null) {
            return null
        }
        
        // Ищем ItemId с ChangeKey
        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"\\s+ChangeKey=\"([^\"]+)\"".toRegex()
        val matches = itemIdPattern.findAll(responseXml).toList()
        
        val index = easServerId.substringAfter(":").toIntOrNull()?.minus(1) ?: 0
        val match = matches.getOrNull(index) ?: matches.firstOrNull()
        return match?.let { Pair(it.groupValues[1], it.groupValues[2]) }
    }
    
    /**
     * Обновление события календаря на сервере Exchange
     */
    suspend fun updateCalendarEvent(
        serverId: String,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String = "",
        body: String = "",
        allDayEvent: Boolean = false,
        reminder: Int = 15,
        busyStatus: Int = 2,
        sensitivity: Int = 0
    ): EasResult<Boolean> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Получаем папку календаря
        val foldersResult = folderSync("0")
        val calendarFolderId = when (foldersResult) {
            is EasResult.Success -> {
                foldersResult.data.folders.find { it.type == 8 }?.serverId
            }
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        if (calendarFolderId == null) {
            return EasResult.Error("Папка календаря не найдена")
        }
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        // Форматируем даты
        val dateFormat = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val startTimeStr = dateFormat.format(java.util.Date(startTime))
        val endTimeStr = dateFormat.format(java.util.Date(endTime))
        
        // Экранируем XML
        val escapedSubject = escapeXml(subject)
        val escapedLocation = escapeXml(location)
        val escapedBody = escapeXml(body)
        
        // Формируем XML для обновления
        val updateXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                        <Commands>
                            <Change>
                                <ServerId>$serverId</ServerId>
                                <ApplicationData>
                                    <calendar:Subject>$escapedSubject</calendar:Subject>
                                    <calendar:StartTime>$startTimeStr</calendar:StartTime>
                                    <calendar:EndTime>$endTimeStr</calendar:EndTime>
                                    <calendar:Location>$escapedLocation</calendar:Location>
                                    <airsyncbase:Body>
                                        <airsyncbase:Type>1</airsyncbase:Type>
                                        <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                                    </airsyncbase:Body>
                                    <calendar:AllDayEvent>${if (allDayEvent) "1" else "0"}</calendar:AllDayEvent>
                                    <calendar:Reminder>$reminder</calendar:Reminder>
                                    <calendar:BusyStatus>$busyStatus</calendar:BusyStatus>
                                    <calendar:Sensitivity>$sensitivity</calendar:Sensitivity>
                                </ApplicationData>
                            </Change>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", updateXml) { responseXml ->
            val status = extractValue(responseXml, "Status")
            status == "1"
        }
    }
    
    /**
     * Ответ на приглашение на встречу через EAS MeetingResponse
     * @param serverId ID события календаря (формат: accountId_easServerId)
     * @param response Тип ответа: "Accept", "Tentative", "Decline"
     * @param sendResponse Отправить ответ организатору (не используется в EAS 12.1)
     */
    suspend fun respondToMeetingRequest(
        serverId: String,
        response: String,
        sendResponse: Boolean = true
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                // Извлекаем EAS serverId из формата "accountId_serverId"
                val easServerId = if (serverId.contains("_")) {
                    serverId.substringAfter("_")
                } else {
                    serverId
                }
                
                // Получаем ID папки календаря
                val foldersResult = folderSync("0")
                val calendarFolderId = when (foldersResult) {
                    is EasResult.Success -> {
                        foldersResult.data.folders.find { it.type == 8 }?.serverId
                    }
                    is EasResult.Error -> return@withContext EasResult.Error(foldersResult.message)
                }
                
                if (calendarFolderId == null) {
                    return@withContext EasResult.Error("Папка календаря не найдена")
                }
                
                // Определяем UserResponse: 1=Accept, 2=Tentative, 3=Decline
                val userResponse = when (response.lowercase()) {
                    "accept" -> 1
                    "tentative" -> 2
                    "decline" -> 3
                    else -> 1
                }
                
                // Формируем MeetingResponse запрос
                val meetingResponseXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <MeetingResponse xmlns="MeetingResponse">
                        <Request>
                            <UserResponse>$userResponse</UserResponse>
                            <CollectionId>$calendarFolderId</CollectionId>
                            <RequestId>$easServerId</RequestId>
                        </Request>
                    </MeetingResponse>
                """.trimIndent()
                
                val result = executeEasCommand("MeetingResponse", meetingResponseXml) { responseXml ->
                    // Проверяем Status в ответе
                    val status = extractValue(responseXml, "Status")
                    when (status) {
                        "1" -> true // Success
                        "2" -> throw Exception("Неверный запрос на собрание")
                        "3" -> throw Exception("Ошибка сервера при обработке запроса")
                        "4" -> throw Exception("Неверный ID запроса на собрание")
                        else -> {
                            // Проверяем наличие CalendarId - это тоже успех
                            if (responseXml.contains("CalendarId")) {
                                true
                            } else {
                                throw Exception("Неизвестная ошибка: Status=$status")
                            }
                        }
                    }
                }
                
                when (result) {
                    is EasResult.Success -> EasResult.Success(true)
                    is EasResult.Error -> {
                        // Если EAS не сработал, пробуем EWS как fallback
                        respondToMeetingRequestEws(serverId, response, sendResponse)
                    }
                }
            } catch (e: Exception) {
                // Пробуем EWS как fallback
                respondToMeetingRequestEws(serverId, response, sendResponse)
            }
        }
    }
    
    /**
     * Ответ на приглашение через EWS (fallback для EAS)
     */
    private suspend fun respondToMeetingRequestEws(
        serverId: String,
        response: String,
        sendResponse: Boolean
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                // Находим EWS ItemId с ChangeKey
                val itemInfo = findEwsCalendarItemIdWithChangeKey(ewsUrl, serverId)
                
                if (itemInfo == null) {
                    return@withContext EasResult.Error("Не удалось найти событие на сервере")
                }
                
                val (ewsItemId, changeKey) = itemInfo
                val escapedItemId = escapeXml(ewsItemId)
                val escapedChangeKey = escapeXml(changeKey)
                
                // Определяем тип ответа для EWS
                val responseType = when (response.lowercase()) {
                    "accept" -> "AcceptItem"
                    "tentative" -> "TentativelyAcceptItem"
                    "decline" -> "DeclineItem"
                    else -> "AcceptItem"
                }
                
                val messageDisposition = if (sendResponse) "SendAndSaveCopy" else "SaveOnly"
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:CreateItem MessageDisposition="$messageDisposition">
                                <m:Items>
                                    <t:$responseType>
                                        <t:ReferenceItemId Id="$escapedItemId" ChangeKey="$escapedChangeKey"/>
                                    </t:$responseType>
                                </m:Items>
                            </m:CreateItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                    return@withContext EasResult.Success(true)
                }
                
                // Извлекаем сообщение об ошибке
                val errorMsg = EWS_MESSAGE_TEXT_REGEX.find(responseXml)?.groupValues?.get(1) ?: "Неизвестная ошибка"
                EasResult.Error(errorMsg)
            } catch (e: Exception) {
                EasResult.Error("Ошибка EWS: ${e.message}")
            }
        }
    }
    
    /**
     * Синхронизация календаря из папки Calendar на сервере Exchange
     * Возвращает список событий
     */
    suspend fun syncCalendar(): EasResult<List<EasCalendarEvent>> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Сначала получаем список папок чтобы найти папку Calendar (type = 8)
        val foldersResult = folderSync("0")
        val folders = when (foldersResult) {
            is EasResult.Success -> foldersResult.data.folders
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        // Определяем версию EAS
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            // EAS 14+ — стандартный Calendar sync
            syncCalendarStandard(folders)
        } else {
            // EAS 12.x (Exchange 2007) — fallback через Search/EWS
            syncCalendarLegacy(folders)
        }
    }
    
    /**
     * Стандартная синхронизация календаря для EAS 14+ (Exchange 2010+)
     */
    private suspend fun syncCalendarStandard(folders: List<EasFolder>): EasResult<List<EasCalendarEvent>> {
        val calendarFolderId = folders.find { it.type == 8 }?.serverId
            ?: return EasResult.Error("Папка календаря не найдена")
        
        cachedCalendarFolderId = calendarFolderId
        
        // Шаг 1: Получаем начальный SyncKey для папки календаря
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        // Шаг 2: Запрашиваем события календаря
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                        <DeletesAsMoves/>
                        <GetChanges/>
                        <WindowSize>100</WindowSize>
                        <Options>
                            <airsyncbase:BodyPreference>
                                <airsyncbase:Type>1</airsyncbase:Type>
                                <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                            </airsyncbase:BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            parseCalendarSyncResponse(responseXml)
        }
    }
    
    /**
     * Legacy синхронизация календаря для Exchange 2007 (EAS 12.x)
     * Используем прямой Sync как в v1.4.2 (без Search/EWS fallback)
     */
    private suspend fun syncCalendarLegacy(folders: List<EasFolder>): EasResult<List<EasCalendarEvent>> {
        // Ищем папку Calendar по типу
        val calendarFolderId = folders.find { it.type == 8 }?.serverId
        
        if (calendarFolderId == null) {
            return EasResult.Error("Папка календаря не найдена")
        }
        
        cachedCalendarFolderId = calendarFolderId
        
        // Шаг 1: Получаем начальный SyncKey для папки календаря
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        // Шаг 2: Запрашиваем события календаря
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$calendarFolderId</CollectionId>
                        <DeletesAsMoves/>
                        <GetChanges/>
                        <WindowSize>100</WindowSize>
                        <Options>
                            <airsyncbase:BodyPreference>
                                <airsyncbase:Type>1</airsyncbase:Type>
                                <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                            </airsyncbase:BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            parseCalendarSyncResponse(responseXml)
        }
    }
    
    /**
     * Парсинг событий календаря из Search ответа
     */
    private fun parseCalendarSearchResponse(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()
        
        val resultPattern = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        resultPattern.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]
            
            val serverId = extractValue(resultXml, "LongId") 
                ?: extractValue(resultXml, "ServerId") 
                ?: return@forEach
            
            val propsPattern = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val propsMatch = propsPattern.find(resultXml)
            if (propsMatch != null) {
                val propsXml = propsMatch.groupValues[1]
                
                val subject = extractCalendarValue(propsXml, "Subject") ?: ""
                val location = extractCalendarValue(propsXml, "Location") ?: ""
                val body = extractCalendarBody(propsXml)
                val startTime = parseCalendarDate(extractCalendarValue(propsXml, "StartTime")) ?: 0L
                val endTime = parseCalendarDate(extractCalendarValue(propsXml, "EndTime")) ?: 0L
                val allDayEvent = extractCalendarValue(propsXml, "AllDayEvent") == "1"
                val reminder = extractCalendarValue(propsXml, "Reminder")?.toIntOrNull() ?: 0
                val busyStatus = extractCalendarValue(propsXml, "BusyStatus")?.toIntOrNull() ?: 2
                val sensitivity = extractCalendarValue(propsXml, "Sensitivity")?.toIntOrNull() ?: 0
                val organizer = extractCalendarValue(propsXml, "OrganizerEmail") 
                    ?: extractCalendarValue(propsXml, "Organizer_Email") ?: ""
                val attendees = parseCalendarAttendees(propsXml)
                val categories = extractCalendarCategories(propsXml)
                val isRecurring = propsXml.contains("<calendar:Recurrence>") || propsXml.contains("<Recurrence>")
                val lastModified = parseCalendarDate(extractCalendarValue(propsXml, "DtStamp")) ?: 0L
                
                events.add(EasCalendarEvent(
                    serverId = serverId,
                    subject = subject,
                    location = location,
                    body = body,
                    startTime = startTime,
                    endTime = endTime,
                    allDayEvent = allDayEvent,
                    reminder = reminder,
                    busyStatus = busyStatus,
                    sensitivity = sensitivity,
                    organizer = organizer,
                    attendees = attendees,
                    isRecurring = isRecurring,
                    categories = categories,
                    lastModified = lastModified
                ))
            }
        }
        
        return events
    }
    
    /**
     * Синхронизация календаря через EWS (для Exchange 2007)
     */
    private suspend fun syncCalendarEws(): EasResult<List<EasCalendarEvent>> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                // Используем только NTLM для Exchange 2007
                val ntlmResult = syncCalendarEwsNtlm(ewsUrl)
                if (ntlmResult is EasResult.Success && ntlmResult.data.isNotEmpty()) {
                    return@withContext ntlmResult
                }
                
                EasResult.Success(emptyList())
            } catch (e: Exception) {
                EasResult.Error("Ошибка EWS: ${e.message}")
            }
        }
    }
    
    /**
     * EWS запрос календаря с NTLM аутентификацией
     */
    private suspend fun syncCalendarEwsNtlm(ewsUrl: String): EasResult<List<EasCalendarEvent>> {
        try {
            val soapRequest = """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                    <soap:Header>
                        <t:RequestServerVersion Version="Exchange2007_SP1"/>
                    </soap:Header>
                    <soap:Body>
                        <m:FindItem Traversal="Shallow">
                            <m:ItemShape>
                                <t:BaseShape>AllProperties</t:BaseShape>
                                <t:BodyType>Text</t:BodyType>
                            </m:ItemShape>
                            <m:IndexedPageItemView MaxEntriesReturned="200" Offset="0" BasePoint="Beginning"/>
                            <m:ParentFolderIds>
                                <t:DistinguishedFolderId Id="calendar"/>
                            </m:ParentFolderIds>
                        </m:FindItem>
                    </soap:Body>
                </soap:Envelope>
            """.trimIndent()
            
            // Получаем NTLM auth header
            val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "FindItem")
                ?: return EasResult.Success(emptyList())
            
            // Выполняем FindItem с NTLM
            val responseBody = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "FindItem")
                ?: return EasResult.Success(emptyList())
            
            if (responseBody.contains("CalendarItem")) {
                val events = parseEwsCalendarResponse(responseBody)
                return EasResult.Success(events)
            }
            
            return EasResult.Success(emptyList())
        } catch (e: Exception) {
            return EasResult.Error("NTLM ошибка: ${e.message}")
        }
    }
    
    /**
     * Парсинг событий календаря из EWS ответа
     */
    private fun parseEwsCalendarResponse(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()
        
        val itemPattern = "<t:CalendarItem>(.*?)</t:CalendarItem>".toRegex(RegexOption.DOT_MATCHES_ALL)
        itemPattern.findAll(xml).forEach { match ->
            val itemXml = match.groupValues[1]
            
            val itemId = extractEwsValue(itemXml, "ItemId")?.let { idXml ->
                "Id=\"([^\"]+)\"".toRegex().find(idXml)?.groupValues?.get(1)
            } ?: return@forEach
            
            val subject = extractEwsValue(itemXml, "Subject") ?: ""
            val location = extractEwsValue(itemXml, "Location") ?: ""
            val rawBody = extractEwsValue(itemXml, "Body") ?: ""
            // Исправляем кодировку body если нужно
            val body = fixEncoding(rawBody)
            val startTime = parseEwsDate(extractEwsValue(itemXml, "Start"))
            val endTime = parseEwsDate(extractEwsValue(itemXml, "End"))
            val allDayEvent = extractEwsValue(itemXml, "IsAllDayEvent") == "true"
            val reminder = extractEwsValue(itemXml, "ReminderMinutesBeforeStart")?.toIntOrNull() ?: 0
            val organizer = extractEwsValue(itemXml, "Organizer")?.let { orgXml ->
                extractEwsValue(orgXml, "EmailAddress") ?: ""
            } ?: ""
            val isRecurring = extractEwsValue(itemXml, "IsRecurring") == "true"
            val lastModified = parseEwsDate(extractEwsValue(itemXml, "LastModifiedTime"))
            
            // Определяем это встреча или обычное событие
            val isMeeting = extractEwsValue(itemXml, "IsMeeting") == "true" || organizer.isNotBlank()
            
            // Парсим статус ответа (MyResponseType)
            // Unknown=0, Organizer=1, Tentative=2, Accept=3, Decline=4, NoResponseReceived=5
            val myResponseType = extractEwsValue(itemXml, "MyResponseType") ?: ""
            val responseStatus = when (myResponseType.lowercase()) {
                "accept" -> 2 // ACCEPTED
                "tentative" -> 3 // TENTATIVE
                "decline" -> 4 // DECLINED
                "noresponserecieved", "unknown" -> 1 // NOT_RESPONDED
                "organizer" -> 0 // NONE (я организатор)
                else -> if (isMeeting && organizer.isNotBlank()) 1 else 0 // NOT_RESPONDED если встреча
            }
            
            events.add(EasCalendarEvent(
                serverId = itemId,
                subject = subject,
                location = location,
                body = body,
                startTime = startTime,
                endTime = endTime,
                allDayEvent = allDayEvent,
                reminder = reminder,
                busyStatus = 2,
                sensitivity = 0,
                organizer = organizer,
                attendees = emptyList(),
                isRecurring = isRecurring,
                categories = emptyList(),
                lastModified = lastModified,
                responseStatus = responseStatus,
                isMeeting = isMeeting
            ))
        }
        
        return events
    }
    
    private fun parseCalendarSyncResponse(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()
        
        // Парсим Add элементы
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val addXml = match.groupValues[1]
            
            // Извлекаем ServerId
            val serverId = extractValue(addXml, "ServerId") ?: return@forEach
            
            // Извлекаем ApplicationData
            val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val dataMatch = dataPattern.find(addXml)
            if (dataMatch != null) {
                val dataXml = dataMatch.groupValues[1]
                
                val subject = extractCalendarValue(dataXml, "Subject") ?: ""
                val location = extractCalendarValue(dataXml, "Location") ?: ""
                val body = extractCalendarBody(dataXml)
                val startTime = parseCalendarDate(extractCalendarValue(dataXml, "StartTime"))
                val endTime = parseCalendarDate(extractCalendarValue(dataXml, "EndTime"))
                val allDayEvent = extractCalendarValue(dataXml, "AllDayEvent") == "1"
                val reminder = extractCalendarValue(dataXml, "Reminder")?.toIntOrNull() ?: 0
                val busyStatus = extractCalendarValue(dataXml, "BusyStatus")?.toIntOrNull() ?: 2
                val sensitivity = extractCalendarValue(dataXml, "Sensitivity")?.toIntOrNull() ?: 0
                val organizer = extractCalendarValue(dataXml, "OrganizerEmail") 
                    ?: extractCalendarValue(dataXml, "Organizer_Email") ?: ""
                val attendees = parseCalendarAttendees(dataXml)
                val categories = extractCalendarCategories(dataXml)
                val lastModified = parseCalendarDate(extractCalendarValue(dataXml, "DtStamp"))
                
                // Проверяем повторяющееся событие
                val isRecurring = dataXml.contains("<calendar:Recurrence>") || dataXml.contains("<Recurrence>")
                
                events.add(EasCalendarEvent(
                    serverId = serverId,
                    subject = subject,
                    location = location,
                    body = body,
                    startTime = startTime,
                    endTime = endTime,
                    allDayEvent = allDayEvent,
                    reminder = reminder,
                    busyStatus = busyStatus,
                    sensitivity = sensitivity,
                    organizer = organizer,
                    attendees = attendees,
                    isRecurring = isRecurring,
                    categories = categories,
                    lastModified = lastModified
                ))
            }
        }
        
        return events
    }
    
    private fun extractCalendarValue(xml: String, tag: String): String? {
        val patterns = listOf(
            "<calendar:$tag>(.*?)</calendar:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
    
    private fun extractCalendarBody(xml: String): String {
        val bodyPatterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<calendar:Body>(.*?)</calendar:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return ""
    }
    
    /**
     * Исправляет кодировку текста если она сломана
     */
    private fun fixEncoding(text: String): String {
        if (text.isBlank()) return text
        
        // Пробуем декодировать из base64 если это base64
        if (text.matches(Regex("^[A-Za-z0-9+/=\\s]+$")) && text.length > 20) {
            try {
                val decoded = android.util.Base64.decode(text.replace("\\s".toRegex(), ""), android.util.Base64.DEFAULT)
                // Пробуем UTF-8
                val utf8 = String(decoded, Charsets.UTF_8)
                if (!utf8.contains("�") && !utf8.contains("?????")) {
                    return utf8
                }
                // Пробуем Windows-1251 (кириллица)
                try {
                    val win1251 = String(decoded, Charset.forName("windows-1251"))
                    if (!win1251.contains("�")) {
                        return win1251
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
        
        // Проверяем не сломана ли кодировка (много знаков вопроса подряд)
        if (text.contains("?????") || text.matches(Regex(".*\\?{3,}.*"))) {
            // Пробуем перекодировать из ISO-8859-1 в UTF-8
            try {
                val bytes = text.toByteArray(Charsets.ISO_8859_1)
                val utf8 = String(bytes, Charsets.UTF_8)
                if (!utf8.contains("?????")) {
                    return utf8
                }
            } catch (_: Exception) {}
            
            // Пробуем Windows-1251
            try {
                val bytes = text.toByteArray(Charsets.ISO_8859_1)
                val win1251 = String(bytes, Charset.forName("windows-1251"))
                if (!win1251.contains("?????")) {
                    return win1251
                }
            } catch (_: Exception) {}
        }
        
        return text
    }
    
    private fun extractCalendarCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesPattern = "<calendar:Categories>(.*?)</calendar:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val categoriesMatch = categoriesPattern.find(xml)
        if (categoriesMatch != null) {
            val categoriesXml = categoriesMatch.groupValues[1]
            CALENDAR_CATEGORY_REGEX.findAll(categoriesXml).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
        }
        return categories
    }
    
    private fun parseCalendarAttendees(xml: String): List<EasAttendee> {
        val attendees = mutableListOf<EasAttendee>()
        
        // Пробуем оба варианта: с namespace prefix и без
        val attendeesPatterns = listOf(
            "<calendar:Attendees>(.*?)</calendar:Attendees>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<Attendees>(.*?)</Attendees>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        var attendeesXml: String? = null
        for (pattern in attendeesPatterns) {
            val match = pattern.find(xml)
            if (match != null) {
                attendeesXml = match.groupValues[1]
                break
            }
        }
        
        if (attendeesXml != null) {
            val attendeePatterns = listOf(
                "<calendar:Attendee>(.*?)</calendar:Attendee>".toRegex(RegexOption.DOT_MATCHES_ALL),
                "<Attendee>(.*?)</Attendee>".toRegex(RegexOption.DOT_MATCHES_ALL)
            )
            
            for (attendeePattern in attendeePatterns) {
                attendeePattern.findAll(attendeesXml).forEach { match ->
                    val attendeeXml = match.groupValues[1]
                    val email = extractCalendarValue(attendeeXml, "Email") ?: return@forEach
                    val name = extractCalendarValue(attendeeXml, "Name") ?: ""
                    val status = extractCalendarValue(attendeeXml, "AttendeeStatus")?.toIntOrNull() ?: 0
                    attendees.add(EasAttendee(email, name, status))
                }
                if (attendees.isNotEmpty()) break
            }
        }
        return attendees
    }
    
    private fun parseCalendarDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        
        // Очищаем строку от Z и миллисекунд
        val cleanDate = dateStr.trim()
            .replace("Z", "")
            .substringBefore(".")
        
        // Список форматов даты, которые может отправлять Exchange
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",      // 2020-12-25T14:00:00
            "yyyyMMdd'T'HHmmss",           // 20201225T140000
            "yyyy-MM-dd HH:mm:ss",         // 2020-12-25 14:00:00
            "yyyy-MM-dd",                  // 2020-12-25
            "yyyyMMdd"                     // 20201225
        )
        
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = sdf.parse(cleanDate)
                if (date != null) {
                    return date.time
                }
            } catch (_: Exception) {
                // Пробуем следующий формат
            }
        }
        
        // Если ничего не подошло, логируем и возвращаем 0
        android.util.Log.w("EasClient", "Failed to parse calendar date: $dateStr")
        return 0L
    }

    // ==================== ЗАДАЧИ (TASKS) ====================
    
    /**
     * Получает ID папки задач с кэшированием и автоматической инвалидацией при ошибке
     * @param forceRefresh принудительно обновить кэш
     */
    private suspend fun getTasksFolderId(forceRefresh: Boolean = false): String? {
        if (forceRefresh) cachedTasksFolderId = null
        
        return cachedTasksFolderId ?: run {
            val foldersResult = folderSync("0")
            when (foldersResult) {
                is EasResult.Success -> {
                    val folderId = foldersResult.data.folders.find { it.type == 7 }?.serverId
                    if (folderId != null) cachedTasksFolderId = folderId
                    folderId
                }
                is EasResult.Error -> null
            }
        }
    }
    
    /**
     * Синхронизация задач из папки Tasks на сервере Exchange
     * Папка Tasks имеет type = 7
     */
    suspend fun syncTasks(): EasResult<List<EasTask>> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Получаем папку задач (type = 7) с кэшированием
        var tasksFolderId = getTasksFolderId()
        
        if (tasksFolderId == null) {
            return EasResult.Error("Папка задач не найдена")
        }
        
        // Шаг 1: Получаем начальный SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            // Проверяем Status - если ошибка, возможно папка изменилась
            val status = extractValue(responseXml, "Status")
            if (status != null && status != "1") {
                throw Exception("INVALID_FOLDER:$status")
            }
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> {
                // Если ошибка связана с папкой, сбрасываем кэш и пробуем снова
                if (initialResult.message.contains("INVALID_FOLDER")) {
                    tasksFolderId = getTasksFolderId(forceRefresh = true)
                    if (tasksFolderId == null) {
                        return EasResult.Error("Папка задач не найдена")
                    }
                    // Повторяем запрос с новым ID
                    val retryXml = initialXml.replace(
                        Regex("<CollectionId>.*?</CollectionId>"),
                        "<CollectionId>$tasksFolderId</CollectionId>"
                    )
                    val retryResult = executeEasCommand("Sync", retryXml) { responseXml ->
                        extractValue(responseXml, "SyncKey") ?: "0"
                    }
                    when (retryResult) {
                        is EasResult.Success -> syncKey = retryResult.data
                        is EasResult.Error -> return retryResult
                    }
                } else {
                    return initialResult
                }
            }
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        // Шаг 2: Запрашиваем задачи
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                        <DeletesAsMoves/>
                        <GetChanges/>
                        <WindowSize>100</WindowSize>
                        <Options>
                            <airsyncbase:BodyPreference>
                                <airsyncbase:Type>1</airsyncbase:Type>
                                <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                            </airsyncbase:BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            parseTaskSyncResponse(responseXml)
        }
    }
    
    /**
     * Стандартная синхронизация задач для EAS 14+ (Exchange 2010+)
     */
    private suspend fun syncTasksStandard(folders: List<EasFolder>): EasResult<List<EasTask>> {
        val tasksFolderId = folders.find { it.type == 7 }?.serverId
            ?: return EasResult.Error("Папка Tasks (type=7) не найдена")
        
        cachedTasksFolderId = tasksFolderId
        
        // Шаг 1: Получаем начальный SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return initialResult
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        // Шаг 2: Запрашиваем задачи
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                        <DeletesAsMoves/>
                        <GetChanges/>
                        <WindowSize>100</WindowSize>
                        <Options>
                            <airsyncbase:BodyPreference>
                                <airsyncbase:Type>1</airsyncbase:Type>
                                <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                            </airsyncbase:BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            parseTaskSyncResponse(responseXml)
        }
    }
    
    /**
     * Legacy синхронизация задач для Exchange 2007 (EAS 12.x)
     * Пробуем Search -> Sync -> EWS
     */
    private suspend fun syncTasksLegacy(folders: List<EasFolder>): EasResult<List<EasTask>> {
        // Ищем папку Tasks по типу или имени
        val tasksFolderId = folders.find { it.type == 7 }?.serverId
            ?: folders.find { 
                it.displayName.equals("Tasks", ignoreCase = true) ||
                it.displayName.equals("Задачи", ignoreCase = true)
            }?.serverId
        
        if (tasksFolderId == null) {
            // Нет папки Tasks — пробуем EWS
            return syncTasksEws()
        }
        
        cachedTasksFolderId = tasksFolderId
        
        // Пробуем Search (как для заметок)
        val searchXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Search xmlns="Search" xmlns:airsync="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Store>
                    <Name>Mailbox</Name>
                    <Query>
                        <And>
                            <airsync:CollectionId>$tasksFolderId</airsync:CollectionId>
                            <FreeText>*</FreeText>
                        </And>
                    </Query>
                    <Options>
                        <Range>0-99</Range>
                        <airsyncbase:BodyPreference>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                        </airsyncbase:BodyPreference>
                    </Options>
                </Store>
            </Search>
        """.trimIndent()
        
        val searchResult = executeEasCommand("Search", searchXml) { responseXml ->
            parseTasksSearchResponse(responseXml)
        }
        
        if (searchResult is EasResult.Success && searchResult.data.isNotEmpty()) {
            return searchResult
        }
        
        // Search не сработал — пробуем Sync
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> {
                // Sync не работает — пробуем EWS
                return syncTasksEws()
            }
        }
        
        if (syncKey == "0") {
            // Пробуем EWS
            return syncTasksEws()
        }
        
        val syncXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                        <GetChanges>1</GetChanges>
                        <WindowSize>100</WindowSize>
                        <Options>
                            <airsyncbase:BodyPreference>
                                <airsyncbase:Type>1</airsyncbase:Type>
                                <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                            </airsyncbase:BodyPreference>
                        </Options>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        val syncResult = executeEasCommand("Sync", syncXml) { responseXml ->
            parseTaskSyncResponse(responseXml)
        }
        
        // Если Sync вернул пустой результат или ошибку — пробуем EWS
        if (syncResult is EasResult.Error || (syncResult is EasResult.Success && syncResult.data.isEmpty())) {
            return syncTasksEws()
        }
        
        return syncResult
    }
    
    /**
     * Парсинг задач из Search ответа
     */
    private fun parseTasksSearchResponse(xml: String): List<EasTask> {
        val tasks = mutableListOf<EasTask>()
        
        val resultPattern = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        resultPattern.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]
            
            val serverId = extractValue(resultXml, "LongId") 
                ?: extractValue(resultXml, "ServerId") 
                ?: return@forEach
            
            val propsPattern = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val propsMatch = propsPattern.find(resultXml)
            if (propsMatch != null) {
                val propsXml = propsMatch.groupValues[1]
                
                val subject = extractTaskValue(propsXml, "Subject") ?: ""
                val body = extractTaskBody(propsXml)
                val startDate = parseCalendarDate(extractTaskValue(propsXml, "StartDate")) ?: 0L
                val dueDate = parseCalendarDate(extractTaskValue(propsXml, "DueDate") ?: extractTaskValue(propsXml, "UtcDueDate")) ?: 0L
                val complete = extractTaskValue(propsXml, "Complete") == "1"
                val dateCompleted = parseCalendarDate(extractTaskValue(propsXml, "DateCompleted")) ?: 0L
                val importance = extractTaskValue(propsXml, "Importance")?.toIntOrNull() ?: 1
                val sensitivity = extractTaskValue(propsXml, "Sensitivity")?.toIntOrNull() ?: 0
                val reminderSet = extractTaskValue(propsXml, "ReminderSet") == "1"
                val reminderTime = parseCalendarDate(extractTaskValue(propsXml, "ReminderTime")) ?: 0L
                val categories = extractTaskCategories(propsXml)
                
                tasks.add(EasTask(
                    serverId = serverId,
                    subject = subject,
                    body = body,
                    startDate = startDate,
                    dueDate = dueDate,
                    complete = complete,
                    dateCompleted = dateCompleted,
                    importance = importance,
                    sensitivity = sensitivity,
                    reminderSet = reminderSet,
                    reminderTime = reminderTime,
                    categories = categories,
                    lastModified = System.currentTimeMillis()
                ))
            }
        }
        
        return tasks
    }
    
    private fun parseTaskSyncResponse(xml: String): List<EasTask> {
        val tasks = mutableListOf<EasTask>()
        
        // Парсим Add и Change элементы
        val patterns = listOf(
            "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        for (pattern in patterns) {
            pattern.findAll(xml).forEach { match ->
                val itemXml = match.groupValues[1]
                
                val serverId = extractValue(itemXml, "ServerId") ?: return@forEach
                
                val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val dataMatch = dataPattern.find(itemXml)
                if (dataMatch != null) {
                    val dataXml = dataMatch.groupValues[1]
                    
                    val subject = extractTaskValue(dataXml, "Subject") ?: ""
                    
                    val body = extractTaskBody(dataXml)
                    val startDate = parseCalendarDate(extractTaskValue(dataXml, "StartDate") ?: extractTaskValue(dataXml, "UtcStartDate")) ?: 0L
                    val dueDate = parseCalendarDate(extractTaskValue(dataXml, "DueDate") ?: extractTaskValue(dataXml, "UtcDueDate")) ?: 0L
                    val complete = extractTaskValue(dataXml, "Complete") == "1"
                    val dateCompleted = parseCalendarDate(extractTaskValue(dataXml, "DateCompleted")) ?: 0L
                    val importance = extractTaskValue(dataXml, "Importance")?.toIntOrNull() ?: 1
                    val sensitivity = extractTaskValue(dataXml, "Sensitivity")?.toIntOrNull() ?: 0
                    val reminderSet = extractTaskValue(dataXml, "ReminderSet") == "1"
                    val reminderTime = parseCalendarDate(extractTaskValue(dataXml, "ReminderTime")) ?: 0L
                    val categories = extractTaskCategories(dataXml)
                    
                    tasks.add(EasTask(
                        serverId = serverId,
                        subject = subject,
                        body = body,
                        startDate = startDate,
                        dueDate = dueDate,
                        complete = complete,
                        dateCompleted = dateCompleted,
                        importance = importance,
                        sensitivity = sensitivity,
                        reminderSet = reminderSet,
                        reminderTime = reminderTime,
                        categories = categories
                    ))
                }
            }
        }
        
        return tasks
    }
    
    private fun extractTaskValue(xml: String, tag: String): String? {
        val patterns = listOf(
            "<tasks:$tag>(.*?)</tasks:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
    
    private fun extractTaskBody(xml: String): String {
        // Сначала пробуем извлечь из AirSyncBase Body с Data
        val bodyPatterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<tasks:Body>(.*?)</tasks:Body>",
            // Для Exchange 2007 - Body может быть без namespace и Data внутри
            "<Body[^>]*>.*?<Data>(.*?)</Data>.*?</Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return match.groupValues[1].trim()
            }
        }
        return ""
    }
    
    private fun extractTaskCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesPattern = "<tasks:Categories>(.*?)</tasks:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val categoriesMatch = categoriesPattern.find(xml)
        if (categoriesMatch != null) {
            val categoriesXml = categoriesMatch.groupValues[1]
            val categoryPattern = "<tasks:Category>(.*?)</tasks:Category>".toRegex()
            categoryPattern.findAll(categoriesXml).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
        }
        return categories
    }
    
    /**
     * Создание задачи на сервере Exchange
     * EAS 14.x — через Sync Add
     * EAS 12.x — через EWS CreateItem
     * @param assignTo — email пользователя для назначения задачи (опционально)
     */
    suspend fun createTask(
        subject: String,
        body: String = "",
        startDate: Long = 0,
        dueDate: Long = 0,
        importance: Int = 1, // 0=Low, 1=Normal, 2=High
        reminderSet: Boolean = false,
        reminderTime: Long = 0,
        assignTo: String? = null
    ): EasResult<String> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        
        // EAS 14+ — через EAS Sync Add с датами
        // EAS 12.x (Exchange 2007) с датами — через EWS (EAS не поддерживает даты)
        // Назначение задач — через EWS
        return if (!assignTo.isNullOrBlank()) {
            createTaskEws(subject, body, startDate, dueDate, importance, reminderSet, reminderTime, assignTo)
        } else if (majorVersion < 14 && (startDate > 0 || dueDate > 0)) {
            // Exchange 2007 с датами — пробуем EWS
            createTaskEws(subject, body, startDate, dueDate, importance, reminderSet, reminderTime, null)
        } else {
            createTaskEas(subject, body, startDate, dueDate, importance, reminderSet, reminderTime)
        }
    }
    
    /**
     * Создание задачи через EAS (Exchange 2010+)
     */
    private suspend fun createTaskEas(
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long
    ): EasResult<String> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Получаем папку задач с кэшированием
        val tasksFolderId = getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        
        // Формат даты как в AOSP reference: yyyy-MM-dd'T'HH:mm:ss.S'Z'
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val escapedSubject = escapeXml(subject)
        val escapedBody = escapeXml(body)
        
        // Формируем XML для создания задачи
        val createXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks">""")
            append("<Collections><Collection>")
            append("<SyncKey>$syncKey</SyncKey>")
            append("<CollectionId>$tasksFolderId</CollectionId>")
            append("<Commands><Add>")
            append("<ClientId>$clientId</ClientId>")
            append("<ApplicationData>")
            append("<tasks:Subject>$escapedSubject</tasks:Subject>")
            append("<airsyncbase:Body>")
            append("<airsyncbase:Type>1</airsyncbase:Type>")
            append("<airsyncbase:Data>$escapedBody</airsyncbase:Data>")
            append("</airsyncbase:Body>")
            append("<tasks:Importance>$importance</tasks:Importance>")
            append("<tasks:Complete>0</tasks:Complete>")
            if (startDate > 0) {
                append("<tasks:StartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:StartDate>")
                append("<tasks:UtcStartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:UtcStartDate>")
            }
            if (dueDate > 0) {
                append("<tasks:DueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:DueDate>")
                append("<tasks:UtcDueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:UtcDueDate>")
            }
            if (reminderSet && reminderTime > 0) {
                append("<tasks:ReminderSet>1</tasks:ReminderSet>")
                append("<tasks:ReminderTime>${dateFormat.format(java.util.Date(reminderTime))}</tasks:ReminderTime>")
            } else {
                append("<tasks:ReminderSet>0</tasks:ReminderSet>")
            }
            append("</ApplicationData>")
            append("</Add></Commands>")
            append("</Collection></Collections>")
            append("</Sync>")
        }
        
        return executeEasCommand("Sync", createXml) { responseXml ->
            val status = extractValue(responseXml, "Status")
            if (status == "1") {
                val serverId = extractValue(responseXml, "ServerId") ?: clientId
                serverId
            } else {
                throw Exception("Ошибка создания задачи: Status=$status")
            }
        }
    }
    
    /**
     * Создание задачи через EWS (Exchange 2007+ и назначение задач)
     * @param assignTo — email пользователя для назначения задачи (опционально)
     */
    private suspend fun createTaskEws(
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long,
        assignTo: String?
    ): EasResult<String> {
        val result = withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                val escapedSubject = escapeXml(subject)
                val escapedBody = escapeXml(body)
                
                // Exchange 2007 EWS — формат даты как в официальной документации MS: yyyy-MM-dd'T'HH:mm:ss (без Z!)
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                
                // Формируем SOAP запрос по официальному примеру Microsoft:
                // https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/createitem-operation-task
                // Exchange 2007 не поддерживает StartDate в CreateItem — только DueDate
                val soapRequest = buildString {
                    append("""<?xml version="1.0" encoding="utf-8"?>""")
                    append("""<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
                    append("""xmlns:xsd="http://www.w3.org/2001/XMLSchema" """)
                    append("""xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">""")
                    append("<soap:Body>")
                    append("""<CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """)
                    append("""MessageDisposition="SaveOnly">""")
                    append("<Items>")
                    append("<t:Task>")
                    append("<t:Subject>$escapedSubject</t:Subject>")
                    if (escapedBody.isNotBlank()) {
                        append("""<t:Body BodyType="Text">$escapedBody</t:Body>""")
                    }
                    // Importance: 0=Low, 1=Normal, 2=High
                    val ewsImportance = when (importance) {
                        0 -> "Low"
                        2 -> "High"
                        else -> "Normal"
                    }
                    append("<t:Importance>$ewsImportance</t:Importance>")
                    if (dueDate > 0) {
                        append("<t:DueDate>${dateFormat.format(java.util.Date(dueDate))}</t:DueDate>")
                    }
                    append("<t:Status>NotStarted</t:Status>")
                    append("</t:Task>")
                    append("</Items>")
                    append("</CreateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                android.util.Log.d("EasClient", "createTaskEws: Request: $soapRequest")
                
                // Пробуем NTLM аутентификацию (как в createNoteEws)
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                android.util.Log.d("EasClient", "createTaskEws: Response: ${responseXml?.take(2000)}")
                
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                // Проверяем на ошибки
                if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                    android.util.Log.e("EasClient", "createTaskEws: Schema error in response")
                    return@withContext EasResult.Error("Ошибка схемы EWS")
                }
                
                // Извлекаем ItemId
                val itemId = EWS_ITEM_ID_REGEX.find(responseXml)?.groupValues?.get(1)
                
                if (itemId != null) {
                    EasResult.Success(itemId)
                } else if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                    EasResult.Success("pending_sync_${System.currentTimeMillis()}")
                } else {
                    EasResult.Error("Не удалось создать задачу через EWS")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка создания задачи через EWS")
            }
        }
        
        // Если нужно назначить задачу другому пользователю — отправляем email-уведомление
        // Отправляем независимо от результата создания задачи на сервере
        if (!assignTo.isNullOrBlank()) {
            val emailResult = sendTaskRequest(assignTo, subject, body, startDate, dueDate, importance)
            if (emailResult is EasResult.Error) {
                // Если задача создана, но письмо не отправлено — сообщаем об этом
                if (result is EasResult.Success) {
                    return EasResult.Error("Задача создана, но уведомление не отправлено: ${emailResult.message}")
                }
                // Если и задача не создана, и письмо не отправлено — возвращаем ошибку письма
                return EasResult.Error("Не удалось отправить уведомление: ${emailResult.message}")
            }
        }
        
        return result
    }
    
    /**
     * Отправка Task Request по email (назначение задачи другому пользователю)
     * Отправляет простое email-уведомление о назначенной задаче
     */
    private suspend fun sendTaskRequest(
        assignTo: String,
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int
    ): EasResult<Boolean> {
        // Отправляем письмо с уведомлением о задаче
        val taskSubject = "Задача: $subject"
        val taskBody = buildString {
            append("Вам назначена задача: $subject\r\n\r\n")
            if (body.isNotBlank()) {
                append("Описание: $body\r\n\r\n")
            }
            if (dueDate > 0) {
                val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                append("Срок выполнения: ${dateFormat.format(java.util.Date(dueDate))}\r\n")
            }
        }
        
        // Используем существующий метод отправки письма
        return sendMail(
            to = assignTo,
            subject = taskSubject,
            body = taskBody,
            cc = "",
            bcc = "",
            importance = importance
        )
    }
    
    /**
     * Экранирование текста для iCalendar
     */
    private fun escapeIcal(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
    
    /**
     * Отправка приглашений на событие календаря
     */
    suspend fun sendMeetingInvitation(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        attendees: String
    ): EasResult<Boolean> {
        val attendeeList = attendees.split(",", ";")
            .map { it.trim() }
            .filter { it.contains("@") }
        
        if (attendeeList.isEmpty()) {
            return EasResult.Success(true)
        }
        
        val dateFormat = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val now = System.currentTimeMillis()
        val uid = java.util.UUID.randomUUID().toString()
        
        // Получаем email отправителя
        val fromEmail = if (deviceIdSuffix.contains("@")) {
            deviceIdSuffix
        } else if (domain.isNotEmpty() && !username.contains("@")) {
            "$username@$domain"
        } else {
            username
        }
        
        // Формируем iCalendar VEVENT
        val vevent = buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("PRODID:-//iwo Mail Client//Calendar//EN\r\n")
            append("METHOD:REQUEST\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:$uid\r\n")
            append("DTSTAMP:${dateFormat.format(java.util.Date(now))}\r\n")
            append("DTSTART:${dateFormat.format(java.util.Date(startTime))}\r\n")
            append("DTEND:${dateFormat.format(java.util.Date(endTime))}\r\n")
            append("SUMMARY:${escapeIcal(subject)}\r\n")
            if (location.isNotBlank()) {
                append("LOCATION:${escapeIcal(location)}\r\n")
            }
            if (body.isNotBlank()) {
                append("DESCRIPTION:${escapeIcal(body)}\r\n")
            }
            append("ORGANIZER:mailto:$fromEmail\r\n")
            for (attendee in attendeeList) {
                append("ATTENDEE;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:$attendee\r\n")
            }
            append("STATUS:CONFIRMED\r\n")
            append("SEQUENCE:0\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
        
        // Формируем тело письма
        val dateFormatDisplay = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        val emailBodyText = buildString {
            append("Вы приглашены на встречу: $subject\r\n\r\n")
            append("Время: ${dateFormatDisplay.format(java.util.Date(startTime))} - ${dateFormatDisplay.format(java.util.Date(endTime))}\r\n")
            if (location.isNotBlank()) {
                append("Место: $location\r\n")
            }
            if (body.isNotBlank()) {
                append("\r\nОписание:\r\n$body\r\n")
            }
        }
        
        // Отправляем каждому участнику с iCalendar вложением
        var lastError: String? = null
        for (attendee in attendeeList) {
            val result = sendMailWithCalendar(
                to = attendee,
                subject = "Приглашение: $subject",
                body = emailBodyText,
                icalendar = vevent
            )
            if (result is EasResult.Error) {
                lastError = result.message
            }
        }
        
        return if (lastError != null) {
            EasResult.Error(lastError)
        } else {
            EasResult.Success(true)
        }
    }
    
    /**
     * Отправка письма с iCalendar вложением (для приглашений на встречи)
     */
    private suspend fun sendMailWithCalendar(
        to: String,
        subject: String,
        body: String,
        icalendar: String
    ): EasResult<Boolean> {
        // Проверка получателя
        if (to.isBlank() || !to.contains("@")) {
            return EasResult.Error("Неверный адрес получателя: $to")
        }
        
        if (!versionDetected) {
            detectEasVersion()
        }
        
        return withContext(Dispatchers.IO) {
        try {
            val fromEmail = if (deviceIdSuffix.contains("@")) {
                deviceIdSuffix
            } else if (domain.isNotEmpty() && !username.contains("@")) {
                "$username@$domain"
            } else {
                username
            }
            
            val messageId = "<${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
            val boundary = "----=_Part_${System.currentTimeMillis()}"
            
            val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
            val date = dateFormat.format(java.util.Date())
            
            val encodedSubject = "=?UTF-8?B?${Base64.encodeToString(subject.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}?="
            
            // Формируем MIME multipart сообщение с text/calendar
            val mimeMessage = buildString {
                append("Date: $date\r\n")
                append("From: $fromEmail\r\n")
                append("To: $to\r\n")
                append("Message-ID: $messageId\r\n")
                append("Subject: $encodedSubject\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: multipart/alternative; boundary=\"$boundary\"\r\n")
                append("\r\n")
                
                // Текстовая часть
                append("--$boundary\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: 8bit\r\n")
                append("\r\n")
                append(body)
                append("\r\n")
                
                // iCalendar часть (METHOD:REQUEST для приглашения)
                append("--$boundary\r\n")
                append("Content-Type: text/calendar; charset=UTF-8; method=REQUEST\r\n")
                append("Content-Transfer-Encoding: 8bit\r\n")
                append("\r\n")
                append(icalendar)
                append("\r\n")
                
                append("--$boundary--\r\n")
            }
            
            val mimeBytes = mimeMessage.toByteArray(Charsets.UTF_8)
            val url = buildUrl("SendMail") + "&SaveInSent=T"
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val request = requestBuilder.build()
            val response = executeRequest(request)
            
            if (response.isSuccessful || response.code == 200) {
                // Для SendMail успешный ответ — пустое тело
                val responseBody = response.body?.bytes()
                if (responseBody != null && responseBody.isNotEmpty()) {
                    if (responseBody[0] == 0x03.toByte()) {
                        val xml = wbxmlParser.parse(responseBody)
                        val status = extractValue(xml, "Status")
                        if (status != null && status != "1") {
                            return@withContext EasResult.Error("Ошибка отправки: Status=$status")
                        }
                    }
                }
                EasResult.Success(true)
            } else if (response.code == 449) {
                // Provision required
                when (val provResult = provision()) {
                    is EasResult.Success -> {
                        val retryRequest = Request.Builder()
                            .url(url)
                            .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                            .header("Authorization", getAuthHeader())
                            .header("MS-ASProtocolVersion", easVersion)
                            .header("Content-Type", contentType)
                            .header("User-Agent", "Android/12-EAS-2.0")
                            .apply { policyKey?.let { header("X-MS-PolicyKey", it) } }
                            .build()
                        
                        val retryResponse = executeRequest(retryRequest)
                        if (retryResponse.isSuccessful) {
                            EasResult.Success(true)
                        } else {
                            EasResult.Error("Ошибка отправки: HTTP ${retryResponse.code}")
                        }
                    }
                    is EasResult.Error -> EasResult.Error(provResult.message)
                }
            } else {
                EasResult.Error("Ошибка отправки: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка отправки: ${e.message}")
        }
        }
    }
    
    /**
     * Обновление задачи на сервере Exchange
     */
    suspend fun updateTask(
        serverId: String,
        subject: String,
        body: String = "",
        startDate: Long = 0,
        dueDate: Long = 0,
        complete: Boolean = false,
        importance: Int = 1,
        reminderSet: Boolean = false,
        reminderTime: Long = 0
    ): EasResult<Boolean> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
        
        // Получаем папку задач с кэшированием
        val tasksFolderId = getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val escapedSubject = escapeXml(subject)
        val escapedBody = escapeXml(body)
        
        // Формируем XML для обновления
        // Для EAS 12.x (Exchange 2007) используем упрощённый формат без AirSyncBase
        val updateXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            if (majorVersion >= 14) {
                append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks">""")
            } else {
                append("""<Sync xmlns="AirSync" xmlns:tasks="Tasks">""")
            }
            append("<Collections><Collection>")
            append("<SyncKey>$syncKey</SyncKey>")
            append("<CollectionId>$tasksFolderId</CollectionId>")
            append("<Commands><Change>")
            append("<ServerId>$serverId</ServerId>")
            append("<ApplicationData>")
            append("<tasks:Subject>$escapedSubject</tasks:Subject>")
            if (majorVersion >= 14) {
                append("<airsyncbase:Body>")
                append("<airsyncbase:Type>1</airsyncbase:Type>")
                append("<airsyncbase:Data>$escapedBody</airsyncbase:Data>")
                append("</airsyncbase:Body>")
            } else if (escapedBody.isNotBlank()) {
                append("<tasks:Body>$escapedBody</tasks:Body>")
            }
            append("<tasks:Importance>$importance</tasks:Importance>")
            append("<tasks:Complete>${if (complete) "1" else "0"}</tasks:Complete>")
            if (complete) {
                append("<tasks:DateCompleted>${dateFormat.format(java.util.Date())}</tasks:DateCompleted>")
            }
            if (startDate > 0) {
                append("<tasks:StartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:StartDate>")
                append("<tasks:UtcStartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:UtcStartDate>")
            }
            if (dueDate > 0) {
                append("<tasks:DueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:DueDate>")
                append("<tasks:UtcDueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:UtcDueDate>")
            }
            if (reminderSet && reminderTime > 0) {
                append("<tasks:ReminderSet>1</tasks:ReminderSet>")
                append("<tasks:ReminderTime>${dateFormat.format(java.util.Date(reminderTime))}</tasks:ReminderTime>")
            } else {
                append("<tasks:ReminderSet>0</tasks:ReminderSet>")
            }
            append("</ApplicationData>")
            append("</Change></Commands>")
            append("</Collection></Collections>")
            append("</Sync>")
        }
        
        return executeEasCommand("Sync", updateXml) { responseXml ->
            val status = extractValue(responseXml, "Status")
            status == "1"
        }
    }
    
    /**
     * Удаление задачи на сервере Exchange
     */
    suspend fun deleteTask(serverId: String): EasResult<Boolean> {
        if (!versionDetected) {
            detectEasVersion()
        }
        
        // Для Exchange 2007 (EAS 12.x) используем EWS - serverId уже в формате EWS ItemId
        if (easVersion.startsWith("12.")) {
            return deleteTaskEws(serverId)
        }
        
        // Получаем папку задач с кэшированием
        val tasksFolderId = getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        // Получаем SyncKey
        val initialXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>0</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        var syncKey = "0"
        val initialResult = executeEasCommand("Sync", initialXml) { responseXml ->
            extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val deleteXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sync xmlns="AirSync">
                <Collections>
                    <Collection>
                        <SyncKey>$syncKey</SyncKey>
                        <CollectionId>$tasksFolderId</CollectionId>
                        <Commands>
                            <Delete>
                                <ServerId>$serverId</ServerId>
                            </Delete>
                        </Commands>
                    </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return executeEasCommand("Sync", deleteXml) { responseXml ->
            val status = extractValue(responseXml, "Status")
            status == "1"
        }
    }
    
    /**
     * Удаление задачи через EWS (для Exchange 2007)
     */
    private suspend fun deleteTaskEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                // Проверяем формат serverId - если это EAS формат (короткий), нужно найти EWS ItemId
                val ewsItemId = if (serverId.length < 50 || serverId.contains(":")) {
                    findEwsTaskItemId(ewsUrl, serverId)
                } else {
                    serverId
                }
                
                if (ewsItemId == null) {
                    return@withContext EasResult.Error("Не удалось найти задачу на сервере")
                }
                
                val escapedItemId = escapeXml(ewsItemId)
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:DeleteItem DeleteType="MoveToDeletedItems" AffectedTaskOccurrences="AllOccurrences">
                                <m:ItemIds>
                                    <t:ItemId Id="$escapedItemId"/>
                                </m:ItemIds>
                            </m:DeleteItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // Используем только NTLM для Exchange 2007
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"") || responseXml.contains("ErrorItemNotFound")) {
                    return@withContext EasResult.Success(true)
                }
                
                EasResult.Error("Не удалось удалить задачу")
            } catch (e: Exception) {
                EasResult.Error("Ошибка EWS: ${e.message}")
            }
        }
    }
    
    /**
     * Находит EWS ItemId задачи по EAS ServerId
     */
    private suspend fun findEwsTaskItemId(ewsUrl: String, easServerId: String): String? {
        val findRequest = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                           xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                <soap:Header>
                    <t:RequestServerVersion Version="Exchange2007_SP1"/>
                </soap:Header>
                <soap:Body>
                    <m:FindItem Traversal="Shallow">
                        <m:ItemShape>
                            <t:BaseShape>IdOnly</t:BaseShape>
                        </m:ItemShape>
                        <m:IndexedPageItemView MaxEntriesReturned="500" Offset="0" BasePoint="Beginning"/>
                        <m:ParentFolderIds>
                            <t:DistinguishedFolderId Id="tasks"/>
                        </m:ParentFolderIds>
                    </m:FindItem>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
        
        // Используем только NTLM для Exchange 2007
        val ntlmAuth = performNtlmHandshake(ewsUrl, findRequest, "FindItem")
        if (ntlmAuth == null) {
            return null
        }
        
        val responseXml = executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
        if (responseXml == null) {
            return null
        }
        
        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
        val matches = itemIdPattern.findAll(responseXml).toList()
        
        val index = easServerId.substringAfter(":").toIntOrNull()?.minus(1) ?: 0
        return matches.getOrNull(index)?.groupValues?.get(1) ?: matches.firstOrNull()?.groupValues?.get(1)
    }
    
    /**
     * Синхронизация задач через EWS (для Exchange 2007)
     */
    private suspend fun syncTasksEws(): EasResult<List<EasTask>> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                // FindItem запрос для задач - простой запрос как для заметок
                val findRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:FindItem Traversal="Shallow">
                                <m:ItemShape>
                                    <t:BaseShape>AllProperties</t:BaseShape>
                                </m:ItemShape>
                                <m:IndexedPageItemView MaxEntriesReturned="200" Offset="0" BasePoint="Beginning"/>
                                <m:ParentFolderIds>
                                    <t:DistinguishedFolderId Id="tasks"/>
                                </m:ParentFolderIds>
                            </m:FindItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                android.util.Log.d("EasClient", "syncTasksEws: Sending FindItem request")
                
                // Используем только NTLM для Exchange 2007
                val ntlmAuth = performNtlmHandshake(ewsUrl, findRequest, "FindItem")
                if (ntlmAuth == null) {
                    android.util.Log.e("EasClient", "syncTasksEws: NTLM handshake failed")
                    return@withContext EasResult.Success(emptyList())
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
                if (responseXml == null) {
                    android.util.Log.e("EasClient", "syncTasksEws: executeNtlmRequest returned null")
                    return@withContext EasResult.Success(emptyList())
                }
                
                android.util.Log.d("EasClient", "syncTasksEws: Got response, length=${responseXml.length}")
                
                val tasks = parseEwsTasksResponse(responseXml)
                EasResult.Success(tasks)
            } catch (e: Exception) {
                EasResult.Success(emptyList())
            }
        }
    }
    
    /**
     * Парсинг задач из EWS ответа
     */
    private fun parseEwsTasksResponse(xml: String): List<EasTask> {
        val tasks = mutableListOf<EasTask>()
        
        // DEBUG: Логируем первые 2000 символов ответа
        android.util.Log.d("EasClient", "parseEwsTasksResponse XML: ${xml.take(2000)}")
        
        // Сначала пробуем найти Items контейнер
        val itemsPattern = "<(?:t:|m:)?Items>(.*?)</(?:t:|m:)?Items>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val itemsMatch = itemsPattern.find(xml)
        val itemsXml = itemsMatch?.groupValues?.get(1) ?: xml
        
        android.util.Log.d("EasClient", "parseEwsTasksResponse itemsXml found: ${itemsMatch != null}, length: ${itemsXml.length}")
        
        // EWS может возвращать задачи как Task, Item или Message
        val itemPatterns = listOf(
            "<t:Task[^>]*>(.*?)</t:Task>",
            "<Task[^>]*>(.*?)</Task>",
            "<t:Item[^>]*>(.*?)</t:Item>",
            "<Item[^>]*>(.*?)</Item>"
        )
        
        for (pattern in itemPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(itemsXml).toList()
            android.util.Log.d("EasClient", "parseEwsTasksResponse pattern '$pattern' found ${matches.size} matches")
            
            matches.forEach { match ->
                val itemXml = match.groupValues[1]
                android.util.Log.d("EasClient", "parseEwsTasksResponse itemXml: ${itemXml.take(500)}")
                
                // Проверяем ItemClass — должен быть IPM.Task (если есть)
                val itemClass = extractEwsValue(itemXml, "ItemClass") ?: ""
                if (itemClass.isNotEmpty() && !itemClass.contains("Task", ignoreCase = true)) {
                    android.util.Log.d("EasClient", "parseEwsTasksResponse skipping itemClass: $itemClass")
                    return@forEach
                }
                
                // Извлекаем ItemId через extractEwsAttribute
                val serverId = extractEwsAttribute(itemXml, "ItemId", "Id")
                android.util.Log.d("EasClient", "parseEwsTasksResponse serverId: $serverId")
                if (serverId == null) return@forEach
                
                val subject = extractEwsValue(itemXml, "Subject") ?: ""
                android.util.Log.d("EasClient", "parseEwsTasksResponse subject: '$subject'")
                
                val body = extractEwsValue(itemXml, "Body") ?: ""
                val startDate = parseCalendarDate(extractEwsValue(itemXml, "StartDate")) ?: 0L
                val dueDate = parseCalendarDate(extractEwsValue(itemXml, "DueDate")) ?: 0L
                val complete = extractEwsValue(itemXml, "Status") == "Completed"
                val dateCompleted = parseCalendarDate(extractEwsValue(itemXml, "CompleteDate")) ?: 0L
                val importance = when (extractEwsValue(itemXml, "Importance")) {
                    "High" -> 2
                    "Low" -> 0
                    else -> 1
                }
                
                tasks.add(EasTask(
                    serverId = serverId,
                    subject = subject,
                    body = body,
                    startDate = startDate,
                    dueDate = dueDate,
                    complete = complete,
                    dateCompleted = dateCompleted,
                    importance = importance,
                    sensitivity = 0,
                    reminderSet = false,
                    reminderTime = 0,
                    categories = emptyList()
                ))
            }
            
            if (tasks.isNotEmpty()) break
        }
        
        android.util.Log.d("EasClient", "parseEwsTasksResponse total tasks: ${tasks.size}")
        return tasks
    }

    private fun parseContactsSyncResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        // Парсим Add элементы
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val addXml = match.groupValues[1]
            
            // Извлекаем ApplicationData
            val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val dataMatch = dataPattern.find(addXml)
            if (dataMatch != null) {
                val dataXml = dataMatch.groupValues[1]
                
                val displayName = extractContactValue(dataXml, "FileAs") 
                    ?: extractContactValue(dataXml, "DisplayName")
                    ?: ""
                val email = extractContactValue(dataXml, "Email1Address") ?: ""
                
                if (displayName.isNotEmpty() || email.isNotEmpty()) {
                    contacts.add(GalContact(
                        displayName = displayName,
                        email = email,
                        firstName = extractContactValue(dataXml, "FirstName") ?: "",
                        lastName = extractContactValue(dataXml, "LastName") ?: "",
                        company = extractContactValue(dataXml, "CompanyName") ?: "",
                        department = extractContactValue(dataXml, "Department") ?: "",
                        jobTitle = extractContactValue(dataXml, "JobTitle") ?: "",
                        phone = extractContactValue(dataXml, "BusinessPhoneNumber") 
                            ?: extractContactValue(dataXml, "HomePhoneNumber") ?: "",
                        mobilePhone = extractContactValue(dataXml, "MobilePhoneNumber") ?: ""
                    ))
                }
            }
        }
        
        return contacts
    }
    
    private fun extractContactValue(xml: String, tag: String): String? {
        // Контакты используют namespace contacts:
        val patterns = listOf(
            "<contacts:$tag>(.*?)</contacts:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @param query Строка поиска (имя или email). Пустая строка или "*" вернёт все контакты
     * @param maxResults Максимальное количество результатов (по умолчанию 100)
     */
    suspend fun searchGAL(query: String, maxResults: Int = 100): EasResult<List<GalContact>> {
        // Для получения всех контактов используем "*" или пустой запрос
        val searchQuery = if (query.isBlank() || query == "*") "*" else query
        
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Search xmlns="Search" xmlns:gal="Gal">
                <Store>
                    <Name>GAL</Name>
                    <Query>$searchQuery</Query>
                    <Options>
                        <Range>0-${maxResults - 1}</Range>
                    </Options>
                </Store>
            </Search>
        """.trimIndent()
        
        return executeEasCommand("Search", xml) { responseXml ->
            parseGalSearchResponse(responseXml)
        }
    }
    
    private fun parseGalSearchResponse(xml: String): List<GalContact> {
        val contacts = mutableListOf<GalContact>()
        
        // Проверяем статус
        val status = extractValue(xml, "Status")?.toIntOrNull() ?: 0
        if (status != 1) {
            return emptyList()
        }
        
        // Парсим результаты
        val resultPattern = "<Result>(.*?)</Result>".toRegex(RegexOption.DOT_MATCHES_ALL)
        resultPattern.findAll(xml).forEach { match ->
            val resultXml = match.groupValues[1]
            
            // Извлекаем Properties
            val propsPattern = "<Properties>(.*?)</Properties>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val propsMatch = propsPattern.find(resultXml)
            if (propsMatch != null) {
                val propsXml = propsMatch.groupValues[1]
                
                val displayName = extractGalValue(propsXml, "DisplayName") ?: ""
                val email = extractGalValue(propsXml, "EmailAddress") ?: ""
                
                if (displayName.isNotEmpty() || email.isNotEmpty()) {
                    contacts.add(GalContact(
                        displayName = displayName,
                        email = email,
                        firstName = extractGalValue(propsXml, "FirstName") ?: "",
                        lastName = extractGalValue(propsXml, "LastName") ?: "",
                        company = extractGalValue(propsXml, "Company") ?: "",
                        department = extractGalValue(propsXml, "Office") ?: "", // Office часто содержит отдел
                        jobTitle = extractGalValue(propsXml, "Title") ?: "",
                        phone = extractGalValue(propsXml, "Phone") ?: "",
                        mobilePhone = extractGalValue(propsXml, "MobilePhone") ?: "",
                        alias = extractGalValue(propsXml, "Alias") ?: ""
                    ))
                }
            }
        }
        
        return contacts
    }
    
    private fun extractGalValue(xml: String, tag: String): String? {
        // GAL использует namespace gal:
        val patterns = listOf(
            "<gal:$tag>(.*?)</gal:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
    
    // ==================== ЧЕРНОВИКИ (DRAFTS) ====================
    
    /**
     * Создание черновика на сервере Exchange через EWS
     * EAS не поддерживает создание email через Sync Add, поэтому используем только EWS
     */
    suspend fun createDraft(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String
    ): EasResult<String> {
        return createDraftEws(to, cc, bcc, subject, body)
    }
    
    /**
     * Создание черновика через EWS CreateItem с MessageDisposition="SaveOnly"
     */
    private suspend fun createDraftEws(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                val escapedSubject = escapeXml(subject)
                val escapedBody = escapeXml(body)
                
                // Формируем получателей
                val toRecipients = to.split(",", ";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("") { email ->
                        """<t:Mailbox><t:EmailAddress>$email</t:EmailAddress></t:Mailbox>"""
                    }
                
                val ccRecipients = cc.split(",", ";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("") { email ->
                        """<t:Mailbox><t:EmailAddress>$email</t:EmailAddress></t:Mailbox>"""
                    }
                
                val bccRecipients = bcc.split(",", ";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("") { email ->
                        """<t:Mailbox><t:EmailAddress>$email</t:EmailAddress></t:Mailbox>"""
                    }
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:CreateItem MessageDisposition="SaveOnly">
                                <m:SavedItemFolderId>
                                    <t:DistinguishedFolderId Id="drafts"/>
                                </m:SavedItemFolderId>
                                <m:Items>
                                    <t:Message>
                                        <t:Subject>$escapedSubject</t:Subject>
                                        <t:Body BodyType="HTML">$escapedBody</t:Body>
                                        ${if (toRecipients.isNotBlank()) "<t:ToRecipients>$toRecipients</t:ToRecipients>" else ""}
                                        ${if (ccRecipients.isNotBlank()) "<t:CcRecipients>$ccRecipients</t:CcRecipients>" else ""}
                                        ${if (bccRecipients.isNotBlank()) "<t:BccRecipients>$bccRecipients</t:BccRecipients>" else ""}
                                    </t:Message>
                                </m:Items>
                            </m:CreateItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // Сначала пробуем Basic аутентификацию
                var responseXml = tryBasicAuthEws(ewsUrl, soapRequest, "CreateItem")
                
                // Если Basic не сработал, пробуем NTLM
                if (responseXml == null) {
                    val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                    if (ntlmAuth != null) {
                        responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                    }
                }
                
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
                }
                
                // Извлекаем ItemId
                val itemId = EWS_ITEM_ID_REGEX.find(responseXml)?.groupValues?.get(1)
                
                if (itemId != null) {
                    EasResult.Success(itemId)
                } else if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                    EasResult.Success(java.util.UUID.randomUUID().toString())
                } else {
                    EasResult.Error("Не удалось создать черновик: ${extractEwsError(responseXml)}")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка создания черновика")
            }
        }
    }
    
    /**
     * Обновление черновика на сервере Exchange
     */
    suspend fun updateDraft(
        serverId: String,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String
    ): EasResult<Boolean> {
        return updateDraftEws(serverId, to, cc, bcc, subject, body)
    }
    
    /**
     * Обновление черновика через EWS UpdateItem
     */
    private suspend fun updateDraftEws(
        serverId: String,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                val escapedSubject = escapeXml(subject)
                val escapedBody = escapeXml(body)
                
                // Формируем получателей
                val toRecipients = to.split(",", ";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("") { email ->
                        """<t:Mailbox><t:EmailAddress>$email</t:EmailAddress></t:Mailbox>"""
                    }
                
                val ccRecipients = cc.split(",", ";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("") { email ->
                        """<t:Mailbox><t:EmailAddress>$email</t:EmailAddress></t:Mailbox>"""
                    }
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:UpdateItem MessageDisposition="SaveOnly" ConflictResolution="AutoResolve">
                                <m:ItemChanges>
                                    <t:ItemChange>
                                        <t:ItemId Id="$serverId"/>
                                        <t:Updates>
                                            <t:SetItemField>
                                                <t:FieldURI FieldURI="item:Subject"/>
                                                <t:Message>
                                                    <t:Subject>$escapedSubject</t:Subject>
                                                </t:Message>
                                            </t:SetItemField>
                                            <t:SetItemField>
                                                <t:FieldURI FieldURI="item:Body"/>
                                                <t:Message>
                                                    <t:Body BodyType="HTML">$escapedBody</t:Body>
                                                </t:Message>
                                            </t:SetItemField>
                                            ${if (toRecipients.isNotBlank()) """
                                            <t:SetItemField>
                                                <t:FieldURI FieldURI="message:ToRecipients"/>
                                                <t:Message>
                                                    <t:ToRecipients>$toRecipients</t:ToRecipients>
                                                </t:Message>
                                            </t:SetItemField>
                                            """ else ""}
                                            ${if (ccRecipients.isNotBlank()) """
                                            <t:SetItemField>
                                                <t:FieldURI FieldURI="message:CcRecipients"/>
                                                <t:Message>
                                                    <t:CcRecipients>$ccRecipients</t:CcRecipients>
                                                </t:Message>
                                            </t:SetItemField>
                                            """ else ""}
                                        </t:Updates>
                                    </t:ItemChange>
                                </m:ItemChanges>
                            </m:UpdateItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // Сначала пробуем Basic аутентификацию
                var responseXml = tryBasicAuthEws(ewsUrl, soapRequest, "UpdateItem")
                
                // Если Basic не сработал, пробуем NTLM
                if (responseXml == null) {
                    val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "UpdateItem")
                    if (ntlmAuth != null) {
                        responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "UpdateItem")
                    }
                }
                
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
                }
                
                if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                    EasResult.Success(true)
                } else {
                    EasResult.Error("Не удалось обновить черновик: ${extractEwsError(responseXml)}")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка обновления черновика")
            }
        }
    }
    
    /**
     * Удаление черновика с сервера Exchange
     */
    suspend fun deleteDraft(serverId: String): EasResult<Boolean> {
        return deleteDraftEws(serverId)
    }
    
    /**
     * Удаление черновика через EWS DeleteItem
     */
    private suspend fun deleteDraftEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = normalizedServerUrl
                    .replace("/Microsoft-Server-ActiveSync", "")
                    .replace("/default.eas", "")
                    .trimEnd('/')
                val ewsUrl = "$baseUrl/EWS/Exchange.asmx"
                
                val soapRequest = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                   xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                        <soap:Header>
                            <t:RequestServerVersion Version="Exchange2007_SP1"/>
                        </soap:Header>
                        <soap:Body>
                            <m:DeleteItem DeleteType="MoveToDeletedItems">
                                <m:ItemIds>
                                    <t:ItemId Id="$serverId"/>
                                </m:ItemIds>
                            </m:DeleteItem>
                        </soap:Body>
                    </soap:Envelope>
                """.trimIndent()
                
                // Сначала пробуем Basic аутентификацию
                var responseXml = tryBasicAuthEws(ewsUrl, soapRequest, "DeleteItem")
                
                // Если Basic не сработал, пробуем NTLM
                if (responseXml == null) {
                    val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                    if (ntlmAuth != null) {
                        responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                    }
                }
                
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
                }
                
                if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                    EasResult.Success(true)
                } else {
                    EasResult.Error("Не удалось удалить черновик: ${extractEwsError(responseXml)}")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка удаления черновика")
            }
        }
    }
    
    /**
     * Извлекает текст ошибки из EWS ответа
     */
    private fun extractEwsError(xml: String): String {
        val messageText = EWS_MESSAGE_TEXT_REGEX.find(xml)?.groupValues?.get(1)
        val responseCode = EWS_RESPONSE_CODE_REGEX.find(xml)?.groupValues?.get(1)
        return messageText ?: responseCode ?: "Unknown error"
    }
    
    /**
     * Unescape XML entities
     */
    private fun unescapeXml(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}

sealed class EasResult<out T> {
    data class Success<T>(val data: T) : EasResult<T>()
    data class Error(val message: String) : EasResult<Nothing>()
}

data class FolderSyncResponse(
    val syncKey: String,
    val folders: List<EasFolder>,
    val status: Int = 1
)

data class EasFolder(
    val serverId: String,
    val displayName: String,
    val parentId: String,
    val type: Int
) {
    fun getFolderTypeName(): String = when (type) {
        1 -> "Пользовательская"
        2 -> "Входящие"
        3 -> "Черновики"
        4 -> "Удалённые"
        5 -> "Отправленные"
        6 -> "Исходящие"
        7 -> "Задачи"
        8 -> "Календарь"
        9 -> "Контакты"
        10 -> "Заметки"
        11 -> "Журнал"
        else -> "Другое"
    }
}

data class SyncResponse(
    val syncKey: String,
    val status: Int,
    val emails: List<EasEmail>,
    val moreAvailable: Boolean = false,
    val deletedIds: List<String> = emptyList(), // ServerId удалённых/перемещённых писем
    val changedEmails: List<EasEmailChange> = emptyList() // Изменения Read/Flag с сервера
)

data class EasEmailChange(
    val serverId: String,
    val read: Boolean? = null,
    val flagged: Boolean? = null
)

data class EasEmail(
    val serverId: String,
    val from: String,
    val to: String,
    val cc: String = "",
    val subject: String,
    val dateReceived: String,
    val read: Boolean,
    val importance: Int,
    val body: String,
    val bodyType: Int = 1, // 1=text, 2=html
    val attachments: List<EasAttachment> = emptyList()
)

data class EasAttachment(
    val fileReference: String,
    val displayName: String,
    val contentType: String,
    val estimatedSize: Long = 0,
    val isInline: Boolean = false,
    val contentId: String? = null // Для inline изображений (cid:)
)

/**
 * Результат Ping запроса
 */
data class PingResult(
    val status: Int,
    val changedFolders: List<String> // ServerId папок с изменениями
)

/**
 * Контакт из глобальной адресной книги (GAL)
 */
data class GalContact(
    val displayName: String,
    val email: String,
    val firstName: String = "",
    val lastName: String = "",
    val company: String = "",
    val department: String = "",
    val jobTitle: String = "",
    val phone: String = "",
    val mobilePhone: String = "",
    val alias: String = ""
)

/**
 * Заметка из Exchange Notes
 */
data class EasNote(
    val serverId: String,
    val subject: String,
    val body: String,
    val categories: List<String> = emptyList(),
    val lastModified: Long = System.currentTimeMillis()
)

/**
 * Черновик из Exchange Drafts
 */
data class EasDraft(
    val serverId: String,
    val subject: String,
    val body: String,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val dateCreated: Long = System.currentTimeMillis()
)

/**
 * Событие календаря из Exchange
 */
data class EasCalendarEvent(
    val serverId: String,
    val subject: String,
    val location: String = "",
    val body: String = "",
    val startTime: Long,
    val endTime: Long,
    val allDayEvent: Boolean = false,
    val reminder: Int = 0,
    val busyStatus: Int = 2,
    val sensitivity: Int = 0,
    val organizer: String = "",
    val attendees: List<EasAttendee> = emptyList(),
    val isRecurring: Boolean = false,
    val recurrenceRule: String = "",
    val categories: List<String> = emptyList(),
    val lastModified: Long = System.currentTimeMillis(),
    val responseStatus: Int = 0, // 0=None, 1=NotResponded, 2=Accepted, 3=Tentative, 4=Declined
    val isMeeting: Boolean = false // Это встреча с участниками
)

/**
 * Участник события календаря
 */
data class EasAttendee(
    val email: String,
    val name: String = "",
    val status: Int = 0 // 0=Unknown, 2=Tentative, 3=Accept, 4=Decline, 5=NotResponded
)

/**
 * Задача из Exchange Tasks
 */
data class EasTask(
    val serverId: String,
    val subject: String,
    val body: String = "",
    val startDate: Long = 0,
    val dueDate: Long = 0,
    val complete: Boolean = false,
    val dateCompleted: Long = 0,
    val importance: Int = 1, // 0=Low, 1=Normal, 2=High
    val sensitivity: Int = 0, // 0=Normal, 1=Personal, 2=Private, 3=Confidential
    val reminderSet: Boolean = false,
    val reminderTime: Long = 0,
    val categories: List<String> = emptyList(),
    val lastModified: Long = System.currentTimeMillis()
)


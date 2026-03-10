package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private val certificatePath: String? = null, // Путь к файлу сертификата сервера
    private val clientCertificatePath: String? = null, // Путь к файлу клиентского сертификата (.p12/.pfx)
    private val clientCertificatePassword: String? = null, // Пароль клиентского сертификата
    private val pinnedCertInfo: com.dedovmosol.iwomail.network.HttpClientProvider.CertificateInfo? = null, // Информация для Certificate Pinning
    private val accountId: Long? = null // ID аккаунта для отслеживания изменений сертификата
) {
    private val wbxmlParser = WbxmlParser()
    private val ntlmAuth = NtlmAuthenticator(domain, username, password)
    
    private val client: OkHttpClient by lazy {
        com.dedovmosol.iwomail.network.HttpClientProvider.getClient(
            acceptAllCerts = acceptAllCerts,
            certificatePath = certificatePath,
            clientCertificatePath = if (clientCertificatePassword.isNullOrBlank()) null else clientCertificatePath,
            clientCertificatePassword = clientCertificatePassword,
            pinnedCertInfo = pinnedCertInfo,
            accountId = accountId
        )
    }
    
    private data class PingClientSnapshot(val client: OkHttpClient, val heartbeat: Int)
    @Volatile private var pingClientSnapshot: PingClientSnapshot? = null
    private val pingClientLock = Any()

    private fun getPingClient(heartbeatInterval: Int): OkHttpClient {
        val snapshot = pingClientSnapshot
        if (snapshot != null && snapshot.heartbeat == heartbeatInterval) return snapshot.client
        synchronized(pingClientLock) {
            val doubleCheck = pingClientSnapshot
            if (doubleCheck != null && doubleCheck.heartbeat == heartbeatInterval) return doubleCheck.client
            val newClient = client.newBuilder()
                .readTimeout((heartbeatInterval + 60).toLong(), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()
            pingClientSnapshot = PingClientSnapshot(newClient, heartbeatInterval)
            return newClient
        }
    }
    
    private val normalizedServerUrl: String = normalizeUrl(serverUrl, port, useHttps)
    
    val ewsClient: EwsClient by lazy {
        EwsClient(
            ewsUrl = ewsUrl,
            username = username,
            password = password,
            domain = domain,
            httpClient = client,
            ntlmAuth = ntlmAuth
        )
    }
    
    private val ewsUrl: String by lazy {
        normalizedServerUrl
            .replace("/Microsoft-Server-ActiveSync", "")
            .replace("/default.eas", "")
            .trimEnd('/') + "/EWS/Exchange.asmx"
    }
    
    /** Centralized transport layer (SRP: HTTP/WBXML/Provision) */
    val transport: EasTransport by lazy {
        EasTransport(
            client = client,
            wbxmlParser = wbxmlParser,
            normalizedServerUrl = normalizedServerUrl,
            username = username,
            password = password,
            domain = domain,
            deviceId = deviceId,
            deviceType = deviceType,
            ntlmAuth = ntlmAuth,
            ewsClient = ewsClient,
            certificatePath = certificatePath,
            initialPolicyKey = initialPolicyKey
        )
    }
    
    val versionDetector: EasVersionDetector by lazy {
        EasVersionDetector(transport)
    }
    
    private var easVersion: String
        get() = transport.easVersion
        set(value) { transport.easVersion = value }
    
    private val versionDetected: Boolean
        get() = versionDetector.versionDetected
    
    private val serverSupportedVersions: List<String>
        get() = versionDetector.serverSupportedVersions
    
    fun isExchange2007(): Boolean = versionDetector.isExchange2007()

    val folderSyncService: EasFolderSyncService by lazy {
        EasFolderSyncService(transport, versionDetector)
    }

    // DeviceId должен быть стабильным для одного аккаунта
    private val deviceId = generateStableDeviceId(username, deviceIdSuffix)
    // DeviceType - как у Huawei
    private val deviceType = "Android"
    
    // === Сервисы (SOLID: Single Responsibility) ===
    
    val contactsService: EasContactsService by lazy {
        EasContactsService(
            executeCommand = { cmd, xml, parser -> 
                transport.executeEasCommand(cmd, xml, parser = parser)
            },
            folderSync = { syncKey -> folderSync(syncKey) },
            extractValue = { xml, tag -> extractValue(xml, tag) },
            getEasVersion = { transport.easVersion }
        )
    }
    
    val tasksService: EasTasksService by lazy {
        EasTasksService(EasTasksService.TasksServiceDependencies(
            executeEasCommand = object : EasTasksService.EasCommandExecutor {
                override suspend fun <T> invoke(
                    command: String,
                    xml: String,
                    parser: (String) -> T
                ): EasResult<T> = transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = { syncKey -> folderSync(syncKey) },
            extractValue = { xml, tag -> extractValue(xml, tag) },
            escapeXml = { text -> escapeXml(text) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { versionDetected },
            detectEasVersion = { detectEasVersion() },
            getTasksFolderId = { getTasksFolderId() },
            getDeletedItemsFolderId = { getDeletedItemsFolderId() },
            performNtlmHandshake = { url, request, action -> transport.performNtlmHandshake(url, request, action) },
            executeNtlmRequest = { url, request, auth, action -> transport.executeNtlmRequest(url, request, auth, action) },
            tryBasicAuthEws = { url, request, action -> transport.tryBasicAuthEws(url, request, action) },
            getEwsUrl = { transport.ewsUrl },
            sendMail = { to, subject, body, cc, bcc, importance -> 
                sendMail(to, subject, body, cc, bcc, importance) 
            }
        ))
    }
    
    val notesService: EasNotesService by lazy {
        EasNotesService(EasNotesService.NotesServiceDependencies(
            executeEasCommand = object : EasNotesService.EasCommandExecutor {
                override suspend fun <T> invoke(
                    command: String,
                    xml: String,
                    parser: (String) -> T
                ): EasResult<T> = transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = { syncKey -> folderSync(syncKey) },
            refreshSyncKey = { folderId, initialKey -> refreshSyncKey(folderId, initialKey) },
            extractValue = { xml, tag -> extractValue(xml, tag) },
            escapeXml = { text -> escapeXml(text) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { versionDetected },
            detectEasVersion = { detectEasVersion() },
            getNotesFolderId = { getNotesFolderId() },
            getDeletedItemsFolderId = { getDeletedItemsFolderId() },
            performNtlmHandshake = { url, request, action -> transport.performNtlmHandshake(url, request, action) },
            executeNtlmRequest = { url, request, auth, action -> transport.executeNtlmRequest(url, request, auth, action) },
            tryBasicAuthEws = { url, request, action -> transport.tryBasicAuthEws(url, request, action) },
            getEwsUrl = { transport.ewsUrl },
            findEwsNoteItemId = { ewsUrl, serverId, subject, searchInDeletedItems -> findEwsNoteItemId(ewsUrl, serverId, subject, searchInDeletedItems) }
        ))
    }
    
    val calendarService: EasCalendarService by lazy {
        EasCalendarService(EasCalendarService.CalendarServiceDependencies(
            executeEasCommand = object : EasCalendarService.EasCommandExecutor {
                override suspend fun <T> invoke(
                    command: String,
                    xml: String,
                    parser: (String) -> T
                ): EasResult<T> = transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = { syncKey -> folderSync(syncKey) },
            refreshSyncKey = { folderId, initialKey -> refreshSyncKey(folderId, initialKey) },
            extractValue = { xml, tag -> extractValue(xml, tag) },
            escapeXml = { text -> escapeXml(text) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { versionDetected },
            detectEasVersion = { detectEasVersion() },
            performNtlmHandshake = { url, request, action -> transport.performNtlmHandshake(url, request, action) },
            executeNtlmRequest = { url, request, auth, action -> transport.executeNtlmRequest(url, request, auth, action) },
            tryBasicAuthEws = { url, request, action -> transport.tryBasicAuthEws(url, request, action) },
            getEwsUrl = { transport.ewsUrl },
            parseEasDate = { dateStr -> parseCalendarDate(dateStr) }
        ))
    }
    
    val draftsService: EasDraftsService by lazy {
        EasDraftsService(EasDraftsService.DraftsServiceDependencies(
            executeEasCommand = object : EasDraftsService.EasCommandExecutor {
                override suspend fun <T> invoke(
                    command: String,
                    xml: String,
                    parser: (String) -> T
                ): EasResult<T> = transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = { syncKey -> folderSync(syncKey) },
            refreshSyncKey = { folderId, initialKey -> refreshSyncKey(folderId, initialKey) },
            extractValue = { xml, tag -> extractValue(xml, tag) },
            escapeXml = { text -> escapeXml(text) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { versionDetected },
            detectEasVersion = { detectEasVersion() },
            performNtlmHandshake = { url, request, action -> transport.performNtlmHandshake(url, request, action) },
            executeNtlmRequest = { url, request, auth, action -> transport.executeNtlmRequest(url, request, auth, action) },
            tryBasicAuthEws = { url, request, action -> transport.tryBasicAuthEws(url, request, action) },
            getEwsUrl = { transport.ewsUrl },
            getDraftsFolderId = { getDraftsFolderId() }
        ))
    }
    
    val emailService: EasEmailService by lazy {
        EasEmailService(EasEmailService.EmailServiceDependencies(
            executeEasCommand = object : EasEmailService.EasCommandExecutor {
                override suspend fun <T> invoke(
                    command: String,
                    xml: String,
                    parser: (String) -> T
                ): EasResult<T> = transport.executeEasCommand(command, xml, parser = parser)
            },
            executeRequest = { request -> transport.executeRequest(request) },
            buildUrl = { command -> transport.buildUrl(command) },
            getAuthHeader = { transport.getAuthHeader() },
            getPolicyKey = { transport.policyKey },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { versionDetected },
            detectEasVersion = { detectEasVersion() },
            provision = { transport.provision() },
            extractValue = { xml, tag -> extractValue(xml, tag) },
            escapeXml = { text -> escapeXml(text) },
            wbxmlParser = wbxmlParser,
            getFromEmail = { 
                if (deviceIdSuffix.contains("@")) deviceIdSuffix
                else if (domain.isNotEmpty() && !username.contains("@")) "$username@$domain"
                else username
            },
            getDeviceId = { deviceId },
            performNtlmHandshake = { url, request, action -> transport.performNtlmHandshake(url, request, action) },
            executeNtlmRequest = { url, request, auth, action -> transport.executeNtlmRequest(url, request, auth, action) },
            getEwsUrl = { transport.ewsUrl },
            getDeletedItemsFolderId = { getDeletedItemsFolderId() },
            isExchange2007 = { isExchange2007() },
            buildEwsSoapRequest = { body -> transport.buildEwsSoapRequest(body) },
            extractEwsError = { xml -> transport.extractEwsError(xml) }
        ))
    }
    
    val attachmentService: EasAttachmentService by lazy {
        EasAttachmentService(EasAttachmentService.AttachmentServiceDependencies(
            executeRequest = { request -> transport.executeRequest(request) },
            executeEasCommand = { command, xml, parser -> 
                transport.executeEasCommand(command, xml) { responseXml -> parser(responseXml) }
            },
            buildUrl = { command -> transport.buildUrl(command) },
            getAuthHeader = { transport.getAuthHeader() },
            getPolicyKey = { transport.policyKey },
            getEasVersion = { transport.easVersion },
            provision = { transport.provision() },
            wbxmlGenerateSendMail = { clientId, mimeBytes -> wbxmlParser.generateSendMail(clientId, mimeBytes) },
            getFromEmail = { 
                if (deviceIdSuffix.contains("@")) deviceIdSuffix
                else if (domain.isNotEmpty() && !username.contains("@")) "$username@$domain"
                else username
            },
            getDeviceId = { deviceId },
            getDeviceType = { deviceType },
            getUsername = { username },
            getNormalizedServerUrl = { normalizedServerUrl }
        ))
    }
    
    var policyKey: String?
        get() = transport.policyKey
        set(value) { transport.policyKey = value }

    data class FolderCreateResult(val serverId: String, val newSyncKey: String)
    
    suspend fun detectEasVersion(): EasResult<String> = versionDetector.detect()
    
    fun getServerInfo(): Map<String, Any> = versionDetector.getServerInfo()
    
    init {
        // Валидация клиентского сертификата
        if (clientCertificatePath != null && clientCertificatePassword.isNullOrBlank()) {
            android.util.Log.w("EasClient", "Client certificate path provided but password is empty - certificate will be ignored")
            throw IllegalArgumentException("CLIENT_CERT_PASSWORD_REQUIRED")
        }
    }
    
    companion object {
        // Content-Type константы
        private const val CONTENT_TYPE_WBXML = "application/vnd.ms-sync.wbxml"
        private const val CONTENT_TYPE_XML = "text/xml; charset=utf-8"
        
        // Regex паттерны вынесены в EasPatterns.kt (DRY)
        // Алиасы для обратной совместимости
        private val EMAIL_BRACKET_REGEX get() = EasPatterns.EMAIL_BRACKET
        private val EWS_ITEM_ID_REGEX get() = EasPatterns.EWS_ITEM_ID
        private val EWS_MESSAGE_TEXT_REGEX get() = EasPatterns.EWS_MESSAGE_TEXT
        private val EWS_RESPONSE_CODE_REGEX get() = EasPatterns.EWS_RESPONSE_CODE
        private val NOTES_CATEGORY_REGEX get() = EasPatterns.NOTES_CATEGORY
        private val CALENDAR_CATEGORY_REGEX get() = EasPatterns.CALENDAR_CATEGORY
        private val MDN_DISPOSITION_REGEX get() = EasPatterns.MDN_DISPOSITION
        private val MDN_RETURN_RECEIPT_REGEX get() = EasPatterns.MDN_RETURN_RECEIPT
        private val MDN_CONFIRM_READING_REGEX get() = EasPatterns.MDN_CONFIRM_READING
        private val BOUNDARY_REGEX get() = EasPatterns.BOUNDARY
        private val MOVE_RESPONSE_REGEX get() = EasPatterns.MOVE_RESPONSE
        private val ITEM_OPS_GLOBAL_STATUS_REGEX get() = EasPatterns.ITEM_OPS_GLOBAL_STATUS
        private val ITEM_OPS_FETCH_STATUS_REGEX get() = EasPatterns.ITEM_OPS_FETCH_STATUS
        private val ITEM_OPS_DATA_REGEX get() = EasPatterns.ITEM_OPS_DATA
        private val ITEM_OPS_PROPS_DATA_REGEX get() = EasPatterns.ITEM_OPS_PROPS_DATA
        private val FOLDER_REGEX get() = EasPatterns.FOLDER
        
        /**
         * Нормализует URL сервера - добавляет схему и порт
         * Принцип KISS: упрощенная логика с сохранением fallback поведения
         */
        fun normalizeUrl(url: String, port: Int = 443, useHttps: Boolean = true): String {
            // Убираем пробелы и существующую схему
            val hostOnly = url.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")  // убираем путь
                .substringBefore(":")  // убираем порт если есть
                .trim()
            
            // Если хост пустой - возвращаем fallback (для обратной совместимости)
            if (hostOnly.isEmpty()) {
                android.util.Log.w("EasClient", "Empty server URL provided: $url")
                val scheme = if (useHttps) "https" else "http"
                return "$scheme://exchange.local:$port"
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
    
    private fun getAuthHeader(): String = transport.getAuthHeader()
    
    private fun buildUrl(command: String): String = transport.buildUrl(command)
    
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

    suspend fun provision(): EasResult<String> = transport.provision()
    
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
        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>${escapeXml(folderId)}</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
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
        val syncXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${escapeXml(syncKey)}</SyncKey>
            <CollectionId>${escapeXml(folderId)}</CollectionId>
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
</Sync>""".trimIndent()
        
        return executeEasCommand("Sync", syncXml) { responseXml ->
            XmlUtils.extractTopLevelBlocks(responseXml, "Add")
                .firstNotNullOfOrNull { parseEmail(it) }
        }
    }
    
    /**
     * Синхронизация папок (FolderSync)
     * Для Exchange 2007 сначала делаем Provision если нет PolicyKey.
     * Результат кэшируется на время жизни EasClient-инстанса (один sync cycle),
     * т.к. иерархия папок не меняется в рамках одной сессии.
     */
    suspend fun folderSync(syncKey: String = "0"): EasResult<FolderSyncResponse> =
        folderSyncService.folderSync(syncKey)
    
    suspend fun createFolder(
        displayName: String,
        parentId: String = "0",
        folderType: Int = 12,
        syncKey: String
    ): EasResult<FolderCreateResult> = folderSyncService.createFolder(displayName, parentId, folderType, syncKey)
    
    suspend fun deleteFolder(serverId: String, syncKey: String): EasResult<String> =
        folderSyncService.deleteFolder(serverId, syncKey)
    
    suspend fun renameFolder(serverId: String, newDisplayName: String, syncKey: String): EasResult<String> =
        folderSyncService.renameFolder(serverId, newDisplayName, syncKey)
    
    /**
     * Перемещение писем между папками (MoveItems)
     */
    suspend fun moveItems(
        items: List<Pair<String, String>>,
        dstFolderId: String
    ): EasResult<Map<String, String>> {
        val movesXml = items.joinToString("") { (srcMsgId, srcFldId) ->
            "<Move><SrcMsgId>${escapeXml(srcMsgId)}</SrcMsgId><SrcFldId>${escapeXml(srcFldId)}</SrcFldId><DstFldId>${escapeXml(dstFolderId)}</DstFldId></Move>"
        }
        
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<MoveItems xmlns="Move">
    $movesXml
</MoveItems>""".trimIndent()
        
        return executeEasCommand("MoveItems", xml) { responseXml ->
            val results = mutableMapOf<String, String>()
            var failedCount = 0
            var lastErrorStatus = 0
            var lockedCount = 0
            MOVE_RESPONSE_REGEX.findAll(responseXml).forEach { match ->
                val responseContent = match.groupValues[1]
                val srcMsgId = extractValue(responseContent, "SrcMsgId") ?: ""
                val status = extractValue(responseContent, "Status")?.toIntOrNull() ?: 0
                val dstMsgId = extractValue(responseContent, "DstMsgId") ?: ""
                
                // MS-ASCMD §2.2.3.177.10 — MoveItems status codes:
                // 1 = Invalid source collection ID or item ID (устаревший serverId / уже перемещён)
                // 2 = Invalid destination collection ID
                // 3 = Success (DstMsgId содержит новый ID)
                // 4 = Source and destination are the same (DstMsgId НЕ возвращается)
                // 5 = Item cannot be moved / multiple DstFldId / source locked
                // 7 = Source or destination locked (transient — рекомендуется retry)
                when (status) {
                    3 -> {
                        if (dstMsgId.isNotEmpty()) {
                            results[srcMsgId] = dstMsgId
                        }
                    }
                    7 -> {
                        // Transient lock — считаем как failed, retry на уровне выше
                        lockedCount++
                        failedCount++
                        lastErrorStatus = status
                        android.util.Log.w("EasClient", "MoveItems locked (transient) for $srcMsgId: status=$status")
                    }
                    else -> {
                        failedCount++
                        lastErrorStatus = status
                        android.util.Log.w("EasClient", "MoveItems failed for $srcMsgId: status=$status")
                    }
                }
            }
            
            // Если ВСЕ элементы отклонены — бросаем исключение.
            // Статус 1 (Invalid source) → устаревшие serverId → нужен ресинк.
            // Статус 7 (Locked) → transient → retry на уровне выше.
            if (results.isEmpty() && failedCount > 0) {
                throw Exception("MOVEITEMS_ALL_FAILED:status=$lastErrorStatus,failed=$failedCount,locked=$lockedCount")
            }
            
            results
        }
    }
    
    /**
     * Получить актуальный syncKey для папки без загрузки данных.
     * Используется перед операциями удаления чтобы избежать "воскрешения" писем.
     * Делает легкий sync с GetChanges=1 но WindowSize=1 чтобы минимизировать трафик.
     */
    suspend fun refreshSyncKey(
        collectionId: String,
        syncKey: String
    ): EasResult<String> {
        // Если syncKey = "0", нужно сначала получить начальный
        if (syncKey == "0") {
            val initResult = sync(collectionId, "0")
            return when (initResult) {
                is EasResult.Success -> {
                    EasResult.Success(initResult.data.syncKey)
                }
                is EasResult.Error -> initResult
            }
        }
        
        // Делаем легкий sync чтобы получить актуальный syncKey
        // GetChanges=1 нужен чтобы сервер обновил состояние
        // WindowSize=1 минимизирует трафик
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${escapeXml(syncKey)}</SyncKey>
            <CollectionId>${escapeXml(collectionId)}</CollectionId>
            <GetChanges>1</GetChanges>
            <WindowSize>1</WindowSize>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return executeEasCommand("Sync", xml) { responseXml ->
            val status = extractValue(responseXml, "Status")?.toIntOrNull() ?: 1
            val newSyncKey = extractValue(responseXml, "SyncKey") ?: syncKey
            
            when (status) {
                1 -> newSyncKey // Success
                3 -> throw Exception("INVALID_SYNCKEY") // Invalid SyncKey - нужен полный resync
                else -> throw Exception("Sync failed: Status=$status")
            }
        }
    }
    
    /**
     * Безопасное выполнение операции с SyncKey и автоматическим retry при INVALID_SYNCKEY
     * @param folderId ID папки для синхронизации
     * @param currentSyncKey Текущий SyncKey
     * @param operation Операция которую нужно выполнить с актуальным SyncKey
     * @return Результат операции
     */
    suspend fun <T> withSyncKeyRetry(
        folderId: String,
        currentSyncKey: String,
        operation: suspend (syncKey: String) -> EasResult<T>
    ): EasResult<T> {
        var result = operation(currentSyncKey)
        
        // Если INVALID_SYNCKEY - получаем новый и повторяем
        if (result is EasResult.Error && result.message.contains("INVALID_SYNCKEY")) {
            try {
                val syncResult = sync(folderId, "0")
                if (syncResult is EasResult.Success && syncResult.data.syncKey != "0") {
                    result = operation(syncResult.data.syncKey)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return EasResult.Error("SyncKey retry failed: ${e.message}")
            }
        }
        
        return result
    }
    
    /**
     * Синхронизация писем (Sync)
     * @see EasEmailService.sync
     */
    suspend fun sync(
        collectionId: String,
        syncKey: String = "0",
        windowSize: Int = 100,
        includeMime: Boolean = false
    ): EasResult<SyncResponse> = emailService.sync(collectionId, syncKey, windowSize, includeMime)
    
    /**
     * Отправка письма (SendMail)
     * @see EasEmailService.sendMail
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
    ): EasResult<Boolean> = emailService.sendMail(to, subject, body, cc, bcc, importance, requestReadReceipt, requestDeliveryReceipt)
    
    // buildMimeWithAttachments перенесён в EasAttachmentService
    
    /**
     * Загрузка тела письма с MDN информацией
     * @see EasEmailService.fetchEmailBodyWithMdn
     */
    suspend fun fetchEmailBodyWithMdn(collectionId: String, serverId: String): EasResult<EmailBodyResult> =
        emailService.fetchEmailBodyWithMdn(collectionId, serverId)
    
    /**
     * Получает свежие метаданные вложений конкретного письма через ItemOperations
     */
    suspend fun fetchAttachmentMetadata(collectionId: String, serverId: String): EasResult<List<EasAttachment>> =
        emailService.fetchAttachmentMetadata(collectionId, serverId)
    
    /**
     * Загрузка тела письма через EWS (fallback для Exchange 2007 SP1)
     * Ищет письмо по Subject + дате используя EWS FindItem, затем получает тело через GetItem
     * @param subject Тема письма для поиска
     * @param folderType Тип папки EWS (inbox, sentitems, deleteditems, drafts)
     * @param dateReceived Дата получения письма (ms) для фильтрации — предотвращает подмену тела при одинаковых subject
     * @return Тело письма в HTML формате или пустая строка
     */
    suspend fun fetchEmailBodyViaEws(subject: String, folderType: String, dateReceived: Long = 0L): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                // Определяем EWS DistinguishedFolderId на основе типа папки
                val distinguishedFolderId = when (folderType.lowercase()) {
                    "inbox", "1" -> "inbox"
                    "sent", "sentitems", "5" -> "sentitems"
                    "drafts", "3" -> "drafts"
                    "deleted", "deleteditems", "4" -> "deleteditems"
                    "outbox", "6" -> "outbox"
                    else -> "inbox" // По умолчанию ищем во входящих
                }
                
                // Экранируем subject для XML
                val escapedSubject = escapeXml(subject)
                
                // Формируем restriction: Subject + дата (±2 мин) для точного поиска
                // Без даты при одинаковых subject возвращалось тело ДРУГОГО письма
                val restriction = if (dateReceived > 0L) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val dateFrom = sdf.format(java.util.Date(dateReceived - 120_000L))
                    val dateTo = sdf.format(java.util.Date(dateReceived + 120_000L))
                    """<t:And>
                    <t:Contains ContainmentMode="FullString" ContainmentComparison="IgnoreCase">
                        <t:FieldURI FieldURI="item:Subject"/>
                        <t:Constant Value="$escapedSubject"/>
                    </t:Contains>
                    <t:IsGreaterThanOrEqualTo>
                        <t:FieldURI FieldURI="item:DateTimeReceived"/>
                        <t:FieldURIOrConstant><t:Constant Value="$dateFrom"/></t:FieldURIOrConstant>
                    </t:IsGreaterThanOrEqualTo>
                    <t:IsLessThanOrEqualTo>
                        <t:FieldURI FieldURI="item:DateTimeReceived"/>
                        <t:FieldURIOrConstant><t:Constant Value="$dateTo"/></t:FieldURIOrConstant>
                    </t:IsLessThanOrEqualTo>
                </t:And>"""
                } else {
                    """<t:Contains ContainmentMode="FullString" ContainmentComparison="IgnoreCase">
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:Constant Value="$escapedSubject"/>
                </t:Contains>"""
                }
                
                // Шаг 1: FindItem для поиска письма по Subject + дате
                val findRequest = """<?xml version="1.0" encoding="utf-8"?>
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
            <m:IndexedPageItemView MaxEntriesReturned="1" Offset="0" BasePoint="Beginning"/>
            <m:Restriction>
                $restriction
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="$distinguishedFolderId"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
                
                val findResponse = executeEwsWithAuth(ewsUrl, findRequest, "FindItem")
                    ?: return@withContext EasResult.Error("FindItem request failed")
                
                // Извлекаем ItemId из ответа
                val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
                val ewsItemId = itemIdPattern.find(findResponse)?.groupValues?.get(1)
                
                if (ewsItemId == null) {
                    // Не нашли письмо по Subject - это нормально для некоторых случаев
                    return@withContext EasResult.Success("")
                }
                
                // Шаг 2: GetItem для получения тела
                val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
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
                <t:BodyType>HTML</t:BodyType>
            </m:ItemShape>
            <m:ItemIds>
                <t:ItemId Id="${escapeXml(ewsItemId)}"/>
            </m:ItemIds>
        </m:GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
                
                val getItemResponse = executeEwsWithAuth(ewsUrl, getItemRequest, "GetItem")
                    ?: return@withContext EasResult.Error("GetItem request failed")
                
                // Извлекаем Body
                val bodyPattern = "<t:Body[^>]*>([\\s\\S]*?)</t:Body>".toRegex()
                val bodyMatch = bodyPattern.find(getItemResponse)
                if (bodyMatch != null) {
                    var body = bodyMatch.groupValues[1]
                    // Убираем CDATA если есть
                    if (body.startsWith("<![CDATA[") && body.endsWith("]]>")) {
                        body = body.removePrefix("<![CDATA[").removeSuffix("]]>")
                    }
                    // КРИТИЧНО: EWS GetItem возвращает тело как XML text content,
                    // где HTML-теги закодированы: &lt;html&gt; вместо <html>.
                    // Это стандартное поведение XML — text nodes кодируют спецсимволы.
                    // Без unescapeXml тело отображается как сырые entities.
                    // Это ОСНОВНАЯ причина бага на Exchange 2007, где ItemOperations
                    // возвращает пустое тело и используется этот EWS fallback.
                    body = unescapeXml(body)
                    return@withContext EasResult.Success(body)
                }
                
                EasResult.Success("")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("EWS error: ${e.message}")
            }
        }
    }
    
    /**
     * Извлекает inline изображения из MIME данных
     * @see EasEmailService.extractInlineImagesFromMime
     */
    fun extractInlineImagesFromMime(mimeData: String): Map<String, String> =
        emailService.extractInlineImagesFromMime(mimeData)
    
    /**
     * Загружает полный MIME письма и извлекает inline изображения
     * @see EasEmailService.fetchInlineImages
     */
    suspend fun fetchInlineImages(collectionId: String, serverId: String): EasResult<Map<String, String>> =
        emailService.fetchInlineImages(collectionId, serverId)
    
    /**
     * Извлекает inline-картинки из EWS-based черновика (длинный ItemId, не EAS формат).
     * Загружает MIME через EWS GetItem (IncludeMimeContent=true) и парсит inline-части.
     * Используется когда EAS ItemOperations недоступен (serverId без ":").
     * @see EasDraftsService.fetchMimeContentEws
     * @see EasEmailService.extractInlineImagesFromMime
     */
    suspend fun fetchInlineImagesEws(itemId: String): EasResult<Map<String, String>> {
        return when (val mimeResult = draftsService.fetchMimeContentEws(itemId)) {
            is EasResult.Success -> {
                if (mimeResult.data.isBlank()) {
                    EasResult.Success(emptyMap())
                } else {
                    val images = emailService.extractInlineImagesFromMime(mimeResult.data)
                    EasResult.Success(images)
                }
            }
            is EasResult.Error -> mimeResult
        }
    }
    
    /**
     * Отправка письма с вложениями
     * @see EasAttachmentService.sendMailWithAttachments
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
    ): EasResult<Boolean> = attachmentService.sendMailWithAttachments(
        to, subject, body, cc, bcc, attachments, requestReadReceipt, requestDeliveryReceipt, importance
    )
    
    /**
     * Сохранение черновика в папку Drafts через Sync Add
     * Использует формат Email namespace для Exchange 2007+
     * 
     * ВАЖНО: EAS официально НЕ поддерживает Sync Add для Email!
     * Эта функция оставлена для экспериментов - некоторые серверы могут принять.
     * В текущей версии приложения черновики сохраняются только локально.
     * @see EasDraftsService.saveDraft
     */
    
    /**
     * Экранирует XML спецсимволы
     */
    private fun escapeXml(text: String): String = XmlUtils.escape(text)
    
    /**
     * Расэкранирует XML entities обратно в символы.
     * КРИТИЧНО: WBXML-парсер выводит XML, где контент <Data> содержит
     * закодированные HTML теги: &lt;div&gt; вместо <div>.
     * Без этой функции тело письма отображается как сырые entities.
     * ПОРЯДОК ВАЖЕН: &amp; декодируется ПОСЛЕДНИМ, чтобы не создать
     * ложные entities (например, &amp;lt; → &lt; → <).
     */
    private fun unescapeXml(text: String): String = XmlUtils.unescape(text)
    
    /**
     * Отправка отчёта о прочтении (MDN - Message Disposition Notification)
     * @param to email получателя отчёта (из заголовка Disposition-Notification-To)
     * @param originalSubject тема оригинального письма
     * @param originalMessageId Message-ID оригинального письма (опционально)
     */
    /**
     * Отправка отчёта о прочтении (MDN)
     * @see EasAttachmentService.sendMdn
     */
    suspend fun sendMdn(
        to: String,
        originalSubject: String,
        originalMessageId: String? = null
    ): EasResult<Boolean> = attachmentService.sendMdn(to, originalSubject, originalMessageId)
    
    // buildMdnMessage перенесён в EasAttachmentService
    
    /**
     * Скачивание вложения в память (ByteArray).
     * @see EasAttachmentService.downloadAttachment
     */
    suspend fun downloadAttachment(
        fileReference: String, 
        collectionId: String? = null, 
        serverId: String? = null
    ): EasResult<ByteArray> = attachmentService.downloadAttachment(fileReference, collectionId, serverId)
    
    /**
     * Скачивание вложения в файл (streaming, без OOM для больших файлов).
     * @see EasAttachmentService.downloadAttachmentToFile
     */
    suspend fun downloadAttachmentToFile(
        fileReference: String,
        destFile: java.io.File,
        collectionId: String? = null,
        serverId: String? = null
    ): EasResult<java.io.File> = attachmentService.downloadAttachmentToFile(fileReference, destFile, collectionId, serverId)

    suspend fun downloadDraftAttachment(fileReference: String): EasResult<ByteArray> {
        return if (fileReference.contains(":")) {
            attachmentService.downloadAttachment(fileReference)
        } else {
            draftsService.downloadAttachmentEws(fileReference)
        }
    }
    
    // Методы загрузки вложений (downloadViaItemOperations, tryItemOperations, 
    // doItemOperationsFetch, parseItemOperationsResponse, downloadViaGetAttachment, 
    // tryGetAttachment) перенесены в EasAttachmentService
    
    // Методы загрузки вложений (~250 строк) перенесены в EasAttachmentService:
    // - downloadViaItemOperationsFetchEmail
    // - downloadViaItemOperations
    // - tryItemOperations
    // - doItemOperationsFetch
    // - parseItemOperationsResponse
    // - downloadViaGetAttachment
    // - tryGetAttachment
    
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
                    <Id>${escapeXml(folderId)}</Id>
                    <Class>Email</Class>
                </Folder>"""
        }
        
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Ping xmlns="Ping">
    <HeartbeatInterval>$heartbeatInterval</HeartbeatInterval>
    <Folders>
$foldersXml
    </Folders>
</Ping>""".trimIndent()
        
        return withContext(Dispatchers.IO) {
            try {
                val url = transport.buildUrl("Ping")
                val wbxmlBody = wbxmlParser.generate(xml)
                
                val pingClient = getPingClient(heartbeatInterval)
                
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(wbxmlBody.toRequestBody(EasTransport.CONTENT_TYPE_WBXML.toMediaType()))
                    .header("Authorization", transport.getAuthHeader())
                    .header("MS-ASProtocolVersion", transport.easVersion)
                    .header("Content-Type", EasTransport.CONTENT_TYPE_WBXML)
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                transport.policyKey?.let { key ->
                    requestBuilder.header("X-MS-PolicyKey", key)
                }
                
                val call = pingClient.newCall(requestBuilder.build())
                val response = suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            if (cont.isActive) cont.resume(response)
                            else response.close()
                        }
                    })
                }
                
                response.use { resp ->
                    if (resp.code == 449) {
                        return@withContext EasResult.Error("Требуется Provision (449)")
                    }
                    
                    if (!resp.isSuccessful) {
                        return@withContext EasResult.Error("HTTP ${resp.code}: ${resp.message}")
                    }
                    
                    val responseBody = resp.body?.bytes()
                    
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
                }
                
            } catch (e: java.net.SocketTimeoutException) {
                EasResult.Success(PingResult(1, emptyList()))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Ошибка Ping: ${e.message}")
            }
        }
    }
    
    /**
     * Пометить письмо как прочитанное
     * @see EasEmailService.markAsRead
     */
    suspend fun markAsRead(
        collectionId: String,
        serverId: String,
        syncKey: String,
        read: Boolean = true,
        subject: String? = null
    ): EasResult<String> = emailService.markAsRead(collectionId, serverId, syncKey, read, subject)
    
    /**
     * Батч-пометка нескольких писем как прочитанных одним Sync-запросом
     * @see EasEmailService.markAsReadBatch
     */
    suspend fun markAsReadBatch(
        collectionId: String,
        serverIds: List<String>,
        syncKey: String,
        read: Boolean = true
    ): EasResult<String> = emailService.markAsReadBatch(collectionId, serverIds, syncKey, read)
    
    /**
     * Переключить флаг письма (избранное)
     * @see EasEmailService.toggleFlag
     */
    suspend fun toggleFlag(
        collectionId: String,
        serverId: String,
        syncKey: String,
        flagged: Boolean
    ): EasResult<String> = emailService.toggleFlag(collectionId, serverId, syncKey, flagged)
    
    /**
     * Удалить письмо (перемещает в корзину)
     * @see EasEmailService.deleteEmail
     */
    suspend fun deleteEmail(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> = emailService.deleteEmail(collectionId, serverId, syncKey)
    
    /**
     * Окончательно удалить письмо через EWS (без перемещения в корзину)
     * @see EasEmailService.deleteEmailPermanentlyViaEWS
     */
    suspend fun deleteEmailPermanentlyViaEWS(
        serverId: String,
        subject: String = ""
    ): EasResult<Unit> = emailService.deleteEmailPermanentlyViaEWS(serverId, subject)
    
    /**
     * Окончательно удалить письмо через EAS Sync Delete
     * @see EasEmailService.deleteEmailPermanently
     */
    suspend fun deleteEmailPermanently(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> = emailService.deleteEmailPermanently(collectionId, serverId, syncKey)
    
    /**
     * Batch удаление писем
     * @see EasEmailService.deleteEmailsPermanentlyBatch
     */
    suspend fun deleteEmailsPermanentlyBatch(
        collectionId: String,
        serverIds: List<String>,
        syncKey: String
    ): EasResult<String> = emailService.deleteEmailsPermanentlyBatch(collectionId, serverIds, syncKey)
    
    private suspend fun <T> executeEasCommand(
        command: String,
        xml: String,
        skipPolicyKey: Boolean = false,
        parser: (String) -> T
    ): EasResult<T> = transport.executeEasCommand(command, xml, skipPolicyKey, parser)
    
    private suspend fun executeRequest(request: Request): Response = transport.executeRequest(request)
    
    
    private fun parseEmail(xml: String): EasEmail? {
        val serverId = extractValue(xml, "ServerId") ?: return null
        
        val attachments = mutableListOf<EasAttachment>()
        for (attXml in XmlUtils.extractTopLevelBlocks(xml, "Attachment")) {
            val fileRef = extractValue(attXml, "FileReference") ?: ""
            val displayName = extractValue(attXml, "DisplayName")?.let { XmlUtils.unescape(it) }
            val contentId = extractValue(attXml, "ContentId")
            val isInline = extractValue(attXml, "IsInline") == "1"
            // КРИТИЧНО: Добавляем вложение даже без FileReference (для inline изображений в Sent Items)
            // FileReference может быть пустым, но contentId важен для inline
            if (displayName != null) {
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
        
        val bodyType = extractValue(xml, "Type")?.toIntOrNull() ?: 1
        val rawBody = extractValue(xml, "Data") 
            ?: run {
                // Пробуем с namespace
                val pattern = "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
                pattern.find(xml)?.groupValues?.get(1)
            }
            ?: ""
        
        // КРИТИЧНО: Расэкранируем XML entities из <Data>.
        // WBXML-парсер выводит XML, где HTML-теги внутри <Data> закодированы:
        // &lt;div&gt; вместо <div>, &lt;br&gt; вместо <br> и т.д.
        // Без этого тело письма отображается как сырой текст с &lt; &gt;
        val decodedBody = if (rawBody.isNotEmpty()) unescapeXml(rawBody) else rawBody
        
        // КРИТИЧНО: Если Type=4 (MIME), извлекаем HTML из MIME
        val body = if (bodyType == 4 && decodedBody.isNotBlank()) {
            extractBodyFromMime(decodedBody)
        } else {
            decodedBody
        }
        
        val flagXml = XmlUtils.extractTagValue(xml, "Flag")
        val flagged = flagXml?.let { extractValue(it, "FlagStatus") == "2" } ?: false
        
        // MS-ASEMAIL 2.2.2.58: Read — optional. "1" = read, "0" = unread.
        // Exchange 2007 SP1 may omit <Read> for some emails (old system entries).
        // Default to true (read) when absent: server sends <Read>0</Read> explicitly for unread.
        val readValue = extractValue(xml, "Read")
        val isRead = if (readValue != null) readValue == "1" else true

        // КРИТИЧНО: Расэкранируем текстовые поля.
        // WBXML-парсер кодирует спецсимволы: & → &amp;, ' → &apos; и т.д.
        // Без этого имена типа "AT&T" отображаются как "AT&amp;T",
        // а "O'Brien" как "O&apos;Brien".
        return EasEmail(
            serverId = serverId,
            from = unescapeXml(extractValue(xml, "From") ?: ""),
            to = unescapeXml(extractValue(xml, "To") ?: ""),
            cc = unescapeXml(extractValue(xml, "Cc") ?: ""),
            subject = unescapeXml(extractValue(xml, "Subject") ?: "(No subject)"),
            dateReceived = extractValue(xml, "DateReceived") ?: "",
            read = isRead,
            importance = extractValue(xml, "Importance")?.toIntOrNull() ?: 1,
            body = body,
            bodyType = bodyType,
            attachments = attachments,
            flagged = flagged
        )
    }
    
    private fun extractValue(xml: String, tag: String): String? {
        return XmlUtils.extractTagValue(xml, tag)
    }
    
    /**
     * Строит EWS SOAP запрос с Exchange2007_SP1 header
     */
    private fun buildEwsSoapRequest(bodyContent: String): String = transport.buildEwsSoapRequest(bodyContent)
    
    /**
     * Синхронизация контактов из папки Contacts на сервере Exchange
     * Возвращает список контактов
     * 
     * @see EasContactsService.syncContacts
     */
    suspend fun syncContacts(): EasResult<List<GalContact>> {
        if (!versionDetected) {
            detectEasVersion()
        }
        return contactsService.syncContacts()
    }

    val contactsIncrementalNoChanges: Boolean
        get() = contactsService.lastSyncWasIncrementalNoChanges

    val contactsSyncWasAuthoritative: Boolean
        get() = contactsService.lastSyncWasAuthoritative
    
    /**
     * Создание заметки на сервере Exchange
     * @see EasNotesService.createNote
     */
    suspend fun createNote(subject: String, body: String): EasResult<String> {
        return notesService.createNote(subject, body)
    }
    
    /**
     * Удаление заметки на сервере Exchange
     * @see EasNotesService.deleteNote
     */
    suspend fun deleteNote(serverId: String): EasResult<Boolean> {
        return notesService.deleteNote(serverId)
    }

    /**
     * Окончательное удаление заметки (из Deleted Items)
     * @see EasNotesService.deleteNotePermanently
     */
    suspend fun deleteNotePermanently(serverId: String): EasResult<Boolean> {
        return notesService.deleteNotePermanently(serverId)
    }

    /**
     * Batch-удаление заметок (в корзину). Один EWS DeleteItem на весь пакет.
     */
    suspend fun deleteNotesBatch(serverIds: List<String>): EasResult<Int> =
        notesService.deleteNotesBatch(serverIds)

    /**
     * Batch окончательное удаление заметок (HardDelete).
     */
    suspend fun deleteNotesPermanentlyBatch(serverIds: List<String>): EasResult<Int> =
        notesService.deleteNotesPermanentlyBatch(serverIds)

    private suspend fun getDeletedItemsFolderId(): String? = folderSyncService.getDeletedItemsFolderId()
    
    /**
     * Обновление заметки на сервере Exchange
     * @see EasNotesService.updateNote
     */
    suspend fun updateNote(serverId: String, subject: String, body: String): EasResult<Boolean> {
        return notesService.updateNote(serverId, subject, body)
    }
    
    /**
     * Восстановление заметки из корзины (Deleted Items) обратно в Notes
     * @see EasNotesService.restoreNote
     */
    suspend fun restoreNote(serverId: String): EasResult<String> {
        return notesService.restoreNote(serverId)
    }
    
    /**
     * Синхронизация заметок из папки Notes на сервере Exchange
     * @see EasNotesService.syncNotes
     */
    suspend fun syncNotes(): EasResult<List<EasNote>> {
        return notesService.syncNotes()
    }

    val notesIncrementalNoChanges: Boolean
        get() = notesService.lastSyncWasIncrementalNoChanges
    
    private suspend fun performNtlmHandshake(ewsUrl: String, soapRequest: String, action: String): String? =
        transport.performNtlmHandshake(ewsUrl, soapRequest, action)
    
    private suspend fun executeNtlmRequest(ewsUrl: String, soapRequest: String, authHeader: String, action: String): String? =
        transport.executeNtlmRequest(ewsUrl, soapRequest, authHeader, action)
    
    private suspend fun tryBasicAuthEws(ewsUrl: String, soapRequest: String, action: String): String? =
        transport.tryBasicAuthEws(ewsUrl, soapRequest, action)

    private suspend fun executeEwsWithAuth(ewsUrl: String, soapRequest: String, operation: String): String? =
        transport.executeEwsWithAuth(ewsUrl, soapRequest, operation)
    
    /**
     * Создание события календаря на сервере Exchange
     * Делегирует в CalendarService
     */
    suspend fun createCalendarEvent(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String = "",
        body: String = "",
        allDayEvent: Boolean = false,
        reminder: Int = 15,
        busyStatus: Int = 2,
        sensitivity: Int = 0,
        attendees: List<String> = emptyList(),
        recurrenceType: Int = -1,
        attachments: List<DraftAttachmentData> = emptyList()
    ): EasResult<String> = calendarService.createCalendarEvent(
        subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, recurrenceType, attachments
    )
    
    /**
     * Удаление события календаря на сервере Exchange
     * Делегирует в CalendarService
     * @param isMeeting true если это встреча с участниками (meeting)
     * @param isOrganizer true если текущий пользователь — организатор встречи
     */
    suspend fun deleteCalendarEvent(
        serverId: String,
        isMeeting: Boolean = false,
        isOrganizer: Boolean = false,
        isRecurringSeries: Boolean = false
    ): EasResult<Boolean> = 
        calendarService.deleteCalendarEvent(serverId, isMeeting, isOrganizer, isRecurringSeries)
    
    /**
     * Batch-удаление событий календаря (один EWS DeleteItem на группу).
     * @param requests список DeleteRequest (serverId, isMeeting, isOrganizer, isRecurringSeries)
     * @return количество успешно удалённых
     */
    suspend fun deleteCalendarEventsBatch(
        requests: List<EasCalendarService.DeleteRequest>
    ): EasResult<Int> =
        calendarService.deleteCalendarEventsBatch(requests)

    suspend fun deleteSingleOccurrence(
        searchSubject: String,
        occurrenceStartTime: Long,
        isMeeting: Boolean = false,
        isOrganizer: Boolean = false
    ): EasResult<Boolean> =
        calendarService.deleteSingleOccurrenceEws(searchSubject, occurrenceStartTime, isMeeting, isOrganizer)
    
    /**
     * Обновление события календаря на сервере Exchange
     * Делегирует в CalendarService
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
        sensitivity: Int = 0,
        attendees: List<String> = emptyList(),
        oldSubject: String? = null,
        recurrenceType: Int = -1,
        attachments: List<DraftAttachmentData> = emptyList(),
        newAttendeesToAppend: List<String> = emptyList()
    ): EasResult<String> = calendarService.updateCalendarEvent(
        serverId, subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, oldSubject, recurrenceType, attachments, newAttendeesToAppend
    )
    
    /**
     * Обновление одного вхождения повторяющегося события (MS-ASCAL Exception).
     */
    suspend fun updateSingleOccurrence(
        serverId: String,
        existingExceptionsJson: String,
        occurrenceOriginalStartTime: Long,
        masterSubject: String,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attachments: List<DraftAttachmentData> = emptyList(),
        removedAttachmentIds: List<String> = emptyList()
    ): EasResult<String> = calendarService.updateSingleOccurrence(
        serverId, existingExceptionsJson, occurrenceOriginalStartTime,
        masterSubject, subject, startTime, endTime, location, body,
        allDayEvent, reminder, busyStatus, sensitivity,
        attachments, removedAttachmentIds
    )

    /**
     * Удаление вложений события календаря через EWS DeleteAttachment
     */
    suspend fun deleteCalendarAttachments(
        attachmentIds: List<String>
    ): EasResult<Boolean> = calendarService.deleteCalendarAttachments(attachmentIds)
    
    /**
     * Ответ на приглашение на встречу.
     * MS-ASCMD §2.2.1.11: EAS 12.1 MeetingResponse использует RequestId (ServerId письма)
     * и CollectionId (ServerId папки Inbox). Для EAS 12.1 Calendar folder НЕ допускается.
     *
     * @param serverId EAS ServerId письма-приглашения (для EAS MeetingResponse) или EWS ItemId
     * @param response Тип ответа: "Accept", "Tentative", "Decline"
     * @param sendResponse Отправить ответ организатору
     * @param subject Тема события (для EWS fallback поиска)
     * @param collectionId EAS ServerId папки с письмом-приглашением (обычно Inbox)
     */
    suspend fun respondToMeetingRequest(
        serverId: String,
        response: String,
        sendResponse: Boolean = true,
        subject: String = "",
        collectionId: String = ""
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            val userResponse = when (response.lowercase()) {
                "accept" -> 1
                "tentative" -> 2
                "decline" -> 3
                else -> 1
            }

            if (collectionId.isNotBlank() && serverId.isNotBlank()) {
                val easResult = respondToMeetingRequestEas(serverId, collectionId, userResponse)
                if (easResult is EasResult.Success) return@withContext easResult
                android.util.Log.w("EasClient",
                    "respondToMeetingRequest: EAS MeetingResponse failed (${(easResult as EasResult.Error).message}), trying EWS")
            }

            val ewsId = if (serverId.contains("_")) serverId.substringAfter("_") else serverId
            if (ewsId.length > 50 && !ewsId.contains(":")) {
                return@withContext respondToMeetingRequestEws(ewsId, response, sendResponse, subject)
            }

            EasResult.Error("Meeting response requires the original meeting request message ID")
        }
    }

    /**
     * EAS MeetingResponse — MS-ASCMD §2.2.1.11 (EAS 12.1).
     * RequestId = ServerId письма-приглашения, CollectionId = ServerId папки (Inbox).
     */
    private suspend fun respondToMeetingRequestEas(
        requestId: String,
        collectionId: String,
        userResponse: Int
    ): EasResult<Boolean> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<MeetingResponse xmlns="MeetingResponse">
    <Request>
        <UserResponse>$userResponse</UserResponse>
        <CollectionId>${escapeXml(collectionId)}</CollectionId>
        <RequestId>${escapeXml(requestId)}</RequestId>
    </Request>
</MeetingResponse>""".trimIndent()

        return try {
            executeEasCommand("MeetingResponse", xml) { responseXml ->
                val status = extractValue(responseXml, "Status")
                when (status) {
                    "1" -> true
                    "2" -> throw Exception("Invalid meeting request (Status=2)")
                    "3" -> throw Exception("Server mailbox error (Status=3)")
                    "4" -> throw Exception("Server error (Status=4)")
                    else -> {
                        if (responseXml.contains("CalendarId")) true
                        else throw Exception("MeetingResponse Status=$status")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error(e.message ?: "MeetingResponse failed")
        }
    }
    
    /**
     * Находит EWS ItemId события календаря с ChangeKey
     * Возвращает пару (ItemId, ChangeKey)
     */
    private suspend fun findEwsCalendarItemIdWithChangeKey(ewsUrl: String, easServerId: String, subject: String = ""): Pair<String, String>? {
        if (easServerId.length > 50 && !easServerId.contains(":")) {
            val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
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
</soap:Envelope>""".trimIndent()
            
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
            // Для meeting response нужен реальный item identity с актуальным ChangeKey.
            return null
        }
        
        if (subject.isBlank()) {
            android.util.Log.w("EasClient", "findEwsCalendarItemIdWithChangeKey: empty subject — cannot safely identify calendar item")
            return null
        }
        val escapedSubject = escapeXml(subject)
        val findRequest = """<?xml version="1.0" encoding="utf-8"?>
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
            <m:Restriction>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="$escapedSubject"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
        
        val ntlmAuth = performNtlmHandshake(ewsUrl, findRequest, "FindItem")
            ?: return null
        
        val responseXml = executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
            ?: return null
        
        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"\\s+ChangeKey=\"([^\"]+)\"".toRegex()
        val match = itemIdPattern.find(responseXml)
        return match?.let { Pair(it.groupValues[1], it.groupValues[2]) }
    }
    
    /**
     * Ответ на приглашение через EWS (fallback для EAS)
     */
    private suspend fun respondToMeetingRequestEws(
        serverId: String,
        response: String,
        sendResponse: Boolean,
        subject: String = ""
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (serverId.length <= 50 || serverId.contains(":")) {
                    return@withContext EasResult.Error("EWS fallback requires the original meeting request EWS ItemId")
                }
                val ewsUrl = this@EasClient.ewsUrl
                
                val itemInfo = findEwsCalendarItemIdWithChangeKey(ewsUrl, serverId, subject)
                
                if (itemInfo == null) {
                    return@withContext EasResult.Error("Не удалось найти исходное письмо-приглашение на сервере")
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
                
                val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
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
</soap:Envelope>""".trimIndent()
                
                val ntlmAuth = performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                if (EwsClient.isEwsSuccess(responseXml)) {
                    return@withContext EasResult.Success(true)
                }
                
                // Извлекаем сообщение об ошибке
                val errorMsg = EWS_MESSAGE_TEXT_REGEX.find(responseXml)?.groupValues?.get(1) ?: "Неизвестная ошибка"
                EasResult.Error(errorMsg)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Ошибка EWS: ${e.message}")
            }
        }
    }
    
    /**
     * Синхронизация календаря из папки Calendar на сервере Exchange
     * Делегирует в CalendarService
     */
    suspend fun syncCalendar(): EasResult<EasCalendarService.CalendarSyncResult> = calendarService.syncCalendar()

    /**
     * Дополняет exceptions для повторяющихся событий через EWS.
     * Exchange 2007 SP1 EAS Sync может не возвращать обновлённые exceptions
     * при модификации отдельных вхождений на ПК.
     */
    suspend fun supplementRecurringExceptions(
        localRecurringEvents: List<EasCalendarService.RecurringEventInfo>
    ): Map<String, String> = calendarService.supplementRecurringExceptionsViaEws(localRecurringEvents)

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
    private suspend fun getTasksFolderId(forceRefresh: Boolean = false): String? =
        folderSyncService.getTasksFolderId(forceRefresh)
    
    /**
     * Получение ID папки Notes с кэшированием
     */
    private suspend fun getNotesFolderId(forceRefresh: Boolean = false): String? =
        folderSyncService.getNotesFolderId(forceRefresh)
    
    /**
     * Получение ID папки черновиков
     */
    /**
     * Извлекает тело письма из MIME данных
     * Используется для Type=4 (MIME) в parseEmail
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
     * Декодирует quoted-printable кодировку
     */
    private fun decodeQuotedPrintable(input: String): String {
        val text = input.replace("=\r\n", "").replace("=\n", "") // Soft line breaks
        val bytes = mutableListOf<Byte>()
        var i = 0
        
        while (i < text.length) {
            when {
                text[i] == '=' && i + 2 < text.length -> {
                    try {
                        val hex = text.substring(i + 1, i + 3)
                        bytes.add(hex.toInt(16).toByte())
                        i += 3
                    } catch (_: Exception) {
                        bytes.add(text[i].code.toByte())
                        i++
                    }
                }
                else -> {
                    bytes.addAll(text[i].toString().toByteArray(Charsets.UTF_8).toList())
                    i++
                }
            }
        }
        
        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            input
        }
    }
    
    /**
     * Находит EWS ItemId для заметки по Subject через EWS FindItem с Restriction.
     * Безопасная замена позиционного индекса, предотвращающая DATA LOSS.
     * @param searchInDeletedItems - искать также в корзине (для restore операций)
     */
    private suspend fun findEwsNoteItemId(ewsUrl: String, easServerId: String, subject: String, searchInDeletedItems: Boolean = false): String? {
        if (subject.isBlank()) {
            android.util.Log.w("EasClient", "findEwsNoteItemId: empty subject — cannot safely identify note")
            return null
        }
        val escapedSubject = escapeXml(subject)
        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()

        val foldersToSearch = mutableListOf("notes")
        if (searchInDeletedItems) foldersToSearch.add("deleteditems")

        for (folder in foldersToSearch) {
            val findRequest = """<?xml version="1.0" encoding="utf-8"?>
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
            <m:Restriction>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="$escapedSubject"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="$folder"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

            val ntlmAuth = performNtlmHandshake(ewsUrl, findRequest, "FindItem") ?: continue
            val responseXml = executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem") ?: continue
            val found = itemIdPattern.find(responseXml)?.groupValues?.get(1)
            if (found != null) return found
        }
        return null
    }
    
    private suspend fun getDraftsFolderId(): String? = folderSyncService.getDraftsFolderId()
    
    /**
     * Синхронизация задач из папки Tasks на сервере Exchange
     * @see EasTasksService.syncTasks
     */
    suspend fun syncTasks(): EasResult<List<EasTask>> {
        return tasksService.syncTasks()
    }
    
    /**
     * Создание задачи на сервере Exchange
     * @see EasTasksService.createTask
     */
    suspend fun createTask(
        subject: String,
        body: String = "",
        startDate: Long = 0,
        dueDate: Long = 0,
        importance: Int = 1,
        reminderSet: Boolean = false,
        reminderTime: Long = 0,
        assignTo: String? = null
    ): EasResult<String> {
        return tasksService.createTask(subject, body, startDate, dueDate, importance, reminderSet, reminderTime, assignTo)
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
            val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
            val url = buildUrl("SendMail") +
                if (majorVersion < 14) "&SaveInSent=T" else ""
            
            val (requestBody, contentType) = if (majorVersion >= 14) {
                val clientId = System.currentTimeMillis().toString()
                val wbxml = wbxmlParser.generateSendMail(clientId, mimeBytes)
                Pair(wbxml.toRequestBody(CONTENT_TYPE_WBXML.toMediaType()), CONTENT_TYPE_WBXML)
            } else {
                Pair(mimeBytes.toRequestBody("message/rfc822".toMediaType()), "message/rfc822")
            }
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val request = requestBuilder.build()
            val response = executeRequest(request)
            
            val firstResult = response.use { resp ->
                if (resp.isSuccessful || resp.code == 200) {
                    val responseBody = resp.body?.bytes()
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
                } else if (resp.code == 449) {
                    null
                } else {
                    EasResult.Error("Ошибка отправки: HTTP ${resp.code}")
                }
            }
            
            if (firstResult != null) {
                firstResult
            } else {
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
                        
                        executeRequest(retryRequest).use { retryResp ->
                            if (retryResp.isSuccessful) {
                                EasResult.Success(true)
                            } else {
                                EasResult.Error("Ошибка отправки: HTTP ${retryResp.code}")
                            }
                        }
                    }
                    is EasResult.Error -> EasResult.Error(provResult.message)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка отправки: ${e.message}")
        }
        }
    }
    
    /**
     * Обновление задачи на сервере Exchange
     * @see EasTasksService.updateTask
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
        reminderTime: Long = 0,
        oldSubject: String? = null
    ): EasResult<Boolean> {
        return tasksService.updateTask(serverId, subject, body, startDate, dueDate, complete, importance, reminderSet, reminderTime, oldSubject)
    }
    
    /**
     * Удаление задачи на сервере Exchange (перемещение в корзину)
     * @see EasTasksService.deleteTask
     */
    suspend fun deleteTask(serverId: String): EasResult<Boolean> {
        return tasksService.deleteTask(serverId)
    }
    
    /**
     * Окончательное удаление задачи (без перемещения в корзину)
     * @see EasTasksService.deleteTaskPermanently
     */
    suspend fun deleteTaskPermanently(serverId: String): EasResult<Boolean> {
        return tasksService.deleteTaskPermanently(serverId)
    }

    /**
     * Batch-удаление задач (в корзину). Один EWS DeleteItem на весь пакет.
     */
    suspend fun deleteTasksBatch(serverIds: List<String>): EasResult<Int> =
        tasksService.deleteTasksBatch(serverIds)

    /**
     * Batch окончательное удаление задач (HardDelete).
     */
    suspend fun deleteTasksPermanentlyBatch(serverIds: List<String>): EasResult<Int> =
        tasksService.deleteTasksPermanentlyBatch(serverIds)
    
    /**
     * Восстановление задачи из корзины
     * Перемещает задачу из Deleted Items обратно в Tasks
     * @see EasTasksService.restoreTask
     */
    suspend fun restoreTask(serverId: String, subject: String? = null): EasResult<String> {
        return tasksService.restoreTask(serverId, subject)
    }
    
    /**
     * Отправить уведомление о назначении задачи
     * @see EasTasksService.sendTaskNotification
     */
    suspend fun sendTaskNotification(
        assignTo: String,
        subject: String,
        body: String,
        dueDate: Long,
        importance: Int
    ) {
        tasksService.sendTaskNotification(assignTo, subject, body, dueDate, importance)
    }
    
    /**
     * Поиск в глобальной адресной книге (GAL)
     * @see EasContactsService.searchGAL
     */
    suspend fun searchGAL(query: String, maxResults: Int = 100): EasResult<List<GalContact>> {
        return contactsService.searchGAL(query, maxResults)
    }
    
    // ==================== ЧЕРНОВИКИ (DRAFTS) ====================
    
    /**
     * Создание черновика на сервере Exchange
     * Делегирует в DraftsService
     */
   suspend fun createDraft(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    draftsFolderId: String? = null,
    attachments: List<DraftAttachmentData> = emptyList()
): EasResult<String> = draftsService.createDraft(to, cc, bcc, subject, body, draftsFolderId, attachments)
    
    /**
     * Получить ВСЕ ItemId черновиков с данным subject.
     * Делегирует в DraftsService.findAllDraftItemIdsBySubject
     */
    suspend fun getAllItemIdsBySubject(subject: String): EasResult<List<String>> =
        draftsService.findAllDraftItemIdsBySubject(subject)
    
    /**
     * Обновление черновика на сервере Exchange
     * Делегирует в DraftsService
     */
    suspend fun updateDraft(
    serverId: String,
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String
): EasResult<Boolean> = draftsService.updateDraft(serverId, to, cc, bcc, subject, body)
    
    /**
     * Полноценная синхронизация черновиков через EWS: FindItem + GetItem batch.
     * Возвращает полные черновики (body, recipients, attachments).
     * Exchange 2007 SP1+.
     */
    suspend fun syncDraftsEws(): EasResult<List<EasDraft>> = draftsService.syncDraftsEws()

        /**
     * Загрузка Body черновика
     * Делегирует в DraftsService
     */
    suspend fun getDraftBody(serverId: String): EasResult<String> = draftsService.getDraftBody(serverId)
    
    /**
     * Получение ПОЛНОГО EWS ItemId из папки Drafts по Subject
     * КРИТИЧНО для UpdateItem - EWS требует ПОЛНЫЙ ItemId, а ActiveSync возвращает КОРОТКИЙ
     * 
     * @param subject Subject черновика для поиска
     * @return ПОЛНЫЙ EWS ItemId (например "AAMkADNi...")
     */
    suspend fun getItemIdBySubject(subject: String): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val escapedSubject = escapeXml(subject)
                
                val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
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
            <m:Restriction>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="$escapedSubject"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="drafts"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
                
                val responseXml = executeEwsWithAuth(ewsUrl, getItemRequest, "FindItem")
                    ?: return@withContext EasResult.Error("Failed to execute FindItem")
                
                // Извлекаем ItemId из ответа
                val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
                val fullItemId = itemIdPattern.find(responseXml)?.groupValues?.get(1)
                
                if (fullItemId != null) {
                    EasResult.Success(fullItemId)
                } else {
                    EasResult.Error("ItemId not found for subject=$subject")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Failed to get ItemId: ${e.message}")
            }
        }
    }
    
    /**
     * Находит EWS ItemId черновика в папке Drafts по Subject (EWS FindItem с Restriction).
     * Безопасная замена позиционного индекса, предотвращающая DATA LOSS.
     */
    suspend fun findDraftItemIdBySubject(subject: String): String? {
        if (subject.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val escapedSubject = escapeXml(subject)
                val findBody = """<m:FindItem Traversal="Shallow">
    <m:ItemShape>
        <t:BaseShape>IdOnly</t:BaseShape>
    </m:ItemShape>
    <m:Restriction>
        <t:IsEqualTo>
            <t:FieldURI FieldURI="item:Subject"/>
            <t:FieldURIOrConstant>
                <t:Constant Value="$escapedSubject"/>
            </t:FieldURIOrConstant>
        </t:IsEqualTo>
    </m:Restriction>
    <m:ParentFolderIds>
        <t:DistinguishedFolderId Id="drafts"/>
    </m:ParentFolderIds>
</m:FindItem>""".trimIndent()
                val findRequest = buildEwsSoapRequest(findBody)
                
                val responseXml = executeEwsWithAuth(ewsUrl, findRequest, "FindItem")
                    ?: return@withContext null
                
                val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
                itemIdPattern.find(responseXml)?.groupValues?.get(1)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }
    }
    
    /**
     * Удаление черновика с сервера Exchange
     * Делегирует в DraftsService
     */
    suspend fun deleteDraft(serverId: String): EasResult<Boolean> = draftsService.deleteDraft(serverId)
    
    private fun extractEwsError(xml: String): String = transport.extractEwsError(xml)
    
    /**
     * Unescape XML entities
     */
}

sealed class EasResult<out T> {
    data class Success<T>(val data: T) : EasResult<T>()
    data class Error(val message: String) : EasResult<Nothing>()
}

data class FolderSyncResponse(
    val syncKey: String,
    val folders: List<EasFolder>,
    val status: Int = 1,
    val deletedFolderIds: List<String> = emptyList()
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
    val flagged: Boolean? = null,
    val body: String? = null,
    val bodyType: Int? = null,
    val attachments: List<EasAttachment> = emptyList()
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
    val attachments: List<EasAttachment> = emptyList(),
    val flagged: Boolean = false, // Флаг избранного (FlagStatus=2 на сервере)
    val messageClass: String = "" // MS-ASEMAIL MessageClass (IPM.Note, IPM.Schedule.Meeting.Request, etc.)
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
    val alias: String = "",
    val easServerId: String = ""
)

/**
 * Заметка из Exchange Notes
 */
data class EasNote(
    val serverId: String,
    val subject: String,
    val body: String,
    val messageClass: String = "IPM.StickyNote",
    val categories: List<String> = emptyList(),
    val lastModified: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

/**
 * Черновик из Exchange Drafts
 */
data class EasDraft(
    val serverId: String,
    val subject: String,
    val body: String = "",
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val dateCreated: String = "",
    val changeKey: String = "",
    val hasAttachments: Boolean = false,
    val attachments: List<EasAttachment> = emptyList()
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
    val organizerName: String = "",
    val attendees: List<EasAttendee> = emptyList(),
    val isRecurring: Boolean = false,
    val recurrenceRule: String = "",
    val uid: String = "",
    val timezone: String = "",
    val exceptions: String = "", // JSON массив исключений
    val categories: List<String> = emptyList(),
    val lastModified: Long = System.currentTimeMillis(),
    val responseStatus: Int = 0, // 0=None, 1=NotResponded, 2=Accepted, 3=Tentative, 4=Declined
    val isMeeting: Boolean = false,
    val meetingStatus: Int = 0,
    val responseRequested: Boolean = false,
    val appointmentReplyTime: Long = 0,
    val disallowNewTimeProposal: Boolean = false,
    val onlineMeetingLink: String = "",
    val hasAttachments: Boolean = false,
    val attachments: String = "" // JSON массив [{name, fileReference, size, isInline, contentId}]
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
    val lastModified: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val owner: String = ""
)

/**
 * Результат загрузки тела письма с MDN информацией
 */
data class EmailBodyResult(
    val body: String,
    val mdnRequestedBy: String? = null, // Email для отправки MDN
    val originalMessageId: String? = null // RFC 5322 Message-ID исходного письма
)


package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64

/**
 * Сервис для работы с email Exchange (EAS/EWS)
 * Выделен из EasClient для соблюдения принципа SRP (Single Responsibility)
 * 
 * Отвечает за:
 * - Синхронизацию писем (Sync)
 * - Отправку писем (SendMail)
 * - Загрузку тела письма и вложений
 * - Удаление и перемещение писем
 * - Отметку прочитанных
 */
class EasEmailService internal constructor(
    private val deps: EmailServiceDependencies
) {
    
    interface EasCommandExecutor {
        suspend operator fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T>
    }
    
    /**
     * Зависимости для EasEmailService
     */
    class EmailServiceDependencies(
        val executeEasCommand: EasCommandExecutor,
        val executeRequest: suspend (Request) -> Response,
        val buildUrl: (String) -> String,
        val getAuthHeader: () -> String,
        val getPolicyKey: () -> String?,
        val getEasVersion: () -> String,
        val isVersionDetected: () -> Boolean,
        val detectEasVersion: suspend () -> EasResult<String>,
        val provision: suspend () -> EasResult<String>,
        val extractValue: (String, String) -> String?,
        val escapeXml: (String) -> String,
        val wbxmlParser: WbxmlParser,
        val getFromEmail: () -> String,
        val getDeviceId: () -> String,
        val performNtlmHandshake: suspend (String, String, String) -> String?,
        val executeNtlmRequest: suspend (String, String, String, String) -> String?,
        val getEwsUrl: () -> String,
        val getDeletedItemsFolderId: suspend () -> String?,
        // Новые зависимости для delete операций
        val isExchange2007: () -> Boolean,
        val buildEwsSoapRequest: (String) -> String,
        val parseSyncResponse: (String) -> SyncResponse,
        val extractEwsError: (String) -> String
    )
    
    companion object {
        private const val CONTENT_TYPE_WBXML = "application/vnd.ms-sync.wbxml"

        private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        
        private fun isValidEmailAddress(addresses: String): Boolean =
            addresses.split(";", ",").all { addr ->
                val trimmed = addr.trim()
                trimmed.isEmpty() || EMAIL_PATTERN.matches(trimmed)
            }

        private val MDN_DISPOSITION_REGEX get() = EasPatterns.MDN_DISPOSITION
        private val MDN_RETURN_RECEIPT_REGEX get() = EasPatterns.MDN_RETURN_RECEIPT
        private val MDN_CONFIRM_READING_REGEX get() = EasPatterns.MDN_CONFIRM_READING
        private val EMAIL_BRACKET_REGEX get() = EasPatterns.EMAIL_BRACKET
        private val BOUNDARY_REGEX get() = EasPatterns.BOUNDARY
        private val AIRSYNC_DATA_REGEX = "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        private val CONTENT_ID_PATTERN = "Content-ID:\\s*<([^>]+)>".toRegex(RegexOption.IGNORE_CASE)
        private val CONTENT_TYPE_IMAGE_PATTERN = "Content-Type:\\s*(image/[^;\\r\\n]+)".toRegex(RegexOption.IGNORE_CASE)
        private val DATA_PATTERN = "<Data>(.*?)</Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val DATA_IMAGE_SRC_REGEX = Regex("""src="data:image/[^;]+;base64,[^"]+"""")
    }
    
    /**
     * Синхронизация писем (Sync)
     */
    suspend fun sync(
        collectionId: String,
        syncKey: String = "0",
        windowSize: Int = 100,
        includeMime: Boolean = false
    ): EasResult<SyncResponse> {
        val safeKey = deps.escapeXml(syncKey)
        val safeCol = deps.escapeXml(collectionId)
        val xml = if (syncKey == "0") {
            """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$safeKey</SyncKey>
            <CollectionId>$safeCol</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        } else {
            val mimeSupport = if (includeMime) "<MIMESupport xmlns=\"AirSyncBase\">2</MIMESupport>" else ""
            """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$safeKey</SyncKey>
            <CollectionId>$safeCol</CollectionId>
            <DeletesAsMoves>1</DeletesAsMoves>
            <GetChanges/>
            <WindowSize>$windowSize</WindowSize>
            <Options>
                <FilterType>0</FilterType>
                <BodyPreference xmlns="AirSyncBase">
                    <Type>2</Type>
                    <TruncationSize>10240</TruncationSize>
                </BodyPreference>
                $mimeSupport
            </Options>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        }
        
        val result = deps.executeEasCommand("Sync", xml) { responseXml ->
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
            when (val provResult = deps.provision()) {
                is EasResult.Success -> {
                    val retryResult = deps.executeEasCommand("Sync", xml) { responseXml ->
                        parseSyncResponse(responseXml)
                    }
                    if (retryResult is EasResult.Success) {
                        return retryResult
                    }
                }
                is EasResult.Error -> {
                    android.util.Log.w("EasEmailService", "Provision after 449 failed: ${provResult.message}")
                }
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
        if (to.isBlank() || !isValidEmailAddress(to)) {
            return EasResult.Error("Неверный адрес получателя: $to")
        }
        
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val mimeBytes = buildMimeMessageBytes(to, subject, body, cc, bcc, requestReadReceipt, requestDeliveryReceipt, importance)
                
                val easVersion = deps.getEasVersion()
                val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
                val url = deps.buildUrl("SendMail") +
                    if (majorVersion < 14) "&SaveInSent=T" else ""
                
                val (requestBody, contentType) = if (majorVersion >= 14) {
                    val clientId = System.currentTimeMillis().toString()
                    val wbxml = deps.wbxmlParser.generateSendMail(clientId, mimeBytes)
                    Pair(wbxml.toRequestBody(CONTENT_TYPE_WBXML.toMediaType()), CONTENT_TYPE_WBXML)
                } else {
                    Pair(mimeBytes.toRequestBody("message/rfc822".toMediaType()), "message/rfc822")
                }
                
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Authorization", deps.getAuthHeader())
                    .header("MS-ASProtocolVersion", easVersion)
                    .header("Content-Type", contentType)
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                deps.getPolicyKey()?.let { key ->
                    requestBuilder.header("X-MS-PolicyKey", key)
                }
                
                val request = requestBuilder.build()
                deps.executeRequest(request).use { response ->
                    if (response.isSuccessful || response.code == 200) {
                        val responseBody = response.body?.bytes()
                        if (responseBody != null && responseBody.isNotEmpty()) {
                            if (responseBody[0] == 0x03.toByte()) {
                                val xml = deps.wbxmlParser.parse(responseBody)
                                val status = deps.extractValue(xml, "Status")
                                if (status != null && status != "1") {
                                    val statusDesc = getStatusDescription(status)
                                    return@withContext EasResult.Error("Ошибка отправки: $statusDesc (Status: $status, EAS: $easVersion)")
                                }
                            }
                        }
                        EasResult.Success(true)
                    } else {
                        if (response.code == 449) {
                            when (val provResult = deps.provision()) {
                                is EasResult.Success -> {
                                    val retryRequest = Request.Builder()
                                        .url(url)
                                        .post(requestBody)
                                        .header("Authorization", deps.getAuthHeader())
                                        .header("MS-ASProtocolVersion", easVersion)
                                        .header("Content-Type", contentType)
                                        .header("User-Agent", "Android/12-EAS-2.0")
                                        .apply { deps.getPolicyKey()?.let { header("X-MS-PolicyKey", it) } }
                                        .build()
                                    
                                    deps.executeRequest(retryRequest).use { retryResponse ->
                                        if (retryResponse.isSuccessful) {
                                            return@withContext EasResult.Success(true)
                                        }
                                    }
                                }
                                is EasResult.Error -> {
                                    android.util.Log.w("EasEmailService", "Provision retry failed: ${provResult.message}")
                                }
                            }
                        }
                        EasResult.Error("Ошибка отправки письма (HTTP ${response.code})")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Ошибка отправки письма: ${e.message}")
            }
        }
    }
    
    /**
     * Загрузка тела письма с MDN информацией
     * Сначала пробует MIME (Type=4) для парсинга заголовков, потом HTML, потом plain text
     */
    suspend fun fetchEmailBodyWithMdn(collectionId: String, serverId: String): EasResult<EmailBodyResult> {
        return withTimeoutOrNull(45_000L) {
            // Сначала пробуем получить полный MIME (Type=4) для парсинга заголовков.
            // КРИТИЧНО: Увеличен TruncationSize с 1 МБ до 5 МБ.
            // При отправке писем с inline-картинками + файловыми вложениями MIME легко
            // превышает 1 МБ (base64 картинки раздуваются ~33%). С лимитом 1 МБ сервер
            // возвращал Truncated=1 → откатывался к HTML (Type=2) с cid: ссылками без данных →
            // inline-картинки не отображались в Отправленных.
            // 5 МБ покрывает большинство писем с 2-3 inline-картинками + файлами.
            val safeCollectionId = deps.escapeXml(collectionId)
            val safeServerId = deps.escapeXml(serverId)
            val mimeXml = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">$safeCollectionId</CollectionId>
        <ServerId xmlns="AirSync">$safeServerId</ServerId>
        <Options>
            <MIMESupport xmlns="AirSync">2</MIMESupport>
            <BodyPreference xmlns="AirSyncBase">
                <Type>4</Type>
                <TruncationSize>5242880</TruncationSize>
            </BodyPreference>
        </Options>
    </Fetch>
</ItemOperations>""".trimIndent()
            
            val mimeResult = deps.executeEasCommand("ItemOperations", mimeXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")?.toIntOrNull()
                if (status == 8) {
                    return@executeEasCommand "OBJECT_NOT_FOUND"
                }
                
                // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Проверяем флаг Truncated
                // Если Truncated=1, значит тело обрезано или не отдано (Outlook уже получил)
                val truncated = deps.extractValue(responseXml, "Truncated")
                if (truncated == "1") {
                    return@executeEasCommand ""  // Возвращаем пустую строку, попробуем другие типы
                }
                
                XmlUtils.unescape(deps.extractValue(responseXml, "Data") 
                    ?: run {
                        AIRSYNC_DATA_REGEX.find(responseXml)?.groupValues?.get(1)
                    }
                    ?: "")
            }
            
            if (mimeResult is EasResult.Success && mimeResult.data == "OBJECT_NOT_FOUND") {
                return@withTimeoutOrNull EasResult.Error("OBJECT_NOT_FOUND")
            }
            
            var mdnRequestedBy: String? = null
            var bodyContent = ""
            
            if (mimeResult is EasResult.Success && mimeResult.data.isNotEmpty()) {
                val mimeData = mimeResult.data
                mdnRequestedBy = parseMdnHeader(mimeData)
                
                // Извлекаем тело из MIME
                bodyContent = extractBodyFromMime(mimeData)
            }
            
            // Если MIME не сработал или тело пустое - fallback на обычный запрос
            if (bodyContent.isEmpty()) {
                // Пробуем HTML (Type=2)
                val htmlXml = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">$safeCollectionId</CollectionId>
        <ServerId xmlns="AirSync">$safeServerId</ServerId>
        <Options>
            <BodyPreference xmlns="AirSyncBase">
                <Type>2</Type>
                <TruncationSize>512000</TruncationSize>
            </BodyPreference>
        </Options>
    </Fetch>
</ItemOperations>""".trimIndent()
                
                val htmlResult = deps.executeEasCommand("ItemOperations", htmlXml) { responseXml ->
                    val status = deps.extractValue(responseXml, "Status")?.toIntOrNull()
                    if (status == 8) {
                        return@executeEasCommand "OBJECT_NOT_FOUND"
                    }
                    
                    // Проверяем флаг Truncated
                    val truncated = deps.extractValue(responseXml, "Truncated")
                    if (truncated == "1") {
                        return@executeEasCommand ""
                    }
                    
                    XmlUtils.unescape(deps.extractValue(responseXml, "Data") 
                        ?: deps.extractValue(responseXml, "Body")
                        ?: run {
                            AIRSYNC_DATA_REGEX.find(responseXml)?.groupValues?.get(1)
                        }
                        ?: "")
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
                val plainXml = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">$safeCollectionId</CollectionId>
        <ServerId xmlns="AirSync">$safeServerId</ServerId>
        <Options>
            <BodyPreference xmlns="AirSyncBase">
                <Type>1</Type>
                <TruncationSize>512000</TruncationSize>
            </BodyPreference>
        </Options>
    </Fetch>
</ItemOperations>""".trimIndent()
                
                val plainResult = deps.executeEasCommand("ItemOperations", plainXml) { responseXml ->
                    val status = deps.extractValue(responseXml, "Status")?.toIntOrNull()
                    if (status == 8) {
                        return@executeEasCommand "OBJECT_NOT_FOUND"
                    }
                    
                    // Проверяем флаг Truncated
                    val truncated = deps.extractValue(responseXml, "Truncated")
                    if (truncated == "1") {
                        return@executeEasCommand ""
                    }
                    
                    XmlUtils.unescape(deps.extractValue(responseXml, "Data") 
                        ?: deps.extractValue(responseXml, "Body")
                        ?: run {
                            AIRSYNC_DATA_REGEX.find(responseXml)?.groupValues?.get(1)
                        }
                        ?: "")
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
     * Парсит вложения из XML (Sync или ItemOperations ответ)
     * Поддерживает оба варианта: с namespace prefix (airsyncbase:) и без
     */
    private fun parseAttachmentsFromXml(xml: String): List<EasAttachment> {
        val attachments = mutableListOf<EasAttachment>()
        for (attXml in XmlUtils.extractTopLevelBlocks(xml, "Attachment")) {
            val fileRef = deps.extractValue(attXml, "FileReference") ?: ""
            val displayName = deps.extractValue(attXml, "DisplayName")?.let { XmlUtils.unescape(it) }
            val contentId = deps.extractValue(attXml, "ContentId")
            val isInline = deps.extractValue(attXml, "IsInline") == "1"
            // КРИТИЧНО: Добавляем вложение даже без FileReference (для inline изображений в Sent Items)
            // FileReference может быть пустым, но contentId важен для inline
            if (displayName != null) {
                attachments.add(EasAttachment(
                    fileReference = fileRef,
                    displayName = displayName,
                    contentType = deps.extractValue(attXml, "ContentType") ?: "application/octet-stream",
                    estimatedSize = deps.extractValue(attXml, "EstimatedDataSize")?.toLongOrNull() ?: 0,
                    isInline = isInline,
                    contentId = contentId
                ))
            }
        }
        return attachments
    }

    /**
     * Получает свежие метаданные вложений конкретного письма через ItemOperations
     * Используется при forceReload чтобы обновить FileReference без полной синхронизации папки
     */
    suspend fun fetchAttachmentMetadata(collectionId: String, serverId: String): EasResult<List<EasAttachment>> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">${deps.escapeXml(collectionId)}</CollectionId>
        <ServerId xmlns="AirSync">${deps.escapeXml(serverId)}</ServerId>
        <Options>
            <BodyPreference xmlns="AirSyncBase">
                <Type>1</Type>
                <TruncationSize>0</TruncationSize>
            </BodyPreference>
        </Options>
    </Fetch>
</ItemOperations>""".trimIndent()

        val result = deps.executeEasCommand("ItemOperations", xml) { responseXml ->
            val fetchStatus = EasPatterns.ITEM_OPS_FETCH_STATUS
                .find(responseXml)?.groupValues?.get(1)?.toIntOrNull()
            fetchStatus to parseAttachmentsFromXml(responseXml)
        }
        return when (result) {
            is EasResult.Success -> {
                val (status, atts) = result.data
                when {
                    status == 1 -> EasResult.Success(atts)
                    status == null -> EasResult.Success(atts)
                    else -> EasResult.Error("ItemOperations Fetch status=$status")
                }
            }
            is EasResult.Error -> result
        }
    }

    /**
     * Парсит заголовок Disposition-Notification-To из MIME данных
     */
    private fun parseMdnHeader(mimeData: String): String? {
        val patterns = listOf(
            MDN_DISPOSITION_REGEX,
            MDN_RETURN_RECEIPT_REGEX,
            MDN_CONFIRM_READING_REGEX
        )
        
        for (pattern in patterns) {
            val match = pattern.find(mimeData)
            if (match != null) {
                val email = match.groupValues[1].trim()
                val emailMatch = EMAIL_BRACKET_REGEX.find(email)
                return emailMatch?.groupValues?.get(1) ?: email
            }
        }
        
        return null
    }
    
    /**
     * Извлекает inline изображения из MIME данных
     * Возвращает Map<contentId, base64DataUrl>
     */
    fun extractInlineImagesFromMime(mimeData: String): Map<String, String> {
        val images = mutableMapOf<String, String>()
        
        if (!mimeData.contains("Content-Type:", ignoreCase = true)) {
            return images
        }
        
        // КРИТИЧНО: Рекурсивно обрабатываем вложенные multipart-структуры.
        // При наличии файловых вложений MIME имеет вид:
        //   multipart/mixed { multipart/related { text/html + image(s) } + attachment(s) }
        // Старая версия разбивала только по внешнему boundary и не находила
        // inline-картинки во вложенном multipart/related.
        extractImagesRecursive(mimeData, images)
        
        return images
    }
    
    /**
     * Рекурсивно извлекает inline-картинки из вложенных multipart-структур MIME
     */
    private fun extractImagesRecursive(mimeSection: String, images: MutableMap<String, String>, depth: Int = 0) {
        if (depth > 10) return
        val boundaryMatch = BOUNDARY_REGEX.find(mimeSection) ?: return
        val boundary = boundaryMatch.groupValues[1]
        val parts = mimeSection.split("--$boundary")
        
        for (part in parts) {
            // Если часть содержит вложенный multipart — рекурсивно обрабатываем
            val isNestedMultipart = part.contains("Content-Type: multipart/", ignoreCase = true) ||
                                   part.contains("Content-Type:multipart/", ignoreCase = true)
            if (isNestedMultipart) {
                extractImagesRecursive(part, images, depth + 1)
                continue
            }
            
            val isImage = part.contains("Content-Type: image/", ignoreCase = true) ||
                         part.contains("Content-Type:image/", ignoreCase = true)
            
            if (!isImage) continue
            
            val cidMatch = CONTENT_ID_PATTERN.find(part)
            val contentId = cidMatch?.groupValues?.get(1) ?: continue
            
            val typeMatch = CONTENT_TYPE_IMAGE_PATTERN.find(part)
            val contentType = typeMatch?.groupValues?.get(1)?.trim() ?: "image/png"
            
            val contentStart = part.indexOf("\r\n\r\n")
            if (contentStart == -1) continue
            
            var content = part.substring(contentStart + 4).trim()
            if (content.endsWith("--")) {
                content = content.dropLast(2).trim()
            }
            content = content.replace("\r\n", "").replace("\n", "").replace(" ", "")
            
            if (content.isNotBlank()) {
                val dataUrl = "data:$contentType;base64,$content"
                images[contentId] = dataUrl
            }
        }
    }
    
    /**
     * Загружает полный MIME письма и извлекает inline изображения
     */
    suspend fun fetchInlineImages(collectionId: String, serverId: String): EasResult<Map<String, String>> {
        
        // КРИТИЧНО: Увеличен TruncationSize с 10 МБ до 20 МБ для надёжности.
        // Также добавлена проверка Truncated флага — если MIME обрезан, inline-картинки
        // не будут полностью в MIME и парсинг вернёт пустую карту (неочевидная ошибка).
        val safeCol = deps.escapeXml(collectionId)
        val safeSid = deps.escapeXml(serverId)
        val mimeXml = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">$safeCol</CollectionId>
        <ServerId xmlns="AirSync">$safeSid</ServerId>
        <Options>
            <MIMESupport xmlns="AirSync">2</MIMESupport>
            <BodyPreference xmlns="AirSyncBase">
                <Type>4</Type>
                <TruncationSize>20971520</TruncationSize>
            </BodyPreference>
        </Options>
    </Fetch>
</ItemOperations>""".trimIndent()
        
        return deps.executeEasCommand("ItemOperations", mimeXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")?.toIntOrNull()
            
            if (status == 8) {
                return@executeEasCommand emptyMap() // Object not found
            }
            
            // КРИТИЧНО: Проверяем Truncated флаг.
            // Если MIME обрезан, inline-картинки могут быть неполными — не парсим.
            val truncated = deps.extractValue(responseXml, "Truncated")
            if (truncated == "1") {
                android.util.Log.w("EasEmailService", "fetchInlineImages: MIME truncated even at 20MB, skipping parse")
                return@executeEasCommand emptyMap()
            }
            
            val mimeData = XmlUtils.unescape(deps.extractValue(responseXml, "Data") 
                ?: run {
                    AIRSYNC_DATA_REGEX.find(responseXml)?.groupValues?.get(1)
                }
                ?: "")
            
            if (mimeData.isBlank()) {
                return@executeEasCommand emptyMap()
            }
            
            extractInlineImagesFromMime(mimeData)
        }
    }
    
    /**
     * Отметка письма как прочитанного/непрочитанного
     * 
     * КРИТИЧНО: Используем GetChanges=0 чтобы сервер не присылал серверные
     * изменения в ответе — нам нужен только новый SyncKey и подтверждение.
     * Без GetChanges=0 Exchange 2007 SP1 может вернуть серверные изменения,
     * что усложняет парсинг и может привести к ошибке извлечения SyncKey.
     */
    suspend fun markAsRead(
        collectionId: String,
        serverId: String,
        syncKey: String,
        read: Boolean = true,
        subject: String? = null
    ): EasResult<String> {
        val readValue = if (read) "1" else "0"
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(collectionId)}</CollectionId>
            <GetChanges>0</GetChanges>
            <Commands>
                <Change>
                    <ServerId>${deps.escapeXml(serverId)}</ServerId>
                    <ApplicationData>
                        <Read xmlns="Email">$readValue</Read>
                    </ApplicationData>
                </Change>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val easResult = deps.executeEasCommand("Sync", xml) { responseXml ->
            android.util.Log.d("EasEmailService", "markAsRead response len=${responseXml.length}")
            
            // Проверяем статус коллекции
            val collectionStatus = deps.extractValue(responseXml, "Status")
            if (collectionStatus != null && collectionStatus != "1") {
                throw Exception("markAsRead failed: Collection Status=$collectionStatus")
            }
            
            // Проверяем статус операции Change (если есть в Responses)
            if (responseXml.contains("<Responses>") && responseXml.contains("<Change>")) {
                val changeStatusMatch = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
                    .find(responseXml)
                if (changeStatusMatch != null) {
                    val changeStatus = changeStatusMatch.groupValues[1]
                    if (changeStatus != "1" && changeStatus != "6" && changeStatus != "7" && changeStatus != "8") {
                        throw Exception("markAsRead failed: Change Status=$changeStatus")
                    }
                }
            }
            
            deps.extractValue(responseXml, "SyncKey") ?: syncKey
        }
        
        // EWS дублирование: EAS Sync Change может не применять Read на Exchange 2007 SP1
        // (пустой ответ, stale SyncKey, серверный баг).
        // EWS UpdateItem с SetItemField message:IsRead — надёжный способ.
        // Документация: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/updateitem-operation
        if (subject != null) {
            try {
                markAsReadViaEws(subject, read)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        
        return easResult
    }
    
    /**
     * Батч-пометка нескольких писем как прочитанных/непрочитанных в одном Sync-запросе.
     * Все письма должны быть из одной папки (одинаковый collectionId + syncKey).
     * MS-ASCMD: "one or more Change elements can appear as a child element of Commands"
     * Exchange 2007 SP1 (EAS 12.1) поддерживает.
     */
    suspend fun markAsReadBatch(
        collectionId: String,
        serverIds: List<String>,
        syncKey: String,
        read: Boolean = true
    ): EasResult<String> {
        if (serverIds.isEmpty()) return EasResult.Success(syncKey)
        
        val readValue = if (read) "1" else "0"
        val esc = deps.escapeXml
        val changesXml = serverIds.joinToString("\n") { sid ->
            """                <Change>
                    <ServerId>${esc(sid)}</ServerId>
                    <ApplicationData>
                        <Read xmlns="Email">$readValue</Read>
                    </ApplicationData>
                </Change>"""
        }
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${esc(syncKey)}</SyncKey>
            <CollectionId>${esc(collectionId)}</CollectionId>
            <GetChanges>0</GetChanges>
            <Commands>
$changesXml
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", xml) { responseXml ->
            android.util.Log.d("EasEmailService", "markAsReadBatch(${serverIds.size}) response len=${responseXml.length}")
            
            val collectionStatus = deps.extractValue(responseXml, "Status")
            if (collectionStatus != null && collectionStatus != "1") {
                throw Exception("markAsReadBatch failed: Collection Status=$collectionStatus")
            }
            
            deps.extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    /**
     * Помечает письмо как прочитанное/непрочитанное через EWS UpdateItem.
     * Находит письмо по Subject через FindItem, затем обновляет IsRead.
     * Работает на Exchange 2007 SP1+ (надёжнее чем EAS Sync Change для Read).
     */
    private suspend fun markAsReadViaEws(subject: String, read: Boolean) {
        val ewsUrl = deps.getEwsUrl()
        val escapedSubject = deps.escapeXml(subject)
        val isReadValue = if (read) "true" else "false"
        
        // 1. FindItem по Subject + IsRead (ищем во ВСЕХ папках через msgfolderroot)
        val findBody = """<m:FindItem Traversal="Shallow">
    <m:ItemShape>
        <t:BaseShape>IdOnly</t:BaseShape>
    </m:ItemShape>
    <m:Restriction>
        <t:And>
            <t:IsEqualTo>
                <t:FieldURI FieldURI="item:Subject"/>
                <t:FieldURIOrConstant>
                    <t:Constant Value="$escapedSubject"/>
                </t:FieldURIOrConstant>
            </t:IsEqualTo>
            <t:IsEqualTo>
                <t:FieldURI FieldURI="message:IsRead"/>
                <t:FieldURIOrConstant>
                    <t:Constant Value="${!read}"/>
                </t:FieldURIOrConstant>
            </t:IsEqualTo>
        </t:And>
    </m:Restriction>
    <m:ParentFolderIds>
        <t:DistinguishedFolderId Id="inbox"/>
        <t:DistinguishedFolderId Id="sentitems"/>
        <t:DistinguishedFolderId Id="drafts"/>
        <t:DistinguishedFolderId Id="deleteditems"/>
    </m:ParentFolderIds>
</m:FindItem>""".trimIndent()
        val findRequest = deps.buildEwsSoapRequest(findBody)
        
        var findResponse = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")?.let { auth ->
            deps.executeNtlmRequest(ewsUrl, findRequest, auth, "FindItem")
        }
        if (findResponse == null) return
        
        // Извлекаем все ItemId + ChangeKey
        val itemPattern = """<t:ItemId Id="([^"]+)"\s+ChangeKey="([^"]+)"""".toRegex()
        val matches = itemPattern.findAll(findResponse).toList()
        if (matches.isEmpty()) return
        
        // 2. UpdateItem для каждого найденного письма
        val itemChanges = matches.joinToString("") { match ->
            val itemId = deps.escapeXml(match.groupValues[1])
            val changeKey = deps.escapeXml(match.groupValues[2])
            """<t:ItemChange>
    <t:ItemId Id="$itemId" ChangeKey="$changeKey"/>
    <t:Updates>
        <t:SetItemField>
            <t:FieldURI FieldURI="message:IsRead"/>
            <t:Message>
                <t:IsRead>$isReadValue</t:IsRead>
            </t:Message>
        </t:SetItemField>
    </t:Updates>
</t:ItemChange>"""
        }
        
        val updateBody = """<m:UpdateItem ConflictResolution="AutoResolve" MessageDisposition="SaveOnly">
    <m:ItemChanges>
$itemChanges
    </m:ItemChanges>
</m:UpdateItem>""".trimIndent()
        val updateRequest = deps.buildEwsSoapRequest(updateBody)
        
        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, updateRequest, "UpdateItem") ?: return
        deps.executeNtlmRequest(ewsUrl, updateRequest, ntlmAuth, "UpdateItem")
    }
    
    /**
     * Переключение флага письма (избранное)
     */
    suspend fun toggleFlag(
        collectionId: String,
        serverId: String,
        syncKey: String,
        flagged: Boolean
    ): EasResult<String> {
        val flagValue = if (flagged) "2" else "0"
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(collectionId)}</CollectionId>
            <GetChanges>0</GetChanges>
            <Commands>
                <Change>
                    <ServerId>${deps.escapeXml(serverId)}</ServerId>
                    <ApplicationData>
                        <Flag xmlns="Email">
                            <FlagStatus>$flagValue</FlagStatus>
                        </Flag>
                    </ApplicationData>
                </Change>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", xml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    /**
     * Удаление письма (в корзину)
     */
    suspend fun deleteEmail(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(collectionId)}</CollectionId>
            <GetChanges>0</GetChanges>
            <DeletesAsMoves>1</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>${deps.escapeXml(serverId)}</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", xml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: syncKey
        }
    }
    
    /**
     * Окончательное удаление письма через EWS HardDelete
     * Используется как fallback для Exchange 2007
     * @param serverId ItemId письма (формат: "FolderId:ItemId" или просто "ItemId")
     */
    suspend fun deleteEmailPermanentlyViaEWS(
        serverId: String,
        subject: String = ""
    ): EasResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            
            val ewsItemId = if (serverId.length < 50 || serverId.contains(":")) {
                findEwsEmailItemId(ewsUrl, subject)
            } else {
                serverId
            }
            
            if (ewsItemId == null) {
                android.util.Log.w("EasEmailService", "EWS delete: could not find ItemId for serverId=$serverId")
                return@withContext EasResult.Error("Could not find EWS ItemId for serverId=$serverId")
            }
            
            val escapedItemId = deps.escapeXml(ewsItemId)
            
            val deleteBody = """<m:DeleteItem DeleteType="HardDelete">
  <m:ItemIds>
    <t:ItemId Id="$escapedItemId"/>
  </m:ItemIds>
</m:DeleteItem>""".trimIndent()
            
            val soapEnvelope = deps.buildEwsSoapRequest(deleteBody)
            
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapEnvelope, "DeleteItem")
            if (ntlmAuth == null) {
                return@withContext EasResult.Error("NTLM аутентификация не удалась")
            }
            
            val responseXml = deps.executeNtlmRequest(ewsUrl, soapEnvelope, ntlmAuth, "DeleteItem")
            if (responseXml == null) {
                return@withContext EasResult.Error("Не удалось выполнить запрос")
            }
            
            // Проверяем ОБА условия
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            
            if (hasSuccess && hasNoError) {
                EasResult.Success(Unit)
            } else if (responseXml.contains("ErrorItemNotFound")) {
                EasResult.Success(Unit) // Уже удалено
            } else {
                val errorMessage = deps.extractEwsError(responseXml)
                EasResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("EWS delete error: ${e.message}")
        }
    }
    
    /**
     * Находит EWS ItemId письма по Subject (EWS FindItem с Restriction).
     * Безопасная замена позиционного индекса, предотвращающая DATA LOSS.
     */
    private suspend fun findEwsEmailItemId(ewsUrl: String, subject: String): String? {
        if (subject.isBlank()) {
            android.util.Log.w("EasEmailService", "findEwsEmailItemId: empty subject — cannot safely identify email")
            return null
        }
        val escapedSubject = deps.escapeXml(subject)
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
        <t:DistinguishedFolderId Id="deleteditems"/>
    </m:ParentFolderIds>
</m:FindItem>""".trimIndent()
        val findRequest = deps.buildEwsSoapRequest(findBody)
        
        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem") ?: return null
        val responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem") ?: return null
        
        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
        return itemIdPattern.find(responseXml)?.groupValues?.get(1)
    }
    
    /**
     * Окончательное удаление письма через EAS Sync Delete
     * Использует DeletesAsMoves=0 + GetChanges (как в AOSP)
     * РАБОТАЕТ на всех версиях Exchange включая 2007
     */
    suspend fun deleteEmailPermanently(
        collectionId: String,
        serverId: String,
        syncKey: String
    ): EasResult<String> {
        
        // КРИТИЧНО: GetChanges=0 — НЕ запрашиваем серверные изменения в ответе Delete.
        // С <GetChanges/> (=TRUE) сервер возвращает pending Adds в ответе,
        // а SyncKey продвигается мимо них. Если эти Adds не обработать —
        // они теряются навсегда (сервер считает их доставленными).
        // С GetChanges=0 сервер обрабатывает Delete, возвращает новый SyncKey,
        // но НЕ включает pending changes. Следующий полноценный sync
        // (в syncEmailsEas) корректно обработает все ожидающие изменения.
        // Совместимо с Exchange 2007 SP1+ (MS-ASCMD 2.2.3.84).
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(collectionId)}</CollectionId>
            <DeletesAsMoves>0</DeletesAsMoves>
            <GetChanges>0</GetChanges>
            <WindowSize>10</WindowSize>
            <Commands>
                <Delete>
                    <ServerId>${deps.escapeXml(serverId)}</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", xml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")?.toIntOrNull() ?: 1
            val newSyncKey = deps.extractValue(responseXml, "SyncKey") ?: syncKey
            
            when (status) {
                1 -> newSyncKey
                8 -> newSyncKey // Object not found - уже удалено
                3 -> throw Exception("INVALID_SYNCKEY")
                else -> throw Exception("Delete failed: Status=$status")
            }
        }
    }
    
    /**
     * Batch удаление писем (несколько писем одним запросом)
     * Решает проблему syncKey race condition при последовательном удалении
     */
    suspend fun deleteEmailsPermanentlyBatch(
        collectionId: String,
        serverIds: List<String>,
        syncKey: String
    ): EasResult<String> {
        if (serverIds.isEmpty()) return EasResult.Success(syncKey)
        
        // КРИТИЧНО: Для ВСЕХ версий Exchange сначала пробуем EAS batch delete.
        // Для элементов в Deleted Items (Корзине) DeletesAsMoves неважен —
        // серверу некуда перемещать, он обязан удалить безвозвратно.
        // Exchange 2007 тоже обрабатывает Delete в Deleted Items корректно.
        val esc = deps.escapeXml
        val deleteCommands = serverIds.joinToString("\n") { sid ->
            """                            <Delete>
                                <ServerId>${esc(sid)}</ServerId>
                            </Delete>"""
        }
        
        // Per MS-ASCMD specification (learn.microsoft.com):
        //
        // 1. GetChanges=0 (MS-ASCMD 2.2.3.84): сервер НЕ возвращает pending changes.
        //    Это предотвращает тихое "проглатывание" Adds (новых писем/черновиков),
        //    которые не были бы обработаны и потерялись бы при продвижении SyncKey.
        //
        // 2. WindowSize=1 (MS-ASCMD 2.2.3.199): минимальный размер окна.
        //    С GetChanges=0 не имеет практического значения, но сохранён для совместимости.
        //
        // 3. Порядок элементов (MS-ASCMD 2.2.3.29.2): СТРОГИЙ —
        //    SyncKey → CollectionId → DeletesAsMoves → GetChanges → WindowSize → Commands
        //
        // Совместимо с Exchange 2007 SP1+.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${esc(syncKey)}</SyncKey>
            <CollectionId>${esc(collectionId)}</CollectionId>
            <DeletesAsMoves>0</DeletesAsMoves>
            <GetChanges>0</GetChanges>
            <WindowSize>1</WindowSize>
            <Commands>
$deleteCommands
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val easResult = deps.executeEasCommand("Sync", xml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")?.toIntOrNull() ?: 1
            val newSyncKey = deps.extractValue(responseXml, "SyncKey") ?: syncKey
            
            when (status) {
                1 -> newSyncKey
                8 -> newSyncKey // Object not found - некоторые уже удалены
                3 -> throw Exception("INVALID_SYNCKEY")
                else -> throw Exception("Batch delete failed: Status=$status")
            }
        }
        
        // Если EAS batch delete удался — возвращаем результат
        if (easResult is EasResult.Success) {
            return easResult
        }
        
        // EAS batch delete не удался — ошибка пробрасывается к caller'у.
        // Caller (EmailOperationsService) выполнит individual EWS deletes
        // с Subject-based поиском, что безопаснее batch позиционного подхода.
        if (deps.isExchange2007()) {
            android.util.Log.w("EasEmailService", "EAS batch delete failed (${(easResult as? EasResult.Error)?.message}), caller will handle EWS fallback per-item")
            return when (val resetResult = sync(collectionId, "0")) {
                is EasResult.Success -> {
                    EasResult.Error("DELETE_NOT_APPLIED")
                }
                is EasResult.Error -> {
                    EasResult.Error("DELETE_NOT_APPLIED")
                }
            }
        }
        
        // Для Exchange 2010+ возвращаем оригинальную ошибку
        return easResult
    }
    
    /**
     * Перемещение писем между папками
     */
    suspend fun moveItems(
        items: List<Pair<String, String>>,
        dstFolderId: String
    ): EasResult<Map<String, String>> {
        if (items.isEmpty()) {
            return EasResult.Success(emptyMap())
        }
        
        val esc = deps.escapeXml
        val safeDst = esc(dstFolderId)
        val movesXml = items.joinToString("") { (srcFolderId, serverId) ->
            """
                <Move>
                    <SrcMsgId>${esc(serverId)}</SrcMsgId>
                    <SrcFldId>${esc(srcFolderId)}</SrcFldId>
                    <DstFldId>$safeDst</DstFldId>
                </Move>
            """.trimIndent()
        }
        
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<MoveItems xmlns="Move">
    $movesXml
</MoveItems>""".trimIndent()
        
        return deps.executeEasCommand("MoveItems", xml) { responseXml ->
            parseMoveItemsResponse(responseXml)
        }
    }
    
    // ==================== Private methods ====================
    
    private fun buildMimeMessageBytes(
        to: String,
        subject: String,
        body: String,
        cc: String,
        bcc: String,
        requestReadReceipt: Boolean,
        requestDeliveryReceipt: Boolean,
        importance: Int
    ): ByteArray {
        val fromEmail = deps.getFromEmail()
        val deviceId = deps.getDeviceId()
        val messageId = "<${System.currentTimeMillis()}.${System.nanoTime()}@$deviceId>"
        
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        
        // Извлекаем inline картинки из data: URLs
        val dataImagePattern = Regex("""<img[^>]*src="data:image/(jpeg|jpg|png|gif);base64,([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val matches = dataImagePattern.findAll(body).toList()
        
        val sb = StringBuilder()
        sb.appendMimeHeaders(
            date = date,
            fromEmail = fromEmail,
            to = to,
            cc = cc,
            bcc = bcc,
            messageId = messageId,
            subject = subject,
            importance = importance,
            requestReadReceipt = requestReadReceipt,
            requestDeliveryReceipt = requestDeliveryReceipt
        )
        
        if (matches.isEmpty()) {
            sb.append("Content-Type: text/html; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("\r\n")
            sb.append(android.util.Base64.encodeToString(body.toByteArray(Charsets.UTF_8), android.util.Base64.CRLF))
        } else {
            // Есть inline картинки - multipart/related
            val boundary = "----=_Part_${System.currentTimeMillis()}_${System.nanoTime()}"
            sb.append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n")
            sb.append("\r\n")
            sb.append("This is a multi-part message in MIME format.\r\n")
            sb.append("\r\n")
            
            // Часть 1: HTML body с замененными cid:
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: text/html; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("\r\n")
            
            var modifiedBody = body
            matches.forEachIndexed { index, match ->
                val fullMatch = match.value
                val contentId = "image${index + 1}@$deviceId"
                
                val replacement = fullMatch.replace(
                    DATA_IMAGE_SRC_REGEX,
                    """src="cid:$contentId""""
                )
                modifiedBody = modifiedBody.replace(fullMatch, replacement)
            }
            
            sb.append(android.util.Base64.encodeToString(modifiedBody.toByteArray(Charsets.UTF_8), android.util.Base64.CRLF))
            sb.append("\r\n")
            
            // Часть 2..N: Inline картинки
            matches.forEachIndexed { index, match ->
                val imageType = match.groupValues[1]
                val base64Data = match.groupValues[2]
                val contentId = "image${index + 1}@$deviceId"
                
                sb.append("--$boundary\r\n")
                sb.append("Content-Type: image/$imageType\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n")
                sb.append("Content-ID: <$contentId>\r\n")
                sb.append("Content-Disposition: inline\r\n")
                sb.append("\r\n")
                
                // Разбиваем base64 на строки по 76 символов (RFC 2045)
                val lines = base64Data.chunked(76)
                lines.forEach { line ->
                    sb.append(line)
                    sb.append("\r\n")
                }
                sb.append("\r\n")
            }
            
            sb.append("--$boundary--\r\n")
        }
        
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    private fun parseSyncResponse(xml: String): SyncResponse {
        val syncKey = deps.extractValue(xml, "SyncKey") ?: "0"
        val status = deps.extractValue(xml, "Status")?.toIntOrNull() ?: 1
        val moreAvailable = xml.contains("<MoreAvailable/>") || xml.contains("<MoreAvailable>")
        
        val emails = mutableListOf<EasEmail>()
        val deletedIds = mutableListOf<String>()
        val changedEmails = mutableListOf<EasEmailChange>()
        
        for (addXml in XmlUtils.extractTopLevelBlocks(xml, "Add")) {
            val email = parseEmailFromXml(addXml)
            if (email != null) emails.add(email)
        }
        
        for (changeXml in XmlUtils.extractTopLevelBlocks(xml, "Change")) {
            val serverId = deps.extractValue(changeXml, "ServerId")
            if (serverId != null) {
                val readValue = deps.extractValue(changeXml, "Read")
                val read = readValue?.let { it == "1" }
                
                val flagXml = XmlUtils.extractTagValue(changeXml, "Flag")
                val flagStatus = flagXml?.let { deps.extractValue(it, "FlagStatus") }
                val flagged = flagStatus?.let { it == "2" }
                
                val bodySection = XmlUtils.extractTagValue(changeXml, "Body")
                val body = if (bodySection != null) extractBody(changeXml) else ""
                val bodyType = if (body.isNotBlank() && bodySection != null) {
                    deps.extractValue(bodySection, "Type")?.toIntOrNull() ?: 1
                } else null
                
                val attachments = parseAttachmentsFromXml(changeXml)
                
                changedEmails.add(EasEmailChange(
                    serverId, read, flagged,
                    body = body.takeIf { it.isNotBlank() },
                    bodyType = bodyType,
                    attachments = attachments
                ))
            }
        }
        
        for (block in XmlUtils.extractTopLevelBlocks(xml, "Delete")) {
            deps.extractValue(block, "ServerId")?.let { deletedIds.add(it) }
        }
        for (block in XmlUtils.extractTopLevelBlocks(xml, "SoftDelete")) {
            deps.extractValue(block, "ServerId")?.let { deletedIds.add(it) }
        }
        
        return SyncResponse(
            syncKey = syncKey,
            status = status,
            moreAvailable = moreAvailable,
            emails = emails,
            deletedIds = deletedIds,
            changedEmails = changedEmails
        )
    }
    
    private fun parseEmailFromXml(xml: String): EasEmail? {
        val serverId = deps.extractValue(xml, "ServerId") ?: return null
        
        // Парсим вложения (DRY: используем общий helper)
        val attachments = parseAttachmentsFromXml(xml)
        
        // Извлекаем <Type> из секции <Body>, а не из полного XML,
        // т.к. тег "Type" существует в нескольких code page WBXML
        // (AirSyncBase, Email/Recurrence, Tasks и др.).
        val bodySectionAdd = XmlUtils.extractTagValue(xml, "Body")
        val bodyType = if (bodySectionAdd != null) {
            deps.extractValue(bodySectionAdd, "Type")?.toIntOrNull() ?: 1
        } else {
            deps.extractValue(xml, "Type")?.toIntOrNull() ?: 1
        }
        val rawBody = extractBody(xml)
        
        // КРИТИЧНО: Для Type=4 (MIME) сохраняем полный MIME (не извлекаем HTML)
        // Это нужно для inline-картинок в Sent Items
        // HTML будет извлечен при отображении в EmailDetailScreen
        val body = rawBody
        
        // MS-ASEMAIL 2.2.2.58: Read — optional. "1" = read, "0" = unread.
        // Exchange 2007 SP1 may omit <Read> for some emails (old system entries).
        // Per spec, omission ≠ "0". Default to true (read) when absent:
        // the server explicitly sends <Read>0</Read> for genuinely unread messages.
        val readValue = deps.extractValue(xml, "Read")
        val isRead = if (readValue != null) readValue == "1" else true

        // MS-ASEMAIL: FlagStatus inside <Flag>. "2" = flagged.
        val flagSection = XmlUtils.extractTagValue(xml, "Flag")
        val isFlagged = flagSection?.let { deps.extractValue(it, "FlagStatus") == "2" } ?: false

        return EasEmail(
            serverId = serverId,
            from = XmlUtils.unescape(deps.extractValue(xml, "From") ?: ""),
            to = XmlUtils.unescape(deps.extractValue(xml, "To") ?: ""),
            cc = XmlUtils.unescape(deps.extractValue(xml, "Cc") ?: ""),
            subject = XmlUtils.unescape(deps.extractValue(xml, "Subject") ?: "(No subject)"),
            dateReceived = deps.extractValue(xml, "DateReceived") ?: "",
            read = isRead,
            importance = deps.extractValue(xml, "Importance")?.toIntOrNull() ?: 1,
            body = body,
            bodyType = bodyType,
            attachments = attachments,
            flagged = isFlagged
        )
    }
    
    private fun extractBody(xml: String): String {
        // Пробуем разные варианты извлечения body
        val patterns = listOf(DATA_PATTERN, AIRSYNC_DATA_REGEX)
        for (regex in patterns) {
            val match = regex.find(xml)
            if (match != null) {
                // КРИТИЧНО: XML-данные внутри <Data> содержат escaped entities
                // (&lt; &gt; &amp; и т.д.) — декодируем обратно в HTML
                return XmlUtils.unescape(match.groupValues[1])
            }
        }
        return ""
    }
    
    /**
     * Извлекает HTML/текст из MIME данных (для bodyType=4)
     */
    private fun looksLikeBase64(s: String): Boolean {
        for (c in s) {
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' || c.isWhitespace()) continue
            return false
        }
        return true
    }
    
    private fun extractBodyFromMime(mimeData: String): String {
        val decoded = try {
            if (looksLikeBase64(mimeData)) {
                String(android.util.Base64.decode(mimeData, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else {
                mimeData
            }
        } catch (e: Exception) {
            mimeData
        }
        
        // RFC 2045 §6.1: Content-Transfer-Encoding applies per MIME part independently
        val htmlPartPattern = "(Content-Type:\\s*text/html[^\\r\\n]*(?:\\r?\\n[^\\r\\n]+)*)\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val htmlMatch = htmlPartPattern.find(decoded)
        if (htmlMatch != null) {
            return decodeMimePartContent(htmlMatch.groupValues[1], htmlMatch.groupValues[2].trim())
        }
        
        val textPartPattern = "(Content-Type:\\s*text/plain[^\\r\\n]*(?:\\r?\\n[^\\r\\n]+)*)\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val textMatch = textPartPattern.find(decoded)
        if (textMatch != null) {
            return decodeMimePartContent(textMatch.groupValues[1], textMatch.groupValues[2].trim())
        }
        
        return decoded
    }
    
    private fun decodeMimePartContent(headers: String, body: String): String {
        val encodingMatch = "Content-Transfer-Encoding:\\s*(\\S+)".toRegex(RegexOption.IGNORE_CASE).find(headers)
        val encoding = encodingMatch?.groupValues?.get(1)?.lowercase() ?: "7bit"
        return when (encoding) {
            "base64" -> try {
                String(android.util.Base64.decode(body, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) { body }
            "quoted-printable" -> decodeQuotedPrintable(body)
            else -> body
        }
    }
    
    /**
     * Декодирует quoted-printable кодировку
     */
    private fun decodeQuotedPrintable(input: String): String {
        val text = input.replace("=\r\n", "").replace("=\n", "")
        val bytes = mutableListOf<Byte>()
        var i = 0
        
        while (i < text.length) {
            if (text[i] == '=' && i + 2 < text.length) {
                try {
                    val hex = text.substring(i + 1, i + 3)
                    bytes.add(hex.toInt(16).toByte())
                    i += 3
                } catch (_: Exception) {
                    bytes.add(text[i].code.toByte())
                    i++
                }
            } else {
                bytes.add(text[i].code.toByte())
                i++
            }
        }
        
        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            input
        }
    }
    
    private fun parseMoveItemsResponse(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val responsePattern = "<Response>(.*?)</Response>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        responsePattern.findAll(xml).forEach { match ->
            val responseXml = match.groupValues[1]
            val srcMsgId = deps.extractValue(responseXml, "SrcMsgId")
            val dstMsgId = deps.extractValue(responseXml, "DstMsgId")
            val status = deps.extractValue(responseXml, "Status")
            
            if (srcMsgId != null && status == "3" && dstMsgId != null) {
                result[srcMsgId] = dstMsgId
            }
        }
        
        return result
    }
    
    private fun getStatusDescription(status: String): String = when (status) {
        "110" -> "Ошибка формата сообщения"
        "111" -> "Неверный получатель"
        "112" -> "Ошибка отправки"
        "113" -> "Сообщение слишком большое"
        "114" -> "Превышен лимит получателей"
        "115" -> "Превышена квота отправки"
        "116" -> "Ящик переполнен"
        "117" -> "Вложение слишком большое"
        "118" -> "Отправка запрещена политикой"
        "119" -> "Неверный формат вложения"
        "120" -> "Ошибка сервера"
        else -> "Неизвестная ошибка"
    }
}
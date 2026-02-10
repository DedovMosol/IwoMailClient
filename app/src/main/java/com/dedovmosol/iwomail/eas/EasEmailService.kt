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
        
        // Regex паттерны для MIME парсинга (DRY: используем EasPatterns)
        private val MDN_DISPOSITION_REGEX get() = EasPatterns.MDN_DISPOSITION
        private val MDN_RETURN_RECEIPT_REGEX get() = EasPatterns.MDN_RETURN_RECEIPT
        private val MDN_CONFIRM_READING_REGEX get() = EasPatterns.MDN_CONFIRM_READING
        private val EMAIL_BRACKET_REGEX get() = EasPatterns.EMAIL_BRACKET
        private val BOUNDARY_REGEX get() = EasPatterns.BOUNDARY
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
        val xml = if (syncKey == "0") {
            """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        } else {
            val mimeSupport = if (includeMime) "<MIMESupport xmlns=\"AirSyncBase\">2</MIMESupport>" else ""
            """<?xml version="1.0" encoding="UTF-8"?>
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
        if (to.isBlank() || !to.contains("@")) {
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
                val url = deps.buildUrl("SendMail") + "&SaveInSent=T"
                
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
                val response = deps.executeRequest(request)
                
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
                                
                                val retryResponse = deps.executeRequest(retryRequest)
                                if (retryResponse.isSuccessful) {
                                    return@withContext EasResult.Success(true)
                                }
                            }
                            is EasResult.Error -> {}
                        }
                    }
                    EasResult.Error("Ошибка отправки письма (HTTP ${response.code})")
                }
            } catch (e: Exception) {
                EasResult.Error("Ошибка отправки письма: ${e.message}")
            }
        }
    }
    
    /**
     * Загрузка тела письма с MDN информацией
     * Сначала пробует MIME (Type=4) для парсинга заголовков, потом HTML, потом plain text
     */
    suspend fun fetchEmailBodyWithMdn(collectionId: String, serverId: String): EasResult<EmailBodyResult> {
        return withTimeoutOrNull(25_000L) {
            // Сначала пробуем получить полный MIME (Type=4) для парсинга заголовков.
            // КРИТИЧНО: Увеличен TruncationSize с 1 МБ до 5 МБ.
            // При отправке писем с inline-картинками + файловыми вложениями MIME легко
            // превышает 1 МБ (base64 картинки раздуваются ~33%). С лимитом 1 МБ сервер
            // возвращал Truncated=1 → откатывался к HTML (Type=2) с cid: ссылками без данных →
            // inline-картинки не отображались в Отправленных.
            // 5 МБ покрывает большинство писем с 2-3 inline-картинками + файлами.
            val mimeXml = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">$collectionId</CollectionId>
                        <ServerId xmlns="AirSync">$serverId</ServerId>
                        <Options>
                            <BodyPreference xmlns="AirSyncBase">
                                <Type>4</Type>
                                <TruncationSize>5242880</TruncationSize>
                            </BodyPreference>
                        </Options>
                    </Fetch>
                </ItemOperations>
            """.trimIndent()
            
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
                
                unescapeXml(deps.extractValue(responseXml, "Data") 
                    ?: run {
                        val pattern = "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
                        pattern.find(responseXml)?.groupValues?.get(1)
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
        <CollectionId xmlns="AirSync">$collectionId</CollectionId>
        <ServerId xmlns="AirSync">$serverId</ServerId>
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
                    
                    unescapeXml(deps.extractValue(responseXml, "Data") 
                        ?: deps.extractValue(responseXml, "Body")
                        ?: run {
                            val pattern = "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
                            pattern.find(responseXml)?.groupValues?.get(1)
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
        <CollectionId xmlns="AirSync">$collectionId</CollectionId>
        <ServerId xmlns="AirSync">$serverId</ServerId>
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
                    
                    unescapeXml(deps.extractValue(responseXml, "Data") 
                        ?: deps.extractValue(responseXml, "Body")
                        ?: run {
                            val pattern = "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
                            pattern.find(responseXml)?.groupValues?.get(1)
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
     * Исправляет битые <img> теги без src/data-uri
     * Заменяет на placeholder с сообщением "Изображение недоступно"
     */
    private fun fixBrokenImages(html: String): String {
        // Находим все <img> теги
        val imgPattern = """<img[^>]*>""".toRegex(RegexOption.IGNORE_CASE)
        
        return imgPattern.replace(html) { matchResult ->
            val imgTag = matchResult.value
            
            // Проверяем наличие валидного src
            val hasSrc = imgTag.contains("""src\s*=\s*["'][^"']+["']""".toRegex(RegexOption.IGNORE_CASE))
            val hasDataUri = imgTag.contains("""src\s*=\s*["']data:image""".toRegex(RegexOption.IGNORE_CASE))
            val hasCid = imgTag.contains("""src\s*=\s*["']cid:""".toRegex(RegexOption.IGNORE_CASE))
            
            if (!hasSrc || (!hasDataUri && !hasCid && !imgTag.contains("http", ignoreCase = true))) {
                // Битый <img> без валидного src - заменяем на placeholder
                """<div style="display:inline-block;padding:8px 12px;background:#f0f0f0;border:1px dashed #ccc;border-radius:4px;color:#666;font-size:12px;font-family:sans-serif;">📷 Изображение недоступно</div>"""
            } else {
                // Валидный <img> - оставляем как есть
                imgTag
            }
        }
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
    private fun extractImagesRecursive(mimeSection: String, images: MutableMap<String, String>) {
        val boundaryMatch = BOUNDARY_REGEX.find(mimeSection) ?: return
        val boundary = boundaryMatch.groupValues[1]
        val parts = mimeSection.split("--$boundary")
        
        for (part in parts) {
            // Если часть содержит вложенный multipart — рекурсивно обрабатываем
            val isNestedMultipart = part.contains("Content-Type: multipart/", ignoreCase = true) ||
                                   part.contains("Content-Type:multipart/", ignoreCase = true)
            if (isNestedMultipart) {
                extractImagesRecursive(part, images)
                continue
            }
            
            val isImage = part.contains("Content-Type: image/", ignoreCase = true) ||
                         part.contains("Content-Type:image/", ignoreCase = true)
            
            if (!isImage) continue
            
            val cidPattern = "Content-ID:\\s*<([^>]+)>".toRegex(RegexOption.IGNORE_CASE)
            val cidMatch = cidPattern.find(part)
            val contentId = cidMatch?.groupValues?.get(1) ?: continue
            
            val typePattern = "Content-Type:\\s*(image/[^;\\r\\n]+)".toRegex(RegexOption.IGNORE_CASE)
            val typeMatch = typePattern.find(part)
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
        val mimeXml = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <CollectionId xmlns="AirSync">$collectionId</CollectionId>
        <ServerId xmlns="AirSync">$serverId</ServerId>
        <Options>
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
            
            val mimeData = unescapeXml(deps.extractValue(responseXml, "Data") 
                ?: run {
                    val pattern = "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
                    pattern.find(responseXml)?.groupValues?.get(1)
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
            <GetChanges>0</GetChanges>
            <Commands>
                <Change>
                    <ServerId>$serverId</ServerId>
                    <ApplicationData>
                        <Read xmlns="Email">$readValue</Read>
                    </ApplicationData>
                </Change>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val easResult = deps.executeEasCommand("Sync", xml) { responseXml ->
            android.util.Log.d("EasEmailService", "markAsRead response: ${responseXml.take(500)}")
            
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
            } catch (_: Exception) {
                // EWS не сработал — EAS мог сработать, не ломаем
            }
        }
        
        return easResult
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
            val itemId = match.groupValues[1]
            val changeKey = match.groupValues[2]
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
            <GetChanges>0</GetChanges>
            <Commands>
                <Change>
                    <ServerId>$serverId</ServerId>
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
            <GetChanges>0</GetChanges>
            <DeletesAsMoves>1</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>$serverId</ServerId>
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
        serverId: String
    ): EasResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            
            // Проверяем формат serverId - если это EAS формат (короткий), нужно найти EWS ItemId
            val ewsItemId = if (serverId.length < 50 || serverId.contains(":")) {
                findEwsEmailItemId(ewsUrl, serverId)
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
            EasResult.Error("EWS delete error: ${e.message}")
        }
    }
    
    /**
     * Находит EWS ItemId письма по EAS ServerId
     */
    private suspend fun findEwsEmailItemId(ewsUrl: String, easServerId: String): String? {
        val findBody = """<m:FindItem Traversal="Shallow">
    <m:ItemShape>
        <t:BaseShape>IdOnly</t:BaseShape>
    </m:ItemShape>
    <m:IndexedPageItemView MaxEntriesReturned="500" Offset="0" BasePoint="Beginning"/>
    <m:ParentFolderIds>
        <t:DistinguishedFolderId Id="deleteditems"/>
    </m:ParentFolderIds>
</m:FindItem>""".trimIndent()
        val findRequest = deps.buildEwsSoapRequest(findBody)
        
        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
        if (ntlmAuth == null) {
            return null
        }
        
        val responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
        if (responseXml == null) {
            return null
        }
        
        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
        val matches = itemIdPattern.findAll(responseXml).toList()
        
        val index = easServerId.substringAfter(":").toIntOrNull()?.minus(1) ?: 0
        return matches.getOrNull(index)?.groupValues?.get(1) ?: matches.firstOrNull()?.groupValues?.get(1)
    }
    
    /**
     * КРИТИЧНО: Batch удаление писем через EWS для Exchange 2007
     * 
     * Проблема: При последовательном удалении через deleteEmailPermanentlyViaEWS
     * после удаления первого письма индексы в папке смещаются.
     * Если было 3 письма [A:1, B:2, C:3], после удаления B:2 остаётся [A:1, C:2]
     * и попытка удалить C:3 провалится или удалит не то письмо.
     * 
     * Решение: Получаем ВСЕ EWS ItemIds СРАЗУ одним запросом FindItem,
     * затем удаляем все ОДНИМ запросом DeleteItem.
     */
    private suspend fun deleteEmailsBatchViaEWS(serverIds: List<String>): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            if (serverIds.isEmpty()) return@withContext EasResult.Success("OK")
            
            val ewsUrl = deps.getEwsUrl()
            
            // ШАГ 1: Получаем ВСЕ ItemIds из deleteditems одним запросом
            val findBody = """<m:FindItem Traversal="Shallow">
    <m:ItemShape>
        <t:BaseShape>IdOnly</t:BaseShape>
    </m:ItemShape>
    <m:IndexedPageItemView MaxEntriesReturned="500" Offset="0" BasePoint="Beginning"/>
    <m:ParentFolderIds>
        <t:DistinguishedFolderId Id="deleteditems"/>
    </m:ParentFolderIds>
</m:FindItem>""".trimIndent()
            val findRequest = deps.buildEwsSoapRequest(findBody)
            
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
            if (ntlmAuth == null) {
                return@withContext EasResult.Error("NTLM аутентификация не удалась")
            }
            
            val findResponse = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
            if (findResponse == null) {
                return@withContext EasResult.Error("Не удалось получить список писем")
            }
            
            // Извлекаем ВСЕ ItemIds из ответа
            val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
            val allItemIds = itemIdPattern.findAll(findResponse).map { it.groupValues[1] }.toList()
            
            if (allItemIds.isEmpty()) {
                // Нет писем в корзине — не можем найти для удаления
                android.util.Log.w("EasEmailService", "EWS batch delete: FindItem returned 0 items in deleteditems")
                return@withContext EasResult.Error("No items found in deleteditems folder")
            }
            
            // ШАГ 2: Выбираем ItemIds соответствующие нашим serverIds (по индексу)
            val itemIdsToDelete = mutableListOf<String>()
            for (serverId in serverIds) {
                val index = serverId.substringAfter(":").toIntOrNull()?.minus(1) ?: 0
                if (index >= 0 && index < allItemIds.size) {
                    itemIdsToDelete.add(allItemIds[index])
                } else if (serverId.length >= 50 && !serverId.contains(":")) {
                    // Это уже EWS ItemId
                    itemIdsToDelete.add(serverId)
                }
            }
            
            if (itemIdsToDelete.isEmpty()) {
                android.util.Log.w("EasEmailService", "EWS batch delete: no matching ItemIds found for ${serverIds.size} serverIds (allItemIds=${allItemIds.size})")
                return@withContext EasResult.Error("No matching items found for deletion")
            }
            
            // ШАГ 3: Удаляем ВСЕ письма ОДНИМ запросом DeleteItem
            val itemIdsXml = itemIdsToDelete.joinToString("\n") { itemId ->
                """    <t:ItemId Id="${deps.escapeXml(itemId)}"/>"""
            }
            
            val deleteBody = """<m:DeleteItem DeleteType="HardDelete">
  <m:ItemIds>
$itemIdsXml
  </m:ItemIds>
</m:DeleteItem>""".trimIndent()
            
            val deleteRequest = deps.buildEwsSoapRequest(deleteBody)
            
            val deleteNtlm = deps.performNtlmHandshake(ewsUrl, deleteRequest, "DeleteItem")
            if (deleteNtlm == null) {
                return@withContext EasResult.Error("NTLM аутентификация не удалась")
            }
            
            val deleteResponse = deps.executeNtlmRequest(ewsUrl, deleteRequest, deleteNtlm, "DeleteItem")
            if (deleteResponse == null) {
                return@withContext EasResult.Error("Не удалось выполнить удаление")
            }
            
            // Проверяем успешность (хотя бы одно Success)
            val hasSuccess = deleteResponse.contains("ResponseClass=\"Success\"")
            val hasNoError = deleteResponse.contains("NoError")
            
            if (hasSuccess || hasNoError || deleteResponse.contains("ErrorItemNotFound")) {
                EasResult.Success("OK")
            } else {
                val errorMessage = deps.extractEwsError(deleteResponse)
                EasResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            EasResult.Error("EWS batch delete error: ${e.message}")
        }
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
            <DeletesAsMoves>0</DeletesAsMoves>
            <GetChanges>0</GetChanges>
            <WindowSize>10</WindowSize>
            <Commands>
                <Delete>
                    <ServerId>$serverId</ServerId>
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
        val deleteCommands = serverIds.joinToString("\n") { serverId ->
            """                            <Delete>
                                <ServerId>$serverId</ServerId>
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$collectionId</CollectionId>
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
        
        // EAS не сработал — для Exchange 2007 пробуем EWS fallback
        if (deps.isExchange2007()) {
            android.util.Log.w("EasEmailService", "EAS batch delete failed (${(easResult as? EasResult.Error)?.message}), trying EWS fallback")
            val ewsResult = deleteEmailsBatchViaEWS(serverIds)
            if (ewsResult is EasResult.Error) {
                // EWS тоже не сработал — возвращаем ошибку
                return ewsResult
            }
            // EWS удаление успешно — обновляем syncKey
            return when (val syncResult = sync(collectionId, syncKey)) {
                is EasResult.Success -> EasResult.Success(syncResult.data.syncKey)
                is EasResult.Error -> {
                    when (val resetResult = sync(collectionId, "0")) {
                        is EasResult.Success -> EasResult.Success(resetResult.data.syncKey)
                        is EasResult.Error -> EasResult.Error("Sync key refresh failed after EWS delete")
                    }
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
        
        val movesXml = items.joinToString("") { (srcFolderId, serverId) ->
            """
                <Move>
                    <SrcMsgId>$serverId</SrcMsgId>
                    <SrcFldId>$srcFolderId</SrcFldId>
                    <DstFldId>$dstFolderId</DstFldId>
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
        
        when (importance) {
            0 -> {
                sb.append("X-Priority: 5\r\n")
                sb.append("Importance: Low\r\n")
            }
            2 -> {
                sb.append("X-Priority: 1\r\n")
                sb.append("Importance: High\r\n")
            }
            else -> {
                sb.append("X-Priority: 3\r\n")
                sb.append("Importance: Normal\r\n")
            }
        }
        
        if (requestReadReceipt) {
            sb.append("Disposition-Notification-To: $fromEmail\r\n")
        }
        if (requestDeliveryReceipt) {
            sb.append("Return-Receipt-To: $fromEmail\r\n")
        }
        
        sb.append("MIME-Version: 1.0\r\n")
        
        if (matches.isEmpty()) {
            // Нет inline картинок - простой HTML
            sb.append("Content-Type: text/html; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n")
            sb.append(body)
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
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n")
            
            var modifiedBody = body
            matches.forEachIndexed { index, match ->
                val fullMatch = match.value
                val imageType = match.groupValues[1]
                val base64Data = match.groupValues[2]
                val contentId = "image${index + 1}@$deviceId"
                
                // Заменяем data: URL на cid:
                val replacement = fullMatch.replace(
                    Regex("""src="data:image/[^;]+;base64,[^"]+""""),
                    """src="cid:$contentId""""
                )
                modifiedBody = modifiedBody.replace(fullMatch, replacement)
            }
            
            sb.append(modifiedBody)
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
        
        // Парсим добавленные письма (<Add>)
        val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        addPattern.findAll(xml).forEach { match ->
            val addXml = match.groupValues[1]
            val email = parseEmailFromXml(addXml)
            if (email != null) {
                emails.add(email)
            }
        }
        
        // Парсим изменённые письма (<Change>) — Read, Flag, и Body
        // MS-ASCMD 2.2.3.24: Change содержит ApplicationData с изменёнными свойствами.
        // Exchange может включать обновлённый Body при редактировании черновика в Outlook.
        val changePattern = "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        changePattern.findAll(xml).forEach { match ->
            val changeXml = match.groupValues[1]
            val serverId = deps.extractValue(changeXml, "ServerId")
            if (serverId != null) {
                val readValue = deps.extractValue(changeXml, "Read")
                val read = readValue?.let { it == "1" }
                
                val flagXml = "<Flag>(.*?)</Flag>".toRegex(RegexOption.DOT_MATCHES_ALL).find(changeXml)?.groupValues?.get(1)
                val flagStatus = flagXml?.let { deps.extractValue(it, "FlagStatus") }
                val flagged = flagStatus?.let { it == "2" }
                
                // Извлекаем body из Change (для черновиков, отредактированных во внешнем клиенте).
                // Извлекаем <Type> из секции <Body>, а не из полного XML,
                // т.к. тег "Type" существует в нескольких code page WBXML
                // (AirSyncBase, Email/Recurrence, Tasks и др.).
                val bodySection = "<Body>(.*?)</Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
                    .find(changeXml)?.groupValues?.get(1)
                val body = if (bodySection != null) extractBody(changeXml) else ""
                val bodyType = if (body.isNotBlank() && bodySection != null) {
                    deps.extractValue(bodySection, "Type")?.toIntOrNull() ?: 1
                } else null
                
                if (read != null || flagged != null || body.isNotBlank()) {
                    changedEmails.add(EasEmailChange(
                        serverId, read, flagged,
                        body = body.takeIf { it.isNotBlank() },
                        bodyType = bodyType
                    ))
                }
            }
        }
        
        // Парсим удалённые письма (<Delete> и <SoftDelete>)
        val deletePattern = "<Delete>(.*?)</Delete>".toRegex(RegexOption.DOT_MATCHES_ALL)
        deletePattern.findAll(xml).forEach { match ->
            deps.extractValue(match.groupValues[1], "ServerId")?.let { deletedIds.add(it) }
        }
        
        val softDeletePattern = "<SoftDelete>(.*?)</SoftDelete>".toRegex(RegexOption.DOT_MATCHES_ALL)
        softDeletePattern.findAll(xml).forEach { match ->
            deps.extractValue(match.groupValues[1], "ServerId")?.let { deletedIds.add(it) }
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
        
        // Парсим вложения
        val attachments = mutableListOf<EasAttachment>()
        val attachmentPattern = "<Attachment>(.*?)</Attachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        attachmentPattern.findAll(xml).forEach { match ->
            val attXml = match.groupValues[1]
            val fileRef = deps.extractValue(attXml, "FileReference") ?: ""
            val displayName = deps.extractValue(attXml, "DisplayName")
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
        
        // Извлекаем <Type> из секции <Body>, а не из полного XML,
        // т.к. тег "Type" существует в нескольких code page WBXML
        // (AirSyncBase, Email/Recurrence, Tasks и др.).
        val bodySectionAdd = "<Body>(.*?)</Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
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
        
        return EasEmail(
            serverId = serverId,
            from = deps.extractValue(xml, "From") ?: "",
            to = deps.extractValue(xml, "To") ?: "",
            cc = deps.extractValue(xml, "Cc") ?: "",
            subject = deps.extractValue(xml, "Subject") ?: "(No subject)",
            dateReceived = deps.extractValue(xml, "DateReceived") ?: "",
            read = deps.extractValue(xml, "Read") == "1",
            importance = deps.extractValue(xml, "Importance")?.toIntOrNull() ?: 1,
            body = body,
            bodyType = bodyType,
            attachments = attachments
        )
    }
    
    private fun extractBody(xml: String): String {
        // Пробуем разные варианты извлечения body
        val patterns = listOf(
            "<Data>(.*?)</Data>",
            "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                // КРИТИЧНО: XML-данные внутри <Data> содержат escaped entities
                // (&lt; &gt; &amp; и т.д.) — декодируем обратно в HTML
                return unescapeXml(match.groupValues[1])
            }
        }
        return ""
    }
    
    /**
     * Декодирует XML entities (&lt;, &gt;, &quot;, &amp;, &apos;)
     */
    private fun unescapeXml(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
    
    /**
     * Извлекает HTML/текст из MIME данных (для bodyType=4)
     */
    private fun extractBodyFromMime(mimeData: String): String {
        // Декодируем base64 если нужно
        val decoded = try {
            if (mimeData.matches(Regex("^[A-Za-z0-9+/=\\s]+$"))) {
                String(android.util.Base64.decode(mimeData, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else {
                mimeData
            }
        } catch (e: Exception) {
            mimeData
        }
        
        // Ищем HTML часть
        val htmlPattern = "Content-Type:\\s*text/html.*?\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val htmlMatch = htmlPattern.find(decoded)
        if (htmlMatch != null) {
            var content = htmlMatch.groupValues[1].trim()
            // Декодируем quoted-printable если нужно
            if (decoded.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                content = decodeQuotedPrintable(content)
            }
            return content
        }
        
        // Fallback на text/plain
        val textPattern = "Content-Type:\\s*text/plain.*?\\r?\\n\\r?\\n(.*?)(?=--|\$)".toRegex(
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val textMatch = textPattern.find(decoded)
        if (textMatch != null) {
            var content = textMatch.groupValues[1].trim()
            if (decoded.contains("Content-Transfer-Encoding: quoted-printable", ignoreCase = true)) {
                content = decodeQuotedPrintable(content)
            }
            return content
        }
        
        return decoded
    }
    
    /**
     * Декодирует quoted-printable кодировку
     */
    private fun decodeQuotedPrintable(input: String): String {
        val result = StringBuilder()
        var i = 0
        val cleaned = input.replace("=\r\n", "").replace("=\n", "")
        
        while (i < cleaned.length) {
            val c = cleaned[i]
            if (c == '=' && i + 2 < cleaned.length) {
                val hex = cleaned.substring(i + 1, i + 3)
                try {
                    val byte = hex.toInt(16).toByte()
                    result.append(byte.toInt().toChar())
                    i += 3
                    continue
                } catch (e: Exception) {
                    // Не hex, добавляем как есть
                }
            }
            result.append(c)
            i++
        }
        return result.toString()
    }
    
    private fun extractBodyFromItemOperations(xml: String): String {
        val dataPattern = "<(?:airsyncbase:)?Data>(.*?)</(?:airsyncbase:)?Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val raw = dataPattern.find(xml)?.groupValues?.get(1) ?: return ""
        return unescapeXml(raw)
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
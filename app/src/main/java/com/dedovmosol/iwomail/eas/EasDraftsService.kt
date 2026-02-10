package com.dedovmosol.iwomail.eas

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class DraftAttachmentData(
    val name: String,
    val mimeType: String,
    val data: ByteArray,
    val isInline: Boolean = false,
    val contentId: String? = null
)

private data class DraftEwsDetails(
    val attachments: List<EasAttachment>,
    val body: String
)

/**
 * Сервис для работы с черновиками Exchange (EAS/EWS)
 * Выделен из EasClient для соблюдения принципа SRP (Single Responsibility)
 * 
 * Отвечает за:
 * - Создание, обновление, удаление черновиков
 * - Синхронизацию черновиков
 * - Работу с EWS для разных версий Exchange
 */
class EasDraftsService internal constructor(
    private val deps: DraftsServiceDependencies
) {
    
    interface EasCommandExecutor {
        suspend operator fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T>
    }
    
    /**
     * Зависимости для EasDraftsService
     */
    class DraftsServiceDependencies(
        val executeEasCommand: EasCommandExecutor,
        val folderSync: suspend (String) -> EasResult<FolderSyncResponse>,
        val refreshSyncKey: suspend (String, String) -> EasResult<String>,
        val extractValue: (String, String) -> String?,
        val escapeXml: (String) -> String,
        val getEasVersion: () -> String,
        val isVersionDetected: () -> Boolean,
        val detectEasVersion: suspend () -> EasResult<String>,
        val performNtlmHandshake: suspend (String, String, String) -> String?,
        val executeNtlmRequest: suspend (String, String, String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String, String) -> String?,
        val getEwsUrl: () -> String,
        val getDraftsFolderId: suspend () -> String?
    )
    
    // Кэш ID папки черновиков
    private var cachedDraftsFolderId: String? = null
    
suspend fun createDraft(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    draftsFolderId: String? = null,
    attachments: List<DraftAttachmentData> = emptyList()
): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        val minorVersion = deps.getEasVersion().substringAfter(".").substringBefore(".").toIntOrNull() ?: 0
        
        // Вспомогательная функция: проверка, не был ли черновик уже создан на сервере.
        // КРИТИЧНО: MIME/EWS могут создать черновик на сервере, но вернуть Error
        // (например, Exchange 2007 не включает ItemId в ответ для MIMEContent).
        // Без этой проверки fallback создаёт ДУБЛИКАТ на сервере.
        // FindItem по subject возвращает самый свежий черновик (DateTimeReceived DESC).
        // Совместимо с Exchange 2007 SP1+ (EWS FindItem).
        suspend fun tryRecoverItemId(): String? {
            if (subject.isBlank()) return null
            return try {
                findDraftItemIdBySubject(subject)
            } catch (_: Exception) { null }
        }
        
        // MIME-подход: тело + вложения в одном MIME-сообщении через CreateItem.
        // Это единственный надёжный способ сохранить inline-картинки в черновиках.
        // CreateItem + CreateAttachment (два запроса) имеет known issues с inline в drafts.
        // MIME — одна атомарная операция. Работает на Exchange 2007 SP1+.
        if (attachments.isNotEmpty()) {
            val mimeResult = createDraftMime(to, cc, bcc, subject, body, attachments)
            if (mimeResult is EasResult.Success) {
                return mimeResult
            }
            // MIME мог создать черновик на сервере, но не вернуть ItemId.
            // Проверяем через FindItem ПЕРЕД созданием дубликата fallback-ом.
            tryRecoverItemId()?.let { return EasResult.Success(it) }
        }
        
        if (majorVersion < 14) {
            val ewsResult = createDraftEws(to, cc, bcc, subject, body, attachments)
            if (ewsResult is EasResult.Success) {
                return ewsResult
            }
            // EWS тоже мог создать черновик — проверяем перед local_draft_ fallback.
            tryRecoverItemId()?.let { return EasResult.Success(it) }
            // НЕ используем createDraftEas: он сбрасывает SyncKey на "0"
            // (через refreshSyncKey(folderId, "0")), что вызывает полную ресинхронизацию
            // и может привести к потере pending Adds (новый черновик от MIME/EWS).
            // local_draft_ гарантирует локальную запись; серверный черновик
            // (если MIME/EWS его создали) подтянется при следующем sync.
            return EasResult.Success("local_draft_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
        }
        
        if (majorVersion == 14 && minorVersion >= 1 || majorVersion > 14) {
            val ewsResult = createDraftEws2013(to, cc, bcc, subject, body, attachments)
            if (ewsResult is EasResult.Success) {
                return ewsResult
            }
            // Проверяем, не создался ли черновик на сервере
            tryRecoverItemId()?.let { return EasResult.Success(it) }
            return EasResult.Success("local_draft_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
        }
        
        val ewsResult = createDraftEws(to, cc, bcc, subject, body, attachments)
        if (ewsResult is EasResult.Success) {
            return ewsResult
        }
        
        // Последний шанс: проверяем, не создался ли черновик ранее (MIME/EWS)
        tryRecoverItemId()?.let { return EasResult.Success(it) }
        
        return EasResult.Success("local_draft_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
    }
    
    /**
     * Сохранение черновика (обновляет существующий или создаёт новый)
     */
  suspend fun saveDraft(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    existingDraftId: String? = null
): EasResult<String> {
        // Если есть существующий черновик - обновляем
        if (existingDraftId != null && !existingDraftId.startsWith("local_draft_")) {
            val updateResult = updateDraft(existingDraftId, to, cc, bcc, subject, body)
            if (updateResult is EasResult.Success && updateResult.data) {
                return EasResult.Success(existingDraftId)
            }
            // Если обновление не удалось - создаём новый
        }
        
       return createDraft(to, cc, bcc, subject, body, existingDraftId)
    }
    
    /**
     * Обновление черновика
     */
suspend fun updateDraft(
    serverId: String,
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String
): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        // Локальные черновики нельзя обновить на сервере
        if (serverId.startsWith("local_draft_")) {
            return EasResult.Success(true)
        }
        
     return updateDraftEws(serverId, to, cc, bcc, subject, body)
    }
    /**
 * Загрузка Body черновика через EWS GetItem
 * Исправление бага старой версии - Body не загружался при открытии черновика
 */
suspend fun getDraftBody(serverId: String): EasResult<String> {
    return withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="${resolveEwsVersion()}"/>
    </soap:Header>
    <soap:Body>
        <m:GetItem>
            <m:ItemShape>
                <t:BaseShape>Default</t:BaseShape>
                <t:IncludeMimeContent>false</t:IncludeMimeContent>
                <t:BodyType>HTML</t:BodyType>
            </m:ItemShape>
            <m:ItemIds>
                <t:ItemId Id="$serverId"/>
            </m:ItemIds>
        </m:GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "GetItem")
            
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "GetItem")
                if (ntlmAuth != null) {
                    responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "GetItem")
                }
            }
            
            if (responseXml == null) {
                return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
            }
            
            // Извлекаем Body
            val bodyPattern = "<t:Body[^>]*><!\\[CDATA\\[([^\\]]+)\\]\\]></t:Body>".toRegex()
            val cdataBody = bodyPattern.find(responseXml)?.groupValues?.get(1)
            val body = if (cdataBody != null) {
                cdataBody // CDATA содержит raw HTML, unescapeXml не нужен
            } else {
                // КРИТИЧНО: EWS GetItem возвращает Body как XML text,
                // где HTML-теги закодированы: &lt;html&gt; вместо <html>.
                // Без unescapeXml тело черновика отображается как сырые entities.
                val rawBody = XmlValueExtractor.extractEws(responseXml, "Body") ?: ""
                if (rawBody.isNotEmpty()) unescapeXml(rawBody) else rawBody
            }
            
            EasResult.Success(body)
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка загрузки Body")
        }
    }
}
    /**
     * Удаление черновика через EWS DeleteItem (как в старой версии)
     */
    suspend fun deleteDraft(serverId: String): EasResult<Boolean> {
        // Локальные черновики удаляем без запроса к серверу
        if (serverId.startsWith("local_draft_")) {
            return EasResult.Success(true)
        }
        
        return deleteDraftEws(serverId)
    }

    /**
     * Загружает полный MIME-контент черновика через EWS GetItem (IncludeMimeContent=true).
     * Используется для извлечения inline-картинок когда EAS ItemOperations недоступен
     * (EWS-based serverIds без формата "collectionId:itemId").
     * Работает на Exchange 2007 SP1+.
     */
    suspend fun fetchMimeContentEws(itemId: String): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val ewsVersion = resolveEwsVersion()
            val escapedItemId = deps.escapeXml(itemId)
            
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="$ewsVersion"/>
    </soap:Header>
    <soap:Body>
        <m:GetItem>
            <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:IncludeMimeContent>true</t:IncludeMimeContent>
            </m:ItemShape>
            <m:ItemIds>
                <t:ItemId Id="$escapedItemId"/>
            </m:ItemIds>
        </m:GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "GetItem")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "GetItem")
                if (ntlmAuth != null) {
                    responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "GetItem")
                }
            }
            
            if (responseXml == null) {
                return@withContext EasResult.Error("Не удалось выполнить EWS GetItem")
            }
            
            // КРИТИЧНО: XmlValueExtractor.extractEws использует regex
            // <t:MimeContent>(.*?)</t:MimeContent> — он НЕ обрабатывает атрибуты.
            // EWS ВСЕГДА возвращает <t:MimeContent CharacterSet="UTF-8">base64</t:MimeContent>.
            // Без [^>]* regex не совпадёт → extractEws вернёт null → inline-картинки потеряны.
            // Используем кастомный regex, который учитывает атрибуты.
            val mimePattern = "<(?:t:)?MimeContent[^>]*>(.*?)</(?:t:)?MimeContent>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val mimeBase64 = mimePattern.find(responseXml)?.groupValues?.get(1)?.trim() ?: ""
            if (mimeBase64.isBlank()) {
                return@withContext EasResult.Success("")
            }
            
            // Защита от OOM: EWS GetItem возвращает полный MIME (без TruncationSize).
            // Для черновика с большими файловыми вложениями MIME может быть сотни МБ.
            // Base64 увеличивает размер ~33%, т.е. 20 МБ base64 ≈ 15 МБ MIME.
            // Лимит 25 МБ base64 (≈18 МБ MIME) — аналог TruncationSize=20MB в EAS fetchInlineImages.
            val MAX_MIME_BASE64_LENGTH = 25 * 1024 * 1024 // 25 MB
            if (mimeBase64.length > MAX_MIME_BASE64_LENGTH) {
                android.util.Log.w("EasDraftsService", "fetchMimeContentEws: MIME too large (${mimeBase64.length} chars), skipping")
                return@withContext EasResult.Success("")
            }
            
            val mimeBytes = Base64.decode(mimeBase64, Base64.DEFAULT)
            EasResult.Success(String(mimeBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            EasResult.Error("Ошибка загрузки MIME: ${e.message}")
        }
    }
    
    suspend fun downloadAttachmentEws(attachmentId: String): EasResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val request = buildGetAttachmentRequest(attachmentId, resolveEwsVersion())

            var response = deps.tryBasicAuthEws(ewsUrl, request, "GetAttachment")
            if (response == null) {
                val authHeader = deps.performNtlmHandshake(ewsUrl, request, "GetAttachment")
                    ?: return@withContext EasResult.Error("NTLM handshake failed")
                response = deps.executeNtlmRequest(ewsUrl, request, authHeader, "GetAttachment")
                    ?: return@withContext EasResult.Error("Ошибка выполнения EWS запроса")
            }

            val contentBase64 = XmlValueExtractor.extractEws(response, "Content")
                ?: return@withContext EasResult.Error("Нет данных вложения")
            val bytes = try {
                Base64.decode(contentBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                return@withContext EasResult.Error("Ошибка декодирования вложения")
            }
            EasResult.Success(bytes)
        } catch (e: Exception) {
            EasResult.Error("Ошибка скачивания вложения: ${e.message}")
        }
    }
       
    // === MIME-подход для черновиков с вложениями ===
    // CreateItem с MIMEContent — одна атомарная операция: тело + вложения.
    // Надёжнее чем CreateItem + CreateAttachment (два запроса, known issues с черновиками).
    // Документация: https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/mimecontent
    // Работает на Exchange 2007 SP1+.
    
    private suspend fun createDraftMime(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        attachments: List<DraftAttachmentData>
    ): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val ewsVersion = resolveEwsVersion()
            
            // 1. Собираем MIME-сообщение через JavaMail
            val mimeBytes = buildMimeMessage(to, cc, bcc, subject, body, attachments)
            val mimeBase64 = Base64.encodeToString(mimeBytes, Base64.NO_WRAP)
            
            // 2. SOAP: CreateItem с MIMEContent
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            sb.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"")
            sb.append(" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\"")
            sb.append(" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">")
            sb.append("<soap:Header>")
            sb.append("<t:RequestServerVersion Version=\"$ewsVersion\"/>")
            sb.append("</soap:Header>")
            sb.append("<soap:Body>")
            sb.append("<m:CreateItem MessageDisposition=\"SaveOnly\">")
            sb.append("<m:SavedItemFolderId>")
            sb.append("<t:DistinguishedFolderId Id=\"drafts\"/>")
            sb.append("</m:SavedItemFolderId>")
            sb.append("<m:Items>")
            sb.append("<t:Message>")
            sb.append("<t:MimeContent CharacterSet=\"UTF-8\">")
            sb.append(mimeBase64)
            sb.append("</t:MimeContent>")
            sb.append("</t:Message>")
            sb.append("</m:Items>")
            sb.append("</m:CreateItem>")
            sb.append("</soap:Body>")
            sb.append("</soap:Envelope>")
            val soapRequest = sb.toString()
            
            // 3. Отправляем
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "CreateItem")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth != null) {
                    responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                }
            }
            
            if (responseXml == null) {
                return@withContext EasResult.Error("MIME CreateItem: нет ответа от сервера")
            }
            
            // 4. Извлекаем ItemId
            var itemId = XmlValueExtractor.extractAttribute(responseXml, "ItemId", "Id")
                ?: EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                ?: """<t:ItemId Id="([^"]+)"""".toRegex().find(responseXml)?.groupValues?.get(1)
            
            // 5. Если ItemId не найден, но ответ без ошибки — черновик мог быть создан.
            // Ищем его через FindItem в папке Drafts по теме (Exchange 2007 SP1+).
            val hasError = responseXml.contains("ResponseClass=\"Error\"")
            if (itemId == null && !hasError && subject.isNotBlank()) {
                try {
                    itemId = findDraftItemIdBySubject(subject)
                } catch (_: Exception) {}
            }
            
            if (itemId != null) {
                EasResult.Success(itemId)
            } else {
                val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(responseXml)?.groupValues?.get(1)
                val details = messageText ?: if (hasError) "Server error" else "ItemId not found"
                EasResult.Error("MIME CreateItem: $details")
            }
        } catch (e: Exception) {
            EasResult.Error("MIME CreateItem: ${e.message}")
        }
    }
    
    /**
     * Строит MIME-сообщение (multipart/related для inline, multipart/mixed для файлов).
     * RFC 2387 (multipart/related), RFC 2392 (cid: URLs).
     */
    private fun buildMimeMessage(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        attachments: List<DraftAttachmentData>
    ): ByteArray {
        val props = java.util.Properties()
        val session = javax.mail.Session.getInstance(props)
        val message = javax.mail.internet.MimeMessage(session)
        
        // Заголовки
        if (to.isNotBlank()) {
            message.setRecipients(
                javax.mail.Message.RecipientType.TO,
                javax.mail.internet.InternetAddress.parse(to)
            )
        }
        if (cc.isNotBlank()) {
            message.setRecipients(
                javax.mail.Message.RecipientType.CC,
                javax.mail.internet.InternetAddress.parse(cc)
            )
        }
        if (bcc.isNotBlank()) {
            message.setRecipients(
                javax.mail.Message.RecipientType.BCC,
                javax.mail.internet.InternetAddress.parse(bcc)
            )
        }
        message.setSubject(subject, "UTF-8")
        message.sentDate = java.util.Date()
        
        val inlineAtts = attachments.filter { it.isInline }
        val fileAtts = attachments.filter { !it.isInline }
        
        when {
            inlineAtts.isEmpty() && fileAtts.isEmpty() -> {
                // Нет вложений — простой HTML
                message.setContent(body, "text/html; charset=UTF-8")
            }
            inlineAtts.isNotEmpty() && fileAtts.isEmpty() -> {
                // Только inline — multipart/related
                val related = javax.mail.internet.MimeMultipart("related")
                related.addBodyPart(makeHtmlPart(body))
                inlineAtts.forEach { related.addBodyPart(makeInlinePart(it)) }
                message.setContent(related)
            }
            inlineAtts.isEmpty() && fileAtts.isNotEmpty() -> {
                // Только файлы — multipart/mixed
                val mixed = javax.mail.internet.MimeMultipart("mixed")
                mixed.addBodyPart(makeHtmlPart(body))
                fileAtts.forEach { mixed.addBodyPart(makeFilePart(it)) }
                message.setContent(mixed)
            }
            else -> {
                // Оба: multipart/mixed { multipart/related { html, inline... }, file... }
                val mixed = javax.mail.internet.MimeMultipart("mixed")
                
                val relatedWrapper = javax.mail.internet.MimeBodyPart()
                val related = javax.mail.internet.MimeMultipart("related")
                related.addBodyPart(makeHtmlPart(body))
                inlineAtts.forEach { related.addBodyPart(makeInlinePart(it)) }
                relatedWrapper.setContent(related)
                mixed.addBodyPart(relatedWrapper)
                
                fileAtts.forEach { mixed.addBodyPart(makeFilePart(it)) }
                message.setContent(mixed)
            }
        }
        
        message.saveChanges()
        
        val outputStream = java.io.ByteArrayOutputStream()
        message.writeTo(outputStream)
        return outputStream.toByteArray()
    }
    
    private fun makeHtmlPart(html: String): javax.mail.internet.MimeBodyPart {
        val part = javax.mail.internet.MimeBodyPart()
        part.setContent(html, "text/html; charset=UTF-8")
        return part
    }
    
    private fun makeInlinePart(att: DraftAttachmentData): javax.mail.internet.MimeBodyPart {
        val part = javax.mail.internet.MimeBodyPart()
        val ds = javax.mail.util.ByteArrayDataSource(att.data, att.mimeType)
        part.dataHandler = javax.activation.DataHandler(ds)
        part.fileName = att.name
        part.disposition = javax.mail.internet.MimeBodyPart.INLINE
        // Content-ID в угловых скобках по RFC 2392: <img1_123>
        val cid = att.contentId?.removePrefix("<")?.removeSuffix(">") ?: att.name
        part.setHeader("Content-ID", "<$cid>")
        return part
    }
    
    private fun makeFilePart(att: DraftAttachmentData): javax.mail.internet.MimeBodyPart {
        val part = javax.mail.internet.MimeBodyPart()
        val ds = javax.mail.util.ByteArrayDataSource(att.data, att.mimeType)
        part.dataHandler = javax.activation.DataHandler(ds)
        part.fileName = att.name
        part.disposition = javax.mail.internet.MimeBodyPart.ATTACHMENT
        return part
    }
    
    // === Вспомогательные методы ===
    
    /**
     * Ищет черновик по теме через EWS FindItem в папке Drafts.
     * Используется как fallback, когда CreateItem не вернул ItemId.
     * Возвращает ItemId самого свежего совпадения или null.
     * Совместим с Exchange 2007 SP1+.
     */
    private suspend fun findDraftItemIdBySubject(subject: String): String? {
        val ewsUrl = deps.getEwsUrl()
        val ewsVersion = resolveEwsVersion()
        val escapedSubject = deps.escapeXml(subject)
        
        val findSb = StringBuilder()
        findSb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        findSb.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"")
        findSb.append(" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\"")
        findSb.append(" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">")
        findSb.append("<soap:Header>")
        findSb.append("<t:RequestServerVersion Version=\"$ewsVersion\"/>")
        findSb.append("</soap:Header>")
        findSb.append("<soap:Body>")
        // XSD порядок: ItemShape → Restriction → SortOrder → ParentFolderIds
        findSb.append("<m:FindItem Traversal=\"Shallow\">")
        findSb.append("<m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>")
        findSb.append("<m:Restriction>")
        findSb.append("<t:IsEqualTo>")
        findSb.append("<t:FieldURI FieldURI=\"item:Subject\"/>")
        findSb.append("<t:FieldURIOrConstant><t:Constant Value=\"$escapedSubject\"/></t:FieldURIOrConstant>")
        findSb.append("</t:IsEqualTo>")
        findSb.append("</m:Restriction>")
        findSb.append("<m:SortOrder>")
        findSb.append("<t:FieldOrder Order=\"Descending\">")
        findSb.append("<t:FieldURI FieldURI=\"item:DateTimeReceived\"/>")
        findSb.append("</t:FieldOrder>")
        findSb.append("</m:SortOrder>")
        findSb.append("<m:ParentFolderIds>")
        findSb.append("<t:DistinguishedFolderId Id=\"drafts\"/>")
        findSb.append("</m:ParentFolderIds>")
        findSb.append("</m:FindItem>")
        findSb.append("</soap:Body>")
        findSb.append("</soap:Envelope>")
        val findRequest = findSb.toString()
        
        var findResponse = deps.tryBasicAuthEws(ewsUrl, findRequest, "FindItem")
        if (findResponse == null) {
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
            if (ntlmAuth != null) {
                findResponse = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
            }
        }
        
        if (findResponse == null) return null
        
        // Извлекаем первый (самый свежий) ItemId
        return XmlValueExtractor.extractAttribute(findResponse, "ItemId", "Id")
            ?: EasPatterns.EWS_ITEM_ID.find(findResponse)?.groupValues?.get(1)
            ?: """<t:ItemId Id="([^"]+)"""".toRegex().find(findResponse)?.groupValues?.get(1)
    }
    
    /**
     * Возвращает ВСЕ ItemId черновиков с данным subject.
     * Используется при пересохранении: удаляем все старые копии кроме нового.
     * Совместим с Exchange 2007 SP1+ (EWS FindItem).
     */
    suspend fun findAllDraftItemIdsBySubject(subject: String): EasResult<List<String>> {
        val ewsUrl = deps.getEwsUrl()
        val ewsVersion = resolveEwsVersion()
        val escapedSubject = deps.escapeXml(subject)
        
        val findSb = StringBuilder()
        findSb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        findSb.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"")
        findSb.append(" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\"")
        findSb.append(" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">")
        findSb.append("<soap:Header>")
        findSb.append("<t:RequestServerVersion Version=\"$ewsVersion\"/>")
        findSb.append("</soap:Header>")
        findSb.append("<soap:Body>")
        findSb.append("<m:FindItem Traversal=\"Shallow\">")
        findSb.append("<m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>")
        findSb.append("<m:Restriction>")
        findSb.append("<t:IsEqualTo>")
        findSb.append("<t:FieldURI FieldURI=\"item:Subject\"/>")
        findSb.append("<t:FieldURIOrConstant><t:Constant Value=\"$escapedSubject\"/></t:FieldURIOrConstant>")
        findSb.append("</t:IsEqualTo>")
        findSb.append("</m:Restriction>")
        findSb.append("<m:SortOrder>")
        findSb.append("<t:FieldOrder Order=\"Descending\">")
        findSb.append("<t:FieldURI FieldURI=\"item:DateTimeReceived\"/>")
        findSb.append("</t:FieldOrder>")
        findSb.append("</m:SortOrder>")
        findSb.append("<m:ParentFolderIds>")
        findSb.append("<t:DistinguishedFolderId Id=\"drafts\"/>")
        findSb.append("</m:ParentFolderIds>")
        findSb.append("</m:FindItem>")
        findSb.append("</soap:Body>")
        findSb.append("</soap:Envelope>")
        val findRequest = findSb.toString()
        
        var findResponse = deps.tryBasicAuthEws(ewsUrl, findRequest, "FindItem")
        if (findResponse == null) {
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
            if (ntlmAuth != null) {
                findResponse = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
            }
        }
        
        if (findResponse == null) return EasResult.Error("EWS FindItem failed")
        
        // Извлекаем ВСЕ ItemId из ответа
        val itemIdPattern = """<t:ItemId\s+Id="([^"]+)"""".toRegex()
        val allIds = itemIdPattern.findAll(findResponse).map { it.groupValues[1] }.toList()
        
        // Fallback: попробуем другой формат (без namespace prefix)
        if (allIds.isEmpty()) {
            val altPattern = """ItemId\s+Id="([^"]+)"""".toRegex()
            val altIds = altPattern.findAll(findResponse).map { it.groupValues[1] }.toList()
            return EasResult.Success(altIds)
        }
        
        return EasResult.Success(allIds)
    }
    
    private suspend fun getDraftsFolderId(): String? {
        if (cachedDraftsFolderId != null) {
            return cachedDraftsFolderId
        }
        
        val folderId = deps.getDraftsFolderId()
        cachedDraftsFolderId = folderId
        return folderId
    }
    
    // === EWS методы ===
    
private suspend fun createDraftEws(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    attachments: List<DraftAttachmentData> = emptyList()
): EasResult<String> {
    return withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()

            val escapedSubject = deps.escapeXml(subject)
            val escapedBody = deps.escapeXml(body)

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

            val ewsVersion = resolveEwsVersion()
            
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="$ewsVersion"/>
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
</soap:Envelope>"""

            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "CreateItem")

            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth != null) {
                    responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                }
            }

            if (responseXml == null) {
                return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
            }

            val itemId = XmlValueExtractor.extractAttribute(responseXml, "ItemId", "Id")
                ?: EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                ?: run {
                    // Дополнительные попытки извлечь ItemId
                    val patterns = listOf(
                        """<t:ItemId Id="([^"]+)"""".toRegex(),
                        """<m:ItemId Id="([^"]+)"""".toRegex(),
                        """<ItemId Id="([^"]+)"""".toRegex()
                    )
                    patterns.firstNotNullOfOrNull { it.find(responseXml)?.groupValues?.get(1) }
                }

            if (itemId != null) {
                if (attachments.isNotEmpty()) {
                    try {
                        val changeKey = """ChangeKey="([^"]+)"""".toRegex()
                            .find(responseXml)?.groupValues?.get(1)
                        attachFilesEws(ewsUrl, itemId, changeKey, attachments, ewsVersion)
                    } catch (_: Exception) {}
                }
                EasResult.Success(itemId)
            } else {
                val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(responseXml)?.groupValues?.get(1)
                val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)
                val error = messageText ?: responseCode ?: "ItemId not found in response"
                EasResult.Error("Не удалось создать черновик: $error")
            }
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка создания черновика")
        }
    }
}
    
    private suspend fun createDraftEws2013(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    attachments: List<DraftAttachmentData> = emptyList()
): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val ewsVersion = resolveEwsVersion()
                val request = buildCreateDraftRequest(to, cc, bcc, subject, body, ewsVersion)
                
                val authHeader = deps.performNtlmHandshake(ewsUrl, request, "CreateItem")
                    ?: return@withContext EasResult.Error("NTLM handshake failed")
                
                val response = deps.executeNtlmRequest(ewsUrl, request, authHeader, "CreateItem")
                    ?: return@withContext EasResult.Error("Ошибка выполнения EWS запроса")
                
                val itemId = XmlValueExtractor.extractAttribute(response, "ItemId", "Id")
                    ?: EasPatterns.EWS_ITEM_ID.find(response)?.groupValues?.get(1)

                if (itemId != null) {
                    if (attachments.isNotEmpty()) {
                        try {
                            val changeKey = """ChangeKey="([^"]+)"""".toRegex()
                                .find(response)?.groupValues?.get(1)
                            attachFilesEws(ewsUrl, itemId, changeKey, attachments, ewsVersion)
                        } catch (_: Exception) {}
                    }
                    EasResult.Success(itemId)
                } else {
                    val hasSuccess = response.contains("ResponseClass=\"Success\"")
                    val hasNoError = response.contains("<ResponseCode>NoError</ResponseCode>") ||
                                    response.contains("<m:ResponseCode>NoError</m:ResponseCode>")
                    if (hasSuccess && hasNoError) {
                        // P2 FIX: Сервер вернул Success, но ItemId не найден в ответе.
                        // Черновик создан — ищем его по subject через FindItem.
                        // Аналогично fallback в createDraftMime().
                        val foundItemId = if (subject.isNotBlank()) {
                            try { findDraftItemIdBySubject(subject) } catch (_: Exception) { null }
                        } else null
                        
                        if (foundItemId != null) {
                            EasResult.Success(foundItemId)
                        } else {
                            // Последний fallback: UUID. Черновик есть на сервере, но ID не удалось получить.
                            // EWS→EAS миграция при следующем sync разрешит корректный ServerId.
                            EasResult.Success("local_draft_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
                        }
                    } else {
                        EasResult.Error("Не удалось создать черновик")
                    }
                }
            } catch (e: Exception) {
                EasResult.Error("Ошибка создания черновика: ${e.message}")
            }
        }
    }
    
private suspend fun createDraftEas(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    draftsFolderId: String
): EasResult<String> {
        // Получаем SyncKey
        // ИСПРАВЛЕНО: порядок аргументов - (folderId, syncKey)
        val syncKeyResult = deps.refreshSyncKey(draftsFolderId, "0")
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return EasResult.Error(syncKeyResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        val clientId = UUID.randomUUID().toString()
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        
        // Форматируем получателей в формате EAS (с кавычками и угловыми скобками)
        val toFormatted = to.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ") { email ->
                if (email.contains("<")) email else "\"$email\" <$email>"
            }
        
        val ccFormatted = cc.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ") { email ->
                if (email.contains("<")) email else "\"$email\" <$email>"
            }
        
        val bccFormatted = bcc.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ") { email ->
                if (email.contains("<")) email else "\"$email\" <$email>"
            }
        
        // ВАЖНО: namespace с двоеточием - Email:, AirSyncBase:, Email2:
        val createXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:email="Email:" xmlns:airsyncbase="AirSyncBase:" xmlns:email2="Email2:">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$draftsFolderId</CollectionId>
            <GetChanges>0</GetChanges>
            <Commands>
                <Add>
                    <ClientId>$clientId</ClientId>
                    <ApplicationData>
                        ${if (toFormatted.isNotBlank()) "<email:To>$toFormatted</email:To>" else ""}
                        ${if (ccFormatted.isNotBlank()) "<email:CC>$ccFormatted</email:CC>" else ""}
                        ${if (bccFormatted.isNotBlank()) "<email2:Bcc>$bccFormatted</email2:Bcc>" else ""}
                        <email:Subject>$escapedSubject</email:Subject>
                        <airsyncbase:Body>
                            <airsyncbase:Type>2</airsyncbase:Type>
                            <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                        </airsyncbase:Body>
                        <email:Importance>1</email:Importance>
                        <email:Read>0</email:Read>
                        <email:Flag/>
                    </ApplicationData>
                </Add>
            </Commands>
        </Collection>
                </Collections>
            </Sync>
        """.trimIndent()
        
        return deps.executeEasCommand("Sync", createXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status != "1") {
                throw Exception("Sync Add failed with status: $status")
            }
            // Извлекаем ServerId созданного черновика
            val serverIdPattern = "<ServerId>([^<]+)</ServerId>".toRegex()
            val responsesSection = responseXml.substringAfter("<Responses>", "").substringBefore("</Responses>")
            serverIdPattern.find(responsesSection)?.groupValues?.get(1) ?: clientId
        }
    }
    
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
            val ewsUrl = deps.getEwsUrl()

            // КРИТИЧНО: Получаем ChangeKey через GetItem перед UpdateItem
            val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <GetItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
            <ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
            </ItemShape>
            <ItemIds>
                <t:ItemId Id="${deps.escapeXml(serverId)}"/>
            </ItemIds>
        </GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            var getItemResponse = deps.tryBasicAuthEws(ewsUrl, getItemRequest, "GetItem")
            if (getItemResponse == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, getItemRequest, "GetItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                getItemResponse = deps.executeNtlmRequest(ewsUrl, getItemRequest, ntlmAuth, "GetItem")
                    ?: return@withContext EasResult.Error("Не удалось получить ChangeKey")
            }
            
            // Извлекаем ChangeKey из ответа
            val changeKeyPattern = """<t:ItemId Id="[^"]+" ChangeKey="([^"]+)"""".toRegex()
            val changeKeyMatch = changeKeyPattern.find(getItemResponse)
            val changeKey = changeKeyMatch?.groupValues?.get(1) ?: ""

            val escapedServerId = deps.escapeXml(serverId)
            val escapedChangeKey = deps.escapeXml(changeKey)
            val escapedSubject = deps.escapeXml(subject)
            val escapedBody = deps.escapeXml(body)

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

            val changeKeyAttr = if (changeKey.isNotEmpty()) """ ChangeKey="$escapedChangeKey"""" else ""
            val exchangeVersion = resolveEwsVersion()
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="$exchangeVersion"/>
    </soap:Header>
    <soap:Body>
        <m:UpdateItem MessageDisposition="SaveOnly" ConflictResolution="AlwaysOverwrite">
            <m:ItemChanges>
                <t:ItemChange>
                    <t:ItemId Id="$escapedServerId"$changeKeyAttr/>
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
                        <t:SetItemField>
                            <t:FieldURI FieldURI="message:ToRecipients"/>
                            <t:Message>
                                <t:ToRecipients>${if (toRecipients.isNotBlank()) toRecipients else ""}</t:ToRecipients>
                            </t:Message>
                        </t:SetItemField>
                        <t:SetItemField>
                            <t:FieldURI FieldURI="message:CcRecipients"/>
                            <t:Message>
                                <t:CcRecipients>${if (ccRecipients.isNotBlank()) ccRecipients else ""}</t:CcRecipients>
                            </t:Message>
                        </t:SetItemField>
                    </t:Updates>
                </t:ItemChange>
            </m:ItemChanges>
        </m:UpdateItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "UpdateItem")

            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "UpdateItem")
                if (ntlmAuth != null) {
                    responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "UpdateItem")
                }
            }

            if (responseXml == null) {
                return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
            }

            // КРИТИЧНО: Проверяем ResponseClass И ResponseCode
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            
            if (hasSuccess && hasNoError) {
                EasResult.Success(true)
            } else {
                val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(responseXml)?.groupValues?.get(1)
                val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)
                val error = messageText ?: responseCode ?: "Unknown error"
                EasResult.Error("Не удалось обновить черновик: $error")
            }
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка обновления черновика")
        }
    }
}

    private suspend fun deleteDraftEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            val maxRetries = 3
            var lastError: String? = null
            
            for (attempt in 1..maxRetries) {
                try {
                    val ewsUrl = deps.getEwsUrl()
                    val soapRequest = """
                        <?xml version="1.0" encoding="utf-8"?>
                        <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                       xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                                       xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                            <soap:Header>
                                <t:RequestServerVersion Version="${resolveEwsVersion()}"/>
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

                    var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "DeleteItem")

                    if (responseXml == null) {
                        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                        if (ntlmAuth != null) {
                            responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                        }
                    }

                    if (responseXml == null) {
                        lastError = "Не удалось выполнить запрос к EWS"
                        if (attempt < maxRetries) {
                            kotlinx.coroutines.delay(1000L * attempt)
                            continue
                        }
                        return@withContext EasResult.Error(lastError)
                    }

                    if (responseXml.contains("NoError") || responseXml.contains("ResponseClass=\"Success\"")) {
                        return@withContext EasResult.Success(true)
                    } else {
                        val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(responseXml)?.groupValues?.get(1)
                        val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)
                        lastError = messageText ?: responseCode ?: "Unknown error"
                        
                        // Retry на временные ошибки сервера
                        if (attempt < maxRetries) {
                            kotlinx.coroutines.delay(1000L * attempt)
                            continue
                        }
                        return@withContext EasResult.Error("Не удалось удалить черновик: $lastError")
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Ошибка удаления черновика"
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(1000L * attempt)
                        continue
                    }
                    return@withContext EasResult.Error(lastError)
                }
            }
            
            EasResult.Error(lastError ?: "Не удалось удалить черновик")
        }
    }
    
    /**
     * Загружает вложения на сервер по ОДНОМУ через EWS CreateAttachment.
     * По одному — для надёжности: меньше размер запроса, точная диагностика ошибок,
     * каждый последующий запрос использует актуальный ChangeKey.
     */
    private suspend fun attachFilesEws(
        ewsUrl: String,
        itemId: String,
        changeKey: String?,
        attachments: List<DraftAttachmentData>,
        exchangeVersion: String
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext EasResult.Success(true)

        var currentChangeKey = changeKey
        
        for ((index, att) in attachments.withIndex()) {
            val request = buildCreateAttachmentRequest(
                itemId, currentChangeKey, listOf(att), exchangeVersion
            )

            var response = deps.tryBasicAuthEws(ewsUrl, request, "CreateAttachment")
            if (response == null) {
                val authHeader = deps.performNtlmHandshake(ewsUrl, request, "CreateAttachment")
                    ?: return@withContext EasResult.Error(
                        "NTLM handshake failed для вложения ${index + 1}/${attachments.size} (${att.name})"
                    )
                response = deps.executeNtlmRequest(ewsUrl, request, authHeader, "CreateAttachment")
                    ?: return@withContext EasResult.Error(
                        "Ошибка EWS запроса для вложения ${index + 1}/${attachments.size} (${att.name})"
                    )
            }

            // Проверяем ответ — ищем ошибку (более надёжно, чем искать Success)
            val hasError = response.contains("ResponseClass=\"Error\"")
            if (hasError) {
                val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(response)?.groupValues?.get(1)
                val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)
                val details = messageText ?: responseCode ?: "Unknown error"
                android.util.Log.e("EasDraftsService", 
                    "CreateAttachment FAILED [${index + 1}/${attachments.size}] " +
                    "${att.name} (inline=${att.isInline}, cid=${att.contentId}): $details")
                return@withContext EasResult.Error(
                    "Вложение '${att.name}' не загружено: $details"
                )
            }
            
            // Не содержит Success — тоже ошибка
            if (!response.contains("ResponseClass=\"Success\"")) {
                android.util.Log.e("EasDraftsService",
                    "CreateAttachment NO SUCCESS [${index + 1}/${attachments.size}] " +
                    "${att.name}: response=${response.take(500)}")
                return@withContext EasResult.Error(
                    "Нет подтверждения загрузки вложения '${att.name}'"
                )
            }
            
            // Обновляем ChangeKey из ответа — каждый CreateAttachment меняет ChangeKey элемента
            val newChangeKey = """ChangeKey="([^"]+)"""".toRegex()
                .find(response)?.groupValues?.get(1)
            if (newChangeKey != null) {
                currentChangeKey = newChangeKey
            }
            
            android.util.Log.w("EasDraftsService", 
                "CreateAttachment OK [${index + 1}/${attachments.size}] " +
                "${att.name} (inline=${att.isInline}, cid=${att.contentId})")
        }

        EasResult.Success(true)
    }

    // === Построение запросов ===
    
    private fun buildCreateAttachmentRequest(
        itemId: String,
        changeKey: String?,
        attachments: List<DraftAttachmentData>,
        exchangeVersion: String
    ): String {
        val escapedItemId = deps.escapeXml(itemId)
        val changeKeyAttr = changeKey?.let { " ChangeKey=\"${deps.escapeXml(it)}\"" } ?: ""
        // Exchange 2007 (Exchange2007_SP1) НЕ поддерживает <t:IsInline>.
        // Только Exchange 2010+ поддерживает IsInline.
        // ContentId поддерживается во всех версиях (по документации Microsoft).
        val supportsIsInline = !exchangeVersion.contains("2007")
        
        // Строим XML без trimIndent — чистый XML без артефактов пробелов.
        // Порядок элементов СТРОГО по XSD схеме Microsoft:
        // Name → ContentType → ContentId → IsInline → Content
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"")
        sb.append(" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\"")
        sb.append(" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">")
        sb.append("<soap:Header>")
        sb.append("<t:RequestServerVersion Version=\"$exchangeVersion\"/>")
        sb.append("</soap:Header>")
        sb.append("<soap:Body>")
        sb.append("<m:CreateAttachment>")
        sb.append("<m:ParentItemId Id=\"$escapedItemId\"$changeKeyAttr/>")
        sb.append("<m:Attachments>")
        
        for (att in attachments) {
            val name = deps.escapeXml(att.name)
            val contentType = deps.escapeXml(att.mimeType)
            val content = Base64.encodeToString(att.data, Base64.NO_WRAP)
            
            sb.append("<t:FileAttachment>")
            sb.append("<t:Name>$name</t:Name>")
            sb.append("<t:ContentType>$contentType</t:ContentType>")
            
            // ContentId — для inline-картинок (cid: ссылки в HTML). Работает на Exchange 2007+.
            if (att.isInline || !att.contentId.isNullOrBlank()) {
                att.contentId?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
                    val cleaned = raw.removePrefix("<").removeSuffix(">")
                    val withoutCid = if (cleaned.startsWith("cid:", ignoreCase = true)) cleaned.substring(4) else cleaned
                    sb.append("<t:ContentId>${deps.escapeXml(withoutCid)}</t:ContentId>")
                }
            }
            
            // IsInline — только для Exchange 2010+ (после ContentId по XSD схеме)
            if (supportsIsInline && att.isInline) {
                sb.append("<t:IsInline>true</t:IsInline>")
            }
            
            sb.append("<t:Content>$content</t:Content>")
            sb.append("</t:FileAttachment>")
        }
        
        sb.append("</m:Attachments>")
        sb.append("</m:CreateAttachment>")
        sb.append("</soap:Body>")
        sb.append("</soap:Envelope>")
        
        return sb.toString()
    }

    private fun buildGetAttachmentRequest(attachmentId: String, exchangeVersion: String): String {
        val escapedAttachmentId = deps.escapeXml(attachmentId)
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                           xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                <soap:Header>
                    <t:RequestServerVersion Version="$exchangeVersion"/>
                </soap:Header>
                <soap:Body>
                    <m:GetAttachment>
                        <m:AttachmentIds>
                            <t:AttachmentId Id="$escapedAttachmentId"/>
                        </m:AttachmentIds>
                    </m:GetAttachment>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
    }
    
    private fun buildCreateDraftRequest(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        exchangeVersion: String
    ): String {
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        
        val toRecipients = formatRecipients(to)
        val ccRecipients = formatRecipients(cc)
        val bccRecipients = formatRecipients(bcc)
        
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
                           xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
                <soap:Header>
                    <t:RequestServerVersion Version="$exchangeVersion"/>
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
    }

    /**
     * Определяет EWS RequestServerVersion на основе EAS версии.
     * 
     * КРИТИЧНО: Exchange 2010 SP1/SP2/SP3 и Exchange 2013 оба возвращают EAS 14.1,
     * но Exchange 2010 НЕ поддерживает schema "Exchange2013"!
     * Отправка "Exchange2013" на Exchange 2010 SP1 вызывает ErrorInvalidServerVersion.
     * 
     * Безопасная стратегия:
     * - EAS < 14 → Exchange2007_SP1 (Exchange 2007)
     * - EAS 14.0 → Exchange2010 (Exchange 2010 RTM)
     * - EAS 14.1 → Exchange2010_SP1 (безопасно для Exchange 2010 SP1+ И Exchange 2013)
     * - EAS 16.x → Exchange2013 (Exchange 2016/2019 — точно поддерживают Exchange2013 schema)
     * 
     * "Exchange2010_SP1" как default для 14.1 — обратно совместим с Exchange 2013,
     * т.к. Exchange 2013 поддерживает ВСЕ предыдущие schema-версии.
     */
    private fun resolveEwsVersion(): String {
        val major = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        val minor = deps.getEasVersion().substringAfter(".").substringBefore(".").toIntOrNull() ?: 0

        return when {
            major < 14 -> "Exchange2007_SP1"
            major == 14 && minor >= 1 -> "Exchange2010_SP1"  // Безопасно для Exchange 2010 SP1+ и 2013
            major >= 16 -> "Exchange2013"  // Только для 2016/2019 где точно поддерживается
            else -> "Exchange2010"  // Exchange 2010 RTM (EAS 14.0)
        }
    }
    
    private fun buildFindDraftsRequest(exchangeVersion: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                <soap:Header>
                    <t:RequestServerVersion Version="$exchangeVersion"/>
                </soap:Header>
                <soap:Body>
                    <FindItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                              Traversal="Shallow">
                        <ItemShape>
                            <t:BaseShape>AllProperties</t:BaseShape>
                        </ItemShape>
                        <ParentFolderIds>
                            <t:DistinguishedFolderId Id="drafts"/>
                        </ParentFolderIds>
                    </FindItem>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
    }
    
    private fun buildFindDraftsRequest2013(): String {
        val ewsVersion = resolveEwsVersion()
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                           xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
                <soap:Header>
                    <t:RequestServerVersion Version="$ewsVersion"/>
                </soap:Header>
                <soap:Body>
                    <FindItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages"
                              Traversal="Shallow">
                        <ItemShape>
                            <t:BaseShape>AllProperties</t:BaseShape>
                        </ItemShape>
                        <ParentFolderIds>
                            <t:DistinguishedFolderId Id="drafts"/>
                        </ParentFolderIds>
                    </FindItem>
                </soap:Body>
            </soap:Envelope>
        """.trimIndent()
    }
    
    private fun formatRecipients(recipients: String): String {
        return recipients.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("") { email ->
                """<t:Mailbox><t:EmailAddress>$email</t:EmailAddress></t:Mailbox>"""
            }
    }
    
    private fun parseDraftsResponse(xml: String): List<EasDraft> {
    val drafts = mutableListOf<EasDraft>()

    val messagePattern = "<t:Message>(.*?)</t:Message>".toRegex(RegexOption.DOT_MATCHES_ALL)
    messagePattern.findAll(xml).forEach { match ->
        val messageXml = match.groupValues[1]

        val itemIdMatch = "Id=\"([^\"]+)\"".toRegex().find(messageXml)
        val itemId = itemIdMatch?.groupValues?.get(1) ?: return@forEach

        val changeKeyMatch = "ChangeKey=\"([^\"]+)\"".toRegex().find(messageXml)
        val changeKey = changeKeyMatch?.groupValues?.get(1) ?: ""

        val subjectMatch = "<t:Subject>(.*?)</t:Subject>".toRegex(RegexOption.DOT_MATCHES_ALL).find(messageXml)
        val subject = subjectMatch?.groupValues?.get(1)?.let { unescapeXml(it) } ?: ""

        val dateMatch = "<t:DateTimeCreated>(.*?)</t:DateTimeCreated>".toRegex().find(messageXml)
        val dateStr = dateMatch?.groupValues?.get(1) ?: ""

        val toRecipients = mutableListOf<String>()
        val toPattern = "<t:ToRecipients>(.*?)</t:ToRecipients>".toRegex(RegexOption.DOT_MATCHES_ALL)
        toPattern.find(messageXml)?.let { toMatch ->
            val emailPattern = "<t:EmailAddress>(.*?)</t:EmailAddress>".toRegex()
            emailPattern.findAll(toMatch.groupValues[1]).forEach { emailMatch ->
                toRecipients.add(emailMatch.groupValues[1])
            }
        }

        val ccRecipients = mutableListOf<String>()
        val ccPattern = "<t:CcRecipients>(.*?)</t:CcRecipients>".toRegex(RegexOption.DOT_MATCHES_ALL)
        ccPattern.find(messageXml)?.let { ccMatch ->
            val emailPattern = "<t:EmailAddress>(.*?)</t:EmailAddress>".toRegex()
            emailPattern.findAll(ccMatch.groupValues[1]).forEach { emailMatch ->
                ccRecipients.add(emailMatch.groupValues[1])
            }
        }

        val hasAttachmentsMatch = "<t:HasAttachments>(.*?)</t:HasAttachments>".toRegex().find(messageXml)
        val hasAttachments = hasAttachmentsMatch?.groupValues?.get(1) == "true"

        drafts.add(
            EasDraft(
                serverId = itemId,
                changeKey = changeKey,
                subject = subject,
                to = toRecipients.joinToString(", "),
                cc = ccRecipients.joinToString(", "),
                dateCreated = dateStr,
                hasAttachments = hasAttachments
            )
        )
    }

    return drafts
}

    private suspend fun fillDraftAttachmentsEws(
        ewsUrl: String,
        drafts: List<EasDraft>,
        exchangeVersion: String
    ): List<EasDraft> {
        val needDetails = drafts.filter { (it.hasAttachments && it.attachments.isEmpty()) || it.body.isBlank() }
        if (needDetails.isEmpty()) return drafts

        val detailsMap = getDraftAttachmentsEws(
            ewsUrl,
            needDetails.map { it.serverId },
            exchangeVersion
        )
        if (detailsMap.isEmpty()) return drafts

        return drafts.map { draft ->
            val details = detailsMap[draft.serverId]
            if (details != null && (details.attachments.isNotEmpty() || details.body.isNotBlank())) {
                draft.copy(
                    attachments = if (details.attachments.isNotEmpty()) details.attachments else draft.attachments,
                    hasAttachments = draft.hasAttachments || details.attachments.isNotEmpty(),
                    body = if (details.body.isNotBlank()) details.body else draft.body
                )
            } else {
                draft
            }
        }
    }

    private suspend fun getDraftAttachmentsEws(
        ewsUrl: String,
        itemIds: List<String>,
        exchangeVersion: String
    ): Map<String, DraftEwsDetails> {
        if (itemIds.isEmpty()) return emptyMap()

        val itemIdsXml = itemIds.joinToString("") { """<t:ItemId Id="${deps.escapeXml(it)}"/>""" }
        val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="$exchangeVersion"/>
    </soap:Header>
    <soap:Body>
        <m:GetItem>
            <m:ItemShape>
                <t:BaseShape>AllProperties</t:BaseShape>
                <t:IncludeMimeContent>false</t:IncludeMimeContent>
                <t:BodyType>HTML</t:BodyType>
            </m:ItemShape>
            <m:ItemIds>
                $itemIdsXml
            </m:ItemIds>
        </m:GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        val authHeader = deps.performNtlmHandshake(ewsUrl, getItemRequest, "GetItem") ?: return emptyMap()
        val response = deps.executeNtlmRequest(ewsUrl, getItemRequest, authHeader, "GetItem") ?: return emptyMap()

        val result = mutableMapOf<String, DraftEwsDetails>()
        val itemPattern = "<t:Message>(.*?)</t:Message>".toRegex(RegexOption.DOT_MATCHES_ALL)
        itemPattern.findAll(response).forEach { match ->
            val itemXml = match.groupValues[1]
            val itemId = XmlValueExtractor.extractAttribute(itemXml, "ItemId", "Id") ?: return@forEach
            val attachments = parseEwsAttachments(itemXml)
            val body = EasPatterns.EWS_BODY.find(itemXml)?.groupValues?.get(1)?.trim()?.let { unescapeXml(it) }.orEmpty()
            if (attachments.isNotEmpty() || body.isNotBlank()) {
                result[itemId] = DraftEwsDetails(attachments = attachments, body = body)
            }
        }
        return result
    }
    
    private fun parseRecipients(xml: String): String {
        val emailPattern = """<t:EmailAddress>(.*?)</t:EmailAddress>""".toRegex()
        val seen = linkedSetOf<String>()
        emailPattern.findAll(xml).forEach { match ->
            val raw = unescapeXml(match.groupValues[1].trim())
            val cleaned = extractEmailOnly(raw)
            if (cleaned.isNotBlank()) {
                seen.add(cleaned)
            }
        }
        return seen.joinToString(", ")
    }

    private fun extractEmailOnly(value: String): String {
        val bracketMatch = """<([^>]+)>""".toRegex().find(value)?.groupValues?.get(1)
        val emailCandidate = (bracketMatch ?: value).replace("\"", "").trim()
        val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        return emailRegex.find(emailCandidate)?.value ?: emailCandidate
    }

    private fun parseEwsAttachments(itemXml: String): List<EasAttachment> {
        val attachments = mutableListOf<EasAttachment>()
        val attachmentsXml = "<(?:t:)?Attachments>(.*?)</(?:t:)?Attachments>".toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(itemXml)?.groupValues?.get(1)
            ?: return attachments

        val fileAttachmentPattern = "<(?:t:)?FileAttachment>(.*?)</(?:t:)?FileAttachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        fileAttachmentPattern.findAll(attachmentsXml).forEach { match ->
            val attXml = match.groupValues[1]
            val attachmentId = """<(?:t:)?AttachmentId[^>]*Id="([^"]+)"""".toRegex()
                .find(attXml)?.groupValues?.get(1) ?: ""
            val displayName = XmlValueExtractor.extractEws(attXml, "Name")
                ?: XmlValueExtractor.extractEws(attXml, "DisplayName")
                ?: "attachment"
            val contentType = XmlValueExtractor.extractEws(attXml, "ContentType") ?: "application/octet-stream"
            val estimatedSize = XmlValueExtractor.extractEws(attXml, "Size")
                ?.toLongOrNull()
                ?: XmlValueExtractor.extractEws(attXml, "EstimatedSize")?.toLongOrNull()
                ?: 0
            val isInline = XmlValueExtractor.extractEws(attXml, "IsInline")
                ?.let { it == "true" || it == "1" } ?: false
            val contentId = XmlValueExtractor.extractEws(attXml, "ContentId")?.trim()?.let { raw ->
                val cleaned = raw.removePrefix("<").removeSuffix(">")
                if (cleaned.startsWith("cid:", ignoreCase = true)) cleaned.substring(4) else cleaned
            }

            attachments.add(
                EasAttachment(
                    fileReference = attachmentId,
                    displayName = displayName,
                    contentType = contentType,
                    estimatedSize = estimatedSize,
                    isInline = isInline,
                    contentId = contentId
                )
            )
        }

        return attachments
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
}

// EasDraft определён в EasClient.kt

package com.dedovmosol.iwomail.eas

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DraftAttachmentData(
    val name: String,
    val mimeType: String,
    val data: ByteArray,
    val isInline: Boolean = false,
    val contentId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DraftAttachmentData) return false
        return name == other.name && mimeType == other.mimeType &&
                data.contentEquals(other.data) && isInline == other.isInline &&
                contentId == other.contentId
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + isInline.hashCode()
        result = 31 * result + (contentId?.hashCode() ?: 0)
        return result
    }
}

private data class DraftEwsDetails(
    val attachments: List<EasAttachment>,
    val body: String,
    val to: String = "",
    val cc: String = "",
    val bcc: String = ""
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

    companion object {
        private val MESSAGE_PATTERN = "<t:Message>(.*?)</t:Message>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ITEM_PATTERN = "<t:Item>(.*?)</t:Item>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val SUBJECT_PATTERN = "<t:Subject>(.*?)</t:Subject>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val DATE_TIME_CREATED_PATTERN = "<t:DateTimeCreated>(.*?)</t:DateTimeCreated>".toRegex()
        private val TO_RECIPIENTS_PATTERN = "<t:ToRecipients>(.*?)</t:ToRecipients>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val CC_RECIPIENTS_PATTERN = "<t:CcRecipients>(.*?)</t:CcRecipients>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BCC_RECIPIENTS_PATTERN = "<t:BccRecipients>(.*?)</t:BccRecipients>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EMAIL_ADDRESS_PATTERN = "<t:EmailAddress>(.*?)</t:EmailAddress>".toRegex()
        private val HAS_ATTACHMENTS_PATTERN = "<t:HasAttachments>(.*?)</t:HasAttachments>".toRegex()
        private val ATTACHMENTS_PATTERN = "<(?:t:)?Attachments>(.*?)</(?:t:)?Attachments>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val FILE_ATTACHMENT_PATTERN = "<(?:t:)?FileAttachment>(.*?)</(?:t:)?FileAttachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ATTACHMENT_ID_PATTERN = """<(?:t:)?AttachmentId[^>]*Id="([^"]+)"""".toRegex()
        private val ROOT_ITEM_CHANGE_KEY_REGEX = """RootItemChangeKey="([^"]+)"""".toRegex()
        private val CHANGE_KEY_REGEX = """ChangeKey="([^"]+)"""".toRegex()
        private val BRACKET_EMAIL_REGEX = """<([^>]+)>""".toRegex()
        private val BODY_PATTERN = "<(?:t:)?Body[^>]*>(.*?)</(?:t:)?Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val MIME_CONTENT_PATTERN = "<(?:t:)?MimeContent[^>]*>(.*?)</(?:t:)?MimeContent>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ITEM_ID_T_PATTERN = """<t:ItemId Id="([^"]+)"""".toRegex()
        private val ITEM_ID_NS_PATTERN = """<t:ItemId\s+Id="([^"]+)"""".toRegex()
        private val ITEM_ID_ALT_PATTERN = """ItemId\s+Id="([^"]+)"""".toRegex()
        private val EMAIL_ADDR_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
    
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
        val performNtlmHandshake: suspend () -> String?,
        val executeNtlmRequest: suspend (String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String) -> String?,
        val getDraftsFolderId: suspend () -> String?
    )
    
    
    /**
     * Создание черновика на сервере. С вложениями — MIME (тело и вложения одним CreateItem: только так
     * inline-картинки с Content-ID надёжно сохраняются), без вложений или при ошибке MIME — EWS CreateItem.
     * Сервер недоступен или отказал — local_draft_ (локальная запись, на сервер её загрузит syncDraftsFull).
     * Exchange 2007 SP1+.
     */
    suspend fun createDraft(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        attachments: List<DraftAttachmentData> = emptyList(),
        importance: Int = 1
    ): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        if (attachments.isNotEmpty()) {
            val mimeResult = createDraftMime(to, cc, bcc, subject, body, attachments)
            if (mimeResult is EasResult.Success) {
                // Успех без id (DraftIds.newUnconfirmed) — черновик уже на сервере: второй CreateItem
                // создал бы дубликат, а дописать Bcc/важность без id нельзя.
                if (!DraftIds.isLocal(mimeResult.data)) applyDraftExtrasEws(mimeResult.data, bcc, importance)
                return mimeResult
            }
            // Ошибку MIME не «чиним» поиском по теме: самый свежий черновик с той же темой — обычно
            // ПРЕДЫДУЩАЯ версия пересохраняемого черновика; её id выдался бы за новый, а саму её
            // вызывающий затем удалил бы. Успешный ответ без ItemId (Exchange 2007) разобран в createDraftMime.
        }

        val ewsResult = createDraftEws(to, cc, bcc, subject, body, attachments, importance)
        if (ewsResult is EasResult.Success) {
            return ewsResult
        }
        android.util.Log.w("EasDraftsService", "createDraft: not saved on the server (${(ewsResult as EasResult.Error).message})")
        // НЕ createDraftEas: он сбрасывает SyncKey на "0" (полная ресинхронизация теряет pending Adds).
        return EasResult.Success(DraftIds.newDeviceOnly())
    }

    /**
     * Тело, скрытые копии и время последнего изменения черновика по EWS ItemId (GetItem). EAS до 16.0
     * скрытые копии не передаёт — только так их можно узнать у черновика из Outlook. Exchange 2007 SP1+.
     */
    internal suspend fun getDraftDetails(itemId: String): EasResult<EwsDraftXml.DraftDetails> = withContext(Dispatchers.IO) {
        try {
            val response = executeEwsWithAuth(EwsDraftXml.getDraftDetails(resolveEwsVersion(), itemId, deps.escapeXml), "GetItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
            if (!EwsClient.isEwsSuccess(response)) {
                return@withContext EasResult.Error("Не удалось загрузить черновик: ${ewsErrorDetails(response)}")
            }
            EasResult.Success(EwsDraftXml.parseDraftDetails(response))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Не удалось загрузить черновик: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }

    /**
     * Обновление черновика на сервере (EWS UpdateItem). [bcc] = null — скрытые копии приложению не
     * известны (EAS до 16.0 их не передаёт): на сервере они не меняются.
     */
    suspend fun updateDraft(
        serverId: String,
        to: String,
        cc: String,
        bcc: String?,
        subject: String,
        body: String,
        importance: Int = 1,
        appendBcc: Boolean = false
    ): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        // Локальные черновики нельзя обновить на сервере
        if (serverId.startsWith("local_draft_")) {
            return EasResult.Success(true)
        }

        return updateDraftEws(serverId, to, cc, bcc, subject, body, importance, appendBcc)
    }
    /**
 * Загрузка Body черновика через EWS GetItem
 * Исправление бага старой версии - Body не загружался при открытии черновика
 */
suspend fun getDraftBody(serverId: String): EasResult<String> {
    return withContext(Dispatchers.IO) {
        try {
            val escapedServerId = deps.escapeXml(serverId)
            
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
                <t:ItemId Id="$escapedServerId"/>
            </m:ItemIds>
        </m:GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            val responseXml = executeEwsWithAuth(soapRequest, "GetItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
            
            val rawBody = BODY_PATTERN
                .find(responseXml)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()

            // Exchange 2007 SP1 может вернуть как raw HTML, так и XML-escaped body.
            val body = if (rawBody.startsWith("&lt;") || (!rawBody.startsWith("<") && rawBody.contains("&lt;"))) {
                XmlUtils.unescape(rawBody)
            } else {
                rawBody
            }
            
            EasResult.Success(body)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error(e.message ?: "Ошибка загрузки Body")
        }
    }
}
    /**
     * Полноценная синхронизация черновиков через EWS.
     * FindItem (заголовки) → GetItem batch (body + recipients + attachments).
     * Совместимо с Exchange 2007 SP1+.
     */
    suspend fun syncDraftsEws(): EasResult<List<EasDraft>> = withContext(Dispatchers.IO) {
        try {
            if (!deps.isVersionDetected()) {
                deps.detectEasVersion()
            }
            val exchangeVersion = resolveEwsVersion()

            // 1. FindItem — получаем список черновиков (заголовки)
            val findRequest = buildFindDraftsRequest(exchangeVersion)
            val findResponse = executeEwsWithAuth(findRequest, "FindItem")
                ?: return@withContext EasResult.Error("EWS FindItem: нет ответа от сервера")
            if (findResponse.contains("ResponseClass=\"Error\"")) {
                val msg = EasPatterns.EWS_MESSAGE_TEXT.find(findResponse)?.groupValues?.get(1) ?: "FindItem error"
                return@withContext EasResult.Error("EWS FindItem: $msg")
            }

            val drafts = parseDraftsResponse(findResponse)
            if (drafts.isEmpty()) {
                return@withContext EasResult.Success(emptyList())
            }

            // 2. GetItem batch — body + recipients + attachments для ВСЕХ черновиков.
            //    FindItem НЕ возвращает Body и ToRecipients/CcRecipients (MS docs).
            val enriched = fillDraftDetailsEws(drafts, exchangeVersion)
            EasResult.Success(enriched)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("EWS syncDrafts: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
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
            
            val responseXml = executeEwsWithAuth(soapRequest, "GetItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить EWS GetItem")
            
            // КРИТИЧНО: XmlValueExtractor.extractEws использует regex
            // <t:MimeContent>(.*?)</t:MimeContent> — он НЕ обрабатывает атрибуты.
            // EWS ВСЕГДА возвращает <t:MimeContent CharacterSet="UTF-8">base64</t:MimeContent>.
            // Без [^>]* regex не совпадёт → extractEws вернёт null → inline-картинки потеряны.
            val mimeBase64 = MIME_CONTENT_PATTERN.find(responseXml)?.groupValues?.get(1)?.trim() ?: ""
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка загрузки MIME: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }
    
    suspend fun downloadAttachmentEws(attachmentId: String): EasResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = buildGetAttachmentRequest(attachmentId, resolveEwsVersion())

            val response = executeEwsWithAuth(request, "GetAttachment")
                ?: return@withContext EasResult.Error("Не удалось выполнить EWS GetAttachment")

            val contentBase64 = XmlValueExtractor.extractEws(response, "Content")
                ?: return@withContext EasResult.Error("Нет данных вложения")
            val bytes = try {
                Base64.decode(contentBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                return@withContext EasResult.Error("Ошибка декодирования вложения")
            }
            EasResult.Success(bytes)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка скачивания вложения: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
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
            val ewsVersion = resolveEwsVersion()
            
            val tempFile = java.io.File.createTempFile("mime_draft_", ".tmp")
            try {
            tempFile.outputStream().buffered().use {
                buildMimeMessageToStream(to, cc, bcc, subject, body, attachments, it)
            }

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

            val chunkSize = 3 * 1024
            val buffer = ByteArray(chunkSize)
            tempFile.inputStream().buffered().use { input ->
                while (true) {
                    var totalRead = 0
                    while (totalRead < chunkSize) {
                        val read = input.read(buffer, totalRead, chunkSize - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    if (totalRead == 0) break
                    sb.append(Base64.encodeToString(buffer, 0, totalRead, Base64.NO_WRAP))
                }
            }

            sb.append("</t:MimeContent>")
            sb.append("</t:Message>")
            sb.append("</m:Items>")
            sb.append("</m:CreateItem>")
            sb.append("</soap:Body>")
            sb.append("</soap:Envelope>")
            val soapRequest = sb.toString()
            
            // 3. Отправляем
            val responseXml = executeEwsWithAuth(soapRequest, "CreateItem")
                ?: return@withContext EasResult.Error("MIME CreateItem: нет ответа от сервера")
            
            // 4. Извлекаем ItemId
            var itemId = XmlValueExtractor.extractAttribute(responseXml, "ItemId", "Id")
                ?: EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                ?: ITEM_ID_T_PATTERN.find(responseXml)?.groupValues?.get(1)
            
            // 5. Если ItemId не найден, но ответ без ошибки — черновик мог быть создан.
            // Ищем его через FindItem в папке Drafts по теме (Exchange 2007 SP1+).
            val hasError = responseXml.contains("ResponseClass=\"Error\"")
            if (itemId == null && !hasError && subject.isNotBlank()) {
                try {
                    itemId = findDraftItemIdBySubject(subject)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            
            when {
                itemId != null -> EasResult.Success(itemId)
                // Сервер подтвердил создание, но id не узнать: черновик есть на сервере — не создаём второй,
                // синхронизация найдёт его и сверит с локальной копией (DraftIds.mayExistOnServer).
                !hasError && EwsClient.isEwsSuccess(responseXml) -> EasResult.Success(DraftIds.newUnconfirmed())
                else -> {
                    val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(responseXml)?.groupValues?.get(1)
                    val details = messageText ?: if (hasError) "Server error" else "ItemId not found"
                    EasResult.Error("MIME CreateItem: $details")
                }
            }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("MIME CreateItem: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }
    
    /**
     * Строит MIME-сообщение (multipart/related для inline, multipart/mixed для файлов).
     * RFC 2387 (multipart/related), RFC 2392 (cid: URLs).
     */
    private fun buildMimeMessageToStream(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        attachments: List<DraftAttachmentData>,
        output: java.io.OutputStream
    ) {
        val props = java.util.Properties()
        val session = javax.mail.Session.getInstance(props)
        val message = javax.mail.internet.MimeMessage(session)
        
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
        message.writeTo(output)
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
     * Basic Auth → NTLM fallback для EWS SOAP запросов.
     * DRY: единая точка авторизации вместо inline-повторений.
     */
    private suspend fun executeEwsWithAuth(soapRequest: String, operation: String): String? {
        var responseXml = deps.tryBasicAuthEws(soapRequest, operation)
        if (responseXml == null) {
            deps.performNtlmHandshake() ?: return null
            responseXml = deps.executeNtlmRequest(soapRequest, operation)
        }
        return responseXml
    }

    /**
     * Ищет черновик по теме через EWS FindItem в папке Drafts.
     * Используется как fallback, когда CreateItem не вернул ItemId.
     * Возвращает ItemId самого свежего совпадения или null.
     * Совместим с Exchange 2007 SP1+.
     */
    private suspend fun findDraftItemIdBySubject(subject: String): String? {
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
        
        val findResponse = executeEwsWithAuth(findRequest, "FindItem") ?: return null

        // Извлекаем первый (самый свежий) ItemId
        return XmlValueExtractor.extractAttribute(findResponse, "ItemId", "Id")
            ?: EasPatterns.EWS_ITEM_ID.find(findResponse)?.groupValues?.get(1)
            ?: ITEM_ID_T_PATTERN.find(findResponse)?.groupValues?.get(1)
    }
    
    /**
     * Возвращает ВСЕ ItemId черновиков с данным subject.
     * Используется при пересохранении: удаляем все старые копии кроме нового.
     * Совместим с Exchange 2007 SP1+ (EWS FindItem).
     */
    suspend fun findAllDraftItemIdsBySubject(subject: String): EasResult<List<String>> {
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
        
        val findResponse = executeEwsWithAuth(findRequest, "FindItem")
            ?: return EasResult.Error("EWS FindItem failed")

        val allIds = ITEM_ID_NS_PATTERN.findAll(findResponse).map { it.groupValues[1] }.toList()
        
        if (allIds.isEmpty()) {
            val altIds = ITEM_ID_ALT_PATTERN.findAll(findResponse).map { it.groupValues[1] }.toList()
            return EasResult.Success(altIds)
        }
        
        return EasResult.Success(allIds)
    }
    
    // === EWS методы ===
    
    /**
     * EWS CreateItem черновика (HTML-тело, получатели, важность), затем вложения по одному через
     * CreateAttachment. Вложения не загрузились — недособранный черновик удаляется и возвращается
     * ошибка: иначе вызывающий принял бы его за сохранённый и удалил прежнюю версию с её вложениями.
     */
    private suspend fun createDraftEws(
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        attachments: List<DraftAttachmentData>,
        importance: Int
    ): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val ewsVersion = resolveEwsVersion()
            val request = EwsDraftXml.createItem(ewsVersion, subject, body, to, cc, bcc, importance, deps.escapeXml)
            val response = executeEwsWithAuth(request, "CreateItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
            var itemId = EwsIds.parseItemId(response)
            if (itemId == null && EwsClient.isEwsSuccess(response) && subject.isNotBlank()) {
                // Успех без ItemId (встречается на Exchange 2007): только что созданный — самый свежий с этой темой.
                itemId = findDraftItemIdBySubject(subject)
            }
            if (itemId == null) {
                // Успех без id: черновик на сервере есть, второй не создаём (см. createDraftMime). Вложения без id не
                // добавить — тогда ошибка (как раньше): иначе новая версия без них, а прежняя удалена.
                if (EwsClient.isEwsSuccess(response) && attachments.isEmpty()) {
                    return@withContext EasResult.Success(DraftIds.newUnconfirmed())
                }
                return@withContext EasResult.Error("Не удалось создать черновик: ${ewsErrorDetails(response)}")
            }
            if (attachments.isNotEmpty()) {
                val attached = attachFilesEws(itemId, EwsIds.parseChangeKey(response), attachments, ewsVersion)
                if (attached is EasResult.Error) {
                    hardDeleteEws(itemId, ewsVersion)
                    return@withContext attached
                }
            }
            EasResult.Success(itemId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка создания черновика: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }

    /**
     * Скрытые копии и важность черновика, созданного из MIME, — явным UpdateItem: что Exchange
     * перенесёт их из заголовков MIME при CreateItem, документацией не гарантировано. Сбой не
     * отменяет сохранение: локальная копия хранит оба поля, отправка из приложения их учтёт.
     */
    private suspend fun applyDraftExtrasEws(itemId: String, bcc: String, importance: Int) {
        if (bcc.isBlank() && importance == 1) return
        try {
            val request = EwsDraftXml.updateItem(
                resolveEwsVersion(), itemId, changeKey = null, subject = null, htmlBody = null,
                to = null, cc = null, bcc = bcc.takeIf { it.isNotBlank() },
                easImportance = importance.takeIf { it != 1 }, escape = deps.escapeXml
            )
            val response = executeEwsWithAuth(request, "UpdateItem")
            if (response == null || !EwsClient.isEwsSuccess(response)) {
                android.util.Log.w("EasDraftsService", "applyDraftExtrasEws: Bcc/Importance not applied")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EasDraftsService", "applyDraftExtrasEws: ${e.message}")
        }
    }

    /**
     * Окончательное удаление черновика по EWS ItemId (без «Удалённых»): Basic, затем NTLM — как при его создании.
     * Черновика уже нет (ErrorItemNotFound) — тоже успех.
     */
    suspend fun hardDeleteDraft(itemId: String): EasResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = executeEwsWithAuth(EwsDraftXml.hardDelete(resolveEwsVersion(), itemId, deps.escapeXml), "DeleteItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить EWS DeleteItem")
            if (EwsClient.isEwsSuccessOrNotFound(response)) EasResult.Success(Unit) else EasResult.Error(ewsErrorDetails(response))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("EWS delete error: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
        }
    }

    /** Уборка черновика, который не удалось собрать целиком (без «Удалённых»). */
    private suspend fun hardDeleteEws(itemId: String, version: String) {
        try {
            executeEwsWithAuth(EwsDraftXml.hardDelete(version, itemId, deps.escapeXml), "DeleteItem")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EasDraftsService", "hardDeleteEws: ${e.message}")
        }
    }

    private fun ewsErrorDetails(xml: String): String =
        EasPatterns.EWS_MESSAGE_TEXT.find(xml)?.groupValues?.get(1)
            ?: EasPatterns.EWS_RESPONSE_CODE.find(xml)?.groupValues?.get(1)
            ?: "Unknown error"

    private suspend fun updateDraftEws(
        serverId: String,
        to: String,
        cc: String,
        bcc: String?,
        subject: String,
        body: String,
        importance: Int,
        appendBcc: Boolean
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val exchangeVersion = resolveEwsVersion()

            // ChangeKey текущей версии (GetItem IdOnly). Элемента нет или id не EWS — ошибка с кодом EWS
            // сразу, без UpdateItem (вызывающий сохранит черновик заново).
            val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
    <soap:Header>
        <t:RequestServerVersion Version="$exchangeVersion"/>
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
            val getItemResponse = executeEwsWithAuth(getItemRequest, "GetItem")
                ?: return@withContext EasResult.Error("Не удалось получить ChangeKey")
            if (!EwsClient.isEwsSuccess(getItemResponse)) {
                return@withContext EasResult.Error("Не удалось обновить черновик: ${ewsErrorDetails(getItemResponse)}")
            }

            val request = EwsDraftXml.updateItem(
                exchangeVersion, serverId, EwsIds.parseChangeKey(getItemResponse),
                subject = subject, htmlBody = body, to = to, cc = cc, bcc = bcc, easImportance = importance,
                escape = deps.escapeXml, appendBcc = appendBcc
            )
            val responseXml = executeEwsWithAuth(request, "UpdateItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить запрос к EWS")
            if (EwsClient.isEwsSuccess(responseXml)) {
                EasResult.Success(true)
            } else {
                EasResult.Error("Не удалось обновить черновик: ${ewsErrorDetails(responseXml)}")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error(e.message ?: "Ошибка обновления черновика")
        }
    }

    private suspend fun deleteDraftEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            val maxRetries = 3
            var lastError: String? = null
            
            for (attempt in 1..maxRetries) {
                try {
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
                                        <t:ItemId Id="${deps.escapeXml(serverId)}"/>
                                    </m:ItemIds>
                                </m:DeleteItem>
                            </soap:Body>
                        </soap:Envelope>
                    """.trimIndent()

                    val responseXml = executeEwsWithAuth(soapRequest, "DeleteItem")
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
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

            val response = executeEwsWithAuth(request, "CreateAttachment")
                ?: return@withContext EasResult.Error(
                    "Не удалось прикрепить вложение ${index + 1}/${attachments.size} (${att.name})"
                )

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
            val newChangeKey = ROOT_ITEM_CHANGE_KEY_REGEX
                .find(response)?.groupValues?.get(1)
                ?: CHANGE_KEY_REGEX.find(response)?.groupValues?.get(1)
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
        val changeKeyAttr = changeKey?.takeIf { it.isNotBlank() }?.let { " ChangeKey=\"${deps.escapeXml(it)}\"" } ?: ""

        // Строим XML без trimIndent — чистый XML без артефактов пробелов.
        // FileAttachment (порядок по XSD, IsInline только с Exchange 2010) — EwsAttachmentXml.
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
            EwsAttachmentXml.appendFileAttachment(sb, att, exchangeVersion, deps.escapeXml)
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

    private fun parseDraftsResponse(xml: String): List<EasDraft> {
    val drafts = mutableListOf<EasDraft>()

    val itemBlocks = MESSAGE_PATTERN.findAll(xml).map { it.groupValues[1] }.toList().ifEmpty {
        ITEM_PATTERN.findAll(xml).map { it.groupValues[1] }.toList()
    }

    itemBlocks.forEach { messageXml ->

        val itemId = XmlValueExtractor.extractAttribute(messageXml, "ItemId", "Id") ?: return@forEach
        val changeKey = XmlValueExtractor.extractAttribute(messageXml, "ItemId", "ChangeKey") ?: ""

        val subjectMatch = SUBJECT_PATTERN.find(messageXml)
        val subject = subjectMatch?.groupValues?.get(1)?.let { XmlUtils.unescape(it) } ?: ""

        val dateMatch = DATE_TIME_CREATED_PATTERN.find(messageXml)
        val dateStr = dateMatch?.groupValues?.get(1) ?: ""

        val toRecipients = mutableListOf<String>()
        TO_RECIPIENTS_PATTERN.find(messageXml)?.let { toMatch ->
            EMAIL_ADDRESS_PATTERN.findAll(toMatch.groupValues[1]).forEach { emailMatch ->
                toRecipients.add(XmlUtils.unescape(emailMatch.groupValues[1]))
            }
        }

        val ccRecipients = mutableListOf<String>()
        CC_RECIPIENTS_PATTERN.find(messageXml)?.let { ccMatch ->
            EMAIL_ADDRESS_PATTERN.findAll(ccMatch.groupValues[1]).forEach { emailMatch ->
                ccRecipients.add(XmlUtils.unescape(emailMatch.groupValues[1]))
            }
        }

        val bccRecipients = mutableListOf<String>()
        BCC_RECIPIENTS_PATTERN.find(messageXml)?.let { bccMatch ->
            EMAIL_ADDRESS_PATTERN.findAll(bccMatch.groupValues[1]).forEach { emailMatch ->
                bccRecipients.add(XmlUtils.unescape(emailMatch.groupValues[1]))
            }
        }

        val hasAttachmentsMatch = HAS_ATTACHMENTS_PATTERN.find(messageXml)
        val hasAttachments = hasAttachmentsMatch?.groupValues?.get(1)
            ?.trim()
            ?.let { it.equals("true", ignoreCase = true) || it == "1" }
            ?: false

        drafts.add(
            EasDraft(
                serverId = itemId,
                changeKey = changeKey,
                subject = subject,
                to = toRecipients.joinToString(", "),
                cc = ccRecipients.joinToString(", "),
                bcc = bccRecipients.joinToString(", "),
                dateCreated = dateStr,
                hasAttachments = hasAttachments
            )
        )
    }

    return drafts
}

    /**
     * GetItem batch для ВСЕХ черновиков: body + recipients + attachments.
     * Вызывается из syncDraftsEws() после FindItem.
     */
    private suspend fun fillDraftDetailsEws(
        drafts: List<EasDraft>,
        exchangeVersion: String
    ): List<EasDraft> {
        if (drafts.isEmpty()) return drafts

        val detailsMap = getDraftDetailsEws(
            drafts.map { it.serverId },
            exchangeVersion
        )
        if (detailsMap.isEmpty()) return drafts

        return drafts.map { draft ->
            val details = detailsMap[draft.serverId]
            if (details != null) {
                draft.copy(
                    attachments = if (details.attachments.isNotEmpty()) details.attachments else draft.attachments,
                    hasAttachments = draft.hasAttachments || details.attachments.isNotEmpty(),
                    body = if (details.body.isNotBlank()) details.body else draft.body,
                    to = if (details.to.isNotBlank()) details.to else draft.to,
                    cc = if (details.cc.isNotBlank()) details.cc else draft.cc,
                    bcc = if (details.bcc.isNotBlank()) details.bcc else draft.bcc
                )
            } else {
                draft
            }
        }
    }

    /**
     * GetItem batch для черновиков: body + recipients + attachments.
     * Basic Auth first, NTLM fallback. Exchange 2007 SP1+.
     */
    private suspend fun getDraftDetailsEws(
        itemIds: List<String>,
        exchangeVersion: String
    ): Map<String, DraftEwsDetails> {
        if (itemIds.isEmpty()) return emptyMap()

        // Батчим по 50 — Exchange лимитирует размер GetItem запроса.
        // Для 100+ черновиков одним запросом можно получить таймаут или ошибку.
        val result = mutableMapOf<String, DraftEwsDetails>()
        for (chunk in itemIds.chunked(50)) {
            val chunkResult = getDraftDetailsBatchEws(chunk, exchangeVersion)
            result.putAll(chunkResult)
        }
        return result
    }

    private suspend fun getDraftDetailsBatchEws(
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

        val response = executeEwsWithAuth(getItemRequest, "GetItem") ?: return emptyMap()

        val result = mutableMapOf<String, DraftEwsDetails>()
        val itemBlocks = MESSAGE_PATTERN.findAll(response)
            .map { it.groupValues[1] }
            .toList()
            .ifEmpty {
                ITEM_PATTERN.findAll(response).map { it.groupValues[1] }.toList()
            }

        itemBlocks.forEach { itemXml ->
            val itemId = XmlValueExtractor.extractAttribute(itemXml, "ItemId", "Id") ?: return@forEach
            val attachments = parseEwsAttachments(itemXml)
            // Exchange 2007 SP1 возвращает raw HTML в <t:Body> (не XML-escaped).
            // unescapeXml нужен ТОЛЬКО если body escaped (начинается с &lt; вместо <).
            val rawBody = EasPatterns.EWS_BODY.find(itemXml)?.groupValues?.get(1)?.trim().orEmpty()
            val body = if (rawBody.startsWith("&lt;") || (!rawBody.startsWith("<") && rawBody.contains("&lt;"))) {
                XmlUtils.unescape(rawBody)
            } else {
                rawBody
            }
            // Извлекаем получателей (GetItem возвращает EmailAddress, в отличие от FindItem)
            val toXml = TO_RECIPIENTS_PATTERN.find(itemXml)?.groupValues?.get(1) ?: ""
            val ccXml = CC_RECIPIENTS_PATTERN.find(itemXml)?.groupValues?.get(1) ?: ""
            val bccXml = BCC_RECIPIENTS_PATTERN.find(itemXml)?.groupValues?.get(1) ?: ""
            val toStr = if (toXml.isNotBlank()) parseRecipients(toXml) else ""
            val ccStr = if (ccXml.isNotBlank()) parseRecipients(ccXml) else ""
            val bccStr = if (bccXml.isNotBlank()) parseRecipients(bccXml) else ""
            result[itemId] = DraftEwsDetails(
                attachments = attachments,
                body = body,
                to = toStr,
                cc = ccStr,
                bcc = bccStr
            )
        }
        return result
    }
    
    private fun parseRecipients(xml: String): String {
        val seen = linkedSetOf<String>()
        EMAIL_ADDRESS_PATTERN.findAll(xml).forEach { match ->
            val raw = XmlUtils.unescape(match.groupValues[1].trim())
            val cleaned = extractEmailOnly(raw)
            if (cleaned.isNotBlank()) {
                seen.add(cleaned)
            }
        }
        return seen.joinToString(", ")
    }

    private fun extractEmailOnly(value: String): String {
        val bracketMatch = BRACKET_EMAIL_REGEX.find(value)?.groupValues?.get(1)
        val emailCandidate = (bracketMatch ?: value).replace("\"", "").trim()
        return EMAIL_ADDR_REGEX.find(emailCandidate)?.value ?: emailCandidate
    }

    private fun parseEwsAttachments(itemXml: String): List<EasAttachment> {
        val attachments = mutableListOf<EasAttachment>()
        val attachmentsXml = ATTACHMENTS_PATTERN.find(itemXml)?.groupValues?.get(1)
            ?: return attachments

        FILE_ATTACHMENT_PATTERN.findAll(attachmentsXml).forEach { match ->
            val attXml = match.groupValues[1]
            val attachmentId = ATTACHMENT_ID_PATTERN.find(attXml)?.groupValues?.get(1) ?: ""
            val displayName = XmlValueExtractor.extractEws(attXml, "Name")
                ?: XmlValueExtractor.extractEws(attXml, "DisplayName")
                ?: "attachment"
            val contentType = XmlValueExtractor.extractEws(attXml, "ContentType") ?: "application/octet-stream"
            val estimatedSize = XmlValueExtractor.extractEws(attXml, "Size")
                ?.toLongOrNull()
                ?: XmlValueExtractor.extractEws(attXml, "EstimatedSize")?.toLongOrNull()
                ?: 0
            val isInlineExplicit = XmlValueExtractor.extractEws(attXml, "IsInline")
                ?.let { it == "true" || it == "1" } ?: false
            val contentId = XmlValueExtractor.extractEws(attXml, "ContentId")?.trim()?.let { raw ->
                val cleaned = raw.removePrefix("<").removeSuffix(">")
                if (cleaned.startsWith("cid:", ignoreCase = true)) cleaned.substring(4) else cleaned
            }
            // Exchange 2007 SP1 НЕ возвращает IsInline — всегда false.
            // Но если ContentId присутствует, вложение фактически inline (cid: ссылка в HTML).
            val isInline = isInlineExplicit || !contentId.isNullOrBlank()

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
    
}

// EasDraft определён в EasClient.kt

package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64

/**
 * Сервис для работы с вложениями Exchange (EAS)
 * Выделен из EasClient для соблюдения принципа SRP (Single Responsibility)
 *
 * Отвечает за:
 * - Отправку писем с вложениями (SendMail)
 * - Скачивание вложений (ItemOperations, GetAttachment)
 */
class EasAttachmentService internal constructor(
    private val deps: AttachmentServiceDependencies
) {

    /**
     * Зависимости для EasAttachmentService
     */
    class AttachmentServiceDependencies(
        val executeRequest: suspend (Request) -> Response,
        /** Команда EAS с ответом-потоком ([EasTransport.openEasCommand]). */
        val openEasCommand: suspend (
            command: String, xml: String, headers: Map<String, String>, read: (Response) -> EasResult<Unit>
        ) -> EasResult<Unit>,
        val parseWbxml: (ByteArray) -> String,
        val buildUrl: (String) -> String,
        val getAuthHeader: () -> String,
        val getPolicyKey: () -> String?,
        val getEasVersion: () -> String,
        val provision: suspend () -> EasResult<String>,
        val wbxmlGenerateSendMail: (String, ByteArray) -> ByteArray,
        /** Команда ComposeMail — общий транспорт отправки ([EasEmailService.postComposeMail]). */
        val postComposeMail: suspend (command: String, legacyQuery: String, mime: ByteArray, wbxml: (ByteArray) -> ByteArray) -> EasResult<Boolean>,
        val wbxmlGenerateSmartForward: (String, String, String, ByteArray) -> ByteArray,
        val getFromEmail: () -> String,
        val getDeviceId: () -> String
    )

    // ==================== ОТПРАВКА С ВЛОЖЕНИЯМИ ====================

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
        importance: Int = 1,
        stableClientId: String? = null
    ): EasResult<Boolean> {
        recipientsError(to, cc, bcc)?.let { return EasResult.Error(it) }
        attachmentsSizeError(attachments)?.let { return EasResult.Error(it) }
        // SYNC-6: стабильный идентификатор отправки (переживает retry через inputData воркера) — DRY с путём без вложений.
        val clientId = stableClientId ?: freshSendToken()
        val mimeBytes = withContext(Dispatchers.IO) {
            buildMimeWithAttachments(to, subject, body, cc, bcc, attachments, requestReadReceipt, requestDeliveryReceipt, importance, clientId)
        }
        mimeSizeError(mimeBytes)?.let { return EasResult.Error(it) }
        return deps.postComposeMail("SendMail", "&SaveInSent=T", mimeBytes) { mime ->
            deps.wbxmlGenerateSendMail(clientId, mime)
        }
    }

    /**
     * Пересылка письма с (новыми) вложениями через SmartForward (MS-ASCMD §2.2.1.19)
     * Сервер автоматически включает оригинальные вложения.
     * attachments содержит ТОЛЬКО новые вложения, добавленные пользователем.
     */
    suspend fun smartForwardWithAttachments(
        to: String,
        subject: String,
        body: String,
        cc: String = "",
        bcc: String = "",
        attachments: List<Triple<String, String, ByteArray>>,
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false,
        importance: Int = 1,
        collectionId: String,
        itemId: String,
        stableClientId: String? = null
    ): EasResult<Boolean> {
        recipientsError(to, cc, bcc)?.let { return EasResult.Error(it) }
        attachmentsSizeError(attachments)?.let { return EasResult.Error(it) }
        val clientId = stableClientId ?: freshSendToken()
        val mimeBytes = withContext(Dispatchers.IO) {
            buildMimeWithAttachments(to, subject, body, cc, bcc, attachments, requestReadReceipt, requestDeliveryReceipt, importance, clientId)
        }
        mimeSizeError(mimeBytes)?.let { return EasResult.Error(it) }
        return deps.postComposeMail("SmartForward", smartForwardQuery(collectionId, itemId), mimeBytes) { mime ->
            deps.wbxmlGenerateSmartForward(clientId, collectionId, itemId, mime)
        }
    }

    /** Вложения крупнее предела сервера — до сборки MIME (base64 увеличивает размер на треть). */
    private fun attachmentsSizeError(attachments: List<Triple<String, String, ByteArray>>): String? {
        val totalSize = attachments.sumOf { it.third.size.toLong() }
        if (totalSize <= MAX_ATTACHMENTS_BYTES) return null
        return "Размер вложений (${totalSize / 1024 / 1024} МБ) превышает лимит сервера (7 МБ)"
    }

    private fun mimeSizeError(mime: ByteArray): String? =
        if (mime.size <= MAX_MIME_BYTES) null
        else "Размер письма (${mime.size / 1024 / 1024} МБ) превышает лимит сервера (10 МБ)"

    /**
     * Строит MIME сообщение с вложениями
     */
    private fun buildMimeWithAttachments(
        to: String,
        subject: String,
        body: String,
        cc: String,
        bcc: String,
        attachments: List<Triple<String, String, ByteArray>>,
        requestReadReceipt: Boolean = false,
        requestDeliveryReceipt: Boolean = false,
        importance: Int = 1,
        clientId: String? = null
    ): ByteArray {
        val fromEmail = deps.getFromEmail()
        val deviceId = deps.getDeviceId()

        val messageId = buildStableMessageId(clientId, deviceId)
        val dateFormat = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        val date = dateFormat.format(java.util.Date())
        val boundary = "----=_Part_${System.currentTimeMillis()}_${System.nanoTime()}"

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
        sb.append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n")
        sb.append("\r\n")

        // КРИТИЧНО: Извлекаем inline-картинки из data: URLs в HTML
        // Без этого Exchange 2007 SP1 обрежет/потеряет data: URL при хранении.
        // Нужно заменить data: URL на cid: ссылки и создать отдельные MIME-части.
        val dataImagePattern = Regex("""<img[^>]*src="data:image/(jpeg|jpg|png|gif|webp|bmp);base64,([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        val inlineMatches = dataImagePattern.findAll(body).toList()

        val htmlBody = if (body.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
                          body.trimStart().startsWith("<html", ignoreCase = true)) {
            body
        } else {
            """<!DOCTYPE html>
<html>
<head><meta charset="UTF-8"></head>
<body>$body</body>
</html>"""
        }

        if (inlineMatches.isNotEmpty()) {
            // Есть inline-картинки → multipart/related внутри multipart/mixed
            // Структура: multipart/mixed { multipart/related { text/html + image(s) } + attachment(s) }
            val relatedBoundary = "----=_Related_${System.currentTimeMillis()}_${System.nanoTime()}"

            sb.append("--$boundary\r\n")
            sb.append("Content-Type: multipart/related; boundary=\"$relatedBoundary\"\r\n")
            sb.append("\r\n")

            // HTML часть с замёнёнными cid: ссылками
            sb.append("--$relatedBoundary\r\n")
            sb.append("Content-Type: text/html; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n")

            var modifiedBody = htmlBody
            inlineMatches.forEachIndexed { index, match ->
                val fullMatch = match.value
                val contentId = "image${index + 1}@$deviceId"
                val replacement = fullMatch.replace(
                    Regex("""src="data:image/[^;]+;base64,[^"]+""""),
                    """src="cid:$contentId""""
                )
                modifiedBody = modifiedBody.replace(fullMatch, replacement)
            }
            sb.append(modifiedBody)
            sb.append("\r\n")

            // Inline-картинки как отдельные MIME-части
            inlineMatches.forEachIndexed { index, match ->
                val imageType = match.groupValues[1]
                val base64Data = match.groupValues[2]
                val contentId = "image${index + 1}@$deviceId"

                sb.append("--$relatedBoundary\r\n")
                sb.append("Content-Type: ${inlineImageMimeType(imageType)}\r\n")
                sb.append("Content-Transfer-Encoding: base64\r\n")
                sb.append("Content-ID: <$contentId>\r\n")
                sb.append("Content-Disposition: inline\r\n")
                sb.append("\r\n")

                // Разбиваем base64 на строки по 76 символов (RFC 2045)
                base64Data.chunked(76).forEach { line ->
                    sb.append(line)
                    sb.append("\r\n")
                }
                sb.append("\r\n")
            }

            sb.append("--$relatedBoundary--\r\n")
        } else {
            // Нет inline-картинок → просто text/html
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: text/html; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n")
            sb.append(htmlBody)
            sb.append("\r\n")
        }

        // Файловые вложения
        for ((name, mimeType, data) in attachments) {
            sb.append("--$boundary\r\n")
            val encodedName = mimeEncodedWord(name)
            sb.append("Content-Type: $mimeType; name=\"$encodedName\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"$encodedName\"\r\n")
            sb.append("\r\n")
            // RFC 2045: строки base64 в MIME разделяются CRLF, не голым LF (как Base64.DEFAULT).
            // Единообразно с телом письма в EasEmailService
            sb.append(Base64.encodeToString(data, Base64.CRLF))
            sb.append("\r\n")
        }

        sb.append("--$boundary--\r\n")

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ==================== СКАЧИВАНИЕ ВЛОЖЕНИЙ ====================
    //
    // Порядок (проверено на Exchange 2007 SP2, output_EasProbe_att2.txt):
    // 1. ItemOperations Fetch с MS-ASAcceptMultiPart: T — файл приходит отдельной двоичной частью и пишется
    //    в поток, без base64 и без копий в памяти. Байты совпадают с base64-ответом, GetAttachment и EWS.
    // 2. GetAttachment методом POST (EAS 12.x; GET сервер отвергает с 501, в 14.0+ команды нет).
    // Вложения больше лимита сервера (~10 МБ: Status 11 и HTTP 413) — [TOO_LARGE]: их отдаёт только EWS,
    // письмо для поиска в EWS знает вызывающий (EmailOperationsService.downloadAttachmentToFile).

    /**
     * Вложение в память — для небольших (inline-картинки, приглашения .ics, вложения ответа и пересылки, которые
     * всё равно ограничены [MAX_ATTACHMENTS_BYTES]). Больше [MAX_IN_MEMORY_BYTES] — ошибка, файлы — [downloadAttachmentToFile].
     */
    suspend fun downloadAttachment(fileReference: String): EasResult<ByteArray> {
        var buffer = BoundedBuffer(MAX_IN_MEMORY_BYTES)
        return when (val result = fetch(fileReference) { BoundedBuffer(MAX_IN_MEMORY_BYTES).also { buffer = it } }) {
            is EasResult.Success -> EasResult.Success(buffer.toByteArray())
            is EasResult.Error -> result
        }
    }

    /** Вложение в файл потоком; при ошибке файл удаляется (половина файла хуже, чем никакого). */
    suspend fun downloadAttachmentToFile(fileReference: String, destFile: java.io.File): EasResult<java.io.File> {
        val result = try {
            fetch(fileReference) { java.io.FileOutputStream(destFile) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            destFile.delete()
            throw e
        }
        return when (result) {
            is EasResult.Success -> EasResult.Success(destFile)
            is EasResult.Error -> {
                destFile.delete()
                result
            }
        }
    }

    /** Попытки по порядку; [open] открывает приёмник заново на каждую попытку (прежнее содержимое отбрасывается). */
    private suspend fun fetch(fileReference: String, open: () -> java.io.OutputStream): EasResult<Unit> {
        if (fileReference.isBlank()) return EasResult.Error(NOT_FOUND)
        val decoded = try { java.net.URLDecoder.decode(fileReference, "UTF-8") } catch (_: Exception) { fileReference }
        // Exchange 2007: ссылка в Fetch уже percent-encoded (%3a). Раскодированная даёт Status 15 или пустой
        // Success — поэтому сначала ссылка как прислал сервер, раскодированная — запасной вариант.
        val refs = linkedSetOf(fileReference, decoded)
        var last: EasResult<Unit> = EasResult.Error(NOT_FOUND)
        var tooLarge = false
        attempts@ for (ref in refs) {
            val safe = XmlUtils.escape(ref)
            for (xml in listOf(
                EasXmlTemplates.itemOperationsFetchAttachment(safe),
                EasXmlTemplates.itemOperationsFetchAttachment(safe, withAirSyncBaseNs = false),
                EasXmlTemplates.itemOperationsFetchAttachment(safe, range = "0-999999999")
            )) {
                last = itemOperationsFetch(xml, open)
                android.util.Log.d(TAG, "ItemOperations Fetch -> ${if (last is EasResult.Success) "ok" else (last as EasResult.Error).message}")
                if (last is EasResult.Success) return last
                if ((last as EasResult.Error).message == TOO_LARGE) { tooLarge = true; break@attempts }
                if (isFinal(last.message)) return last
            }
        }
        // Лимит сервера общий: при Status 11 GetAttachment отвечает 413 (проверено на 2007 SP2) — запрос лишний
        if (!tooLarge && isGetAttachmentSupported()) {
            for (ref in refs) {
                val viaGet = getAttachmentPost(ref, open)
                android.util.Log.d(TAG, "GetAttachment POST -> ${if (viaGet is EasResult.Success) "ok" else (viaGet as EasResult.Error).message}")
                if (viaGet is EasResult.Success) return viaGet
                if ((viaGet as EasResult.Error).message == TOO_LARGE) return viaGet
                if (isFinal(viaGet.message)) return viaGet
            }
        }
        return if (tooLarge) EasResult.Error(TOO_LARGE) else last
    }

    /** Без этого обрыв связи давал до 8 запросов подряд, каждый со своим таймаутом. */
    private fun isFinal(message: String): Boolean = AttachmentErrors.isFinal(message)

    private fun isGetAttachmentSupported(): Boolean =
        (deps.getEasVersion().substringBefore('.').toIntOrNull() ?: 12) < 14

    private suspend fun itemOperationsFetch(xml: String, open: () -> java.io.OutputStream): EasResult<Unit> {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        return deps.openEasCommand("ItemOperations", xml, mapOf("MS-ASAcceptMultiPart" to "T")) { resp ->
            try {
                readFetchResponse(resp, open) { job?.ensureActive() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(downloadError(e))
            }
        }
    }

    /** Разбор ответа Fetch; [checkActive] бросает при отмене (ответ закроет вызывающий через use). */
    private fun readFetchResponse(resp: Response, open: () -> java.io.OutputStream, checkActive: () -> Unit): EasResult<Unit> {
        httpError(resp.code)?.let { return EasResult.Error(it) }
        val body = resp.body ?: return EasResult.Error(NOT_FOUND)
        val contentType = resp.header("Content-Type").orEmpty()
        if (contentType.contains("multipart", ignoreCase = true)) {
            val reader = EasMultipart.Reader(body.byteStream())
            android.util.Log.d(TAG, "multipart parts: ${reader.readHeader()}")
            val reply = EasMultipart.fetchReply(deps.parseWbxml(reader.readFirstPart()))
            statusError(reply)?.let { return EasResult.Error(it) }
            val part = reply.part ?: return EasResult.Error(NOT_FOUND)
            val written = open().use { out -> reader.copyPart(part, out, checkActive) }
            // Exchange 2007 на раскодированную ссылку отвечает «пустым Success»: ноль байт по EAS — не файл.
            // Настоящее пустое вложение подтверждает EWS (вложение найдено по Id, Content пустой)
            return if (written > 0) EasResult.Success(Unit) else EasResult.Error(NOT_FOUND)
        }
        // Сервер не поддержал multipart: base64 внутри WBXML. Ответ читается с пределом — длина может быть
        // неизвестна (chunked); больше предела — к EWS, там поток.
        val raw = BoundedBuffer(MAX_INLINE_RESPONSE_BYTES)
        try {
            copy(body.byteStream(), raw, checkActive)
        } catch (_: BoundedBuffer.Overflow) {
            return EasResult.Error(TOO_LARGE)
        }
        val reply = deps.parseWbxml(raw.toByteArray())
        statusError(EasMultipart.fetchReply(reply))?.let { return EasResult.Error(it) }
        val base64 = (EasPatterns.ITEM_OPS_DATA.find(reply) ?: EasPatterns.ITEM_OPS_PROPS_DATA.find(reply))
            ?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return EasResult.Error(NOT_FOUND)
        val data = java.util.Base64.getMimeDecoder().decode(base64)
        open().use { it.write(data) }
        return if (data.isNotEmpty()) EasResult.Success(Unit) else EasResult.Error(NOT_FOUND)
    }

    /** null — Fetch успешен. Status 11 «слишком большой» — к EWS; 6/15 и прочие — следующая попытка. */
    private fun statusError(reply: EasMultipart.FetchReply): String? {
        val fetch = reply.fetchStatus?.toIntOrNull()
        return when {
            reply.status != null && reply.status != "1" -> "ItemOperations Status=${reply.status}"
            fetch == ItemOperationsStatus.SUCCESS -> null
            fetch == ItemOperationsStatus.TOO_LARGE -> TOO_LARGE
            fetch == ItemOperationsStatus.NOT_FOUND -> NOT_FOUND
            else -> "ItemOperations Fetch Status=${reply.fetchStatus}"
        }
    }

    /** GetAttachment ([MS-ASCMD] 2.2.1.7): тело ответа — сам файл. Методом POST, GET Exchange 2007 отвергает (501). */
    private suspend fun getAttachmentPost(ref: String, open: () -> java.io.OutputStream): EasResult<Unit> =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            fun request(): Request {
                val builder = Request.Builder()
                    .url(deps.buildUrl("GetAttachment") + "&AttachmentName=" + ref)
                    .post(ByteArray(0).toRequestBody("message/rfc822".toMediaType()))
                    .header("Authorization", deps.getAuthHeader())
                    .header("MS-ASProtocolVersion", deps.getEasVersion())
                    .header("User-Agent", "Android/12-EAS-2.0")
                deps.getPolicyKey()?.let { builder.header("X-MS-PolicyKey", it) }
                return builder.build()
            }
            try {
                var response = deps.executeRequest(request())
                if (response.code == 449) {
                    response.close()
                    when (val prov = deps.provision()) {
                        is EasResult.Success -> response = deps.executeRequest(request())
                        is EasResult.Error -> return@withContext prov.let { EasResult.Error(it.message) }
                    }
                }
                response.use { resp ->
                    if (resp.code == 413) return@withContext EasResult.Error(TOO_LARGE)
                    httpError(resp.code)?.let { return@withContext EasResult.Error(it) }
                    val body = resp.body ?: return@withContext EasResult.Error(NOT_FOUND)
                    val written = open().use { out -> copy(body.byteStream(), out) { job?.ensureActive() } }
                    if (written > 0) EasResult.Success(Unit) else EasResult.Error(NOT_FOUND)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(downloadError(e))
            }
        }

    private fun httpError(code: Int): String? = when {
        code in 200..299 -> null
        code == 401 -> "UNAUTHORIZED"
        code == 403 -> "ACCESS_DENIED"
        code == 449 -> "PROVISION_REQUIRED"
        else -> "HTTP $code"
    }

    private fun downloadError(e: Exception): String = AttachmentErrors.of(e)

    /** ByteArrayOutputStream с пределом: память под вложение не растёт без границы. */
    internal class BoundedBuffer(private val limit: Long) : java.io.ByteArrayOutputStream() {
        class Overflow : java.io.IOException("in-memory attachment over limit")
        override fun write(b: Int) {
            if (count + 1L > limit) throw Overflow()
            super.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (count + len.toLong() > limit) throw Overflow()
            super.write(b, off, len)
        }
    }

    companion object {
        private const val TAG = "EasAttachment"
        private const val CONTENT_TYPE_WBXML = "application/vnd.ms-sync.wbxml"
        /** Пределы письма с вложениями (типичные лимиты Exchange: maxReceiveSize / maxRequestLength). */
        private const val MAX_ATTACHMENTS_BYTES = 7L * 1024 * 1024
        private const val MAX_MIME_BYTES = 10 * 1024 * 1024

        /** Лимит сервера на вложение для EAS (Status 11 / HTTP 413) — файл берётся через EWS. */
        const val TOO_LARGE = AttachmentErrors.TOO_LARGE
        const val NOT_FOUND = AttachmentErrors.NOT_FOUND
        const val MAX_IN_MEMORY_BYTES = 16L * 1024 * 1024
        const val MAX_INLINE_RESPONSE_BYTES = 24L * 1024 * 1024

        fun copy(input: java.io.InputStream, out: java.io.OutputStream, onChunk: () -> Unit): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                onChunk()
                val n = input.read(buffer)
                if (n < 0) return total
                out.write(buffer, 0, n)
                total += n
            }
        }
    }
}

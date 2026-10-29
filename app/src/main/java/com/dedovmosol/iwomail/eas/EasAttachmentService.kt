package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.Dispatchers
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
        val executeEasCommand: suspend (String, String, (String) -> ByteArray) -> EasResult<ByteArray>,
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
        val getDeviceId: () -> String,
        val getDeviceType: () -> String,
        val getUsername: () -> String,
        val getNormalizedServerUrl: () -> String
    )

    companion object {
        private const val CONTENT_TYPE_WBXML = "application/vnd.ms-sync.wbxml"
        /** Пределы письма с вложениями (типичные лимиты Exchange: maxReceiveSize / maxRequestLength). */
        private const val MAX_ATTACHMENTS_BYTES = 7L * 1024 * 1024
        private const val MAX_MIME_BYTES = 10 * 1024 * 1024

        // Regex для парсинга ItemOperations ответов
        private val ITEM_OPS_FETCH_STATUS_REGEX get() = EasPatterns.ITEM_OPS_FETCH_STATUS
        private val ITEM_OPS_DATA_REGEX get() = EasPatterns.ITEM_OPS_DATA
        private val ITEM_OPS_PROPS_DATA_REGEX get() = EasPatterns.ITEM_OPS_PROPS_DATA
    }

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

    /**
     * Скачивание вложения в оперативную память.
     * Для больших файлов (>50 МБ) рекомендуется downloadAttachmentToFile().
     */
    suspend fun downloadAttachment(
        fileReference: String
    ): EasResult<ByteArray> {
        val itemOpsResult = downloadViaItemOperations(fileReference)
        if (itemOpsResult is EasResult.Success) return itemOpsResult

        // Убран broken-fallback «ItemOperations Fetch целого письма»: он возвращал ВЕСЬ MIME письма
        // как «вложение» (TODO извлечения вложения по FileReference из MIME не был реализован) →
        // пользователь получал повреждённый файл (всё письмо). Корректные пути — Fetch по
        // FileReference (выше) и GetAttachment (ниже).
        val getAttResult = downloadViaGetAttachment(fileReference)
        if (getAttResult is EasResult.Success) return getAttResult

        return EasResult.Error("Сервер не поддерживает скачивание вложений через EAS.\n\nВозможные причины:\n• Политика безопасности запрещает скачивание\n• Вложение удалено с сервера\n\nПопробуйте открыть письмо в веб-интерфейсе OWA.")
    }

    /**
     * Скачивание вложения напрямую в файл (streaming, без OOM).
     * GetAttachment (EAS 2.5/12.0/12.1): бинарные данные стримятся через byteStream().
     * ItemOperations (EAS 12.0+): base64 декодируется и пишется в файл.
     */
    suspend fun downloadAttachmentToFile(
        fileReference: String,
        destFile: java.io.File
    ): EasResult<java.io.File> {
        val itemOpsResult = downloadViaItemOperations(fileReference)
        if (itemOpsResult is EasResult.Success && itemOpsResult.data.isNotEmpty()) {
            return writeToFile(itemOpsResult.data, destFile)
        }

        // Убран broken-fallback целого письма (возвращал весь MIME как вложение) — см. downloadAttachment.
        val streamResult = streamViaGetAttachment(fileReference, destFile)
        if (streamResult is EasResult.Success) return streamResult

        return EasResult.Error("Сервер не поддерживает скачивание вложений через EAS.\n\nВозможные причины:\n• Политика безопасности запрещает скачивание\n• Вложение удалено с сервера\n\nПопробуйте открыть письмо в веб-интерфейсе OWA.")
    }

    private fun writeToFile(data: ByteArray, destFile: java.io.File): EasResult<java.io.File> {
        return try {
            destFile.outputStream().use { it.write(data) }
            EasResult.Success(destFile)
        } catch (e: Exception) {
            EasResult.Error("Ошибка записи файла: ${e.message}")
        }
    }

    private suspend fun downloadViaItemOperations(fileReference: String): EasResult<ByteArray> {
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) {
            fileReference
        }

        var result = tryItemOperations(decodedRef)
        if (result is EasResult.Success) {
            return result
        }

        if (decodedRef != fileReference) {
            result = tryItemOperations(fileReference)
            if (result is EasResult.Success) {
                return result
            }
        }

        return result
    }

    private suspend fun tryItemOperations(fileRef: String): EasResult<ByteArray> {
        val safeRef = XmlUtils.escape(fileRef)

        var result = doItemOperationsFetch(EasXmlTemplates.itemOperationsFetchAttachment(safeRef))
        if (result is EasResult.Success && result.data.isNotEmpty()) return result

        result = doItemOperationsFetch(EasXmlTemplates.itemOperationsFetchAttachment(safeRef, withAirSyncBaseNs = false))
        if (result is EasResult.Success && result.data.isNotEmpty()) return result

        result = doItemOperationsFetch(EasXmlTemplates.itemOperationsFetchAttachment(safeRef, range = "0-999999999"))
        if (result is EasResult.Success && result.data.isNotEmpty()) return result

        return result
    }

    private suspend fun doItemOperationsFetch(xml: String): EasResult<ByteArray> {
        var result = deps.executeEasCommand("ItemOperations", xml) { responseXml ->
            parseItemOperationsResponse(responseXml)
        }

        // Обработка HTTP 449 (Provision Required)
        if (result is EasResult.Error && result.message.contains("449")) {
            when (val provResult = deps.provision()) {
                is EasResult.Success -> {
                    result = deps.executeEasCommand("ItemOperations", xml) { responseXml ->
                        parseItemOperationsResponse(responseXml)
                    }
                }
                is EasResult.Error -> return EasResult.Error(provResult.message)
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
        val fetchStatus = ITEM_OPS_FETCH_STATUS_REGEX.find(responseXml)?.groupValues?.get(1)
        if (fetchStatus != "1") {
            return ByteArray(0)
        }

        // Вариант 1: <Data>base64</Data>
        val dataMatch = ITEM_OPS_DATA_REGEX.find(responseXml)
        if (dataMatch != null) {
            val base64Data = dataMatch.groupValues[1].trim()
            try {
                return Base64.decode(base64Data, Base64.DEFAULT)
            } catch (_: Exception) {}
        }

        // Вариант 2: Properties/Data
        val propsMatch = ITEM_OPS_PROPS_DATA_REGEX.find(responseXml)
        if (propsMatch != null) {
            val base64Data = propsMatch.groupValues[1].trim()
            try {
                return Base64.decode(base64Data, Base64.DEFAULT)
            } catch (_: Exception) {}
        }

        return ByteArray(0)
    }

    private suspend fun downloadViaGetAttachment(fileReference: String): EasResult<ByteArray> {
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) {
            fileReference
        }

        val result1 = tryGetAttachment(decodedRef)
        if (result1 is EasResult.Success) return result1

        if (decodedRef != fileReference) {
            val result2 = tryGetAttachment(fileReference)
            if (result2 is EasResult.Success) return result2
        }

        return EasResult.Error("GetAttachment не поддерживается сервером (HTTP 501)")
    }

    private fun buildGetAttachmentRequest(attachmentName: String): Request {
        val serverUrl = deps.getNormalizedServerUrl()
        val username = deps.getUsername()
        val deviceId = deps.getDeviceId()
        val deviceType = deps.getDeviceType()
        val encodedUser = java.net.URLEncoder.encode(username, "UTF-8")
        val url = "$serverUrl/Microsoft-Server-ActiveSync?" +
            "Cmd=GetAttachment&AttachmentName=$attachmentName&User=$encodedUser&DeviceId=$deviceId&DeviceType=$deviceType"

        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", deps.getAuthHeader())
            .header("MS-ASProtocolVersion", deps.getEasVersion())
            .header("User-Agent", "Android/12-EAS-2.0")
        deps.getPolicyKey()?.let { builder.header("X-MS-PolicyKey", it) }
        return builder.build()
    }

    private suspend fun executeGetAttachmentWithProvision(attachmentName: String): Response? {
        val initialResponse = deps.executeRequest(buildGetAttachmentRequest(attachmentName))
        if (initialResponse.code == 449) {
            initialResponse.close()
            return when (deps.provision()) {
                is EasResult.Success -> deps.executeRequest(buildGetAttachmentRequest(attachmentName))
                is EasResult.Error -> null
            }
        }
        return initialResponse
    }

    private suspend fun tryGetAttachment(attachmentName: String): EasResult<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val response = executeGetAttachmentWithProvision(attachmentName)
                    ?: return@withContext EasResult.Error("Provision failed")

                response.use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext EasResult.Error("HTTP ${resp.code}: ${resp.message}")
                    }
                    val data = resp.body?.bytes()
                    if (data == null || data.isEmpty()) {
                        return@withContext EasResult.Error("Пустой ответ")
                    }
                    EasResult.Success(data)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Ошибка: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
            }
        }
    }

    /**
     * GetAttachment стриминг прямо в файл (без промежуточного ByteArray).
     * MS-ASCMD 2.2.1.7: GetAttachment возвращает бинарные данные вложения напрямую.
     * Поддерживается ТОЛЬКО в EAS 2.5/12.0/12.1.
     */
    private suspend fun streamViaGetAttachment(
        fileReference: String,
        destFile: java.io.File
    ): EasResult<java.io.File> {
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) { fileReference }

        val result = streamGetAttachmentToFile(decodedRef, destFile)
        if (result is EasResult.Success) return result

        if (decodedRef != fileReference) {
            val result2 = streamGetAttachmentToFile(fileReference, destFile)
            if (result2 is EasResult.Success) return result2
        }
        return EasResult.Error("GetAttachment failed")
    }

    private suspend fun streamGetAttachmentToFile(
        attachmentName: String,
        destFile: java.io.File
    ): EasResult<java.io.File> {
        return withContext(Dispatchers.IO) {
            try {
                val response = executeGetAttachmentWithProvision(attachmentName)
                    ?: return@withContext EasResult.Error("Provision failed")

                response.use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext EasResult.Error("HTTP ${resp.code}: ${resp.message}")
                    }
                    val body = resp.body
                        ?: return@withContext EasResult.Error("Пустой ответ")

                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }
                    if (destFile.length() == 0L) {
                        return@withContext EasResult.Error("Пустой ответ")
                    }
                    EasResult.Success(destFile)
                }
            } catch (e: Exception) {
                destFile.delete()
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Ошибка: ${com.dedovmosol.iwomail.network.ConnectionFailure.describe(e)}")
            }
        }
    }
}

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
 * - Отправку MDN (уведомлений о прочтении)
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
        val getFromEmail: () -> String,
        val getDeviceId: () -> String,
        val getDeviceType: () -> String,
        val getUsername: () -> String,
        val getNormalizedServerUrl: () -> String
    )
    
    companion object {
        private const val CONTENT_TYPE_WBXML = "application/vnd.ms-sync.wbxml"
        
        // Regex для парсинга ItemOperations ответов
        private val ITEM_OPS_GLOBAL_STATUS_REGEX get() = EasPatterns.ITEM_OPS_GLOBAL_STATUS
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
            
            val url = deps.buildUrl("SendMail") + "&SaveInSent=T"
            val easVersion = deps.getEasVersion()
            val majorVersion = easVersion.substringBefore(".").toIntOrNull() ?: 12
            
            // Для EAS 14.0+ используем WBXML формат, для 12.x - message/rfc822
            val (requestBody, contentType) = if (majorVersion >= 14) {
                val clientId = System.currentTimeMillis().toString()
                val wbxml = deps.wbxmlGenerateSendMail(clientId, mimeBytes)
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
            
            val response = deps.executeRequest(requestBuilder.build())
            
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
        importance: Int = 1
    ): ByteArray {
        val fromEmail = deps.getFromEmail()
        val deviceId = deps.getDeviceId()
        
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
                sb.append("Content-Type: image/$imageType\r\n")
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
    
    // ==================== MDN (Message Disposition Notification) ====================
    
    /**
     * Отправка отчёта о прочтении (MDN)
     */
    suspend fun sendMdn(
        to: String,
        originalSubject: String,
        originalMessageId: String? = null
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val mimeBytes = buildMdnMessage(to, originalSubject, originalMessageId)
            val url = deps.buildUrl("SendMail") + "&SaveInSent=F" // Не сохраняем MDN в Отправленные
            val contentType = "message/rfc822"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(mimeBytes.toRequestBody(contentType.toMediaType()))
                .header("Authorization", deps.getAuthHeader())
                .header("MS-ASProtocolVersion", deps.getEasVersion())
                .header("Content-Type", contentType)
                .header("User-Agent", "Android/12-EAS-2.0")
            
            deps.getPolicyKey()?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val response = deps.executeRequest(requestBuilder.build())
            
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
        val fromEmail = deps.getFromEmail()
        val deviceId = deps.getDeviceId()
        
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
    
    // ==================== СКАЧИВАНИЕ ВЛОЖЕНИЙ ====================
    
    /**
     * Скачивание вложения
     * Пробует несколько методов: ItemOperations, Fetch email, GetAttachment
     */
    suspend fun downloadAttachment(
        fileReference: String, 
        collectionId: String? = null, 
        serverId: String? = null
    ): EasResult<ByteArray> {
        // Метод 1: ItemOperations (стандартный)
        val itemOpsResult = downloadViaItemOperations(fileReference)
        if (itemOpsResult is EasResult.Success) {
            return itemOpsResult
        }
        
        // Метод 2: Fetch всего письма и извлечение вложения
        if (collectionId != null && serverId != null) {
            val fetchResult = downloadViaItemOperationsFetchEmail(collectionId, serverId, fileReference)
            if (fetchResult is EasResult.Success) {
                return fetchResult
            }
        }
        
        // Метод 3: GetAttachment (устаревший, для Exchange 2007)
        val getAttResult = downloadViaGetAttachment(fileReference)
        if (getAttResult is EasResult.Success) {
            return getAttResult
        }
        
        return EasResult.Error("Сервер не поддерживает скачивание вложений через EAS.\n\nВозможные причины:\n• Политика безопасности запрещает скачивание\n• Вложение удалено с сервера\n\nПопробуйте открыть письмо в веб-интерфейсе OWA.")
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
        // Вариант 1: FileReference в namespace AirSyncBase
        val xml1 = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <FileReference xmlns="AirSyncBase">$fileRef</FileReference>
    </Fetch>
</ItemOperations>""".trimIndent()
        
        var result = doItemOperationsFetch(xml1)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        // Вариант 2: FileReference без namespace
        val xml2 = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <FileReference>$fileRef</FileReference>
    </Fetch>
</ItemOperations>""".trimIndent()
        
        result = doItemOperationsFetch(xml2)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
        // Вариант 3: С Options для указания Range
        val xml3 = """<?xml version="1.0" encoding="UTF-8"?>
<ItemOperations xmlns="ItemOperations">
    <Fetch>
        <Store>Mailbox</Store>
        <FileReference xmlns="AirSyncBase">$fileRef</FileReference>
        <Options>
            <Range>0-999999999</Range>
        </Options>
    </Fetch>
</ItemOperations>""".trimIndent()
        
        result = doItemOperationsFetch(xml3)
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            return result
        }
        
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
    
    private suspend fun downloadViaItemOperationsFetchEmail(
        collectionId: String, 
        serverId: String,
        fileReference: String
    ): EasResult<ByteArray> {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
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
</ItemOperations>""".trimIndent()
        
        val result = doItemOperationsFetch(xml)
        
        if (result is EasResult.Success && result.data.isNotEmpty()) {
            // TODO: Парсить MIME и извлекать вложение по индексу
            return result
        }
        
        return result
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
    
    private suspend fun tryGetAttachment(attachmentName: String): EasResult<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = deps.getNormalizedServerUrl()
                val username = deps.getUsername()
                val deviceId = deps.getDeviceId()
                val deviceType = deps.getDeviceType()
                
                val url = "$serverUrl/Microsoft-Server-ActiveSync?" +
                    "Cmd=GetAttachment&AttachmentName=$attachmentName&User=$username&DeviceId=$deviceId&DeviceType=$deviceType"
                
                val requestBuilder = Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", deps.getAuthHeader())
                    .header("MS-ASProtocolVersion", deps.getEasVersion())
                    .header("User-Agent", "Android/12-EAS-2.0")
                
                deps.getPolicyKey()?.let { requestBuilder.header("X-MS-PolicyKey", it) }
                
                var response = deps.executeRequest(requestBuilder.build())
                
                // Обработка 449 (Provision Required)
                if (response.code == 449) {
                    when (val provResult = deps.provision()) {
                        is EasResult.Success -> {
                            val retryBuilder = Request.Builder()
                                .url(url)
                                .get()
                                .header("Authorization", deps.getAuthHeader())
                                .header("MS-ASProtocolVersion", deps.getEasVersion())
                                .header("User-Agent", "Android/12-EAS-2.0")
                            deps.getPolicyKey()?.let { retryBuilder.header("X-MS-PolicyKey", it) }
                            response = deps.executeRequest(retryBuilder.build())
                        }
                        is EasResult.Error -> return@withContext EasResult.Error(provResult.message)
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
}

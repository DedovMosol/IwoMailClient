package com.dedovmosol.iwomail.eas

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import android.util.Base64

// Предкомпилированные regex для производительности
private val FETCH_STATUS_REGEX = "<Fetch>.*?<Status>(\\d+)</Status>".toRegex(RegexOption.DOT_MATCHES_ALL)
private val DATA_REGEX = "<Data>(.*?)</Data>".toRegex(RegexOption.DOT_MATCHES_ALL)
private val SAFE_FILENAME_REGEX = Regex("[\\\\/:*?\"<>|]")

/**
 * Менеджер для работы с вложениями Exchange
 * Использует общий HttpClientProvider для предотвращения утечек памяти
 */
class AttachmentManager(
    private val context: Context,
    serverUrl: String,
    private val username: String,
    private val password: String,
    private val domain: String = "",
    private val acceptAllCerts: Boolean = false,
    private val port: Int = 443,
    private val useHttps: Boolean = true,
    private val policyKey: String? = null,
    deviceIdSuffix: String = "",
    private val easVersion: String = "12.1"
) {
    private val client: OkHttpClient = com.dedovmosol.iwomail.network.HttpClientProvider.getAttachmentClient(acceptAllCerts)
    private val wbxmlParser = WbxmlParser()
    private val deviceId = generateStableDeviceId(username, deviceIdSuffix)
    private val normalizedServerUrl: String = EasClient.normalizeUrl(serverUrl, port, useHttps)
    
    private fun generateStableDeviceId(username: String, suffix: String): String {
        val hash = (username + suffix).hashCode().toLong() and 0xFFFFFFFFL
        return "androidc${String.format("%010d", hash % 10000000000L)}"
    }
    
    companion object {
        private const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val MAX_FILE_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        fun cleanupOldAttachments(context: Context) {
            try {
                val dir = File(context.filesDir, "attachments")
                if (!dir.exists()) return
                val files = dir.listFiles() ?: return
                val now = System.currentTimeMillis()
                var totalSize = files.sumOf { it.length() }

                val sorted = files.sortedBy { it.lastModified() }
                for (file in sorted) {
                    val tooOld = now - file.lastModified() > MAX_FILE_AGE_MS
                    val overSize = totalSize > MAX_CACHE_SIZE_BYTES
                    if (tooOld || overSize) {
                        val size = file.length()
                        if (file.delete()) totalSize -= size
                    } else if (!overSize) {
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        fun getFileIcon(fileName: String): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "doc", "docx" -> "📄"
                "xls", "xlsx" -> "📊"
                "ppt", "pptx" -> "📽️"
                "pdf" -> "📕"
                "txt" -> "📝"
                "jpg", "jpeg", "png", "gif", "bmp" -> "🖼️"
                "mp3", "wav", "ogg" -> "🎵"
                "mp4", "avi", "mkv", "mov" -> "🎬"
                "zip", "rar", "7z", "tar", "gz" -> "📦"
                "msg", "eml" -> "✉️"
                else -> "📎"
            }
        }
    }
    
    private fun getAuthHeader(): String {
        val credentials = if (domain.isNotEmpty()) {
            "$domain\\$username:$password"
        } else {
            "$username:$password"
        }
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }
    
    /**
     * Скачивает вложение с сервера Exchange
     * Пробует несколько методов: ItemOperations с разными вариантами XML
     */
    suspend fun downloadAttachment(
        fileReference: String,
        fileName: String,
        onProgress: (Int) -> Unit = {}
    ): EasResult<File> = withContext(Dispatchers.IO) {
        // Декодируем FileReference если он URL-encoded
        val decodedRef = try {
            java.net.URLDecoder.decode(fileReference, "UTF-8")
        } catch (e: Exception) {
            fileReference
        }
        val safeRef = XmlUtils.escape(decodedRef)
        val variants = listOf(
            EasXmlTemplates.itemOperationsFetchAttachment(safeRef),
            EasXmlTemplates.itemOperationsFetchAttachment(safeRef, withAirSyncBaseNs = false),
            EasXmlTemplates.itemOperationsFetchAttachment(safeRef, range = "0-999999999")
        )
        
        for ((index, xml) in variants.withIndex()) {
            val result = tryDownload(xml, fileName, onProgress)
            if (result is EasResult.Success) {
                return@withContext result
            }
        }
        
        // Все варианты не сработали
        EasResult.Error("Сервер не поддерживает скачивание вложений через EAS.\n\nВозможные причины:\n• Политика безопасности запрещает скачивание\n• Вложение удалено с сервера\n\nПопробуйте открыть письмо в веб-интерфейсе OWA.")
    }
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun tryDownload(
        xml: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): EasResult<File> = withContext(Dispatchers.IO) {
        try {
            val wbxml = wbxmlParser.generate(xml)
            val encodedUser = if (domain.isNotEmpty()) {
                java.net.URLEncoder.encode("$domain\\$username", "UTF-8")
            } else {
                java.net.URLEncoder.encode(username, "UTF-8")
            }
            val url = "$normalizedServerUrl/Microsoft-Server-ActiveSync?" +
                "Cmd=ItemOperations&User=$encodedUser&DeviceId=$deviceId&DeviceType=Android"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(wbxml.toRequestBody("application/vnd.ms-sync.wbxml".toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("MS-ASProtocolVersion", easVersion)
                .header("Content-Type", "application/vnd.ms-sync.wbxml")
                .header("User-Agent", "Android/12-EAS-2.0")
            
            // Добавляем PolicyKey если есть
            policyKey?.let { key ->
                requestBuilder.header("X-MS-PolicyKey", key)
            }
            
            val request = requestBuilder.build()
            val call = client.newCall(request)
            val response = suspendCancellableCoroutine<Response> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response) { response.close() }
                    }
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        if (!cont.isCancelled) cont.resumeWithException(e)
                    }
                })
            }
            response.use { resp ->
            if (resp.code == 449) {
                return@withContext EasResult.Error("Требуется повторная авторизация (449)")
            }
            
            if (!resp.isSuccessful) {
                return@withContext EasResult.Error("HTTP ${resp.code}")
            }
            
            val responseBody = resp.body?.bytes()
            if (responseBody == null || responseBody.isEmpty()) {
                return@withContext EasResult.Error("Пустой ответ")
            }
            
            val responseXml = wbxmlParser.parse(responseBody)
            
            val fetchStatus = FETCH_STATUS_REGEX.find(responseXml)?.groupValues?.get(1)
            if (fetchStatus != "1") {
                return@withContext EasResult.Error("Вложение не найдено (Status=$fetchStatus)")
            }
            
            val dataMatch = DATA_REGEX.find(responseXml)
            
            if (dataMatch == null) {
                return@withContext EasResult.Error("Данные не найдены в ответе")
            }
            
            val base64Data = dataMatch.groupValues[1].trim()
            val fileData = Base64.decode(base64Data, Base64.DEFAULT)
            // Сохраняем файл
            val attachmentsDir = File(context.filesDir, "attachments")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            
            val safeFileName = SAFE_FILENAME_REGEX.replace(fileName, "_")
            val file = File(attachmentsDir, "${System.currentTimeMillis()}_$safeFileName")
            
            FileOutputStream(file).use { fos ->
                fos.write(fileData)
            }
            
            onProgress(100)
            EasResult.Success(file)
            } // response.use
            
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка: ${e.message}")
        }
    }
    
    /**
     * Открывает файл через системное приложение
     */
    fun openFile(file: File): Intent? {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val mimeType = getMimeType(file.name)
            
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Делится файлом через системный диалог
     */
    fun shareFile(file: File): Intent? {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val mimeType = getMimeType(file.name)
            
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "zip" -> "application/zip"
                "rar" -> "application/x-rar-compressed"
                "7z" -> "application/x-7z-compressed"
                "msg" -> "application/vnd.ms-outlook"
                "eml" -> "message/rfc822"
                else -> "application/octet-stream"
            }
    }
}


package com.iwo.mailclient.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.iwo.mailclient.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Информация об обновлении
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val minSdk: Int = 24
)

/**
 * Результат проверки обновлений
 */
sealed class UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult()
    object UpToDate : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

/**
 * Прогресс скачивания
 */
sealed class DownloadProgress {
    object Starting : DownloadProgress()
    data class Downloading(val progress: Int, val downloadedMb: Float, val totalMb: Float) : DownloadProgress()
    data class Completed(val file: File) : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
}

/**
 * Сервис проверки и установки обновлений
 */
class UpdateChecker(private val context: Context) {
    
    companion object {
        // URL к JSON файлу с информацией об обновлении
        private const val UPDATE_URL = "https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json"
        
        private const val UPDATES_DIR = "updates"
        private const val APK_FILENAME = "update.apk"
    }
    
    // Используем общий HttpClient для предотвращения утечек памяти
    private val client = com.iwo.mailclient.network.HttpClientProvider.getClient()
    
    /**
     * Определяет архитектуру устройства
     */
    private fun getDeviceArch(): String {
        val supportedAbis = Build.SUPPORTED_ABIS
        return when {
            supportedAbis.contains("arm64-v8a") -> "arm64-v8a"
            supportedAbis.contains("armeabi-v7a") -> "armeabi-v7a"
            supportedAbis.contains("x86_64") -> "x86_64"
            supportedAbis.contains("x86") -> "x86"
            else -> "universal"
        }
    }
    
    /**
     * Проверяет наличие обновлений
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UPDATE_URL)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext UpdateResult.Error("HTTP ${response.code}")
            }
            
            val body = response.body?.string() ?: return@withContext UpdateResult.Error("Empty response")
            val json = JSONObject(body)
            
            // Определяем архитектуру и выбираем нужный URL
            val arch = getDeviceArch()
            val apkUrls = json.optJSONObject("apkUrls")
            val apkUrl = apkUrls?.optString(arch)?.takeIf { it.isNotBlank() }
                ?: apkUrls?.optString("universal")?.takeIf { it.isNotBlank() }
                ?: json.optString("apkUrl") // fallback на старый формат
            
            if (apkUrl.isBlank()) {
                return@withContext UpdateResult.Error("No APK URL for $arch")
            }
            
            val updateInfo = UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = apkUrl,
                changelog = json.optString("changelog", ""),
                minSdk = json.optInt("minSdk", 24)
            )
            
            // Проверяем совместимость с SDK
            if (Build.VERSION.SDK_INT < updateInfo.minSdk) {
                return@withContext UpdateResult.UpToDate
            }
            
            // Сравниваем версии
            if (updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                UpdateResult.Available(updateInfo)
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Скачивает APK с прогрессом
     */
    fun downloadUpdate(apkUrl: String): Flow<DownloadProgress> = kotlinx.coroutines.flow.channelFlow {
        send(DownloadProgress.Starting)
        
        try {
            val request = Request.Builder()
                .url(apkUrl)
                .build()
            
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            
            if (!response.isSuccessful) {
                send(DownloadProgress.Error("HTTP ${response.code}"))
                return@channelFlow
            }
            
            val body = response.body ?: run {
                send(DownloadProgress.Error("Empty response"))
                return@channelFlow
            }
            
            val contentLength = body.contentLength()
            val totalMb = if (contentLength > 0) contentLength / (1024f * 1024f) else 0f
            
            // Создаём директорию для обновлений
            val updatesDir = File(context.filesDir, UPDATES_DIR)
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }
            
            // Удаляем старый APK если есть
            val apkFile = File(updatesDir, APK_FILENAME)
            if (apkFile.exists()) {
                apkFile.delete()
            }
            
            // Скачиваем с прогрессом
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        var lastProgress = -1
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            
                            val progress = if (contentLength > 0) {
                                ((totalBytesRead * 100) / contentLength).toInt()
                            } else {
                                -1
                            }
                            
                            // Эмитим прогресс каждые 2%
                            if (progress != lastProgress && (progress % 2 == 0 || progress == 100)) {
                                lastProgress = progress
                                val downloadedMb = totalBytesRead / (1024f * 1024f)
                                trySend(DownloadProgress.Downloading(progress, downloadedMb, totalMb))
                            }
                        }
                    }
                }
            }
            
            send(DownloadProgress.Completed(apkFile))
            
        } catch (e: Exception) {
            send(DownloadProgress.Error(e.message ?: "Download failed"))
        }
    }
    
    /**
     * Запускает установку APK
     */
    fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        
        context.startActivity(intent)
    }
    
    /**
     * Очищает скачанные файлы обновлений
     */
    fun clearUpdateFiles() {
        val updatesDir = File(context.filesDir, UPDATES_DIR)
        if (updatesDir.exists()) {
            updatesDir.listFiles()?.forEach { it.delete() }
        }
    }
}

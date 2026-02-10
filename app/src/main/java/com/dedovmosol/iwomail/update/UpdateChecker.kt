package com.dedovmosol.iwomail.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.dedovmosol.iwomail.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
 * Информация о предыдущей версии (для отката)
 */
data class PreviousVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val missingFeatures: List<String>,
    val lostData: List<String>
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
 * Результат проверки предыдущей версии
 */
sealed class PreviousVersionResult {
    data class Available(val info: PreviousVersionInfo) : PreviousVersionResult()
    object NotAvailable : PreviousVersionResult()
    data class Error(val message: String) : PreviousVersionResult()
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
        private const val MAX_RETRY_ATTEMPTS = 2
    }
    
    // Используем общий HttpClient для предотвращения утечек памяти
    private val client = com.dedovmosol.iwomail.network.HttpClientProvider.getClient()
    
    // Клиент с увеличенными таймаутами для проверки обновлений (для медленного мобильного интернета)
    private val updateClient = client.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    
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
     * @param isRussian true для русского changelog, false для английского
     */
    suspend fun checkForUpdate(isRussian: Boolean = true): UpdateResult = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        
        // Пробуем несколько раз при ошибке (для медленного мобильного интернета)
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(UPDATE_URL)
                    .build()
                
                val response = updateClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    lastError = Exception("HTTP ${response.code}")
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        kotlinx.coroutines.delay(1000L * (attempt + 1)) // 1s, 2s задержка
                        return@repeat
                    }
                    return@withContext UpdateResult.Error("HTTP ${response.code}")
                }
                
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    lastError = Exception("Empty response")
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        kotlinx.coroutines.delay(1000L * (attempt + 1))
                        return@repeat
                    }
                    return@withContext UpdateResult.Error("Empty response")
                }
                
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
                
                // Выбираем changelog по языку (с fallback на старый формат)
                val changelog = if (isRussian) {
                    json.optString("changelogRu", "").takeIf { it.isNotBlank() }
                        ?: json.optString("changelog", "")
                } else {
                    json.optString("changelogEn", "").takeIf { it.isNotBlank() }
                        ?: json.optString("changelog", "")
                }
                
                val updateInfo = UpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = apkUrl,
                    changelog = changelog,
                    minSdk = json.optInt("minSdk", 24)
                )
                
                // Проверяем совместимость с SDK
                if (Build.VERSION.SDK_INT < updateInfo.minSdk) {
                    return@withContext UpdateResult.UpToDate
                }
                
                // Сравниваем версии
                return@withContext if (updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateResult.Available(updateInfo)
                } else {
                    UpdateResult.UpToDate
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
        }
        
        UpdateResult.Error(lastError?.message ?: "Unknown error")
    }
    
    /**
     * Проверяет наличие предыдущей версии для отката
     * @param isRussian true для русских описаний, false для английских
     */
    suspend fun checkPreviousVersion(isRussian: Boolean = true): PreviousVersionResult = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        
        // Пробуем несколько раз при ошибке (для медленного мобильного интернета)
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(UPDATE_URL)
                    .build()
                
                val response = updateClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    lastError = Exception("HTTP ${response.code}")
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        kotlinx.coroutines.delay(1000L * (attempt + 1))
                        return@repeat
                    }
                    return@withContext PreviousVersionResult.Error("HTTP ${response.code}")
                }
                
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    lastError = Exception("Empty response")
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        kotlinx.coroutines.delay(1000L * (attempt + 1))
                        return@repeat
                    }
                    return@withContext PreviousVersionResult.Error("Empty response")
                }
                
                val json = JSONObject(body)
                
                // Проверяем наличие секции previous
                val previousJson = json.optJSONObject("previous")
                    ?: return@withContext PreviousVersionResult.NotAvailable
                
                // Определяем архитектуру и выбираем нужный URL
                val arch = getDeviceArch()
                val apkUrls = previousJson.optJSONObject("apkUrls")
                val apkUrl = apkUrls?.optString(arch)?.takeIf { it.isNotBlank() }
                    ?: apkUrls?.optString("universal")?.takeIf { it.isNotBlank() }
                    ?: previousJson.optString("apkUrl")
                
                if (apkUrl.isBlank()) {
                    return@withContext PreviousVersionResult.NotAvailable
                }
                
                // Парсим missingFeatures (по языку)
                val missingFeaturesKey = if (isRussian) "missingFeaturesRu" else "missingFeaturesEn"
                val missingFeaturesArray = previousJson.optJSONArray(missingFeaturesKey)
                    ?: previousJson.optJSONArray("missingFeatures")
                val missingFeatures = mutableListOf<String>()
                if (missingFeaturesArray != null) {
                    for (i in 0 until missingFeaturesArray.length()) {
                        missingFeatures.add(missingFeaturesArray.getString(i))
                    }
                }
                
                // Парсим lostData (по языку)
                val lostDataKey = if (isRussian) "lostDataRu" else "lostDataEn"
                val lostDataArray = previousJson.optJSONArray(lostDataKey)
                    ?: previousJson.optJSONArray("lostData")
                val lostData = mutableListOf<String>()
                if (lostDataArray != null) {
                    for (i in 0 until lostDataArray.length()) {
                        lostData.add(lostDataArray.getString(i))
                    }
                }
                
                val previousInfo = PreviousVersionInfo(
                    versionCode = previousJson.getInt("versionCode"),
                    versionName = previousJson.getString("versionName"),
                    apkUrl = apkUrl,
                    missingFeatures = missingFeatures,
                    lostData = lostData
                )
                
                // Проверяем что предыдущая версия действительно старше текущей
                return@withContext if (previousInfo.versionCode < BuildConfig.VERSION_CODE) {
                    PreviousVersionResult.Available(previousInfo)
                } else {
                    PreviousVersionResult.NotAvailable
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
        }
        
        PreviousVersionResult.Error(lastError?.message ?: "Unknown error")
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
     * @param apkFile файл APK для установки
     * @param isDowngrade true если это откат к предыдущей версии
     */
    fun installApk(apkFile: File, isDowngrade: Boolean = false) {
        if (isDowngrade && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            // Для downgrade используем PackageInstaller API
            installApkWithPackageInstaller(apkFile, allowDowngrade = true)
        } else {
            // Для обычной установки используем стандартный Intent
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
    }
    
    /**
     * Устанавливает APK через PackageInstaller API
     * Поддерживает downgrade для Android 8+
     */
    private fun installApkWithPackageInstaller(apkFile: File, allowDowngrade: Boolean = false) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            
            if (allowDowngrade) {
                // Для Android 10+ (API 29+) используем официальный метод
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    try {
                        // Используем reflection чтобы избежать ошибки компиляции
                        val method = params.javaClass.getMethod("setRequestDowngrade", Boolean::class.java)
                        method.invoke(params, true)
                        android.util.Log.d("UpdateChecker", "Downgrade flag set via official API for API ${android.os.Build.VERSION.SDK_INT}")
                    } catch (e: Exception) {
                        android.util.Log.w("UpdateChecker", "Failed to call setRequestDowngrade: ${e.message}")
                    }
                }
                // Для Android 8-9 (API 26-28) используем reflection для установки флага
                else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        // Пытаемся установить флаг INSTALL_ALLOW_DOWNGRADE через reflection
                        val field = params.javaClass.getDeclaredField("installFlags")
                        field.isAccessible = true
                        var flags = field.getInt(params)
                        // INSTALL_ALLOW_DOWNGRADE = 0x00000080
                        flags = flags or 0x00000080
                        field.setInt(params, flags)
                        android.util.Log.d("UpdateChecker", "Downgrade flag set via reflection for API ${android.os.Build.VERSION.SDK_INT}")
                    } catch (e: Exception) {
                        android.util.Log.w("UpdateChecker", "Failed to set downgrade flag via reflection: ${e.message}")
                    }
                }
            }
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            // Копируем APK в сессию
            session.openWrite("package", 0, -1).use { outputStream ->
                apkFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                session.fsync(outputStream)
            }
            
            // Создаём Intent для результата установки
            val intent = Intent(context, context.javaClass).apply {
                action = "com.dedovmosol.iwomail.INSTALL_COMPLETE"
            }
            
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
            
            // Запускаем установку
            session.commit(pendingIntent.intentSender)
            session.close()
            
        } catch (e: Exception) {
            android.util.Log.e("UpdateChecker", "PackageInstaller error: ${e.message}", e)
            // Fallback на стандартный метод
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

package com.dedovmosol.iwomail.update

import android.app.NotificationChannel
import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.dedovmosol.iwomail.BuildConfig
import com.dedovmosol.iwomail.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
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
class UpdateChecker(context: Context) {
    private val context: Context = context.applicationContext
    
    companion object {
        // URL к JSON файлу с информацией об обновлении
        private const val UPDATE_URL = "https://raw.githubusercontent.com/DedovMosol/IwoMailClient/main/update.json"
        
        private const val UPDATES_DIR = "updates"
        private const val APK_FILENAME = "update.apk"
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val ROLLBACK_NOTIFICATION_ID = 9999
        private const val ROLLBACK_SUBFOLDER = "iwomail rollback"

        // Single-flight защита для автопроверки OTA (между параллельными MainScreen/Activity)
        private val autoCheckMutex = Mutex()

        /**
         * Запускает блок автопроверки OTA в single-flight режиме.
         * Если параллельная автопроверка уже идёт — вернёт null и пропустит дублирующий запуск.
         */
        suspend fun <T> runAutoCheckSingleFlight(block: suspend () -> T): T? {
            if (!autoCheckMutex.tryLock()) return null
            return try {
                block()
            } finally {
                autoCheckMutex.unlock()
            }
        }
    }
    
    // Используем общий HttpClient для предотвращения утечек памяти
    private val client = com.dedovmosol.iwomail.network.HttpClientProvider.getClient()
    
    // Клиент с увеличенными таймаутами для проверки обновлений (для медленного мобильного интернета)
    private val updateClient = client.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    
    // Клиент для скачивания APK — без callTimeout (файл может быть большим)
    private val downloadClient = client.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS) // без ограничения на весь вызов
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
     * Скачивает APK с прогрессом.
     * Использует downloadClient с увеличенными таймаутами и без callTimeout.
     * При ошибке делает одну повторную попытку.
     */
    fun downloadUpdate(apkUrl: String): Flow<DownloadProgress> = kotlinx.coroutines.flow.channelFlow {
        send(DownloadProgress.Starting)
        
        var lastError: String? = null
        
        for (attempt in 0 until MAX_RETRY_ATTEMPTS) {
            if (attempt > 0) {
                android.util.Log.w("UpdateChecker", "Download retry attempt ${attempt + 1}, previous error: $lastError")
                kotlinx.coroutines.delay(2000L * attempt)
                send(DownloadProgress.Starting)
            }
            
            try {
                val request = Request.Builder()
                    .url(apkUrl)
                    .build()
                
                val response = withContext(Dispatchers.IO) {
                    downloadClient.newCall(request).execute()
                }
                
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    android.util.Log.w("UpdateChecker", "Download failed: HTTP $code, url=$apkUrl")
                    // 404 = файл не существует на сервере, retry бессмысленен
                    if (code == 404) {
                        send(DownloadProgress.Error("HTTP 404: file not found"))
                        return@channelFlow
                    }
                    lastError = "HTTP $code"
                    continue
                }
                
                val body = response.body
                if (body == null) {
                    response.close()
                    lastError = "Empty response"
                    continue
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
                var totalBytesRead = 0L
                withContext(Dispatchers.IO) {
                    response.use {
                        body.byteStream().use { input ->
                            apkFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
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
                }
                
                // Проверяем что файл скачан полностью
                if (contentLength > 0 && totalBytesRead != contentLength) {
                    lastError = "Incomplete download: $totalBytesRead/$contentLength bytes"
                    android.util.Log.w("UpdateChecker", lastError!!)
                    apkFile.delete()
                    continue
                }
                
                // Проверяем что файл не пустой
                if (!apkFile.exists() || apkFile.length() == 0L) {
                    lastError = "Downloaded file is empty"
                    android.util.Log.w("UpdateChecker", lastError!!)
                    continue
                }
                
                send(DownloadProgress.Completed(apkFile))
                return@channelFlow
                
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.w("UpdateChecker", "Download attempt ${attempt + 1} failed: $lastError")
                // Удаляем частично скачанный файл
                val apkFile = File(File(context.filesDir, UPDATES_DIR), APK_FILENAME)
                if (apkFile.exists()) apkFile.delete()
            }
        }
        
        send(DownloadProgress.Error(lastError ?: "Download failed"))
    }
    
    /**
     * Запускает установку APK
     * @param apkFile файл APK для установки
     * @param isDowngrade true если это откат к предыдущей версии
     */
    fun installApk(apkFile: File, isDowngrade: Boolean = false) {
        if (isDowngrade) {
            // Android НЕ позволяет обычным приложениям устанавливать APK с меньшим versionCode.
            // INSTALL_ALLOW_DOWNGRADE — internal API, работает только для system apps / adb.
            // Единственный 100% рабочий способ:
            // 1. Копируем APK в Downloads (переживёт удаление приложения)
            // 2. Запускаем удаление текущего приложения
            // 3. После удаления пользователь открывает iwomail-rollback-vX.X.X.apk из Downloads/iwomail rollback/
            installDowngrade(apkFile)
        } else {
            // Для обычного обновления (versionCode выше) — стандартный ACTION_VIEW
            installApkWithActionView(apkFile)
        }
    }
    
    /**
     * Проверяет, существует ли APK отката в папке Downloads/iwomail rollback/.
     * @return true если файл уже существует
     */
    fun checkApkExistsInDownloads(versionName: String): Boolean {
        val apkFileName = "iwomail-rollback-v${versionName}.apk"
        return try {
            // Прямая проверка файла — MediaStore query на Android 10+ ненадёжен:
            // не видит файлы от предыдущей установки и pending-файлы.
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetFile = File(File(downloadsDir, ROLLBACK_SUBFOLDER), apkFileName)
            if (targetFile.exists() && targetFile.length() > 0) return true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$ROLLBACK_SUBFOLDER/"
                val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(apkFileName, relPath)
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor -> cursor.count > 0 } ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("UpdateChecker", "checkApkExistsInDownloads error: ${e.message}")
            false
        }
    }
    
    /**
     * Подготавливает APK для отката — копирует в Downloads.
     * Вызывается из UI перед показом диалога с инструкцией.
     * @return true если APK успешно скопирован в Downloads
     */
    fun prepareDowngrade(apkFile: File, versionName: String): Boolean {
        return try {
            val uri = copyApkToDownloads(apkFile, versionName)
            if (uri != null) {
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("UpdateChecker", "prepareDowngrade error: ${e.message}", e)
            false
        }
    }
    
    /**
     * Запускает удаление текущего приложения (для отката).
     * Вызывается из UI после того как пользователь прочитал инструкцию.
     */
    fun requestUninstall() {
        val packageUri = Uri.parse("package:${context.packageName}")
        val uninstallIntent = Intent(Intent.ACTION_DELETE, packageUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(uninstallIntent)
        } catch (e: Exception) {
            // Fallback для OEM-прошивок: открываем экран приложения, где пользователь
            // может нажать "Удалить" вручную.
            val detailsIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(detailsIntent)
        }
    }

    /**
     * Открывает папку Downloads/iwomail rollback/ в системном файловом менеджере.
     *
     * Стратегия (от точной к общей):
     * 1. ACTION_VIEW с content:// URI подпапки → открывает Files прямо в ней
     * 2. Fallback: ACTION_VIEW на корень Downloads
     * 3. Fallback: ACTION_VIEW_DOWNLOADS
     *
     * FLAG_ACTIVITY_NEW_TASK без FLAG_ACTIVITY_NEW_DOCUMENT — файловый менеджер
     * остаётся в фоне при возврате в приложение (не привязан к нашему task).
     */
    fun openDownloads(): Boolean {
        val subfolderDocId = "primary:${Environment.DIRECTORY_DOWNLOADS}/$ROLLBACK_SUBFOLDER"
        val subfolderUri = android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", subfolderDocId
        )
        val folderIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(subfolderUri, "vnd.android.document/directory")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        return try {
            context.startActivity(folderIntent)
            true
        } catch (_: Exception) {
            val downloadsDocId = "primary:${Environment.DIRECTORY_DOWNLOADS}"
            val downloadsUri = android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", downloadsDocId
            )
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(downloadsUri, "vnd.android.document/directory")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            try {
                context.startActivity(fallbackIntent)
                true
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                } catch (e: Exception) {
                    android.util.Log.w("UpdateChecker", "openDownloads all attempts failed: ${e.message}")
                    false
                }
            }
        }
    }
    
    /**
     * Полноценный откат к предыдущей версии.
     *
     * Алгоритм:
     * 1. Копируем APK в папку Downloads через MediaStore (публичная, переживёт удаление приложения)
     * 2. Показываем Toast с инструкцией
     * 3. Запускаем ACTION_DELETE для удаления текущего приложения
     * 4. После удаления пользователь открывает iwomail-rollback-vX.X.X.apk из Downloads/iwomail rollback/
     */
    private fun installDowngrade(apkFile: File) {
        try {
            // Шаг 1: Копируем APK в Downloads (fallback version = "unknown")
            val downloadUri = copyApkToDownloads(apkFile, "unknown")
            if (downloadUri == null) {
                android.util.Log.e("UpdateChecker", "Failed to copy APK to Downloads")
                android.widget.Toast.makeText(context, "Ошибка копирования APK", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            android.util.Log.d("UpdateChecker", "APK copied to Downloads: $downloadUri")
            
            // Шаг 2: Показываем notification (на случай если переживёт удаление на некоторых OEM)
            showInstallNotification(downloadUri)
            
            // Шаг 3: Toast с инструкцией
            android.widget.Toast.makeText(
                context,
                "После удаления откройте Downloads/$ROLLBACK_SUBFOLDER",
                android.widget.Toast.LENGTH_LONG
            ).show()
            
            // Шаг 4: Запускаем удаление текущего приложения
            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(uninstallIntent)
            
        } catch (e: Exception) {
            android.util.Log.e("UpdateChecker", "Downgrade error: ${e.message}", e)
        }
    }
    
    /**
     * Копирует APK в папку Downloads через MediaStore API (Android 10+) или напрямую (Android 9-)
     * @return Uri скопированного файла или null при ошибке
     */
    private fun copyApkToDownloads(apkFile: File, versionName: String): Uri? {
        val apkFileName = "iwomail-rollback-v${versionName}.apk"
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$ROLLBACK_SUBFOLDER/"
            val existingSelection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(apkFileName, relPath)

            // Ищем существующую запись в MediaStore (файл от текущей установки)
            var existingUri: Uri? = null
            try {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    existingSelection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        existingUri = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        )
                    }
                }
            } catch (_: Exception) {}

            // Путь 1: Перезапись существующего файла через его MediaStore URI ("wt" = write-truncate).
            // Это гарантирует перезапись без авто-переименования со стороны MediaStore.
            if (existingUri != null) {
                try {
                    val copiedBytes = resolver.openOutputStream(existingUri!!, "wt")?.use { output ->
                        apkFile.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Failed to open output stream for existing URI")

                    if (copiedBytes <= 0L) {
                        throw IllegalStateException("Copied APK is empty")
                    }
                    return existingUri
                } catch (e: Exception) {
                    android.util.Log.w("UpdateChecker",
                        "Failed to overwrite existing MediaStore entry, falling back to delete+insert: ${e.message}")
                    try { resolver.delete(existingUri!!, null, null) } catch (_: Exception) {}
                }
            }

            // Путь 2: Файл от предыдущей установки (невидим в MediaStore) — удаляем напрямую
            @Suppress("DEPRECATION")
            val directFile = File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROLLBACK_SUBFOLDER),
                apkFileName
            )
            try { if (directFile.exists()) directFile.delete() } catch (_: Exception) {}

            // Путь 3: Вставляем новую запись
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, apkFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROLLBACK_SUBFOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            
            try {
                val copiedBytes = resolver.openOutputStream(uri)?.use { output ->
                    apkFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Failed to open output stream for Downloads")

                if (copiedBytes <= 0L) {
                    throw IllegalStateException("Copied APK is empty")
                }
                
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                
                uri
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                android.util.Log.e("UpdateChecker", "Failed to write APK to Downloads: ${e.message}")
                null
            }
        } else {
            // Android 9 и ниже — копируем напрямую в Downloads/iwomail rollback/
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val rollbackDir = File(downloadsDir, ROLLBACK_SUBFOLDER)
                if (!rollbackDir.exists()) rollbackDir.mkdirs()
                val targetFile = File(rollbackDir, apkFileName)
                if (targetFile.exists()) targetFile.delete()
                
                apkFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (!targetFile.exists() || targetFile.length() <= 0L) {
                    throw IllegalStateException("Copied APK is empty")
                }
                
                Uri.fromFile(targetFile)
            } catch (e: Exception) {
                android.util.Log.e("UpdateChecker", "Failed to copy APK to Downloads (legacy): ${e.message}")
                null
            }
        }
    }
    
    /**
     * Показывает persistent notification с кнопкой установки APK.
     * Notification переживёт удаление приложения (системная notification) и позволит
     * пользователю установить старую версию после удаления текущей.
     */
    private fun showInstallNotification(apkUri: Uri) {
        val channelId = "rollback_install"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Создаём notification channel (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Установка обновлений",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления для установки обновлений и откатов"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // Intent для установки APK из Downloads
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            installIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("iwo Mail Client — откат готов")
            .setContentText("После удаления нажмите чтобы установить предыдущую версию")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Приложение будет удалено. После удаления нажмите на это уведомление чтобы установить предыдущую версию. APK сохранён в папке Downloads."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false) // Можно смахнуть
            .setAutoCancel(true) // Исчезает после нажатия
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(ROLLBACK_NOTIFICATION_ID, notification)
    }
    
    /**
     * Устанавливает APK через стандартный ACTION_VIEW Intent
     */
    private fun installApkWithActionView(apkFile: File) {
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
     * Возвращает уже скачанный APK файл, если он существует и не пуст.
     * Используется для возобновления с текущей стадии при повторном открытии диалога.
     */
    fun getExistingApkFile(): File? {
        val apkFile = File(File(context.filesDir, UPDATES_DIR), APK_FILENAME)
        return if (apkFile.exists() && apkFile.length() > 0) apkFile else null
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

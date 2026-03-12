package com.dedovmosol.iwomail.data.repository

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Очистка файлов приложения из Downloads:
 * - Download/IwoMail (сохранённые вложения)
 * - Download/IwoMail/Calendar (вложения календаря)
 * - Download/iwomail rollback (APK для отката)
 *
 * SRP: только очистка файлов, не email/folder cleanup.
 * Android Scoped Storage (API 29+): MediaStore query + delete.
 * Legacy (API < 29): File API напрямую.
 */
class AppFileCleanupService(context: Context) {

    private val context = context.applicationContext

    data class CleanupResult(
        val deletedCount: Int = 0,
        val hadErrors: Boolean = false
    )

    companion object {
        private const val DOWNLOADS_RELATIVE_PATH = "Download/IwoMail"
        private const val ROLLBACK_RELATIVE_PATH = "Download/iwomail rollback"
    }

    /**
     * Удаляет файлы из Download/IwoMail и Download/IwoMail/Calendar
     * старше [maxAgeDays] дней.
     * @return количество удалённых файлов
     */
    fun cleanupDownloads(maxAgeDays: Int): CleanupResult {
        if (maxAgeDays <= 0) return CleanupResult()
        return cleanupByRelativePath(DOWNLOADS_RELATIVE_PATH, maxAgeDays)
    }

    /**
     * Удаляет APK-файлы из Download/iwomail rollback
     * старше [maxAgeDays] дней.
     * @return количество удалённых файлов
     */
    fun cleanupRollback(maxAgeDays: Int): CleanupResult {
        if (maxAgeDays <= 0) return CleanupResult()
        return cleanupByRelativePath(ROLLBACK_RELATIVE_PATH, maxAgeDays)
    }

    /**
     * DRY: общая логика очистки по RELATIVE_PATH и возрасту файла.
     * API 29+: MediaStore query → contentResolver.delete (свои файлы).
     * API < 29: File.listFiles → file.delete.
     */
    private fun cleanupByRelativePath(relativePath: String, maxAgeDays: Int): CleanupResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cleanupViaMediaStore(relativePath, maxAgeDays)
        } else {
            cleanupViaFileApi(relativePath, maxAgeDays)
        }
    }

    private fun cleanupViaMediaStore(relativePath: String, maxAgeDays: Int): CleanupResult {
        val thresholdSeconds = (System.currentTimeMillis() / 1000) - (maxAgeDays.toLong() * 86400)
        val normalizedPath = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
        var deletedCount = 0

        val projection = arrayOf(MediaStore.Downloads._ID)
        // RELATIVE_PATH в MediaStore нормализуется с завершающим "/".
        // Сужаем фильтр до точной папки и её подпапок, не задевая префиксы вроде IwoMailBackup.
        val selection =
            "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?) AND " +
                "${MediaStore.Downloads.DATE_ADDED} < ?"
        val selectionArgs = arrayOf(relativePath, "$normalizedPath%", thresholdSeconds.toString())

        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    try {
                        val rows = context.contentResolver.delete(uri, null, null)
                        if (rows > 0) deletedCount++
                    } catch (_: SecurityException) {
                        // RecoverableSecurityException: файл не принадлежит приложению
                        // (создан предыдущей установкой). Пропускаем без краша.
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AppFileCleanup", "MediaStore cleanup failed for $relativePath", e)
            return CleanupResult(deletedCount = deletedCount, hadErrors = true)
        }

        return CleanupResult(deletedCount = deletedCount)
    }

    @Suppress("DEPRECATION")
    private fun cleanupViaFileApi(relativePath: String, maxAgeDays: Int): CleanupResult {
        val thresholdMs = System.currentTimeMillis() - (maxAgeDays.toLong() * 86_400_000)
        var deletedCount = 0

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val subPath = relativePath.removePrefix("Download/")
            val targetDir = File(downloadsDir, subPath)
            if (!targetDir.exists() || !targetDir.isDirectory) return CleanupResult()

            deleteOldFilesRecursive(targetDir, thresholdMs) { deletedCount++ }
        } catch (e: Exception) {
            android.util.Log.w("AppFileCleanup", "File API cleanup failed for $relativePath", e)
            return CleanupResult(deletedCount = deletedCount, hadErrors = true)
        }

        return CleanupResult(deletedCount = deletedCount)
    }

    private fun deleteOldFilesRecursive(
        dir: File,
        thresholdMs: Long,
        onDeleted: () -> Unit
    ) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                deleteOldFilesRecursive(file, thresholdMs, onDeleted)
                if (file.listFiles()?.isEmpty() == true) {
                    file.delete()
                }
            } else if (file.lastModified() < thresholdMs) {
                if (file.delete()) onDeleted()
            }
        }
    }
}

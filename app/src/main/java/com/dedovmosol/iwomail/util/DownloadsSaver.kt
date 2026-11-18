package com.dedovmosol.iwomail.util

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException

/**
 * Файл в общую папку «Загрузки» через MediaStore (Android 10+, без разрешения на память).
 *
 * Запись с `IS_PENDING = 1`: пока файл пишется, другие приложения его не видят; ошибка — запись удаляется,
 * в «Загрузках» не остаётся недописанного файла (документация MediaStore, «Toggle pending status»).
 * На Android 8-9 MediaStore.Downloads нет — там сохранение идёт системным диалогом (SAF), см. [isAvailable].
 */
object DownloadsSaver {

    @androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    @WorkerThread
    fun save(resolver: ContentResolver, source: File, displayName: String, mimeType: String, relativePath: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream for $uri")
            out.use { o -> source.inputStream().use { it.copyTo(o, 64 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }
}

package com.dedovmosol.iwomail.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Имя из OpenableColumns: провайдер часто отдаёт null. */
internal const val MAX_TEXT_IMPORT_BYTES = 5L * 1024 * 1024
internal const val MAX_CERTIFICATE_BYTES = 2L * 1024 * 1024
/** Один выбранный/share-файл: как лимит вложений письма. */
internal const val MAX_PICKED_FILE_BYTES = 10L * 1024 * 1024

internal fun openableDisplayName(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: "file"

internal data class OpenableFileMeta(val name: String, val size: Long, val mimeType: String)

/** Метаданные выбранного файла. null — провайдер отказал или курсор пуст (не краш). */
internal fun queryOpenableFileMeta(resolver: ContentResolver, uri: Uri): OpenableFileMeta? {
    return try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            OpenableFileMeta(
                name = openableDisplayName(if (nameIndex >= 0) cursor.getString(nameIndex) else null),
                size = if (sizeIndex >= 0) cursor.getLong(sizeIndex).coerceAtLeast(0L) else 0L,
                mimeType = resolver.getType(uri) ?: "application/octet-stream"
            )
        }
    } catch (_: Exception) {
        null
    }
}

internal sealed class LimitedCopyResult {
    class Ok(val bytesCopied: Long) : LimitedCopyResult()
    object ExceededSingle : LimitedCopyResult()
    object ExceededTotal : LimitedCopyResult()
}

/**
 * Копирование с потолками «один файл» и «сумма уже выбранного», без чтения целиком в память.
 * Календарь и письма: лимиты сервера, защита от OOM на главном потоке.
 */
internal fun copyStreamLimited(
    input: InputStream,
    output: OutputStream,
    maxSingleFile: Long,
    currentTotal: Long,
    maxTotal: Long
): LimitedCopyResult {
    val buffer = ByteArray(8 * 1024)
    var totalRead = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        totalRead += read
        if (totalRead > maxSingleFile) return LimitedCopyResult.ExceededSingle
        if (currentTotal + totalRead > maxTotal) return LimitedCopyResult.ExceededTotal
        output.write(buffer, 0, read)
    }
    return LimitedCopyResult.Ok(totalRead)
}

/** Чтение в память с потолком. null — файл больше лимита. */
internal fun readStreamLimited(input: InputStream, maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    return when (copyStreamLimited(input, out, maxBytes, 0L, maxBytes)) {
        is LimitedCopyResult.Ok -> out.toByteArray()
        else -> null
    }
}

/** Текст импорта (vCard/CSV): без readText() всего потока. */
internal fun readUtf8Limited(input: InputStream, maxBytes: Long): String? =
    readStreamLimited(input, maxBytes)?.toString(Charsets.UTF_8)

/** Копия content URI в файл с потолком. false — лимит/ошибка (dest удалён). */
internal fun copyUriLimited(
    resolver: ContentResolver,
    uri: Uri,
    dest: java.io.File,
    maxBytes: Long
): Boolean {
    var ok = false
    try {
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                ok = copyStreamLimited(input, output, maxBytes, 0L, maxBytes) is LimitedCopyResult.Ok
            }
        }
    } catch (_: Exception) {
        ok = false
    }
    if (!ok) dest.delete()
    return ok
}

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
    object Unreadable : LimitedCopyResult()
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

/** Байты URI с потолком (отправка письма). null — нет доступа, OOM или файл больше лимита. */
internal fun readUriBytesLimited(resolver: ContentResolver, uri: Uri, maxBytes: Long): ByteArray? {
    if (maxBytes <= 0L) return null
    return try {
        resolver.openInputStream(uri)?.use { readStreamLimited(it, maxBytes) }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}

internal const val COMPOSE_ATTACHMENTS_DIR = "compose_attachments"
internal const val SHARE_ATTACHMENTS_DIR = "share_attachments"

private val UNSAFE_PICKED_FILENAME = Regex("[\\/:*?\"<>|]")

/** Имя копии в каталоге приложения: UUID + безопасное имя, без коллизий и path traversal. */
internal fun uniquePickedFileName(displayName: String): String {
    val safe = openableDisplayName(displayName).replace(UNSAFE_PICKED_FILENAME, "_")
    return "${java.util.UUID.randomUUID().toString().replace("-", "")}_$safe"
}

/**
 * Копия content URI в [dest] с потолками «один файл» и «сумма».
 * При любой неудаче [dest] удаляется.
 */
internal fun copyOpenableToFile(
    resolver: ContentResolver,
    uri: Uri,
    dest: java.io.File,
    maxSingleFile: Long,
    currentTotal: Long,
    maxTotal: Long
): LimitedCopyResult {
    var result: LimitedCopyResult? = null
    try {
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                result = copyStreamLimited(input, output, maxSingleFile, currentTotal, maxTotal)
            }
        }
    } catch (_: Exception) {
        result = null
    }
    val finalResult = result
    if (finalResult !is LimitedCopyResult.Ok) dest.delete()
    return finalResult ?: LimitedCopyResult.Unreadable
}

internal fun deleteIfUnderDir(file: java.io.File, dir: java.io.File) {
    try {
        val canonical = file.canonicalFile
        val root = dir.canonicalFile
        val prefix = root.path + java.io.File.separator
        if (canonical.path.startsWith(prefix) && canonical.isFile) canonical.delete()
    } catch (_: Exception) {
    }
}

/** Удаляет локальную копию вложения (picker / share), если URI принадлежит FileProvider приложения. */
internal fun deletePickedAttachmentIfOwned(context: android.content.Context, uri: Uri) {
    if (uri.authority != "${context.packageName}.fileprovider") return
    val name = uri.lastPathSegment ?: return
    if (name.contains("..") || name.contains("/") || name.contains("\\")) return
    val dirs = listOf(
        java.io.File(context.filesDir, COMPOSE_ATTACHMENTS_DIR),
        java.io.File(context.cacheDir, SHARE_ATTACHMENTS_DIR)
    )
    for (dir in dirs) {
        val candidate = java.io.File(dir, name)
        if (candidate.exists()) {
            deleteIfUnderDir(candidate, dir)
            return
        }
    }
}

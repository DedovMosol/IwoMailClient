package com.dedovmosol.iwomail.util

import java.io.InputStream
import java.io.OutputStream

/** Имя из OpenableColumns: провайдер часто отдаёт null. */
internal fun openableDisplayName(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: "file"

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

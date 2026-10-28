package com.dedovmosol.iwomail.util

import java.util.Locale

/** Тип «неизвестно, что внутри» (RFC 2046). */
const val OCTET_STREAM = "application/octet-stream"

/**
 * MIME по расширению имени файла (без Android API — проверяется на JVM). null — расширение не знаем.
 * Нужен потому, что EAS не передаёт тип вложения: у элемента Attachment ([MS-ASAIRS] 2.2.2.7) нет ContentType —
 * только имя, ссылка, размер, Content-ID.
 */
fun mimeTypeFromFileName(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg", "jpe" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "zip" -> "application/zip"
    "rar" -> "application/x-rar-compressed"
    "7z" -> "application/x-7z-compressed"
    "msg" -> "application/vnd.ms-outlook"
    "eml" -> "message/rfc822"
    else -> null
}

/** Тип вложения: заданный сервером (EWS, файлы приложения), иначе по имени файла, иначе [OCTET_STREAM]. */
fun attachmentMimeType(contentType: String?, fileName: String): String =
    contentType?.trim()?.takeIf { it.isNotEmpty() && !it.equals(OCTET_STREAM, ignoreCase = true) }
        ?: mimeTypeFromFileName(fileName)
        ?: OCTET_STREAM

/** Вложение — картинка (по типу или, для вложений из EAS без типа, по имени файла). */
fun isImageAttachment(contentType: String?, fileName: String): Boolean =
    attachmentMimeType(contentType, fileName).startsWith("image/", ignoreCase = true)

/**
 * MIME для data URL inline-картинки: по байтам ([detectImageMimeType] — надёжнее всего), иначе тип вложения, если это
 * картинка, иначе image/png. С «application/octet-stream» такая картинка при пересохранении черновика ушла бы на
 * сервер с этим типом.
 */
fun dataUrlImageMimeType(bytes: ByteArray, contentType: String?, fileName: String): String =
    detectImageMimeType(bytes)
        ?: attachmentMimeType(contentType, fileName).takeIf { it.startsWith("image/", ignoreCase = true) }
        ?: "image/png"

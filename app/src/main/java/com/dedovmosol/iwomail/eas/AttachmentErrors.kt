package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.network.ConnectionFailure

/**
 * Коды ошибок скачивания вложения (EAS и EWS). Текст для пользователя — NotificationStrings.localizeError.
 * Раньше любой отказ превращался в «Сервер не поддерживает скачивание вложений через EAS» — в том числе
 * лимит размера, обрыв связи и неверный пароль.
 */
object AttachmentErrors {
    /** Больше лимита сервера для EAS (ItemOperations Status 11, GetAttachment HTTP 413), и EWS тоже не отдал. */
    const val TOO_LARGE = "ATTACHMENT_TOO_LARGE"
    /** Сервер не нашёл вложение (или письмо в EWS) по ссылке. */
    const val NOT_FOUND = "ATTACHMENT_NOT_FOUND"
    /** На устройстве кончилось место при записи файла. */
    const val NO_SPACE = "ATTACHMENT_NO_SPACE"
    /** Вложение больше предела чтения в память (inline-картинки, .ics) — для файла есть потоковый путь. */
    const val TOO_LARGE_FOR_MEMORY = "ATTACHMENT_TOO_LARGE_FOR_MEMORY"

    private val NO_RETRY = setOf("UNAUTHORIZED", "ACCESS_DENIED", NO_SPACE, TOO_LARGE_FOR_MEMORY)

    /** Повтор с другой ссылкой или другим протоколом не поможет: учётные данные, доступ, место, сеть. */
    fun isFinal(message: String): Boolean = message in NO_RETRY || ConnectionFailure.fromMessage(message) != null

    /** EWS может отдать то, чего не отдал EAS: лимит размера, ссылка устарела, ItemOperations отказал. */
    fun ewsMayHelp(message: String): Boolean = !isFinal(message)

    /** Исключение при скачивании: нехватка места отдельно от сети (ENOSPC — errno Linux, текст Android). */
    fun of(e: Exception): String = when {
        e is EasAttachmentService.BoundedBuffer.Overflow -> TOO_LARGE_FOR_MEMORY
        isNoSpace(e) -> NO_SPACE
        else -> ConnectionFailure.describe(e)
    }

    private fun isNoSpace(e: Throwable): Boolean {
        var cause: Throwable? = e
        repeat(5) {
            val c = cause ?: return false
            // Android: IOException("write failed: ENOSPC (No space left on device)") с ErrnoException в причине
            if (c.message?.contains("ENOSPC") == true) return true
            cause = c.cause
        }
        return false
    }
}

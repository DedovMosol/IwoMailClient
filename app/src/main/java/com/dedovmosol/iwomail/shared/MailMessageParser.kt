package com.dedovmosol.iwomail.shared

import javax.mail.Message
import javax.mail.Part
import javax.mail.internet.MimeMultipart

/**
 * Общий парсер для MIME сообщений (IMAP/POP3)
 * Принцип DRY: устраняет дублирование между ImapClient и Pop3Client
 */
object MailMessageParser {
    
    // Предкомпилированный regex для производительности
    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    
    /**
     * Извлекает превью из сообщения (первые 200 символов без HTML тегов)
     */
    fun getPreview(message: Message): String {
        return try {
            getBody(message).take(200).replace(HTML_TAG_REGEX, "").trim()
        } catch (_: Exception) { "" }
    }
    
    /**
     * Извлекает тело сообщения (HTML или plain text)
     */
    fun getBody(message: Message): String {
        return try {
            when (val content = message.content) {
                is String -> content
                is MimeMultipart -> extractTextFromMultipart(content)
                else -> ""
            }
        } catch (_: Exception) { "" }
    }
    
    /**
     * Рекурсивно извлекает текст из multipart сообщения
     * Приоритет: HTML > plain text
     */
    fun extractTextFromMultipart(multipart: MimeMultipart): String {
        var html = ""
        var text = ""
        
        for (i in 0 until multipart.count) {
            val part = multipart.getBodyPart(i)
            when {
                part.isMimeType("text/plain") -> text = part.content as? String ?: ""
                part.isMimeType("text/html") -> html = part.content as? String ?: ""
                part.content is MimeMultipart -> {
                    val nested = extractTextFromMultipart(part.content as MimeMultipart)
                    if (nested.isNotEmpty()) return nested
                }
            }
        }
        
        return html.ifEmpty { text }
    }
    
    /**
     * Проверяет наличие вложений в сообщении
     */
    fun hasAttachments(message: Message): Boolean {
        return try {
            val content = message.content
            if (content is MimeMultipart) {
                for (i in 0 until content.count) {
                    val part = content.getBodyPart(i)
                    if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) { false }
    }
    
    /**
     * Определяет тип тела сообщения (1 = plain text, 2 = HTML)
     */
    fun getBodyType(message: Message): Int {
        return if (getBody(message).contains("<html", ignoreCase = true)) 2 else 1
    }
}

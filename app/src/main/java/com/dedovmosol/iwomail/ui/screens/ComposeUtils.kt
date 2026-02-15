package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.dedovmosol.iwomail.util.HtmlRegex
import java.io.ByteArrayOutputStream

// Предкомпилированные regex для stripHtml (производительность)
private val BLOCK_END_REGEX = Regex("</(?:p|div|li|tr)>", RegexOption.IGNORE_CASE)
private val TD_END_REGEX = Regex("</td>", RegexOption.IGNORE_CASE)
private val SPACES_REGEX = Regex("[ \\t]+")
private val NEWLINES_REGEX = Regex("\\n{3,}")

private val EMAIL_VALIDATION_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
private val GROUP_TOKEN_REGEX = Regex("^\\[.+]$")

/**
 * Проверяет, является ли строка валидным email-адресом
 */
fun isValidEmail(email: String): Boolean {
    return EMAIL_VALIDATION_REGEX.matches(email.trim())
}

/**
 * Проверяет, является ли токен ссылкой на группу контактов [GroupName]
 */
fun isGroupToken(token: String): Boolean {
    return GROUP_TOKEN_REGEX.matches(token.trim())
}

/**
 * Проверяет список получателей (разделённых запятыми/точками с запятой).
 * Пустая строка считается валидной (поле необязательное).
 * Токены [GroupName] считаются валидными (группа контактов).
 * Возвращает true если все адреса валидны.
 */
fun isValidRecipientList(recipients: String): Boolean {
    if (recipients.isBlank()) return true
    return recipients.split(",", ";")
        .filter { it.isNotBlank() }
        .all { val trimmed = it.trim(); isValidEmail(trimmed) || isGroupToken(trimmed) }
}

/**
 * Раскрывает [GroupName] токены в списке получателей, заменяя их на email из маппинга.
 * Обычные email-адреса остаются как есть.
 */
fun expandGroupTokens(recipients: String, groupMappings: Map<String, List<String>>): String {
    if (recipients.isBlank()) return recipients
    val tokens = recipients.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
    val expanded = tokens.flatMap { token ->
        if (isGroupToken(token)) {
            val groupName = token.removePrefix("[").removeSuffix("]")
            // Если группа не найдена в маппинге — пропускаем (не отправлять [GroupName] на сервер)
            groupMappings[groupName] ?: emptyList()
        } else {
            listOf(token)
        }
    }
    return expanded.distinct().joinToString(", ")
}

/**
 * Извлекает все email-адреса из поля получателей (включая раскрытие групп).
 * Используется для проверки дубликатов при добавлении новых получателей.
 */
fun extractAllEmails(field: String, groupMappings: Map<String, List<String>>): Set<String> {
    if (field.isBlank()) return emptySet()
    val tokens = field.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
    return tokens.flatMap { token ->
        if (isGroupToken(token)) {
            val groupName = token.removePrefix("[").removeSuffix("]")
            groupMappings[groupName] ?: emptyList()
        } else if (EMAIL_VALIDATION_REGEX.matches(token)) {
            listOf(token)
        } else {
            emptyList()
        }
    }.map { it.lowercase() }.toSet()
}

/**
 * Проверяет, есть ли email-адрес уже в каком-либо из полей получателей (to/cc/bcc).
 * Возвращает название поля, где найден дубликат, или null если дубликатов нет.
 */
fun findDuplicateField(
    email: String,
    to: String,
    cc: String,
    bcc: String,
    groupMappings: Map<String, List<String>>
): String? {
    val emailLower = email.lowercase()
    if (emailLower in extractAllEmails(to, groupMappings)) return "To"
    if (emailLower in extractAllEmails(cc, groupMappings)) return "Cc"
    if (emailLower in extractAllEmails(bcc, groupMappings)) return "Bcc"
    return null
}

/**
 * VisualTransformation для подкраски токенов [GroupName] цветом группы контактов.
 * Текст не меняется, только добавляется SpanStyle с цветом и жирным шрифтом.
 */
class GroupColorVisualTransformation(
    private val groupColors: Map<String, Int>
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (groupColors.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val raw = text.text
        val builder = AnnotatedString.Builder(raw)
        // Копируем существующие стили из оригинала
        text.spanStyles.forEach { builder.addStyle(it.item, it.start, it.end) }
        // Ищем все [GroupName] токены и подкрашиваем
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '[') {
                val end = raw.indexOf(']', i)
                if (end > i) {
                    val name = raw.substring(i + 1, end)
                    val color = groupColors[name]
                    if (color != null) {
                        builder.addStyle(
                            SpanStyle(color = Color(color), fontWeight = FontWeight.Bold),
                            i, end + 1
                        )
                    }
                    i = end + 1
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * Форматирует дату для отображения в письмах/цитатах
 */
fun formatEmailDate(timestamp: Long): String {
    return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}

/**
 * Форматирует размер файла для отображения во вложениях
 */
fun formatAttachmentSize(bytes: Long): String {
    return when {
        bytes < 1024 -> if (java.util.Locale.getDefault().language == "ru") "$bytes Б" else "$bytes B"
        bytes < 1024 * 1024 -> if (java.util.Locale.getDefault().language == "ru") "${bytes / 1024} КБ" else "${bytes / 1024} KB"
        else -> if (java.util.Locale.getDefault().language == "ru") String.format("%.1f МБ", bytes / (1024.0 * 1024.0)) else String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/**
 * Очищает HTML теги из текста, оставляя только plain text
 */
fun stripHtml(html: String): String {
    if (html.isBlank()) return ""
    
    return html
        // Заменяем <br>, <br/>, <br /> на переносы строк
        .replace(HtmlRegex.BR, "\n")
        // Заменяем </p>, </div>, </li> на переносы строк
        .replace(BLOCK_END_REGEX, "\n")
        // Заменяем </td> на табуляцию
        .replace(TD_END_REGEX, "\t")
        // Удаляем <style>...</style> и <script>...</script> блоки
        .replace(HtmlRegex.STYLE, "")
        .replace(HtmlRegex.SCRIPT, "")
        // Удаляем HTML комментарии
        .replace(HtmlRegex.COMMENT, "")
        // Удаляем все оставшиеся HTML теги
        .replace(HtmlRegex.TAG, "")
        // Декодируем HTML entities
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        // Убираем множественные пробелы
        .replace(SPACES_REGEX, " ")
        // Убираем множественные переносы строк (больше 2 подряд)
        .replace(NEWLINES_REGEX, "\n\n")
        // Убираем пробелы в начале и конце строк
        .lines().joinToString("\n") { it.trim() }
        .trim()
}

/**
 * Сжимает изображение для вставки inline в письмо
 * @param context Context для доступа к ContentResolver
 * @param uri URI изображения
 * @param maxWidth Максимальная ширина (по умолчанию 1024px)
 * @param maxHeight Максимальная высота (по умолчанию 1024px)
 * @param quality Качество JPEG (0-100, по умолчанию 85)
 * @return Pair<ByteArray, String> - сжатые байты и MIME тип, или null при ошибке
 */
fun compressImageForInline(
    context: Context,
    uri: Uri,
    maxWidth: Int = 1024,
    maxHeight: Int = 1024,
    quality: Int = 85
): Pair<ByteArray, String>? {
    var bitmap: Bitmap? = null
    var scaledBitmap: Bitmap? = null
    
    try {
        // Сначала получаем размеры изображения без загрузки в память
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight
        
        // Если изображение маленькое — возвращаем как есть
        if (originalWidth <= maxWidth && originalHeight <= maxHeight) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            return bytes?.let { Pair(it, mimeType) }
        }
        
        // Вычисляем коэффициент масштабирования (степень двойки для эффективности)
        var inSampleSize = 1
        while (originalWidth / inSampleSize > maxWidth * 2 || originalHeight / inSampleSize > maxHeight * 2) {
            inSampleSize *= 2
        }
        
        // Загружаем с уменьшением
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null
        
        // Дополнительное масштабирование если нужно
        scaledBitmap = if (bitmap.width > maxWidth || bitmap.height > maxHeight) {
            val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        
        // Сжимаем в JPEG
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        
        return Pair(outputStream.toByteArray(), "image/jpeg")
    } catch (e: Exception) {
        return null
    } catch (e: OutOfMemoryError) {
        System.gc()
        return null
    } finally {
        // Гарантированно освобождаем bitmap'ы
        if (scaledBitmap != null && scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        bitmap?.recycle()
    }
}

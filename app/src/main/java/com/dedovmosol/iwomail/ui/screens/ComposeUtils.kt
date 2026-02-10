package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.dedovmosol.iwomail.util.HtmlRegex
import java.io.ByteArrayOutputStream

// Предкомпилированные regex для stripHtml (производительность)
private val BLOCK_END_REGEX = Regex("</(?:p|div|li|tr)>", RegexOption.IGNORE_CASE)
private val TD_END_REGEX = Regex("</td>", RegexOption.IGNORE_CASE)
private val SPACES_REGEX = Regex("[ \\t]+")
private val NEWLINES_REGEX = Regex("\\n{3,}")

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

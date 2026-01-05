package com.iwo.mailclient.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Компонент для отображения HTML контента с изображениями и кликабельными ссылками
 */
@Composable
fun RichTextWithImages(
    htmlContent: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    
    // Парсим HTML контент
    val parsedContent = remember(htmlContent) {
        parseHtmlContent(htmlContent)
    }
    
    // Используем Column без скролла - родительский контейнер должен обеспечить скролл
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parsedContent.forEach { element ->
            when (element) {
                is ContentElement.Image -> {
                    var imageLoaded by remember { mutableStateOf(true) }
                    if (imageLoaded) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(element.url)
                                .crossfade(true)
                                .listener(
                                    onError = { _, _ -> imageLoaded = false }
                                )
                                .build(),
                            contentDescription = element.alt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .clickable {
                                    // Открыть изображение в браузере при клике
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(element.url))
                                        context.startActivity(intent)
                                    } catch (e: Exception) { }
                                },
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }
                is ContentElement.Text -> {
                    if (element.text.isNotBlank()) {
                        val annotatedString = buildAnnotatedString {
                            append(element.text)
                            element.links.forEach { link ->
                                if (link.range.first >= 0 && link.range.last <= element.text.length) {
                                    addStyle(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        start = link.range.first,
                                        end = link.range.last
                                    )
                                    addStringAnnotation(
                                        tag = "URL",
                                        annotation = link.url,
                                        start = link.range.first,
                                        end = link.range.last
                                    )
                                }
                            }
                        }
                        
                        ClickableText(
                            text = annotatedString,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = { offset ->
                                annotatedString.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { annotation ->
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                                            context.startActivity(intent)
                                        } catch (e: Exception) { }
                                    }
                            }
                        )
                    }
                }
            }
        }
    }
}


/**
 * Элемент контента
 */
private sealed class ContentElement {
    data class Image(val url: String, val alt: String) : ContentElement()
    data class Text(val text: String, val links: List<LinkInfo>) : ContentElement()
}

private data class LinkInfo(val range: IntRange, val url: String)

/**
 * Парсит HTML контент и возвращает список элементов (текст и изображения)
 */
private fun parseHtmlContent(html: String): List<ContentElement> {
    val elements = mutableListOf<ContentElement>()
    
    // Регулярки для поиска изображений и ссылок
    val imgRegex = Regex("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*(?:alt=[\"']([^\"']*)[\"'])?[^>]*>", RegexOption.IGNORE_CASE)
    val linkRegex = Regex("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)
    val urlRegex = Regex("(https?://[^\\s<>\"]+)")
    val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")
    
    // Собираем все изображения: из <img> тегов и из URL
    data class ImageMatch(val range: IntRange, val url: String, val alt: String)
    val allImages = mutableListOf<ImageMatch>()
    
    // Изображения из <img> тегов
    imgRegex.findAll(html).forEach { match ->
        val imgUrl = match.groupValues[1]
        val imgAlt = match.groupValues.getOrNull(2) ?: ""
        if (imgUrl.startsWith("http")) {
            allImages.add(ImageMatch(match.range, imgUrl, imgAlt))
        }
    }
    
    // Изображения из URL (не внутри <img> тегов)
    urlRegex.findAll(html).forEach { match ->
        val url = match.value
        val isImage = imageExtensions.any { url.lowercase().contains(it) }
        val alreadyInImg = allImages.any { it.range.first <= match.range.first && it.range.last >= match.range.last }
        if (isImage && !alreadyInImg) {
            allImages.add(ImageMatch(match.range, url, ""))
        }
    }
    
    // Сортируем по позиции
    allImages.sortBy { it.range.first }
    
    // Разбиваем контент по изображениям
    var currentPosition = 0
    
    for (img in allImages) {
        // Текст до изображения
        if (img.range.first > currentPosition) {
            val textBefore = html.substring(currentPosition, img.range.first)
            val textElement = parseTextWithLinks(textBefore, linkRegex, urlRegex, imageExtensions)
            if (textElement != null) {
                elements.add(textElement)
            }
        }
        
        // Изображение
        elements.add(ContentElement.Image(img.url, img.alt))
        
        currentPosition = img.range.last + 1
    }
    
    // Текст после последнего изображения
    if (currentPosition < html.length) {
        val textAfter = html.substring(currentPosition)
        val textElement = parseTextWithLinks(textAfter, linkRegex, urlRegex, imageExtensions)
        if (textElement != null) {
            elements.add(textElement)
        }
    }
    
    // Если не было изображений, парсим весь текст
    if (allImages.isEmpty()) {
        val textElement = parseTextWithLinks(html, linkRegex, urlRegex, imageExtensions)
        if (textElement != null) {
            elements.add(textElement)
        }
    }
    
    return elements
}

/**
 * Парсит текст с ссылками
 */
private fun parseTextWithLinks(html: String, linkRegex: Regex, urlRegex: Regex, imageExtensions: List<String>): ContentElement.Text? {
    // Регулярки для email и телефонов
    val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    val phoneRegex = Regex("(?:\\+7|8)[\\s-]?\\(?\\d{3}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}|\\+?\\d{1,3}[\\s-]?\\(?\\d{2,4}\\)?[\\s-]?\\d{2,4}[\\s-]?\\d{2,4}")
    
    // Извлекаем ссылки из <a> тегов
    val htmlLinks = linkRegex.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
    
    // Заменяем <a> теги на плейсхолдеры
    var processedHtml = html
    val linkPlaceholders = mutableListOf<Pair<String, String>>() // placeholder -> url
    
    htmlLinks.forEachIndexed { index, (url, text) ->
        val placeholder = "{{LINK_$index}}"
        processedHtml = linkRegex.replaceFirst(processedHtml, "$placeholder$text{{/LINK}}")
        linkPlaceholders.add(placeholder to url)
    }
    
    // Очищаем HTML теги
    var cleanText = processedHtml
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("{{/LINK}}", "")
    
    // Очищаем markdown-ссылки [text](url) — оставляем только text
    cleanText = cleanText.replace(Regex("\\[([^\\]]*)]\\([^)]+\\)")) { match ->
        match.groupValues[1]
    }
    
    // Очищаем markdown-изображения ![alt](url) — удаляем полностью
    cleanText = cleanText.replace(Regex("!\\[[^\\]]*]\\([^)]+\\)"), "")
    
    // Удаляем одиночные [ и ] которые остались
    cleanText = cleanText.replace(Regex("\\[\\s*\\]"), "")
    
    cleanText = cleanText.trim()
    
    if (cleanText.isBlank()) return null
    
    // Находим позиции ссылок
    val links = mutableListOf<LinkInfo>()
    var finalText = cleanText
    
    // Обрабатываем плейсхолдеры ссылок
    linkPlaceholders.forEach { (placeholder, url) ->
        val placeholderIndex = finalText.indexOf(placeholder)
        if (placeholderIndex >= 0) {
            finalText = finalText.replace(placeholder, "")
            // Находим конец текста ссылки (до следующего пробела или конца)
            val linkText = htmlLinks.find { it.first == url }?.second ?: ""
            if (linkText.isNotBlank()) {
                val linkStart = placeholderIndex
                val linkEnd = linkStart + linkText.length
                if (linkEnd <= finalText.length) {
                    links.add(LinkInfo(linkStart until linkEnd, url))
                }
            }
        }
    }
    
    // Находим URL в тексте (исключая картинки — они обрабатываются отдельно)
    urlRegex.findAll(finalText).forEach { match ->
        val url = match.value
        val isImage = imageExtensions.any { url.lowercase().contains(it) }
        // Проверяем, что этот URL не уже в списке ссылок и не картинка
        val alreadyLinked = links.any { it.range.first <= match.range.first && it.range.last >= match.range.last }
        if (!alreadyLinked && !isImage) {
            links.add(LinkInfo(match.range, match.value))
        }
    }
    
    // Находим email адреса
    emailRegex.findAll(finalText).forEach { match ->
        val alreadyLinked = links.any { 
            (it.range.first <= match.range.first && it.range.last >= match.range.last) ||
            (match.range.first <= it.range.first && match.range.last >= it.range.last)
        }
        if (!alreadyLinked) {
            links.add(LinkInfo(match.range, "mailto:${match.value}"))
        }
    }
    
    // Находим телефонные номера
    phoneRegex.findAll(finalText).forEach { match ->
        val alreadyLinked = links.any { 
            (it.range.first <= match.range.first && it.range.last >= match.range.last) ||
            (match.range.first <= it.range.first && match.range.last >= it.range.last)
        }
        if (!alreadyLinked) {
            // Очищаем номер от пробелов и скобок для tel: URI
            val cleanPhone = match.value.replace(Regex("[\\s()-]"), "")
            links.add(LinkInfo(match.range, "tel:$cleanPhone"))
        }
    }
    
    return ContentElement.Text(finalText, links)
}

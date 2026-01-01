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
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parsedContent.forEach { element ->
            when (element) {
                is ContentElement.Image -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(element.url)
                            .crossfade(true)
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
    
    // Разбиваем HTML по изображениям
    var currentPosition = 0
    val imgMatches = imgRegex.findAll(html).toList()
    
    for (imgMatch in imgMatches) {
        // Текст до изображения
        if (imgMatch.range.first > currentPosition) {
            val textBefore = html.substring(currentPosition, imgMatch.range.first)
            val textElement = parseTextWithLinks(textBefore, linkRegex, urlRegex)
            if (textElement != null) {
                elements.add(textElement)
            }
        }
        
        // Изображение
        val imgUrl = imgMatch.groupValues[1]
        val imgAlt = imgMatch.groupValues.getOrNull(2) ?: ""
        if (imgUrl.startsWith("http")) {
            elements.add(ContentElement.Image(imgUrl, imgAlt))
        }
        
        currentPosition = imgMatch.range.last + 1
    }
    
    // Текст после последнего изображения
    if (currentPosition < html.length) {
        val textAfter = html.substring(currentPosition)
        val textElement = parseTextWithLinks(textAfter, linkRegex, urlRegex)
        if (textElement != null) {
            elements.add(textElement)
        }
    }
    
    // Если не было изображений, парсим весь текст
    if (imgMatches.isEmpty()) {
        val textElement = parseTextWithLinks(html, linkRegex, urlRegex)
        if (textElement != null) {
            elements.add(textElement)
        }
    }
    
    return elements
}

/**
 * Парсит текст с ссылками
 */
private fun parseTextWithLinks(html: String, linkRegex: Regex, urlRegex: Regex): ContentElement.Text? {
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
    val cleanText = processedHtml
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("{{/LINK}}", "")
        .trim()
    
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
    
    // Находим URL в тексте
    urlRegex.findAll(finalText).forEach { match ->
        // Проверяем, что этот URL не уже в списке ссылок
        val alreadyLinked = links.any { it.range.first <= match.range.first && it.range.last >= match.range.last }
        if (!alreadyLinked) {
            links.add(LinkInfo(match.range, match.value))
        }
    }
    
    return ContentElement.Text(finalText, links)
}

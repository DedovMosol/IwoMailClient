package com.dedovmosol.iwomail.util

/**
 * Утилиты для работы с HTML
 * Принцип DRY: общие функции для экранирования и парсинга HTML
 */

/**
 * Экранирует специальные HTML символы
 * Используется для безопасной вставки текста в HTML
 */
fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

/**
 * Предкомпилированные regex для парсинга HTML (производительность)
 * Используются в ComposeScreen и MailRepository
 */
object HtmlRegex {
    val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    val STYLE = Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val SCRIPT = Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    val TAG = Regex("<[^>]+>")
    val HTML_ENTITY = Regex("&#x?[0-9a-fA-F]+;")
    val HEAD = Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val P_OPEN = Regex("<p[^>]*>", RegexOption.IGNORE_CASE)
    val P_CLOSE = Regex("</p>", RegexOption.IGNORE_CASE)
    val NUMERIC_ENTITY = Regex("&#(\\d+);")
    val BLANK_LINES = Regex("[ \\t]*\\n[ \\t]*")
    val MULTI_NEWLINES = Regex("\\n{3,}")

    fun decodeNumericEntity(match: MatchResult): String {
        return try {
            val raw = match.value.drop(2).dropLast(1)
            val code = if (raw.startsWith("x", ignoreCase = true)) {
                raw.drop(1).toInt(16)
            } else {
                raw.toInt()
            }
            code.toChar().toString()
        } catch (_: Exception) {
            match.value
        }
    }
}

/**
 * Очистка HTML-тегов из текста, если он содержит HTML-разметку.
 * Exchange 2007 SP1 возвращает тело задач/заметок в HTML из Outlook.
 */
fun stripHtmlIfNeeded(text: String): String {
    val lower = text.lowercase()
    if (!lower.contains("<html") && !lower.contains("<body") && !lower.contains("<font")) {
        return text
    }
    return text
        .replace(HtmlRegex.STYLE, "")
        .replace(HtmlRegex.SCRIPT, "")
        .replace(HtmlRegex.COMMENT, "")
        .replace(HtmlRegex.HEAD, "")
        .replace(HtmlRegex.BR, "\n")
        .replace(HtmlRegex.P_OPEN, "\n")
        .replace(HtmlRegex.P_CLOSE, "")
        .replace(HtmlRegex.TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(HtmlRegex.HTML_ENTITY) { HtmlRegex.decodeNumericEntity(it) }
        .replace(HtmlRegex.BLANK_LINES, "\n")
        .replace(HtmlRegex.MULTI_NEWLINES, "\n\n")
        .trim()
}

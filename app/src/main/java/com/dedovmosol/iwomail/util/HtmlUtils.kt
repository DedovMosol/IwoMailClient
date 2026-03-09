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
 * XSS protection: removes script tags, event handlers, and javascript: URIs.
 * Idempotent — safe to call multiple times on the same HTML.
 * Used before injecting email body into WebView.
 */
private val SANITIZE_SCRIPT_TAG = Regex("(?si)<script[^>]*>.*?</script>")
private val SANITIZE_SCRIPT_OPEN_CLOSE = Regex("(?i)</?script[^>]*>")
private val SANITIZE_EVENT_DOUBLE = Regex("""(?i)\s+on\w+\s*=\s*"[^"]*"""")
private val SANITIZE_EVENT_SINGLE = Regex("""(?i)\s+on\w+\s*=\s*'[^']*'""")
private val SANITIZE_EVENT_UNQUOTED = Regex("""(?i)\s+on\w+\s*=\s*[^\s>"']+""")
private val SANITIZE_JS_URI_DOUBLE = Regex("""(?i)(href|src|action|formaction)\s*=\s*"\s*javascript:[^"]*"""")
private val SANITIZE_JS_URI_SINGLE = Regex("""(?i)(href|src|action|formaction)\s*=\s*'\s*javascript:[^']*'""")

fun sanitizeEmailHtml(html: String): String = html
    .replace(SANITIZE_SCRIPT_TAG, "")
    .replace(SANITIZE_SCRIPT_OPEN_CLOSE, "")
    .replace(SANITIZE_EVENT_DOUBLE, "")
    .replace(SANITIZE_EVENT_SINGLE, "")
    .replace(SANITIZE_EVENT_UNQUOTED, "")
    .replace(SANITIZE_JS_URI_DOUBLE, """$1="#"""")
    .replace(SANITIZE_JS_URI_SINGLE, """$1='#'""")

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

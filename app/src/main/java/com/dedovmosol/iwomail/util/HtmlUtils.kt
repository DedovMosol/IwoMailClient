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
    val HTML_ENTITY = Regex("&#\\d+;")
}

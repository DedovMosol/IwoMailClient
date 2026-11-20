package com.dedovmosol.iwomail.util

/**
 * Эталон для сверки: sanitizeEmailHtml до MAIL-03 (HtmlUtils.kt, 1.6.3b) — те же выражения в том же порядке, плюс
 * шаг MAIL-09 (формы, поля ввода, base, link, фреймы, SVG use — тег заменяется пробелом) на том же месте, что и в
 * линейной реализации. Квадратичен на «письме-яде», поэтому сверка с ним — только на обычных и коротких входах.
 */
internal object LegacySanitizer {
    private val steps = listOf(
        Regex("(?si)<script[^>]*>.*?</script>") to "",
        Regex("(?i)</?script[^>]*>") to "",
        Regex("(?si)<iframe[^>]*>.*?</iframe>") to "",
        Regex("(?i)</?iframe[^>]*>") to "",
        Regex("(?si)<object[^>]*>.*?</object>") to "",
        Regex("(?i)</?object[^>]*>") to "",
        Regex("(?i)<embed\\b[^>]*/?>") to "",
        Regex("(?si)<applet[^>]*>.*?</applet>") to "",
        Regex("(?i)</?applet[^>]*>") to "",
        Regex("""(?i)<meta\b[^>]*http-equiv\s*=\s*['"]?refresh['"]?[^>]*>""") to "",
        Regex("""(?i)</?(?:form|input|button|textarea|select|base|link|frameset|frame|use)(?![\p{L}\p{Nd}_-])[^>]*>""") to " ",
        Regex("""(?i)\s+on\w+\s*=\s*"[^"]*"""") to "",
        Regex("""(?i)\s+on\w+\s*=\s*'[^']*'""") to "",
        Regex("""(?i)\s+on\w+\s*=\s*[^\s>"']+""") to "",
        Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*"\s*javascript:[^"]*"""") to """$1="#"""",
        Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*'\s*javascript:[^']*'""") to """$1='#'""",
        Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*"\s*data:text/html[^"]*"""") to """$1="#"""",
        Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*'\s*data:text/html[^']*'""") to """$1='#'"""
    )

    fun sanitize(html: String): String = steps.fold(html) { acc, (re, rep) -> acc.replace(re, rep) }
}

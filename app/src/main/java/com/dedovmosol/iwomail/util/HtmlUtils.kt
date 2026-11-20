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
 * Обычный текст (тело mailto:, текст из «Поделиться») для HTML-редактора: символы экранируются (текст «<b>» остаётся
 * текстом, а не разметкой), переводы строк — <br>.
 */
fun plainTextToHtml(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').escapeHtml().replace("\n", "<br>")

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
            // Code point, а не Char: эмодзи и прочие символы вне BMP (> U+FFFF) — суррогатная пара.
            if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        } catch (_: Exception) {
            match.value
        }
    }
}

/** Блочные теги и `<br>`: при извлечении текста дают перевод строки (иначе «a</div><div>b» → «ab»). */
private val BLOCK_TAG_REGEX = Regex(
    "(?i)</?(?:br|p|div|tr|td|th|li|ul|ol|table|thead|tbody|h[1-6]|blockquote|pre|hr)\\b[^>]*>"
)

/** Серия переводов строк с пробелами вокруг: соседние блочные теги («</div><div>») — один перенос. */
private val NEWLINE_RUN_REGEX = Regex("[ \\t]*(?:\\r?\\n[ \\t]*)+")

private val HTML_EMAIL_FRAGMENT = Regex(
    "(?i)<\\s*(html|body|div|p|br|table|tr|td|th|tbody|thead|span|a|img|strong|b|i|em|u|blockquote|ul|ol|li|font|style|head)\\b"
)
private val ENCODED_HTML_EMAIL_FRAGMENT = Regex(
    "(?i)&lt;\\s*(html|body|div|p|br|table|tr|td|th|tbody|thead|span|a|img|strong|b|i|em|u|blockquote|ul|ol|li|font|style|head)\\b"
)

/**
 * XSS protection: removes script tags, event handlers, javascript:/data:text/html URIs,
 * embedded plugin containers (iframe/object/embed/applet) and meta-refresh redirects.
 * Idempotent — safe to call multiple times on the same HTML.
 * Used before injecting email body into WebView.
 *
 * Note: WebView baseURL is null when loading sanitized email body
 * (see EmailDetailScreen.loadDataWithBaseURL(null, ...)). Null-baseURL prevents
 * cross-origin requests, cookie/localStorage exfiltration and service workers,
 * but does NOT prevent UI manipulation, external navigation, or plugin loading.
 * The blocklist below complements null-baseURL.
 */
// MAIL-03: всё ниже работает за линейное время от размера письма. Прежние выражения `<tag[^>]*>.*?</tag>`,
// `</?tag[^>]*>`, `<meta\b[^>]*http-equiv…` и `\s+on\w+…` на «письме-яде» (тысячи незакрытых тегов, нет `>`,
// длинные пробелы) перебирали остаток строки с каждой позиции: 160 КБ — 16 с разбора на главном потоке (ANR).
// Удаление тегов — сканером по indexOf с тем же результатом (сверено с прежними выражениями на 300 000 строк,
// docs/audit/experiments/SanitizerLinearFix.java и HtmlUtilsSanitizeTest), обработчики событий — выражениями,
// которые начинают совпадение только с начала пробельного отрезка и не откатываются (`(?<!\s)\s++`, `\w++`).
private val SANITIZE_META_REFRESH_ATTR = Regex("""(?i)http-equiv\s*=\s*['"]?refresh""")
private val SANITIZE_EVENT_DOUBLE = Regex("""(?i)(?<!\s)\s++on\w++\s*=\s*"[^"]*"""")
private val SANITIZE_EVENT_SINGLE = Regex("""(?i)(?<!\s)\s++on\w++\s*=\s*'[^']*'""")
private val SANITIZE_EVENT_UNQUOTED = Regex("""(?i)(?<!\s)\s++on\w++\s*=\s*[^\s>"']+""")
private val SANITIZE_JS_URI_DOUBLE = Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*"\s*javascript:[^"]*"""")
private val SANITIZE_JS_URI_SINGLE = Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*'\s*javascript:[^']*'""")
private val SANITIZE_DATA_HTML_DOUBLE = Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*"\s*data:text/html[^"]*"""")
private val SANITIZE_DATA_HTML_SINGLE = Regex("""(?i)(href|src|action|formaction|xlink:href)\s*=\s*'\s*data:text/html[^']*'""")

/**
 * MAIL-09: теги, которые письму не нужны, а фишингу и слежке — да. Убираются только сами теги, текст между ними
 * остаётся (рассылки оборачивают в `<form>` всё письмо). Формы и поля ввода — поддельный вход «введите пароль»
 * прямо в письме; `<base>` — подмена адресов относительных ссылок; `<link>` — внешние стили и dns-prefetch/prefetch
 * (запрос на сервер отправителя при открытии, мимо запрета картинок: CSP их не закрывает); `<frame>`/`<frameset>` —
 * чужая страница внутри письма; SVG `<use>` — подгрузка внешнего SVG. Имя — целиком (`<input`, но не `<inputs`).
 */
private val SANITIZE_STRIPPED_TAGS = arrayOf(
    "form", "input", "button", "textarea", "select", "base", "link", "frameset", "frame", "use"
)

fun sanitizeEmailHtml(html: String): String {
    var h = html
    for (tag in arrayOf("script", "iframe", "object")) {
        h = removePairedTags(h, tag)
        h = removeOpenCloseTags(h, tag)
    }
    h = removeTags(h, "<embed", requireBoundary = true) { true }
    h = removePairedTags(h, "applet")
    h = removeOpenCloseTags(h, "applet")
    h = removeTags(h, "<meta", requireBoundary = true) { tag -> SANITIZE_META_REFRESH_ATTR.containsMatchIn(tag) }
    h = removeNamedTags(h, SANITIZE_STRIPPED_TAGS)
    return h
        .replace(SANITIZE_EVENT_DOUBLE, "")
        .replace(SANITIZE_EVENT_SINGLE, "")
        .replace(SANITIZE_EVENT_UNQUOTED, "")
        .replace(SANITIZE_JS_URI_DOUBLE, """$1="#"""")
        .replace(SANITIZE_JS_URI_SINGLE, """$1='#'""")
        .replace(SANITIZE_DATA_HTML_DOUBLE, """$1="#"""")
        .replace(SANITIZE_DATA_HTML_SINGLE, """$1='#'""")
}

/** [s] в позиции [at] начинается с [lower] без учёта регистра ASCII (как `(?i)` без UNICODE_CASE). */
private fun regionMatchesAscii(s: String, at: Int, lower: String): Boolean {
    if (at < 0 || at + lower.length > s.length) return false
    for (k in lower.indices) {
        val c = s[at + k]
        val lc = if (c in 'A'..'Z') c + 32 else c
        if (lc != lower[k]) return false
    }
    return true
}

/** Первое вхождение [lower] (без учёта регистра ASCII) в [s] начиная с [from], или -1. Линейно: [lower] короткий. */
private fun indexOfAscii(s: String, lower: String, from: Int): Int {
    val first = lower[0]
    var i = s.indexOf(first, from)
    while (i >= 0) {
        if (regionMatchesAscii(s, i, lower)) return i
        i = s.indexOf(first, i + 1)
    }
    return -1
}

/** Удаляет `<tag…>…</tag>` целиком (то же, что `(?si)<tag[^>]*>.*?</tag>`). */
private fun removePairedTags(s: String, tag: String): String {
    val open = "<$tag"
    val close = "</$tag>"
    var out: StringBuilder? = null
    var copyFrom = 0
    var p = indexOfAscii(s, open, 0)
    while (p >= 0) {
        val gt = s.indexOf('>', p + open.length)
        if (gt < 0) break                         // дальше нет '>' — совпадений нет
        val c = indexOfAscii(s, close, gt + 1)
        if (c < 0) break                          // дальше нет закрывающего — совпадений нет
        val sb = out ?: StringBuilder(s.length).also { out = it }
        sb.append(s, copyFrom, p)
        copyFrom = c + close.length
        p = indexOfAscii(s, open, copyFrom)
    }
    val sb = out ?: return s
    sb.append(s, copyFrom, s.length)
    return sb.toString()
}

/**
 * Одним проходом заменяет пробелом теги `<name…>` и `</name…>` с именем из [names] целиком (после имени не
 * буква/цифра/`_`/`-`: `<input`, но не `<inputs>`) — то же, что
 * `(?i)</?(?:name1|name2…)(?![\p{L}\p{Nd}_-])[^>]*>` → `" "`. Пробел, а не пустота: из остатков вокруг удалённого тега
 * не собирается новый (`<fo<input>rm>` даёт `<fo rm>`, а не `<form>`); в тексте пробелы схлопываются.
 */
private fun removeNamedTags(s: String, names: Array<String>): String {
    var out: StringBuilder? = null
    var copyFrom = 0
    var lt = s.indexOf('<')
    while (lt >= 0) {
        val nameAt = if (lt + 1 < s.length && s[lt + 1] == '/') lt + 2 else lt + 1
        val name = names.firstOrNull { name ->
            val after = nameAt + name.length
            regionMatchesAscii(s, nameAt, name) &&
                (after >= s.length || !(s[after].isLetterOrDigit() || s[after] == '_' || s[after] == '-'))
        }
        if (name != null) {
            val gt = s.indexOf('>', nameAt + name.length)
            if (gt < 0) break
            val sb = out ?: StringBuilder(s.length).also { out = it }
            sb.append(s, copyFrom, lt).append(' ')
            copyFrom = gt + 1
            lt = s.indexOf('<', copyFrom)
        } else {
            lt = s.indexOf('<', lt + 1)
        }
    }
    val sb = out ?: return s
    sb.append(s, copyFrom, s.length)
    return sb.toString()
}

/** Удаляет одиночные `<tag…>` и `</tag…>` (то же, что `(?i)</?tag[^>]*>`). */
private fun removeOpenCloseTags(s: String, tag: String): String {
    var out: StringBuilder? = null
    var copyFrom = 0
    var lt = s.indexOf('<')
    while (lt >= 0) {
        val nameAt = if (lt + 1 < s.length && s[lt + 1] == '/') lt + 2 else lt + 1
        if (regionMatchesAscii(s, nameAt, tag)) {
            val gt = s.indexOf('>', nameAt + tag.length)
            if (gt < 0) break
            val sb = out ?: StringBuilder(s.length).also { out = it }
            sb.append(s, copyFrom, lt)
            copyFrom = gt + 1
            lt = s.indexOf('<', copyFrom)
        } else {
            lt = s.indexOf('<', lt + 1)
        }
    }
    val sb = out ?: return s
    sb.append(s, copyFrom, s.length)
    return sb.toString()
}

/**
 * Удаляет теги, начинающиеся с [lowerOpen] (например `<embed`), до первого `>` включительно, если [shouldRemove]
 * для текста тега. [requireBoundary] — после имени не буква/цифра/`_` (как `\b`).
 */
private inline fun removeTags(s: String, lowerOpen: String, requireBoundary: Boolean, shouldRemove: (String) -> Boolean): String {
    var out: StringBuilder? = null
    var copyFrom = 0
    var p = indexOfAscii(s, lowerOpen, 0)
    while (p >= 0) {
        val after = p + lowerOpen.length
        if (requireBoundary && after < s.length && (s[after].isLetterOrDigit() || s[after] == '_')) {
            p = indexOfAscii(s, lowerOpen, p + 1)
            continue
        }
        val gt = s.indexOf('>', after)
        if (gt < 0) break
        if (shouldRemove(s.substring(after, gt))) {
            val sb = out ?: StringBuilder(s.length).also { out = it }
            sb.append(s, copyFrom, p)
            copyFrom = gt + 1
            p = indexOfAscii(s, lowerOpen, copyFrom)
        } else {
            // Тег без совпадения: начало внутри него дало бы тот же `>` и меньший текст — тоже без совпадения
            p = indexOfAscii(s, lowerOpen, gt + 1)
        }
    }
    val sb = out ?: return s
    sb.append(s, copyFrom, s.length)
    return sb.toString()
}

/**
 * Email HTML часто приходит как fragment без <html>/<body>:
 * например только <br>, <strong>, <table>, <blockquote>.
 * Для Exchange/Outlook это нормальный сценарий, поэтому не ограничиваемся
 * только полными document wrappers.
 */
fun looksLikeHtmlEmailContent(text: String): Boolean =
    HTML_EMAIL_FRAGMENT.containsMatchIn(text)

fun looksLikeEncodedHtmlEmailContent(text: String): Boolean =
    ENCODED_HTML_EMAIL_FRAGMENT.containsMatchIn(text)

/**
 * Убирает из HTML блоки, текст которых не является содержимым: `<head>`, `<style>`, `<script>`,
 * комментарии (в т.ч. условные комментарии Word/Outlook). Без этого при снятии тегов CSS Outlook
 * («p.MsoNormal {…}») попадает в текстовое превью.
 */
fun stripNonContentHtml(html: String): String = html
    .replace(HtmlRegex.STYLE, "")
    .replace(HtmlRegex.SCRIPT, "")
    .replace(HtmlRegex.COMMENT, "")
    .replace(HtmlRegex.HEAD, "")

/**
 * Раскрывает HTML-сущности: базовые именованные и числовые (`&#1058;`, `&#x41;` — так Word/Outlook
 * кодирует не-ASCII при `charset=us-ascii`). `&amp;` — последним, чтобы `&amp;lt;` остался текстом `&lt;`.
 */
fun decodeHtmlEntities(text: String): String {
    if (text.indexOf('&') < 0) return text
    return text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(HtmlRegex.HTML_ENTITY) { HtmlRegex.decodeNumericEntity(it) }
        .replace("&amp;", "&")
}

/**
 * Видимый текст HTML (поиск, текстовые превью): без служебных блоков и тегов, блочные теги — переводы
 * строк (серия подряд — один), сущности раскрыты. Текст без HTML-разметки возвращается как есть.
 */
fun htmlVisibleText(text: String): String {
    if (!looksLikeHtmlEmailContent(text)) return text
    return decodeHtmlEntities(
        stripNonContentHtml(text)
            .replace(BLOCK_TAG_REGEX, "\n")
            .replace(HtmlRegex.TAG, "")
    )
        .replace('\u00A0', ' ')
        .replace(NEWLINE_RUN_REGEX, "\n")
        .trim()
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
    return stripNonContentHtml(text)
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

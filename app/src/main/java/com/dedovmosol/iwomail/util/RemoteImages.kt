package com.dedovmosol.iwomail.util

/**
 * Картинки из интернета в письме. По ним отправитель узнаёт, что письмо открыли, когда и с какого адреса (пиксели
 * отслеживания), поэтому по умолчанию они не загружаются — как в Outlook и OWA. Встроенные в письмо картинки
 * (cid:, data:) показываются всегда: они пришли вместе с письмом.
 */
object RemoteImages {

    /**
     * Ссылки на сеть, которые грузит отрисовка письма: src/srcset/background/poster у тегов и url(...) в CSS.
     * Ссылки (href) не в счёт — они открываются только по нажатию, во внешнем браузере.
     */
    private val RESOURCE_URL = Regex(
        """(?:(?<![\w:-])(?:src|background|poster)\s*+=\s*+["']?\s*+|\burl\(\s*+["']?\s*+)((?:https?:)?//)""",
        RegexOption.IGNORE_CASE
    )
    private val SRCSET = Regex(
        """(?<![\w:-])srcset\s*+=\s*+(?:"([^"]*+)"|'([^']*+)'|([^\s>"']++))""",
        RegexOption.IGNORE_CASE
    )

    private fun Char.isSrcsetSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000c'

    // URL is a non-whitespace token, not a comma-separated field: data: URLs contain commas.
    private fun srcsetCandidates(html: String, range: IntRange): Sequence<IntRange> = sequence {
        var p = range.first
        while (p <= range.last) {
            while (p <= range.last && (html[p].isSrcsetSpace() || html[p] == ',')) p++
            if (p > range.last) break
            val start = p
            while (p <= range.last && !html[p].isSrcsetSpace()) p++
            if (html[p - 1] == ',') {
                var end = p
                while (end > start && html[end - 1] == ',') end--
                yield(start until end)
                continue
            }
            var depth = 0
            while (p <= range.last) {
                when (html[p]) {
                    '(' -> depth++
                    ')' -> if (depth > 0) depth--
                    ',' -> if (depth == 0) break
                }
                p++
            }
            yield(start until p)
            if (p <= range.last) p++
        }
    }

    private fun srcsetValue(match: MatchResult): MatchGroup =
        checkNotNull(match.groups[1] ?: match.groups[2] ?: match.groups[3])

    private fun isRemote(html: String, p: Int) = html.startsWith("//", p) ||
        html.startsWith("https://", p, ignoreCase = true) || html.startsWith("http://", p, ignoreCase = true)

    /** В письме есть картинки (или другие ресурсы отрисовки) из интернета. */
    fun present(html: String): Boolean = RESOURCE_URL.containsMatchIn(html) || SRCSET.findAll(html).any { match ->
        srcsetCandidates(html, srcsetValue(match).range).any { isRemote(html, it.first) }
    }

    internal fun prepareForWebView(html: String, remoteAllowed: Boolean): String {
        if (!html.contains("//")) return html
        // A blocked high-DPI candidate does not make WebView retry the embedded img src.
        // Ignore blocked candidates on this render only; reload the original after consent.
        val filtered = SRCSET.replace(html) { match ->
            val value = srcsetValue(match)
            fun blocked(candidate: IntRange): Boolean = if (remoteAllowed) {
                html.startsWith("http://", candidate.first, ignoreCase = true)
            } else isRemote(html, candidate.first)
            val candidates = srcsetCandidates(html, value.range)
            if (candidates.none(::blocked)) match.value
            else {
                val available = candidates.filterNot(::blocked).joinToString(", ") { html.substring(it).trimEnd() }
                if (available.isEmpty()) "" else {
                    val from = value.range.first - match.range.first
                    val to = value.range.last + 1 - match.range.first
                    match.value.substring(0, from) + available + match.value.substring(to)
                }
            }
        }
        return if (remoteAllowed) resolveProtocolRelative(filtered) else filtered
    }

    /** Use HTTPS for network-path image URLs without giving the message a real origin or rebasing relative links. */
    private fun resolveProtocolRelative(html: String): String {
        if (!html.contains("//")) return html
        val positions = sortedSetOf<Int>()
        RESOURCE_URL.findAll(html).forEach { m ->
            val scheme = m.groups[1] ?: return@forEach
            if (scheme.value == "//") positions += scheme.range.first
        }
        for (match in SRCSET.findAll(html)) {
            for (candidate in srcsetCandidates(html, srcsetValue(match).range)) {
                if (html.startsWith("//", candidate.first)) positions += candidate.first
            }
        }
        if (positions.isEmpty()) return html
        return buildString {
            var from = 0
            for (p in positions) {
                append(html, from, p)
                append("https:")
                from = p
            }
            append(html, from, html.length)
        }
    }

    /** Адрес отправителя для списка доверенных: без имени и регистра. */
    fun senderKey(from: String): String = extractEmailAddress(from).trim().lowercase()

    /**
     * Загружать ли картинки письма от [from]: всегда (настройка), доверенный отправитель или пользователь нажал
     * «Показать» для этого письма.
     */
    fun allowed(alwaysLoad: Boolean, trustedSenders: Set<String>, from: String, shownForThisEmail: Boolean): Boolean =
        alwaysLoad || shownForThisEmail || senderKey(from).let { it.isNotEmpty() && it in trustedSenders }

    /**
     * Content-Security-Policy страницы письма. Без разрешения сеть закрыта и для картинок, и для шрифтов (тоже
     * пиксели отслеживания); стили только встроенные — внешний CSS и @import не грузятся никогда.
     */
    /**
     * MAIL-09: default-src не распространяется на отправку форм и `<base>` — их закрывают отдельно: форма письма
     * не отправит введённое, `<base>` не перенаправит относительные ссылки. Общая часть CSP письма и редактора (DRY).
     */
    const val NAVIGATION_LOCKDOWN = "form-action 'none'; base-uri 'none';"

    fun contentSecurityPolicy(nonce: String, remoteAllowed: Boolean): String {
        val network = if (remoteAllowed) " https:" else ""
        return "default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; " +
            "img-src data: cid: blob:$network; font-src data:$network; $NAVIGATION_LOCKDOWN"
    }
}

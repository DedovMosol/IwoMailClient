package com.dedovmosol.iwomail.eas

/**
 * Записи RSS-каналов, которые Outlook скачивает в ящик Exchange ([MS-OXORSS]): класс элемента IPM.Post.RSS, отправитель
 * (PidTagSenderName) — название канала, тело — описание записи и в конце ссылка на статью
 * (`<A HREF="…">View article...</A>`, та же, что PidLidPostRssItemLink). Ленты скачивает Outlook на компьютере; клиент
 * их только читает.
 */
object RssItems {

    private const val MESSAGE_CLASS = "IPM.Post.RSS"

    /** Запись RSS-канала (в том числе производные классы IPM.Post.RSS.*); регистр класса не важен. */
    fun isRss(messageClass: String?): Boolean =
        messageClass != null && (messageClass.equals(MESSAGE_CLASS, ignoreCase = true) ||
            messageClass.startsWith("$MESSAGE_CLASS.", ignoreCase = true))

    /**
     * Ссылка на статью: последняя ссылка http(s) тела — Outlook добавляет её после описания. Текстовое тело — последний
     * адрес http(s) в тексте. null — ссылки нет (тело ещё не загружено или обрезано).
     */
    fun articleLink(body: String): String? {
        val fromAnchors = ANCHOR_HREF.findAll(body).map { decodeEntities(it.groupValues[2].trim()) }.lastOrNull(::isWebLink)
        return fromAnchors ?: if (ANY_ANCHOR.containsMatchIn(body)) null else BARE_URL.findAll(body)
            // Знак препинания после адреса в тексте — не часть адреса
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']') }.lastOrNull(::isWebLink)
    }

    private fun isWebLink(url: String): Boolean = try {
        val uri = java.net.URI(url)
        (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    } catch (_: java.net.URISyntaxException) {
        false
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'")
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)

    private val ANCHOR_HREF = Regex("""<a\b[^>]*?\bhref\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val ANY_ANCHOR = Regex("""<a\b""", RegexOption.IGNORE_CASE)
    private val BARE_URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
}

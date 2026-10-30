package com.dedovmosol.iwomail.eas

/**
 * Отписка от рассылки по заголовкам письма.
 *
 * - List-Unsubscribe (RFC 2369 §3.2): URI в угловых скобках через запятую, в порядке предпочтения отправителя;
 *   пробелы внутри скобок не в счёт, текст вне скобок — комментарии. Берутся https: и mailto:. http: — нет: запрос по
 *   открытому каналу видят и подменяют по пути, а в адресе отписки — идентификатор получателя.
 * - List-Unsubscribe-Post: List-Unsubscribe=One-Click (RFC 8058 §3.1) — отписка одним POST на https-адрес, без
 *   открытия страницы.
 */
data class ListUnsubscribe(val https: String?, val mailto: Mailto?, val oneClick: Boolean) {

    /** Письмо отписки (mailto:, RFC 6068): адрес, тема и текст. */
    data class Mailto(val to: String, val subject: String, val body: String)

    /** Способ отписки: одним запросом, письмом или на странице — как в Gmail. */
    sealed interface Method {
        data class OneClick(val url: String) : Method
        data class Mail(val mailto: Mailto) : Method
        data class Web(val url: String) : Method
    }

    /** Предпочтительный способ: one-click не требует от пользователя ничего, письмо — без браузера. */
    val method: Method
        get() = when {
            oneClick && https != null -> Method.OneClick(https)
            mailto != null -> Method.Mail(mailto)
            else -> Method.Web(requireNotNull(https))
        }

    companion object {
        /** Длиннее — не заголовок рассылки, а мусор: в базу не пишем. */
        const val MAX_HEADER_LENGTH = 2048

        /** Тело POST отписки одним запросом (RFC 8058 §3.1). */
        const val ONE_CLICK_BODY = "List-Unsubscribe=One-Click"

        private const val DEFAULT_SUBJECT = "unsubscribe"
        private val URI = Regex("<([^>]*)>")

        /**
         * Значение List-Unsubscribe и поддержка one-click из верхних заголовков MIME; null — отписки нет.
         *
         * One-click — только если подпись DKIM охватывает оба заголовка (RFC 8058 §4: без неё получателю не следует
         * предлагать one-click). Проверить саму подпись на устройстве нечем (нужен DNS и ключ отправителя), поэтому
         * проверяется её охват (тег h=): письмо, где заголовки отписки дописаны после подписи, one-click не получит —
         * останется отписка письмом или на странице, с подтверждением.
         */
        fun fromHeaders(topLevelHeaders: String): Pair<String, Boolean>? {
            val value = mimeHeaderValue(topLevelHeaders, "List-Unsubscribe")
                ?.takeIf { it.length <= MAX_HEADER_LENGTH && parse(it, oneClick = false) != null }
                ?: return null
            val post = mimeHeaderValue(topLevelHeaders, "List-Unsubscribe-Post")
            val oneClick = post?.filterNot(Char::isWhitespace)?.equals(ONE_CLICK_BODY, ignoreCase = true) == true &&
                dkimCoversUnsubscribe(topLevelHeaders)
            return value to oneClick
        }

        /** Есть подпись DKIM, в тег h= которой входят List-Unsubscribe и List-Unsubscribe-Post (RFC 6376 §3.5). */
        internal fun dkimCoversUnsubscribe(topLevelHeaders: String): Boolean =
            mimeHeaderValues(topLevelHeaders, "DKIM-Signature").any { signature ->
                val signed = DKIM_TAG_H.find(signature)?.groupValues?.get(1)
                    ?.split(':')?.map { it.filterNot(Char::isWhitespace).lowercase() }?.toSet()
                    .orEmpty()
                "list-unsubscribe" in signed && "list-unsubscribe-post" in signed
            }

        private val DKIM_TAG_H = Regex("""(?:^|;)\s*h\s*=\s*([^;]*)""")

        /**
         * Предлагать отписку в папке [folderType]: не в своих письмах и не в «Нежелательной почте» — отписка от спама
         * лишь подтверждает отправителю, что адрес живой.
         */
        fun offeredIn(folderType: Int?): Boolean = folderType !in NOT_OFFERED_FOLDERS

        private val NOT_OFFERED_FOLDERS = setOf(FolderType.SENT_ITEMS, FolderType.DRAFTS, FolderType.OUTBOX, FolderType.JUNK_EMAIL)

        /** Сохранённое значение заголовка → способы отписки; null — ни https:, ни mailto:. */
        fun parse(value: String?, oneClick: Boolean): ListUnsubscribe? {
            if (value.isNullOrBlank()) return null
            val uris = URI.findAll(value).map { match -> match.groupValues[1].filterNot(Char::isWhitespace) }.toList()
            val https = uris.firstOrNull(::isHttps)
            val mailto = uris.firstNotNullOfOrNull(::parseMailto)
            if (https == null && mailto == null) return null
            return ListUnsubscribe(https, mailto, oneClick = oneClick && https != null)
        }

        private fun isHttps(uri: String): Boolean = try {
            val parsed = java.net.URI(uri)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null
        } catch (_: java.net.URISyntaxException) {
            false
        }

        /** mailto:адрес?subject=…&body=… (RFC 6068): первый адрес; «+» — сам символ, а не пробел. */
        private fun parseMailto(uri: String): Mailto? {
            if (!uri.startsWith("mailto:", ignoreCase = true)) return null
            val rest = uri.substring("mailto:".length)
            val fields = rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { field ->
                decode(field.substringBefore('=')).lowercase() to decode(field.substringAfter('=', ""))
            }
            val to = decode(rest.substringBefore('?')).split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: fields["to"]?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
                ?: return null
            if (!EMAIL.matches(to)) return null
            return Mailto(to, fields["subject"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SUBJECT, fields["body"].orEmpty())
        }

        private val EMAIL = Regex("""[^\s@<>()",;]+@[^\s@<>()",;]+\.[^\s@<>()",;]+""")

        private fun decode(value: String): String = try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }
    }
}

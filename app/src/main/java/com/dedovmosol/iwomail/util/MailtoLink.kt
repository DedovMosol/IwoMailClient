package com.dedovmosol.iwomail.util

import java.util.Locale

/**
 * Ссылка mailto: (RFC 6068) — один разбор для ссылок в письмах и событиях, mailto: от других приложений и отписки
 * по письму (DRY).
 *
 * `mailto:адреса?имя=значение&…`: адреса до «?» и в поле `to` — через запятую; поля `cc`, `bcc`, `subject`, `body`;
 * имя поля — без учёта регистра; значения — %-кодировка UTF-8; повтор поля темы или текста не заменяет первое.
 * «+» — сам символ (адрес `user+tag@…`), а не пробел: это не форма HTML (RFC 6068 §5). Тема — одна строка: перевод
 * строки в ней — попытка подставить свой заголовок письма. Переводы строк текста — `\n`.
 */
data class MailtoLink(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null
) {
    companion object {
        private const val SCHEME = "mailto:"

        /** null — не mailto:. Пустая ссылка `mailto:` — пустое письмо. */
        fun parse(uri: String?): MailtoLink? {
            if (uri == null || !uri.startsWith(SCHEME, ignoreCase = true)) return null
            val rest = uri.substring(SCHEME.length)
            val to = mutableListOf<String>()
            val cc = mutableListOf<String>()
            val bcc = mutableListOf<String>()
            var subject: String? = null
            var body: String? = null
            to += addresses(decode(rest.substringBefore('?')))
            for (field in rest.substringAfter('?', "").split('&')) {
                if (field.isEmpty()) continue
                val value = decode(field.substringAfter('=', ""))
                when (decode(field.substringBefore('=')).lowercase(Locale.ROOT)) {
                    "to" -> to += addresses(value)
                    "cc" -> cc += addresses(value)
                    "bcc" -> bcc += addresses(value)
                    "subject" -> if (subject == null) subject = value.replace(LINE_BREAKS, " ").trim()
                    "body" -> if (body == null) body = value.replace("\r\n", "\n").replace('\r', '\n')
                }
            }
            return MailtoLink(to.distinct(), cc.distinct(), bcc.distinct(), subject, body)
        }

        private val LINE_BREAKS = Regex("[\\r\\n]+")

        private fun addresses(value: String): List<String> =
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun decode(value: String): String = try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            value
        }
    }
}

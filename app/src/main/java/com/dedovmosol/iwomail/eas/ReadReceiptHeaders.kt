package com.dedovmosol.iwomail.eas

/**
 * Запрос отчёта о прочтении во входящем письме — адрес, куда его просят отправить.
 *
 * Только Disposition-Notification-To (RFC 8098 §2.1) и его старый аналог X-Confirm-Reading-To. Return-Receipt-To —
 * запрос отчёта о ДОСТАВКЕ (Exchange сопоставляет его с PidTagOriginatorDeliveryReportRequested): прежде он тоже
 * вызывал вопрос об отчёте о прочтении, хотя отправитель о нём не просил.
 *
 * Значение — список адресов RFC 5322 (`"Имя" <адрес>`, `адрес`), может быть свёрнуто на несколько строк; берётся
 * первый адрес. null — отчёт не запрошен.
 */
internal fun readReceiptRequestAddress(topLevelHeaders: String): String? {
    val value = mimeHeaderValue(topLevelHeaders, "Disposition-Notification-To")
        ?: mimeHeaderValue(topLevelHeaders, "X-Confirm-Reading-To")
        ?: return null
    return firstAddress(value)
}

/** Значение заголовка [name] с развёрнутыми продолжениями (RFC 5322 §2.2.3); null — заголовка нет. */
internal fun mimeHeaderValue(headers: String, name: String): String? = mimeHeaderValues(headers, name).firstOrNull()

/** Все значения заголовка [name] по порядку (DKIM-Signature, Received бывают по нескольку), с развёрнутыми продолжениями. */
internal fun mimeHeaderValues(headers: String, name: String): List<String> {
    val lines = headers.replace("\r\n", "\n").split('\n')
    val values = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].startsWith("$name:", ignoreCase = true)) {
            i++
            continue
        }
        val value = StringBuilder(lines[i].substringAfter(':'))
        i++
        while (i < lines.size && lines[i].isNotEmpty() && lines[i][0].isWhitespace()) {
            value.append(' ').append(lines[i].trim())
            i++
        }
        value.toString().trim().takeIf { it.isNotEmpty() }?.let(values::add)
    }
    return values
}

/** Первый адрес списка: в угловых скобках, иначе первое слово с @. Запятые внутри кавычек имени — не разделитель. */
private fun firstAddress(value: String): String? {
    value.substringAfter('<', "").substringBefore('>', "").trim().takeIf { it.contains('@') }?.let { return it }
    return value.split(',', ';', ' ').map { it.trim().trim('"', '<', '>') }.firstOrNull { it.contains('@') }
}

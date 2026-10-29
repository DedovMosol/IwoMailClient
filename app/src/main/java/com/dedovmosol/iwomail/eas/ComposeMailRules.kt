package com.dedovmosol.iwomail.eas

/**
 * Правила команд ComposeMail (SendMail, SmartForward) — чистые функции, общие для всех путей отправки.
 */

private val EMAIL_ADDRESS = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/**
 * Ответ ComposeMail — успех: без Status (EAS 12.x и пустое тело EAS 14), Status 1 или 118 (MessagePreviouslySent,
 * MS-ASCMD 2.2.2: письмо с этим ClientId уже отправлено — повтор после потерянного ответа не дублирует письмо).
 */
internal fun isComposeMailSuccess(status: String?): Boolean = status == null || status == "1" || status == "118"

/** Текст ошибки HTTP команды ComposeMail (локализуется по префиксу «Ошибка отправки письма (»). */
internal fun composeHttpError(code: Int): String = "Ошибка отправки письма (HTTP $code)"

/** EAS 12.x: SmartForward — исходное письмо в параметрах URL (MS-ASCMD 2.2.1.19), копия — в «Отправленные». */
internal fun smartForwardQuery(collectionId: String, itemId: String): String =
    "&CollectionId=${java.net.URLEncoder.encode(collectionId, "UTF-8")}" +
        "&ItemId=${java.net.URLEncoder.encode(itemId, "UTF-8")}" +
        "&SaveInSent=T"

/**
 * Получатели письма: хотя бы один в Кому, Копии или Скрытой копии, все адреса — вида имя@домен. null — всё верно.
 * Раньше требовался непустой «Кому»: письмо только со скрытыми копиями не отправлялось вовсе.
 */
internal fun recipientsError(to: String, cc: String, bcc: String): String? {
    val all = listOf(to, cc, bcc).flatMap { field -> field.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() } }
    if (all.isEmpty()) return "Неверный адрес получателя: нет получателей"
    val invalid = all.firstOrNull { !EMAIL_ADDRESS.matches(it) } ?: return null
    return "Неверный адрес получателя: $invalid"
}

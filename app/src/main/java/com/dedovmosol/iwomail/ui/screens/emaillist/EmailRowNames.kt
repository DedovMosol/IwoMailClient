package com.dedovmosol.iwomail.ui.screens.emaillist

/**
 * Имя первого получателя для строки списка (Отправленные, Черновики): `"Иван Петров" <ivan@corp>` → Иван Петров,
 * `<ivan@corp>` и `ivan@corp` → ivan. Пусто — получателей нет.
 */
internal fun firstRecipientName(to: String): String {
    val field = to.trim()
    if (field.isEmpty()) return ""
    val name = field.substringBefore("<").trim().trim('"').trim()
    if (field.contains("<") && name.isNotEmpty()) return name
    val address = if (field.contains("<")) field.substringAfter("<").substringBefore(">") else field
    return address.substringBefore("@").substringBefore(",").substringBefore(";").trim().ifEmpty { field }
}

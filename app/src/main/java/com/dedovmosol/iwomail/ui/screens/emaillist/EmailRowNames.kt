package com.dedovmosol.iwomail.ui.screens.emaillist

import com.dedovmosol.iwomail.eas.FolderType

/** Отправленные и черновики в списке показывают получателя, не From. */
internal fun folderShowsRecipient(type: Int): Boolean =
    type == FolderType.SENT_ITEMS || type == FolderType.DRAFTS

/** Поле для имени в строке: Кому, иначе Копия, иначе скрытая копия. */
internal fun firstRecipientField(to: String, cc: String = "", bcc: String? = null): String =
    to.trim().ifEmpty { cc.trim() }.ifEmpty { bcc.orEmpty().trim() }

/**
 * Имя первого получателя для строки списка (Отправленные, Черновики): `"Иван Петров" <ivan@corp>` → Иван Петров,
 * `<ivan@corp>` и `ivan@corp` → ivan. Кому пусто — Копия, оба пусты — скрытая копия. Пусто — получателей нет.
 */
internal fun firstRecipientName(to: String, cc: String = "", bcc: String? = null): String {
    val field = firstRecipientField(to, cc, bcc)
    if (field.isEmpty()) return ""
    val name = field.substringBefore("<").trim().trim('"').trim()
    if (field.contains("<") && name.isNotEmpty()) return name
    val address = if (field.contains("<")) field.substringAfter("<").substringBefore(">") else field
    return address.substringBefore("@").substringBefore(",").substringBefore(";").trim().ifEmpty { field }
}

package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.util.CalendarCategories

/**
 * Категории события в запросах записи (DRY для EAS Add/Change и EWS CreateItem/UpdateItem).
 *
 * EAS ([MS-ASCAL] Categories/Category, code page Calendar): `<calendar:Categories>` с дочерними
 * `<calendar:Category>`; в запросе SHOULD быть не больше 300 Category. Пустой Categories допустим
 * (схема: sequence minOccurs=0) — так категории очищаются в Change.
 *
 * EWS: `Categories` (ArrayOfStringsType из `String`) стоит в CalendarItem после Body и до
 * ReminderIsSet (порядок схемы ItemType); в UpdateItem — FieldURI `item:Categories`.
 */
internal object CalendarCategoriesXml {
    const val MAX_CATEGORIES = 300

    private fun normalized(categories: List<String>): List<String> =
        categories.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { CalendarCategories.key(it) }.take(MAX_CATEGORIES)

    /** EAS ApplicationData. Пустой список — пустой элемент (очистка). */
    fun eas(categories: List<String>, escapeXml: (String) -> String): String {
        val names = normalized(categories)
        if (names.isEmpty()) return "<calendar:Categories/>"
        return names.joinToString("", "<calendar:Categories>", "</calendar:Categories>") {
            "<calendar:Category>${escapeXml(it)}</calendar:Category>"
        }
    }

    /** EWS CreateItem: элементы CalendarItem в default namespace types (без префикса). Пусто — ничего. */
    fun ewsCreate(categories: List<String>, escapeXml: (String) -> String): String {
        val names = normalized(categories)
        if (names.isEmpty()) return ""
        return names.joinToString("", "<Categories>", "</Categories>") { "<String>${escapeXml(it)}</String>" }
    }

    /** EWS UpdateItem: SetItemField со списком или DeleteItemField, если категорий не осталось. */
    fun ewsUpdate(categories: List<String>, escapeXml: (String) -> String): String {
        val names = normalized(categories)
        if (names.isEmpty()) {
            return """<t:DeleteItemField><t:FieldURI FieldURI="item:Categories"/></t:DeleteItemField>"""
        }
        return """<t:SetItemField><t:FieldURI FieldURI="item:Categories"/><t:CalendarItem>""" +
            names.joinToString("", "<t:Categories>", "</t:Categories>") { "<t:String>${escapeXml(it)}</t:String>" } +
            "</t:CalendarItem></t:SetItemField>"
    }
}

package com.dedovmosol.iwomail.eas

/**
 * EWS-1: устойчивый разбор идентификаторов EWS (ItemId / ChangeKey).
 *
 * Прежние regex'ы требовали строгого порядка атрибутов (Id перед ChangeKey) и часто жёсткого
 * префикса `t:`. По XML-схеме порядок атрибутов НЕ гарантирован, а префикс пространства имён
 * может отличаться между серверами. Эти функции извлекают Id и ChangeKey НЕЗАВИСИМО и допускают
 * любой (или отсутствующий) префикс. Поведение на реальном выводе Exchange 2007 SP2
 * (`<t:ItemId Id="…" ChangeKey="…"/>`) полностью сохраняется, плюс добавляется устойчивость
 * к перестановке атрибутов и смене префикса.
 *
 * Чистые функции — покрыты EwsIdsTest.
 */
object EwsIds {
    // Открывающий тег именно ItemId (не ReferenceItemId/ParentItemId — там перед "ItemId" нет '<').
    // Группа 1 — блок атрибутов до закрытия тега ('>' или '/>').
    private val ITEM_ID_TAG = Regex("<(?:[A-Za-z][\\w.-]*:)?ItemId\\b([^>]*?)/?>")
    private val ID_ATTR = Regex("\\bId=\"([^\"]+)\"")
    private val CHANGE_KEY_ATTR = Regex("\\bChangeKey=\"([^\"]+)\"")

    /** Первый ItemId → его Id (или null). Порядок атрибутов и префикс не важны. */
    fun parseItemId(xml: String): String? {
        val attrs = ITEM_ID_TAG.find(xml)?.groupValues?.get(1) ?: return null
        return ID_ATTR.find(attrs)?.groupValues?.get(1)
    }

    /** Первый ItemId → (Id, ChangeKey), только если ОБА атрибута присутствуют; иначе null. */
    fun parseItemIdAndChangeKey(xml: String): Pair<String, String>? {
        val attrs = ITEM_ID_TAG.find(xml)?.groupValues?.get(1) ?: return null
        val id = ID_ATTR.find(attrs)?.groupValues?.get(1) ?: return null
        val changeKey = CHANGE_KEY_ATTR.find(attrs)?.groupValues?.get(1) ?: return null
        return id to changeKey
    }

    /** Первый ChangeKey из ItemId (или null). */
    fun parseChangeKey(xml: String): String? = parseItemIdAndChangeKey(xml)?.second

    /** ВСЕ ItemId, у которых есть и Id, и ChangeKey (для пакетных операций, напр. mark-read). */
    fun parseAllItemIdsAndChangeKeys(xml: String): List<Pair<String, String>> =
        ITEM_ID_TAG.findAll(xml).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val id = ID_ATTR.find(attrs)?.groupValues?.get(1)
            val changeKey = CHANGE_KEY_ATTR.find(attrs)?.groupValues?.get(1)
            if (id != null && changeKey != null) id to changeKey else null
        }.toList()
}

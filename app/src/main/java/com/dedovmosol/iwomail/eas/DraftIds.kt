package com.dedovmosol.iwomail.eas

/**
 * Идентификаторы черновиков. EAS ServerId на Exchange — «папка:элемент» ("5:12"); EWS ItemId — base64,
 * двоеточия в нём не бывает; local_draft_ — черновик, ещё не сохранённый на сервере.
 */
internal object DraftIds {
    const val LOCAL_PREFIX = "local_draft_"

    /** EWS-id черновика не определить однозначно (несколько черновиков с такой темой или ни одного). */
    const val NOT_RESOLVED = "DRAFT_NOT_RESOLVED"

    fun isLocal(id: String): Boolean = id.startsWith(LOCAL_PREFIX)

    fun isEwsItemId(id: String): Boolean = id.isNotBlank() && !isLocal(id) && !id.contains(':')

    /** Единственный id среди найденных (кроме [excludeId]); несколько или ни одного — null (не угадываем). */
    fun uniqueMatch(ids: List<String>, excludeId: String?): String? =
        ids.distinct().filter { it != excludeId }.singleOrNull()
}

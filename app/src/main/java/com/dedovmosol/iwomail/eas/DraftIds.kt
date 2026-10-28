package com.dedovmosol.iwomail.eas

/**
 * Идентификаторы черновиков. EAS ServerId на Exchange — «папка:элемент» ("5:12"); EWS ItemId — base64,
 * двоеточия в нём не бывает; local_draft_ — черновик, ещё не сохранённый на сервере.
 *
 * Локальные id двух видов (оба с префиксом [LOCAL_PREFIX], проверки startsWith("local_draft_") их видят):
 * - [newDeviceOnly] — черновик точно только на устройстве (нет связи, сервер отказал, режим «на
 *   устройстве»). Синхронизация загружает его и никогда не удаляет «как дубликат» по теме.
 * - [newUnconfirmed] — сервер ответил успехом, но id не вернул: черновик, скорее всего, уже на сервере,
 *   синхронизация сверяет его с серверными по теме и дате.
 * Прежние (до разделения) id — "local_draft_<время>_<uuid>" — сверяются по теме, как раньше.
 */
internal object DraftIds {
    const val LOCAL_PREFIX = "local_draft_"
    private const val DEVICE_ONLY_PREFIX = "${LOCAL_PREFIX}d_"
    private const val UNCONFIRMED_PREFIX = "${LOCAL_PREFIX}u_"

    /** EWS-id черновика не определить однозначно (несколько черновиков с такой темой или ни одного). */
    const val NOT_RESOLVED = "DRAFT_NOT_RESOLVED"

    fun isLocal(id: String): Boolean = id.startsWith(LOCAL_PREFIX)

    fun isEwsItemId(id: String): Boolean = id.isNotBlank() && !isLocal(id) && !id.contains(':')

    fun newDeviceOnly(): String = DEVICE_ONLY_PREFIX + suffix()

    fun newUnconfirmed(): String = UNCONFIRMED_PREFIX + suffix()

    /** Локальный черновик может уже быть на сервере (успех без id или прежний формат id). */
    fun mayExistOnServer(id: String): Boolean = isLocal(id) && !id.startsWith(DEVICE_ONLY_PREFIX)

    /** Сервер подтвердил создание, но id не вернул. */
    fun isUnconfirmed(id: String): Boolean = id.startsWith(UNCONFIRMED_PREFIX)

    /** Единственный id среди найденных (кроме [excludeId]); несколько или ни одного — null (не угадываем). */
    fun uniqueMatch(ids: List<String>, excludeId: String?): String? =
        ids.distinct().filter { it != excludeId }.singleOrNull()

    /**
     * Отметка «заменяет» (EmailEntity.replacesServerId) → id прежних версий на сервере, которые ещё не удалось
     * удалить. Их может быть несколько (каждая правка без удаления прежней добавляет одну), разделитель — перевод
     * строки: в EAS ServerId и EWS ItemId (base64) его не бывает.
     */
    fun replacedIds(marker: String?): List<String> =
        marker?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    /** id прежних версий → отметка; пусто — null. */
    fun replacedMarker(ids: Iterable<String>): String? =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n").ifEmpty { null }

    /** Отметки [a] и [b] вместе, кроме [except] (id самой записи: себя она не заменяет). */
    fun mergeReplaced(a: String?, b: String?, except: String? = null): String? =
        replacedMarker((replacedIds(a) + replacedIds(b)).filter { it != except })

    private fun suffix() = "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
}

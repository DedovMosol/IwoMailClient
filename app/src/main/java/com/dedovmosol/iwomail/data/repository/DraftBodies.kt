package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.util.stripHtml

/**
 * Какое тело черновика открыть на редактирование: сохранённое на устройстве или свежее с сервера.
 * Чистая логика (тестируется без сети и БД).
 *
 * Сравниваются тексты, не HTML: Exchange переписывает разметку (обёртка html/head, стили), картинки
 * в сохранённой копии — data: URL, на сервере — cid:.
 */
internal object DraftBodies {

    /** Расхождение часов телефона и сервера, в пределах которого «сохранено позже» не считается. */
    const val CLOCK_SKEW_MS = 2 * 60 * 1000L

    /** Прежняя синхронизация хранила тело черновика обрезанным по TruncationSize = 10240 байт. */
    private const val LEGACY_TRUNCATION_BYTES = 10_240

    /** Хвост обрезанного HTML (оборванный тег или сущность) превращается в мусор — его не сравниваем. */
    private const val CUT_TAIL_CHARS = 64

    /**
     * Тело для редактора; null — оставить сохранённое ([local]).
     * - На сервере тела нет (не загрузилось) — сохранённое.
     * - Сохранённое пустое, обрезано прежней синхронизацией или старше версии на сервере — с сервера.
     * - Тексты совпадают — сохранённое: в нём уже есть картинки, повторно их не скачиваем.
     * - Тексты различаются, а сохранено позже изменения на сервере ([serverModifiedAt], с запасом на
     *   расхождение часов) — сохранённое: это правка, которую прежняя версия приложения не довезла
     *   до сервера. Время изменения не известно — с сервера (там правка из Outlook, которую
     *   синхронизация ещё не принесла).
     */
    fun choose(local: String, localSavedAt: Long, server: String?, serverModifiedAt: Long?): String? {
        if (server.isNullOrBlank()) return null
        if (local.isBlank()) return server
        val localText = text(local)
        val serverText = text(server)
        if (localText == serverText) return null
        if (isLegacyTruncation(local, localText, serverText)) return server
        if (serverModifiedAt != null && localSavedAt > serverModifiedAt + CLOCK_SKEW_MS) return null
        return server
    }

    /** Черновик на сервере изменён позже сохранения на устройстве (с запасом на расхождение часов); время не известно — нет. */
    fun serverIsNewer(localSavedAt: Long, serverModifiedAt: Long?): Boolean =
        serverModifiedAt != null && serverModifiedAt > localSavedAt + CLOCK_SKEW_MS

    /** Версии различаются по тексту (для решения, нужны ли подробности с сервера). */
    fun differ(local: String, server: String?): Boolean =
        !server.isNullOrBlank() && local.isNotBlank() && text(local) != text(server)

    fun text(html: String): String = stripHtml(html)

    private fun isLegacyTruncation(localHtml: String, localText: String, serverText: String): Boolean {
        val bytes = localHtml.toByteArray(Charsets.UTF_8).size
        if (bytes < LEGACY_TRUNCATION_BYTES * 3 / 4 || bytes > LEGACY_TRUNCATION_BYTES + 16) return false
        if (localText.length >= serverText.length) return false
        return serverText.startsWith(localText.dropLast(CUT_TAIL_CHARS))
    }
}

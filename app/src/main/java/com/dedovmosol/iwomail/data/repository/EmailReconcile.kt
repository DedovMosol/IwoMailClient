package com.dedovmosol.iwomail.data.repository

/**
 * После full resync (EAS 12.1 Status 3/12) локальные id, которых нет в ответе сервера — orphans.
 * Письмо с [pending.move] уже лежит в корзине/спаме на устройстве, на сервере его там ещё нет:
 * его нельзя стирать как orphan, иначе офлайн-перенос пропадёт.
 */
internal fun fullResyncOrphanIds(
    localIds: List<String>,
    serverIds: Set<String>,
    pendingMoveIds: Set<String>
): List<String> = localIds.filter { it !in serverIds && it !in pendingMoveIds }

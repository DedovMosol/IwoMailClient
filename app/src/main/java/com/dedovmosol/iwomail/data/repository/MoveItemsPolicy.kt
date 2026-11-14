package com.dedovmosol.iwomail.data.repository

/**
 * Ответ MoveItems, когда ни один элемент не принят ([EasClient.moveItems]).
 * Формат: `MOVEITEMS_ALL_FAILED:status=N,failed=…,locked=…`
 *
 * [MS-ASCMD] Status=1: Sync папки-источника и повтор. Status=7: повтор без Sync (замок).
 * Остальные коды (2, 4, 5) — не синхронизировать папку зря.
 */
internal fun moveItemsAllFailed(errorMessage: String): Boolean =
    errorMessage.contains("MOVEITEMS_ALL_FAILED")

internal fun moveItemsNeedsSrcSync(errorMessage: String): Boolean {
    if (!moveItemsAllFailed(errorMessage)) return false
    val status = MOVE_ITEMS_STATUS.find(errorMessage)?.groupValues?.get(1)?.toIntOrNull()
    return status == null || status == 1
}

private val MOVE_ITEMS_STATUS = Regex("status=(\\d+)")

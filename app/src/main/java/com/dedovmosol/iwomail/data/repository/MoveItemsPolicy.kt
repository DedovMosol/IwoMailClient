package com.dedovmosol.iwomail.data.repository

/**
 * [MS-ASCMD] MoveItems Status=1 (Invalid source): сначала Sync папки-источника, потом повтор.
 * Не синхронизировать папку перед каждым переносом — на Exchange 2010/2013 это лишний Sync под шлюзом.
 * Сообщение парсера: `MOVEITEMS_ALL_FAILED` (часто `status=1`).
 */
internal fun moveItemsNeedsSrcSync(errorMessage: String): Boolean =
    errorMessage.contains("MOVEITEMS_ALL_FAILED")

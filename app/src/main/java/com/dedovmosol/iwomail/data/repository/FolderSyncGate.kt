package com.dedovmosol.iwomail.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Один Sync-запрос на папку за раз.
 *
 * SyncKey ([MS-ASCMD] Sync) — цепочка: каждый ответ выдаёт следующий ключ. Два запроса с одним ключом (цикл
 * синхронизации и звезда/прочитано) разводят ключ клиента и сервера: следующий запрос получает Status 3, и папка
 * синхронизируется заново. Через шлюз идут цикл синхронизации папки ([EmailSyncService.syncEmailsEas]) и локальные
 * изменения ([EmailOperationsService]: флаг, прочитано); внутри блока ключ читается из БД и сохраняется.
 *
 * Общий на процесс: SyncWorker и PushService создают разные экземпляры сервисов. Мьютекс не реентерабельный —
 * внутри блока шлюз той же папки не брать. Записей — по одной на папку (их немного), поэтому не очищаются.
 */
internal object FolderSyncGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFolder(folderId: String, block: suspend () -> T): T =
        locks.getOrPut(folderId) { Mutex() }.withLock { block() }
}

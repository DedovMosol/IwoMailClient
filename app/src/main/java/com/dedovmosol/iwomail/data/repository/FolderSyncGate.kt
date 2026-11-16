package com.dedovmosol.iwomail.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Один EAS-запрос к коллекции папки за раз (Sync и ItemOperations Fetch).
 *
 * SyncKey ([MS-ASCMD] Sync) — цепочка: два Sync с одним ключом дают Status 3.
 * На Exchange 2007 SP1 параллельный ItemOperations Fetch во время Sync той же
 * коллекции часто отвечает пустым телом или Status 8 — открытие письма ждёт шлюз.
 *
 * Через шлюз: цикл синхронизации, флаг/прочитано, загрузка тела.
 * Мьютекс не реентерабельный — внутри блока шлюз той же папки не брать.
 */
internal object FolderSyncGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFolder(folderId: String, block: suspend () -> T): T =
        locks.getOrPut(folderId) { Mutex() }.withLock { block() }

    /**
     * Предзагрузка: не ждать пользователя/Sync. null — папка занята, этот Fetch пропускаем.
     */
    suspend fun <T> tryWithFolder(folderId: String, block: suspend () -> T): T? {
        val mutex = locks.getOrPut(folderId) { Mutex() }
        if (!mutex.tryLock()) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

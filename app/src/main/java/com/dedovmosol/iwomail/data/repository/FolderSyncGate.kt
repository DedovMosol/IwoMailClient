package com.dedovmosol.iwomail.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private class FolderAccess {
        val mutex = Mutex()
        @Volatile var resetVersion = 0L
    }

    private val folders = ConcurrentHashMap<String, FolderAccess>()
    private val resetVersion = AtomicLong()

    private fun access(folderId: String) = folders.getOrPut(folderId) { FolderAccess() }

    /** Take a checkpoint before reading selected rows, not after preparing the Delete. */
    fun resetCheckpoint(): Long = resetVersion.get()

    fun wasResetSince(folderId: String, checkpoint: Long): Boolean =
        (folders[folderId]?.resetVersion ?: 0L) > checkpoint

    /** A Sync(0) can reuse ServerIds. Record the start and the end of its reconciliation. */
    fun noteReset(folderId: String) {
        access(folderId).resetVersion = resetVersion.incrementAndGet()
    }

    suspend fun <T> withFolder(folderId: String, block: suspend () -> T): T =
        access(folderId).mutex.withLock { block() }

    /**
     * Предзагрузка: не ждать пользователя/Sync. null — папка занята, этот Fetch пропускаем.
     */
    suspend fun <T> tryWithFolder(folderId: String, block: suspend () -> T): T? {
        val mutex = access(folderId).mutex
        if (!mutex.tryLock()) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

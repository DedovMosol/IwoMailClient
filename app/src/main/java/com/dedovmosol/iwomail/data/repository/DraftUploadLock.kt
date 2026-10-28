package com.dedovmosol.iwomail.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Замок на загрузку и замену черновиков аккаунта: синхронизация (SyncWorker, PushService — разные
 * экземпляры сервисов) и сохранение с экрана письма не загружают один и тот же локальный черновик
 * дважды (иначе на сервере два одинаковых черновика). Свой у каждого аккаунта: сохранение не ждёт
 * синхронизации другого. Не реентерабелен: внутри блока замок не брать.
 */
internal object DraftUploadLock {
    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(accountId: Long, block: suspend () -> T): T =
        mutexes.getOrPut(accountId) { Mutex() }.withLock { block() }
}

/**
 * Черновик сменил запись, пока открыт в редакторе: загружен с устройства на сервер, перенесён синхронизацией под
 * EAS ServerId или заменён серверной копией. Сохранение по прежнему id находит актуальную запись, а не создаёт
 * второй черновик. Только в памяти процесса: редактор — в том же процессе.
 */
internal object DraftSuccessors {
    private const val MAX_ENTRIES = 512
    private const val MAX_HOPS = 16
    private val next = ConcurrentHashMap<String, String>()

    fun record(oldEmailId: String, newEmailId: String) {
        if (oldEmailId == newEmailId) return
        if (next.size >= MAX_ENTRIES) next.clear()
        next[oldEmailId] = newEmailId
    }

    /** Актуальная запись для [emailId] (по цепочке переносов); null — не переносилась. */
    fun latest(emailId: String): String? {
        var current = next[emailId] ?: return null
        repeat(MAX_HOPS) { current = next[current] ?: return current }
        return current
    }
}

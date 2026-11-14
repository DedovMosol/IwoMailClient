package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.PendingPimChangeDao
import com.dedovmosol.iwomail.data.database.PendingPimChangeEntity
import com.dedovmosol.iwomail.data.database.PimKind
import com.dedovmosol.iwomail.data.model.isConnectionLevelError

/**
 * Когда правку задачи или заметки отложить, а не показывать ошибку.
 *
 * - Сети нет — серверу не пишем вовсе: запрос ждал бы таймаута и тратил батарею.
 * - Запрос не дошёл до сервера: метка транспорта `[NET:…]` уровня соединения, HTTP 5xx ([isConnectionLevelError]) или
 *   сеть пропала во время запроса. Путь EWS (Exchange 2007) при обрыве часто возвращает ошибку без метки, поэтому
 *   состояние сети проверяется и после сбоя.
 *
 * Всё остальное (сервер отказал в изменении, объекта нет) — ошибка, как раньше: повтор её не исправит.
 */
internal class OfflineWrites(
    private val isOnline: () -> Boolean,
    private val scheduleSend: () -> Unit
) {
    fun offline(): Boolean = !isOnline()

    fun canDefer(errorMessage: String): Boolean = isConnectionLevelError(errorMessage) || !isOnline()

    /** Запланировать отправку очереди, когда появится связь. */
    fun schedule() = scheduleSend()

    companion object {
        fun of(context: Context): OfflineWrites {
            val app = context.applicationContext
            return OfflineWrites(
                isOnline = { com.dedovmosol.iwomail.network.NetworkMonitor.isNetworkAvailable(app) },
                scheduleSend = { com.dedovmosol.iwomail.sync.PendingMailChangesWorker.enqueue(app) }
            )
        }

        /** Для тестов репозиториев, где очередь не проверяется: сеть есть, отправка не планируется. */
        val ALWAYS_ONLINE = OfflineWrites(isOnline = { true }, scheduleSend = {})
    }
}

/** Итог отправки одной записи очереди. */
internal enum class PimSendOutcome {
    /** Сервер принял (или отказал — тогда синхронизация вернёт его версию); запись снята. */
    DONE,
    /** Связи нет — запись остаётся до следующей попытки. */
    KEPT
}

/**
 * Отправляет правки задач и заметок, сделанные без связи, включая появившиеся во время отправки. Запись с той же
 * версией — не больше одного раза за проход: новый сбой связи не зацикливает, а правка, сделанная во время отправки
 * (версия выросла), уходит в том же проходе. true — очередь пуста.
 */
internal class PendingPimChanges(
    private val queue: PendingPimChangeDao,
    private val send: suspend (PendingPimChangeEntity) -> PimSendOutcome,
    /**
     * Один раз на аккаунт и вид после прохода, если что-то ушло: синхронизация подтягивает ServerId, выданные сервером
     * (перенос в корзину, UpdateItem на Exchange 2007). По синхронизации на каждую запись — лишние запросы и батарея.
     */
    private val afterSent: suspend (accountId: Long, kind: PimKind) -> Unit = { _, _ -> }
) {
    suspend fun flush(accountId: Long? = null, kind: PimKind? = null): Boolean {
        fun matches(change: PendingPimChangeEntity) =
            (accountId == null || change.accountId == accountId) && (kind == null || change.kind == kind)

        val attempted = HashSet<Pair<String, Long>>()
        val sent = LinkedHashSet<Pair<Long, PimKind>>()
        while (true) {
            val batch = queue.getAll().filter { matches(it) && (it.itemId to it.version) !in attempted }
            if (batch.isEmpty()) break
            for (change in batch) {
                attempted += change.itemId to change.version
                if (send(change) == PimSendOutcome.DONE) sent += change.accountId to change.kind
            }
        }
        for ((account, sentKind) in sent) afterSent(account, sentKind)
        return queue.getAll().none(::matches)
    }
}

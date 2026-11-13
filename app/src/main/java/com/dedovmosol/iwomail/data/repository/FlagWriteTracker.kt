package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.eas.MailFlag
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Флаги писем, которые сейчас записываются на сервер (в БД они уже стоят — оптимистично).
 *
 * - Частые нажатия: каждое — [start] с новым номером. На сервер уходит только последнее ([isLatest]), и итог в БД
 *   пишет только оно ([finish]): ответы на прежние нажатия звезду не перещёлкивают.
 * - Отказ сервера откатывает к последнему флагу, который сервер подтвердил, а не к снимку до нажатия: снимок мог
 *   быть оптимистичным значением предыдущего нажатия.
 * - Синхронизация не перетирает такой флаг ответом, запрошенным до записи ([applyServerFlag]).
 *
 * Все чтения и записи флага в БД, которые зависят от этого состояния, идут под одним замком: иначе между решением
 * и записью успело бы вклиниться новое нажатие. Порядок замков — шлюз папки ([FolderSyncGate]), затем этот;
 * обратного нет, взаимной блокировки нет.
 */
class FlagWriteTracker(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    /** Итог записи на сервер. */
    sealed interface Outcome {
        /** Сервер принял [flag] (может отличаться от запрошенного сроком — Exchange без даты не принял). */
        data class Applied(val flag: MailFlag) : Outcome
        /** Сервер отказал явно (Status): откат к подтверждённому флагу. */
        data object Rejected : Outcome
        /** Не отправлено или ответа нет (нет связи, нажатие устарело): флаг в БД остаётся как есть. */
        data object KeptLocal : Outcome
    }

    /** Начатая запись: [ticket] — номер нажатия, [previous] — флаг до него, [target] — записанный в БД. */
    data class Started(val ticket: Long, val previous: MailFlag, val target: MailFlag)

    private class Entry(var confirmed: MailFlag) {
        var latest = 0L
        var inFlight = 0
        var touchedAt = 0L
    }

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()
    private var lastTicket = 0L

    fun now(): Long = clock()

    /**
     * Под замком: текущий флаг письма [read] (null — письма нет, запись не начинается), новый — [target] от него,
     * оптимистичная запись [write].
     */
    suspend fun start(
        emailId: String,
        read: suspend () -> MailFlag?,
        target: (MailFlag) -> MailFlag,
        write: suspend (MailFlag) -> Unit,
        unconfirmed: Boolean = false
    ): Started? = mutex.withLock {
        evictIdle()
        val previous = read() ?: return@withLock null
        val wanted = target(previous)
        // Повторная отправка того же флага (очередь без связи) БД не трогает: лишняя запись перезапросила бы списки
        if (wanted != previous) write(wanted)
        // Флаг в БД ждёт отправки ([unconfirmed]) и записи об этом письме нет (процесс перезапущен, запись забыта):
        // значение в БД — не то, что подтвердил сервер, откат к нему оставил бы отклонённый флаг. Сервер, скорее всего,
        // держит противоположное ([serverGuess]).
        val entry = entries.getOrPut(emailId) { Entry(if (unconfirmed) serverGuess(previous) else previous) }
        entry.latest = ++lastTicket
        entry.inFlight++
        entry.touchedAt = clock()
        Started(entry.latest, previous, wanted)
    }

    /** Нажатие [ticket] — последнее: только оно отправляет флаг на сервер. */
    suspend fun isLatest(emailId: String, ticket: Long): Boolean = mutex.withLock { entries[emailId]?.latest == ticket }

    /**
     * Запись [started] завершилась с [outcome]. Итог — [settle] под замком и только если новых нажатий не было: флаг для
     * БД (null — в БД уже нужный) и сам [outcome] (по нему вызывающий ставит или снимает отметку «ждёт отправки»).
     */
    suspend fun finish(
        emailId: String,
        started: Started,
        outcome: Outcome,
        settle: suspend (flag: MailFlag?, outcome: Outcome) -> Unit
    ) {
        mutex.withLock {
            val entry = entries[emailId]
            if (entry != null) {
                entry.inFlight = (entry.inFlight - 1).coerceAtLeast(0)
                entry.touchedAt = clock()
                if (outcome is Outcome.Applied) entry.confirmed = outcome.flag
                if (entry.latest == started.ticket) {
                    val result = settledFlag(outcome, entry.confirmed)
                    val changed = if (result != null && result != started.target) result else null
                    settle(changed, outcome)
                }
            }
        }
    }

    /**
     * Что, вероятнее всего, держит сервер, пока на устройстве ждёт отправки [local]: звезда ставится и снимается, поэтому
     * противоположное (без срока — срок и напоминание сервера неизвестны).
     */
    private fun serverGuess(local: MailFlag): MailFlag = if (local.active) MailFlag.NONE else MailFlag(MailFlag.STATUS_ACTIVE)

    /** Флаг в БД по итогу: принятый сервером, подтверждённый (откат) или прежний (null). */
    private fun settledFlag(outcome: Outcome, confirmed: MailFlag): MailFlag? = when (outcome) {
        is Outcome.Applied -> outcome.flag
        Outcome.Rejected -> confirmed
        Outcome.KeptLocal -> null
    }

    /**
     * Флаг письма из ответа синхронизации, запрошенной в [requestedAt]: записывается ([write]), если письмо сейчас не
     * пишется и не было записано после запроса (ответ тогда устарел — на сервере уже флаг пользователя). Пока запись
     * идёт, флаг сервера запоминается как подтверждённый — к нему откат, если сервер запись отклонит.
     */
    suspend fun applyServerFlag(emailId: String, serverFlag: MailFlag, requestedAt: Long, write: suspend (MailFlag) -> Unit) {
        mutex.withLock {
            val entry = entries[emailId]
            when {
                entry == null -> write(serverFlag)
                entry.inFlight > 0 -> entry.confirmed = serverFlag
                entry.touchedAt >= requestedAt -> Unit
                else -> {
                    entry.confirmed = serverFlag
                    write(serverFlag)
                }
            }
        }
    }

    /** Завершённые записи старше [IDLE_TTL_MS] больше не нужны ни для отката, ни для защиты от устаревших ответов. */
    private fun evictIdle() {
        val now = clock()
        entries.values.removeAll { it.inFlight == 0 && now - it.touchedAt > IDLE_TTL_MS }
    }

    companion object {
        /** Дольше любого запроса Sync (таймауты сети — минуты): ответ, запрошенный раньше, уже обработан. */
        private const val IDLE_TTL_MS = 10 * 60_000L

        /** Общий на процесс: синхронизация и операции с письмами — разные экземпляры сервисов. */
        val shared = FlagWriteTracker()
    }
}

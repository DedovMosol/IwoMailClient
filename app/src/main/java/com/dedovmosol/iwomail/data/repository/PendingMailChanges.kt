package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.PendingEmailChangeDao
import com.dedovmosol.iwomail.eas.EasResult

/**
 * Отправка правок писем, сделанных без связи ([com.dedovmosol.iwomail.data.database.PendingEmailChangeEntity]):
 * флаг и «прочитано» — тем же путём, что и сразу после нажатия ([EmailOperationsService.retryPendingFlag],
 * [EmailOperationsService.retryPendingRead]). Отправка сама снимает запись очереди при успехе или отказе сервера и
 * оставляет её при новом сбое связи.
 */
class PendingMailChanges(
    private val queue: PendingEmailChangeDao,
    private val sendFlag: suspend (emailId: String) -> EasResult<Boolean>,
    private val sendRead: suspend (emailId: String) -> EasResult<Boolean>,
    private val sendMove: suspend (emailId: String) -> EasResult<Boolean> = { EasResult.Success(true) }
) {
    /**
     * Отправляет всё, что ждёт, включая правки, появившиеся во время отправки; каждое письмо — не больше одного раза
     * за проход (новый сбой связи не зацикливает). true — очередь пуста.
     */
    suspend fun flush(): Boolean {
        val attempted = HashSet<String>()
        while (true) {
            val batch = queue.getAll().filter { it.emailId !in attempted }
            if (batch.isEmpty()) break
            for (change in batch) {
                attempted += change.emailId
                if (change.flag) sendFlag(change.emailId)
                if (change.read) sendRead(change.emailId)
                if (change.move) sendMove(change.emailId)
            }
        }
        return queue.count() == 0
    }
}

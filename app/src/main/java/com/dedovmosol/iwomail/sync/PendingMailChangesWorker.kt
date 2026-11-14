package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.PimKind
import com.dedovmosol.iwomail.data.repository.PendingPimChanges
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import java.util.concurrent.TimeUnit

/**
 * Отправляет правки, сделанные без связи, когда связь появится: писем (флаг, «прочитано») и задач и заметок (изменение,
 * корзина). WorkManager с условием сети переживает перезапуск процесса и устройства. Имя класса и работы прежние: уже
 * запланированная работа после обновления приложения продолжит отправку.
 *
 * Уникальная работа с [ExistingWorkPolicy.KEEP]: правка во время отправки не перезапускает работу (иначе новый сбой
 * связи внутри самой работы зациклил бы её без паузы) — отправка подхватывает такие правки сама
 * ([com.dedovmosol.iwomail.data.repository.PendingMailChanges.flush]), а оставшиеся — повтор с нарастающей паузой.
 * Запись, появившаяся в последний миг работы, отправится при следующей синхронизации ([enqueueIfPending] из
 * [SyncWorker]) или запуске приложения.
 */
class PendingMailChangesWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val mailDone = RepositoryProvider.getMailRepository(applicationContext).flushPendingChanges()
        val pimDone = flushPendingPim(applicationContext)
        val done = mailDone && pimDone
        when {
            done -> Result.success()
            runAttemptCount + 1 >= MAX_ATTEMPTS -> {
                // Очередь остаётся: её отправит следующая синхронизация или запуск приложения
                android.util.Log.w(TAG, "Pending mail changes not sent after $MAX_ATTEMPTS attempts")
                Result.success()
            }
            else -> Result.retry()
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.w(TAG, "Flush failed", e)
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "PendingMailChanges"
        internal const val WORK_NAME = "pending_mail_changes"
        private const val MAX_ATTEMPTS = 20
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingMailChangesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Запланировать отправку, если в очереди что-то есть (запуск приложения, периодическая синхронизация). */
        suspend fun enqueueIfPending(context: Context) {
            val db = MailDatabase.getInstance(context)
            if (db.pendingEmailChangeDao().count() > 0 || db.pendingPimChangeDao().count() > 0) enqueue(context)
        }

        /** Правки задач и заметок без связи — каждая своим репозиторием (EAS или EWS по версии Exchange). */
        private suspend fun flushPendingPim(context: Context): Boolean {
            val queue = MailDatabase.getInstance(context).pendingPimChangeDao()
            if (queue.count() == 0) return true
            val tasks = RepositoryProvider.getTaskRepository(context)
            val notes = RepositoryProvider.getNoteRepository(context)
            return PendingPimChanges(
                queue,
                send = { change ->
                    when (change.kind) {
                        PimKind.TASK -> tasks.sendPendingTask(change, resync = false)
                        PimKind.NOTE -> notes.sendPendingNote(change, resync = false)
                    }
                },
                afterSent = { accountId, kind ->
                    when (kind) {
                        PimKind.TASK -> tasks.syncAfterPendingSent(accountId)
                        PimKind.NOTE -> notes.syncAfterPendingSent(accountId)
                    }
                }
            ).flush()
        }
    }
}

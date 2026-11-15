package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.work.*
import com.dedovmosol.iwomail.data.database.MailDatabase

class RescheduleRemindersWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val database = MailDatabase.getInstance(applicationContext)
            val now = System.currentTimeMillis()

            val events = database.calendarEventDao().getAllFutureEventsWithReminders(
                now, CalendarReminderReceiver.MAX_SCHEDULED_REMINDERS
            )
            CalendarReminderReceiver.rescheduleAllReminders(applicationContext, events)

            val tasks = database.taskDao().getTasksWithReminders(
                now, TaskReminderReceiver.MAX_SCHEDULED_REMINDERS
            )
            TaskReminderReceiver.rescheduleAllReminders(applicationContext, tasks)
            FlagReminderReceiver.rescheduleAll(applicationContext)

            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w(TAG, "Failed to reschedule reminders", e)
            // KEEP + вечный retry блокирует новый enqueue (синк, грант exact). После потолка
            // success — уникальная работа завершена, следующий enqueue проходит.
            if (runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "RescheduleReminders"
        private const val WORK_NAME = "reschedule_reminders"
        private const val PERIODIC_NAME = "reschedule_reminders_periodic"
        private const val MAX_RETRIES = 5

        fun enqueue(context: Context, replace: Boolean = false) {
            val app = context.applicationContext
            val request = OneTimeWorkRequestBuilder<RescheduleRemindersWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork(
                    WORK_NAME,
                    if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    request
                )
        }

        /** OEM мог съесть alarm без boot: раз в 6 ч подтянуть ближайшие 50. KEEP — не плодить. */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<RescheduleRemindersWorker>(6, java.util.concurrent.TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

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
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RescheduleReminders"
        private const val WORK_NAME = "reschedule_reminders"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RescheduleRemindersWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

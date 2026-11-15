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

            val events = database.calendarEventDao().getAllFutureEventsWithReminders(now)
            CalendarReminderReceiver.rescheduleAllReminders(applicationContext, events)

            val tasks = database.taskDao().getTasksWithReminders(now)
            TaskReminderReceiver.rescheduleAllReminders(applicationContext, tasks)
            FlagReminderReceiver.rescheduleAll(applicationContext)

            Result.success()
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "AlarmManager limit reached (MIUI/HyperOS/Samsung)", e)
            if (runAttemptCount >= MAX_ALARM_RETRIES) {
                android.util.Log.e(TAG, "Giving up after $MAX_ALARM_RETRIES tries, next enqueue can run (KEEP + finished)")
                Result.success()
            } else Result.retry()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w(TAG, "Failed to reschedule reminders", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RescheduleReminders"
        private const val WORK_NAME = "reschedule_reminders"
        private const val MAX_ALARM_RETRIES = 5

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RescheduleRemindersWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

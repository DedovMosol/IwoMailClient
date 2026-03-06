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

            Result.success()
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "AlarmManager limit reached (MIUI/HyperOS/Samsung)", e)
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
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

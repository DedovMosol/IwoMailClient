package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.work.*
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider

/**
 * Worker для пометки задачи как выполненной на сервере Exchange.
 * Запускается из TaskReminderReceiver при нажатии кнопки «Прочитано».
 * WorkManager гарантирует выполнение даже при агрессивных OEM-ограничениях
 * (MIUI/HyperOS/EMUI), в отличие от goAsync() с 10-секундным лимитом.
 */
class MarkTaskCompleteWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()

        return try {
            val database = MailDatabase.getInstance(applicationContext)
            val task = database.taskDao().getTask(taskId)

            if (task != null && !task.complete) {
                val taskRepo = RepositoryProvider.getTaskRepository(applicationContext)
                taskRepo.updateTask(
                    task = task,
                    subject = task.subject,
                    body = task.body,
                    startDate = task.startDate,
                    dueDate = task.dueDate,
                    complete = true,
                    importance = task.importance,
                    reminderSet = task.reminderSet,
                    reminderTime = task.reminderTime
                )
            }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to mark task complete", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "MarkTaskComplete"
        const val KEY_TASK_ID = "task_id"
        private const val MAX_RETRIES = 3

        fun enqueue(context: Context, taskId: String) {
            val data = workDataOf(KEY_TASK_ID to taskId)

            val request = OneTimeWorkRequestBuilder<MarkTaskCompleteWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "mark_task_complete_$taskId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}

package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.work.*
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import java.security.MessageDigest

/**
 * Worker для пометки писем как прочитанных на сервере Exchange.
 * Запускается из MailNotificationActionReceiver при нажатии кнопки «Прочитано».
 * WorkManager гарантирует выполнение даже при агрессивных OEM-ограничениях
 * (MIUI/HyperOS/EMUI), в отличие от goAsync() с 10-секундным лимитом.
 */
class MarkEmailReadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val emailIds = inputData.getStringArray(KEY_EMAIL_IDS)

        if (emailIds.isNullOrEmpty()) return Result.failure()

        return try {
            val mailRepo = RepositoryProvider.getMailRepository(applicationContext)
            if (emailIds.size == 1) {
                mailRepo.markAsRead(emailIds[0], true)
            } else {
                mailRepo.markAsReadBatch(emailIds.toList(), true)
            }
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w(TAG, "Failed to mark emails as read", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "MarkEmailRead"
        const val KEY_EMAIL_IDS = "email_ids"
        private const val WORK_NAME = "mark_email_read"
        private const val MAX_RETRIES = 3

        fun enqueue(context: Context, emailIds: Array<String>) {
            val data = workDataOf(KEY_EMAIL_IDS to emailIds)

            val request = OneTimeWorkRequestBuilder<MarkEmailReadWorker>()
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

            val uniqueName = buildWorkName(emailIds)
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        private fun buildWorkName(emailIds: Array<String>): String {
            val normalized = emailIds.sortedArray()
            val payload = normalized.joinToString("|")
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return "${WORK_NAME}_$hash"
        }
    }
}

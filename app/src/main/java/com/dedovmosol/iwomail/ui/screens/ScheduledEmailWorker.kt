package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import androidx.work.*
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.NotificationStrings
import java.util.concurrent.TimeUnit

/**
 * Worker для отложенной отправки письма
 */
class ScheduledEmailWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val accountId = inputData.getLong("accountId", -1)
        val to = inputData.getString("to") ?: return Result.failure()
        val cc = inputData.getString("cc") ?: ""
        val bcc = inputData.getString("bcc") ?: ""
        val subject = inputData.getString("subject") ?: ""
        val body = inputData.getString("body") ?: ""
        val requestReadReceipt = inputData.getBoolean("requestReadReceipt", false)
        val requestDeliveryReceipt = inputData.getBoolean("requestDeliveryReceipt", false)
        val importance = inputData.getInt("importance", 1)
        
        val accountRepo = AccountRepository(applicationContext)
        val account = accountRepo.getAccount(accountId) ?: return Result.failure()
        
        val client = accountRepo.createEasClient(accountId) ?: return Result.failure()
        
        return when (client.sendMail(to, subject, body, cc, bcc, importance = importance, requestReadReceipt = requestReadReceipt, requestDeliveryReceipt = requestDeliveryReceipt)) {
            is EasResult.Success -> {
                // Показываем уведомление об успешной отправке
                val isRu = java.util.Locale.getDefault().language == "ru"
                val title = NotificationStrings.getEmailSent(isRu)
                val text = NotificationStrings.getScheduledEmailSent(to, isRu)
                showNotification(title, text)
                Result.success()
            }
            is EasResult.Error -> Result.retry()
        }
    }
    
    private fun showNotification(title: String, text: String) {
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext, 
            com.dedovmosol.iwomail.MailApplication.CHANNEL_SCHEDULED
        )
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        applicationContext.getSystemService(android.app.NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), notification)
    }
}

/**
 * Планирует отправку письма через WorkManager
 */
fun scheduleEmail(
    context: Context,
    accountId: Long,
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    delayMillis: Long,
    requestReadReceipt: Boolean = false,
    requestDeliveryReceipt: Boolean = false,
    importance: Int = 1
) {
    val data = workDataOf(
        "accountId" to accountId,
        "to" to to,
        "cc" to cc,
        "bcc" to bcc,
        "subject" to subject,
        "body" to body,
        "requestReadReceipt" to requestReadReceipt,
        "requestDeliveryReceipt" to requestDeliveryReceipt,
        "importance" to importance
    )
    
    val request = OneTimeWorkRequestBuilder<ScheduledEmailWorker>()
        .setInputData(data)
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()
    
    WorkManager.getInstance(context).enqueue(request)
}

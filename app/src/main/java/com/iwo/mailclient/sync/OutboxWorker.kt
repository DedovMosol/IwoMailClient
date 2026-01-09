package com.iwo.mailclient.sync

import android.content.Context
import androidx.work.*
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.eas.EasResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Worker для отправки писем из очереди при появлении сети
 */
class OutboxWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val OUTBOX_FILE = "outbox.json"
        private const val WORK_NAME = "outbox_send"
        
        /**
         * Добавляет письмо в очередь отправки
         */
        fun enqueue(
            context: Context,
            accountId: Long,
            to: String,
            cc: String,
            bcc: String,
            subject: String,
            body: String,
            requestReadReceipt: Boolean = false
        ) {
            val outbox = loadOutbox(context)
            val email = JSONObject().apply {
                put("accountId", accountId)
                put("to", to)
                put("cc", cc)
                put("bcc", bcc)
                put("subject", subject)
                put("body", body)
                put("requestReadReceipt", requestReadReceipt)
                put("timestamp", System.currentTimeMillis())
            }
            outbox.put(email)
            saveOutbox(context, outbox)
            
            // Запускаем Worker с требованием сети
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
        
        /**
         * Возвращает количество писем в очереди
         */
        fun getQueueSize(context: Context): Int {
            return loadOutbox(context).length()
        }
        
        /**
         * Очищает очередь
         */
        fun clearQueue(context: Context) {
            saveOutbox(context, JSONArray())
        }
        
        private fun loadOutbox(context: Context): JSONArray {
            val file = File(context.filesDir, OUTBOX_FILE)
            return if (file.exists()) {
                try {
                    JSONArray(file.readText())
                } catch (e: Exception) {
                    JSONArray()
                }
            } else {
                JSONArray()
            }
        }
        
        private fun saveOutbox(context: Context, outbox: JSONArray) {
            val file = File(context.filesDir, OUTBOX_FILE)
            file.writeText(outbox.toString())
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val outbox = loadOutbox(applicationContext)
        if (outbox.length() == 0) {
            return@withContext Result.success()
        }
        
        val accountRepo = AccountRepository(applicationContext)
        val failedEmails = JSONArray()
        var successCount = 0
        
        for (i in 0 until outbox.length()) {
            val email = outbox.getJSONObject(i)
            val accountId = email.getLong("accountId")
            
            try {
                val easClient = accountRepo.createEasClient(accountId)
                if (easClient == null) {
                    failedEmails.put(email)
                    continue
                }
                
                val result = easClient.sendMail(
                    to = email.getString("to"),
                    cc = email.optString("cc", ""),
                    bcc = email.optString("bcc", ""),
                    subject = email.getString("subject"),
                    body = email.getString("body"),
                    requestReadReceipt = email.optBoolean("requestReadReceipt", false)
                )
                
                when (result) {
                    is EasResult.Success -> successCount++
                    is EasResult.Error -> failedEmails.put(email)
                }
            } catch (e: Exception) {
                failedEmails.put(email)
            }
        }
        
        // Сохраняем только неотправленные
        saveOutbox(applicationContext, failedEmails)
        
        // Показываем уведомление если что-то отправилось
        if (successCount > 0) {
            showNotification(successCount)
        }
        
        // Если остались неотправленные — повторим позже
        if (failedEmails.length() > 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }
    
    private fun showNotification(count: Int) {
        // Простое уведомление через NotificationManager
        try {
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) 
                as android.app.NotificationManager
            
            val channelId = "outbox_channel"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val isRu = java.util.Locale.getDefault().language == "ru"
                val channel = android.app.NotificationChannel(
                    channelId,
                    if (isRu) "Исходящие" else "Outbox",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            val isRu = java.util.Locale.getDefault().language == "ru"
            val title = if (isRu) {
                if (count == 1) "Письмо отправлено" else "Письма отправлены"
            } else {
                if (count == 1) "Email sent" else "Emails sent"
            }
            val text = if (isRu) "Отправлено из очереди: $count" else "Sent from queue: $count"
            
            val notification = android.app.Notification.Builder(applicationContext, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(9999, notification)
        } catch (e: Exception) {
            // Игнорируем ошибки уведомлений
        }
    }
}

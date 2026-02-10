package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.work.*
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        private const val OUTBOX_TEMP_FILE = "outbox.json.tmp"
        private const val WORK_NAME = "outbox_send"
        
        // Общий lock для всех операций с файлом очереди
        // Защищает от гонки между enqueue() (UI) и doWork() (Worker thread)
        private val outboxLock = Any()
        
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
            synchronized(outboxLock) {
                val outbox = loadOutboxInternal(context)
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
                saveOutboxInternal(context, outbox)
            }
            
            // Запускаем Worker с требованием сети
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, java.util.concurrent.TimeUnit.MINUTES // 1 мин → 2 мин → 4 мин ...
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
        
        /**
         * Возвращает количество писем в очереди
         */
        fun getQueueSize(context: Context): Int {
            synchronized(outboxLock) {
                return loadOutboxInternal(context).length()
            }
        }
        
        /**
         * Очищает очередь
         */
        fun clearQueue(context: Context) {
            synchronized(outboxLock) {
                saveOutboxInternal(context, JSONArray())
            }
        }
        
        /**
         * Загрузка + сохранение под общим lock (вызывать из synchronized(outboxLock))
         */
        internal fun loadOutboxLocked(context: Context): JSONArray {
            return loadOutboxInternal(context)
        }
        
        internal fun saveOutboxLocked(context: Context, outbox: JSONArray) {
            saveOutboxInternal(context, outbox)
        }
        
        private fun loadOutboxInternal(context: Context): JSONArray {
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
        
        /**
         * Атомарная запись: write → temp, rename temp → target
         * Если крашнемся во время writeText — основной файл не повреждён
         */
        private fun saveOutboxInternal(context: Context, outbox: JSONArray) {
            val file = File(context.filesDir, OUTBOX_FILE)
            val tempFile = File(context.filesDir, OUTBOX_TEMP_FILE)
            try {
                tempFile.writeText(outbox.toString())
                // renameTo — атомарная операция на файловой системе
                if (!tempFile.renameTo(file)) {
                    // Fallback: если renameTo не сработал (например, разные файловые системы)
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                // Если temp-файл не записался, основной файл не повреждён
                tempFile.delete()
            }
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Читаем очередь под lock
        val outbox = synchronized(outboxLock) {
            loadOutboxLocked(applicationContext)
        }
        if (outbox.length() == 0) {
            return@withContext Result.success()
        }
        
        val accountRepo = RepositoryProvider.getAccountRepository(applicationContext)
        val failedEmails = JSONArray()
        var successCount = 0
        val successAccountIds = mutableSetOf<Long>() // Аккаунты с успешной отправкой
        
        // Кэшируем EasClient по accountId чтобы не пересоздавать на каждое письмо
        val easClients = mutableMapOf<Long, com.dedovmosol.iwomail.eas.EasClient?>()
        
        for (i in 0 until outbox.length()) {
            val email = outbox.getJSONObject(i)
            val accountId = email.getLong("accountId")
            
            try {
                val easClient = easClients.getOrPut(accountId) {
                    accountRepo.createEasClient(accountId)
                }
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
                    is EasResult.Success -> {
                        successCount++
                        successAccountIds.add(accountId)
                    }
                    is EasResult.Error -> failedEmails.put(email)
                }
            } catch (e: Exception) {
                failedEmails.put(email)
            }
        }
        
        // Сохраняем только неотправленные (под lock)
        synchronized(outboxLock) {
            saveOutboxLocked(applicationContext, failedEmails)
        }
        
        // Показываем уведомление если что-то отправилось
        if (successCount > 0) {
            showNotification(successCount)
            
            // Синхронизируем папку "Отправленные" для каждого аккаунта,
            // из которого были успешно отправлены письма.
            // Без этого письма из очереди не появляются в Sent Items
            // до следующего цикла SyncWorker (15+ минут).
            val mailRepo = RepositoryProvider.getMailRepository(applicationContext)
            val database = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(applicationContext)
            for (accountId in successAccountIds) {
                try {
                    delay(3000) // Даём серверу время обработать
                    val sentFolder = database.folderDao().getFoldersByAccountList(accountId)
                        .find { it.type == 5 } // FolderType.SENT_ITEMS
                    sentFolder?.let { folder ->
                        mailRepo.syncEmails(accountId, folder.id)
                    }
                } catch (_: Exception) {
                    // Sync-ошибки не должны блокировать Worker
                }
            }
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
            
            val isRu = java.util.Locale.getDefault().language == "ru"
            val title = if (isRu) {
                if (count == 1) "Письмо отправлено" else "Письма отправлены"
            } else {
                if (count == 1) "Email sent" else "Emails sent"
            }
            val text = if (isRu) "Отправлено из очереди: $count" else "Sent from queue: $count"
            
            val notification = androidx.core.app.NotificationCompat.Builder(
                applicationContext, 
                com.dedovmosol.iwomail.MailApplication.CHANNEL_OUTBOX
            )
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(9996, notification) // Уникальный ID (не 9999, который занят SyncWorker)
        } catch (e: Exception) {
            // Игнорируем ошибки уведомлений
        }
    }
}

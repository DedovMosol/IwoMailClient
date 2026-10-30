package com.dedovmosol.iwomail.sync

import android.content.Context
import android.os.Build
import androidx.work.*
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.dedovmosol.iwomail.domain.isRetryableSendError
import com.dedovmosol.iwomail.domain.refreshSentFolder
import com.dedovmosol.iwomail.domain.saveUnsentAsDraft
import com.dedovmosol.iwomail.domain.send
import com.dedovmosol.iwomail.eas.EasResult
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
        private const val OUTBOX_TEMP_FILE = "outbox.json.tmp"
        private const val WORK_NAME = "outbox_send"
        
        // Общий lock для всех операций с файлом очереди
        private val outboxLock = Any()
        
        /** Попыток на письмо при временных ошибках сервера (5xx, 429); дальше — в Черновики с уведомлением. */
        internal const val MAX_ATTEMPTS = 10

        /**
         * Письма, поставленные в очередь прежними версиями (теперь отправка без связи идёт через [ScheduledSend]):
         * если очередь не пуста — отправить их, когда будет связь. Вызывать не с главного потока.
         */
        fun drainLegacyQueue(context: Context) {
            val pending = synchronized(outboxLock) { loadOutboxInternal(context).length() > 0 }
            if (!pending) return
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
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
        
        /** Запись очереди → письмо (старые записи без новых полей — с прежними значениями по умолчанию). */
        internal fun mailOf(entry: JSONObject) = OutgoingMail(
            to = entry.optString("to"),
            cc = entry.optString("cc"),
            bcc = entry.optString("bcc"),
            subject = entry.optString("subject"),
            body = entry.optString("body"),
            importance = entry.optInt("importance", 1),
            requestReadReceipt = entry.optBoolean("requestReadReceipt", false),
            requestDeliveryReceipt = entry.optBoolean("requestDeliveryReceipt", false),
            forwardFolderId = entry.optString("forwardFolderId").takeIf { it.isNotBlank() },
            forwardItemId = entry.optString("forwardItemId").takeIf { it.isNotBlank() }
        )

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
                    // Аккаунта нет — письмо не отправить никогда; иначе (клиент временно не собрать) — позже
                    if (accountRepo.getAccount(accountId) != null) failedEmails.put(email)
                    continue
                }
                val mail = mailOf(email)
                // N-11: стабильный id (null для старых записей без него → прежнее поведение).
                val result = easClient.send(mail, stableClientId = email.optString("clientId").takeIf { it.isNotBlank() })
                when (result) {
                    is EasResult.Success -> {
                        successCount++
                        successAccountIds.add(accountId)
                    }
                    is EasResult.Error -> {
                        val attempts = email.optInt("attempts", 0) + 1
                        email.put("attempts", attempts)
                        if (isRetryableSendError(result.message) && attempts < MAX_ATTEMPTS) {
                            failedEmails.put(email)
                        } else {
                            // Повтор не поможет: письмо — в Черновики, пользователь узнаёт, почему оно не ушло
                            val saved = saveUnsentAsDraft(applicationContext, accountId, mail) != null
                            SendNotifications.notSent(
                                applicationContext, com.dedovmosol.iwomail.MailApplication.CHANNEL_OUTBOX,
                                SendNotifications.idFor(email.optString("clientId")), mail.subject, result.message, saved
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failedEmails.put(email)
            }
        }
        
        synchronized(outboxLock) {
            val current = loadOutboxLocked(applicationContext)
            val emailKey = { json: org.json.JSONObject ->
                "${json.optLong("accountId")}_${json.optLong("timestamp")}"
            }
            val failedByKey = (0 until failedEmails.length())
                .map { failedEmails.getJSONObject(it) }
                .associateBy { emailKey(it) }
            val sentKeys = (0 until outbox.length())
                .map { emailKey(outbox.getJSONObject(it)) }
                .filter { it !in failedByKey }
                .toSet()
            val merged = JSONArray()
            for (i in 0 until current.length()) {
                val item = current.getJSONObject(i)
                val key = emailKey(item)
                // Неудачные — с обновлённым счётчиком попыток; отправленные и брошенные — из очереди вон
                if (key !in sentKeys) merged.put(failedByKey[key] ?: item)
            }
            saveOutboxLocked(applicationContext, merged)
        }
        
        // Показываем уведомление и воспроизводим звук если что-то отправилось
        if (successCount > 0) {
            com.dedovmosol.iwomail.util.SoundPlayer.playSendSound(applicationContext)
            showNotification(successCount)
            
            // Синхронизируем папку "Отправленные" для каждого аккаунта,
            // из которого были успешно отправлены письма.
            // Без этого письма из очереди не появляются в Sent Items
            // до следующего цикла SyncWorker (15+ минут).
            for (accountId in successAccountIds) refreshSentFolder(applicationContext, accountId)
        }
        // Если остались неотправленные — повторим позже
        if (failedEmails.length() > 0) {
            Result.retry()
        } else {
            Result.success()
        }
    }
    
    private fun showNotification(count: Int) {
        val ru = SendNotifications.isRussian(applicationContext)
        val title = when {
            ru && count == 1 -> "Письмо отправлено"
            ru -> "Письма отправлены"
            count == 1 -> "Email sent"
            else -> "Emails sent"
        }
        val text = if (ru) "Отправлено из очереди: $count" else "Sent from queue: $count"
        // 9996 — свой id (9999 занят SyncWorker)
        SendNotifications.show(applicationContext, com.dedovmosol.iwomail.MailApplication.CHANNEL_OUTBOX, 9996, title, text)
    }
}

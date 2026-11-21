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
import java.util.UUID

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
        
        // Общий lock для всех операций с файлом очереди
        private val outboxLock = Any()
        
        /** Попыток на письмо при временных ошибках сервера (5xx, 429); дальше — в Черновики с уведомлением. */
        internal const val MAX_ATTEMPTS = 10

        /**
         * Письма, поставленные в очередь прежними версиями (теперь отправка без связи идёт через [ScheduledSend]):
         * если очередь не пуста — отправить их, когда будет связь. Вызывать не с главного потока.
         */
        fun drainLegacyQueue(context: Context) {
            val rows = synchronized(outboxLock) {
                val queue = loadOutboxInternal(context)
                (0 until queue.length()).mapNotNull(queue::optJSONObject)
            }
            if (rows.none { !it.optBoolean("failed") || recoverable(it) }) return
            val network = if (rows.any { !it.optBoolean("failed") }) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
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
        
        private fun recoverable(entry: JSONObject): Boolean = entry.optLong("accountId", -1L) >= 0 && entry.opt("body") is String

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
        
        private fun saveOutboxInternal(context: Context, outbox: JSONArray) {
            ScheduledSend.atomicText(File(context.filesDir, OUTBOX_FILE), outbox.toString())
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
        
        val accountRepo by lazy { RepositoryProvider.getAccountRepository(applicationContext) }
        var retry = false
        val remove = mutableSetOf<Int>()
        fun terminal(email: JSONObject) = synchronized(outboxLock) {
            email.put("failed", true)
            // Do this before the suspending draft save, so a stopped worker cannot send it again.
            saveOutboxLocked(applicationContext, outbox)
        }
        suspend fun recover(index: Int, email: JSONObject) {
            if (!recoverable(email)) return
            val key = runCatching { UUID.fromString(email.optString("recoveryId")) }.getOrNull() ?: UUID.randomUUID().also {
                synchronized(outboxLock) {
                    email.put("recoveryId", it.toString())
                    saveOutboxLocked(applicationContext, outbox)
                }
            }
            try {
                ScheduledSend.recoverLegacyOutbox(applicationContext, key, ScheduledSend.Job(
                    accountId = email.getLong("accountId"), mail = mailOf(email), sendAt = email.optLong("timestamp"),
                    clientId = email.optString("clientId").ifBlank { key.toString() }
                ))
                remove.add(index)
            } catch (e: java.io.IOException) {
                android.util.Log.w("OutboxWorker", "Could not move a failed message to recovery", e)
                retry = true
            }
        }
        var successCount = 0
        val successAccountIds = mutableSetOf<Long>() // Аккаунты с успешной отправкой
        
        // Кэшируем EasClient по accountId чтобы не пересоздавать на каждое письмо
        val easClients = mutableMapOf<Long, com.dedovmosol.iwomail.eas.EasClient?>()
        
        for (i in 0 until outbox.length()) {
            val email = outbox.optJSONObject(i) ?: continue
            if (email.optBoolean("failed")) {
                recover(i, email)
                continue
            }
            val accountId = email.optLong("accountId", -1L)
            if (!recoverable(email)) {
                terminal(email)
                continue
            }
            val mail = mailOf(email)

            try {
                val easClient = easClients.getOrPut(accountId) {
                    accountRepo.createEasClient(accountId)
                }
                if (easClient == null) {
                    if (accountRepo.getAccount(accountId) != null) retry = true else {
                        terminal(email)
                        recover(i, email)
                        SendNotifications.notSent(
                            applicationContext, com.dedovmosol.iwomail.MailApplication.CHANNEL_OUTBOX,
                            SendNotifications.idFor(email.optString("clientId")), mail.subject, "Account not found", false
                        )
                    }
                    continue
                }
                // N-11: стабильный id (null для старых записей без него → прежнее поведение).
                val result = easClient.send(mail, stableClientId = email.optString("clientId").takeIf { it.isNotBlank() })
                when (result) {
                    is EasResult.Success -> {
                        remove.add(i)
                        successCount++
                        successAccountIds.add(accountId)
                    }
                    is EasResult.Error -> {
                        val attempts = email.optInt("attempts", 0) + 1
                        email.put("attempts", attempts)
                        if (isRetryableSendError(result.message) && attempts < MAX_ATTEMPTS) {
                            retry = true
                        } else {
                            terminal(email)
                            val saved = ScheduledSend.attemptDraftSave {
                                saveUnsentAsDraft(applicationContext, accountId, mail) != null
                            }
                            if (saved) remove.add(i) else recover(i, email)
                            SendNotifications.notSent(
                                applicationContext, com.dedovmosol.iwomail.MailApplication.CHANNEL_OUTBOX,
                                SendNotifications.idFor(email.optString("clientId")), mail.subject, result.message, saved
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!email.optBoolean("failed")) retry = true
            }
        }

        // This legacy queue has no producers in current versions. Indexes avoid conflating two older
        // messages written to the same account in the same millisecond.
        synchronized(outboxLock) {
            val remaining = JSONArray()
            for (i in 0 until outbox.length()) if (i !in remove) remaining.put(outbox.get(i))
            saveOutboxLocked(applicationContext, remaining)
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
        if (retry) {
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

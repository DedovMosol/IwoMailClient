package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import androidx.work.*
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.NotificationStrings
import java.io.File
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
        val requestReadReceipt = inputData.getBoolean("requestReadReceipt", false)
        val requestDeliveryReceipt = inputData.getBoolean("requestDeliveryReceipt", false)
        val importance = inputData.getInt("importance", 1)
        val attachmentDirPath = inputData.getString("attachmentDir") ?: ""
        // SYNC-6: стабильный clientId из inputData (WorkManager сохраняет inputData неизменным
        // при retry) → повтор после потерянного ACK не порождает новый Message-ID/ClientId.
        val clientId = inputData.getString("clientId")
        
        val accountRepo = AccountRepository(applicationContext)
        
        val client = accountRepo.createEasClient(accountId) ?: return Result.failure()
        
        val attachmentDir = if (attachmentDirPath.isNotBlank()) File(attachmentDirPath) else null
        // Тело — из файла (inputData WorkManager ограничен 10 КБ); "body" — задачи, поставленные до этого.
        val body = attachmentDir?.let { File(it, BODY_FILE) }?.takeIf { it.exists() }?.readText()
            ?: inputData.getString("body") ?: ""
        val sendResult = if (attachmentDir != null) {
            val attachments = readPersistedAttachments(attachmentDir)
            if (attachments.isNotEmpty()) {
                client.sendMailWithAttachments(
                    to, subject, body, cc, bcc, attachments,
                    requestReadReceipt, requestDeliveryReceipt, importance,
                    stableClientId = clientId
                )
            } else {
                client.sendMail(
                    to, subject, body, cc, bcc,
                    importance = importance,
                    requestReadReceipt = requestReadReceipt,
                    requestDeliveryReceipt = requestDeliveryReceipt,
                    stableClientId = clientId
                )
            }
        } else {
            client.sendMail(
                to, subject, body, cc, bcc,
                importance = importance,
                requestReadReceipt = requestReadReceipt,
                requestDeliveryReceipt = requestDeliveryReceipt,
                stableClientId = clientId
            )
        }
        
        return when (sendResult) {
            is EasResult.Success -> {
                attachmentDir?.deleteRecursively()
                deleteSentDraft()
                val isRu = java.util.Locale.getDefault().language == "ru"
                val title = NotificationStrings.getEmailSent(isRu)
                val text = NotificationStrings.getScheduledEmailSent(to, isRu)
                showNotification(title, text)
                Result.success()
            }
            is EasResult.Error -> Result.retry()
        }
    }

    /**
     * Отправлен черновик — он удаляется, как при обычной отправке (SendProgressBar). Только если после
     * планирования его не меняли: правка, сделанная после, — уже другое письмо, его не трогаем.
     */
    private suspend fun deleteSentDraft() {
        val draftId = inputData.getString("draftId")?.takeIf { it.isNotBlank() } ?: return
        try {
            val draft = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(applicationContext)
                .emailDao().getEmail(draftId) ?: return
            if (draft.dateReceived != inputData.getLong("draftStamp", -1L)) return
            com.dedovmosol.iwomail.data.repository.RepositoryProvider.getMailRepository(applicationContext)
                .deleteDraft(draft.accountId, draft.serverId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ScheduledEmailWorker", "Draft cleanup after send failed: ${e.message}")
        }
    }
    
    private fun readPersistedAttachments(dir: File): List<Triple<String, String, ByteArray>> {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) return emptyList()
        return try {
            val manifest = org.json.JSONArray(manifestFile.readText())
            (0 until manifest.length()).mapNotNull { i ->
                val entry = manifest.getJSONObject(i)
                val file = File(dir, entry.getString("file"))
                if (file.exists()) {
                    Triple(entry.getString("name"), entry.getString("mimeType"), file.readBytes())
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun showNotification(title: String, text: String) {
        if (!com.dedovmosol.iwomail.sync.NotificationHelper.canPostNotifications(applicationContext)) return
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext, 
            com.dedovmosol.iwomail.MailApplication.CHANNEL_SCHEDULED
        )
            .setSmallIcon(com.dedovmosol.iwomail.R.drawable.ic_email)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        applicationContext.getSystemService(android.app.NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    internal companion object {
        const val BODY_FILE = "body.html"
    }
}

/**
 * Сохраняет тело и вложения на диск для отложенной отправки: inputData WorkManager ограничен 10 КБ
 * (Data.MAX_DATA_BYTES) — тело длиннее (HTML с подписью, картинки в тексте) роняло планирование.
 */
internal fun persistScheduledPayload(
    dir: File,
    body: String,
    attachments: List<Triple<String, String, ByteArray>>
) {
    dir.mkdirs()
    File(dir, ScheduledEmailWorker.BODY_FILE).writeText(body)
    if (attachments.isEmpty()) return
    val manifest = org.json.JSONArray()
    for ((index, att) in attachments.withIndex()) {
        val (name, mimeType, bytes) = att
        val safeFileName = "${index}_${name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
        File(dir, safeFileName).writeBytes(bytes)
        manifest.put(org.json.JSONObject().apply {
            put("name", name)
            put("mimeType", mimeType)
            put("file", safeFileName)
        })
    }
    File(dir, "manifest.json").writeText(manifest.toString())
}

/**
 * Планирует отправку письма через WorkManager. [draftId]/[draftStamp] — отправляемый черновик и время
 * его последнего изменения: после отправки он удаляется, если с тех пор не менялся.
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
    importance: Int = 1,
    attachments: List<Triple<String, String, ByteArray>> = emptyList(),
    draftId: String? = null,
    draftStamp: Long = -1L
) {
    val payloadDir = File(context.filesDir, "scheduled_attachments/${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}")
    persistScheduledPayload(payloadDir, body, attachments)
    
    val data = workDataOf(
        "accountId" to accountId,
        "to" to to,
        "cc" to cc,
        "bcc" to bcc,
        "subject" to subject,
        "requestReadReceipt" to requestReadReceipt,
        "requestDeliveryReceipt" to requestDeliveryReceipt,
        "importance" to importance,
        "attachmentDir" to payloadDir.absolutePath,
        "draftId" to (draftId ?: ""),
        "draftStamp" to draftStamp,
        // SYNC-6: стабильный clientId на всю жизнь задачи (переживает retry) → без дубля отправки.
        "clientId" to java.util.UUID.randomUUID().toString().replace("-", "")
    )
    
    val request = OneTimeWorkRequestBuilder<ScheduledEmailWorker>()
        .setInputData(data)
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()
    
    WorkManager.getInstance(context).enqueue(request)
}

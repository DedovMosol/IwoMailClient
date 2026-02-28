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
        val body = inputData.getString("body") ?: ""
        val requestReadReceipt = inputData.getBoolean("requestReadReceipt", false)
        val requestDeliveryReceipt = inputData.getBoolean("requestDeliveryReceipt", false)
        val importance = inputData.getInt("importance", 1)
        val attachmentDirPath = inputData.getString("attachmentDir") ?: ""
        
        val accountRepo = AccountRepository(applicationContext)
        val account = accountRepo.getAccount(accountId) ?: return Result.failure()
        
        val client = accountRepo.createEasClient(accountId) ?: return Result.failure()
        
        val attachmentDir = if (attachmentDirPath.isNotBlank()) File(attachmentDirPath) else null
        val sendResult = if (attachmentDir != null) {
            val attachments = readPersistedAttachments(attachmentDir)
            if (attachments.isNotEmpty()) {
                client.sendMailWithAttachments(
                    to, subject, body, cc, bcc, attachments,
                    requestReadReceipt, requestDeliveryReceipt, importance
                )
            } else {
                client.sendMail(
                    to, subject, body, cc, bcc,
                    importance = importance,
                    requestReadReceipt = requestReadReceipt,
                    requestDeliveryReceipt = requestDeliveryReceipt
                )
            }
        } else {
            client.sendMail(
                to, subject, body, cc, bcc,
                importance = importance,
                requestReadReceipt = requestReadReceipt,
                requestDeliveryReceipt = requestDeliveryReceipt
            )
        }
        
        return when (sendResult) {
            is EasResult.Success -> {
                attachmentDir?.deleteRecursively()
                val isRu = java.util.Locale.getDefault().language == "ru"
                val title = NotificationStrings.getEmailSent(isRu)
                val text = NotificationStrings.getScheduledEmailSent(to, isRu)
                showNotification(title, text)
                Result.success()
            }
            is EasResult.Error -> Result.retry()
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
 * Сохраняет вложения на диск для отложенной отправки.
 * WorkManager inputData ограничен 10 КБ — байты вложений хранятся в файлах.
 */
private fun persistAttachments(
    context: Context,
    attachments: List<Triple<String, String, ByteArray>>
): String? {
    if (attachments.isEmpty()) return null
    val dir = File(context.filesDir, "scheduled_attachments/${System.currentTimeMillis()}")
    dir.mkdirs()
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
    return dir.absolutePath
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
    importance: Int = 1,
    attachments: List<Triple<String, String, ByteArray>> = emptyList()
) {
    val attachmentDir = persistAttachments(context, attachments)
    
    val data = workDataOf(
        "accountId" to accountId,
        "to" to to,
        "cc" to cc,
        "bcc" to bcc,
        "subject" to subject,
        "body" to body,
        "requestReadReceipt" to requestReadReceipt,
        "requestDeliveryReceipt" to requestDeliveryReceipt,
        "importance" to importance,
        "attachmentDir" to (attachmentDir ?: "")
    )
    
    val request = OneTimeWorkRequestBuilder<ScheduledEmailWorker>()
        .setInputData(data)
        .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        .build()
    
    WorkManager.getInstance(context).enqueue(request)
}

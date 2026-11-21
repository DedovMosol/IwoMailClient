package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.dedovmosol.iwomail.domain.isRetryableSendError
import com.dedovmosol.iwomail.domain.refreshSentFolder
import com.dedovmosol.iwomail.domain.keepScheduledAsDraft
import com.dedovmosol.iwomail.domain.send
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.sync.ScheduledSend
import com.dedovmosol.iwomail.sync.SendNotifications
import com.dedovmosol.iwomail.ui.NotificationStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Отправка запланированного письма ([ScheduledSend]). Класс и пакет — прежние: задачи, поставленные до обновления,
 * WorkManager находит по имени класса.
 *
 * - Временная ошибка (нет связи, сервер перегружен) — повтор с нарастающей паузой, не больше [MAX_ATTEMPTS] раз.
 * - Ошибка, которую повтор не исправит (адрес, размер, политика), или попытки кончились — письмо в Черновики и
 *   уведомление с причиной. Раньше повторы шли бесконечно, молча, раз в несколько часов.
 */
class ScheduledEmailWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val root = ScheduledSend.root(applicationContext)
        val stored = inputData.getString(ScheduledSend.KEY_PAYLOAD_DIR)?.takeIf { it.isNotBlank() }?.let(::File)
        if (stored != null && (stored.parentFile != root || stored.canonicalFile.parentFile != root.canonicalFile)) {
            return@withContext Result.failure()
        }
        val legacyId = id.toString().replace("-", "")
        val dir = stored ?: File(root, "legacy_$legacyId")
        ScheduledSend.lock(dir).withLock {
            // Old inline work retains its own payload in WorkManager even after draft recovery or delivery.
            // Keep a small receipt keyed by that work ID so it cannot reconstruct and resend the job.
            val receipt = if (stored == null) ScheduledSend.legacyInlineReceipt(dir) else null
            if (receipt?.exists() == true) return@withLock Result.success()
            if (receipt != null && ScheduledSend.hasFailureMarker(dir)) {
                // A crash can land between the terminal marker and the metadata commit. WorkManager's
                // inline copy may reconstruct a recovery payload here, but must never reach sendScheduled.
                return@withLock try {
                    if (!ScheduledSend.hasMetadata(dir)) {
                        val job = legacyJob(inputData, null, legacyId) ?: return@withLock corrupt(dir)
                        ScheduledSend.publishFailed(applicationContext, dir, job)
                    }
                    markConsumed(receipt)
                    Result.failure()
                } catch (e: java.io.IOException) {
                    Result.retry() // Retry local recovery only; the terminal marker still blocks delivery.
                }
            }
            when (ScheduledSend.status(dir)) {
                ScheduledSend.Status.FAILED -> return@withLock Result.failure()
                ScheduledSend.Status.CORRUPT -> return@withLock corrupt(dir)
                ScheduledSend.Status.PENDING -> {
                    val job = ScheduledSend.read(dir) ?: return@withLock corrupt(dir)
                    if (job.sendAt > System.currentTimeMillis()) return@withLock Result.retry()
                    return@withLock sendScheduled(job, dir)
                }
                ScheduledSend.Status.GONE -> Unit
            }
            // Never fall back to inputData if a disk-format job has (or had) a committed payload.
            if (ScheduledSend.hasMetadata(dir) || stored != null && !dir.exists()) return@withLock Result.success()
            val job = legacyJob(inputData, stored, legacyId) ?: return@withLock corrupt(dir)
            // Disk legacy jobs already have durable payload files. Publish metadata last; retain their original bytes.
            if (stored != null) {
                if (!File(dir, ScheduledSend.BODY_FILE).exists()) {
                    ScheduledSend.atomicText(File(dir, ScheduledSend.BODY_FILE), job.mail.body)
                }
                ScheduledSend.writeNewMeta(dir, job)
            }
            sendScheduled(job, dir, inlineReceipt = receipt)
        }
    }

    private fun markConsumed(receipt: File) {
        receipt.parentFile?.mkdirs()
        receipt.createNewFile()
    }

    private fun corrupt(dir: File): Result {
        if (dir.isDirectory) ScheduledSend.quarantine(applicationContext, dir)
        return Result.failure()
    }

    private suspend fun sendScheduled(job: ScheduledSend.Job, dir: File, inlineReceipt: File? = null): Result {
        suspend fun giveUp(reason: String, saveAsDraft: Boolean = true): Result =
            giveUp(job, dir, inlineReceipt, reason, saveAsDraft)
        val accountRepo = RepositoryProvider.getAccountRepository(applicationContext)
        if (accountRepo.getAccount(job.accountId) == null) {
            return giveUp("Account not found", saveAsDraft = false)
        }
        val client = accountRepo.createEasClient(job.accountId)
            ?: return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry()
            else giveUp(com.dedovmosol.iwomail.data.repository.RepositoryErrors.CLIENT_CREATE_FAILED)
        return when (val result = client.send(job.mail, stableClientId = job.clientId)) {
            is EasResult.Success -> {
                withContext(NonCancellable) {
                    if (inlineReceipt != null) markConsumed(inlineReceipt)
                    else ScheduledSend.finish(applicationContext, dir)
                }
                deleteSentDraft(job)
                val ru = SendNotifications.isRussian(applicationContext)
                SendNotifications.show(
                    applicationContext, MailApplication.CHANNEL_SCHEDULED, SendNotifications.idFor(job.clientId),
                    NotificationStrings.getEmailSent(ru),
                    if (job.undo) NotificationStrings.getEmailSentTo(firstRecipient(job.mail), ru)
                    else NotificationStrings.getScheduledEmailSent(firstRecipient(job.mail), ru)
                )
                refreshSentFolder(applicationContext, job.accountId)
                Result.success()
            }
            is EasResult.Error -> {
                if (isRetryableSendError(result.message) && runAttemptCount + 1 < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    giveUp(result.message)
                }
            }
        }
    }

    private suspend fun giveUp(
        job: ScheduledSend.Job, dir: File, inlineReceipt: File?, reason: String, saveAsDraft: Boolean
    ): Result {
        if (inlineReceipt != null) {
            try {
                withContext(NonCancellable) {
                    ScheduledSend.publishFailed(applicationContext, dir, job)
                    markConsumed(inlineReceipt)
                }
            } catch (e: java.io.IOException) {
                return if (ScheduledSend.hasFailureMarker(dir)) Result.retry() else Result.failure()
            }
        }
        val saved = ScheduledSend.retainFailure(applicationContext, dir) {
            saveAsDraft && keepScheduledAsDraft(applicationContext, job.accountId, job.mail, job.draftId, job.draftStamp)
        }
        SendNotifications.notSent(
            applicationContext, MailApplication.CHANNEL_SCHEDULED, SendNotifications.idFor(job.clientId),
            job.mail.subject, reason, saved
        )
        return Result.failure()
    }

    /**
     * Отправлен черновик — он удаляется, как при обычной отправке. Только если после планирования его не меняли:
     * правка, сделанная после, — уже другое письмо, его не трогаем.
     */
    private suspend fun deleteSentDraft(job: ScheduledSend.Job) {
        val draftId = job.draftId ?: return
        val draft = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(applicationContext)
            .emailDao().getEmail(draftId) ?: return
        if (draft.dateReceived == job.draftStamp) com.dedovmosol.iwomail.domain.deleteDraftQuietly(applicationContext, draftId)
    }

    /** Срочная задача на Android 11 и ниже работает как служба переднего плана — ей нужно уведомление. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ru = SendNotifications.isRussian(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, MailApplication.CHANNEL_SCHEDULED)
            .setSmallIcon(com.dedovmosol.iwomail.R.drawable.ic_email)
            // Этот же воркер досылает и письма с отменой отправки — общее название
            .setContentTitle(if (ru) "Отправка письма" else "Sending email")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FOREGROUND_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_ID, notification)
        }
    }

    internal companion object {
        /** Попыток при временных ошибках: с паузой 1, 2, 4… минут — около двух часов. */
        const val MAX_ATTEMPTS = 8
        private const val FOREGROUND_ID = 9_994

        /** Кому — для уведомления «отправлено»: первый из Кому, Копии, Скрытой копии. */
        internal fun firstRecipient(mail: OutgoingMail): String =
            listOf(mail.to, mail.cc, mail.bcc).firstOrNull { it.isNotBlank() }
                ?.split(',', ';')?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        /** Задача, поставленная прежней версией: поля — в inputData, тело — в каталоге (или в inputData совсем старых). */
        internal fun legacyJob(
            data: Data, dir: File?, fallbackClientId: String = java.util.UUID.randomUUID().toString().replace("-", "")
        ): ScheduledSend.Job? = runCatching {
            val to = requireNotNull(data.getString("to"))
            val accountId = data.getLong("accountId", -1).also { require(it >= 0) }
            val diskBody = dir?.let { File(it, ScheduledSend.BODY_FILE) }?.takeIf { it.exists() }
            val body = if (diskBody == null) requireNotNull(data.getString("body")) else {
                require(diskBody.isFile && diskBody.canonicalFile.parentFile == dir.canonicalFile)
                diskBody.readText()
            }
            ScheduledSend.Job(
                accountId = accountId,
                mail = OutgoingMail(
                    to = to,
                    cc = data.getString("cc").orEmpty(),
                    bcc = data.getString("bcc").orEmpty(),
                    subject = data.getString("subject").orEmpty(),
                    body = body,
                    attachments = dir?.let { ScheduledSend.readAttachments(it, requireManifest = diskBody == null) }.orEmpty(),
                    importance = data.getInt("importance", 1),
                    requestReadReceipt = data.getBoolean("requestReadReceipt", false),
                    requestDeliveryReceipt = data.getBoolean("requestDeliveryReceipt", false)
                ),
                sendAt = 0L,
                clientId = data.getString("clientId")?.takeIf { it.isNotBlank() } ?: fallbackClientId,
                draftId = data.getString("draftId")?.takeIf { it.isNotBlank() },
                draftStamp = data.getLong("draftStamp", -1L)
            )
        }.getOrNull()
    }
}

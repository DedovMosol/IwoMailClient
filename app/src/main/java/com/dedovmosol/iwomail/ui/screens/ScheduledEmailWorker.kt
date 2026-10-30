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
import kotlinx.coroutines.sync.withLock
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

    override suspend fun doWork(): Result {
        val dir = inputData.getString(ScheduledSend.KEY_PAYLOAD_DIR)?.takeIf { it.isNotBlank() }?.let(::File)
        return ScheduledSend.lock(dir ?: File("")).withLock {
            // Каталога нет — письмо уже отправлено (будильником или подстраховкой) или отменено
            if (dir != null && !dir.exists()) return@withLock Result.success()
            val job = dir?.let { ScheduledSend.read(it) } ?: legacyJob(inputData, dir) ?: return@withLock Result.failure()
            sendScheduled(job, dir)
        }
    }

    private suspend fun sendScheduled(job: ScheduledSend.Job, dir: File?): Result {
        val accountRepo = RepositoryProvider.getAccountRepository(applicationContext)
        if (accountRepo.getAccount(job.accountId) == null) {
            // Аккаунт удалён — отправить не от кого, сохранить некуда
            return giveUp(job, dir, "Account not found", saveAsDraft = false)
        }
        val client = accountRepo.createEasClient(job.accountId)
            ?: return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else giveUp(job, dir, com.dedovmosol.iwomail.data.repository.RepositoryErrors.CLIENT_CREATE_FAILED, true)
        return when (val result = client.send(job.mail, stableClientId = job.clientId)) {
            is EasResult.Success -> {
                dir?.let { ScheduledSend.finish(applicationContext, it) }
                deleteSentDraft(job)
                val ru = SendNotifications.isRussian(applicationContext)
                SendNotifications.show(
                    applicationContext, MailApplication.CHANNEL_SCHEDULED, SendNotifications.idFor(job.clientId),
                    NotificationStrings.getEmailSent(ru),
                    NotificationStrings.getScheduledEmailSent(firstRecipient(job.mail), ru)
                )
                refreshSentFolder(applicationContext, job.accountId)
                Result.success()
            }
            is EasResult.Error -> {
                if (isRetryableSendError(result.message) && runAttemptCount + 1 < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    giveUp(job, dir, result.message, saveAsDraft = true)
                }
            }
        }
    }

    /**
     * Не отправить: в Черновики ([keepScheduledAsDraft]) и уведомление с причиной. Каталог письма удаляется —
     * больше его никто не отправит.
     */
    private suspend fun giveUp(job: ScheduledSend.Job, dir: File?, reason: String, saveAsDraft: Boolean): Result {
        val saved = saveAsDraft &&
            keepScheduledAsDraft(applicationContext, job.accountId, job.mail, job.draftId, job.draftStamp)
        SendNotifications.notSent(
            applicationContext, MailApplication.CHANNEL_SCHEDULED, SendNotifications.idFor(job.clientId),
            job.mail.subject, reason, saved
        )
        dir?.let { ScheduledSend.finish(applicationContext, it) }
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
            .setContentTitle(if (ru) "Отправка запланированного письма" else "Sending scheduled email")
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
        internal fun legacyJob(data: Data, dir: File?): ScheduledSend.Job? {
            val to = data.getString("to") ?: return null
            val body = dir?.let { File(it, ScheduledSend.BODY_FILE) }?.takeIf { it.exists() }?.readText()
                ?: data.getString("body").orEmpty()
            return ScheduledSend.Job(
                accountId = data.getLong("accountId", -1),
                mail = OutgoingMail(
                    to = to,
                    cc = data.getString("cc").orEmpty(),
                    bcc = data.getString("bcc").orEmpty(),
                    subject = data.getString("subject").orEmpty(),
                    body = body,
                    attachments = dir?.let { ScheduledSend.readAttachments(it) }.orEmpty(),
                    importance = data.getInt("importance", 1),
                    requestReadReceipt = data.getBoolean("requestReadReceipt", false),
                    requestDeliveryReceipt = data.getBoolean("requestDeliveryReceipt", false)
                ),
                sendAt = 0L,
                clientId = data.getString("clientId") ?: java.util.UUID.randomUUID().toString().replace("-", ""),
                draftId = data.getString("draftId")?.takeIf { it.isNotBlank() },
                draftStamp = data.getLong("draftStamp", -1L)
            )
        }
    }
}

package com.dedovmosol.iwomail.ui.screens.emaildetail

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.CalendarRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.TaskRepository
import com.dedovmosol.iwomail.eas.AttachmentErrors
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.util.ICalParser
import com.dedovmosol.iwomail.util.MimeHtmlProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import com.dedovmosol.iwomail.util.detectImageMimeType
import com.dedovmosol.iwomail.util.normalizeContentId
import com.dedovmosol.iwomail.util.readStreamLimited
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Base64
import java.util.Locale

private fun <T> EasResult<T>.toUnit(): EasResult<Unit> = when (this) {
    is EasResult.Success -> EasResult.Success(Unit)
    is EasResult.Error -> this
}
private val MAX_AUTO_INLINE_IMAGE_BYTES =
    com.dedovmosol.iwomail.domain.EditorInlineBudget.MAX_BYTES_PER_IMAGE.toLong()
private val MAX_AUTO_INLINE_TOTAL_BYTES =
    com.dedovmosol.iwomail.domain.EditorInlineBudget.MAX_TOTAL_BYTES.toLong()

/**
 * Business logic holder for EmailDetailScreen.
 * Encapsulates all network/DB side effects, keeping UI state in the Composable.
 *
 * Protocol notes (Exchange 2007 SP1 / EAS 12.1):
 * - Attachment download: ItemOperations Fetch (multipart) → GetAttachment POST (EAS 12.x) → EWS GetAttachment,
 *   streamed to a file ([downloadAttachmentToFile]); small ones in memory ([fetchAttachmentBytes])
 * - Meeting response: raw MIME via SendMail (EAS 12.1), WBXML wrapper for 14.0+
 * - Mark as Read: Sync Change with <Read>1</Read> (MS-ASEMAIL §2.2.2.58)
 * - MoveItems: supported since EAS 2.5
 */
class EmailDetailActions(
    private val mailRepo: MailRepository,
    private val accountRepo: AccountRepository,
    private val calendarRepo: CalendarRepository,
    private val taskRepo: TaskRepository
) {

    /**
     * Downloads attachment bytes from local cache or EAS/EWS server.
     * Returns EasResult.Success(ByteArray) or EasResult.Error.
     *
     * Exchange (EAS 12.1 … 2013): скачивание по FileReference через GetAttachment
     * (MS-ASCMD §2.2.1.7) и ItemOperations Fetch (MS-ASCMD §2.2.1.10). Если первая попытка
     * падает, выполняется self-heal ([refreshedFileReferenceOrNull]): заново запрашиваем свежий
     * FileReference и повторяем загрузку один раз. Это устраняет баг «иногда вложение не
     * открывается/не скачивается, помогает ручное обновление письма» — обновление ссылки теперь
     * происходит автоматически.
     */
    suspend fun fetchAttachmentBytes(att: AttachmentEntity): EasResult<ByteArray> {
        if (att.downloaded && att.localPath != null) {
            val f = File(att.localPath)
            if (f.exists()) {
                val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                return EasResult.Success(bytes)
            }
        }

        val first = downloadAttachmentBytesOnce(att.fileReference)
        if (first is EasResult.Success) return first
        if (AttachmentErrors.isFinal((first as EasResult.Error).message)) return first

        val freshRef = refreshedFileReferenceOrNull(att) ?: return first
        return downloadAttachmentBytesOnce(freshRef)
    }

    private suspend fun downloadAttachmentBytesOnce(fileReference: String): EasResult<ByteArray> {
        val account = accountRepo.getActiveAccountSync()
        val easClient = account?.let { accountRepo.createEasClient(it.id) }
            ?: return EasResult.Error("NO_CLIENT")
        val result = withContext(Dispatchers.IO) {
            easClient.downloadAttachment(fileReference)
        }
        if (result is EasResult.Success) {
            easClient.policyKey?.let { newKey ->
                if (newKey != account.policyKey) {
                    accountRepo.savePolicyKey(account.id, newKey)
                }
            }
        }
        return result
    }

    /**
     * Downloads attachment to a file via streaming, with the same self-heal retry as
     * [fetchAttachmentBytes] (used by the "Save as…" document-picker path).
     */
    suspend fun downloadAttachmentToFile(att: AttachmentEntity, destFile: File): EasResult<Unit> {
        val first = withContext(Dispatchers.IO) { mailRepo.downloadAttachmentToFile(att, destFile) }
        if (first is EasResult.Success) return first.toUnit()
        // Свежая ссылка не поможет при лимите размера (EWS уже пробовали), сети, учётных данных, нехватке места
        val error = (first as EasResult.Error).message
        if (error == AttachmentErrors.TOO_LARGE || AttachmentErrors.isFinal(error)) return first.toUnit()

        val freshRef = refreshedFileReferenceOrNull(att) ?: return first.toUnit()
        return withContext(Dispatchers.IO) {
            mailRepo.downloadAttachmentToFile(att.copy(fileReference = freshRef, downloaded = false), destFile)
        }.toUnit()
    }

    /**
     * Self-heal для скачивания вложений Exchange: повторно получает свежие метаданные вложения
     * ([MailRepository.refreshAttachmentMetadata] → ItemOperations Fetch метаданных) и возвращает
     * ОБНОВЛЁННЫЙ FileReference, если он изменился.
     *
     * На Exchange (EAS 12.1 … 2013) FileReference, используемый GetAttachment и ItemOperations
     * Fetch, привязан к текущему состоянию синхронизации письма и инвалидируется при ресинке; он
     * также может быть пустым, если письмо синхронизировалось до первого refresh. В обоих случаях
     * первая загрузка падает — ровно это пользователь раньше чинил вручную «обновлением письма».
     * Возвращаем non-null ТОЛЬКО когда ссылка реально изменилась (стала непустой и отличается от
     * прежней): иначе повтор бессмыслен (вложение действительно недоступно / удалено на сервере)
     * и создавал бы лишнюю нагрузку.
     *
     * @return новый непустой FileReference, отличный от [AttachmentEntity.fileReference], либо null.
     */
    private suspend fun refreshedFileReferenceOrNull(att: AttachmentEntity): String? {
        return try {
            withContext(Dispatchers.IO) {
                mailRepo.refreshAttachmentMetadata(att.emailId)
                val fresh = mailRepo.getAttachmentsSync(att.emailId)
                val match = fresh.firstOrNull { it.id == att.id }
                    ?: fresh.firstOrNull { it.displayName == att.displayName }
                match?.fileReference?.takeIf { it.isNotBlank() && it != att.fileReference }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    suspend fun markAsRead(emailId: String, read: Boolean): EasResult<Unit> =
        mailRepo.markAsRead(emailId, read).toUnit()

    suspend fun loadEmailBody(emailId: String, forceReload: Boolean = false) =
        mailRepo.loadEmailBody(emailId, forceReload = forceReload)

    suspend fun refreshAttachmentMetadata(emailId: String) {
        withContext(Dispatchers.IO) {
            mailRepo.refreshAttachmentMetadata(emailId)
        }
    }

    suspend fun syncAndReloadBody(emailId: String, accountId: Long, folderId: String): EasResult<Unit> {
        val syncResult = kotlinx.coroutines.withTimeoutOrNull(45_000L) {
            withContext(Dispatchers.IO) { mailRepo.syncEmails(accountId, folderId) }
        } ?: return EasResult.Error("TIMEOUT")
        if (syncResult is EasResult.Error) return syncResult.toUnit()
        val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            withContext(Dispatchers.IO) { mailRepo.loadEmailBody(emailId, forceReload = true) }
        } ?: return EasResult.Error("TIMEOUT")
        return result.toUnit()
    }

    suspend fun deleteEmails(emailIds: List<String>, isInTrash: Boolean): EasResult<Any> {
        return withContext(Dispatchers.IO) {
            if (isInTrash) {
                mailRepo.deleteEmailsPermanentlyWithProgress(emailIds) { _, _ -> }
            } else {
                mailRepo.moveToTrash(emailIds)
            }
        }
    }

    suspend fun moveEmails(emailIds: List<String>, targetFolderId: String): EasResult<Any> =
        withContext(Dispatchers.IO) { mailRepo.moveEmails(emailIds, targetFolderId) }

    suspend fun restoreFromTrash(emailIds: List<String>): EasResult<Any> =
        withContext(Dispatchers.IO) { mailRepo.restoreFromTrash(emailIds) }

    suspend fun pendingReadReceipt(emailId: String): String? =
        mailRepo.pendingReadReceipt(emailId)

    suspend fun ensureListUnsubscribe(emailId: String) =
        mailRepo.ensureListUnsubscribe(emailId)

    suspend fun answerReadReceipt(emailId: String, send: Boolean): EasResult<Unit> =
        mailRepo.answerReadReceipt(emailId, send).toUnit()

    suspend fun toggleFlag(emailId: String) =
        mailRepo.toggleFlag(emailId)

    suspend fun setFlag(emailId: String, flag: com.dedovmosol.iwomail.eas.MailFlag): EasResult<Unit> =
        mailRepo.setFlag(emailId, flag).toUnit()

    /** Maps the decoded HTML's CIDs to display data only; the saved body stays unchanged. */
    suspend fun loadInlineImages(
        emailId: String,
        accountId: Long,
        cids: Set<String>,
        mimeBody: String?,
        attachments: List<AttachmentEntity>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (cids.isEmpty()) return@withContext emptyMap()
        fun key(cid: String) = normalizeContentId(cid).lowercase(Locale.ROOT)
        val missing = cids.mapTo(linkedSetOf(), ::key)
        val images = linkedMapOf<String, String>()
        var remaining = MAX_AUTO_INLINE_TOTAL_BYTES
        fun limit() = minOf(MAX_AUTO_INLINE_IMAGE_BYTES, remaining)
        fun add(id: String, bytes: ByteArray) {
            if (id !in missing || bytes.size.toLong() > limit()) return
            val mimeType = detectImageMimeType(bytes) ?: return
            images[id] = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"
            missing.remove(id)
            remaining -= bytes.size
        }
        if (mimeBody != null) {
            val ioContext = coroutineContext
            MimeHtmlProcessor.forEachReferencedInlineImage(mimeBody, missing, ::limit) { cid, bytes ->
                ioContext.ensureActive()
                add(cid, bytes)
            }
        }
        // Locally saved draft images can have a CID without a server FileReference.
        for (cid in missing.toList()) {
            coroutineContext.ensureActive()
            if (limit() <= 0) break
            val att = attachments.singleOrNull { it.emailId == emailId && it.contentId?.let(::key) == cid } ?: continue
            val file = att.localPath?.let(::File)?.takeIf { it.isFile && it.length() <= limit() } ?: continue
            val bytes = try {
                file.inputStream().use { readStreamLimited(it, limit()) }
            } catch (_: java.io.IOException) {
                null
            }
            if (bytes != null) add(cid, bytes)
        }
        if (missing.isNotEmpty() && remaining > 0) {
            // Retain images already read if a later fetch times out; parent cancellation still propagates.
            withTimeoutOrNull(20_000L) {
                mailRepo.loadInlineAttachmentBytes(emailId, accountId, missing.toSet(), attachments,
                    MAX_AUTO_INLINE_IMAGE_BYTES, remaining, ::add)
            }
        }
        images
    }

    /**
     * Sends a meeting response to the organizer via EAS SendMail.
     * EAS 12.1: raw MIME with Content-Type: message/rfc822 (MS-ASCMD §2.2.1.17).
     */
    suspend fun sendResponseToOrganizer(
        accountId: Long,
        organizerEmail: String,
        subject: String,
        body: String
    ) {
        val account = withContext(Dispatchers.IO) {
            accountRepo.getAccount(accountId)
        } ?: return
        withContext(Dispatchers.IO) {
            val easClient = accountRepo.createEasClient(account.id)
            easClient?.sendMail(
                to = organizerEmail,
                subject = subject,
                body = body
            )
        }
    }

    /**
     * Accepts a meeting invitation: creates calendar event + sends response.
     */
    suspend fun acceptMeeting(
        accountId: Long,
        summary: String,
        startTime: Long,
        endTime: Long,
        location: String,
        description: String,
        busyStatus: Int,
        organizerEmail: String,
        responseSubject: String,
        responseBody: String
    ): EasResult<Unit> {
        val result = withContext(Dispatchers.IO) {
            calendarRepo.createEvent(
                accountId = accountId,
                subject = summary,
                startTime = startTime,
                endTime = endTime,
                location = location,
                body = description,
                reminder = 15,
                busyStatus = busyStatus
            )
        }
        if (result is EasResult.Success) {
            sendResponseToOrganizer(accountId, organizerEmail, responseSubject, responseBody)
        }
        return result.toUnit()
    }

    /**
     * Declines a meeting invitation: sends decline response (no calendar event created).
     */
    suspend fun declineMeeting(
        accountId: Long,
        organizerEmail: String,
        responseSubject: String,
        responseBody: String
    ) {
        sendResponseToOrganizer(accountId, organizerEmail, responseSubject, responseBody)
    }

    /**
     * Creates a task from email task data.
     */
    suspend fun createTask(
        accountId: Long,
        subject: String,
        body: String,
        dueDate: Long,
        reminderSet: Boolean,
        reminderTime: Long
    ): EasResult<Unit> = withContext(Dispatchers.IO) {
        taskRepo.createTask(
            accountId = accountId,
            subject = subject,
            body = body,
            dueDate = dueDate,
            reminderSet = reminderSet,
            reminderTime = if (reminderSet) reminderTime else 0
        )
    }.toUnit()

    /**
     * Updates attendee status for a meeting response.
     */
    suspend fun updateAttendeeStatus(
        accountId: Long,
        meetingSubject: String,
        attendeeEmail: String,
        status: Int
    ): EasResult<Unit> = withContext(Dispatchers.IO) {
        calendarRepo.updateAttendeeStatus(
            accountId = accountId,
            meetingSubject = meetingSubject,
            attendeeEmail = attendeeEmail,
            status = status
        )
    }.toUnit()

    suspend fun getEmailSync(emailId: String) = mailRepo.getEmailSync(emailId)

    suspend fun findSameEmailId(email: com.dedovmosol.iwomail.data.database.EmailEntity) = mailRepo.findSameEmailId(email)

    fun isFolderSyncing(folderId: String) = mailRepo.isFolderSyncing(folderId)
}

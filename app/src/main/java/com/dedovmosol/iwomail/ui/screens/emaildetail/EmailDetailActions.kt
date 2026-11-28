package com.dedovmosol.iwomail.ui.screens.emaildetail

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.CalendarRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.TaskRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.util.MimeHtmlProcessor
import com.dedovmosol.iwomail.util.detectImageMimeType
import com.dedovmosol.iwomail.util.normalizeContentId
import com.dedovmosol.iwomail.util.readStreamLimited
import kotlinx.coroutines.Dispatchers
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

    /** Bounded iCalendar fetch; the repository keeps the owner and attachment identity through retries. */
    suspend fun fetchAttachmentBytes(att: AttachmentEntity, owner: EmailEntity): EasResult<ByteArray> =
        mailRepo.fetchAttachmentBytes(att, owner)

    /** Manual downloads use the same identity checks without copying a large file into memory. */
    suspend fun downloadAttachmentToFile(att: AttachmentEntity, destFile: File, owner: EmailEntity): EasResult<Unit> =
        mailRepo.downloadAttachmentToFile(att, destFile, owner).toUnit()

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

    suspend fun moveToTrash(emailIds: List<String>): EasResult<Int> =
        withContext(Dispatchers.IO) { mailRepo.moveToTrash(emailIds) }

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
        // Locally saved images can have a CID without a server FileReference.
        val sameMessage = attachments.filter { it.emailId == emailId }
        for (cid in missing.toList()) {
            coroutineContext.ensureActive()
            if (limit() <= 0) break
            val byCid = sameMessage.filter { it.contentId?.let(::key) == cid }
            // Outlook can use cid:image001.png@... with only image001.png in local metadata.
            // Count duplicate names and never substitute a file with a conflicting explicit CID.
            val att = if (byCid.isNotEmpty()) byCid.singleOrNull() else sameMessage.singleOrNull {
                val name = it.displayName.trim()
                name.isNotEmpty() && (name.equals(cid, ignoreCase = true) ||
                    name.equals(cid.substringBefore('@'), ignoreCase = true))
            }?.takeIf { it.contentId.isNullOrBlank() }
            if (att == null) continue
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

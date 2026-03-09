package com.dedovmosol.iwomail.ui.screens.emaildetail

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.CalendarRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.TaskRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.util.ICalParser
import com.dedovmosol.iwomail.util.MimeHtmlProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File

private fun <T> EasResult<T>.toUnit(): EasResult<Unit> = when (this) {
    is EasResult.Success -> EasResult.Success(Unit)
    is EasResult.Error -> this
}

/**
 * Business logic holder for EmailDetailScreen.
 * Encapsulates all network/DB side effects, keeping UI state in the Composable.
 *
 * Protocol notes (Exchange 2007 SP1 / EAS 12.1):
 * - Attachment download: GetAttachment (EAS 2.5/12.0/12.1) primary, ItemOperations Fetch fallback
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
     * Downloads attachment bytes from local cache or EAS server.
     * Returns EasResult.Success(ByteArray) or EasResult.Error.
     * EAS 12.1: uses GetAttachment command (MS-ASCMD §2.2.1.7).
     */
    suspend fun fetchAttachmentBytes(att: AttachmentEntity): EasResult<ByteArray> {
        if (att.downloaded && att.localPath != null) {
            val f = File(att.localPath)
            if (f.exists()) {
                val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                return EasResult.Success(bytes)
            }
        }
        val account = accountRepo.getActiveAccountSync()
        val easClient = account?.let { accountRepo.createEasClient(it.id) }
            ?: return EasResult.Error("NO_CLIENT")
        val result = withContext(Dispatchers.IO) {
            easClient.downloadAttachment(att.fileReference)
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
     * Downloads attachment to a file via streaming.
     * Returns the EAS result.
     */
    suspend fun downloadAttachmentToFile(att: AttachmentEntity, destFile: File): EasResult<Unit> {
        val account = accountRepo.getActiveAccountSync()
        val easClient = account?.let { accountRepo.createEasClient(it.id) }
            ?: return EasResult.Error("NO_CLIENT")
        return withContext(Dispatchers.IO) {
            easClient.downloadAttachmentToFile(att.fileReference, destFile)
        }.toUnit()
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
        withContext(Dispatchers.IO) {
            mailRepo.syncEmails(accountId, folderId)
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            mailRepo.loadEmailBody(emailId, forceReload = true)
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

    suspend fun sendMdn(emailId: String): EasResult<Unit> =
        mailRepo.sendMdn(emailId).toUnit()

    suspend fun markMdnSent(emailId: String) =
        mailRepo.markMdnSent(emailId)

    suspend fun toggleFlag(emailId: String) =
        mailRepo.toggleFlag(emailId)

    /**
     * Loads inline images for an email.
     * Handles MIME extraction (bodyType=4) and CID-referenced attachment download.
     *
     * EAS 12.1: GetAttachment for individual CID images,
     * fetchInlineImages for batch (uses ItemOperations if available).
     */
    suspend fun loadInlineImages(
        emailBody: String,
        bodyType: Int,
        emailServerId: String,
        folderId: String,
        accountId: Long,
        attachments: List<AttachmentEntity>
    ): Map<String, String> {
        if (emailBody.isEmpty()) return emptyMap()

        val bodyLooksMime = emailBody.contains("Content-Type:", ignoreCase = true) &&
                emailBody.contains("boundary", ignoreCase = true)
        if (bodyType == 4 && bodyLooksMime) {
            val mimeImages = withContext(Dispatchers.IO) {
                MimeHtmlProcessor.extractInlineImagesFromMime(emailBody)
            }
            if (mimeImages.isNotEmpty()) return mimeImages
        }

        val cidRefs = com.dedovmosol.iwomail.util.CID_REGEX
            .findAll(emailBody).map { it.groupValues[1] }.toSet()
        if (cidRefs.isEmpty()) return emptyMap()

        val inlineAttachments = attachments.filter { att ->
            if (att.contentId == null) return@filter false
            val cleanCid = att.contentId.removeSurrounding("<", ">")
            cidRefs.contains(cleanCid) || cidRefs.contains(att.contentId) || cidRefs.contains(att.displayName)
        }

        val account = accountRepo.getActiveAccountSync() ?: return emptyMap()
        val newImages = mutableMapOf<String, String>()

        withContext(Dispatchers.IO) {
            val attachmentsWithRef = inlineAttachments.filter { it.fileReference.isNotBlank() }
            if (attachmentsWithRef.isNotEmpty()) {
                supervisorScope {
                    attachmentsWithRef.map { att ->
                        async {
                            try {
                                val easClient = accountRepo.createEasClient(account.id) ?: return@async null
                                when (val result = easClient.downloadAttachment(att.fileReference)) {
                                    is EasResult.Success -> {
                                        val base64 = android.util.Base64.encodeToString(result.data, android.util.Base64.NO_WRAP)
                                        Pair(att, "data:${att.contentType};base64,$base64")
                                    }
                                    is EasResult.Error -> null
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().forEach { pair ->
                        pair.first.contentId?.let { newImages[it] = pair.second }
                        newImages[pair.first.displayName] = pair.second
                    }
                }
            }

            val missingCids = cidRefs.filter { cid ->
                !newImages.containsKey(cid) && !newImages.containsKey("<$cid>")
            }
            if (missingCids.isNotEmpty()) {
                try {
                    val easClient = accountRepo.createEasClient(account.id)
                    if (easClient != null) {
                        val folderServerId = folderId.substringAfter("_")
                        when (val result = easClient.fetchInlineImages(folderServerId, emailServerId)) {
                            is EasResult.Success -> result.data.forEach { (cid, dataUrl) ->
                                newImages[cid] = dataUrl
                            }
                            is EasResult.Error -> { }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }

        return newImages
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
}

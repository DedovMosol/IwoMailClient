package com.dedovmosol.iwomail.domain

import android.content.Context
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.DraftSuccessors
import com.dedovmosol.iwomail.data.repository.DraftUploadLock
import com.dedovmosol.iwomail.data.repository.EmailSyncService
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.eas.EasResult

/** [DraftSaver.Store] поверх репозиториев приложения. */
class RepositoryDraftStore(
    private val context: Context,
    private val mailRepository: MailRepository,
    private val accountRepository: AccountRepository
) : DraftSaver.Store {

    override suspend fun draft(emailId: String): EmailEntity? = mailRepository.getEmailSync(emailId)

    override fun successor(emailId: String): String? = DraftSuccessors.latest(emailId)

    override suspend fun attachments(emailId: String): List<AttachmentEntity> = mailRepository.getAttachmentsSync(emailId)

    override suspend fun attachmentBytes(accountId: Long, attachment: AttachmentEntity, allowNetwork: Boolean): ByteArray? =
        mailRepository.draftAttachmentBytes(accountId, attachment, allowNetwork)

    override suspend fun create(account: AccountEntity, draft: DraftSaver.NewDraft, deviceOnly: Boolean): String? =
        mailRepository.saveDraft(
            accountId = account.id,
            to = draft.to,
            cc = draft.cc,
            bcc = draft.bcc,
            subject = draft.subject,
            serverBody = draft.serverBody,
            localBody = draft.localBody,
            fromEmail = draft.fromEmail,
            fromName = draft.fromName,
            hasAttachments = draft.attachments.isNotEmpty(),
            attachmentFiles = draft.attachments,
            importance = draft.importance,
            forceLocal = deviceOnly,
            receipts = draft.receipts
        )

    override suspend fun update(
        account: AccountEntity,
        serverId: String,
        content: DraftSaver.Content,
        bcc: String?,
        serverBody: String,
        appendBcc: Boolean
    ): EasResult<String> = mailRepository.updateDraft(
        accountId = account.id,
        serverId = serverId,
        to = content.to,
        cc = content.cc,
        bcc = bcc,
        subject = content.subject,
        body = serverBody,
        fromEmail = content.fromEmail,
        fromName = content.fromName,
        importance = content.importance,
        localBody = content.body,
        appendBcc = appendBcc,
        receipts = content.receipts
    )

    override suspend fun delete(accountId: Long, serverId: String, excludeServerId: String?): EasResult<Boolean> =
        mailRepository.deleteDraft(accountId, serverId, excludeEwsItemId = excludeServerId, requireServerDeletion = true)

    override suspend fun removeLocally(emailId: String) = mailRepository.removeDraftLocally(emailId)

    override suspend fun markReplacing(emailId: String, marker: String?) = mailRepository.markDraftReplacing(emailId, marker)

    override suspend fun carryAttachments(rows: List<AttachmentEntity>, toEmailId: String) =
        mailRepository.carryDraftAttachments(rows, toEmailId)

    override suspend fun serverExtras(accountId: Long, serverId: String): DraftSaver.ServerExtras? =
        mailRepository.serverDraftDetails(accountId, serverId)?.let { DraftSaver.ServerExtras(it.bcc, it.receipts) }

    override suspend fun serverReachable(accountId: Long): Boolean {
        // Состояние — до пробы: параллельная синхронизация могла переключить адрес, пока проба шла.
        val usingFallback = accountRepository.isUsingFallback(accountId)
        if (accountRepository.isCurrentEndpointReachable(accountId)) return true
        // Dual-URL: текущий сервер не отвечает — другой (TCP-проба внутри); переключились — работаем через него.
        return accountRepository.hasAlternateUrl(accountId) &&
            accountRepository.switchToOtherEndpoint(accountId, fromFallback = usingFallback)
    }

    override suspend fun findServerDraftBySubject(accountId: Long, subject: String, excludeServerId: String?): String? =
        accountRepository.createEasClient(accountId)?.findUniqueDraftItemIdBySubject(subject, excludeServerId)

    override suspend fun hasOtherDraftWithSubject(folderId: String, subject: String, exceptIds: Set<String>): Boolean =
        mailRepository.hasOtherDraftWithSubject(folderId, subject, exceptIds)

    override suspend fun restore(email: EmailEntity, attachments: List<DraftAttachmentData>) =
        mailRepository.restoreDraftRecord(email, attachments)

    override fun protect(emailId: String) = EmailSyncService.registerDeletedEmail(emailId, context)

    override fun unprotect(emailId: String) = EmailSyncService.unregisterDeletedEmail(emailId, context)

    override suspend fun <T> exclusive(accountId: Long, block: suspend () -> T): T = DraftUploadLock.withLock(accountId, block)
}

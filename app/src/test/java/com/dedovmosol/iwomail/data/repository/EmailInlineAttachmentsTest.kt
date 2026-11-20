package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailDao
import com.dedovmosol.iwomail.data.database.InlineAttachmentTarget
import com.dedovmosol.iwomail.data.database.FolderDao
import com.dedovmosol.iwomail.eas.AttachmentErrors
import com.dedovmosol.iwomail.eas.EasAttachment
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasEmailService
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.EwsAttachments
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EmailInlineAttachmentsTest {
    private val owner = 2L
    private val address = "sender@example.test"
    private val emails = mockk<EmailDao>()
    private val accounts = mockk<AccountRepository>()
    private val folders = mockk<FolderDao>()
    private val client = mockk<EasClient>()
    private val account = mockk<AccountEntity>()
    private val service = EmailOperationsService(mockk(), mockk(), folders, emails, mockk(), accounts, mockk())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1)
    private val date = "2026-01-01T10:00:00.000Z"
    private val email = InlineAttachmentTarget(id = "mail", accountId = owner, folderId = "2_inbox", serverId = "5:2",
        subject = "Test", from = address, dateReceived = EmailSyncService.parseDate(date), internetMessageId = null)
    private val stale = AttachmentEntity(id = 1, emailId = email.id, displayName = "image.png",
        fileReference = "5%3a2%3a1", contentId = "logo", contentType = "image/png", estimatedSize = 0)
    private val pdf = stale.copy(id = 2, displayName = "huge.pdf", fileReference = "5:2:2",
        contentId = "report", contentType = "application/pdf", estimatedSize = 60_000_000)

    init {
        coEvery { emails.getInlineAttachmentTarget(email.id) } returns email
        coEvery { emails.countSameItem(email.id, email.subject, email.from, email.dateReceived) } returns 1
        coEvery { folders.getFolder(email.folderId) } returns null
        coEvery { accounts.getAccount(owner) } returns account
        every { account.accountType } returns AccountType.EXCHANGE.name
        every { account.policyKey } returns null
        coEvery { accounts.createEasClient(owner) } returns client
        every { client.policyKey } returns null
    }

    @Test
    fun `small logo beside huge PDF retries only a verified fresh EAS reference`() = runTest {
        val fresh = EasAttachment("5:2:3", "image.png", "image/png", 0, contentId = "<Logo>")
        coEvery { client.downloadSmallEasAttachment(stale.fileReference, 100L) } returns EasResult.Error(AttachmentErrors.NOT_FOUND)
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns EasResult.Success(
            EasEmailService.AttachmentMetadata(email.subject, date, listOf(fresh))
        )
        coEvery { client.downloadSmallEasAttachment(fresh.fileReference, 100L) } returns EasResult.Success(png)
        val observed = mutableMapOf<String, ByteArray>()

        service.loadInlineAttachmentBytes(email.id, owner, setOf("logo", "report"), listOf(stale, pdf), 100, 200) { id, bytes ->
            observed[id] = bytes
        }

        assertThat(observed.keys).containsExactly("logo")
        assertThat(observed["logo"]).isEqualTo(png)
        coVerify(exactly = 0) { accounts.getActiveAccountSync() }
        coVerify(exactly = 0) { client.downloadSmallEasAttachment(pdf.fileReference, any()) }
        coVerify(exactly = 0) { client.fetchInlineImages(any(), any()) }
    }

    @Test
    fun `renumbered EAS item is rejected and only an exact EWS CID is requested`() = runTest {
        val wrong = EasAttachment("5:2:3", "image.png", "image/png", contentId = "logo")
        coEvery { client.downloadSmallEasAttachment(stale.fileReference, 100L) } returns EasResult.Success(png)
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns EasResult.Success(
            EasEmailService.AttachmentMetadata("Different message", date, listOf(wrong))
        )
        coEvery { client.listInlineAttachmentCandidates(null, null) } returns listOf(
            EwsAttachments.FileAttachment("PDF=", pdf.displayName, "report", "application/pdf"),
            EwsAttachments.FileAttachment("AAMk=", "another-name.png", "<Logo>", "image/png")
        )
        coEvery { client.downloadSmallEwsAttachment("AAMk=", 100L) } returns EasResult.Success(png)
        val loaded = mutableListOf<String>()
        service.loadInlineAttachmentBytes(email.id, owner, setOf("logo", "report"), listOf(stale, pdf), 100, 200) { id, _ -> loaded += id }
        assertThat(loaded).containsExactly("logo")
        coVerify(exactly = 0) { client.downloadSmallEasAttachment(stale.fileReference, any()) }
        coVerify(exactly = 0) { client.downloadSmallEasAttachment(wrong.fileReference, any()) }
        coVerify(exactly = 0) { client.downloadSmallEwsAttachment("PDF=", any()) }
    }

    @Test
    fun `unpadded EWS item id needs no guessed EAS folder or message lookup`() = runTest {
        val draft = email.copy(serverId = "QUJDREVG")
        coEvery { emails.getInlineAttachmentTarget(email.id) } returns draft
        coEvery { client.listInlineAttachmentCandidates(draft.serverId, null) } returns listOf(
            EwsAttachments.FileAttachment("file-id", "logo.png", "logo", "image/png"))
        coEvery { client.downloadSmallEwsAttachment("file-id", 100L) } returns EasResult.Success(png)
        val loaded = mutableListOf<String>()
        service.loadInlineAttachmentBytes(email.id, owner, setOf("logo"), emptyList(), 100, 200) { id, _ -> loaded += id }
        assertThat(loaded).containsExactly("logo")
        coVerify(exactly = 0) { client.fetchInlineAttachmentMetadata(any(), any()) }
    }

    @Test
    fun `cancelled work propagates cancellation and a new call can independently retry`() = runTest {
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns EasResult.Success(
            EasEmailService.AttachmentMetadata(email.subject, date, listOf(
                EasAttachment(stale.fileReference, "image.png", "image/png", contentId = "logo")
            ))
        )
        coEvery { client.downloadSmallEasAttachment(stale.fileReference, 100L) } throws CancellationException("cancelled")
        val observed = mutableListOf<String>()
        suspend fun load() = service.loadInlineAttachmentBytes(email.id, owner, setOf("logo"), listOf(stale), 100, 200) { id, _ ->
            observed += id
        }
        assertThat(runCatching { load() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(observed).isEmpty()
        coEvery { client.downloadSmallEasAttachment(stale.fileReference, 100L) } returns EasResult.Success(png)
        load()
        assertThat(observed).containsExactly("logo")
    }
}

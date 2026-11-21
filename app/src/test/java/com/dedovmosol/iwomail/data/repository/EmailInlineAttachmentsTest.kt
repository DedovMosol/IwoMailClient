package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailDao
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.InlineAttachmentTarget
import com.dedovmosol.iwomail.data.database.FolderDao
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.eas.AttachmentErrors
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.EwsMailFolder
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
        coEvery { client.listInlineAttachmentCandidates(null, null) } returns emptyList()
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
    fun `missing subject is accepted only with matching sender and date`() = runTest {
        val target = email.copy(subject = "(No subject)")
        coEvery { emails.getInlineAttachmentTarget(email.id) } returns target
        val fresh = EasAttachment("5:2:3", "image.png", "image/png", 0, contentId = "<Logo>")
        coEvery { client.fetchInlineAttachmentMetadata("inbox", target.serverId) } returns EasResult.Success(
            EasEmailService.AttachmentMetadata(null, date, listOf(fresh), from = address)
        )
        coEvery { client.downloadSmallEasAttachment(fresh.fileReference, 100L) } returns EasResult.Success(png)
        val observed = mutableListOf<String>()
        service.loadInlineAttachmentBytes(target.id, owner, setOf("logo"), listOf(stale), 100, 200) { id, _ -> observed += id }
        assertThat(observed).containsExactly("logo")
    }

    @Test
    fun `missing subject with a different sender is rejected`() = runTest {
        val target = email.copy(subject = "(No subject)")
        coEvery { emails.getInlineAttachmentTarget(email.id) } returns target
        val fresh = EasAttachment("5:2:3", "image.png", "image/png", 0, contentId = "<Logo>")
        coEvery { client.fetchInlineAttachmentMetadata("inbox", target.serverId) } returns EasResult.Success(
            EasEmailService.AttachmentMetadata(null, date, listOf(fresh), from = "other@example.test")
        )
        val observed = mutableListOf<String>()
        service.loadInlineAttachmentBytes(target.id, owner, setOf("logo"), listOf(stale), 100, 200) { id, _ -> observed += id }
        assertThat(observed).isEmpty()
        coVerify(exactly = 0) { client.downloadSmallEasAttachment(any(), any()) }
    }

    @Test
    fun `titled local mail rejects an absent remote subject even at the same sender and second`() = runTest {
        val fresh = EasAttachment("5:2:3", "image.png", "image/png", contentId = "logo")
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns EasResult.Success(
            EasEmailService.AttachmentMetadata(null, date, listOf(fresh), from = address)
        )
        val observed = mutableListOf<String>()
        service.loadInlineAttachmentBytes(email.id, owner, setOf("logo"), listOf(stale), 100, 200) { id, _ -> observed += id }
        assertThat(observed).isEmpty()
        coVerify(exactly = 0) { client.downloadSmallEasAttachment(any(), any()) }
    }

    @Test
    fun `same Message-ID independently verifies titled mail when the remote subject is omitted`() = runTest {
        val target = email.copy(internetMessageId = "<message@example.test>")
        coEvery { emails.getInlineAttachmentTarget(email.id) } returns target
        val fresh = EasAttachment("5:2:3", "image.png", "image/png", contentId = "logo")
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns EasResult.Success(
            EasEmailService.AttachmentMetadata(null, null, listOf(fresh), internetMessageId = "message@example.test")
        )
        coEvery { client.downloadSmallEasAttachment(fresh.fileReference, 100L) } returns EasResult.Success(png)
        val observed = mutableListOf<String>()
        service.loadInlineAttachmentBytes(email.id, owner, setOf("logo"), listOf(stale), 100, 200) { id, _ -> observed += id }
        assertThat(observed).containsExactly("logo")
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

    private val ownerSnapshot = EmailEntity(email.id, owner, email.folderId, email.serverId, email.from,
        to = "", subject = email.subject, body = "", dateReceived = email.dateReceived, read = true)
    private val ics = stale.copy(displayName = "invite.ics", contentType = "text/calendar", contentId = null)

    private fun invite(ref: String, name: String = ics.displayName, cid: String? = null) =
        EasAttachment(ref, name, ics.contentType, contentId = cid)

    private fun metadata(vararg values: EasAttachment) =
        EasResult.Success(EasEmailService.AttachmentMetadata(email.subject, date, values.toList(), from = email.from))

    @Test
    fun `ICS skips stale positional success and retries a new reference on the same owner`() = runTest {
        val first = "5:2:3"
        val retry = "5:2:5"
        val calendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR".toByteArray()
        coEvery { client.downloadAttachment(ics.fileReference) } returns EasResult.Success("wrong message".toByteArray())
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returnsMany
            listOf(metadata(invite(first)), metadata(invite(retry)))
        coEvery { client.downloadAttachment(first) } returns EasResult.Error(AttachmentErrors.NOT_FOUND)
        coEvery { client.downloadAttachment(retry) } returns EasResult.Success(calendar)
        assertThat((service.fetchAttachmentBytes(ics, ownerSnapshot) as EasResult.Success).data).isEqualTo(calendar)
        coVerify(exactly = 0) { client.downloadAttachment(ics.fileReference) }
        coVerify(exactly = 0) { accounts.getActiveAccountSync() }
        coVerify(exactly = 1) { accounts.createEasClient(owner) }
    }

    @Test
    fun `ICS rejects duplicate filenames or returned attachment with conflicting known CID`() = runTest {
        val pref = "5:2:3"
        val other = "5:2:4"
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returnsMany listOf(
            metadata(invite(pref), invite(other, "INVITE.ICS")), metadata(invite(pref, cid = "wrong@cid"))
        )
        assertThat(service.fetchAttachmentBytes(ics, ownerSnapshot)).isEqualTo(EasResult.Error(AttachmentErrors.NOT_FOUND))
        assertThat(service.fetchAttachmentBytes(ics.copy(contentId = "right@cid"), ownerSnapshot))
            .isEqualTo(EasResult.Error(AttachmentErrors.NOT_FOUND))
        coVerify(exactly = 0) { client.downloadAttachment(any()) }
    }

    @Test
    fun `ICS does not retry after the owner row is reassigned during a request`() = runTest {
        val fresh = "5:2:3"
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns metadata(invite(fresh))
        coEvery { client.downloadAttachment(fresh) } coAnswers {
            coEvery { emails.getInlineAttachmentTarget(email.id) } returns email.copy(accountId = owner + 1)
            EasResult.Error(AttachmentErrors.NOT_FOUND)
        }
        assertThat(service.fetchAttachmentBytes(ics, ownerSnapshot)).isEqualTo(EasResult.Error(AttachmentErrors.NOT_FOUND))
        coVerify(exactly = 1) { client.fetchInlineAttachmentMetadata("inbox", email.serverId) }
        coVerify(exactly = 0) { accounts.createEasClient(owner + 1) }
    }

    @Test
    fun `manual file download streams only a freshly verified reference`() = runTest {
        val fresh = "5:2:3"
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val dest = java.io.File(dir, "attachment.part")
            coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns metadata(invite(fresh))
            coEvery { client.downloadAttachmentToFile(fresh, dest) } answers {
                dest.writeText("verified payload")
                EasResult.Success(dest)
            }
            assertThat(service.downloadAttachmentToFile(ics, dest, ownerSnapshot)).isEqualTo(EasResult.Success(dest))
            assertThat(dest.readText()).isEqualTo("verified payload")
            coVerify(exactly = 0) { client.downloadAttachmentToFile(ics.fileReference, any()) }
            coVerify(exactly = 0) { client.downloadAttachment(any()) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `manual file download falls back to verified EWS when EAS metadata exceeds its cap`() = runTest {
        val locator = EasClient.EwsMailLocator(email.subject, EwsMailFolder.Distinguished("inbox"), email.dateReceived, null)
        coEvery { folders.getFolder(email.folderId) } returns
            FolderEntity(email.folderId, owner, "inbox", "Inbox", "0", FolderType.INBOX)
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns
            EasResult.Error(AttachmentErrors.METADATA_TOO_LARGE)
        coEvery { client.listInlineAttachmentCandidates(null, locator) } returns listOf(
            EwsAttachments.FileAttachment("ews-other", "other.ics", null),
            EwsAttachments.FileAttachment("ews-verified", ics.displayName, null)
        )
        val dest = kotlin.io.path.createTempFile().toFile()
        try {
            coEvery { client.downloadEwsAttachmentToFile("ews-verified", dest) } answers {
                dest.writeText("verified payload")
                EasResult.Success(dest)
            }
            assertThat(service.downloadAttachmentToFile(ics, dest, ownerSnapshot)).isEqualTo(EasResult.Success(dest))
            assertThat(dest.readText()).isEqualTo("verified payload")
            coVerify(exactly = 1) { client.fetchInlineAttachmentMetadata("inbox", email.serverId) }
            coVerify(exactly = 0) { client.downloadAttachmentToFile(any(), any()) }
            coVerify(exactly = 0) { client.downloadEwsAttachmentToFile("ews-other", any()) }
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `an actual attachment in-memory overflow does not retry through EWS`() = runTest {
        val fresh = "5:2:3"
        coEvery { client.fetchInlineAttachmentMetadata("inbox", email.serverId) } returns metadata(invite(fresh))
        coEvery { client.downloadAttachment(fresh) } returns EasResult.Error(AttachmentErrors.TOO_LARGE_FOR_MEMORY)
        assertThat(service.fetchAttachmentBytes(ics, ownerSnapshot))
            .isEqualTo(EasResult.Error(AttachmentErrors.TOO_LARGE_FOR_MEMORY))
        coVerify(exactly = 1) { client.fetchInlineAttachmentMetadata("inbox", email.serverId) }
        coVerify(exactly = 0) { client.listInlineAttachmentCandidates(any(), any()) }
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

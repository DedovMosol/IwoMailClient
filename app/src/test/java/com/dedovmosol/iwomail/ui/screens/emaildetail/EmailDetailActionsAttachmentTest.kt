package com.dedovmosol.iwomail.ui.screens.emaildetail

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class EmailDetailActionsAttachmentTest {
    @get:Rule val temp = TemporaryFolder()

    private val mailRepo = mockk<MailRepository>()
    private val accountRepo = mockk<AccountRepository>()
    private val actions = EmailDetailActions(mailRepo, accountRepo, mockk(), mockk())
    private val email = mockk<EmailEntity>()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1)
    private val b64 = Base64.getEncoder().encodeToString(png)
    private val local = AttachmentEntity(id = 42, emailId = EMAIL_ID, fileReference = "",
        displayName = "image001.png", contentType = "image/png", estimatedSize = 5)

    @Test
    fun `ICS delegates the displayed owner to the repository and never checks the active account`() = runTest {
        val ics = local.copy(displayName = "invite.ics", contentType = "text/calendar")
        coEvery { mailRepo.fetchAttachmentBytes(ics, email) } returns EasResult.Success(byteArrayOf(42))
        assertThat((actions.fetchAttachmentBytes(ics, email) as EasResult.Success).data).isEqualTo(byteArrayOf(42))
        coVerify(exactly = 0) { accountRepo.getActiveAccountSync() }
    }

    @Test
    fun `partial MIME contributes one logo and requests only the other logo even with a huge PDF`() = runTest {
        val mime = """
            Content-Type: multipart/related; boundary="rel"

            --rel
            Content-Type: text/html

            <img src="cid:First"><img src="cid:Second">
            --rel
            Content-Type: image/png
            Content-ID: <FIRST>
            Content-Transfer-Encoding: base64

            $b64
            --rel--
        """.trimIndent()
        val pdf = local.copy(displayName = "huge.pdf", estimatedSize = 40_000_000)
        coEvery { mailRepo.loadInlineAttachmentBytes(EMAIL_ID, OWNER_ID, setOf("second"), listOf(pdf), any(), any(), any()) } coAnswers {
            arg<(String, ByteArray) -> Unit>(6)("second", png)
        }
        val images = actions.loadInlineImages(EMAIL_ID, OWNER_ID, setOf("First", "Second"), mime, listOf(pdf))
        assertThat(images).containsExactly("first", "data:image/png;base64,$b64", "second", "data:image/png;base64,$b64")
        coVerify(exactly = 0) { accountRepo.getActiveAccountSync() }
    }

    @Test
    fun `unique local filename supplies an Outlook CID alias but not from a different message`() = runTest {
        val file = temp.newFile().apply { writeBytes(png) }
        val saved = local.copy(localPath = file.path, downloaded = true)
        val alias = "image001.png@01outlook"
        val observed = actions.loadInlineImages(EMAIL_ID, OWNER_ID, setOf("IMAGE001.PNG@01Outlook"), null,
            listOf(saved, saved.copy(emailId = "other")))
        assertThat(observed).containsExactly(alias, "data:image/png;base64,$b64")
        coVerify(exactly = 0) { mailRepo.loadInlineAttachmentBytes(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ambiguous names and explicit conflicting CIDs are not used as local substitutes`() = runTest {
        val file = temp.newFile().apply { writeBytes(png) }
        val saved = local.copy(localPath = file.path, downloaded = true)
        val alias = "image001.png@01outlook"
        coEvery { mailRepo.loadInlineAttachmentBytes(EMAIL_ID, OWNER_ID, setOf(alias), any(), any(), any(), any()) } returns Unit
        for (rows in listOf(listOf(saved, saved.copy(id = 43)), listOf(saved.copy(contentId = "other@cid")),
            listOf(saved.copy(emailId = "other")))) {
            assertThat(actions.loadInlineImages(EMAIL_ID, OWNER_ID, setOf(alias), null, rows)).isEmpty()
        }
    }

    companion object {
        private const val EMAIL_ID = "email-1"
        private const val OWNER_ID = 2L
    }
}

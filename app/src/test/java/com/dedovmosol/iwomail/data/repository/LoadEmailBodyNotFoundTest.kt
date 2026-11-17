package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.EmailBodyResult
import com.dedovmosol.iwomail.eas.FolderType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROTO-08: ItemOperations Fetch Status 6 во время полной синхронизации папки (письмо ещё не пришло заново —
 * поведение Exchange 2007, проверено на сервере) не должно удалять письмо локально и прятать его от синхронизации.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoadEmailBodyNotFoundTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var service: EmailOperationsService
    private val accountRepo = mockk<AccountRepository>(relaxed = true)
    private val client = mockk<EasClient>(relaxed = true)
    private var accountId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me")
        accountId = runBlocking { db.accountDao().insert(account) }
        runBlocking {
            db.folderDao().insert(FolderEntity("${accountId}_2", accountId, "2", "Входящие", "0", FolderType.INBOX))
        }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns client
        every { client.isExchange2007() } returns true
        service = EmailOperationsService(context, db, db.folderDao(), db.emailDao(), db.attachmentDao(), accountRepo, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seed(serverId: String = "2:41"): String {
        val id = "${accountId}_$serverId"
        runBlocking {
            db.emailDao().insert(
                EmailEntity(
                    id = id, accountId = accountId, folderId = "${accountId}_2", serverId = serverId,
                    from = "boss@corp.example", to = "me@corp.example", subject = "Отчёт", body = "",
                    dateReceived = 1_000L, read = false, hasAttachments = true
                )
            )
            db.attachmentDao().insertAll(
                listOf(AttachmentEntity(emailId = id, fileReference = "2%3a41%3a0", displayName = "a.pdf", contentType = "application/pdf"))
            )
        }
        return id
    }

    @Test
    fun `status 6 keeps the email, its attachments and lets sync bring it back`() = runBlocking<Unit> {
        val id = seed()
        coEvery { client.fetchEmailBodyWithMdn(any(), any(), any()) } returns EasResult.Error("OBJECT_NOT_FOUND")
        coEvery { client.fetchEmailBodyViaEws(any(), any(), any(), any()) } returns EasResult.Error("not found")

        val result = service.loadEmailBody(id)

        assertThat(result).isEqualTo(EasResult.Error("OBJECT_NOT_FOUND"))
        assertThat(db.emailDao().getEmail(id)).isNotNull()
        assertThat(db.attachmentDao().getAttachmentsList(id)).hasSize(1)
        // Защита от «воскрешения» не включена: синхронизация, принёсшая письмо заново, его не отбросит
        assertThat(EmailSyncService.isDeletedByUser(id)).isFalse()
    }

    @Test
    fun `status 6 with body recovered through EWS stores the body`() = runBlocking<Unit> {
        val id = seed("2:42")
        coEvery { client.fetchEmailBodyWithMdn(any(), any(), any()) } returns EasResult.Error("OBJECT_NOT_FOUND")
        coEvery { client.fetchEmailBodyViaEws(any(), any(), any(), any()) } returns EasResult.Success(EmailBodyResult("<p>текст</p>"))

        val result = service.loadEmailBody(id)

        assertThat(result).isEqualTo(EasResult.Success("<p>текст</p>"))
        assertThat(db.emailDao().getEmail(id)!!.body).isEqualTo("<p>текст</p>")
    }
}

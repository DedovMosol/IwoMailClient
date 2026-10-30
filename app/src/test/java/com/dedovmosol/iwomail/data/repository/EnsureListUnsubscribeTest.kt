package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Отписка у писем, тело которых загружено раньше (до поддержки отписки): заголовки MIME запрашиваются один раз,
 * ответ хранится в настоящей Room-БД; сетевая граница ([EasClient]) подменена.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnsureListUnsubscribeTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var service: EmailOperationsService
    private val accountRepo = mockk<AccountRepository>(relaxed = true)
    private val client = mockk<EasClient>(relaxed = true)
    private var accountId = 0L

    private val signedHeaders = "DKIM-Signature: v=1; d=shop.example; h=From:List-Unsubscribe:List-Unsubscribe-Post; b=x\r\n" +
        "From: news@shop.example\r\nList-Unsubscribe: <https://shop.example/u/1>\r\n" +
        "List-Unsubscribe-Post: List-Unsubscribe=One-Click\r\nSubject: Sale"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me")
        accountId = runBlocking { db.accountDao().insert(account) }
        runBlocking {
            db.folderDao().insert(FolderEntity("${accountId}_2", accountId, "2", "Входящие", "0", FolderType.INBOX))
            db.folderDao().insert(FolderEntity("${accountId}_11", accountId, "11", "Спам", "0", FolderType.JUNK_EMAIL))
            db.folderDao().insert(FolderEntity("${accountId}_5", accountId, "5", "Отправленные", "0", FolderType.SENT_ITEMS))
        }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns client
        service = EmailOperationsService(context, db, db.folderDao(), db.emailDao(), db.attachmentDao(), accountRepo, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun email(
        folder: String = "2",
        serverId: String = "$folder:1",
        listUnsubscribe: String? = null,
        messageClass: String = "IPM.Note",
        body: String = "<p>cached</p>",
        dateReceived: Long = 1_000L
    ): String {
        val entity = EmailEntity(
            id = "${accountId}_$serverId", accountId = accountId, folderId = "${accountId}_$folder", serverId = serverId,
            from = "news@shop.example", to = "me@corp.example", subject = "Sale", body = body,
            dateReceived = dateReceived, read = true, listUnsubscribe = listUnsubscribe, messageClass = messageClass
        )
        runBlocking { db.emailDao().insert(entity) }
        return entity.id
    }

    private fun stored(id: String) = runBlocking { db.emailDao().getEmail(id)!! }

    @Test
    fun `old newsletter gets its unsubscribe from the headers, asked once`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.fetchTopLevelHeaders("2", "2:1") } returns EasResult.Success(signedHeaders)

        service.ensureListUnsubscribe(id)
        service.ensureListUnsubscribe(id)

        assertThat(stored(id).listUnsubscribe).isEqualTo("<https://shop.example/u/1>")
        assertThat(stored(id).listUnsubscribeOneClick).isTrue()
        coVerify(exactly = 1) { client.fetchTopLevelHeaders(any(), any()) }
    }

    @Test
    fun `email without the header is remembered as checked`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.fetchTopLevelHeaders(any(), any()) } returns EasResult.Success("From: a@corp.example\r\nSubject: x")

        service.ensureListUnsubscribe(id)
        service.ensureListUnsubscribe(id)

        assertThat(stored(id).listUnsubscribe).isEmpty()
        coVerify(exactly = 1) { client.fetchTopLevelHeaders(any(), any()) }
    }

    @Test
    fun `failed request is retried next time`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.fetchTopLevelHeaders(any(), any()) } returns EasResult.Error("[NET:TIMEOUT] timeout")

        service.ensureListUnsubscribe(id)

        assertThat(stored(id).listUnsubscribe).isNull()
    }

    @Test
    fun `RSS feed items are never asked for unsubscribe headers`() = runBlocking<Unit> {
        val id = email(messageClass = "IPM.Post.RSS")

        service.ensureListUnsubscribe(id)

        coVerify(exactly = 0) { client.fetchTopLevelHeaders(any(), any()) }
    }

    @Test
    fun `opening an RSS item loads its body without the MIME header request`() = runBlocking<Unit> {
        val id = email(messageClass = "IPM.Post.RSS", body = "")
        coEvery { client.fetchEmailBodyWithMdn(any(), any(), any()) } returns
            EasResult.Success(com.dedovmosol.iwomail.eas.EmailBodyResult("<p>item</p>"))

        service.loadEmailBody(id)

        // Отчёта о прочтении и отписки у записи канала нет — второй запрос (MIME) лишний
        coVerify(exactly = 1) { client.fetchEmailBodyWithMdn("2", "2:1", false) }
    }

    @Test
    fun `RSS items delivered to the Inbox do not raise new-mail notifications`() = runBlocking<Unit> {
        email(serverId = "2:10", messageClass = "IPM.Post.RSS", dateReceived = 5_000L)
        email(serverId = "2:12", messageClass = "ipm.post.rss.custom", dateReceived = 5_500L)
        email(serverId = "2:11", messageClass = "IPM.Note", dateReceived = 6_000L)
        // Не RSS — то же правило, что RssItems.isRss
        email(serverId = "2:13", messageClass = "IPM.Post.RSSX", dateReceived = 7_000L)

        val ids = db.emailDao().getNewEmailsForNotification(accountId, 2_000L).map { it.id }

        assertThat(ids).containsExactly("${accountId}_2:13", "${accountId}_2:11").inOrder()
    }

    @Test
    fun `junk, own mail, already checked and device-only drafts are not asked`() = runBlocking<Unit> {
        val ids = listOf(
            email(folder = "11"),
            email(folder = "5"),
            email(serverId = "2:7", listUnsubscribe = ""),
            email(serverId = "local_draft_1")
        )

        ids.forEach { service.ensureListUnsubscribe(it) }

        coVerify(exactly = 0) { client.fetchTopLevelHeaders(any(), any()) }
    }
}

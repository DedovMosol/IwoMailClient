package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.EasAttachment
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasEmail
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.EmailBodyResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.SyncResponse
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
 * Exchange 2007 (EAS 12.1) нумерует письма папки в порядке выдачи ("2:1", "2:2"…) и после SyncKey=0 — заново
 * (проверено на сервере, LiveServerIdProbeTest): под прежним id может оказаться другое письмо. Синхронизация
 * переписывает такие записи, тело и вложения другого письма в запись не попадают. Room настоящая, подменён только
 * [EasClient].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerIdRemapTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var sync: EmailSyncService
    private lateinit var ops: EmailOperationsService
    private val accountRepo = mockk<AccountRepository>(relaxed = true)
    private val client = mockk<EasClient>(relaxed = true)
    private var accountId = 0L
    private val inbox get() = "${accountId}_2"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(
            id = 30_000L + (System.nanoTime() % 60_000),
            email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me"
        )
        accountId = runBlocking { db.accountDao().insert(account) }
        runBlocking {
            db.folderDao().insert(FolderEntity(inbox, accountId, "2", "Входящие", "0", FolderType.INBOX, syncKey = "K1"))
        }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns client
        coEvery { client.isExchange2007() } returns true
        sync = EmailSyncService(context, db.folderDao(), db.emailDao(), db.attachmentDao(), db.accountDao(), accountRepo, db)
        ops = EmailOperationsService(context, db, db.folderDao(), db.emailDao(), db.attachmentDao(), accountRepo, sync)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    private fun server(serverId: String, subject: String, from: String, dateMs: Long, attachments: List<EasAttachment> = emptyList()) =
        EasEmail(
            serverId = serverId, from = from, to = "me@corp.example", subject = subject, dateReceived = iso(dateMs),
            read = true, importance = 1, body = "", messageClass = "IPM.Note", attachments = attachments
        )

    private fun row(serverId: String, subject: String, from: String, dateMs: Long, body: String = "") = EmailEntity(
        id = "${accountId}_$serverId", accountId = accountId, folderId = inbox, serverId = serverId,
        from = from, to = "me@corp.example", subject = subject, body = body, dateReceived = dateMs, read = true,
        hasAttachments = false
    )

    private fun stored(serverId: String) = runBlocking { db.emailDao().getEmail("${accountId}_$serverId") }

    private fun serverSends(vararg emails: EasEmail) {
        coEvery { client.sync("2", any(), any(), any(), any()) } returns EasResult.Success(SyncResponse("K2", 1, emails.toList()))
    }

    private val t0 = 1_790_000_000_000L

    // ── Сверка записи с Add ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `same item - by Message-ID when both know it, otherwise by subject, sender and time`() {
        val a = row("2:1", "Смета", "boss@corp.example", t0)
        assertThat(EmailSyncService.sameServerItem(a, a.copy())).isTrue()
        assertThat(EmailSyncService.sameServerItem(a, a.copy(dateReceived = t0 + 1_500))).isTrue()
        assertThat(EmailSyncService.sameServerItem(a, a.copy(subject = "Счёт"))).isFalse()
        assertThat(EmailSyncService.sameServerItem(a, a.copy(from = "hr@corp.example"))).isFalse()
        assertThat(EmailSyncService.sameServerItem(a, a.copy(dateReceived = t0 + 60_000))).isFalse()
        // Message-ID решает, если известен у обоих (скобки и регистр не важны)
        val withId = a.copy(internetMessageId = "<X1@corp>")
        assertThat(EmailSyncService.sameServerItem(withId, a.copy(subject = "RE: другое", internetMessageId = "x1@corp"))).isTrue()
        assertThat(EmailSyncService.sameServerItem(withId, a.copy(internetMessageId = "<X2@corp>"))).isFalse()
        // Известен у одного — сравнение по теме, отправителю и времени
        assertThat(EmailSyncService.sameServerItem(withId, a.copy())).isTrue()
        // Черновик правится на устройстве: тема или время могут разойтись, но не оба сразу
        assertThat(EmailSyncService.sameServerItem(a, a.copy(subject = "Смета v2"), draft = true)).isTrue()
        assertThat(EmailSyncService.sameServerItem(a, a.copy(subject = "Другое", dateReceived = t0 + 600_000), draft = true)).isFalse()
    }

    // ── Синхронизация ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Add for an existing id about another message rewrites the row, its attachments and drops its pending change`() = runBlocking<Unit> {
        db.emailDao().insert(row("2:1", "Смета", "boss@corp.example", t0, body = "<p>смета</p>"))
        db.attachmentDao().insertAll(listOf(AttachmentEntity(emailId = "${accountId}_2:1", fileReference = "2%3a1%3a0", displayName = "смета.pdf", contentType = "application/pdf")))
        db.pendingEmailChangeDao().markFlag("${accountId}_2:1", accountId)
        serverSends(
            server(
                "2:1", "Отчёт.xlsx", "hr@corp.example", t0 + 3_600_000,
                attachments = listOf(EasAttachment(fileReference = "2%3a1%3a0", displayName = "Отчёт.xlsx", contentType = "", estimatedSize = 10, isInline = false))
            )
        )

        sync.syncEmailsEas(accountId, inbox)

        val now = stored("2:1")!!
        assertThat(now.subject).isEqualTo("Отчёт.xlsx")
        assertThat(now.from).isEqualTo("hr@corp.example")
        assertThat(now.dateReceived).isEqualTo(t0 + 3_600_000)
        // Тело прежнего письма — не этого: загрузится при открытии
        assertThat(now.body).isEmpty()
        assertThat(db.attachmentDao().getAttachmentsList(now.id).map { it.displayName }).containsExactly("Отчёт.xlsx")
        // Флаг ждал отправки для прежнего письма: ушёл бы на чужое
        assertThat(db.pendingEmailChangeDao().get(now.id)).isNull()
    }

    @Test
    fun `ids shifted by a new mail on top - every row names its server message and bodies follow their messages`() = runBlocking<Unit> {
        // До сдвига: 2:1 = A, 2:2 = B. Сверху пришло W: теперь 2:1 = W, 2:2 = A, 2:3 = B
        db.emailDao().insert(row("2:1", "A", "a@corp.example", t0, body = "<p>тело A</p>"))
        db.emailDao().insert(row("2:2", "B", "b@corp.example", t0 - 60_000, body = "<p>тело B</p>"))
        serverSends(
            server("2:1", "W", "w@corp.example", t0 + 60_000),
            server("2:2", "A", "a@corp.example", t0),
            server("2:3", "B", "b@corp.example", t0 - 60_000)
        )

        sync.syncEmailsEas(accountId, inbox)

        assertThat(stored("2:1")!!.subject).isEqualTo("W")
        assertThat(stored("2:1")!!.body).isEmpty()
        assertThat(stored("2:2")!!.subject).isEqualTo("A")
        assertThat(stored("2:2")!!.body).isEqualTo("<p>тело A</p>")
        assertThat(stored("2:3")!!.subject).isEqualTo("B")
        assertThat(stored("2:3")!!.body).isEqualTo("<p>тело B</p>")
        assertThat(db.emailDao().getCountByFolder(inbox)).isEqualTo(3)
    }

    @Test
    fun `Add of the same message under the same id keeps the row and its body`() = runBlocking<Unit> {
        db.emailDao().insert(row("2:1", "Смета", "boss@corp.example", t0, body = "<p>смета</p>").copy(internetMessageId = "<m1@corp>"))
        db.pendingEmailChangeDao().markFlag("${accountId}_2:1", accountId)
        serverSends(server("2:1", "Смета", "boss@corp.example", t0))

        sync.syncEmailsEas(accountId, inbox)

        assertThat(stored("2:1")!!.body).isEqualTo("<p>смета</p>")
        assertThat(stored("2:1")!!.internetMessageId).isEqualTo("<m1@corp>")
        assertThat(db.pendingEmailChangeDao().get("${accountId}_2:1")).isNotNull()
    }

    @Test
    fun `a folder marked stale gets one full resync`() = runBlocking<Unit> {
        coEvery { client.sync("2", "0", any(), any(), any()) } returns EasResult.Success(SyncResponse("S1", 1, emptyList()))
        coEvery { client.sync("2", "S1", any(), any(), any()) } returns EasResult.Success(SyncResponse("S2", 1, emptyList()))
        coEvery { client.sync("2", "K1", any(), any(), any()) } returns EasResult.Success(SyncResponse("K2", 1, emptyList()))

        EmailSyncService.markServerIdsStale(inbox)
        sync.syncEmailsEas(accountId, inbox)

        coVerify(exactly = 1) { client.sync("2", "0", any(), any(), any()) }
        coVerify(exactly = 0) { client.sync("2", "K1", any(), any(), any()) }
        assertThat(EmailSyncService.takeStaleResync(inbox)).isFalse()
    }

    // ── Тело письма ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `body of another message under the id is not saved and the folder is marked for a full resync`() = runBlocking<Unit> {
        db.emailDao().insert(row("2:5", "Смета", "boss@corp.example", t0))
        coEvery { client.fetchEmailBodyWithMdn(any(), "2:5", any()) } returns EasResult.Success(
            EmailBodyResult("<p>чужое</p>", itemSubject = "Отчёт.xlsx", itemDateReceived = iso(t0 + 3_600_000))
        )

        val result = ops.loadEmailBody("${accountId}_2:5")

        assertThat(result).isEqualTo(EasResult.Error(RepositoryErrors.ITEM_MISMATCH))
        assertThat(stored("2:5")!!.body).isEmpty()
        assertThat(EmailSyncService.takeStaleResync(inbox)).isTrue()
    }

    @Test
    fun `body of the same message is saved (subject case, empty subject, server without properties)`() = runBlocking<Unit> {
        db.emailDao().insert(row("2:5", "Смета", "boss@corp.example", t0))
        db.emailDao().insert(row("2:6", "(No subject)", "boss@corp.example", t0))
        db.emailDao().insert(row("2:7", "Без свойств", "boss@corp.example", t0))
        coEvery { client.fetchEmailBodyWithMdn(any(), "2:5", any()) } returns
            EasResult.Success(EmailBodyResult("<p>5</p>", itemSubject = " СМЕТА ", itemDateReceived = iso(t0)))
        coEvery { client.fetchEmailBodyWithMdn(any(), "2:6", any()) } returns
            EasResult.Success(EmailBodyResult("<p>6</p>", itemSubject = "", itemDateReceived = iso(t0)))
        coEvery { client.fetchEmailBodyWithMdn(any(), "2:7", any()) } returns EasResult.Success(EmailBodyResult("<p>7</p>"))

        for (n in 5..7) {
            assertThat(ops.loadEmailBody("${accountId}_2:$n")).isEqualTo(EasResult.Success("<p>$n</p>"))
            assertThat(stored("2:$n")!!.body).isEqualTo("<p>$n</p>")
        }
        assertThat(EmailSyncService.takeStaleResync(inbox)).isFalse()
    }

    @Test
    fun `row rewritten by a sync while the body was loading does not get the old body`() = runBlocking<Unit> {
        db.emailDao().insert(row("2:5", "Смета", "boss@corp.example", t0))
        coEvery { client.fetchEmailBodyWithMdn(any(), "2:5", any()) } coAnswers {
            // Пока шёл запрос, синхронизация отдала id другому письму
            db.emailDao().insert(row("2:5", "Отчёт.xlsx", "hr@corp.example", t0 + 3_600_000))
            EasResult.Success(EmailBodyResult("<p>смета</p>", itemSubject = "Смета", itemDateReceived = iso(t0)))
        }

        val result = ops.loadEmailBody("${accountId}_2:5")

        assertThat(result).isEqualTo(EasResult.Error(RepositoryErrors.ITEM_MISMATCH))
        assertThat(stored("2:5")!!.body).isEmpty()
        // Запись уже исправлена синхронизацией — лишняя полная синхронизация не нужна
        assertThat(EmailSyncService.takeStaleResync(inbox)).isFalse()
    }

    @Test
    fun `item identity check`() {
        assertThat(bodyBelongsTo("Смета", t0, "смета", t0 + 1_000)).isTrue()
        assertThat(bodyBelongsTo("Смета", t0, null, 0L)).isTrue()
        assertThat(bodyBelongsTo("(No subject)", t0, "", t0)).isTrue()
        assertThat(bodyBelongsTo("Смета", t0, "Отчёт", t0)).isFalse()
        assertThat(bodyBelongsTo("Смета", t0, "Смета", t0 + 3_600_000)).isFalse()
        // Дата записи неизвестна (1 — заглушка старых версий) — только тема
        assertThat(bodyBelongsTo("Смета", 1L, "Смета", t0)).isTrue()
    }
}

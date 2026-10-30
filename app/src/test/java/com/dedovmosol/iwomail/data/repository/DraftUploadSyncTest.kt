package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.eas.DraftIds
import com.dedovmosol.iwomail.eas.EasAttachment
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.SyncResponse
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Загрузка черновиков с устройства при синхронизации ([EmailSyncService.syncDraftsFull]) на настоящей Room-БД;
 * подменена только сетевая граница ([EasClient]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftUploadSyncTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var service: EmailSyncService
    private val accountRepo = mockk<AccountRepository>(relaxed = true)
    private val easClient = mockk<EasClient>(relaxed = true)
    private val deletedServerDrafts = mutableListOf<Pair<String, String?>>()
    private var deleteSucceeds = true
    private val failingDeletes = mutableSetOf<String>()
    private var accountId = 0L
    private val folderId get() = "${accountId}_5"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me")
        accountId = runBlocking { db.accountDao().insert(account) }
        runBlocking {
            db.folderDao().insert(FolderEntity(folderId, accountId, "5", "Черновики", "0", FolderType.DRAFTS, syncKey = "K1"))
        }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns easClient
        // Сервер ничего нового не прислал
        coEvery { easClient.sync(any(), any(), any(), any(), any()) } returns EasResult.Success(SyncResponse("K2", 1, emptyList()))
        // EWS по умолчанию не отвечает: скрытые копии и флажки прежних версий не прочитать
        coEvery { easClient.findDraftDetails(any(), any()) } returns null
        service = EmailSyncService(
            context, db.folderDao(), db.emailDao(), db.attachmentDao(), db.accountDao(), accountRepo, db,
            deleteServerDraft = { _, serverId, exclude ->
                deletedServerDrafts += serverId to exclude
                deleteSucceeds && serverId !in failingDeletes
            }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun draft(serverId: String, subject: String = "Отчёт", replaces: String? = null, bcc: String? = "boss@corp.example") =
        EmailEntity(
            id = "${accountId}_$serverId", accountId = accountId, folderId = folderId, serverId = serverId,
            from = "me@corp.example", to = "a@corp.example", subject = subject, body = "<p>Текст</p>",
            dateReceived = 1_000L, read = true, importance = 2, bcc = bcc, replacesServerId = replaces
        )

    private fun drafts() = runBlocking { db.emailDao().getEmailsByFolderList(folderId) }

    private fun details(bcc: String? = null, receipts: com.dedovmosol.iwomail.eas.ReceiptRequests? = null) =
        com.dedovmosol.iwomail.eas.EwsDraftXml.DraftDetails(body = "", bcc = bcc, lastModified = null, receipts = receipts)

    @Test
    fun `device draft is uploaded with Bcc and importance and replaces the old server version`() = runBlocking<Unit> {
        val local = draft(DraftIds.newDeviceOnly(), replaces = "5:9")
        db.emailDao().insert(local)
        val bcc = slot<String>()
        coEvery { easClient.createDraft(any(), any(), capture(bcc), any(), any(), any(), 2) } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(bcc.captured).isEqualTo("boss@corp.example")
        val uploaded = drafts().single()
        assertThat(uploaded.serverId).isEqualTo("AAMkNEW=")
        // Прежняя версия удалена по своему id (новая не задета), отметка снята.
        assertThat(deletedServerDrafts).containsExactly("5:9" to "AAMkNEW=")
        assertThat(uploaded.replacesServerId).isNull()
    }

    @Test
    fun `unknown Bcc of an offline edit is read from the replaced version before upload`() = runBlocking<Unit> {
        db.emailDao().insert(draft(DraftIds.newDeviceOnly(), replaces = "5:9", bcc = null))
        coEvery { easClient.findDraftDetails("5", "5:9") } returns details(bcc = "boss@corp.example")
        val bcc = slot<String>()
        coEvery { easClient.createDraft(any(), any(), capture(bcc), any(), any(), any(), any()) } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(bcc.captured).isEqualTo("boss@corp.example")
        assertThat(deletedServerDrafts).containsExactly("5:9" to "AAMkNEW=")
        assertThat(drafts().single().bcc).isEqualTo("boss@corp.example")
    }

    @Test
    fun `replaced version whose Bcc cannot be read is still replaced, not left as a second draft`() = runBlocking<Unit> {
        db.emailDao().insert(draft(DraftIds.newDeviceOnly(), replaces = "5:9", bcc = null))
        coEvery { easClient.findDraftDetails(any(), any()) } returns null
        val bcc = slot<String>()
        coEvery { easClient.createDraft(any(), any(), capture(bcc), any(), any(), any(), any()) } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(bcc.captured).isEmpty()
        assertThat(deletedServerDrafts).containsExactly("5:9" to "AAMkNEW=")
        assertThat(drafts().single().replacesServerId).isNull()
    }

    @Test
    fun `unknown receipt requests of an offline edit come from the replaced version`() = runBlocking<Unit> {
        // Правка черновика из Outlook без связи: флажки «запросить отчёт» EAS не передаёт — они на сервере
        db.emailDao().insert(draft(DraftIds.newDeviceOnly(), replaces = "5:9"))
        val serverReceipts = com.dedovmosol.iwomail.eas.ReceiptRequests(read = true, delivery = false)
        coEvery { easClient.findDraftDetails("5", "5:9") } returns details(bcc = "", receipts = serverReceipts)
        val receipts = slot<com.dedovmosol.iwomail.eas.ReceiptRequests>()
        coEvery {
            easClient.createDraft(any(), any(), any(), any(), any(), any(), any(), capture(receipts))
        } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(receipts.captured).isEqualTo(serverReceipts)
        val uploaded = drafts().single()
        assertThat(uploaded.requestReadReceipt).isTrue()
        assertThat(uploaded.requestDeliveryReceipt).isFalse()
        // Скрытые копии записи известны — EWS спрашивали только ради флажков, один раз
        coVerify(exactly = 1) { easClient.findDraftDetails(any(), any()) }
    }

    @Test
    fun `known receipts and Bcc need no EWS request`() = runBlocking<Unit> {
        db.emailDao().insert(draft(DraftIds.newDeviceOnly(), replaces = "5:9").copy(requestReadReceipt = false, requestDeliveryReceipt = true))
        val receipts = slot<com.dedovmosol.iwomail.eas.ReceiptRequests>()
        coEvery {
            easClient.createDraft(any(), any(), any(), any(), any(), any(), any(), capture(receipts))
        } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(receipts.captured).isEqualTo(com.dedovmosol.iwomail.eas.ReceiptRequests(read = false, delivery = true))
        coVerify(exactly = 0) { easClient.findDraftDetails(any(), any()) }
    }

    @Test
    fun `every replaced version is deleted, the ones that failed stay marked`() = runBlocking<Unit> {
        db.emailDao().insert(draft(DraftIds.newDeviceOnly(), replaces = "5:9\n5:8"))
        failingDeletes += "5:8"
        coEvery { easClient.createDraft(any(), any(), any(), any(), any(), any(), any()) } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(deletedServerDrafts.map { it.first }).containsExactly("5:9", "5:8")
        assertThat(drafts().single().replacesServerId).isEqualTo("5:8")
        assertThat(db.emailDao().getReplacedServerIds(folderId)).containsExactly("5:8")
    }

    @Test
    fun `device-only mode takes the Bcc it can read from the replaced version, then deletes it`() = runBlocking<Unit> {
        coEvery { accountRepo.getAccount(accountId) } returns AccountEntity(
            id = accountId, email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me",
            draftMode = com.dedovmosol.iwomail.data.database.DraftMode.LOCAL.name
        )
        val local = draft(DraftIds.newDeviceOnly(), replaces = "5:9\n5:8", bcc = null)
        db.emailDao().insert(local)
        coEvery { easClient.findDraftDetails("5", "5:9") } returns details(bcc = "boss@corp.example")
        coEvery { easClient.findDraftDetails("5", "5:8") } returns null // не прочитать — не ждём

        service.syncDraftsFull(accountId, folderId)

        assertThat(deletedServerDrafts).containsExactly("5:9" to null, "5:8" to null)
        val record = db.emailDao().getEmail(local.id)!!
        assertThat(record.bcc).isEqualTo("boss@corp.example")
        assertThat(record.replacesServerId).isNull()
    }

    @Test
    fun `nothing is uploaded or deleted while the server does not answer`() = runBlocking<Unit> {
        coEvery { easClient.sync(any(), any(), any(), any(), any()) } returns EasResult.Error("[NET:TIMEOUT] timeout")
        db.emailDao().insert(draft(DraftIds.newDeviceOnly(), replaces = "5:9"))

        service.syncDraftsFull(accountId, folderId)

        coVerify(exactly = 0) { easClient.createDraft(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(deletedServerDrafts).isEmpty()
    }

    @Test
    fun `old version that could not be deleted stays marked and hidden from sync`() = runBlocking<Unit> {
        deleteSucceeds = false
        db.emailDao().insert(draft(DraftIds.newDeviceOnly(), replaces = "5:9"))
        coEvery { easClient.createDraft(any(), any(), any(), any(), any(), any(), any()) } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(drafts().single().replacesServerId).isEqualTo("5:9")
        assertThat(db.emailDao().getReplacedServerIds(folderId)).containsExactly("5:9")
    }

    @Test
    fun `device-only draft with the subject of a server draft is not dropped as a duplicate`() = runBlocking<Unit> {
        // Правка без связи: та же тема, что у серверного черновика, — не дубликат.
        db.emailDao().insert(draft("5:1"))
        val local = draft(DraftIds.newDeviceOnly())
        db.emailDao().insert(local.copy(dateReceived = 1_010L))
        coEvery { easClient.createDraft(any(), any(), any(), any(), any(), any(), any()) } returns
            EasResult.Success(DraftIds.newDeviceOnly()) // сервер не принял — остаётся на устройстве

        service.syncDraftsFull(accountId, folderId)

        assertThat(drafts().map { it.id }).contains(local.id)
    }

    @Test
    fun `local copy of a draft the server confirmed without an id is dropped when the server copy arrives`() = runBlocking<Unit> {
        db.emailDao().insert(draft("5:1", subject = ""))
        val unconfirmed = draft(DraftIds.newUnconfirmed(), subject = "")
        db.emailDao().insert(unconfirmed)

        service.syncDraftsFull(accountId, folderId)

        // Без темы: локально "", с сервера могло прийти "(No subject)" — та же тема.
        assertThat(drafts().map { it.id }).doesNotContain(unconfirmed.id)
        coVerify(exactly = 0) { easClient.createDraft(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `attachment carried by reference is downloaded before upload, upload waits without connection`() = runBlocking<Unit> {
        val local = draft(DraftIds.newDeviceOnly(), replaces = "5:9")
        db.emailDao().insert(local)
        db.attachmentDao().insertAll(listOf(
            AttachmentEntity(emailId = local.id, fileReference = "5%3a9%3a0", displayName = "report.pdf", contentType = "application/pdf")
        ))
        coEvery { easClient.downloadDraftAttachment("5%3a9%3a0") } returns EasResult.Error("[NET:TIMEOUT] timeout")
        coEvery { easClient.fetchAttachmentMetadata(any(), any()) } returns EasResult.Error("[NET:TIMEOUT] timeout")

        service.syncDraftsFull(accountId, folderId)
        // Нет связи — не загружаем без вложения, ничего не теряем.
        coVerify(exactly = 0) { easClient.createDraft(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(db.attachmentDao().getAttachmentsList(local.id)).hasSize(1)

        coEvery { easClient.downloadDraftAttachment("5%3a9%3a0") } returns EasResult.Success(byteArrayOf(1, 2, 3))
        val parts = slot<List<DraftAttachmentData>>()
        coEvery { easClient.createDraft(any(), any(), any(), any(), any(), capture(parts), any()) } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(parts.captured.map { it.name to it.data.size }).containsExactly("report.pdf" to 3)
        assertThat(db.attachmentDao().getAttachmentsList("${accountId}_AAMkNEW=").single().localPath).isNotNull()
    }

    @Test
    fun `stale reference is refreshed from the replaced draft, a deleted source drops only that attachment`() = runBlocking<Unit> {
        val local = draft(DraftIds.newDeviceOnly(), replaces = "5:9")
        db.emailDao().insert(local)
        db.attachmentDao().insertAll(listOf(
            AttachmentEntity(emailId = local.id, fileReference = "5%3a9%3a0", displayName = "report.pdf", contentType = "application/pdf")
        ))
        coEvery { easClient.downloadDraftAttachment("5%3a9%3a0") } returns EasResult.Error("Status=6")
        coEvery { easClient.fetchAttachmentMetadata("5", "5:9") } returns EasResult.Success(listOf(
            EasAttachment(fileReference = "5%3a12%3a0", displayName = "report.pdf", contentType = "application/pdf")
        ))
        coEvery { easClient.downloadDraftAttachment("5%3a12%3a0") } returns EasResult.Success(byteArrayOf(9))
        val parts = slot<List<DraftAttachmentData>>()
        coEvery { easClient.createDraft(any(), any(), any(), any(), any(), capture(parts), any()) } returns EasResult.Success("AAMkNEW=")

        service.syncDraftsFull(accountId, folderId)

        assertThat(parts.captured.single().data).isEqualTo(byteArrayOf(9))

        // Заменяемого черновика на сервере уже нет (удалён в Outlook) — вложение взять неоткуда.
        val other = draft(DraftIds.newDeviceOnly(), subject = "Другой", replaces = "5:10")
        db.emailDao().insert(other)
        db.attachmentDao().insertAll(listOf(
            AttachmentEntity(emailId = other.id, fileReference = "5%3a10%3a0", displayName = "gone.pdf", contentType = "application/pdf")
        ))
        coEvery { easClient.downloadDraftAttachment("5%3a10%3a0") } returns EasResult.Error("Status=6")
        coEvery { easClient.fetchAttachmentMetadata("5", "5:10") } returns EasResult.Error("ItemOperations Fetch status=6")

        service.syncDraftsFull(accountId, folderId)

        assertThat(parts.captured).isEmpty()
    }
}

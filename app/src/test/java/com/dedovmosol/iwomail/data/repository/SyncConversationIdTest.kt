package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasEmail
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.SyncResponse
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ConversationId из Sync попадает в письма на настоящей Room-БД — и обычной синхронизацией папки, и полной
 * синхронизацией «Отправленных» (ответы в цепочке). Письмо Exchange 2007 — без переписки. Подменён только [EasClient].
 * Полная синхронизация «Отправленных» идёт через шлюз папки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncConversationIdTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var service: EmailSyncService
    private val accountRepo = mockk<AccountRepository>(relaxed = true)
    private val easClient = mockk<EasClient>(relaxed = true)
    private var accountId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(
            id = 10_000L + (System.nanoTime() % 90_000),
            email = "me@corp.example",
            displayName = "Me",
            serverUrl = "mail.corp.example",
            username = "me"
        )
        accountId = runBlocking { db.accountDao().insert(account) }
        runBlocking {
            db.folderDao().insert(FolderEntity("${accountId}_2", accountId, "2", "Входящие", "0", FolderType.INBOX, syncKey = "K1"))
            db.folderDao().insert(FolderEntity("${accountId}_5", accountId, "5", "Отправленные", "0", FolderType.SENT_ITEMS, syncKey = "K1"))
        }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns easClient
        service = EmailSyncService(
            context, db.folderDao(), db.emailDao(), db.attachmentDao(), db.accountDao(), accountRepo, db,
            deleteServerDraft = { _, _, _ -> true }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun mail(serverId: String, subject: String, conversationId: String?) = EasEmail(
        serverId = serverId, from = "Boss <boss@corp.example>", to = "me@corp.example", subject = subject,
        dateReceived = "2026-10-01T10:00:00.000Z", read = false, importance = 1, body = "Текст", messageClass = "IPM.Note",
        conversationId = conversationId
    )

    private fun serverSends(collectionId: String, vararg emails: EasEmail) {
        coEvery { easClient.sync(collectionId, any(), any(), any(), any()) } returns
            EasResult.Success(SyncResponse("K2", 1, emails.toList()))
    }

    @Test
    fun `folder sync keeps the conversation of each email`() = runBlocking<Unit> {
        serverSends("2", mail("2:1", "RE: Смета", "AAQkADAwATM0MDAAMS1iNWM="), mail("2:2", "Смета", null))

        val syncResult = service.syncEmailsEas(accountId, "${accountId}_2")
        assertThat(syncResult).isInstanceOf(EasResult.Success::class.java)

        val emails = db.emailDao().getEmailsByFolderList("${accountId}_2").associateBy { it.serverId }
        assertThat(emails.keys).containsAtLeast("2:1", "2:2")
        assertThat(emails.getValue("2:1").conversationId).isEqualTo("AAQkADAwATM0MDAAMS1iNWM=")
        assertThat(emails.getValue("2:2").conversationId).isNull()
    }

    @Test
    fun `full Sent sync keeps the conversation of replies`() = runBlocking<Unit> {
        serverSends("5", mail("5:1", "RE: Смета", "AAQkADAwATM0MDAAMS1iNWM="))

        val sentResult = service.syncEmailsEas(accountId, "${accountId}_5", forceFullSync = true)
        assertThat(sentResult).isInstanceOf(EasResult.Success::class.java)

        val sent = db.emailDao().getEmailsByFolderList("${accountId}_5").single()
        assertThat(sent.conversationId).isEqualTo("AAQkADAwATM0MDAAMS1iNWM=")
    }

    /**
     * Полная синхронизация «Отправленных» сбрасывает состояние на сервере (Sync с ключом 0). Пока шлюз папки занят
     * (звезда или «прочитано» отправляются со старым ключом), она не начинается: иначе сервер отверг бы изменение, а
     * ключ, сохранённый одним из запросов, разошёлся бы с сервером.
     */
    @Test
    fun `full Sent sync waits for the folder gate`() = runBlocking<Unit> {
        serverSends("5", mail("5:1", "Смета", null))
        val folderId = "${accountId}_5"
        val gateHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val change = launch(Dispatchers.Default) {
            FolderSyncGate.withFolder(folderId) {
                gateHeld.complete(Unit)
                release.await()
            }
        }
        gateHeld.await()

        val sync = async(Dispatchers.Default) { service.syncEmailsEas(accountId, folderId, forceFullSync = true) }
        delay(300)
        coVerify(exactly = 0) { easClient.sync("5", any(), any(), any(), any()) }

        release.complete(Unit)
        change.join()
        assertThat(sync.await()).isInstanceOf(EasResult.Success::class.java)
        coVerify(exactly = 1) { easClient.sync("5", "0", any(), any(), any()) }
        assertThat(db.folderDao().getFolder(folderId)!!.syncKey).isEqualTo("K2")
    }
}

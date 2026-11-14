package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.flag
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasEmail
import com.dedovmosol.iwomail.eas.EasEmailChange
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FlagWrite
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.MailFlag
import com.dedovmosol.iwomail.eas.SyncResponse
import com.dedovmosol.iwomail.sync.PendingMailChangesWorker
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Звезда и «прочитано» на настоящей Room-БД (Exchange 2007–2013): отправка на сервер, откат только при отказе сервера,
 * правки без связи — в очереди и отправляются позже, синхронизация их не перетирает; частые нажатия; окончательное
 * удаление получает изменения папки обычной синхронизацией. Подменена только сетевая граница ([EasClient]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineMailChangesTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var sync: EmailSyncService
    private lateinit var ops: EmailOperationsService
    private val accountRepo = mockk<AccountRepository>(relaxed = true)
    private val client = mockk<EasClient>(relaxed = true)
    private val tracker = FlagWriteTracker()
    private var accountId = 0L
    private val inbox get() = "${accountId}_2"
    private val trash get() = "${accountId}_3"
    private val on = MailFlag(MailFlag.STATUS_ACTIVE)
    private val offline = EasResult.Error("[NET:TIMEOUT] SocketTimeoutException: timeout")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(
            id = 20_000L + (System.nanoTime() % 80_000),
            email = "me@corp.example",
            displayName = "Me",
            serverUrl = "mail.corp.example",
            username = "me"
        )
        accountId = runBlocking { db.accountDao().insert(account) }
        runBlocking {
            db.folderDao().insert(FolderEntity(inbox, accountId, "2", "Входящие", "0", FolderType.INBOX, syncKey = "K1"))
            db.folderDao().insert(FolderEntity(trash, accountId, "3", "Удалённые", "0", FolderType.DELETED_ITEMS, syncKey = "T1"))
        }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns client
        coEvery { client.isExchange2007() } returns false
        sync = EmailSyncService(
            context, db.folderDao(), db.emailDao(), db.attachmentDao(), db.accountDao(), accountRepo, db,
            deleteServerDraft = { _, _, _ -> true }, flagWrites = tracker
        )
        ops = EmailOperationsService(context, db, db.folderDao(), db.emailDao(), db.attachmentDao(), accountRepo, sync, tracker)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun email(folder: String = inbox, serverId: String = "2:1", read: Boolean = false): String {
        val entity = EmailEntity(
            id = "${accountId}_$serverId", accountId = accountId, folderId = folder, serverId = serverId,
            from = "boss@corp.example", to = "me@corp.example", subject = "Смета", body = "<p>текст</p>",
            dateReceived = 1_000L, read = read
        )
        runBlocking { db.emailDao().insert(entity) }
        return entity.id
    }

    private fun stored(id: String) = runBlocking { db.emailDao().getEmail(id)!! }
    private fun key(folderId: String) = runBlocking { db.folderDao().getFolder(folderId)!!.syncKey }
    private fun pending(id: String) = runBlocking { db.pendingEmailChangeDao().get(id) }
    private fun flushQueue() = runBlocking {
        PendingMailChanges(db.pendingEmailChangeDao(), ops::retryPendingFlag, ops::retryPendingRead, ops::retryPendingMove).flush()
    }

    @Test
    fun `offline trash stays in trash and is moved when the connection is back`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.moveItems(any(), any()) } returns offline

        val result = ops.moveToTrash(listOf(id)) { _, _ -> EasResult.Error("no-draft") }

        assertThat(result).isEqualTo(EasResult.Success(1))
        assertThat(stored(id).folderId).isEqualTo(trash)
        assertThat(pending(id)!!.move).isTrue()

        coEvery { client.moveItems(any(), "3") } returns EasResult.Success(mapOf("2:1" to "3:9"))
        assertThat(flushQueue()).isTrue()
        assertThat(pending(id)).isNull()
        assertThat(runBlocking { db.emailDao().getEmail("${accountId}_3:9") }?.folderId).isEqualTo(trash)
    }

    @Test
    fun `server rejection of move puts the email back in the source folder`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.moveItems(any(), any()) } returns EasResult.Error("MOVEITEMS_ALL_FAILED:status=2,failed=1,locked=0")

        val result = ops.moveToTrash(listOf(id)) { _, _ -> EasResult.Error("no-draft") }

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(stored(id).folderId).isEqualTo(inbox)
        assertThat(pending(id)?.move == true).isFalse()
    }

    // ── Звезда ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `star is sent and the new sync key is saved`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.setFlag("2", "2:1", "K1", on) } returns EasResult.Success(FlagWrite("K2", on))

        val result = ops.toggleFlag(id)

        assertThat(result).isEqualTo(EasResult.Success(true))
        assertThat(stored(id).flagged).isTrue()
        assertThat(key(inbox)).isEqualTo("K2")
        assertThat(pending(id)).isNull()
    }

    @Test
    fun `server rejection rolls the star back, keeps the new key and says so`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.setFlag(any(), any(), any(), any()) } returns EasResult.Success(FlagWrite("K2", applied = null, changeStatus = 6))

        val result = ops.toggleFlag(id)

        assertThat(result).isEqualTo(EasResult.Error(RepositoryErrors.FLAG_REJECTED))
        assertThat(stored(id).flagged).isFalse()
        // Ключ сервер выдал и при отказе — без него следующий Sync получил бы Status 3
        assertThat(key(inbox)).isEqualTo("K2")
        assertThat(pending(id)).isNull()
    }

    @Test
    fun `server-adjusted flag (today's dates) is what the database keeps`() = runBlocking<Unit> {
        val id = email()
        val dated = MailFlag.due(5_000L, 5_000L)
        coEvery { client.setFlag(any(), any(), any(), any()) } returns EasResult.Success(FlagWrite("K2", dated))

        ops.toggleFlag(id)

        assertThat(stored(id).flag).isEqualTo(dated)
    }

    @Test
    fun `offline star stays, is queued and is sent when the connection is back`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.setFlag(any(), any(), any(), any()) } returns offline

        val result = ops.toggleFlag(id)

        // Не ошибка: правка сохранена и уйдёт позже, звезда не гаснет
        assertThat(result).isEqualTo(EasResult.Success(true))
        assertThat(stored(id).flagged).isTrue()
        assertThat(pending(id)!!.flag).isTrue()
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(PendingMailChangesWorker.WORK_NAME).get()
        assertThat(work).isNotEmpty()

        coEvery { client.setFlag("2", "2:1", "K1", on) } returns EasResult.Success(FlagWrite("K2", on))
        assertThat(flushQueue()).isTrue()

        assertThat(pending(id)).isNull()
        assertThat(stored(id).flagged).isTrue()
        assertThat(key(inbox)).isEqualTo("K2")
    }

    @Test
    fun `expired sync key or a server hiccup does not undo the star`() = runBlocking<Unit> {
        for (message in listOf("Exception: setFlag failed: Collection Status=3", "HTTP 503: Service Unavailable")) {
            val id = email(serverId = "2:${message.length}")
            coEvery { client.setFlag(any(), any(), any(), any()) } returns EasResult.Error(message)

            assertThat(ops.toggleFlag(id)).isEqualTo(EasResult.Success(true))

            assertThat(stored(id).flagged).isTrue()
            assertThat(pending(id)!!.flag).isTrue()
        }
    }

    @Test
    fun `fast taps send only the final state and end where the user left the star`() = runBlocking<Unit> {
        val id = email()
        val sent = mutableListOf<MailFlag>()
        val firstAnswer = CompletableDeferred<Unit>()
        coEvery { client.setFlag(any(), any(), any(), any()) } coAnswers {
            val flag = arg<MailFlag>(3)
            sent += flag
            if (sent.size == 1) firstAnswer.await()
            EasResult.Success(FlagWrite("K${sent.size + 1}", flag))
        }

        val first = async { ops.toggleFlag(id) }
        withTimeout(5_000) { while (sent.isEmpty()) delay(5) }
        val second = async { ops.toggleFlag(id) }
        // Второе нажатие уже сняло звезду в БД, пока первый запрос ещё в пути
        withTimeout(5_000) { while (stored(id).flagged) delay(5) }
        firstAnswer.complete(Unit)
        first.await()
        second.await()

        assertThat(sent).containsExactly(on, MailFlag.NONE).inOrder()
        assertThat(stored(id).flagged).isFalse()
        assertThat(key(inbox)).isEqualTo("K3")
    }

    @Test
    fun `sync answer does not overwrite a star that waits for the queue`() = runBlocking<Unit> {
        val id = email()
        coEvery { client.setFlag(any(), any(), any(), any()) } returns offline
        ops.toggleFlag(id)
        coEvery { client.sync("2", any(), any(), any(), any()) } returns EasResult.Success(
            SyncResponse("K5", 1, emptyList(), changedEmails = listOf(EasEmailChange("2:1", flag = MailFlag.NONE)))
        )

        sync.syncEmailsEas(accountId, inbox)

        assertThat(stored(id).flagged).isTrue()
        assertThat(key(inbox)).isEqualTo("K5")
    }

    // ── Прочитано ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `offline read stays read, sync does not make it unread, and it is sent later`() = runBlocking<Unit> {
        val id = email(read = false)
        coEvery { client.markAsRead(any(), any(), any(), any(), any()) } returns offline

        assertThat(ops.markAsRead(id, true)).isEqualTo(EasResult.Success(true))
        assertThat(stored(id).read).isTrue()
        assertThat(pending(id)!!.read).isTrue()

        coEvery { client.sync("2", any(), any(), any(), any()) } returns EasResult.Success(
            SyncResponse("K5", 1, emptyList(), changedEmails = listOf(EasEmailChange("2:1", read = false)))
        )
        sync.syncEmailsEas(accountId, inbox)
        assertThat(stored(id).read).isTrue()

        coEvery { client.markAsRead("2", "2:1", "K5", true, any()) } returns EasResult.Success("K6")
        assertThat(flushQueue()).isTrue()
        assertThat(pending(id)).isNull()
        assertThat(key(inbox)).isEqualTo("K6")
    }

    @Test
    fun `server rejection of read rolls it back`() = runBlocking<Unit> {
        val id = email(read = false)
        coEvery { client.markAsRead(any(), any(), any(), any(), any()) } returns
            EasResult.Error("Exception: markAsRead failed: Change Status=5")

        assertThat(ops.markAsRead(id, true)).isInstanceOf(EasResult.Error::class.java)

        assertThat(stored(id).read).isFalse()
        assertThat(pending(id)).isNull()
    }

    @Test
    fun `server changes still apply to emails without local edits`() = runBlocking<Unit> {
        val id = email(read = false)
        coEvery { client.sync("2", any(), any(), any(), any()) } returns EasResult.Success(
            SyncResponse("K2", 1, emptyList(), changedEmails = listOf(EasEmailChange("2:1", read = true, flag = on)))
        )

        sync.syncEmailsEas(accountId, inbox)

        assertThat(stored(id).read).isTrue()
        assertThat(stored(id).flagged).isTrue()
    }

    // ── Очередь ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `queue entry of a deleted email is dropped, failures stay for the next attempt`() = runBlocking<Unit> {
        val gone = "${accountId}_2:404"
        db.pendingEmailChangeDao().markFlag(gone, accountId)
        db.pendingEmailChangeDao().markRead(gone, accountId)
        val id = email()
        coEvery { client.setFlag(any(), any(), any(), any()) } returns offline
        ops.toggleFlag(id)

        assertThat(flushQueue()).isFalse()

        assertThat(pending(gone)).isNull()
        assertThat(pending(id)!!.flag).isTrue()
    }

    @Test
    fun `queued star the server rejects after a restart is rolled back and leaves the queue`() = runBlocking<Unit> {
        val id = email()
        // Состояние после перезапуска процесса: звезда в БД и в очереди, записи трекера нет
        db.emailDao().updateFlag(id, true, false, null, null, null)
        db.pendingEmailChangeDao().markFlag(id, accountId)
        coEvery { client.setFlag(any(), any(), any(), any()) } returns EasResult.Success(FlagWrite("K2", applied = null, changeStatus = 6))

        assertThat(flushQueue()).isTrue()

        assertThat(stored(id).flagged).isFalse()
        assertThat(pending(id)).isNull()
        assertThat(key(inbox)).isEqualTo("K2")
    }

    // ── Перемещение ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `move pairs each email with itself when one of the ids is already gone`() = runBlocking<Unit> {
        val a = email(serverId = "2:1")
        val b = email(serverId = "2:2")
        coEvery { client.sync("2", any(), any(), any(), any()) } returns EasResult.Success(SyncResponse("K2", 1, emptyList()))
        coEvery { client.moveItems(any(), "3") } returns EasResult.Success(mapOf("2:1" to "3:11", "2:2" to "3:12"))

        val result = ops.moveEmails(listOf(a, "${accountId}_2:404", b), trash)

        assertThat(result).isEqualTo(EasResult.Success(2))
        assertThat(runBlocking { db.emailDao().getEmail("${accountId}_3:11") }!!.subject).isEqualTo("Смета")
        assertThat(runBlocking { db.emailDao().getEmail("${accountId}_3:12") }).isNotNull()
        assertThat(runBlocking { db.emailDao().getEmail(a) }).isNull()
    }

    // ── Окончательное удаление ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `permanent delete of a large trash keeps order across the SQL chunks`() = runBlocking<Unit> {
        val ids = (1..1_200).map { i ->
            EmailEntity(
                id = "${accountId}_3:$i", accountId = accountId, folderId = trash, serverId = "3:$i",
                from = "a@corp.example", to = "me@corp.example", subject = "s$i", body = "", dateReceived = 1_000L + i, read = true
            )
        }
        db.emailDao().insertAllIgnore(ids)
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))
        val sent = mutableListOf<String>()
        coEvery { client.deleteEmailsPermanentlyBatch("3", any(), "T2") } coAnswers {
            sent += arg<List<String>>(1)
            EasResult.Success("T3")
        }

        val result = ops.deleteEmailsPermanently(ids.map { it.id })

        assertThat(result).isEqualTo(EasResult.Success(1_200))
        assertThat(sent).containsExactlyElementsIn(ids.map { it.serverId }).inOrder()
        assertThat(db.emailDao().getEmailsByFolderList(trash)).isEmpty()
    }

    @Test
    fun `permanent delete without a connection does not try every email over EWS`() = runBlocking<Unit> {
        val id = email(folder = trash, serverId = "3:1")
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))
        coEvery { client.deleteEmailsPermanentlyBatch(any(), any(), any()) } returns offline

        val result = ops.deleteEmailsPermanently(listOf(id))

        // Раньше каждое письмо уходило отдельным EWS-запросом (N таймаутов подряд, батарея)
        assertThat(result).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(runBlocking { db.emailDao().getEmail(id) }).isNotNull()
        coVerify(exactly = 0) { client.deleteEmailPermanentlyViaEWS(any(), any()) }
    }

    @Test
    fun `permanent delete rejected by the server falls back to EWS per email`() = runBlocking<Unit> {
        val id = email(folder = trash, serverId = "3:1")
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))
        coEvery { client.deleteEmailsPermanentlyBatch(any(), any(), any()) } returns EasResult.Error("Batch delete failed: Status=5")
        coEvery { client.deleteEmailPermanentlyViaEWS("3:1", "Смета") } returns EasResult.Success(Unit)

        val result = ops.deleteEmailsPermanently(listOf(id))

        assertThat(result).isEqualTo(EasResult.Success(1))
        assertThat(runBlocking { db.emailDao().getEmail(id) }).isNull()
    }

    @Test
    fun `permanent delete takes the folder changes by a real sync instead of dropping them`() = runBlocking<Unit> {
        val id = email(folder = trash, serverId = "3:1")
        val arrived = EasEmail(
            serverId = "3:9", from = "Boss <boss@corp.example>", to = "me@corp.example", subject = "Удалено в Outlook",
            dateReceived = "2026-10-01T10:00:00.000Z", read = true, importance = 1, body = "x", messageClass = "IPM.Note"
        )
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, listOf(arrived)))
        coEvery { client.deleteEmailsPermanentlyBatch("3", listOf("3:1"), "T2") } returns EasResult.Success("T3")

        val result = ops.deleteEmailsPermanently(listOf(id))

        assertThat(result).isEqualTo(EasResult.Success(1))
        assertThat(runBlocking { db.emailDao().getEmail(id) }).isNull()
        // Письмо, пришедшее в «Удалённые» до удаления, не потеряно (раньше холостой Sync продвигал ключ мимо него)
        assertThat(runBlocking { db.emailDao().getEmail("${accountId}_3:9") }).isNotNull()
        assertThat(key(trash)).isEqualTo("T3")
        coVerify(exactly = 0) { client.sync("3", "0", any(), any(), any()) }
    }
}

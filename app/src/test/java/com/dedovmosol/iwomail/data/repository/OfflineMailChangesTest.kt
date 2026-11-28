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
import com.dedovmosol.iwomail.eas.EasDeleteResponse
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
    // Звезда ставит флаг «Сегодня» с датами: Exchange 2007 отклоняет флаг без дат (Sync Status 6), см. FlagPresets.toggled
    private val on get() = com.dedovmosol.iwomail.domain.FlagPresets.toggled(MailFlag.NONE)
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

        val result = ops.moveToTrash(listOf(id))

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

        val result = ops.moveToTrash(listOf(id))

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
    fun `object not found (Status 8) keeps the star and queues it instead of rolling back`() = runBlocking<Unit> {
        // Exchange 2007: Change сразу после SyncKey=0 получает 8 — сервер ещё не знает id, это не отказ во флаге
        val id = email()
        coEvery { client.setFlag(any(), any(), any(), any()) } returns EasResult.Success(FlagWrite("K2", applied = null, changeStatus = 8))

        val result = ops.toggleFlag(id)

        assertThat(result).isEqualTo(EasResult.Success(true))
        assertThat(stored(id).flagged).isTrue()
        assertThat(pending(id)!!.flag).isTrue()
        assertThat(key(inbox)).isEqualTo("K2")
    }

    @Test
    fun `with no sync key the star and read state wait - no Sync(0) that would renumber the folder`() = runBlocking<Unit> {
        val id = email(read = false)
        db.folderDao().updateSyncKey(inbox, "0")

        assertThat(ops.toggleFlag(id)).isEqualTo(EasResult.Success(true))
        assertThat(ops.markAsRead(id, true)).isEqualTo(EasResult.Success(true))

        assertThat(stored(id).flagged).isTrue()
        assertThat(stored(id).read).isTrue()
        assertThat(pending(id)!!.flag).isTrue()
        assertThat(pending(id)!!.read).isTrue()
        coVerify(exactly = 0) { client.sync(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.setFlag(any(), any(), any(), any()) }
        assertThat(key(inbox)).isEqualTo("0")
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
            val requested = arg<List<String>>(1)
            sent += requested
            EasResult.Success(EasDeleteResponse("T3", requested.toSet()))
        }

        val result = ops.deleteEmailsPermanently(ids.map { it.id })

        assertThat(result).isEqualTo(EasResult.Success(1_200))
        assertThat(sent).containsExactlyElementsIn(ids.map { it.serverId }).inOrder()
        assertThat(db.emailDao().getEmailsByFolderList(trash)).isEmpty()
    }

    @Test
    fun `permanent delete leaves the message when the connection is lost`() = runBlocking<Unit> {
        val id = email(folder = trash, serverId = "3:1")
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))
        coEvery { client.deleteEmailsPermanentlyBatch(any(), any(), any()) } returns offline

        val result = ops.deleteEmailsPermanently(listOf(id))

        assertThat(result).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(id)).isNotNull()
        assertThat(key(trash)).isEqualTo("T2")
    }

    @Test
    fun `rejected EAS delete leaves the selected message and the same-subject neighbour`() = runBlocking<Unit> {
        val selected = email(folder = trash, serverId = "3:1")
        val neighbour = email(folder = trash, serverId = "3:2")
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))
        coEvery { client.deleteEmailsPermanentlyBatch("3", listOf("3:1"), "T2") } returns EasResult.Error("Delete collection Status=5")

        assertThat(ops.deleteEmailsPermanently(listOf(selected))).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(selected)).isNotNull()
        assertThat(db.emailDao().getEmail(neighbour)).isNotNull()
        coVerify(exactly = 1) { client.deleteEmailsPermanentlyBatch(any(), any(), any()) }
    }

    @Test
    fun `partial delete saves the key but retains the rejected row and pending changes`() = runBlocking<Unit> {
        val confirmed = email(folder = trash, serverId = "3:1")
        val rejected = email(folder = trash, serverId = "3:2")
        db.pendingEmailChangeDao().markFlag(confirmed, accountId)
        db.pendingEmailChangeDao().markRead(rejected, accountId)
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))
        coEvery { client.deleteEmailsPermanentlyBatch("3", listOf("3:1", "3:2"), "T2") } returns
            EasResult.Success(EasDeleteResponse("T3", setOf("3:1")))

        assertThat(ops.deleteEmailsPermanently(listOf(confirmed, rejected))).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(confirmed)).isNull()
        assertThat(pending(confirmed)).isNull()
        assertThat(db.emailDao().getEmail(rejected)).isNotNull()
        assertThat(pending(rejected)?.read).isTrue()
        assertThat(key(trash)).isEqualTo("T3")
    }

    @Test
    fun `delete is not replayed after Exchange 2007 assigns the selected ServerId to a different message`() = runBlocking<Unit> {
        val selected = email(folder = trash, serverId = "3:1")
        val recovered = EasEmail(serverId = "3:2", subject = "Смета", from = "boss@corp.example", to = "me@corp.example",
            dateReceived = "1970-01-01T00:00:01.000Z", read = false, importance = 1, body = "A", messageClass = "IPM.Note")
        val different = recovered.copy(serverId = "3:1", subject = "B", from = "other@corp.example")
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T1", 3, emptyList()))
        coEvery { client.sync("3", "0", any(), any(), any()) } returns EasResult.Success(SyncResponse("R1", 1, emptyList()))
        coEvery { client.sync("3", "R1", any(), any(), any()) } returns EasResult.Success(SyncResponse("R2", 1, listOf(different, recovered)))

        assertThat(ops.deleteEmailsPermanently(listOf(selected))).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(selected)?.subject).isEqualTo("B")
        assertThat(db.emailDao().getEmail("${accountId}_3:2")?.subject).isEqualTo("Смета")
        assertThat(key(trash)).isEqualTo("R2")
        coVerify(exactly = 0) { client.deleteEmailsPermanentlyBatch(any(), any(), any()) }
    }

    @Test
    fun `a reset invalidates even an unchanged-looking selection and a new selection can delete`() = runBlocking<Unit> {
        val selected = email(folder = trash, serverId = "3:1")
        val same = EasEmail(serverId = "3:1", subject = "Смета", from = "boss@corp.example", to = "me@corp.example",
            dateReceived = "1970-01-01T00:00:01.000Z", read = false, importance = 1, body = "A", messageClass = "IPM.Note")
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T1", 3, emptyList()))
        coEvery { client.sync("3", "0", any(), any(), any()) } returns EasResult.Success(SyncResponse("R1", 1, emptyList()))
        coEvery { client.sync("3", "R1", any(), any(), any()) } returns EasResult.Success(SyncResponse("R2", 1, listOf(same)))
        coEvery { client.sync("3", "R2", any(), any(), any()) } returns EasResult.Success(SyncResponse("R3", 1, emptyList()))
        coEvery { client.deleteEmailsPermanentlyBatch("3", listOf("3:1"), "R3") } returns EasResult.Success(EasDeleteResponse("R4", setOf("3:1")))

        assertThat(ops.deleteEmailsPermanently(listOf(selected))).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(selected)).isNotNull()
        coVerify(exactly = 0) { client.deleteEmailsPermanentlyBatch(any(), any(), any()) }
        assertThat(ops.deleteEmailsPermanently(listOf(selected))).isEqualTo(EasResult.Success(1))
        assertThat(db.emailDao().getEmail(selected)).isNull()
    }

    @Test
    fun `an old confirmation cannot acquire a different message now stored under its ID`() = runBlocking<Unit> {
        val id = email(folder = trash, serverId = "3:1")
        val selection = MailDeleteSelection.of(stored(id))
        db.emailDao().insert(stored(id).copy(subject = "Replacement B"))

        assertThat(ops.deleteEmailsPermanentlyWithProgress(selection.ids, { _, _ -> }, selection))
            .isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(id)?.subject).isEqualTo("Replacement B")
        coVerify(exactly = 0) { client.deleteEmailsPermanentlyBatch(any(), any(), any()) }
    }

    @Test
    fun `cleanup cannot use a candidate from a previous persisted SyncKey epoch`() = runBlocking<Unit> {
        val candidate = email(folder = trash, serverId = "3:1")
        db.inboxSyncSnapshotDao().insertMarker(
            com.dedovmosol.iwomail.data.database.InboxSyncSnapshotEntry(trash, "epoch:new", seen = false)
        )
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))

        assertThat(ops.deleteEmailsPermanently(listOf(candidate), mapOf(trash to "old"))).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(candidate)).isNotNull()
        coVerify(exactly = 0) { client.deleteEmailsPermanentlyBatch(any(), any(), any()) }
    }

    @Test
    fun `move to trash never permanently deletes an existing Trash match`() = runBlocking<Unit> {
        val candidate = email(folder = trash, serverId = "3:1")
        assertThat(ops.moveToTrash(listOf(candidate))).isEqualTo(EasResult.Error("OPEN_FOLDER_TO_DELETE"))
        assertThat(db.emailDao().getEmail(candidate)).isNotNull()
        coVerify(exactly = 0) { client.deleteEmailsPermanentlyBatch(any(), any(), any()) }
    }

    @Test
    fun `finishing a saved full sync checkpoint still requires a new delete selection`() = runBlocking<Unit> {
        val selected = email(folder = trash, serverId = "3:1")
        db.inboxSyncSnapshotDao().insertMarker(
            com.dedovmosol.iwomail.data.database.InboxSyncSnapshotEntry(trash, "", seen = false)
        )
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, emptyList()))
        coEvery { client.sync("3", "T2", any(), any(), any()) } returns EasResult.Success(SyncResponse("T3", 1, emptyList()))
        coEvery { client.deleteEmailsPermanentlyBatch("3", listOf("3:1"), "T3") } returns EasResult.Success(EasDeleteResponse("T4", setOf("3:1")))

        assertThat(ops.deleteEmailsPermanently(listOf(selected))).isEqualTo(EasResult.Error("DELETE_FAILED"))
        assertThat(db.emailDao().getEmail(selected)).isNotNull()
        assertThat(db.inboxSyncSnapshotDao().getMarker(trash)).isNull()
        coVerify(exactly = 0) { client.deleteEmailsPermanentlyBatch(any(), any(), any()) }
        assertThat(ops.deleteEmailsPermanently(listOf(selected))).isEqualTo(EasResult.Success(1))
    }

    @Test
    fun `permanent delete takes the folder changes by a real sync instead of dropping them`() = runBlocking<Unit> {
        val id = email(folder = trash, serverId = "3:1")
        val arrived = EasEmail(
            serverId = "3:9", from = "Boss <boss@corp.example>", to = "me@corp.example", subject = "Удалено в Outlook",
            dateReceived = "2026-10-01T10:00:00.000Z", read = true, importance = 1, body = "x", messageClass = "IPM.Note"
        )
        coEvery { client.sync("3", "T1", any(), any(), any()) } returns EasResult.Success(SyncResponse("T2", 1, listOf(arrived)))
        coEvery { client.deleteEmailsPermanentlyBatch("3", listOf("3:1"), "T2") } returns EasResult.Success(EasDeleteResponse("T3", setOf("3:1")))

        val result = ops.deleteEmailsPermanently(listOf(id))

        assertThat(result).isEqualTo(EasResult.Success(1))
        assertThat(runBlocking { db.emailDao().getEmail(id) }).isNull()
        // Письмо, пришедшее в «Удалённые» до удаления, не потеряно (раньше холостой Sync продвигал ключ мимо него)
        assertThat(runBlocking { db.emailDao().getEmail("${accountId}_3:9") }).isNotNull()
        assertThat(key(trash)).isEqualTo("T3")
        coVerify(exactly = 0) { client.sync("3", "0", any(), any(), any()) }
    }
}

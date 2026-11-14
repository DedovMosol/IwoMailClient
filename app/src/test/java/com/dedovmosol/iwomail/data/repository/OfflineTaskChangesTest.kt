package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.PimKind
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.EasTask
import com.dedovmosol.iwomail.eas.TasksSyncResult
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Правки задач без связи на настоящей Room-БД: откладываются, переживают синхронизацию, уходят на сервер тем же путём
 * и в том же порядке. Подменены только сетевая граница ([EasClient]) и состояние сети ([OfflineWrites]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTaskChangesTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var repo: TaskRepository
    private val accountRepo = mockk<AccountRepository>()
    private val easClient = mockk<EasClient>()
    private var accountId = 0L
    private var online = true
    private var scheduled = 0

    private val queue get() = db.pendingPimChangeDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me")
        accountId = runBlocking { db.accountDao().insert(account) }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns easClient
        every { accountRepo.clearEasClientCache(accountId) } just Runs
        coEvery { easClient.syncTasks() } returns EasResult.Success(TasksSyncResult(emptyList(), isAuthoritative = false))
        repo = TaskRepository(context, db, accountRepo, OfflineWrites(isOnline = { online }, scheduleSend = { scheduled++ }))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seed(serverId: String = "t1", subject: String = "Смета", isDeleted: Boolean = false): TaskEntity {
        val task = TaskEntity(
            id = "${accountId}_$serverId", accountId = accountId, serverId = serverId, subject = subject,
            lastModified = LONG_AGO, isDeleted = isDeleted
        )
        runBlocking { db.taskDao().insert(task) }
        return task
    }

    private fun task(serverId: String = "t1") = runBlocking { db.taskDao().getTask("${accountId}_$serverId") }

    private fun serverUpdate(result: EasResult<Boolean>) {
        coEvery {
            easClient.updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns result
    }

    @Test
    fun `edit without a connection is kept on the device and queued without contacting the server`() = runBlocking<Unit> {
        val original = seed()
        online = false

        val result = repo.updateTask(original, subject = "Смета v2", complete = true)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat(task()!!.subject).isEqualTo("Смета v2")
        assertThat(task()!!.complete).isTrue()
        val change = queue.get(original.id)!!
        assertThat(change.pendingUpdate).isTrue()
        assertThat(change.serverSubject).isEqualTo("Смета")
        assertThat(scheduled).isEqualTo(1)
        coVerify(exactly = 0) { easClient.updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `connection failure during the request queues the edit`() = runBlocking<Unit> {
        val original = seed()
        serverUpdate(EasResult.Error("[NET:TIMEOUT] SocketTimeoutException: timeout"))

        val result = repo.updateTask(original, subject = "Смета v2")

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat(repo.hasPendingChange(original.id)).isTrue()
        assertThat(task()!!.subject).isEqualTo("Смета v2")
    }

    @Test
    fun `EWS failure without a tag is queued when the network dropped during the request`() = runBlocking<Unit> {
        // Exchange 2007 (EWS): обрыв даёт ошибку без метки транспорта — решает состояние сети после сбоя
        val original = seed()
        coEvery {
            easClient.updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            online = false
            EasResult.Error("Не удалось найти задачу на сервере")
        }

        val result = repo.updateTask(original, subject = "Смета v2")

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat(repo.hasPendingChange(original.id)).isTrue()
    }

    @Test
    fun `server rejection is an error and the edit is neither saved nor queued`() = runBlocking<Unit> {
        val original = seed()
        serverUpdate(EasResult.Error("Change Status=7: Conflict"))

        val result = repo.updateTask(original, subject = "Смета v2")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(task()!!.subject).isEqualTo("Смета")
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `assignment without a connection is an error, not a silent queue`() = runBlocking<Unit> {
        // Назначение — письмо исполнителю: без связи его не отправить
        val original = seed()
        online = false
        serverUpdate(EasResult.Error("[NET:DNS] UnknownHostException"))

        val result = repo.updateTask(original, subject = "Смета", assignTo = "boss@corp.local")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `sync does not overwrite an edit that is waiting to be sent`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")
        // Без связи — часы: защита «свежей правки» (10 с) уже не действует, держит только очередь
        db.taskDao().touch(original.id, LONG_AGO)
        online = true
        coEvery { easClient.syncTasks() } returns EasResult.Success(
            TasksSyncResult(listOf(EasTask(serverId = "t1", subject = "Смета")), isAuthoritative = true)
        )

        repo.syncTasks(accountId)

        assertThat(task()!!.subject).isEqualTo("Смета v2")
    }

    @Test
    fun `flush sends the current state with the server subject and clears the queue`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")
        repo.updateTask(task()!!, subject = "Смета v3") // две правки без связи — один итог
        online = true
        serverUpdate(EasResult.Success(true))

        val change = queue.get(original.id)!!
        assertThat(repo.sendPendingTask(change)).isEqualTo(PimSendOutcome.DONE)

        // EWS (Exchange 2007) ищет задачу по теме, которая на сервере до правок
        coVerify(exactly = 1) {
            easClient.updateTask("t1", "Смета v3", any(), any(), any(), any(), any(), any(), any(), "Смета")
        }
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `an edit made while the queued one is being sent is not lost`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")
        online = true
        val sent = queue.get(original.id)!!
        coEvery {
            easClient.updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            // Пока запрос в пути, пользователь правит ещё раз
            queue.markUpdate(original.id, accountId, PimKind.TASK, "Смета")
            EasResult.Success(true)
        }

        repo.sendPendingTask(sent)

        assertThat(queue.get(original.id)!!.pendingUpdate).isTrue()
    }

    @Test
    fun `an online edit behind a queued one goes to the queue to keep the order`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")
        online = true

        repo.updateTask(task()!!, subject = "Смета v3")

        coVerify(exactly = 0) { easClient.updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(task()!!.subject).isEqualTo("Смета v3")
        assertThat(queue.get(original.id)!!.version).isEqualTo(2)
    }

    @Test
    fun `flush without a connection keeps the queue`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")

        assertThat(repo.sendPendingTask(queue.get(original.id)!!)).isEqualTo(PimSendOutcome.KEPT)
        assertThat(queue.count()).isEqualTo(1)
    }

    @Test
    fun `rejected queued edit is dropped so sync brings back the server version`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")
        online = true
        serverUpdate(EasResult.Error("Change Status=8: Object not found"))

        assertThat(repo.sendPendingTask(queue.get(original.id)!!)).isEqualTo(PimSendOutcome.DONE)
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `trash without a connection then restore never touches the server`() = runBlocking<Unit> {
        val original = seed()
        online = false

        assertThat(repo.deleteTask(original)).isInstanceOf(EasResult.Success::class.java)
        assertThat(task()!!.isDeleted).isTrue()
        assertThat(queue.get(original.id)!!.pendingTrash).isTrue()

        assertThat(repo.restoreTask(task()!!)).isInstanceOf(EasResult.Success::class.java)
        assertThat(task()!!.isDeleted).isFalse()
        assertThat(queue.count()).isEqualTo(0)
        coVerify(exactly = 0) { easClient.deleteTask(any()) }
        coVerify(exactly = 0) { easClient.restoreTask(any(), any()) }
    }

    @Test
    fun `sync does not bring back a task trashed without a connection`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.deleteTask(original)
        db.taskDao().touch(original.id, LONG_AGO) // защита «свежей корзины» (30 с) уже не действует
        online = true
        coEvery { easClient.syncTasks() } returns EasResult.Success(
            TasksSyncResult(listOf(EasTask(serverId = "t1", subject = "Смета")), isAuthoritative = true, includesDeletedItems = true)
        )

        repo.syncTasks(accountId)

        assertThat(task()!!.isDeleted).isTrue()
    }

    @Test
    fun `permanent delete sends the queued trash first`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.deleteTask(original)
        online = true
        coEvery { easClient.deleteTask("t1") } returns EasResult.Success(true)
        coEvery { easClient.deleteTaskPermanently("t1", "Смета") } returns EasResult.Success(true)

        val result = repo.deleteTaskPermanently(task()!!)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            easClient.deleteTask("t1")
            easClient.deleteTaskPermanently("t1", "Смета")
        }
        assertThat(task()).isNull()
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `queued trash is retried with the new ServerId the server gave the task`() = runBlocking<Unit> {
        // Exchange 2007: после UpdateItem у задачи новый ServerId — старый сервер не находит
        val original = seed()
        online = false
        repo.deleteTask(original)
        online = true
        coEvery { easClient.deleteTask("t1") } returns EasResult.Error("Задача не найдена")
        coEvery { easClient.syncTasks() } returns EasResult.Success(
            TasksSyncResult(listOf(EasTask(serverId = "t1b", subject = "Смета")), isAuthoritative = false)
        )
        coEvery { easClient.deleteTask("t1b") } returns EasResult.Success(true)

        assertThat(repo.sendPendingTask(queue.get(original.id)!!)).isEqualTo(PimSendOutcome.DONE)

        coVerify(exactly = 1) { easClient.deleteTask("t1b") }
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `permanent delete without a connection keeps the task and reports unsent changes`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.deleteTask(original)

        val result = repo.deleteTaskPermanently(task()!!)

        assertThat((result as EasResult.Error).message).isEqualTo(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
        assertThat(task()).isNotNull()
        coVerify(exactly = 0) { easClient.deleteTaskPermanently(any(), any()) }
    }

    @Test
    fun `reset does not wipe unsent edits`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")

        val result = repo.resetAndResync(accountId)

        assertThat((result as EasResult.Error).message).isEqualTo(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
        assertThat(task()!!.subject).isEqualTo("Смета v2")
    }

    @Test
    fun `deleting the account drops its queue`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateTask(original, subject = "Смета v2")

        db.accountDao().delete(accountId)

        assertThat(queue.count()).isEqualTo(0)
    }

    private companion object {
        val LONG_AGO = System.currentTimeMillis() - 3_600_000L
    }
}

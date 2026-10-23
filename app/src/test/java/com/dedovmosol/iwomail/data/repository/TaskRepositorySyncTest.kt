package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.EasTask
import com.dedovmosol.iwomail.eas.TasksSyncResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TaskRepository.syncTasks] на настоящей Room-БД: удаляет ли репозиторий локальные задачи по снимку.
 *
 * Подменена только сетевая граница — [EasClient.syncTasks] отдаёт заданный [TasksSyncResult]
 * (как он собирается из ответов сервера, проверяет EasTasksSyncCompletenessTest). Всё остальное —
 * прод-код: репозиторий, DAO, SQL, напоминания (Robolectric AlarmManager).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskRepositorySyncTest {

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var repo: TaskRepository
    private val accountRepo = mockk<AccountRepository>()
    private val easClient = mockk<EasClient>()
    private var accountId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val account = AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me")
        accountId = runBlocking { db.accountDao().insert(account) }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns easClient
        repo = TaskRepository(context, db, accountRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun local(serverId: String, isDeleted: Boolean = false, subject: String = "Task $serverId") = TaskEntity(
        id = "${accountId}_$serverId",
        accountId = accountId,
        serverId = serverId,
        subject = subject,
        lastModified = LONG_AGO,
        isDeleted = isDeleted
    )

    private fun server(serverId: String) = EasTask(serverId = serverId, subject = "Task $serverId")

    private fun seed(vararg tasks: TaskEntity) = runBlocking { db.taskDao().insertAll(tasks.toList()) }

    private fun serverReturns(snapshot: TasksSyncResult) {
        coEvery { easClient.syncTasks() } returns EasResult.Success(snapshot)
    }

    private fun localServerIds(): List<String> = runBlocking {
        (db.taskDao().getTasksByAccountList(accountId) + db.taskDao().getDeletedTasksList(accountId)).map { it.serverId }
    }

    @Test
    fun `incomplete snapshot never deletes local tasks`() = runBlocking<Unit> {
        seed(local("t1"), local("t2"), local("t3"))
        serverReturns(TasksSyncResult(listOf(server("t1")), isAuthoritative = false))

        val result = repo.syncTasks(accountId)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat(localServerIds()).containsExactly("t1", "t2", "t3")
    }

    @Test
    fun `incomplete snapshot still upserts the tasks it does contain`() = runBlocking<Unit> {
        seed(local("t1", subject = "Old subject"))
        serverReturns(TasksSyncResult(listOf(server("t1"), server("t9")), isAuthoritative = false))

        repo.syncTasks(accountId)

        assertThat(db.taskDao().getTask("${accountId}_t1")!!.subject).isEqualTo("Task t1")
        assertThat(db.taskDao().getTask("${accountId}_t9")).isNotNull()
    }

    @Test
    fun `authoritative snapshot deletes tasks that are gone from the server`() = runBlocking<Unit> {
        seed(local("t1"), local("t2"), local("t3"))
        serverReturns(TasksSyncResult(listOf(server("t1"), server("t2")), isAuthoritative = true))

        repo.syncTasks(accountId)

        assertThat(localServerIds()).containsExactly("t1", "t2")
    }

    @Test
    fun `unresolved tasks are kept even in an authoritative snapshot`() = runBlocking<Unit> {
        // EWS: FindItem подтвердил t2, но GetItem деталей не отдал — задача есть, удалять нельзя.
        seed(local("t1"), local("t2", subject = "Keep me"), local("t3"))
        serverReturns(TasksSyncResult(listOf(server("t1")), isAuthoritative = true, unresolvedServerIds = setOf("t2")))

        repo.syncTasks(accountId)

        assertThat(localServerIds()).containsExactly("t1", "t2")
        assertThat(db.taskDao().getTask("${accountId}_t2")!!.subject).isEqualTo("Keep me")
    }

    @Test
    fun `heuristic still guards an authoritative empty snapshot once`() = runBlocking<Unit> {
        // Вторая линия защиты сохранена: «сервер пуст» при >3 локальных задачах удаляет не с первого раза.
        seed(local("t1"), local("t2"), local("t3"), local("t4"))
        serverReturns(TasksSyncResult(emptyList(), isAuthoritative = true))

        repo.syncTasks(accountId)
        assertThat(localServerIds()).containsExactly("t1", "t2", "t3", "t4")

        repo.syncTasks(accountId)
        assertThat(localServerIds()).isEmpty()
    }

    @Test
    fun `repeated incomplete empty snapshots never delete`() = runBlocking<Unit> {
        // Раньше неполный синк выглядел как пустой сервер и со второго раза сносил все задачи.
        seed(local("t1"), local("t2"), local("t3"), local("t4"))
        serverReturns(TasksSyncResult(emptyList(), isAuthoritative = false))

        repeat(3) { repo.syncTasks(accountId) }

        assertThat(localServerIds()).containsExactly("t1", "t2", "t3", "t4")
    }

    @Test
    fun `sync error leaves the database untouched`() = runBlocking<Unit> {
        seed(local("t1"), local("t2"))
        coEvery { easClient.syncTasks() } returns EasResult.Error("HTTP 503")

        val result = repo.syncTasks(accountId)

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(localServerIds()).containsExactly("t1", "t2")
    }

    private companion object {
        val LONG_AGO = System.currentTimeMillis() - 3_600_000L
    }
}

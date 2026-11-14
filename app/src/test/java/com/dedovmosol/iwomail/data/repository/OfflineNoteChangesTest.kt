package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.NoteEntity
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasNote
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.NotesSyncResult
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
 * Правки заметок без связи на настоящей Room-БД: откладываются, переживают синхронизацию, уходят на сервер, а
 * окончательное удаление сначала отправляет корзину. Подменены только [EasClient] и состояние сети ([OfflineWrites]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineNoteChangesTest {

    private lateinit var db: MailDatabase
    private lateinit var repo: NoteRepository
    private val accountRepo = mockk<AccountRepository>()
    private val easClient = mockk<EasClient>()
    private var accountId = 0L
    private var online = true
    private var scheduled = 0

    private val queue get() = db.pendingPimChangeDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me")
        accountId = runBlocking { db.accountDao().insert(account) }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns easClient
        every { accountRepo.clearEasClientCache(accountId) } just Runs
        unchangedServer()
        repo = NoteRepository(db, accountRepo, OfflineWrites(isOnline = { online }, scheduleSend = { scheduled++ }))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun unchangedServer() {
        coEvery { easClient.syncNotes() } returns EasResult.Success(
            NotesSyncResult(emptyList(), isActiveAuthoritative = false, isDeletedAuthoritative = false, isUnchanged = true)
        )
    }

    private fun seed(serverId: String = "n1", subject: String = "Идеи", body: String = "Текст"): NoteEntity {
        val note = NoteEntity(
            id = "${accountId}_$serverId", accountId = accountId, serverId = serverId, subject = subject, body = body,
            lastModified = LONG_AGO
        )
        runBlocking { db.noteDao().insert(note) }
        return note
    }

    private fun note(serverId: String = "n1") = runBlocking { db.noteDao().getNote("${accountId}_$serverId") }

    @Test
    fun `edit without a connection is kept on the device and queued`() = runBlocking<Unit> {
        val original = seed()
        online = false

        val result = repo.updateNote(original, "Идеи v2", "Новый текст")

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat(note()!!.body).isEqualTo("Новый текст")
        assertThat(queue.get(original.id)!!.pendingUpdate).isTrue()
        assertThat(scheduled).isEqualTo(1)
        coVerify(exactly = 0) { easClient.updateNote(any(), any(), any()) }
    }

    @Test
    fun `server rejection is an error and nothing is queued`() = runBlocking<Unit> {
        val original = seed()
        coEvery { easClient.updateNote(any(), any(), any()) } returns EasResult.Error("Change Status=6")

        val result = repo.updateNote(original, "Идеи v2", "Новый текст")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(note()!!.body).isEqualTo("Текст")
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `full sync does not overwrite or trash a queued edit`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateNote(original, "Идеи v2", "Новый текст")
        // Без связи — часы: защита «свежей правки» (10 с) уже не действует, держит только очередь
        db.noteDao().update(note()!!.copy(lastModified = LONG_AGO))
        online = true
        coEvery { easClient.syncNotes() } returns EasResult.Success(
            NotesSyncResult(
                listOf(EasNote(serverId = "n1", subject = "Идеи", body = "Текст", lastModified = LONG_AGO)),
                isActiveAuthoritative = true, isDeletedAuthoritative = true
            )
        )

        repo.syncNotes(accountId)

        assertThat(note()!!.subject).isEqualTo("Идеи v2")
        assertThat(note()!!.body).isEqualTo("Новый текст")
        assertThat(note()!!.isDeleted).isFalse()
    }

    @Test
    fun `flush sends the edit and clears the queue`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.updateNote(original, "Идеи v2", "Новый текст")
        online = true
        coEvery { easClient.updateNote("n1", "Идеи v2", "Новый текст") } returns EasResult.Success(true)

        assertThat(repo.sendPendingNote(queue.get(original.id)!!)).isEqualTo(PimSendOutcome.DONE)

        coVerify(exactly = 1) { easClient.updateNote("n1", "Идеи v2", "Новый текст") }
        assertThat(queue.count()).isEqualTo(0)
    }

    @Test
    fun `batch trash without a connection queues every note`() = runBlocking<Unit> {
        val a = seed("n1")
        val b = seed("n2", subject = "Другое")
        online = false

        val result = repo.deleteNotes(listOf(a, b))

        assertThat((result as EasResult.Success).data).isEqualTo(2)
        assertThat(note("n1")!!.isDeleted).isTrue()
        assertThat(note("n2")!!.isDeleted).isTrue()
        assertThat(queue.count()).isEqualTo(2)
        coVerify(exactly = 0) { easClient.deleteNotesBatch(any()) }
    }

    @Test
    fun `batch trash that loses the connection queues the notes`() = runBlocking<Unit> {
        val a = seed("n1")
        coEvery { easClient.deleteNotesBatch(any()) } returns EasResult.Error("[NET:RESET] SocketException: Connection reset")

        val result = repo.deleteNotes(listOf(a))

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat(queue.get(a.id)!!.pendingTrash).isTrue()
        coVerify(exactly = 0) { easClient.deleteNote(any()) }
    }

    @Test
    fun `restore of a note trashed without a connection stays on the device`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.deleteNote(original)

        assertThat(repo.restoreNote(note()!!)).isInstanceOf(EasResult.Success::class.java)

        assertThat(note()!!.isDeleted).isFalse()
        assertThat(queue.count()).isEqualTo(0)
        coVerify(exactly = 0) { easClient.restoreNote(any()) }
    }

    @Test
    fun `permanent delete sends the queued trash first and deletes the note under its new id`() = runBlocking<Unit> {
        // Перенос в Deleted Items выдаёт заметке новый ServerId: окончательное удаление идёт по нему
        val original = seed()
        online = false
        repo.deleteNote(original)
        online = true
        coEvery { easClient.deleteNote("n1") } returns EasResult.Success(true)
        coEvery { easClient.syncNotes() } returns EasResult.Success(
            NotesSyncResult(
                listOf(EasNote(serverId = "d9", subject = "Идеи", body = "Текст", lastModified = LONG_AGO, isDeleted = true)),
                isActiveAuthoritative = true, isDeletedAuthoritative = true
            )
        )
        coEvery { easClient.deleteNotePermanently("d9") } returns EasResult.Success(true)

        val result = repo.deleteNotePermanently(note()!!)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            easClient.deleteNote("n1")
            easClient.deleteNotePermanently("d9")
        }
        assertThat(db.noteDao().getAllNotesByAccountList(accountId)).isEmpty()
    }

    @Test
    fun `empty trash without a connection keeps the trash and reports unsent changes`() = runBlocking<Unit> {
        val original = seed()
        online = false
        repo.deleteNote(original)

        val result = repo.emptyNotesTrash(accountId)

        assertThat((result as EasResult.Error).message).isEqualTo(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
        assertThat(note()).isNotNull()
        coVerify(exactly = 0) { easClient.deleteNotePermanently(any()) }
    }

    private companion object {
        val LONG_AGO = System.currentTimeMillis() - 3_600_000L
    }
}

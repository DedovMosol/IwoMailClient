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
 * [NoteRepository.syncNotes] на настоящей Room-БД: что репозиторий помечает удалённым и что
 * окончательно стирает из локальной корзины в зависимости от полноты снимка.
 *
 * Подменена только сетевая граница — [EasClient.syncNotes] отдаёт заданный [NotesSyncResult]
 * (сборку снимка из ответов сервера проверяет EasNotesSyncCompletenessTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteRepositorySyncTest {

    private lateinit var db: MailDatabase
    private lateinit var repo: NoteRepository
    private val accountRepo = mockk<AccountRepository>()
    private val easClient = mockk<EasClient>()
    private var accountId = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val account = AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me")
        accountId = runBlocking { db.accountDao().insert(account) }
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns easClient
        repo = NoteRepository(db, accountRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun local(
        serverId: String,
        isDeleted: Boolean = false,
        subject: String = "Note $serverId",
        body: String = "Body $serverId"
    ) = NoteEntity(
        id = "${accountId}_$serverId",
        accountId = accountId,
        serverId = serverId,
        subject = subject,
        body = body,
        lastModified = LONG_AGO,
        isDeleted = isDeleted
    )

    private fun server(
        serverId: String,
        isDeleted: Boolean = false,
        subject: String = "Note $serverId",
        body: String = "Body $serverId"
    ) = EasNote(serverId = serverId, subject = subject, body = body, lastModified = LONG_AGO, isDeleted = isDeleted)

    private fun seed(vararg notes: NoteEntity) = runBlocking { db.noteDao().insertAll(notes.toList()) }

    private fun serverReturns(snapshot: NotesSyncResult) {
        coEvery { easClient.syncNotes() } returns EasResult.Success(snapshot)
    }

    private fun activeIds(): List<String> = runBlocking { db.noteDao().getNotesByAccountList(accountId).map { it.serverId } }
    private fun trashIds(): List<String> = runBlocking { db.noteDao().getDeletedNotesByAccountList(accountId).map { it.serverId } }

    @Test
    fun `incomplete Notes snapshot marks nothing as deleted`() = runBlocking<Unit> {
        // Раньше: сбой/обрыв на середине → заметки вне частичного списка уходили в корзину.
        seed(local("n1"), local("n2"), local("n3"), local("d1", isDeleted = true))
        serverReturns(NotesSyncResult(listOf(server("n1")), isActiveAuthoritative = false, isDeletedAuthoritative = true))

        val result = repo.syncNotes(accountId)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        assertThat(activeIds()).containsExactly("n1", "n2", "n3")
        assertThat(trashIds()).containsExactly("d1")
    }

    @Test
    fun `complete Notes but incomplete Deleted Items moves missing notes to trash and keeps the trash`() = runBlocking<Unit> {
        seed(local("n1"), local("n2"), local("n3"), local("d1", isDeleted = true))
        serverReturns(NotesSyncResult(listOf(server("n1"), server("n2")), isActiveAuthoritative = true, isDeletedAuthoritative = false))

        repo.syncNotes(accountId)

        // n3 удалена в Outlook → в локальную корзину; d1 за окном Deleted Items → остаётся.
        assertThat(activeIds()).containsExactly("n1", "n2")
        assertThat(trashIds()).containsExactly("n3", "d1")
    }

    @Test
    fun `both folders complete purge a trash entry that is gone everywhere`() = runBlocking<Unit> {
        seed(local("n1"), local("d1", isDeleted = true), local("d2", isDeleted = true))
        serverReturns(NotesSyncResult(
            listOf(server("n1"), server("d2", isDeleted = true)),
            isActiveAuthoritative = true,
            isDeletedAuthoritative = true
        ))

        repo.syncNotes(accountId)

        assertThat(activeIds()).containsExactly("n1")
        assertThat(trashIds()).containsExactly("d2")
    }

    @Test
    fun `unresolved notes keep their body and are not moved to trash`() = runBlocking<Unit> {
        // EWS: FindItem подтвердил n2, но GetItem тела не отдал — локальная копия неприкосновенна.
        seed(local("n1"), local("n2", body = "Original long body"), local("n3"))
        serverReturns(NotesSyncResult(
            listOf(server("n1")),
            isActiveAuthoritative = true,
            isDeletedAuthoritative = true,
            unresolvedServerIds = setOf("n2")
        ))

        repo.syncNotes(accountId)

        assertThat(activeIds()).containsExactly("n1", "n2")
        assertThat(trashIds()).containsExactly("n3")
        assertThat(db.noteDao().getNote("${accountId}_n2")!!.body).isEqualTo("Original long body")
    }

    @Test
    fun `unchanged snapshot leaves the database untouched`() = runBlocking<Unit> {
        seed(local("n1"), local("n2"), local("d1", isDeleted = true))
        serverReturns(NotesSyncResult(emptyList(), isActiveAuthoritative = false, isDeletedAuthoritative = false, isUnchanged = true))

        val result = repo.syncNotes(accountId)

        assertThat((result as EasResult.Success).data).isEqualTo(3)
        assertThat(activeIds()).containsExactly("n1", "n2")
        assertThat(trashIds()).containsExactly("d1")
    }

    @Test
    fun `incomplete snapshot does not drop a separate note with the same text`() = runBlocking<Unit> {
        // Две разные заметки с одинаковым текстом; в частичный снимок попала только одна.
        // Дедупликация по содержимому не должна принимать вторую за устаревшую копию.
        seed(local("n1", subject = "Buy milk", body = "2 l"), local("n2", subject = "Buy milk", body = "2 l"))
        serverReturns(NotesSyncResult(
            listOf(server("n1", subject = "Buy milk", body = "2 l")),
            isActiveAuthoritative = false,
            isDeletedAuthoritative = false
        ))

        repo.syncNotes(accountId)

        assertThat(activeIds()).containsExactly("n1", "n2")
    }

    @Test
    fun `complete snapshot keeps both notes with the same text when both are on the server`() = runBlocking<Unit> {
        seed(local("n1", subject = "Buy milk", body = "2 l"), local("n2", subject = "Buy milk", body = "2 l"))
        serverReturns(NotesSyncResult(
            listOf(server("n1", subject = "Buy milk", body = "2 l"), server("n2", subject = "Buy milk", body = "2 l")),
            isActiveAuthoritative = true,
            isDeletedAuthoritative = true
        ))

        repo.syncNotes(accountId)

        assertThat(activeIds()).containsExactly("n1", "n2")
    }

    @Test
    fun `sync error leaves the database untouched`() = runBlocking<Unit> {
        seed(local("n1"), local("d1", isDeleted = true))
        coEvery { easClient.syncNotes() } returns EasResult.Error("HTTP 503")

        val result = repo.syncNotes(accountId)

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(activeIds()).containsExactly("n1")
        assertThat(trashIds()).containsExactly("d1")
    }

    private companion object {
        val LONG_AGO = System.currentTimeMillis() - 3_600_000L
    }
}

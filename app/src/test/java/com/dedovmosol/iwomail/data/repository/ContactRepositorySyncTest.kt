package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.eas.ContactsSyncResult
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.GalContact
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
 * [ContactRepository.syncExchangeContacts] на настоящей Room-БД: удаление локальных контактов
 * управляется значением [ContactsSyncResult] (симметрично задачам/заметкам), а не @Volatile-флагом.
 *
 * Тест гоняет всё через публичный API двумя синками: первый (authoritative) вставляет контакты их
 * штатным id-скоупом, второй проверяет удаление/сохранение. Внутренний хеш id не дублируется.
 * Подменена только сетевая граница — [EasClient.syncContacts].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactRepositorySyncTest {

    private lateinit var db: MailDatabase
    private lateinit var repo: ContactRepository
    private val accountRepo = mockk<AccountRepository>()
    private val easClient = mockk<EasClient>()
    private var accountId = 0L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountId = runBlocking {
            db.accountDao().insert(
                AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me")
            )
        }
        coEvery { accountRepo.createEasClient(accountId) } returns easClient
        repo = ContactRepository(db, accountRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun gal(serverId: String) =
        GalContact(displayName = "Name $serverId", email = "$serverId@corp.local", easServerId = serverId)

    private fun serverReturns(snapshot: ContactsSyncResult) {
        coEvery { easClient.syncContacts() } returns EasResult.Success(snapshot)
    }

    private fun exchangeServerIds(): List<String> = runBlocking {
        db.contactDao().getExchangeContactsList(accountId)
            .filter { it.id.startsWith("${accountId}_exchange_") }
            .mapNotNull { it.serverId }
    }

    @Test
    fun `authoritative snapshot inserts then deletes contacts gone from the server`() = runBlocking<Unit> {
        serverReturns(ContactsSyncResult(listOf(gal("a"), gal("b"), gal("c")), isAuthoritative = true))
        repo.syncExchangeContacts(accountId)
        assertThat(exchangeServerIds()).containsExactly("a", "b", "c")

        serverReturns(ContactsSyncResult(listOf(gal("a"), gal("b")), isAuthoritative = true))
        repo.syncExchangeContacts(accountId)
        assertThat(exchangeServerIds()).containsExactly("a", "b")
    }

    @Test
    fun `non-authoritative snapshot keeps contacts absent from it`() = runBlocking<Unit> {
        serverReturns(ContactsSyncResult(listOf(gal("a"), gal("b"), gal("c")), isAuthoritative = true))
        repo.syncExchangeContacts(accountId)

        // Неполная выдача (обрыв/лимит): отсутствие b, c ничего не доказывает — не удаляем.
        serverReturns(ContactsSyncResult(listOf(gal("a")), isAuthoritative = false))
        repo.syncExchangeContacts(accountId)
        assertThat(exchangeServerIds()).containsExactly("a", "b", "c")
    }

    @Test
    fun `unchanged snapshot is a no-op and returns the existing count`() = runBlocking<Unit> {
        serverReturns(ContactsSyncResult(listOf(gal("a"), gal("b")), isAuthoritative = true))
        repo.syncExchangeContacts(accountId)

        serverReturns(ContactsSyncResult(emptyList(), isAuthoritative = false, isUnchanged = true))
        val result = repo.syncExchangeContacts(accountId)

        assertThat((result as EasResult.Success).data).isEqualTo(2)
        assertThat(exchangeServerIds()).containsExactly("a", "b")
    }

    @Test
    fun `sync error leaves contacts untouched`() = runBlocking<Unit> {
        serverReturns(ContactsSyncResult(listOf(gal("a"), gal("b")), isAuthoritative = true))
        repo.syncExchangeContacts(accountId)

        coEvery { easClient.syncContacts() } returns EasResult.Error("HTTP 503")
        val result = repo.syncExchangeContacts(accountId)

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(exchangeServerIds()).containsExactly("a", "b")
    }
}

package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.ContactSource
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Отправителя в контакты» на настоящей Room-БД: формы адреса Exchange 2007–2013, дубли, свой адрес, гонка. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddSendersToLocalTest {

    private lateinit var db: MailDatabase
    private lateinit var repo: ContactRepository
    private var accountId = 0L
    private var otherAccountId = 0L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MailDatabase::class.java).build()
        runBlocking {
            accountId = db.accountDao().insert(AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me"))
            otherAccountId = db.accountDao().insert(AccountEntity(email = "two@corp.local", displayName = "Two", serverUrl = "https://x", username = "two"))
        }
        repo = ContactRepository(db, mockk(relaxed = true))
    }

    @After
    fun tearDown() = db.close()

    private fun local(id: Long = accountId) = runBlocking { db.contactDao().getContactsByAccountList(id).filter { it.source == ContactSource.LOCAL } }

    @Test
    fun `quoted Exchange 2007 from and separate fromName give one contact with a clean address and name`() = runBlocking<Unit> {
        val saved = repo.addSendersToLocal(accountId, listOf("\"Иванов Иван\" <Ivan.Ivanov@corp.local>" to ""), "me@corp.local")
        assertThat(saved).isEqualTo(ContactRepository.SendersSaved(1, 0))
        val c = local().single()
        assertThat(c.email).isEqualTo("Ivan.Ivanov@corp.local")
        assertThat(c.displayName).isEqualTo("Иванов Иван")
        assertThat(c.source).isEqualTo(ContactSource.LOCAL)
    }

    @Test
    fun `existing local contact is not duplicated, case-insensitive, and duplicates in one batch count once`() = runBlocking<Unit> {
        repo.addContact(accountId, "Petrov", "petrov@corp.local")
        val saved = repo.addSendersToLocal(
            accountId,
            listOf("PETROV@corp.local" to "Petrov", "a@corp.local" to "A", "A@CORP.LOCAL" to "A again"),
            "me@corp.local"
        )
        assertThat(saved).isEqualTo(ContactRepository.SendersSaved(added = 1, existing = 1))
        assertThat(local().map { it.email.lowercase() }).containsExactly("petrov@corp.local", "a@corp.local")
    }

    @Test
    fun `own address, blank and X500 legacy DN senders are skipped`() = runBlocking<Unit> {
        val saved = repo.addSendersToLocal(
            accountId,
            listOf(
                "Me <ME@corp.local>" to "Me",
                "" to "",
                "/O=CORP/OU=EXCHANGE ADMINISTRATIVE GROUP/CN=RECIPIENTS/CN=ivanov" to "Иванов"
            ),
            "me@corp.local"
        )
        assertThat(saved).isEqualTo(ContactRepository.SendersSaved(0, 0))
        assertThat(local()).isEmpty()
    }

    @Test
    fun `name equal to the address is not used as a name`() = runBlocking<Unit> {
        repo.addSendersToLocal(accountId, listOf("noreply@corp.local" to "noreply@corp.local"), null)
        assertThat(local().single().displayName).isEqualTo("noreply@corp.local")
    }

    @Test
    fun `contact goes to the sender's account only`() = runBlocking<Unit> {
        repo.addSendersToLocal(otherAccountId, listOf("x@corp.local" to "X"), "two@corp.local")
        assertThat(local(accountId)).isEmpty()
        assertThat(local(otherAccountId).map { it.email }).containsExactly("x@corp.local")
    }

    @Test
    fun `concurrent double tap creates one contact`() = runBlocking<Unit> {
        val results = (1..8).map {
            async(Dispatchers.Default) { repo.addSendersToLocal(accountId, listOf("race@corp.local" to "Race"), null) }
        }.awaitAll()
        assertThat(local().count { it.email == "race@corp.local" }).isEqualTo(1)
        assertThat(results.sumOf { it.added }).isEqualTo(1)
        assertThat(results.sumOf { it.existing }).isEqualTo(7)
    }

    @Test
    fun `toast text covers every outcome`() {
        assertThat(NotificationStrings.sendersSaved(1, 0, true)).isEqualTo("Отправитель добавлен в локальные контакты")
        assertThat(NotificationStrings.sendersSaved(0, 1, true)).isEqualTo("Отправитель уже есть в локальных контактах")
        assertThat(NotificationStrings.sendersSaved(0, 3, true)).isEqualTo("Все отправители уже есть в локальных контактах")
        assertThat(NotificationStrings.sendersSaved(3, 0, false)).isEqualTo("Added to local contacts: 3")
        assertThat(NotificationStrings.sendersSaved(2, 1, true)).isEqualTo("Добавлено в локальные контакты: 2, уже были: 1")
        assertThat(NotificationStrings.sendersSaved(0, 0, false)).isEqualTo("The sender has no address to save")
    }
}

package com.dedovmosol.iwomail.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.MailFlag
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Очередь правок без связи на настоящей Room-БД: SQL без UPSERT (SQLite Android 8), защита от перетирания сервером. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingEmailChangeDaoTest {

    private lateinit var db: MailDatabase
    private lateinit var dao: PendingEmailChangeDao
    private var accountId = 0L
    private val id get() = "${accountId}_2:1"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        dao = db.pendingEmailChangeDao()
        accountId = runBlocking {
            db.accountDao().insert(AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me"))
        }
        runBlocking {
            db.folderDao().insert(FolderEntity("${accountId}_2", accountId, "2", "Входящие", "0", FolderType.INBOX))
            db.emailDao().insert(EmailEntity(
                id = id, accountId = accountId, folderId = "${accountId}_2", serverId = "2:1", from = "a@corp.example",
                to = "me@corp.example", subject = "s", body = "", dateReceived = 1L, read = true, flagged = true
            ))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `flag and read waits combine in one row and clear independently`() = runBlocking<Unit> {
        dao.markFlag(id, accountId)
        dao.markRead(id, accountId)
        assertThat(dao.get(id)).isEqualTo(PendingEmailChangeEntity(id, accountId, flag = true, read = true))

        dao.clearFlag(id)
        assertThat(dao.get(id)).isEqualTo(PendingEmailChangeEntity(id, accountId, flag = false, read = true))

        dao.clearRead(id)
        assertThat(dao.get(id)).isNull()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `marking twice keeps the other field`() = runBlocking<Unit> {
        dao.markRead(id, accountId)
        dao.markFlag(id, accountId)
        dao.markFlag(id, accountId)

        assertThat(dao.get(id)!!.read).isTrue()
        assertThat(dao.getAll()).hasSize(1)
    }

    @Test
    fun `server values do not overwrite fields waiting to be sent, others do`() = runBlocking<Unit> {
        dao.markFlag(id, accountId)
        val emails = db.emailDao()

        emails.saveServerFlag(id, MailFlag.NONE)
        emails.updateReadStatusFromServer(id, false)

        val stored = emails.getEmail(id)!!
        assertThat(stored.flagged).isTrue()  // ждёт отправки
        assertThat(stored.read).isFalse()    // своё «прочитано» не менялось — значение сервера

        dao.clearFlag(id)
        emails.saveServerFlag(id, MailFlag.NONE)
        assertThat(emails.getEmail(id)!!.flagged).isFalse()
    }

    @Test
    fun `pending change moves with the email when its ServerId changes`() = runBlocking<Unit> {
        dao.markFlag(id, accountId)

        dao.rename(id, "${accountId}_2:77")

        assertThat(dao.get(id)).isNull()
        assertThat(dao.get("${accountId}_2:77")!!.flag).isTrue()
    }

    @Test
    fun `flag target projection reads the flag without the body`() = runBlocking<Unit> {
        val target = db.emailDao().getFlagTarget(id)!!

        assertThat(target.serverId).isEqualTo("2:1")
        assertThat(target.flag).isEqualTo(MailFlag(MailFlag.STATUS_ACTIVE))
    }

    @Test
    fun `deleting the account removes its queue`() = runBlocking<Unit> {
        dao.markFlag(id, accountId)

        db.accountDao().delete(accountId)

        assertThat(dao.count()).isEqualTo(0)
    }
}

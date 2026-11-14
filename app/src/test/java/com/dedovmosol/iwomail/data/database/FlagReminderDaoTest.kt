package com.dedovmosol.iwomail.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.eas.FolderType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Выборки напоминания по флагу несут accountId — тап переключает ящик, как календарь и задачи. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlagReminderDaoTest {

    private lateinit var db: MailDatabase
    private var accountId = 0L
    private val emailId get() = "${accountId}_2:9"
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        accountId = runBlocking {
            db.accountDao().insert(
                AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me")
            )
        }
        runBlocking {
            db.folderDao().insert(FolderEntity("${accountId}_2", accountId, "2", "Inbox", "0", FolderType.INBOX))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `flagged future reminder row includes the mailbox id`() = runBlocking<Unit> {
        insertMail(flagged = true, reminder = now + 60_000L)
        val row = db.emailDao().getFlagReminder(emailId)
        assertThat(row).isNotNull()
        assertThat(row!!.accountId).isEqualTo(accountId)
        assertThat(row.id).isEqualTo(emailId)
        assertThat(row.flagReminder).isEqualTo(now + 60_000L)

        val upcoming = db.emailDao().getFlagReminders(now, limit = 10)
        assertThat(upcoming.map { it.accountId }).containsExactly(accountId)
    }

    @Test
    fun `cleared flag is not a reminder`() = runBlocking<Unit> {
        insertMail(flagged = false, reminder = now + 60_000L)
        assertThat(db.emailDao().getFlagReminder(emailId)).isNull()
        assertThat(db.emailDao().getFlagReminders(now, 10)).isEmpty()
    }

    @Test
    fun `past reminder is not scheduled`() = runBlocking<Unit> {
        insertMail(flagged = true, reminder = now - 1L)
        assertThat(db.emailDao().getFlagReminder(emailId)!!.accountId).isEqualTo(accountId)
        assertThat(db.emailDao().getFlagReminders(now, 10)).isEmpty()
    }

    private suspend fun insertMail(flagged: Boolean, reminder: Long?) {
        db.emailDao().insert(
            EmailEntity(
                id = emailId,
                accountId = accountId,
                folderId = "${accountId}_2",
                serverId = "2:9",
                from = "boss@corp.example",
                to = "me@corp.example",
                subject = "flag",
                body = "",
                dateReceived = now,
                read = true,
                flagged = flagged,
                flagReminder = reminder
            )
        )
    }
}

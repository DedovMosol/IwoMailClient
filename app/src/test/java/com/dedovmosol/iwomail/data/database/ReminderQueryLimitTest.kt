package com.dedovmosol.iwomail.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.sync.BackgroundWork
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тысячи будущих строк не должны уходить в AlarmManager: выборка ближайших LIMIT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ReminderQueryLimitTest {

    private lateinit var db: MailDatabase
    private var accountId = 0L
    private val now = 1_700_000_000_000L
    private val cap = BackgroundWork.MAX_REMINDER_ALARMS

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
    fun `calendar reminder query keeps only the nearest cap among hundreds`() = runBlocking<Unit> {
        val rows = (0 until 80).map { i ->
            CalendarEventEntity(
                id = "${accountId}_ev$i",
                accountId = accountId,
                serverId = "ev$i",
                subject = "e$i",
                startTime = now + (i + 2) * 60_000L,
                endTime = now + (i + 3) * 60_000L,
                reminder = 1
            )
        }
        db.calendarEventDao().insertAll(rows)
        db.calendarEventDao().insert(
            CalendarEventEntity(
                id = "${accountId}_noise",
                accountId = accountId,
                serverId = "noise",
                subject = "deleted",
                startTime = now + 10_000L,
                endTime = now + 20_000L,
                reminder = 1,
                isDeleted = true
            )
        )
        val picked = db.calendarEventDao().getAllFutureEventsWithReminders(now, cap)
        assertThat(picked).hasSize(cap)
        assertThat(picked.map { it.serverId }).isEqualTo((0 until cap).map { "ev$it" })
    }

    @Test
    fun `task reminder query keeps only the nearest cap among hundreds`() = runBlocking<Unit> {
        val rows = (0 until 80).map { i ->
            TaskEntity(
                id = "${accountId}_t$i",
                accountId = accountId,
                serverId = "t$i",
                subject = "t$i",
                reminderSet = true,
                reminderTime = now + (i + 1) * 60_000L
            )
        }
        db.taskDao().insertAll(rows)
        val picked = db.taskDao().getTasksWithReminders(now, cap)
        assertThat(picked).hasSize(cap)
        assertThat(picked.map { it.serverId }).isEqualTo((0 until cap).map { "t$it" })
    }

    @Test
    fun `flag reminder query keeps only the nearest cap among hundreds`() = runBlocking<Unit> {
        val folderId = "${accountId}_2"
        val mails = (0 until 80).map { i ->
            EmailEntity(
                id = "${accountId}_m$i",
                accountId = accountId,
                folderId = folderId,
                serverId = "m$i",
                from = "a@b.c",
                to = "me@corp.example",
                subject = "m$i",
                body = "",
                dateReceived = now,
                read = true,
                flagged = true,
                flagReminder = now + (i + 1) * 60_000L
            )
        }
        db.emailDao().insertAllIgnore(mails)
        val picked = db.emailDao().getFlagReminders(now, cap)
        assertThat(picked).hasSize(cap)
        assertThat(picked.map { it.id }).isEqualTo((0 until cap).map { "${accountId}_m$it" })
    }
}

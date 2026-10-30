package com.dedovmosol.iwomail.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.eas.MailFlag
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Миграция 46 → 47 (флаг со сроком и напоминанием) на БД ровно по экспортированной схеме v46: у писем срока нет,
 * отметка «избранное» и остальные данные целы; новые поля пишутся и читаются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration46To47Test {

    private lateinit var context: Context
    private val dbName = "migration-46-47.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration adds flag dates and keeps flagged emails`() = runBlocking<Unit> {
        MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_46, version = 46,
            extraRows = listOf(
                "folders" to ContentValues().apply {
                    put("id", "1_2"); put("accountId", 1L); put("serverId", "2"); put("displayName", "Входящие"); put("type", 2)
                },
                "emails" to ContentValues().apply {
                    put("id", "1_2:1"); put("accountId", 1L); put("folderId", "1_2"); put("serverId", "2:1")
                    put("subject", "Отчёт"); put("body", "<p>Тело</p>"); put("flagged", 1)
                }
            )
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.emailDao()
            val email = requireNotNull(dao.getEmail("1_2:1"))
            assertThat(email.flagged).isTrue()
            assertThat(email.flagCompleted).isFalse()
            assertThat(email.flagDueDate).isNull()
            assertThat(email.flagReminder).isNull()
            assertThat(email.flag).isEqualTo(MailFlag(MailFlag.STATUS_ACTIVE))

            val future = System.currentTimeMillis() + 3_600_000L
            dao.saveFlag("1_2:1", MailFlag.due(1_000L, 2_000L, future))
            assertThat(dao.getEmail("1_2:1")!!.flag).isEqualTo(MailFlag(MailFlag.STATUS_ACTIVE, 1_000L, 2_000L, future))
            assertThat(dao.getFlagReminders(System.currentTimeMillis(), 50).map { it.id }).containsExactly("1_2:1")
            // Список писем (проекция без тела) — тоже со сроком
            assertThat(dao.getEmailSummariesByFolder("1_2").first().single().flagDueDate).isEqualTo(2_000L)

            // Выполнен: срок и напоминание убраны, напоминаний больше нет
            dao.saveFlag("1_2:1", MailFlag(MailFlag.STATUS_COMPLETE, 1_000L, 2_000L, future))
            val completed = dao.getEmail("1_2:1")!!
            assertThat(completed.flag).isEqualTo(MailFlag(MailFlag.STATUS_COMPLETE))
            assertThat(completed.flagDueDate).isNull()
            assertThat(dao.getFlagReminders(System.currentTimeMillis(), 50)).isEmpty()
            assertThat(dao.getFlagReminder("1_2:1")).isNull()
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertThat(raw.version).isAtLeast(47)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_46 = "/room-schemas/MailDatabase-46.json"
    }
}

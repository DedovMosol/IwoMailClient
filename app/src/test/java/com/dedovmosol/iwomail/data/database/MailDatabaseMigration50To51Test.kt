package com.dedovmosol.iwomail.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Миграция 50 → 51 (очередь правок задач и заметок без связи) на БД ровно по схеме v50: задачи, заметки и очередь
 * правок писем целы, новая таблица создана так, как её ждёт Room (Room сверяет схему при открытии), и работает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration50To51Test {

    private lateinit var context: Context
    private val dbName = "migration-50-51.db"

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
    fun `migration adds the task and note queue and keeps existing data`() = runBlocking<Unit> {
        val accountId = MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_50, version = 50,
            extraRows = listOf(
                "tasks" to ContentValues().apply {
                    put("id", "1_t1"); put("accountId", 1L); put("serverId", "t1"); put("subject", "Смета")
                },
                "notes" to ContentValues().apply {
                    put("id", "1_n1"); put("accountId", 1L); put("serverId", "n1"); put("subject", "Идеи"); put("body", "Текст")
                },
                "pending_email_changes" to ContentValues().apply {
                    put("emailId", "1_2:1"); put("accountId", 1L); put("flag", 1); put("read", 0)
                }
            )
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertThat(room.taskDao().getTask("1_t1")!!.subject).isEqualTo("Смета")
            assertThat(room.noteDao().getNote("1_n1")!!.body).isEqualTo("Текст")
            assertThat(room.pendingEmailChangeDao().get("1_2:1")!!.flag).isTrue()

            val queue = room.pendingPimChangeDao()
            assertThat(queue.count()).isEqualTo(0)
            queue.markTrash("1_t1", accountId, PimKind.TASK, "Смета")
            assertThat(queue.get("1_t1")!!.pendingTrash).isTrue()
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertThat(raw.version).isAtLeast(51)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_50 = "/room-schemas/MailDatabase-50.json"
    }
}

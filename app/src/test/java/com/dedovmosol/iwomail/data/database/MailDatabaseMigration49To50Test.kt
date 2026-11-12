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
 * Миграция 49 → 50 (очередь правок писем без связи) на БД ровно по схеме v49: письма целы, таблица очереди создана
 * так, как её ждёт Room (Room сверяет схему при открытии), и работает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration49To50Test {

    private lateinit var context: Context
    private val dbName = "migration-49-50.db"

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
    fun `migration adds the pending changes queue and keeps emails`() = runBlocking<Unit> {
        val accountId = MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_49, version = 49,
            extraRows = listOf(
                "folders" to ContentValues().apply {
                    put("id", "1_2"); put("accountId", 1L); put("serverId", "2"); put("displayName", "Входящие"); put("type", 2)
                },
                "emails" to ContentValues().apply {
                    put("id", "1_2:1"); put("accountId", 1L); put("folderId", "1_2"); put("serverId", "2:1")
                    put("subject", "Смета"); put("flagged", 1); put("conversationId", "AAQk")
                }
            )
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val email = requireNotNull(room.emailDao().getEmail("1_2:1"))
            assertThat(email.subject).isEqualTo("Смета")
            assertThat(email.flagged).isTrue()
            assertThat(email.conversationId).isEqualTo("AAQk")

            val queue = room.pendingEmailChangeDao()
            assertThat(queue.count()).isEqualTo(0)
            queue.markFlag("1_2:1", accountId)
            assertThat(queue.get("1_2:1")!!.flag).isTrue()
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // Дальше по цепочке миграций — до текущей версии.
            assertThat(raw.version).isAtLeast(50)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_49 = "/room-schemas/MailDatabase-49.json"
    }
}

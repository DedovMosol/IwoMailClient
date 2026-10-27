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
 * Миграция 44 → 45 (скрытые копии черновика) на БД ровно по экспортированной схеме v44 с черновиком:
 * у существующих писем скрытые копии не известны (null — на сервере их не трогаем), данные целы.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration44To45Test {

    private lateinit var context: Context
    private val dbName = "migration-44-45.db"

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
    fun `migration adds unknown bcc to existing drafts and keeps their data`() = runBlocking<Unit> {
        val accountId = MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_44, version = 44,
            extraRows = listOf(
                "folders" to ContentValues().apply {
                    put("id", "1_5"); put("accountId", 1L); put("serverId", "5"); put("displayName", "Черновики"); put("type", 3)
                },
                "emails" to ContentValues().apply {
                    put("id", "1_5:1"); put("accountId", 1L); put("folderId", "1_5"); put("serverId", "5:1")
                    put("to", "a@corp.example"); put("subject", "Отчёт"); put("body", "<p>Тело</p>"); put("importance", 2)
                }
            )
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Открытие запускает миграцию и сверку схемы Room (IllegalStateException при расхождении).
            val draft = requireNotNull(room.emailDao().getEmail("1_5:1"))
            assertThat(draft.accountId).isEqualTo(accountId)
            assertThat(draft.body).isEqualTo("<p>Тело</p>")
            assertThat(draft.importance).isEqualTo(2)
            assertThat(draft.bcc).isNull()

            room.emailDao().updateBcc("1_5:1", "hidden@corp.example")
            assertThat(room.emailDao().getEmail("1_5:1")?.bcc).isEqualTo("hidden@corp.example")
            // Список писем (без тела) тоже несёт новую колонку.
            assertThat(room.emailDao().getEmailsByFolderList("1_5").single().bcc).isEqualTo("hidden@corp.example")
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertThat(raw.version).isAtLeast(45)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_44 = "/room-schemas/MailDatabase-44.json"
    }
}

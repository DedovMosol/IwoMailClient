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
 * Миграции 51 → 52 → 53 → 54 → 55 на БД ровно по схеме каждой версии (замороженные копии в test resources).
 * Room при открытии сверяет таблицы, колонки, индексы и внешние ключи с текущими сущностями — любая расхождение
 * миграции со схемой 55 роняет открытие. Данные писем, очередей и папок сохраняются, новые колонки получают значения
 * по умолчанию, новая таблица снимка «Входящих» работает и удаляется вместе с папкой.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration51To55Test {

    private lateinit var context: Context
    private val dbName = "migration-51-55.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Папка, письмо и очередь правок письма — есть во всех версиях 51–54. */
    private fun baseRows(version: Int) = buildList {
        add("folders" to ContentValues().apply {
            put("id", "1_inbox"); put("accountId", 1L); put("serverId", "inbox"); put("displayName", "Входящие"); put("type", 2)
        })
        add("emails" to ContentValues().apply {
            put("id", "1_m1"); put("accountId", 1L); put("folderId", "1_inbox"); put("serverId", "m1")
            put("subject", "Отчёт"); put("from", "\"Петров\" <petrov@corp.local>")
            if (version >= 52) put("conversationIndex", "AQHXabc=")
            if (version >= 53) put("inReplyTo", "<orig@corp.local>")
        })
        add("pending_email_changes" to ContentValues().apply {
            put("emailId", "1_m1"); put("accountId", 1L); put("flag", 1); put("read", 0)
            if (version >= 54) { put("move", 1); put("targetFolderId", "1_trash") }
        })
        add("pending_pim_changes" to ContentValues().apply {
            put("itemId", "1_t1"); put("accountId", 1L); put("kind", "TASK"); put("pendingTrash", 1); put("serverSubject", "Смета")
            if (version >= 52) put("pendingCreate", 1)
        })
    }

    private fun migrateFrom(version: Int, check: suspend (MailDatabase) -> Unit) = runBlocking {
        context.deleteDatabase(dbName)
        MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, "/room-schemas/MailDatabase-$version.json", version = version, extraRows = baseRows(version)
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            check(room)
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertThat(raw.version).isEqualTo(55)
        } finally {
            raw.close()
        }
    }

    @Test
    fun `from 51 new columns get defaults and existing rows survive`() = migrateFrom(51) { room ->
        val email = room.emailDao().getEmail("1_m1")!!
        assertThat(email.subject).isEqualTo("Отчёт")
        assertThat(email.conversationIndex).isNull()
        assertThat(email.inReplyTo).isNull()
        val pendingMail = room.pendingEmailChangeDao().get("1_m1")!!
        assertThat(pendingMail.flag).isTrue()
        assertThat(pendingMail.move).isFalse()
        assertThat(pendingMail.targetFolderId).isEmpty()
        val pendingTask = room.pendingPimChangeDao().get("1_t1")!!
        assertThat(pendingTask.pendingTrash).isTrue()
        assertThat(pendingTask.pendingCreate).isFalse()
    }

    @Test
    fun `from 52 conversation index and queued create are kept`() = migrateFrom(52) { room ->
        assertThat(room.emailDao().getEmail("1_m1")!!.conversationIndex).isEqualTo("AQHXabc=")
        assertThat(room.pendingPimChangeDao().get("1_t1")!!.pendingCreate).isTrue()
        assertThat(room.emailDao().getEmail("1_m1")!!.inReplyTo).isNull()
    }

    @Test
    fun `from 53 In-Reply-To is kept`() = migrateFrom(53) { room ->
        assertThat(room.emailDao().getEmail("1_m1")!!.inReplyTo).isEqualTo("<orig@corp.local>")
        assertThat(room.pendingEmailChangeDao().get("1_m1")!!.move).isFalse()
    }

    @Test
    fun `from 54 queued move is kept and the Inbox snapshot table works and cascades with its folder`() = migrateFrom(54) { room ->
        val pending = room.pendingEmailChangeDao().get("1_m1")!!
        assertThat(pending.move).isTrue()
        assertThat(pending.targetFolderId).isEqualTo("1_trash")

        val snapshot = room.inboxSyncSnapshotDao()
        snapshot.insertMarker(InboxSyncSnapshotEntry("1_inbox", "", seen = false))
        assertThat(snapshot.getMarker("1_inbox")).isNotNull()
        // Внешний ключ на папку с ON DELETE CASCADE: папка удалена — снимок не остаётся сиротой
        room.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = '1_inbox'")
        assertThat(snapshot.getMarker("1_inbox")).isNull()
    }
}

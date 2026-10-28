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
 * Миграция 45 → 46 (черновик, заменяющий серверный) на БД ровно по экспортированной схеме v45: у
 * существующих черновиков отметки нет, скрытые копии и остальные данные целы.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration45To46Test {

    private lateinit var context: Context
    private val dbName = "migration-45-46.db"

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
    fun `migration adds the replaced-draft marker and keeps drafts`() = runBlocking<Unit> {
        MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_45, version = 45,
            extraRows = listOf(
                "folders" to ContentValues().apply {
                    put("id", "1_5"); put("accountId", 1L); put("serverId", "5"); put("displayName", "Черновики"); put("type", 3)
                },
                "emails" to ContentValues().apply {
                    put("id", "1_5:1"); put("accountId", 1L); put("folderId", "1_5"); put("serverId", "5:1")
                    put("subject", "Отчёт"); put("body", "<p>Тело</p>"); put("bcc", "boss@corp.example")
                }
            )
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val draft = requireNotNull(room.emailDao().getEmail("1_5:1"))
            assertThat(draft.bcc).isEqualTo("boss@corp.example")
            assertThat(draft.replacesServerId).isNull()
            assertThat(room.emailDao().getReplacedServerIds("1_5")).isEmpty()

            room.emailDao().updateReplacesServerId("1_5:1", "5:9")
            assertThat(room.emailDao().getReplacedServerIds("1_5")).containsExactly("5:9")
            assertThat(room.emailDao().getEmailsByFolderList("1_5").single().replacesServerId).isEqualTo("5:9")
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertThat(raw.version).isAtLeast(46)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_45 = "/room-schemas/MailDatabase-45.json"
    }
}

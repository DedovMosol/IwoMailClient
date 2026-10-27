package com.dedovmosol.iwomail.data.database

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
 * Миграция 42 → 43 (таблица category_colors) на БД, собранной ровно по экспортированной схеме v42,
 * с живыми данными. Открытие через Room с [MailDatabase.ALL_MIGRATIONS] без деструктивного fallback:
 * Room доводит БД до текущей версии и сверяет результат с её схемой — падает при расхождении.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration42To43Test {

    private lateinit var context: Context
    private val dbName = "migration-42-43.db"

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
    fun `migration creates category_colors with cascade and keeps existing data`() = runBlocking<Unit> {
        val accountId = MigrationTestSupport.createDatabaseWithAccount(context, dbName, SCHEMA_42, version = 42)
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Открытие запускает миграцию и проверку схемы Room (IllegalStateException при расхождении).
            assertThat(room.accountDao().getAccount(accountId)).isNotNull()
            room.categoryColorDao().upsert(CategoryColorEntity(accountId, "проект", "Проект", serverColor = 19))
            assertThat(room.categoryColorDao().getAll(accountId).single().serverColor).isEqualTo(19)
            room.accountDao().delete(accountId)
            assertThat(room.categoryColorDao().getAll(accountId)).isEmpty()
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // Дальше по цепочке миграций — до текущей версии.
            assertThat(raw.version).isAtLeast(43)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_42 = "/room-schemas/MailDatabase-42.json"
    }
}

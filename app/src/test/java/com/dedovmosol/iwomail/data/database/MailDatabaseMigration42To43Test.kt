package com.dedovmosol.iwomail.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Миграция 42 → 43 (таблица category_colors) на БД, собранной ровно по экспортированной схеме v42,
 * с живыми данными. Открытие через Room с [MailDatabase.ALL_MIGRATIONS] без деструктивного fallback:
 * Room сверяет результат миграции со схемой v43 и падает при расхождении.
 *
 * Схема v42 — замороженная копия экспорта Room в test resources (app/schemas в .gitignore и в CI
 * генерируется только текущая версия).
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

    /** Схема v42 из экспорта Room + аккаунт (все NOT NULL колонки заполнены нейтральными значениями). */
    private fun createVersion42WithAccount(): Long {
        val json = requireNotNull(javaClass.getResource(SCHEMA_42)) { "missing test resource $SCHEMA_42" }.readText()
        val schema = JSONObject(json).getJSONObject("database")
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        try {
            val entities = schema.getJSONArray("entities")
            var accountValues: ContentValues? = null
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                if (table == "accounts") accountValues = neutralRow(entity.getJSONArray("fields"))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 42
            return db.insertOrThrow("accounts", null, accountValues!!.apply { remove("id") })
        } finally {
            db.close()
        }
    }

    private fun neutralRow(fields: org.json.JSONArray): ContentValues = ContentValues().apply {
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            if (!field.optBoolean("notNull")) continue
            val column = field.getString("columnName")
            when (field.getString("affinity")) {
                "INTEGER" -> put(column, 0L)
                "REAL" -> put(column, 0.0)
                "BLOB" -> put(column, ByteArray(0))
                else -> put(column, "")
            }
        }
    }

    @Test
    fun `migration creates category_colors with cascade and keeps existing data`() = runBlocking<Unit> {
        val accountId = createVersion42WithAccount()

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
            assertThat(raw.version).isEqualTo(43)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_42 = "/room-schemas/MailDatabase-42.json"
    }
}

package com.dedovmosol.iwomail.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Миграция 48 → 49 (ConversationId для цепочек писем) на БД ровно по схеме v48: у прежних писем переписка не известна
 * (null), остальное цело; новое поле пишется и читается, в том числе списком без тела.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration48To49Test {

    private lateinit var context: Context
    private val dbName = "migration-48-49.db"

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
    fun `migration adds conversation id and keeps emails`() = runBlocking<Unit> {
        MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_48, version = 48,
            extraRows = listOf(
                "folders" to ContentValues().apply {
                    put("id", "1_2"); put("accountId", 1L); put("serverId", "2"); put("displayName", "Входящие"); put("type", 2)
                },
                "emails" to ContentValues().apply {
                    put("id", "1_2:1"); put("accountId", 1L); put("folderId", "1_2"); put("serverId", "2:1")
                    put("subject", "RE: Отчёт"); put("listUnsubscribe", "<mailto:u@list.example>"); put("requestReadReceipt", 1)
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
            assertThat(email.subject).isEqualTo("RE: Отчёт")
            assertThat(email.listUnsubscribe).isEqualTo("<mailto:u@list.example>")
            assertThat(email.requestReadReceipt).isTrue()
            assertThat(email.conversationId).isNull()

            dao.insert(email.copy(id = "1_2:2", serverId = "2:2", conversationId = "AAQkADAwATM="))
            assertThat(requireNotNull(dao.getEmail("1_2:2")).conversationId).isEqualTo("AAQkADAwATM=")

            // Список писем (проекция без тела) читает новое поле
            val summaries = dao.getEmailSummariesByFolder("1_2").first().associateBy { it.id }
            assertThat(summaries.getValue("1_2:1").conversationId).isNull()
            assertThat(summaries.getValue("1_2:2").conversationId).isEqualTo("AAQkADAwATM=")
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertThat(raw.version).isEqualTo(49)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_48 = "/room-schemas/MailDatabase-48.json"
    }
}

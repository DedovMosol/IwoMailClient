package com.dedovmosol.iwomail.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.eas.ReceiptRequests
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
 * Миграция 47 → 48 (отписка от рассылки, флажки «запросить отчёт» черновика) на БД ровно по схеме v47: у прежних
 * писем отписки нет, флажки черновиков не известны (null), остальное цело; новые поля пишутся и читаются,
 * в том числе списком без тела.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration47To48Test {

    private lateinit var context: Context
    private val dbName = "migration-47-48.db"

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
    fun `migration adds unsubscribe and receipt columns and keeps emails`() = runBlocking<Unit> {
        MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_47, version = 47,
            extraRows = listOf(
                "folders" to ContentValues().apply {
                    put("id", "1_3"); put("accountId", 1L); put("serverId", "3"); put("displayName", "Черновики"); put("type", 3)
                },
                "emails" to ContentValues().apply {
                    put("id", "1_3:1"); put("accountId", 1L); put("folderId", "1_3"); put("serverId", "3:1")
                    put("subject", "Черновик"); put("body", "<p>Тело</p>"); put("importance", 2); put("flagged", 1)
                }
            )
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = room.emailDao()
            val email = requireNotNull(dao.getEmail("1_3:1"))
            assertThat(email.subject).isEqualTo("Черновик")
            assertThat(email.importance).isEqualTo(2)
            assertThat(email.flagged).isTrue()
            assertThat(email.listUnsubscribe).isNull()
            assertThat(email.listUnsubscribeOneClick).isFalse()
            assertThat(ReceiptRequests.of(email.requestReadReceipt, email.requestDeliveryReceipt)).isNull()

            dao.updateListUnsubscribe("1_3:1", "<https://list.example/u>", oneClick = true)
            dao.updateReceiptRequests("1_3:1", read = true, delivery = false)
            val updated = requireNotNull(dao.getEmail("1_3:1"))
            assertThat(updated.listUnsubscribe).isEqualTo("<https://list.example/u>")
            assertThat(updated.listUnsubscribeOneClick).isTrue()
            assertThat(updated.requestReadReceipt).isTrue()
            assertThat(updated.requestDeliveryReceipt).isFalse()

            // Список писем (проекция без тела) читает новые поля
            val summary = dao.getEmailSummariesByFolder("1_3").first().single()
            assertThat(summary.requestReadReceipt).isTrue()
            assertThat(summary.listUnsubscribeOneClick).isTrue()
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertThat(raw.version).isAtLeast(48)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_47 = "/room-schemas/MailDatabase-47.json"
    }
}

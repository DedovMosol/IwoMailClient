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
 * Миграция 43 → 44 (пин сертификата резервного сервера) на БД ровно по экспортированной схеме v43 с
 * аккаунтом, у которого уже есть пин основного сервера: он сохраняется, у резервного пина нет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailDatabaseMigration43To44Test {

    private lateinit var context: Context
    private val dbName = "migration-43-44.db"

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
    fun `migration adds the backup server pin and keeps the primary pin`() = runBlocking<Unit> {
        val accountId = MigrationTestSupport.createDatabaseWithAccount(
            context, dbName, SCHEMA_43, version = 43,
            accountOverrides = ContentValues().apply {
                put("serverUrl", "mail.corp.example")
                put("alternateServerUrl", "10.0.0.5:8443")
                put("pinnedCertificateHash", "primary-spki")
                put("pinnedCertificateCN", "mail.corp.example")
                put("certificatePinningEnabled", 1)
            }
        )
        val room = Room.databaseBuilder(context, MailDatabase::class.java, dbName)
            .addMigrations(*MailDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            // Открытие запускает миграцию и сверку схемы Room (IllegalStateException при расхождении).
            val migrated = requireNotNull(room.accountDao().getAccount(accountId))
            assertThat(migrated.pinnedCertificateHash).isEqualTo("primary-spki")
            assertThat(migrated.alternateServerUrl).isEqualTo("10.0.0.5:8443")
            assertThat(migrated.alternatePinnedCertificateHash).isNull()

            room.accountDao().updateAlternatePinnedCertificate(accountId, "backup-spki", "10.0.0.5", "Corp", 1L, 2L)
            val pinned = requireNotNull(room.accountDao().getAccount(accountId))
            assertThat(pinned.alternatePinnedCertificateHash).isEqualTo("backup-spki")
            assertThat(pinned.alternatePinnedCertificateValidTo).isEqualTo(2L)
            assertThat(pinned.pinnedCertificateHash).isEqualTo("primary-spki")
        } finally {
            room.close()
        }
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // альше по цепочке миграций — до текущей версии.
            assertThat(raw.version).isAtLeast(44)
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val SCHEMA_43 = "/room-schemas/MailDatabase-43.json"
    }
}

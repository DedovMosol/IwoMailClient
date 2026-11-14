package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.PimKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Очередь правок задач и заметок ([com.dedovmosol.iwomail.data.database.PendingPimChangeDao]) и её отправка — на настоящей Room-БД. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingPimChangesTest {

    private lateinit var db: MailDatabase
    private var accountId = 0L
    private val queue get() = db.pendingPimChangeDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        accountId = runBlocking {
            db.accountDao().insert(AccountEntity(email = "me@corp.local", displayName = "Me", serverUrl = "https://x", username = "me"))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the server subject of the first edit is kept and every edit bumps the version`() = runBlocking<Unit> {
        queue.markUpdate("t1", accountId, PimKind.TASK, "Смета")
        queue.markUpdate("t1", accountId, PimKind.TASK, "Смета v2")
        queue.markTrash("t1", accountId, PimKind.TASK, "Смета v3")

        val change = queue.get("t1")!!
        assertThat(change.serverSubject).isEqualTo("Смета")
        assertThat(change.version).isEqualTo(3)
        assertThat(change.pendingUpdate).isTrue()
        assertThat(change.pendingTrash).isTrue()
    }

    @Test
    fun `clearing with a stale version keeps the newer edit`() = runBlocking<Unit> {
        queue.markUpdate("t1", accountId, PimKind.TASK, "Смета")
        val sent = queue.get("t1")!!
        queue.markUpdate("t1", accountId, PimKind.TASK, "Смета")

        queue.clearUpdate("t1", sent.version)

        assertThat(queue.get("t1")!!.pendingUpdate).isTrue()
        queue.clearUpdate("t1", queue.get("t1")!!.version)
        assertThat(queue.get("t1")).isNull()
    }

    @Test
    fun `cancelling an unsent trash keeps a pending update`() = runBlocking<Unit> {
        queue.markUpdate("n1", accountId, PimKind.NOTE, "Идеи")
        queue.markTrash("n1", accountId, PimKind.NOTE, "Идеи")

        assertThat(queue.cancelTrash("n1")).isTrue()
        assertThat(queue.get("n1")!!.pendingUpdate).isTrue()
        assertThat(queue.get("n1")!!.pendingTrash).isFalse()
        assertThat(queue.cancelTrash("n1")).isFalse()
    }

    @Test
    fun `flush resends an item edited during the pass and does not loop on a missing connection`() = runBlocking<Unit> {
        queue.markUpdate("t1", accountId, PimKind.TASK, "Смета")
        queue.markUpdate("n1", accountId, PimKind.NOTE, "Идеи")
        val sends = mutableListOf<Pair<String, Long>>()
        val flusher = PendingPimChanges(queue, send = { change ->
            sends += change.itemId to change.version
            when (change.itemId) {
                "t1" -> if (change.version == 1L) {
                    // Правка во время отправки: новая версия уходит в том же проходе
                    queue.markUpdate("t1", accountId, PimKind.TASK, "Смета")
                    queue.clearUpdate("t1", change.version)
                    PimSendOutcome.DONE
                } else {
                    queue.clearUpdate("t1", change.version)
                    PimSendOutcome.DONE
                }
                else -> PimSendOutcome.KEPT // связи нет — один раз за проход
            }
        })

        val empty = flusher.flush()

        assertThat(empty).isFalse()
        assertThat(sends).containsExactly("n1" to 1L, "t1" to 1L, "t1" to 2L)
        assertThat(queue.get("t1")).isNull()
        assertThat(queue.get("n1")).isNotNull()
    }

    @Test
    fun `one sync per account and kind after the pass, none when nothing was sent`() = runBlocking<Unit> {
        queue.markUpdate("t1", accountId, PimKind.TASK, "Смета")
        queue.markUpdate("t2", accountId, PimKind.TASK, "План")
        queue.markTrash("t3", accountId, PimKind.TASK, "Отчёт")
        queue.markUpdate("n1", accountId, PimKind.NOTE, "Идеи")
        val syncs = mutableListOf<Pair<Long, PimKind>>()

        PendingPimChanges(
            queue,
            send = { change ->
                if (change.kind == PimKind.NOTE) {
                    PimSendOutcome.KEPT
                } else {
                    queue.delete(change.itemId)
                    PimSendOutcome.DONE
                }
            },
            afterSent = { account, kind -> syncs += account to kind }
        ).flush()

        assertThat(syncs).containsExactly(accountId to PimKind.TASK)
    }

    @Test
    fun `flush of one account and kind leaves the others alone`() = runBlocking<Unit> {
        queue.markUpdate("t1", accountId, PimKind.TASK, "Смета")
        queue.markUpdate("n1", accountId, PimKind.NOTE, "Идеи")
        val sent = mutableListOf<String>()

        val empty = PendingPimChanges(queue, send = { change ->
            sent += change.itemId
            queue.clearUpdate(change.itemId, change.version)
            PimSendOutcome.DONE
        }).flush(accountId, PimKind.TASK)

        assertThat(empty).isTrue()
        assertThat(sent).containsExactly("t1")
        assertThat(queue.get("n1")).isNotNull()
    }
}

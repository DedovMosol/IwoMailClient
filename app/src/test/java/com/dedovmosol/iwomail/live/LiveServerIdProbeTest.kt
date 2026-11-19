package com.dedovmosol.iwomail.live

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.EmailOperationsService
import com.dedovmosol.iwomail.data.repository.EmailSyncService
import com.dedovmosol.iwomail.data.repository.RepositoryErrors
import com.dedovmosol.iwomail.data.repository.bodyBelongsTo
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasEmail
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.MailFlag
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exchange 2007 и ServerId — по трём жалобам с устройства: чужое тело, «удалено на сервере», отказ флага.
 * Сервер нумерует письма в порядке выдачи ("2:1"…), после SyncKey=0 — заново; Change сразу после сброса получает
 * Status 8. Отдельное партнёрство (deviceIdSuffix); почта не меняется, кроме флага одного письма — он возвращается.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveServerIdProbeTest {
    private val host = System.getenv("IWO_LIVE_HOST")
    private val domain = System.getenv("IWO_LIVE_DOMAIN").orEmpty()
    private val user = System.getenv("IWO_LIVE_USER")
    private val pass = System.getenv("IWO_LIVE_PASS")

    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var client: EasClient
    private val accountRepo = mockk<AccountRepository>(relaxed = true)
    private var accountId = 0L

    @Before
    fun setUp() {
        assumeTrue("live server not configured", !host.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(email = "$user@live.test", displayName = "Live", serverUrl = host!!, username = user!!, domain = domain)
        accountId = runBlocking { db.accountDao().insert(account) }
        client = EasClient(
            serverUrl = host, username = user, password = pass!!, domain = domain,
            acceptAllCerts = true, port = 443, useHttps = true, deviceIdSuffix = "iwo-probe-ids"
        )
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns client
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun println(s: String) = kotlin.io.println("[probe] $s")

    private suspend fun inbox(): String =
        (client.folderSync("0") as EasResult.Success).data.folders.first { it.type == FolderType.INBOX }.serverId

    /** Полный проход с нуля: письма по ServerId в порядке выдачи и последний ключ. */
    private suspend fun fullPass(col: String): Pair<LinkedHashMap<String, EasEmail>, String> {
        var key = (client.sync(col, "0", 50) as EasResult.Success).data.syncKey
        val map = LinkedHashMap<String, EasEmail>()
        while (true) {
            val p = (client.sync(col, key, 50) as EasResult.Success).data
            key = p.syncKey
            p.emails.forEach { map[it.serverId] = it }
            if (!p.moreAvailable) break
        }
        return map to key
    }

    private fun date(raw: String?) = raw?.let { EmailSyncService.parseDate(it) } ?: 0L

    private fun sync() = EmailSyncService(
        context, db.folderDao(), db.emailDao(), db.attachmentDao(), db.accountDao(), accountRepo, db
    )

    private fun ops(sync: EmailSyncService) =
        EmailOperationsService(context, db, db.folderDao(), db.emailDao(), db.attachmentDao(), accountRepo, sync)

    private fun row(id: String, col: String, like: EasEmail, body: String = "") = EmailEntity(
        id = "${accountId}_$id", accountId = accountId, folderId = "${accountId}_$col", serverId = id,
        from = like.from, to = like.to, subject = like.subject, body = body,
        dateReceived = date(like.dateReceived), read = true
    )

    @Test
    fun `ItemOperations names the item, so a body of another message under an old id is recognised`() = runBlocking<Unit> {
        val col = inbox()
        val (first, _) = fullPass(col)
        val (second, _) = fullPass(col)
        val shared = first.keys.intersect(second.keys)
        val sameIdOtherItem = shared.count { first[it]!!.subject != second[it]!!.subject || first[it]!!.dateReceived != second[it]!!.dateReceived }
        println("pass1=${first.size} pass2=${second.size} shared ids=${shared.size} same id → other message=$sameIdOtherItem")
        println("id shape: ${second.keys.take(3)} … ${second.keys.toList().takeLast(2)}")

        val ids = second.keys.toList()
        assumeTrue("inbox needs mail", ids.size >= 3)
        for (id in listOf(ids.first(), ids[ids.size / 2], ids.last())) {
            val item = second.getValue(id)
            val fetched = (client.fetchEmailBodyWithMdn(col, id, fetchMimeHeaders = false) as EasResult.Success).data
            // Тема и дата из ответа ItemOperations — того же письма, что Sync выдал под этим id
            if (item.subject != "(No subject)") assertThat(fetched.itemSubject).isEqualTo(item.subject)
            assertThat(date(fetched.itemDateReceived)).isEqualTo(date(item.dateReceived))
            assertThat(bodyBelongsTo(item.subject, date(item.dateReceived), fetched.itemSubject, date(fetched.itemDateReceived))).isTrue()
            // Запись о другом письме под этим id — тело не её
            val other = second.values.first { it.subject.trim() != item.subject.trim() }
            assertThat(bodyBelongsTo(other.subject, date(other.dateReceived), fetched.itemSubject, date(fetched.itemDateReceived))).isFalse()
        }

        // Сразу после сброса (одна страница) сервер прежних id ещё не знает — Status 6, а не чужое письмо
        val reset = (client.sync(col, "0", 50) as EasResult.Success).data.syncKey
        (client.sync(col, reset, 50) as EasResult.Success).data
        val r = client.fetchEmailBodyWithMdn(col, ids.last(), fetchMimeHeaders = false)
        println("body by an id not yet re-sent after a reset: $r")
        assertThat(r).isEqualTo(EasResult.Error("OBJECT_NOT_FOUND"))
    }

    @Test
    fun `full resync rewrites rows whose ids now name other messages, bodies follow their messages (app sync)`() = runBlocking<Unit> {
        val col = inbox()
        val folderId = "${accountId}_$col"
        val (server, _) = fullPass(col)
        val all = server.keys.toList()
        // Девять писем подряд в одном батче (по 50, как у приложения) с разными темами у соседей: перенос тела
        // сверяется в пределах батча, а одинаковые соседние темы (рассылки) не дали бы отличить сдвиг
        val start = (0..all.size - 9).firstOrNull { s ->
            s / 50 == (s + 8) / 50 && (s until s + 8).all { server.getValue(all[it]).subject != server.getValue(all[it + 1]).subject }
        }
        assumeTrue("inbox needs nine neighbours with different subjects", start != null)
        val ids = all.subList(start!!, start + 9)
        val m = ids.map { server.getValue(it) }
        println("window at ${ids.first()}…${ids.last()}")

        // Как на устройстве до исправления: сверху пришло письмо, номера сдвинулись, а записи остались прежними —
        // под id[i+1] лежит письмо m[i] со своим телом. Полная синхронизация (ключ "0") их встречает.
        db.folderDao().insert(FolderEntity(folderId, accountId, col, "Входящие", "0", FolderType.INBOX))
        for (i in 0..5) db.emailDao().insert(row(ids[i + 1], col, m[i], body = "LOCAL-BODY-$i"))

        val sync = sync()
        val result = sync.syncEmailsEas(accountId, folderId)
        println("app full resync: $result")
        assertThat(result).isInstanceOf(EasResult.Success::class.java)

        for (i in 0..6) {
            val stored = db.emailDao().getEmail("${accountId}_${ids[i]}")!!
            // Под каждым id — письмо, которое под ним на сервере
            assertThat(stored.subject).isEqualTo(m[i].subject)
            assertThat(stored.from).isEqualTo(m[i].from)
            // Тело — своего письма: m[0..5] свои тела унесли, у m[6] его не было
            assertThat(stored.body).isEqualTo(if (i <= 5) "LOCAL-BODY-$i" else "")
        }

        // Тело и защита на реальном сервере: своё письмо грузится, запись о другом письме под чужим id — нет
        val ops = ops(sync)
        val own = ops.loadEmailBody("${accountId}_${ids[6]}")
        assertThat(own).isInstanceOf(EasResult.Success::class.java)
        assertThat((own as EasResult.Success).data).isNotEmpty()

        db.emailDao().insert(row(ids[7], col, m[8]))
        val foreign = ops.loadEmailBody("${accountId}_${ids[7]}")
        println("body for a row describing another message: $foreign")
        assertThat(foreign).isEqualTo(EasResult.Error(RepositoryErrors.ITEM_MISMATCH))
        assertThat(db.emailDao().getEmail("${accountId}_${ids[7]}")!!.body).isEmpty()
        assertThat(EmailSyncService.takeStaleResync(folderId)).isTrue()
    }

    @Test
    fun `star through the app service is accepted, and with no sync key it waits instead of resetting the server`() = runBlocking<Unit> {
        val col = inbox()
        val folderId = "${accountId}_$col"
        val (items, key0) = fullPass(col)
        val target = items.values.firstOrNull { !it.flagged } ?: return@runBlocking
        db.folderDao().insert(FolderEntity(folderId, accountId, col, "Входящие", "0", FolderType.INBOX, syncKey = key0))
        val id = "${accountId}_${target.serverId}"
        db.emailDao().insert(row(target.serverId, col, target))
        val ops = ops(sync())
        try {
            // Звезда с действующим ключом: сервер принял, флаг на устройстве — тот, что подтвердил сервер
            val set = ops.toggleFlag(id)
            println("star via the service: $set")
            assertThat(set).isEqualTo(EasResult.Success(true))
            assertThat(db.emailDao().getEmail(id)!!.flagged).isTrue()
            assertThat(db.pendingEmailChangeDao().get(id)).isNull()
            val keyAfterStar = db.folderDao().getFolder(folderId)!!.syncKey

            // Ключа нет (папка ждёт полной синхронизации): звезда снимается на устройстве и ждёт, Sync(0) не уходит —
            // иначе следующий Change получил бы Status 8, а номера писем раздались бы заново
            db.folderDao().updateSyncKey(folderId, "0")
            val clear = ops.toggleFlag(id)
            assertThat(clear).isEqualTo(EasResult.Success(true))
            assertThat(db.emailDao().getEmail(id)!!.flagged).isFalse()
            assertThat(db.pendingEmailChangeDao().get(id)!!.flag).isTrue()
            // Состояние синхронизации на сервере не сброшено: прежний ключ действует
            val viaOldKey = client.setFlag(col, target.serverId, keyAfterStar, MailFlag.NONE)
            println("clear with the key from before: $viaOldKey")
            assertThat(viaOldKey).isInstanceOf(EasResult.Success::class.java)
            assertThat((viaOldKey as EasResult.Success).data.applied).isNotNull()
        } finally {
            val (now, k) = fullPass(col)
            if (now[target.serverId]?.flagged == true) println("restore: ${client.setFlag(col, target.serverId, k, MailFlag.NONE)}")
        }
    }
}

package com.dedovmosol.iwomail.live

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.EmailOperationsService
import com.dedovmosol.iwomail.data.repository.EmailSyncService
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.shared.MailSessionProperties
import com.dedovmosol.iwomail.util.LegacySanitizer
import javax.mail.Session
import com.dedovmosol.iwomail.util.sanitizeEmailHtml
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
 * Проверка исправлений на НАСТОЯЩЕМ Exchange (EAS 12.1) кодом приложения: EasClient, EmailOperationsService, санитайзер.
 *
 * Запускается только при заданных переменных окружения (иначе пропускается — CI и обычный прогон их не задают):
 *   IWO_LIVE_HOST, IWO_LIVE_DOMAIN, IWO_LIVE_USER, IWO_LIVE_PASS
 * Почта только читается: Sync без клиентских команд (в том числе сброс SyncKey=0 своего партнёрства устройства)
 * и ItemOperations Fetch. Календарный тест создаёт синтетическую серию «IWO live series …» на 2 недели вперёд
 * и удаляет её в finally. Отдельное партнёрство устройства (deviceIdSuffix), чтобы не трогать настоящее.
 * Сертификат сервера выпущен внутренним CA и подключение по IP — поэтому acceptAllCerts (только в этом тесте).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveExchangeTest {

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
        if (System.getenv("IWO_LIVE_LOG") != null) org.robolectric.shadows.ShadowLog.stream = System.out
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val account = AccountEntity(email = "$user@live.test", displayName = "Live", serverUrl = host!!, username = user!!, domain = domain)
        accountId = runBlocking { db.accountDao().insert(account) }
        client = EasClient(
            serverUrl = host, username = user, password = pass!!, domain = domain,
            acceptAllCerts = true, port = 443, useHttps = true, deviceIdSuffix = "iwo-live-test"
        )
        coEvery { accountRepo.getAccount(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepo.createEasClient(accountId) } returns client
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun println(s: String) = kotlin.io.println("[live] $s")

    private suspend fun inboxServerId(): String {
        val folders = (client.folderSync("0") as EasResult.Success).data.folders
        return folders.first { it.type == FolderType.INBOX }.serverId
    }

    /** Полный проход синхронизации папки с нуля: ServerId всех писем в порядке выдачи сервером. */
    private suspend fun fullPass(collectionId: String, stopAfterPages: Int = Int.MAX_VALUE): Pair<List<String>, String> {
        var key = (client.sync(collectionId, "0", 50) as EasResult.Success).data.syncKey
        val ids = mutableListOf<String>()
        var pages = 0
        while (pages < stopAfterPages) {
            val page = (client.sync(collectionId, key, 50) as EasResult.Success).data
            key = page.syncKey
            ids += page.emails.map { it.serverId }
            pages++
            if (!page.moreAvailable) break
        }
        return ids to key
    }

    @Test
    fun `PROTO-08 body request during a full resync keeps the email (real Exchange 2007)`() = runBlocking<Unit> {
        val inbox = inboxServerId()
        db.folderDao().insert(FolderEntity("${accountId}_$inbox", accountId, inbox, "Входящие", "0", FolderType.INBOX))
        val (all, _) = fullPass(inbox)
        assumeTrue("inbox needs mail", all.size > 60)
        val last = all.last() // приходит последним — до конца ресинка сервер о нём «не знает»
        val id = "${accountId}_$last"
        db.emailDao().insert(
            EmailEntity(
                id = id, accountId = accountId, folderId = "${accountId}_$inbox", serverId = last,
                from = "x@live.test", to = "y@live.test", subject = "live-${System.nanoTime()}", body = "",
                dateReceived = 1L, read = true
            )
        )
        val service = EmailOperationsService(context, db, db.folderDao(), db.emailDao(), db.attachmentDao(), accountRepo, mockk(relaxed = true))

        // Сброс состояния синхронизации на сервере (SyncKey=0) и одна страница — как при полной пересинхронизации
        val resetKey = (client.sync(inbox, "0", 50) as EasResult.Success).data.syncKey
        val firstPage = (client.sync(inbox, resetKey, 50) as EasResult.Success).data
        assertThat(firstPage.emails.map { it.serverId }).doesNotContain(last)

        val during = service.loadEmailBody(id)
        println("loadEmailBody during resync: $during")
        // Главное: письмо НЕ удалено и не спрятано от синхронизации (раньше — удаление + защита на 5 минут)
        assertThat(db.emailDao().getEmail(id)).isNotNull()
        assertThat(EmailSyncService.isDeletedByUser(id)).isFalse()

        // Ресинк завершён — тело приходит
        var key = firstPage.syncKey
        var more = firstPage.moreAvailable
        while (more) {
            val page = (client.sync(inbox, key, 50) as EasResult.Success).data
            key = page.syncKey
            more = page.moreAvailable
        }
        val after = service.loadEmailBody(id, forceReload = true)
        println("loadEmailBody after resync: ${(after as? EasResult.Success)?.data?.length} chars")
        assertThat(after).isInstanceOf(EasResult.Success::class.java)
        assertThat((after as EasResult.Success).data).isNotEmpty()
    }

    /** Как на Android: TLS открывает Conscrypt (BoringSSL), а не JSSE JVM, где TLS 1.0 и 3DES выключены политикой JDK. */
    private fun conscryptFirst() {
        if (java.security.Security.getProviders().firstOrNull()?.name != "Conscrypt") {
            com.dedovmosol.iwomail.network.TestConscrypt.uninstall()
            com.dedovmosol.iwomail.network.TestConscrypt.installFirst()
        }
    }

    private fun mailLogin(protocol: MailSessionProperties.Protocol, port: Int, useSsl: Boolean, acceptAllCerts: Boolean): String {
        val props = MailSessionProperties.build(protocol, host!!, port, useSsl, acceptAllCerts)
        val store = Session.getInstance(props).getStore(MailSessionProperties.storeProtocol(protocol, useSsl))
        store.connect(host, port, if (domain.isEmpty()) user else "$domain\\$user", pass)
        try {
            return if (protocol == MailSessionProperties.Protocol.IMAP) {
                "folders=" + store.defaultFolder.list("*").size
            } else {
                val inbox = store.getFolder("INBOX")
                inbox.open(javax.mail.Folder.READ_ONLY)
                try { "messages=" + inbox.messageCount } finally { inbox.close(false) }
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun `UI2-01 IMAP STARTTLS, IMAPS, POP3 STLS and POP3S sign in on Exchange 2007 with the app settings`() {
        conscryptFirst()
        val cases = listOf(
            Triple(MailSessionProperties.Protocol.IMAP, 143, false),
            Triple(MailSessionProperties.Protocol.IMAP, 993, true),
            Triple(MailSessionProperties.Protocol.POP3, 110, false),
            Triple(MailSessionProperties.Protocol.POP3, 995, true)
        )
        for ((protocol, port, ssl) in cases) {
            val result = mailLogin(protocol, port, ssl, acceptAllCerts = true)
            println("$protocol:$port ssl=$ssl -> $result")
            assertThat(result).isNotEmpty()
        }
    }

    @Test
    fun `control - with the provider defaults (no TLS 1_0 and 3DES settings) Exchange 2007 IMAPS does not connect`() {
        conscryptFirst()
        val props = MailSessionProperties.build(MailSessionProperties.Protocol.IMAP, host!!, 993, useSsl = true, acceptAllCerts = true)
        props.keys.filter { it.toString().endsWith(".ssl.protocols") || it.toString().endsWith(".ssl.ciphersuites") }.forEach { props.remove(it) }

        val error = runCatching {
            Session.getInstance(props).getStore("imaps").connect(host, 993, "$domain\\$user", pass)
        }.exceptionOrNull()

        println("IMAPS:993 provider defaults -> ${error?.javaClass?.simpleName}: ${error?.message}")
        assertThat(error).isNotNull()
    }

    @Test
    fun `UI2-02 without accept-all an internal-CA certificate on an IP address is refused before sign-in`() {
        conscryptFirst()
        for ((protocol, port, ssl) in listOf(
            Triple(MailSessionProperties.Protocol.IMAP, 143, false),
            Triple(MailSessionProperties.Protocol.IMAP, 993, true)
        )) {
            val error = runCatching { mailLogin(protocol, port, ssl, acceptAllCerts = false) }.exceptionOrNull()
            println("$protocol:$port ssl=$ssl strict -> ${error?.javaClass?.simpleName}: ${error?.message}")
            assertThat(error).isInstanceOf(javax.mail.MessagingException::class.java)
            // Отказ на сертификате, а не на входе: пароль серверу не передан
            assertThat(error!!.message.orEmpty()).doesNotContain("AUTHENTICATE")
            assertThat(error.message.orEmpty()).doesNotContain("LOGIN failed")
            // Пользователь видит причину — сертификат, а не «сервер без STARTTLS»
            val shown = com.dedovmosol.iwomail.data.repository.mailConnectionErrorMessage(error, protocol.name)
            println("shown to user: $shown")
            assertThat(shown).contains("Сертификат")
        }
    }

    @Test
    fun `EAS body with headers and the EWS fallback by Message-ID both return the body (Exchange 2007)`() = runBlocking<Unit> {
        assertThat(client.testConnection()).isInstanceOf(EasResult.Success::class.java)
        println("Exchange 2007: ${client.isExchange2007()}")
        val inbox = inboxServerId()
        val (ids, _) = fullPass(inbox, stopAfterPages = 1)
        var eas = 0
        var ews = 0
        for (serverId in ids.take(5)) {
            val res = client.fetchEmailBodyWithMdn(inbox, serverId, fetchMimeHeaders = true) as? EasResult.Success ?: continue
            if (res.data.body.isNotEmpty()) eas++
            val messageId = res.data.originalMessageId
            println("messageId known=${messageId != null}, shape=${messageId?.replace(Regex("[A-Za-z0-9]"), "x")}")
            if (messageId == null) continue
            val viaEws = client.fetchEmailBodyViaEws(
                subject = "", folder = com.dedovmosol.iwomail.eas.EwsMailFolder.Distinguished("inbox"), internetMessageId = messageId
            )
            println("EWS: ${if (viaEws is EasResult.Success) "Success len=${viaEws.data.body.length}" else viaEws}")
            if (viaEws is EasResult.Success && viaEws.data.body.isNotEmpty()) ews++
        }
        println("EAS bodies=$eas, EWS bodies by Message-ID=$ews of 5")
        assertThat(eas).isEqualTo(5)
        assertThat(ews).isEqualTo(5)
    }

    @Test
    fun `an occurrence changed on the server reaches the app through the EWS exception supplement (Exchange 2007)`() = runBlocking<Unit> {
        val hour = 3_600_000L
        val day = 24 * hour
        val subject = "IWO live series ${System.nanoTime()}"
        val start = (System.currentTimeMillis() / hour) * hour + 14 * day
        val created = client.createCalendarEvent(subject, start, start + hour, reminder = 0, recurrenceType = 0)
        println("create daily series: $created")
        assertThat(created).isInstanceOf(EasResult.Success::class.java)
        var serverId: String? = null
        try {
            val event = (client.syncCalendar() as EasResult.Success).data.events.first { it.subject == subject }
            serverId = event.serverId
            println("EAS: recurring=${event.isRecurring}, uid shape=${event.uid.replace(Regex("[A-Za-z0-9]"), "x").take(40)}…")
            assertThat(event.uid).isNotEmpty()

            val originalSecond = start + day
            val moved = originalSecond + 2 * hour
            val updated = client.updateSingleOccurrence(
                event.serverId, event.exceptions, originalSecond, subject, "$subject changed",
                moved, moved + hour, "Room live", "exception body", false, 0, 2, 0
            )
            println("update occurrence #2: $updated")
            assertThat(updated).isInstanceOf(EasResult.Success::class.java)

            val supplement = client.supplementRecurringExceptions(
                listOf(com.dedovmosol.iwomail.eas.EasCalendarService.RecurringEventInfo(event.uid, event.serverId, event.exceptions))
            )
            println("supplement: series updated=${supplement.size}")
            // EAS UID и EWS UID должны совпасть, иначе дополнение молча ничего не находит
            val json = org.json.JSONArray(supplement.getValue(event.uid))
            val ex = (0 until json.length()).map { json.getJSONObject(it) }.single { it.getLong("exceptionStartTime") == originalSecond }
            println("exception: start shift=${(ex.getLong("startTime") - originalSecond) / 60000} min, body=${ex.getString("body").take(40)}")
            assertThat(ex.getBoolean("deleted")).isFalse()
            assertThat(ex.getString("subject")).isEqualTo("$subject changed")
            assertThat(ex.getLong("startTime")).isEqualTo(moved)
            assertThat(ex.getLong("endTime")).isEqualTo(moved + hour)
            assertThat(ex.getString("location")).isEqualTo("Room live")
            assertThat(ex.getString("body")).contains("exception body")
        } finally {
            val sid = serverId ?: (client.syncCalendar() as? EasResult.Success)?.data?.events?.firstOrNull { it.subject == subject }?.serverId
            if (sid != null) println("cleanup delete series: ${client.deleteCalendarEvent(sid, isRecurringSeries = true)}")
        }
    }

    @Test
    fun `MAIL-03 linear sanitizer gives the same result as before on real mail bodies`() = runBlocking<Unit> {
        val inbox = inboxServerId()
        val (ids, _) = fullPass(inbox)
        var compared = 0
        var maxNewMs = 0L
        var maxOldMs = 0L
        for (serverId in ids.take(300)) {
            val body = (client.fetchEmailBodyWithMdn(inbox, serverId, fetchMimeHeaders = false) as? EasResult.Success)?.data?.body
                ?: continue
            if (body.isEmpty()) continue
            var t = System.nanoTime()
            val fresh = sanitizeEmailHtml(body)
            maxNewMs = maxOf(maxNewMs, (System.nanoTime() - t) / 1_000_000)
            t = System.nanoTime()
            val legacy = LegacySanitizer.sanitize(body)
            maxOldMs = maxOf(maxOldMs, (System.nanoTime() - t) / 1_000_000)
            assertThat(fresh).isEqualTo(legacy)
            compared++
        }
        println("bodies compared: $compared, worst time new=${maxNewMs} ms, legacy=${maxOldMs} ms")
        assertThat(compared).isGreaterThan(50)
    }
}

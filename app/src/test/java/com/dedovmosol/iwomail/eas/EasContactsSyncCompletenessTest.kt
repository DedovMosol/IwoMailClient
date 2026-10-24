package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Полнота снимка контактов — от HTTP до [ContactsSyncResult], без моков логики.
 *
 * Регрессия, которую закрываем: Status≠1 (кроме 3/12) и пустая страница с MoreAvailable сходили за
 * конец выдачи. Снимок помечался полным, и ContactRepository удалял контакты, которых сервер просто
 * не успел отдать (при Status на первой странице — все контакты Exchange).
 *
 * Путь как в проде: [EasContactsService] → [EasTransport] (реальный WBXML-кодек, OkHttp) → [FakeExchange].
 * EAS 12.1 (Exchange 2007) и 14.1 (Exchange 2010/2013) шлют один и тот же Sync, отличие только в версии.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasContactsSyncCompletenessTest {

    private fun service(exchange: FakeExchange, easVersion: String = "14.1"): EasContactsService {
        val transport = exchange.transport(easVersion)
        return EasContactsService(
            executeCommand = object : EasContactsService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = {
                EasResult.Success(FolderSyncResponse("1", listOf(EasFolder(CONTACTS, "Contacts", "0", FolderType.CONTACTS))))
            },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            getEasVersion = { transport.easVersion }
        )
    }

    private fun eas(easVersion: String = "14.1", route: (FakeExchange.Call) -> Reply): Pair<FakeExchange, EasContactsService> {
        val exchange = FakeExchange(route)
        return exchange to service(exchange, easVersion)
    }

    private fun EasResult<ContactsSyncResult>.snapshot(): ContactsSyncResult {
        assertThat(this).isInstanceOf(EasResult.Success::class.java)
        return (this as EasResult.Success).data
    }

    /** SyncKey=0 → K1, далее страницы по ключам K1, K2, …; [pages] — ответы по порядку. */
    private fun easPages(vararg pages: (FakeExchange.Call) -> Reply): (FakeExchange.Call) -> Reply = { call ->
        when (call.syncKey) {
            "0" -> Reply.Wbxml(EasSyncXml.initial("K1", CONTACTS))
            else -> {
                val index = call.syncKey!!.removePrefix("K").toInt() - 1
                pages.getOrNull(index)?.invoke(call) ?: Reply.Http(500)
            }
        }
    }

    private fun page(key: String, more: Boolean, vararg ids: String): (FakeExchange.Call) -> Reply = {
        Reply.Wbxml(EasSyncXml.page(key, CONTACTS, ids.map { id -> contact(id) }, more))
    }

    private fun contact(id: String) = EasSyncXml.contact(id, "Contact $id", "user${id.substringAfter(':')}@corp.local")

    @Test
    fun `all pages until MoreAvailable is exhausted give an authoritative snapshot`() = runBlocking<Unit> {
        val (exchange, service) = eas(route = easPages(
            page("K2", more = true, "c:1", "c:2"),
            page("K3", more = false, "c:3")
        ))

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts.map { it.easServerId }).containsExactly("c:1", "c:2", "c:3").inOrder()
        assertThat(snapshot.contacts.map { it.displayName }).containsExactly("Contact c:1", "Contact c:2", "Contact c:3").inOrder()
        assertThat(snapshot.contacts.first().email).isEqualTo("user1@corp.local")
        assertThat(snapshot.isAuthoritative).isTrue()
        assertThat(snapshot.isUnchanged).isFalse()
        assertThat(exchange.calls.map { it.syncKey }).containsExactly("0", "K1", "K2").inOrder()
        assertThat(exchange.calls.map { it.collectionId }.distinct()).containsExactly(CONTACTS)
    }

    @Test
    fun `Exchange 2007 (EAS 12_1) follows the same paging`() = runBlocking<Unit> {
        val (_, service) = eas(easVersion = "12.1", route = easPages(
            page("K2", more = true, "c:1"),
            page("K3", more = false, "c:2")
        ))

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts.map { it.easServerId }).containsExactly("c:1", "c:2").inOrder()
        assertThat(snapshot.isAuthoritative).isTrue()
    }

    @Test
    fun `collection Status 5 on the first page is an error, not an empty folder`() = runBlocking<Unit> {
        // До фикса: пустой «полный» снимок → репозиторий удалял все контакты Exchange.
        val (_, service) = eas(route = easPages({ Reply.Wbxml(EasSyncXml.status(5, CONTACTS)) }))

        val result = service.syncContacts()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("Status=5")
    }

    @Test
    fun `collection Status 8 mid-sync keeps fetched contacts, is not authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(route = easPages(
            page("K2", more = true, "c:1", "c:2"),
            { Reply.Wbxml(EasSyncXml.status(8, CONTACTS)) }
        ))

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts.map { it.easServerId }).containsExactly("c:1", "c:2")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `MoreAvailable without data is not authoritative`() = runBlocking<Unit> {
        // До фикса: пустая страница обнуляла moreAvailable, и снимок без c:2… считался полным.
        val (exchange, service) = eas(route = easPages(
            page("K2", more = true, "c:1"),
            page("K3", more = true)
        ))

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts.map { it.easServerId }).containsExactly("c:1")
        assertThat(snapshot.isAuthoritative).isFalse()
        assertThat(exchange.calls).hasSize(3) // без повторов по пустой странице
    }

    @Test
    fun `HTTP failure on a later page keeps fetched contacts but is not authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(route = easPages(
            page("K2", more = true, "c:1"),
            { Reply.Http(500) }
        ))

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts.map { it.easServerId }).containsExactly("c:1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `truncated WBXML of the last page is not taken for a complete page`() = runBlocking<Unit> {
        val lastPage = EasSyncXml.page("K3", CONTACTS, listOf(contact("c:2"), contact("c:3")), more = false)
        val (_, service) = eas(route = easPages(
            page("K2", more = true, "c:1"),
            { Reply.RawBytes(FakeExchange.truncatedWbxml(lastPage, keepBytes = WbxmlParser().generate(lastPage).size - 12)) }
        ))

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts.map { it.easServerId }).containsExactly("c:1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `failure of the initial Sync is an error`() = runBlocking<Unit> {
        val (_, service) = eas { Reply.Http(503) }

        assertThat(service.syncContacts()).isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `initial Sync without a SyncKey is an error, not a silent empty success`() = runBlocking<Unit> {
        // Status=12 на первичном Sync: ключа нет. Раньше — Success с пустым списком и «0 контактов».
        val (_, service) = eas { Reply.Wbxml(EasSyncXml.status(12, CONTACTS)) }

        val result = service.syncContacts()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("SyncKey")
    }

    @Test
    fun `empty Contacts folder confirmed by the server is an authoritative empty snapshot`() = runBlocking<Unit> {
        val (_, service) = eas(route = easPages(page("K2", more = false)))

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts).isEmpty()
        assertThat(snapshot.isAuthoritative).isTrue()
    }

    @Test
    fun `page cap with MoreAvailable still set is not authoritative`() = runBlocking<Unit> {
        val (exchange, service) = eas { call ->
            if (call.syncKey == "0") {
                Reply.Wbxml(EasSyncXml.initial("K1", CONTACTS))
            } else {
                val n = call.syncKey!!.removePrefix("K").toInt()
                Reply.Wbxml(EasSyncXml.page("K${n + 1}", CONTACTS, listOf(contact("c:$n")), more = true))
            }
        }

        val snapshot = service.syncContacts().snapshot()

        assertThat(snapshot.contacts).hasSize(10)
        assertThat(snapshot.isAuthoritative).isFalse()
        assertThat(exchange.calls).hasSize(11) // первичный Sync + 10 страниц
    }

    @Test
    fun `probe by the cached key without changes reports unchanged with a single request`() = runBlocking<Unit> {
        val (exchange, service) = eas { call ->
            when {
                call.syncKey == "0" -> Reply.Wbxml(EasSyncXml.initial("K1", CONTACTS))
                call.syncKey == "K1" -> Reply.Wbxml(EasSyncXml.page("K2", CONTACTS, listOf(contact("c:1")), more = false))
                call.syncKey == "K2" && call.windowSize == 1 -> Reply.Wbxml(EasSyncXml.page("K2", CONTACTS, emptyList(), more = false))
                else -> Reply.Http(500)
            }
        }
        assertThat(service.syncContacts().snapshot().isAuthoritative).isTrue()
        exchange.calls.clear()

        val second = service.syncContacts().snapshot()

        assertThat(second.isUnchanged).isTrue()
        assertThat(second.isAuthoritative).isFalse()
        assertThat(second.contacts).isEmpty()
        assertThat(exchange.calls.map { it.syncKey to it.windowSize }).containsExactly("K2" to 1)
    }

    @Test
    fun `probe that sees changes falls back to a full resync from SyncKey 0`() = runBlocking<Unit> {
        var generation = 1
        val (exchange, service) = eas { call ->
            when {
                call.syncKey == "0" -> Reply.Wbxml(EasSyncXml.initial("G${generation}K1", CONTACTS))
                call.windowSize == 1 -> Reply.Wbxml(EasSyncXml.page("P", CONTACTS, listOf(contact("c:9")), more = false))
                call.syncKey == "G1K1" -> Reply.Wbxml(EasSyncXml.page("G1K2", CONTACTS, listOf(contact("c:1")), more = false))
                call.syncKey == "G2K1" -> Reply.Wbxml(EasSyncXml.page("G2K2", CONTACTS, listOf(contact("c:1"), contact("c:9")), more = false))
                else -> Reply.Http(500)
            }
        }
        service.syncContacts().snapshot()
        generation = 2
        exchange.calls.clear()

        val second = service.syncContacts().snapshot()

        assertThat(second.contacts.map { it.easServerId }).containsExactly("c:1", "c:9")
        assertThat(second.isAuthoritative).isTrue()
        assertThat(exchange.calls.map { it.syncKey }).containsExactly("G1K2", "0", "G2K1").inOrder()
    }

    @Test
    fun `Status 3 mid-sync drops the key, so the next sync starts from SyncKey 0 without a probe`() = runBlocking<Unit> {
        val (exchange, service) = eas(route = easPages(
            page("K2", more = true, "c:1"),
            { Reply.Wbxml(EasSyncXml.status(3, CONTACTS)) }
        ))

        val first = service.syncContacts().snapshot()
        assertThat(first.contacts.map { it.easServerId }).containsExactly("c:1")
        assertThat(first.isAuthoritative).isFalse()
        exchange.calls.clear()

        service.syncContacts()

        // Недействительный ключ не вернулся в кэш: ни одной пробы с WindowSize=1.
        assertThat(exchange.calls.first().syncKey).isEqualTo("0")
        assertThat(exchange.calls.none { it.windowSize == 1 }).isTrue()
    }

    private companion object {
        const val CONTACTS = "contacts1"
    }
}

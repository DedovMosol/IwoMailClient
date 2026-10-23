package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Полнота снимка задач — от HTTP до [TasksSyncResult], без моков логики.
 *
 * Регрессия, которую закрываем: неполный синк (ошибка/обрыв/лимит на середине выдачи) возвращался
 * как обычный Success со списком, и TaskRepository удалял по нему реальные задачи. Теперь сервис
 * обязан сказать, полон ли снимок ([TasksSyncResult.isAuthoritative]).
 *
 * Путь как в проде: [EasTasksService] → [EasTransport] (реальный WBXML-кодек, OkHttp) → [FakeExchange].
 * Оба протокола: EAS 14.1 (Exchange 2010/2013) и EWS (Exchange 2007, EAS 12.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasTasksSyncCompletenessTest {

    private fun service(exchange: FakeExchange, easVersion: String): EasTasksService {
        val transport = exchange.transport(easVersion)
        return EasTasksService(EasTasksService.TasksServiceDependencies(
            executeEasCommand = object : EasTasksService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = { EasResult.Success(FolderSyncResponse("1", listOf(EasFolder(TASKS, "Tasks", "0", FolderType.TASKS)))) },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { XmlUtils.escape(it) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Success(transport.easVersion) },
            getTasksFolderId = { TASKS },
            getDeletedItemsFolderId = { "deleted" },
            performNtlmHandshake = { transport.performNtlmHandshake() },
            executeNtlmRequest = { request, action -> transport.executeNtlmRequest(request, action) },
            tryBasicAuthEws = { request, action -> transport.executeEwsWithAuth(request, action) },
            sendMail = { _, _, _, _, _, _ -> EasResult.Error("unused") }
        ))
    }

    private fun eas(route: (FakeExchange.Call) -> Reply): Pair<FakeExchange, EasTasksService> {
        val exchange = FakeExchange(route)
        return exchange to service(exchange, "14.1")
    }

    private fun EasResult<TasksSyncResult>.snapshot(): TasksSyncResult {
        assertThat(this).isInstanceOf(EasResult.Success::class.java)
        return (this as EasResult.Success).data
    }

    /** EAS-маршрут: SyncKey=0 → K1, далее страницы по ключам K1, K2, …; [pages] — ответы по порядку. */
    private fun easPages(vararg pages: (FakeExchange.Call) -> Reply): (FakeExchange.Call) -> Reply = { call ->
        when (call.syncKey) {
            "0" -> Reply.Wbxml(EasSyncXml.initial("K1", TASKS))
            else -> {
                val index = call.syncKey!!.removePrefix("K").toInt() - 1
                pages.getOrNull(index)?.invoke(call) ?: Reply.Http(500)
            }
        }
    }

    private fun page(key: String, more: Boolean, vararg ids: String): (FakeExchange.Call) -> Reply = {
        Reply.Wbxml(EasSyncXml.page(key, TASKS, ids.map { id -> EasSyncXml.task(id, "Subject $id") }, more))
    }

    // ───────────────────────── EAS 14.x ─────────────────────────

    @Test
    fun `EAS - all pages until MoreAvailable is exhausted give an authoritative snapshot`() = runBlocking<Unit> {
        val (exchange, service) = eas(easPages(
            page("K2", more = true, "t:1", "t:2"),
            page("K3", more = false, "t:3")
        ))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("t:1", "t:2", "t:3").inOrder()
        assertThat(snapshot.tasks.map { it.subject }).containsExactly("Subject t:1", "Subject t:2", "Subject t:3").inOrder()
        assertThat(snapshot.isAuthoritative).isTrue()
        assertThat(snapshot.unresolvedServerIds).isEmpty()
        // Запросы ушли по цепочке ключей: 0 → K1 → K2.
        assertThat(exchange.calls.map { it.syncKey }).containsExactly("0", "K1", "K2").inOrder()
        assertThat(exchange.calls.map { it.collectionId }.distinct()).containsExactly(TASKS)
    }

    @Test
    fun `EAS - HTTP failure on a later page keeps fetched tasks but is not authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(easPages(
            page("K2", more = true, "t:1", "t:2"),
            { Reply.Http(500) }
        ))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("t:1", "t:2")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EAS - dropped connection on a later page is not authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(easPages(
            page("K2", more = true, "t:1"),
            { Reply.Disconnect }
        ))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("t:1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EAS - truncated WBXML of the last page is not taken for a complete page`() = runBlocking<Unit> {
        // Последняя страница (без MoreAvailable) оборвана посреди второй задачи. До фикса декодер
        // отдавал незакрытый XML без MoreAvailable, и синк считался завершённым — t:3 «исчезала».
        val lastPage = EasSyncXml.page("K3", TASKS, listOf(EasSyncXml.task("t:2", "Two"), EasSyncXml.task("t:3", "Three")), more = false)
        val full = WbxmlParser().generate(lastPage)
        val (_, service) = eas(easPages(
            page("K2", more = true, "t:1"),
            { Reply.RawBytes(full.copyOf(full.size - 12)) }
        ))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("t:1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EAS - failure of the initial Sync is an error, not an empty folder`() = runBlocking<Unit> {
        // Раньше: Success(emptyList()) → репозиторий удалял все локальные задачи.
        val (_, service) = eas { Reply.Http(503) }

        val result = service.syncTasks()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `EAS - collection Status 5 on the first page is an error`() = runBlocking<Unit> {
        val (_, service) = eas(easPages({ Reply.Wbxml(EasSyncXml.status(5, TASKS)) }))

        val result = service.syncTasks()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("Status=5")
    }

    @Test
    fun `EAS - Status 3 mid-sync keeps fetched tasks, is not authoritative`() = runBlocking<Unit> {
        // MS-ASCMD: Status 3 = Invalid synchronization key, клиент обязан начать с SyncKey=0.
        val (_, service) = eas(easPages(
            page("K2", more = true, "t:1"),
            { Reply.Wbxml(EasSyncXml.status(3, TASKS)) }
        ))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("t:1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EAS - MoreAvailable without data is not authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(easPages(
            page("K2", more = true, "t:1"),
            page("K3", more = true)
        ))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("t:1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EAS - iteration cap with MoreAvailable still set is not authoritative`() = runBlocking<Unit> {
        // 50 страниц по одной задаче, и сервер всё ещё обещает продолжение.
        val (exchange, service) = eas { call ->
            if (call.syncKey == "0") {
                Reply.Wbxml(EasSyncXml.initial("K1", TASKS))
            } else {
                val n = call.syncKey!!.removePrefix("K").toInt()
                Reply.Wbxml(EasSyncXml.page("K${n + 1}", TASKS, listOf(EasSyncXml.task("t:$n", "Task $n")), more = true))
            }
        }

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks).hasSize(50)
        assertThat(snapshot.isAuthoritative).isFalse()
        assertThat(exchange.calls).hasSize(51) // первичный Sync + 50 страниц, без лишних запросов
    }

    @Test
    fun `EAS - empty Tasks folder confirmed by the server is an authoritative empty snapshot`() = runBlocking<Unit> {
        val (_, service) = eas(easPages(page("K2", more = false)))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks).isEmpty()
        assertThat(snapshot.isAuthoritative).isTrue()
    }

    // ───────────────────────── EWS (Exchange 2007, EAS 12.1) ─────────────────────────

    /** Исправный Exchange 2007: корневая папка, подпапок нет, одна задача в Deleted Items. */
    private fun healthyEws(
        rootFindItem: (FakeExchange.Call) -> Reply = {
            Reply.Text(EwsXml.findItem(EwsXml.idOnlyTask("T1") + EwsXml.idOnlyTask("T2"), total = 2, includesLast = true))
        },
        getItem: (FakeExchange.Call) -> Reply = { call ->
            Reply.Text(EwsXml.getItem(call.itemIds.map { EwsXml.task(it, "Task $it") }))
        },
        deepFindFolder: (FakeExchange.Call) -> Reply = { Reply.Text(EwsXml.findFolder()) },
        shallowFindFolder: (FakeExchange.Call) -> Reply = { Reply.Text(EwsXml.findFolder()) },
        byFolderId: (FakeExchange.Call) -> Reply = { Reply.Http(500) },
        deletedFindItem: (FakeExchange.Call) -> Reply = {
            Reply.Text(EwsXml.findItem(EwsXml.idOnlyTask("D1"), total = 1, includesLast = true))
        }
    ): (FakeExchange.Call) -> Reply = { call ->
        when {
            !call.isEws -> Reply.Http(500)
            call.command == "GetFolder" -> Reply.Text(EwsXml.getFolder())
            call.command == "GetItem" -> getItem(call)
            call.command == "FindFolder" && call.xml.contains("Traversal=\"Deep\"") -> deepFindFolder(call)
            call.command == "FindFolder" -> shallowFindFolder(call)
            call.command == "FindItem" && call.xml.contains("DistinguishedFolderId Id=\"deleteditems\"") -> deletedFindItem(call)
            call.command == "FindItem" && call.xml.contains("DistinguishedFolderId Id=\"tasks\"") -> rootFindItem(call)
            call.command == "FindItem" && call.xml.contains("<t:FolderId Id=") -> byFolderId(call)
            else -> Reply.Http(500)
        }
    }

    private fun ews(route: (FakeExchange.Call) -> Reply): Pair<FakeExchange, EasTasksService> {
        val exchange = FakeExchange(route)
        return exchange to service(exchange, "12.1")
    }

    @Test
    fun `EWS - complete root, discovery and Deleted Items give an authoritative snapshot`() = runBlocking<Unit> {
        val (_, service) = ews(healthyEws())

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.filter { !it.isDeleted }.map { it.serverId }).containsExactly("T1", "T2")
        assertThat(snapshot.tasks.filter { it.isDeleted }.map { it.serverId }).containsExactly("D1")
        assertThat(snapshot.tasks.first { it.serverId == "T1" }.subject).isEqualTo("Task T1")
        assertThat(snapshot.isAuthoritative).isTrue()
        assertThat(snapshot.unresolvedServerIds).isEmpty()
    }

    @Test
    fun `EWS - FindItem paging is followed to the page with IncludesLastItemInRange`() = runBlocking<Unit> {
        val (exchange, service) = ews(healthyEws(rootFindItem = { call ->
            when (call.offset) {
                0 -> Reply.Text(EwsXml.findItem(EwsXml.idOnlyTask("T1") + EwsXml.idOnlyTask("T2"), total = 3, includesLast = false, nextOffset = 2))
                2 -> Reply.Text(EwsXml.findItem(EwsXml.idOnlyTask("T3"), total = 3, includesLast = true, nextOffset = 3))
                else -> Reply.Http(500)
            }
        }))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.filter { !it.isDeleted }.map { it.serverId }).containsExactly("T1", "T2", "T3")
        assertThat(snapshot.isAuthoritative).isTrue()
        val rootPages = exchange.calls.filter { it.command == "FindItem" && it.xml.contains("DistinguishedFolderId Id=\"tasks\"") }
        assertThat(rootPages.map { it.offset }).containsExactly(0, 2).inOrder()
    }

    @Test
    fun `EWS - error ResponseClass on root FindItem is an error, not an empty folder`() = runBlocking<Unit> {
        // Раньше: break → пустой список → «папка пуста» → репозиторий удалял задачи.
        val (_, service) = ews(healthyEws(
            rootFindItem = { Reply.Text(EwsXml.error("FindItem", "ErrorServerBusy")) },
            deletedFindItem = { Reply.Text(EwsXml.findItem("", total = 0, includesLast = true)) }
        ))

        val result = service.syncTasks()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `EWS - HTML page from a reverse proxy with HTTP 200 is not an empty folder`() = runBlocking<Unit> {
        // Прокси с формой входа отвечает 200 + HTML. В нём нет ResponseClass="Error", и прежний код
        // принимал такую «страницу» за пустую папку.
        val loginPage = "<html><body><form action=\"/owa/auth.owa\">Sign in</form></body></html>"
        val (_, service) = ews(healthyEws(rootFindItem = { Reply.Text(loginPage) }))

        val snapshot = service.syncTasks().snapshot()

        // Deleted Items прочиталась, но корень — нет: данные есть, удалять по ним нельзя.
        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("D1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EWS - FindItem response cut off without an HTTP error is not a complete folder`() = runBlocking<Unit> {
        // Соединение закрыто без длины: успех и IncludesLastItemInRange="true" стоят в начале ответа,
        // обрезан только хвост с элементами. Без проверки конверта это выглядело полной папкой.
        val full = EwsXml.findItem(EwsXml.idOnlyTask("T1") + EwsXml.idOnlyTask("T2"), total = 2, includesLast = true)
        val cut = full.substring(0, full.indexOf("""Id="T2""""))
        val (_, service) = ews(healthyEws(rootFindItem = { Reply.Text(cut) }))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.filter { !it.isDeleted }).isEmpty()
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EWS - GetItem response cut off mid-body leaves the batch unresolved`() = runBlocking<Unit> {
        val (_, service) = ews(healthyEws(getItem = { call ->
            val full = EwsXml.getItem(call.itemIds.map { EwsXml.task(it, "Task $it") })
            if (call.itemIds.contains("T1")) Reply.Text(full.substring(0, full.indexOf("Body of Task T2") + 7))
            else Reply.Text(full)
        }))

        val snapshot = service.syncTasks().snapshot()

        // Ни одной задачи из обрубка (с усечённым телом) — обе ждут следующего синка, не удаляются.
        assertThat(snapshot.tasks.filter { !it.isDeleted }).isEmpty()
        assertThat(snapshot.unresolvedServerIds).containsExactly("T1", "T2")
        assertThat(snapshot.isAuthoritative).isTrue()
    }

    @Test
    fun `EWS - lost GetItem batch keeps ids as unresolved and the snapshot authoritative`() = runBlocking<Unit> {
        // FindItem подтвердил T1 и T2, но GetItem не дошёл: деталей нет, а удалять их нельзя.
        val (_, service) = ews(healthyEws(getItem = { call ->
            if (call.itemIds.contains("T1")) Reply.Disconnect
            else Reply.Text(EwsXml.getItem(call.itemIds.map { EwsXml.task(it, "Task $it") }))
        }))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("D1")
        assertThat(snapshot.unresolvedServerIds).containsExactly("T1", "T2")
        assertThat(snapshot.isAuthoritative).isTrue()
    }

    @Test
    fun `EWS - per-item GetItem error leaves only that item unresolved`() = runBlocking<Unit> {
        val (_, service) = ews(healthyEws(getItem = { call ->
            Reply.Text(EwsXml.getItem(call.itemIds.map { id -> if (id == "T2") null else EwsXml.task(id, "Task $id") }))
        }))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.filter { !it.isDeleted }.map { it.serverId }).containsExactly("T1")
        assertThat(snapshot.unresolvedServerIds).containsExactly("T2")
        assertThat(snapshot.isAuthoritative).isTrue()
    }

    @Test
    fun `EWS - failed subfolder discovery makes the snapshot non-authoritative`() = runBlocking<Unit> {
        // Сбой discovery раньше был неотличим от «подпапок нет» — задачи подпапок «исчезали».
        val (_, service) = ews(healthyEws(deepFindFolder = { Reply.Disconnect }))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.filter { !it.isDeleted }.map { it.serverId }).containsExactly("T1", "T2")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EWS - discovered task subfolder is synced and deduplicated with the root`() = runBlocking<Unit> {
        val (_, service) = ews(healthyEws(
            deepFindFolder = {
                Reply.Text(EwsXml.findFolder(EwsXml.folder("F-ROOT", "IPF.Task", "Tasks") + EwsXml.folder("F-WORK", "IPF.Task", "Work")))
            },
            byFolderId = { call ->
                when {
                    call.xml.contains("F-ROOT") -> Reply.Text(EwsXml.findItem(EwsXml.idOnlyTask("T1") + EwsXml.idOnlyTask("T2"), 2, includesLast = true))
                    call.xml.contains("F-WORK") -> Reply.Text(EwsXml.findItem(EwsXml.idOnlyTask("W1"), 1, includesLast = true))
                    else -> Reply.Http(500)
                }
            }
        ))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.filter { !it.isDeleted }.map { it.serverId }).containsExactly("T1", "T2", "W1")
        assertThat(snapshot.isAuthoritative).isTrue()
    }

    @Test
    fun `EWS - paging that stops without IncludesLastItemInRange is not authoritative`() = runBlocking<Unit> {
        val (_, service) = ews(healthyEws(rootFindItem = {
            Reply.Text(EwsXml.findItem(EwsXml.idOnlyTask("T1"), total = 5, includesLast = false))
        }))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.filter { !it.isDeleted }.map { it.serverId }).containsExactly("T1")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EWS - Deleted Items failure keeps active tasks but is not authoritative`() = runBlocking<Unit> {
        val (_, service) = ews(healthyEws(deletedFindItem = { Reply.Text(EwsXml.error("FindItem", "ErrorInternalServerError")) }))

        val snapshot = service.syncTasks().snapshot()

        assertThat(snapshot.tasks.map { it.serverId }).containsExactly("T1", "T2")
        assertThat(snapshot.isAuthoritative).isFalse()
    }

    @Test
    fun `EWS - everything unreachable is an error`() = runBlocking<Unit> {
        val (_, service) = ews { Reply.Disconnect }

        val result = service.syncTasks()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
    }

    private companion object {
        const val TASKS = "tasks1"
    }
}

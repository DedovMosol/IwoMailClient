package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Полнота снимка заметок — от HTTP до [NotesSyncResult], без моков логики.
 *
 * Регрессия, которую закрываем: сбой папки Notes глотался (Success со списком без активных), и
 * NoteRepository помечал удалёнными ВСЕ локальные заметки; сбой Deleted Items чистил локальную
 * корзину; EWS при сбое GetItem отдавал заметки с пустым телом. Признаков полноты два: активные и
 * корзина (окно Deleted Items без пагинации).
 *
 * Путь как в проде: [EasNotesService] → [EasTransport] (реальный WBXML-кодек, OkHttp) → [FakeExchange].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasNotesSyncCompletenessTest {

    private fun service(exchange: FakeExchange, easVersion: String): EasNotesService {
        val transport = exchange.transport(easVersion)
        return EasNotesService(EasNotesService.NotesServiceDependencies(
            executeEasCommand = object : EasNotesService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = {
                EasResult.Success(FolderSyncResponse("1", listOf(
                    EasFolder(NOTES, "Notes", "0", FolderType.NOTES),
                    EasFolder(DELETED, "Deleted Items", "0", FolderType.DELETED_ITEMS)
                )))
            },
            refreshSyncKey = { _, _ -> EasResult.Error("unused") },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { XmlUtils.escape(it) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Success(transport.easVersion) },
            getNotesFolderId = { NOTES },
            getDeletedItemsFolderId = { DELETED },
            performNtlmHandshake = { transport.performNtlmHandshake() },
            executeNtlmRequest = { request, action -> transport.executeNtlmRequest(request, action) },
            tryBasicAuthEws = { request, action -> transport.executeEwsWithAuth(request, action) },
            findEwsNoteItemId = { _, _ -> null }
        ))
    }

    private fun EasResult<NotesSyncResult>.snapshot(): NotesSyncResult {
        assertThat(this).isInstanceOf(EasResult.Success::class.java)
        return (this as EasResult.Success).data
    }

    private val NotesSyncResult.activeIds get() = notes.filter { !it.isDeleted }.map { it.serverId }
    private val NotesSyncResult.deletedIds get() = notes.filter { it.isDeleted }.map { it.serverId }

    // ───────────────────────── EAS 14.x ─────────────────────────

    /**
     * EAS-маршрут по папкам: SyncKey=0 → ключ "<папка>-1"; страница N запрашивается ключом "<папка>-N".
     * [notesPages] / [deletedPage] — ответы по порядку.
     */
    private fun easRoute(
        notesPages: List<(FakeExchange.Call) -> Reply>,
        deletedPage: (FakeExchange.Call) -> Reply = {
            Reply.Wbxml(EasSyncXml.page("$DELETED-2", DELETED, listOf(
                EasSyncXml.note("d:1", "Old note", "old body"),
                EasSyncXml.mail("d:2", "Deleted mail")
            ), more = false))
        }
    ): (FakeExchange.Call) -> Reply = { call ->
        val folder = call.collectionId
        when {
            call.command != "Sync" -> Reply.Http(500)
            call.syncKey == "0" -> Reply.Wbxml(EasSyncXml.initial("$folder-1", folder!!))
            folder == DELETED -> deletedPage(call)
            folder == NOTES -> {
                val index = call.syncKey!!.substringAfterLast('-').toInt() - 1
                notesPages.getOrNull(index)?.invoke(call) ?: Reply.Http(500)
            }
            else -> Reply.Http(500)
        }
    }

    private fun notesPage(next: Int, more: Boolean, vararg ids: String): (FakeExchange.Call) -> Reply = {
        Reply.Wbxml(EasSyncXml.page("$NOTES-$next", NOTES, ids.map { id -> EasSyncXml.note(id, "Note $id", "body of $id") }, more))
    }

    private fun eas(route: (FakeExchange.Call) -> Reply): Pair<FakeExchange, EasNotesService> {
        val exchange = FakeExchange(route)
        return exchange to service(exchange, "14.1")
    }

    @Test
    fun `EAS - full Notes paging and complete Deleted Items window are both authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(easRoute(listOf(
            notesPage(2, more = true, "n:1", "n:2"),
            notesPage(3, more = false, "n:3")
        )))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("n:1", "n:2", "n:3").inOrder()
        assertThat(snapshot.notes.first { it.serverId == "n:2" }.body).isEqualTo("body of n:2")
        // Письмо из Deleted Items отфильтровано — в корзину заметок попадает только IPM.StickyNote.
        assertThat(snapshot.deletedIds).containsExactly("d:1")
        assertThat(snapshot.isActiveAuthoritative).isTrue()
        assertThat(snapshot.isDeletedAuthoritative).isTrue()
        assertThat(snapshot.isUnchanged).isFalse()
    }

    @Test
    fun `EAS - Deleted Items window with MoreAvailable is not authoritative, Notes still is`() = runBlocking<Unit> {
        // Типичный ящик: в Deleted Items больше 100 элементов. Отсутствие заметки в первом окне
        // ничего не доказывает — корзину по нему чистить нельзя.
        val (_, service) = eas(easRoute(
            notesPages = listOf(notesPage(2, more = false, "n:1")),
            deletedPage = {
                Reply.Wbxml(EasSyncXml.page("$DELETED-2", DELETED, listOf(EasSyncXml.note("d:1", "Old", "old")), more = true))
            }
        ))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("n:1")
        assertThat(snapshot.deletedIds).containsExactly("d:1")
        assertThat(snapshot.isActiveAuthoritative).isTrue()
        assertThat(snapshot.isDeletedAuthoritative).isFalse()
    }

    @Test
    fun `EAS - Notes page failure mid-sync keeps fetched notes, active not authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(easRoute(listOf(
            notesPage(2, more = true, "n:1"),
            { Reply.Http(500) }
        )))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("n:1")
        assertThat(snapshot.isActiveAuthoritative).isFalse()
    }

    @Test
    fun `EAS - Notes failure before any note is an error, not an empty folder`() = runBlocking<Unit> {
        // Раньше ошибка активных глоталась: Success(только корзина) → все локальные заметки «удалены».
        val (_, service) = eas(easRoute(listOf({ Reply.Disconnect })))

        val result = service.syncNotes()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `EAS - Status 12 on the first Notes page is an error`() = runBlocking<Unit> {
        // MS-ASCMD: Status 12 = Folder hierarchy has changed.
        val (_, service) = eas(easRoute(listOf({ Reply.Wbxml(EasSyncXml.status(12, NOTES)) })))

        val result = service.syncNotes()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("Status=12")
    }

    @Test
    fun `EAS - truncated WBXML of the last Notes page is not authoritative`() = runBlocking<Unit> {
        val lastPage = EasSyncXml.page("$NOTES-3", NOTES, listOf(
            EasSyncXml.note("n:2", "Two", "second"),
            EasSyncXml.note("n:3", "Three", "third")
        ), more = false)
        val (_, service) = eas(easRoute(listOf(
            notesPage(2, more = true, "n:1"),
            { Reply.RawBytes(FakeExchange.truncatedWbxml(lastPage, keepBytes = WbxmlParser().generate(lastPage).size - 10)) }
        )))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("n:1")
        assertThat(snapshot.isActiveAuthoritative).isFalse()
    }

    @Test
    fun `EAS - Deleted Items failure does not fail the sync but is not authoritative`() = runBlocking<Unit> {
        val (_, service) = eas(easRoute(
            notesPages = listOf(notesPage(2, more = false, "n:1")),
            deletedPage = { Reply.Http(503) }
        ))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("n:1")
        assertThat(snapshot.deletedIds).isEmpty()
        assertThat(snapshot.isActiveAuthoritative).isTrue()
        assertThat(snapshot.isDeletedAuthoritative).isFalse()
    }

    @Test
    fun `EAS - probe without changes reports an unchanged snapshot`() = runBlocking<Unit> {
        // Первый синк кэширует SyncKey папки Notes; второй шлёт пробу (WindowSize=1) этим ключом.
        val (exchange, service) = eas { call ->
            when {
                call.windowSize == 1 -> Reply.Wbxml(EasSyncXml.page("$NOTES-2", NOTES, emptyList(), more = false))
                else -> easRoute(listOf(notesPage(2, more = false, "n:1")))(call)
            }
        }

        val first = service.syncNotes().snapshot()
        val second = service.syncNotes().snapshot()

        assertThat(first.isUnchanged).isFalse()
        assertThat(second.isUnchanged).isTrue()
        assertThat(second.notes).isEmpty()
        // Неизменный снимок ничего не разрешает удалять.
        assertThat(second.isActiveAuthoritative).isFalse()
        assertThat(second.isDeletedAuthoritative).isFalse()
        val probe = exchange.calls.last()
        assertThat(probe.windowSize).isEqualTo(1)
        assertThat(probe.syncKey).isEqualTo("$NOTES-2")
    }

    // ───────────────────────── EWS (Exchange 2007, EAS 12.1) ─────────────────────────

    private fun ewsRoute(
        notesFind: (FakeExchange.Call) -> Reply = {
            Reply.Text(EwsXml.findItem(EwsXml.note("N1", "First") + EwsXml.note("N2", "Second"), total = 2, includesLast = true))
        },
        deletedFind: (FakeExchange.Call) -> Reply = {
            Reply.Text(EwsXml.findItem(
                EwsXml.note("D1", "Trashed") + EwsXml.note("M1", "Mail", itemClass = "IPM.Note"),
                total = 2, includesLast = true
            ))
        },
        getItem: (FakeExchange.Call) -> Reply = { call ->
            Reply.Text(EwsXml.getItem(call.itemIds.map { id -> EwsXml.note(id, "Subject $id", body = "Body of $id") }))
        }
    ): (FakeExchange.Call) -> Reply = { call ->
        when {
            !call.isEws -> Reply.Http(500)
            call.command == "GetItem" -> getItem(call)
            call.command == "FindItem" && call.xml.contains("DistinguishedFolderId Id=\"deleteditems\"") -> deletedFind(call)
            call.command == "FindItem" && call.xml.contains("DistinguishedFolderId Id=\"notes\"") -> notesFind(call)
            else -> Reply.Http(500)
        }
    }

    private fun ews(route: (FakeExchange.Call) -> Reply): Pair<FakeExchange, EasNotesService> {
        val exchange = FakeExchange(route)
        return exchange to service(exchange, "12.1")
    }

    @Test
    fun `EWS - complete FindItem with GetItem bodies is authoritative for both folders`() = runBlocking<Unit> {
        val (_, service) = ews(ewsRoute())

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("N1", "N2").inOrder()
        assertThat(snapshot.notes.first { it.serverId == "N1" }.body).isEqualTo("Body of N1")
        assertThat(snapshot.deletedIds).containsExactly("D1")
        assertThat(snapshot.notes.first { it.serverId == "D1" }.body).isEqualTo("Body of D1")
        assertThat(snapshot.isActiveAuthoritative).isTrue()
        assertThat(snapshot.isDeletedAuthoritative).isTrue()
        assertThat(snapshot.unresolvedServerIds).isEmpty()
    }

    @Test
    fun `EWS - error on notes FindItem is an error, not an empty folder`() = runBlocking<Unit> {
        // Раньше любой сбой давал Success(emptyList()) → все локальные заметки помечались удалёнными.
        val (_, service) = ews(ewsRoute(notesFind = { Reply.Text(EwsXml.error("FindItem", "ErrorServerBusy")) }))

        val result = service.syncNotes()

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("ErrorServerBusy")
    }

    @Test
    fun `EWS - unreachable notes FindItem is an error`() = runBlocking<Unit> {
        val (_, service) = ews(ewsRoute(notesFind = { Reply.Disconnect }))

        assertThat(service.syncNotes()).isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `EWS - failed GetItem never yields notes with an empty body`() = runBlocking<Unit> {
        // FindItem тел не отдаёт. Раньше при сбое GetItem заметки уходили в БД с пустым телом
        // и затирали локальные. Теперь они «неразрешённые»: в снимке их нет, удалять их нельзя.
        val (_, service) = ews(ewsRoute(getItem = { call ->
            if (call.itemIds.contains("N1")) Reply.Disconnect
            else Reply.Text(EwsXml.getItem(call.itemIds.map { id -> EwsXml.note(id, "Subject $id", body = "Body of $id") }))
        }))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).isEmpty()
        assertThat(snapshot.unresolvedServerIds).containsExactly("N1", "N2")
        assertThat(snapshot.notes.none { it.body.isEmpty() }).isTrue()
        assertThat(snapshot.isActiveAuthoritative).isTrue() // FindItem полон: список ID надёжен
        assertThat(snapshot.deletedIds).containsExactly("D1")
    }

    @Test
    fun `EWS - notes FindItem cut off without an HTTP error is not authoritative`() = runBlocking<Unit> {
        // Успех и IncludesLastItemInRange="true" — в начале ответа; обрезан хвост, N2 не дошла.
        val full = EwsXml.findItem(EwsXml.note("N1", "First") + EwsXml.note("N2", "Second"), total = 2, includesLast = true)
        val cut = full.substring(0, full.indexOf("""Id="N2""""))
        val (_, service) = ews(ewsRoute(notesFind = { Reply.Text(cut) }))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("N1")
        assertThat(snapshot.isActiveAuthoritative).isFalse()
    }

    @Test
    fun `EWS - GetItem response cut off mid-body leaves notes unresolved`() = runBlocking<Unit> {
        val (_, service) = ews(ewsRoute(getItem = { call ->
            val full = EwsXml.getItem(call.itemIds.map { id -> EwsXml.note(id, "Subject $id", body = "Body of $id") })
            if (call.itemIds.contains("N1")) Reply.Text(full.substring(0, full.indexOf("Body of N2") + 4))
            else Reply.Text(full)
        }))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).isEmpty()
        assertThat(snapshot.unresolvedServerIds).containsExactly("N1", "N2")
    }

    @Test
    fun `EWS - per-item GetItem error leaves only that note unresolved`() = runBlocking<Unit> {
        val (_, service) = ews(ewsRoute(getItem = { call ->
            Reply.Text(EwsXml.getItem(call.itemIds.map { id -> if (id == "N2") null else EwsXml.note(id, "Subject $id", body = "Body of $id") }))
        }))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("N1")
        assertThat(snapshot.unresolvedServerIds).containsExactly("N2")
    }

    @Test
    fun `EWS - note with a really empty body is resolved, not unresolved`() = runBlocking<Unit> {
        val (_, service) = ews(ewsRoute(getItem = { call ->
            Reply.Text(EwsXml.getItem(call.itemIds.map { id -> EwsXml.note(id, "Subject $id", body = if (id == "N2") "" else "Body of $id") }))
        }))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("N1", "N2")
        assertThat(snapshot.unresolvedServerIds).isEmpty()
    }

    @Test
    fun `EWS - first 1000 notes without IncludesLastItemInRange are not authoritative`() = runBlocking<Unit> {
        val (_, service) = ews(ewsRoute(notesFind = {
            Reply.Text(EwsXml.findItem(EwsXml.note("N1", "First"), total = 1500, includesLast = false, nextOffset = 1000))
        }))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("N1")
        assertThat(snapshot.isActiveAuthoritative).isFalse()
    }

    @Test
    fun `EWS - Deleted Items failure keeps active notes and is not authoritative for the trash`() = runBlocking<Unit> {
        val (_, service) = ews(ewsRoute(deletedFind = { Reply.Text(EwsXml.error("FindItem", "ErrorInternalServerError")) }))

        val snapshot = service.syncNotes().snapshot()

        assertThat(snapshot.activeIds).containsExactly("N1", "N2")
        assertThat(snapshot.deletedIds).isEmpty()
        assertThat(snapshot.isActiveAuthoritative).isTrue()
        assertThat(snapshot.isDeletedAuthoritative).isFalse()
    }

    private companion object {
        const val NOTES = "notes1"
        const val DELETED = "deleted1"
    }
}

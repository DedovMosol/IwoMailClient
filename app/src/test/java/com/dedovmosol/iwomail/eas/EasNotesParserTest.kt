package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Характеризация разбора ответов [EasNotesService] ПЕРЕД миграцией regex → [EasXmlParser].
 *
 * Два пути (оба должны работать для Exchange 2007 SP2 … 2013):
 *  - EAS Sync (`parseNotesResponse`/`parseNoteFromXml`) — ГОЛЫЕ теги, как выдаёт [WbxmlParser.parse]
 *    (см. [EasCodePages]: getTagName возвращает имя без namespace-префикса; Notes — code page 23,
 *    AirSyncBase — 17). Поэтому в живом EAS-ответе нет `notes:`/`airsyncbase:`-префиксов, тело идёт
 *    как `<Body><Data>…</Data></Body>`. Notes-over-EAS — фича 14.0+, на 2007 SP2 заметки идут по EWS.
 *  - EWS (`parseEwsNotesResponse`) — реальные префиксы `t:`/`m:`.
 *
 * Контракт эквивалентности: тесты зелёные на СТАРОМ regex-коде → миграция → те же тесты зелёные
 * ⇒ разбор не изменился. Robolectric нужен, т.к. после миграции парсеры используют android.util.Xml.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasNotesParserTest {

    // Парсеры не ходят в сеть. extractValue инжектируем РЕАЛЬНЫЙ (XmlUtils.extractTagValue) —
    // так в проде (EasClient передаёт XmlUtils.extractTagValue); parseNoteFromXml берёт им ServerId.
    private fun service(): EasNotesService {
        val deps = EasNotesService.NotesServiceDependencies(
            executeEasCommand = object : EasNotesService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    EasResult.Error("unused in parse tests")
            },
            folderSync = { EasResult.Error("unused") },
            refreshSyncKey = { _, _ -> EasResult.Error("unused") },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { it },
            getEasVersion = { "14.1" },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Error("unused") },
            getNotesFolderId = { null },
            getDeletedItemsFolderId = { null },
            performNtlmHandshake = { null },
            executeNtlmRequest = { _, _ -> null },
            tryBasicAuthEws = { _, _ -> null },
            findEwsNoteItemId = { _, _ -> null }
        )
        return EasNotesService(deps)
    }

    private fun parseUtc(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(value)!!.time

    // ───────────────────────── EAS Sync (голые теги) ─────────────────────────

    @Test
    fun `parseNotesResponse maps Add and Change, body via Data, defaults, skips empty`() {
        // Заметка 1 (Add): все поля + экранированный '&' в теле + Categories ГОЛЫМ тегом.
        // Заметка 2 (Change): без MessageClass (→ дефолт) и без даты (→ now), '<>' в теме.
        // Заметка 3 (Add): только MessageClass → пустые тема и тело → пропуск.
        // Заметка 4 (Add): без ServerId → пропуск.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync><Collections><Collection><SyncKey>5</SyncKey><CollectionId>10</CollectionId><Status>1</Status>
<Commands>
<Add><ServerId>10:1</ServerId><ApplicationData>
<Subject>Shopping list</Subject>
<MessageClass>IPM.StickyNote</MessageClass>
<LastModifiedDate>2024-03-15T09:30:00.000Z</LastModifiedDate>
<Categories><Category>Personal</Category></Categories>
<Body><Type>1</Type><Data>Milk &amp; eggs</Data></Body>
</ApplicationData></Add>
<Add><ServerId>10:3</ServerId><ApplicationData><MessageClass>IPM.StickyNote</MessageClass></ApplicationData></Add>
<Add><ApplicationData><Subject>Orphan</Subject></ApplicationData></Add>
<Change><ServerId>10:2</ServerId><ApplicationData>
<Subject>Meeting &lt;notes&gt;</Subject>
<Body><Type>1</Type><Data>Discuss Q3</Data></Body>
</ApplicationData></Change>
</Commands></Collection></Collections></Sync>""".trimIndent()

        val before = System.currentTimeMillis()
        val notes = service().parseNotesResponse(xml)
        val after = System.currentTimeMillis()

        // Порядок: сначала все Add (10:1; 10:3 и orphan пропущены), затем Change (10:2).
        assertThat(notes.map { it.serverId }).containsExactly("10:1", "10:2").inOrder()

        val n1 = notes[0]
        assertThat(n1.subject).isEqualTo("Shopping list")
        assertThat(n1.body).isEqualTo("Milk & eggs")        // '&amp;' развёрнут (unescape НЕ no-op)
        assertThat(n1.messageClass).isEqualTo("IPM.StickyNote")
        assertThat(n1.lastModified).isEqualTo(parseUtc("2024-03-15T09:30:00"))
        // Пре-существующая особенность: ГОЛЫЙ <Categories> не матчится паттерном <notes:Categories>,
        // поэтому категории EAS-заметок сейчас НЕ извлекаются (фиксируем текущее поведение).
        assertThat(n1.categories).isEmpty()
        assertThat(n1.isDeleted).isFalse()

        val n2 = notes[1]
        assertThat(n2.subject).isEqualTo("Meeting <notes>")  // '&lt;&gt;' развёрнуты
        assertThat(n2.body).isEqualTo("Discuss Q3")
        assertThat(n2.messageClass).isEqualTo("IPM.StickyNote") // дефолт (тега не было)
        assertThat(n2.lastModified).isIn(before..after)         // даты не было → now
    }

    @Test
    fun `parseNotesResponse returns empty when no Add or Change`() {
        val xml = "<Sync><Collections><Collection><SyncKey>5</SyncKey><Status>1</Status></Collection></Collections></Sync>"
        assertThat(service().parseNotesResponse(xml)).isEmpty()
    }

    // ───────────────────────── EWS (префиксы t:/m:) ─────────────────────────

    @Test
    fun `parseEwsNotesResponse extracts items, filters by StickyNote, skips missing ItemId`() {
        // AAA (Message, IPM.StickyNote) — берём. BBB (Message, IPM.Note) — пропуск (не заметка).
        // CCC (PostItem, без ItemClass) — берём (пустой класс допустим). Последний — без ItemId → пропуск.
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
<soap:Body><m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage>
<m:RootFolder><t:Items>
<t:Message>
  <t:ItemId Id="AAA111" ChangeKey="CK1"/>
  <t:Subject>EWS Note One</t:Subject>
  <t:ItemClass>IPM.StickyNote</t:ItemClass>
  <t:Body BodyType="Text">EWS body &amp; one</t:Body>
</t:Message>
<t:Message>
  <t:ItemId Id="BBB222" ChangeKey="CK2"/>
  <t:Subject>Regular Mail</t:Subject>
  <t:ItemClass>IPM.Note</t:ItemClass>
  <t:Body BodyType="Text">should be skipped</t:Body>
</t:Message>
<t:PostItem>
  <t:ItemId Id="CCC333" ChangeKey="CK3"/>
  <t:Subject>Post Note</t:Subject>
  <t:Body BodyType="Text">post body</t:Body>
</t:PostItem>
<t:Message>
  <t:Subject>No Id Note</t:Subject>
  <t:ItemClass>IPM.StickyNote</t:ItemClass>
</t:Message>
</t:Items></m:RootFolder>
</m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse></soap:Body></soap:Envelope>""".trimIndent()

        val notes = service().parseEwsNotesResponse(xml)

        assertThat(notes.map { it.serverId }).containsExactly("AAA111", "CCC333").inOrder()

        val n1 = notes[0]
        assertThat(n1.subject).isEqualTo("EWS Note One")
        assertThat(n1.body).isEqualTo("EWS body & one")       // '&amp;' развёрнут
        assertThat(n1.messageClass).isEqualTo("IPM.StickyNote")

        val n2 = notes[1]
        assertThat(n2.subject).isEqualTo("Post Note")
        assertThat(n2.body).isEqualTo("post body")
        assertThat(n2.messageClass).isEqualTo("")             // ItemClass отсутствовал
    }

    @Test
    fun `parseEwsNotesResponse returns empty when no items`() {
        val xml = """<?xml version="1.0"?><soap:Envelope xmlns:soap="s" xmlns:t="t" xmlns:m="m">
<soap:Body><m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage>
<m:RootFolder><t:Items/></m:RootFolder>
</m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse></soap:Body></soap:Envelope>""".trimIndent()
        assertThat(service().parseEwsNotesResponse(xml)).isEmpty()
    }
}

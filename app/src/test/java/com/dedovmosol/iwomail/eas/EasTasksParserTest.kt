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
 * Характеризация разбора ответов [EasTasksService] ПЕРЕД миграцией regex → [EasXmlParser].
 *
 * Два пути (оба обязаны работать для Exchange 2007 SP2 … 2013):
 *  - EAS Sync (`parseTaskSyncResponse`/`parseTaskFromXml`) — ГОЛЫЕ теги (WBXML-декод, [EasCodePages]):
 *    Tasks code page 9, тело через AirSyncBase (page 17) `<Body><Data>…</Data></Body>`. Это путь,
 *    который МИГРИРУЕТСЯ на EasXmlParser.
 *  - EWS (`parseEwsTasksResponse`) — префиксы `t:`/`m:`. ОСТАЁТСЯ на regex (в нём позиционный
 *    fallback для нестандартной обёртки Exchange 2007 SP1, режущий произвольные подстроки —
 *    XmlPullParser их безопасно не разберёт). Тесты EWS здесь — регрессионный щит.
 *
 * Контракт эквивалентности: зелёные на СТАРОМ коде → миграция EAS → те же зелёные.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasTasksParserTest {

    // extractValue инжектируем РЕАЛЬНЫЙ (XmlUtils.extractTagValue) — как в проде (EasClient).
    private fun service(): EasTasksService {
        val deps = EasTasksService.TasksServiceDependencies(
            executeEasCommand = object : EasTasksService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    EasResult.Error("unused in parse tests")
            },
            folderSync = { EasResult.Error("unused") },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { it },
            getEasVersion = { "14.1" },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Error("unused") },
            getTasksFolderId = { null },
            performNtlmHandshake = { null },
            executeNtlmRequest = { _, _ -> null },
            tryBasicAuthEws = { _, _ -> null },
            sendMail = { _, _, _, _, _, _ -> EasResult.Error("unused") }
        )
        return EasTasksService(deps)
    }

    private fun utcMillis(value: String, pattern: String): Long =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)!!.time

    // ───────────────────────── EAS Sync (голые теги) — МИГРИРУЕМЫЙ путь ─────────────────────────

    @Test
    fun `parseTaskSyncResponse maps all fields, Add then Change order, body via Data`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync><Collections><Collection><SyncKey>5</SyncKey><CollectionId>12</CollectionId><Status>1</Status>
<Commands>
<Add><ServerId>12:1</ServerId><ApplicationData>
<Subject>Prepare report</Subject>
<Body><Type>1</Type><Data>Draft &amp; review</Data></Body>
<StartDate>2026-01-15T09:00:00.000Z</StartDate>
<DueDate>2026-01-20T17:00:00.000Z</DueDate>
<Complete>1</Complete>
<DateCompleted>2026-01-19T12:00:00.000Z</DateCompleted>
<Importance>2</Importance>
<Sensitivity>2</Sensitivity>
<ReminderSet>1</ReminderSet>
<ReminderTime>2026-01-20T08:00:00.000Z</ReminderTime>
<Categories><Category>Work</Category></Categories>
<OwnerId>Andrey</OwnerId>
</ApplicationData></Add>
<!-- OwnerId выше — НЕ элемент класса Tasks (MS-ASTASK): сервер его не присылает, а мы игнорируем. -->
<Change><ServerId>12:2</ServerId><ApplicationData>
<Subject>Call client</Subject>
</ApplicationData></Change>
</Commands></Collection></Collections></Sync>""".trimIndent()

        val tasks = service().parseTaskSyncResponse(xml)

        // Порядок: сначала <Add> (12:1), затем <Change> (12:2).
        assertThat(tasks.map { it.serverId }).containsExactly("12:1", "12:2").inOrder()

        val t1 = tasks[0]
        assertThat(t1.subject).isEqualTo("Prepare report")
        assertThat(t1.body).isEqualTo("Draft & review")             // Data, '&amp;' развёрнут
        assertThat(t1.startDate).isEqualTo(utcMillis("2026-01-15T09:00:00.000Z", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        assertThat(t1.dueDate).isEqualTo(utcMillis("2026-01-20T17:00:00.000Z", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        assertThat(t1.complete).isTrue()
        assertThat(t1.dateCompleted).isEqualTo(utcMillis("2026-01-19T12:00:00.000Z", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        assertThat(t1.importance).isEqualTo(2)
        assertThat(t1.sensitivity).isEqualTo(2)
        assertThat(t1.reminderSet).isTrue()
        assertThat(t1.reminderTime).isEqualTo(utcMillis("2026-01-20T08:00:00.000Z", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        // MS-ASTASK: у класса Tasks нет Owner — по EAS владелец не приходит, OwnerId игнорируется.
        assertThat(t1.owner).isEqualTo("")
        // Голый <Categories> (как выдаёт декодер) теперь извлекается (раньше терялся — баг исправлен).
        assertThat(t1.categories).containsExactly("Work")

        val t2 = tasks[1]
        assertThat(t2.subject).isEqualTo("Call client")
        assertThat(t2.body).isEqualTo("")
        assertThat(t2.startDate).isEqualTo(0L)
        assertThat(t2.dueDate).isEqualTo(0L)
        assertThat(t2.complete).isFalse()
        assertThat(t2.importance).isEqualTo(1)   // дефолт
        assertThat(t2.sensitivity).isEqualTo(0)  // дефолт
        assertThat(t2.reminderSet).isFalse()
        assertThat(t2.owner).isEqualTo("")
    }

    @Test
    fun `real WBXML round-trip - task fields and categories survive the actual decoder`() {
        // XML с префиксами → РЕАЛЬНЫЙ WbxmlParser.generate → РЕАЛЬНЫЙ parse (как в проде) → разбор.
        val wbxml = WbxmlParser()
        val sent = """<?xml version="1.0" encoding="utf-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks"><Collections><Collection><SyncKey>5</SyncKey><CollectionId>12</CollectionId><Status>1</Status><Commands>
<Add><ServerId>12:1</ServerId><ApplicationData><tasks:Subject>Prepare report</tasks:Subject><airsyncbase:Body><airsyncbase:Type>1</airsyncbase:Type><airsyncbase:Data>Draft &amp; review</airsyncbase:Data></airsyncbase:Body><tasks:Importance>2</tasks:Importance><tasks:Sensitivity>2</tasks:Sensitivity><tasks:Complete>1</tasks:Complete><tasks:DateCompleted>2026-01-19T12:00:00.000Z</tasks:DateCompleted><tasks:UtcDueDate>2026-01-20T17:00:00.000Z</tasks:UtcDueDate><tasks:ReminderSet>1</tasks:ReminderSet><tasks:ReminderTime>2026-01-20T08:00:00.000Z</tasks:ReminderTime><tasks:Categories><tasks:Category>Work</tasks:Category><tasks:Category>Urgent</tasks:Category></tasks:Categories></ApplicationData></Add>
</Commands></Collection></Collections></Sync>"""
        val decoded = wbxml.parse(wbxml.generate(sent))
        assertThat(decoded).doesNotContain("tasks:")

        val t = service().parseTaskSyncResponse(decoded).single()
        val iso = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        assertThat(t.serverId).isEqualTo("12:1")
        assertThat(t.subject).isEqualTo("Prepare report")
        assertThat(t.body).isEqualTo("Draft & review")
        assertThat(t.importance).isEqualTo(2)
        assertThat(t.sensitivity).isEqualTo(2)
        assertThat(t.complete).isTrue()
        assertThat(t.dateCompleted).isEqualTo(utcMillis("2026-01-19T12:00:00.000Z", iso))
        assertThat(t.dueDate).isEqualTo(utcMillis("2026-01-20T17:00:00.000Z", iso))   // UtcDueDate fallback
        assertThat(t.reminderSet).isTrue()
        assertThat(t.reminderTime).isEqualTo(utcMillis("2026-01-20T08:00:00.000Z", iso))
        assertThat(t.categories).containsExactly("Work", "Urgent").inOrder()
    }

    @Test
    fun `parseTaskSyncResponse uses UtcStartDate and UtcDueDate fallback`() {
        val xml = """<Sync><Commands><Add><ServerId>12:3</ServerId><ApplicationData>
<Subject>Utc fallback</Subject>
<UtcStartDate>2026-03-01T10:00:00.000Z</UtcStartDate>
<UtcDueDate>2026-03-05T10:00:00.000Z</UtcDueDate>
</ApplicationData></Add></Commands></Sync>""".trimIndent()

        val t = service().parseTaskSyncResponse(xml).single()
        assertThat(t.startDate).isEqualTo(utcMillis("2026-03-01T10:00:00.000Z", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        assertThat(t.dueDate).isEqualTo(utcMillis("2026-03-05T10:00:00.000Z", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
    }

    @Test
    fun `parseTaskSyncResponse skips item without ServerId and returns empty when no Add or Change`() {
        val noServerId = """<Sync><Commands><Add><ApplicationData><Subject>orphan</Subject></ApplicationData></Add></Commands></Sync>"""
        assertThat(service().parseTaskSyncResponse(noServerId)).isEmpty()

        val none = "<Sync><Collections><Collection><Status>1</Status></Collection></Collections></Sync>"
        assertThat(service().parseTaskSyncResponse(none)).isEmpty()
    }

    // ───────────────────────── EWS (t:/m:) — регрессионный щит ─────────────────────────

    @Test
    fun `parseEwsTasksResponse maps fields, status, importance, sensitivity, categories`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
<soap:Body><m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage>
<m:RootFolder><t:Items>
<t:Task>
  <t:ItemId Id="AAA111" ChangeKey="CK1"/>
  <t:Subject>EWS task one</t:Subject>
  <t:Body BodyType="Text">Body &amp; one</t:Body>
  <t:StartDate>2026-02-01T00:00:00Z</t:StartDate>
  <t:DueDate>2026-02-10T00:00:00Z</t:DueDate>
  <t:Importance>High</t:Importance>
  <t:Sensitivity>Private</t:Sensitivity>
  <t:Status>Completed</t:Status>
  <t:CompleteDate>2026-02-09T00:00:00Z</t:CompleteDate>
  <t:Categories><t:String>Work</t:String><t:String>Urgent</t:String></t:Categories>
  <t:Owner>Ivan Petrov</t:Owner>
</t:Task>
<t:Task>
  <t:ItemId Id="BBB222" ChangeKey="CK2"/>
  <t:Subject>EWS task two</t:Subject>
  <t:Importance>Low</t:Importance>
  <t:Status>NotStarted</t:Status>
</t:Task>
</t:Items></m:RootFolder>
</m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse></soap:Body></soap:Envelope>""".trimIndent()

        val tasks = service().parseEwsTasksResponse(xml)

        assertThat(tasks.map { it.serverId }).containsExactly("AAA111", "BBB222").inOrder()

        val t1 = tasks[0]
        assertThat(t1.subject).isEqualTo("EWS task one")
        assertThat(t1.body).isEqualTo("Body & one")
        assertThat(t1.startDate).isEqualTo(utcMillis("2026-02-01T00:00:00Z", "yyyy-MM-dd'T'HH:mm:ss'Z'"))
        assertThat(t1.dueDate).isEqualTo(utcMillis("2026-02-10T00:00:00Z", "yyyy-MM-dd'T'HH:mm:ss'Z'"))
        assertThat(t1.complete).isTrue()                 // Status=Completed
        assertThat(t1.dateCompleted).isEqualTo(utcMillis("2026-02-09T00:00:00Z", "yyyy-MM-dd'T'HH:mm:ss'Z'"))
        assertThat(t1.importance).isEqualTo(2)           // High
        assertThat(t1.sensitivity).isEqualTo(2)          // Private
        assertThat(t1.categories).containsExactly("Work", "Urgent").inOrder()
        assertThat(t1.owner).isEqualTo("Ivan Petrov")

        val t2 = tasks[1]
        assertThat(t2.subject).isEqualTo("EWS task two")
        assertThat(t2.body).isEqualTo("")
        assertThat(t2.complete).isFalse()                // NotStarted
        assertThat(t2.importance).isEqualTo(0)           // Low
        assertThat(t2.sensitivity).isEqualTo(0)
        assertThat(t2.categories).isEmpty()
        assertThat(t2.owner).isEqualTo("")
    }

    @Test
    fun `parseEwsTasksResponse deduplicates repeated ItemId`() {
        // Один и тот же ItemId в <t:Task> и в <t:Message> → должен попасть один раз (seenIds).
        val xml = """<soap:Envelope xmlns:soap="s" xmlns:t="t" xmlns:m="m"><soap:Body><t:Items>
<t:Task><t:ItemId Id="DUP1" ChangeKey="K"/><t:Subject>first</t:Subject></t:Task>
<t:Message><t:ItemId Id="DUP1" ChangeKey="K"/><t:Subject>dup</t:Subject></t:Message>
</t:Items></soap:Body></soap:Envelope>""".trimIndent()

        val tasks = service().parseEwsTasksResponse(xml)
        assertThat(tasks.map { it.serverId }).containsExactly("DUP1")
        assertThat(tasks.single().subject).isEqualTo("first")
    }
}

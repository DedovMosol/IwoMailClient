package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Протокол создания и правки встречи — от [EasCalendarService] до HTTP, без моков логики.
 *
 * Ключевое правило (MS-ASCMD, протоколы 2.5–14.1): сервер НЕ рассылает приглашения/обновления при
 * EAS Sync — это делает клиент. Поэтому встречу, о которой надо уведомить участников (организатор
 * изменил встречу или добавил людей), на Exchange 2010/2013 ведём через EWS UpdateItem/CreateItem с
 * SendMeetingInvitationsOrCancellations. Непубличная правка (участник, личное поле, appointment без
 * участников) остаётся на EAS Sync. На Exchange 2007 (EAS 12.1) всё и так идёт через EWS.
 *
 * Путь как в проде: [EasCalendarService] → [EasTransport] (реальный WBXML-кодек, OkHttp) → [FakeExchange].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasCalendarUpdateMeetingTest {

    private fun service(exchange: FakeExchange, easVersion: String): EasCalendarService {
        val transport = exchange.transport(easVersion)
        return EasCalendarService(EasCalendarService.CalendarServiceDependencies(
            executeEasCommand = object : EasCalendarService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    transport.executeEasCommand(command, xml, parser = parser)
            },
            folderSync = {
                EasResult.Success(FolderSyncResponse("1", listOf(EasFolder(CALENDAR, "Calendar", "0", FolderType.CALENDAR))))
            },
            refreshSyncKey = { _, key -> EasResult.Success(key) },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { XmlUtils.escape(it) },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Success(transport.easVersion) },
            performNtlmHandshake = { transport.performNtlmHandshake() },
            executeNtlmRequest = { request, action -> transport.executeNtlmRequest(request, action) },
            tryBasicAuthEws = { request, action -> transport.executeEwsWithAuth(request, action) },
            parseEasDate = { null }
        ))
    }

    /** EWS CalendarItem для FindItem: ItemId+ChangeKey, Subject и (для master) CalendarItemType. */
    private fun calItem(id: String = "AAMkCAL1", changeKey: String = "DwAAAB", start: String = START_ISO, type: String? = null): String =
        "<t:CalendarItem>" +
            """<t:ItemId Id="$id" ChangeKey="$changeKey"/>""" +
            "<t:Subject>Planning</t:Subject>" +
            (if (type != null) "<t:CalendarItemType>$type</t:CalendarItemType>" else "") +
            "<t:Start>$start</t:Start>" +
            "</t:CalendarItem>"

    /**
     * Комбинированный «сервер»: EAS Sync (продвижение ключа 0→K1→K2 и команда Add/Change) и EWS
     * (FindItem → UpdateItem/CreateItem). Повторяющийся master ищется FindItem без CalendarView.
     */
    private fun route(
        calendarItem: String = calItem(),
        recurringMaster: String? = null
    ): (FakeExchange.Call) -> Reply = { call ->
        when {
            call.isEws && call.command == "FindItem" -> {
                val wantsMaster = recurringMaster != null && !call.xml.contains("CalendarView")
                Reply.Text(EwsXml.findItem(if (wantsMaster) recurringMaster!! else calendarItem, total = 1, includesLast = true))
            }
            call.isEws && call.command == "UpdateItem" -> Reply.Text(EwsXml.success("UpdateItem"))
            call.isEws && call.command == "CreateItem" ->
                Reply.Text(EwsXml.success("CreateItem", """<t:CalendarItem><t:ItemId Id="NEW-EWS" ChangeKey="CK-NEW"/></t:CalendarItem>"""))
            !call.isEws && call.xml.contains("<Commands>") -> Reply.Wbxml(EasSyncXml.initial("K3", CALENDAR))
            !call.isEws && call.syncKey == "0" -> Reply.Wbxml(EasSyncXml.initial("K1", CALENDAR))
            !call.isEws && call.syncKey == "K1" -> Reply.Wbxml(EasSyncXml.page("K2", CALENDAR, emptyList(), more = false))
            else -> Reply.Http(500)
        }
    }

    private suspend fun EasCalendarService.update(
        meeting: MeetingEdit,
        startTime: Long = START,
        oldStartTime: Long = 0L,
        recurrenceType: Int = -1
    ): EasResult<String> = updateCalendarEvent(
        serverId = "1:5", subject = "Planning", startTime = startTime, endTime = startTime + 3_600_000L,
        location = "Room 1", body = "Agenda", meeting = meeting, oldSubject = "Planning",
        oldStartTime = oldStartTime, recurrenceType = recurrenceType
    )

    private class SentAttendee(val email: String?, val name: String?, val status: String?, val type: String?)

    private fun easAttendeesIn(xml: String): List<SentAttendee> =
        Regex("<Attendee>(.*?)</Attendee>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(m.groupValues[1])?.groupValues?.get(1)
            SentAttendee(tag("Email"), tag("Name"), tag("AttendeeStatus"), tag("AttendeeType"))
        }.toList()

    private fun meetingStatusIn(xml: String): String? = Regex("<MeetingStatus>(\\d+)</MeetingStatus>").find(xml)?.groupValues?.get(1)

    private fun FakeExchange.easChange() = calls.single { !it.isEws && it.xml.contains("<Change>") }.xml
    private fun FakeExchange.ewsUpdate() = calls.single { it.isEws && it.command == "UpdateItem" }.xml
    private fun FakeExchange.hasEasCall() = calls.any { !it.isEws }
    private fun FakeExchange.hasEwsCall() = calls.any { it.isEws }

    private val serverAttendees = listOf(EasAttendee("anna@corp.local", "Anna", 3), EasAttendee("boris@corp.local", "Boris", 4))

    // ───────────────────────── EAS 14.x (Exchange 2010/2013): маршрутизация ─────────────────────────

    @Test
    fun `EAS 14 - organizer meeting change routes through EWS and mails all attendees`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = true, meetingStatus = 1, sendUpdates = true)

        val result = service(exchange, "14.1").update(meeting)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        val update = exchange.ewsUpdate()
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToAllAndSaveCopy"""")
        assertThat(update).contains("""<t:ItemId Id="AAMkCAL1" ChangeKey="DwAAAB"/>""")
        assertThat(update).doesNotContain("AppendToItemField") // состав не менялся — только рассылка изменения
        assertThat(exchange.hasEasCall()).isFalse()            // ни одного EAS Sync Change
    }

    @Test
    fun `EAS 14 - organizer adding an attendee routes through EWS and appends only the new one`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())
        val meeting = MeetingEdit(
            attendees = serverAttendees + EasAttendee("new@corp.local"),
            isOrganizer = true, meetingStatus = 1, sendUpdates = true, newAttendees = listOf("new@corp.local")
        )

        service(exchange, "14.1").update(meeting)

        val update = exchange.ewsUpdate()
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToAllAndSaveCopy"""")
        assertThat(Regex("<t:AppendToItemField>").findAll(update).count()).isEqualTo(1)
        assertThat(update).contains("<t:EmailAddress>new@corp.local</t:EmailAddress>")
        assertThat(update).doesNotContain("anna@corp.local") // уже на встрече — повторно не шлём
        assertThat(exchange.hasEasCall()).isFalse()
    }

    @Test
    fun `EAS 14 - organizer editing only a private field stays on EAS and mails nobody`() = runBlocking<Unit> {
        // Личная правка (напоминание/занятость) рассылки не требует → остаётся на EAS Sync Change.
        val exchange = FakeExchange(route())
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = true, meetingStatus = 1, sendUpdates = false)

        service(exchange, "14.1").update(meeting)

        val change = exchange.easChange()
        assertThat(meetingStatusIn(change)).isEqualTo("1")
        assertThat(easAttendeesIn(change).map { it.email }).containsExactly("anna@corp.local", "boris@corp.local").inOrder()
        assertThat(exchange.hasEwsCall()).isFalse()
    }

    @Test
    fun `EAS 14 - attendee edit stays on EAS with the received MeetingStatus`() = runBlocking<Unit> {
        // Участник не рассылает и не меняет состав: EAS Change с серверным MeetingStatus (бит R).
        val exchange = FakeExchange(route())
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = false, meetingStatus = 3)

        service(exchange, "14.1").update(meeting)

        val change = exchange.easChange()
        assertThat(meetingStatusIn(change)).isEqualTo("3")
        assertThat(easAttendeesIn(change).map { it.status }).containsExactly("3", "4").inOrder()
        assertThat(exchange.hasEwsCall()).isFalse()
    }

    @Test
    fun `EAS 14 - organizer appointment without attendees stays on EAS`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())

        service(exchange, "14.1").update(MeetingEdit())

        val change = exchange.easChange()
        assertThat(meetingStatusIn(change)).isEqualTo("0")
        assertThat(change).doesNotContain("<Attendees>")
        assertThat(exchange.hasEwsCall()).isFalse()
    }

    @Test
    fun `EAS 14 - invalid stored attendee status is sent as unknown on the EAS path`() = runBlocking<Unit> {
        // MS-ASCAL AttendeeStatus: только 0, 2, 3, 4, 5. sendUpdates=false → остаётся на EAS.
        val exchange = FakeExchange(route())

        service(exchange, "14.1").update(MeetingEdit(attendees = listOf(EasAttendee("x@corp.local", "X", 1)), isOrganizer = true))

        assertThat(easAttendeesIn(exchange.easChange()).single().status).isEqualTo("0")
        assertThat(exchange.hasEwsCall()).isFalse()
    }

    @Test
    fun `EAS 14 - organizer recurring series edit routes through EWS on the master item`() = runBlocking<Unit> {
        // CalendarView вернул бы вхождение; серию адресует master (FindItem без CalendarView).
        val exchange = FakeExchange(route(recurringMaster = calItem(id = "MASTER1", changeKey = "CKM", type = "RecurringMaster")))
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = true, meetingStatus = 1, sendUpdates = true)

        service(exchange, "14.1").update(meeting, recurrenceType = 1)

        val find = exchange.calls.single { it.isEws && it.command == "FindItem" }
        assertThat(find.xml).doesNotContain("CalendarView")
        assertThat(exchange.ewsUpdate()).contains("""<t:ItemId Id="MASTER1" ChangeKey="CKM"/>""")
        assertThat(exchange.hasEasCall()).isFalse()
    }

    @Test
    fun `EAS 14 - meeting edit resolves the item by the old start, not the new one`() = runBlocking<Unit> {
        // Сервер держит событие на СТАРОМ времени; FindItem по новому промахнулся бы (окно и 5-мин фильтр).
        val exchange = FakeExchange(route()) // calItem.Start = START (старое)
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = true, meetingStatus = 1, sendUpdates = true)
        val newStart = START + 10L * 24 * 3600 * 1000 // +10 дней

        service(exchange, "14.1").update(meeting, startTime = newStart, oldStartTime = START)

        val find = exchange.calls.single { it.isEws && it.command == "FindItem" }.xml
        assertThat(find).contains("2026-11-01T10:00:00Z")  // окно ±1 день вокруг СТАРОГО начала
        assertThat(find).doesNotContain("2026-11-11")       // не вокруг нового
        // Элемент нашёлся по старому времени → UpdateItem на реальном ItemId, а не на исходном "1:5".
        assertThat(exchange.ewsUpdate()).contains("""<t:ItemId Id="AAMkCAL1" ChangeKey="DwAAAB"/>""")
    }

    @Test
    fun `EAS 14 - organizer creating a meeting with attendees routes through EWS`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())

        val result = service(exchange, "14.1").createCalendarEvent(
            subject = "Planning", startTime = START, endTime = START + 3_600_000L,
            attendees = listOf("anna@corp.local", "boris@corp.local")
        )

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        val create = exchange.calls.single { it.isEws && it.command == "CreateItem" }.xml
        assertThat(create).contains("""SendMeetingInvitations="SendToAllAndSaveCopy"""")
        assertThat(create).contains("<EmailAddress>anna@corp.local</EmailAddress>")
        assertThat(create).contains("<EmailAddress>boris@corp.local</EmailAddress>")
        assertThat(exchange.hasEasCall()).isFalse() // ни одного EAS Sync Add
    }

    @Test
    fun `EAS 14 - creating an appointment without attendees stays on EAS`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())

        service(exchange, "14.1").createCalendarEvent(subject = "Planning", startTime = START, endTime = START + 3_600_000L)

        val add = exchange.calls.single { !it.isEws && it.xml.contains("<Add>") }.xml
        assertThat(meetingStatusIn(add)).isEqualTo("0")
        assertThat(add).doesNotContain("<Attendees>")
        assertThat(exchange.hasEwsCall()).isFalse()
    }

    // ───────────────────────── EWS (Exchange 2007, EAS 12.1) ─────────────────────────

    @Test
    fun `Exchange 2007 - organizer meeting change mails all and appends only new attendees`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())
        val meeting = MeetingEdit(
            attendees = serverAttendees + EasAttendee("new@corp.local"),
            isOrganizer = true, meetingStatus = 1, sendUpdates = true, newAttendees = listOf("new@corp.local")
        )

        val result = service(exchange, "12.1").update(meeting)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        val update = exchange.ewsUpdate()
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToAllAndSaveCopy"""")
        assertThat(update).contains("""<t:ItemId Id="AAMkCAL1" ChangeKey="DwAAAB"/>""")
        assertThat(Regex("<t:AppendToItemField>").findAll(update).count()).isEqualTo(1)
        assertThat(update).contains("<t:EmailAddress>new@corp.local</t:EmailAddress>")
    }

    @Test
    fun `Exchange 2007 - organizer reminder-only edit mails nobody`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = true, meetingStatus = 1, sendUpdates = false)

        service(exchange, "12.1").update(meeting)

        val update = exchange.ewsUpdate()
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToNone"""")
        assertThat(update).doesNotContain("AppendToItemField")
    }

    @Test
    fun `Exchange 2007 - attendee edit is saved without mailing anyone`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = false, meetingStatus = 3)

        service(exchange, "12.1").update(meeting)

        val update = exchange.ewsUpdate()
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToNone"""")
        assertThat(update).doesNotContain("AppendToItemField")
    }

    private companion object {
        const val CALENDAR = "cal1"
        const val START_ISO = "2026-11-02T10:00:00Z"
        val START: Long = Instant.parse(START_ISO).toEpochMilli()
    }
}

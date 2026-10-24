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
 * Что уходит на сервер при правке встречи — от [EasCalendarService] до HTTP, без моков логики.
 *
 * EAS 14.1 (Exchange 2010/2013): Sync Change. Без airsync:Supported ничего не ghosted, коллекция
 * Attendees заменяется целиком, а MeetingStatus участника обязан сохранить бит R (MS-ASCAL), иначе
 * Exchange сочтёт пользователя организатором чужой встречи.
 * EAS 12.1 (Exchange 2007): правка идёт EWS UpdateItem; рассылка — SendMeetingInvitationsOrCancellations.
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

    /** EAS: SyncKey=0 → K1, продвижение K1 → K2, команда (Add/Change) по K2 → Status=1. */
    private val easRoute: (FakeExchange.Call) -> Reply = { call ->
        when {
            call.isEws -> Reply.Http(500)
            call.xml.contains("<Commands>") -> Reply.Wbxml(EasSyncXml.initial("K3", CALENDAR))
            call.syncKey == "0" -> Reply.Wbxml(EasSyncXml.initial("K1", CALENDAR))
            call.syncKey == "K1" -> Reply.Wbxml(EasSyncXml.page("K2", CALENDAR, emptyList(), more = false))
            else -> Reply.Http(500)
        }
    }

    /** EWS (Exchange 2007): FindItem находит событие, UpdateItem — успех. */
    private val ewsRoute: (FakeExchange.Call) -> Reply = { call ->
        when {
            !call.isEws -> Reply.Http(500)
            call.command == "FindItem" -> Reply.Text(EwsXml.findItem(
                """<t:CalendarItem><t:ItemId Id="AAMkCAL1" ChangeKey="DwAAAB"/><t:Subject>Planning</t:Subject>""" +
                    "<t:Start>$START_ISO</t:Start></t:CalendarItem>",
                total = 1, includesLast = true
            ))
            call.command == "UpdateItem" -> Reply.Text(EwsXml.success("UpdateItem"))
            else -> Reply.Http(500)
        }
    }

    private suspend fun EasCalendarService.update(meeting: MeetingEdit): EasResult<String> = updateCalendarEvent(
        serverId = "1:5", subject = "Planning", startTime = START, endTime = START + 3_600_000L,
        location = "Room 1", body = "Agenda", meeting = meeting, oldSubject = "Planning"
    )

    private class SentAttendee(val email: String?, val name: String?, val status: String?, val type: String?)

    private fun attendeesIn(xml: String): List<SentAttendee> =
        Regex("<Attendee>(.*?)</Attendee>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(m.groupValues[1])?.groupValues?.get(1)
            SentAttendee(tag("Email"), tag("Name"), tag("AttendeeStatus"), tag("AttendeeType"))
        }.toList()

    private fun meetingStatusIn(xml: String): String? = Regex("<MeetingStatus>(\\d+)</MeetingStatus>").find(xml)?.groupValues?.get(1)

    private val serverAttendees = listOf(EasAttendee("anna@corp.local", "Anna", 3), EasAttendee("boris@corp.local", "Boris", 4))

    // ───────────────────────── EAS 14.1 ─────────────────────────

    @Test
    fun `EAS - organizer edit sends MeetingStatus 1 and attendees with their names and responses`() = runBlocking<Unit> {
        val exchange = FakeExchange(easRoute)
        val meeting = MeetingEdit(
            attendees = serverAttendees + EasAttendee("new@corp.local"),
            isOrganizer = true, meetingStatus = 1, sendUpdates = true, newAttendees = listOf("new@corp.local")
        )

        val result = service(exchange, "14.1").update(meeting)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        val change = exchange.calls.single { it.xml.contains("<Change>") }.xml
        assertThat(meetingStatusIn(change)).isEqualTo("1")
        val sent = attendeesIn(change)
        assertThat(sent.map { it.email }).containsExactly("anna@corp.local", "boris@corp.local", "new@corp.local").inOrder()
        assertThat(sent.map { it.name }).containsExactly("Anna", "Boris", null).inOrder() // нового имени нет — Name не шлём
        assertThat(sent.map { it.status }).containsExactly("3", "4", "0").inOrder()       // ответы не сбрасываются
        assertThat(sent.map { it.type }.distinct()).containsExactly("1")
    }

    @Test
    fun `EAS - attendee edit keeps the server MeetingStatus with the received bit`() = runBlocking<Unit> {
        // До фикса: MeetingStatus=1 («пользователь — организатор») для копии чужой встречи.
        val exchange = FakeExchange(easRoute)
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = false, meetingStatus = 3)

        service(exchange, "14.1").update(meeting)

        val change = exchange.calls.single { it.xml.contains("<Change>") }.xml
        assertThat(meetingStatusIn(change)).isEqualTo("3")
        assertThat(attendeesIn(change).map { it.email }).containsExactly("anna@corp.local", "boris@corp.local").inOrder()
    }

    @Test
    fun `EAS - organizer appointment without attendees stays an appointment`() = runBlocking<Unit> {
        val exchange = FakeExchange(easRoute)

        service(exchange, "14.1").update(MeetingEdit())

        val change = exchange.calls.single { it.xml.contains("<Change>") }.xml
        assertThat(meetingStatusIn(change)).isEqualTo("0")
        assertThat(change).doesNotContain("<Attendees>")
    }

    @Test
    fun `EAS - invalid stored attendee status is sent as unknown`() = runBlocking<Unit> {
        // MS-ASCAL AttendeeStatus: только 0, 2, 3, 4, 5.
        val exchange = FakeExchange(easRoute)

        service(exchange, "14.1").update(MeetingEdit(attendees = listOf(EasAttendee("x@corp.local", "X", 1))))

        val change = exchange.calls.single { it.xml.contains("<Change>") }.xml
        assertThat(attendeesIn(change).single().status).isEqualTo("0")
    }

    @Test
    fun `EAS - create still sends attendees as before (no Name, response unknown)`() = runBlocking<Unit> {
        // Общий построитель блока Attendees не должен менять создание встречи.
        val exchange = FakeExchange(easRoute)

        service(exchange, "14.1").createCalendarEvent(
            subject = "New", startTime = START, endTime = START + 3_600_000L, attendees = listOf(" a@corp.local ")
        )

        val add = exchange.calls.single { it.xml.contains("<Add>") }.xml
        assertThat(meetingStatusIn(add)).isEqualTo("1")
        val sent = attendeesIn(add).single()
        assertThat(sent.email).isEqualTo("a@corp.local")
        assertThat(sent.name).isNull()
        assertThat(sent.status).isEqualTo("0")
        assertThat(sent.type).isEqualTo("1")
    }

    // ───────────────────────── EWS (Exchange 2007, EAS 12.1) ─────────────────────────

    @Test
    fun `EWS - organizer meeting change mails all and appends only new attendees`() = runBlocking<Unit> {
        val exchange = FakeExchange(ewsRoute)
        val meeting = MeetingEdit(
            attendees = serverAttendees + EasAttendee("new@corp.local"),
            isOrganizer = true, meetingStatus = 1, sendUpdates = true, newAttendees = listOf("new@corp.local")
        )

        val result = service(exchange, "12.1").update(meeting)

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        val update = exchange.calls.single { it.command == "UpdateItem" }.xml
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToAllAndSaveCopy"""")
        assertThat(update).contains("""<t:ItemId Id="AAMkCAL1" ChangeKey="DwAAAB"/>""")
        assertThat(Regex("<t:AppendToItemField>").findAll(update).count()).isEqualTo(1)
        assertThat(update).contains("<t:EmailAddress>new@corp.local</t:EmailAddress>")
    }

    @Test
    fun `EWS - organizer reminder-only edit mails nobody`() = runBlocking<Unit> {
        // До фикса префилл делал список участников непустым, и любая правка рассылала обновление всем.
        val exchange = FakeExchange(ewsRoute)
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = true, meetingStatus = 1, sendUpdates = false)

        service(exchange, "12.1").update(meeting)

        val update = exchange.calls.single { it.command == "UpdateItem" }.xml
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToNone"""")
        assertThat(update).doesNotContain("AppendToItemField")
    }

    @Test
    fun `EWS - attendee edit is saved without mailing anyone`() = runBlocking<Unit> {
        val exchange = FakeExchange(ewsRoute)
        val meeting = MeetingEdit(attendees = serverAttendees, isOrganizer = false, meetingStatus = 3)

        service(exchange, "12.1").update(meeting)

        val update = exchange.calls.single { it.command == "UpdateItem" }.xml
        assertThat(update).contains("""SendMeetingInvitationsOrCancellations="SendToNone"""")
        assertThat(update).doesNotContain("AppendToItemField")
    }

    private companion object {
        const val CALENDAR = "cal1"
        const val START_ISO = "2026-11-02T10:00:00Z"
        val START: Long = Instant.parse(START_ISO).toEpochMilli()
    }
}

package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParserException

/**
 * Разбор EAS Sync календаря ([CalendarXmlParser.parseCalendarEvents]) на РЕАЛЬНОМ выходе WBXML-декодера:
 * фикстура (XML с namespace-префиксами, как в MS-ASCAL) → [WbxmlParser.generate] → [WbxmlParser.parse]
 * → парсер. Ровно та строка, что приходит в проде (голые теги, без пробелов между ними).
 *
 * Календарь синкается по EAS на ВСЕХ версиях (Exchange 2007 SP1 … 2013, EAS 12.1–14.1).
 * Тесты двух видов:
 *  - эквивалентность (поля, которые прежний regex разбирал верно) — зелёные и до, и после миграции;
 *  - исправленные баги (участники/категории терялись; поля Exceptions протекали в серию) — красные на
 *    прежнем коде (зафиксировано прогоном), зелёные после.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarXmlParserTest {

    private val wbxml = WbxmlParser()
    private fun decoded(xml: String): String = wbxml.parse(wbxml.generate(xml))

    // Прод-формат EAS-даты (CalendarDateUtils.createEasDateFormat: yyyyMMdd'T'HHmmss'Z', UTC).
    private fun easDate(s: String?): Long? =
        s?.trim()?.takeIf { it.isNotEmpty() }?.let { CalendarDateUtils.createEasDateFormat().parse(it)?.time }

    private val parser = CalendarXmlParser(parseEasDate = ::easDate)

    private fun sync(vararg commands: String) = """<?xml version="1.0" encoding="utf-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar"><Collections><Collection><SyncKey>7</SyncKey><CollectionId>3</CollectionId><Status>1</Status><Commands>${commands.joinToString("")}</Commands></Collection></Collections></Sync>"""

    // Повторяющаяся встреча в порядке сериализации Exchange: Exceptions идут ДО MeetingStatus/ResponseType;
    // у серии НЕТ Location, у исключения — есть. Именно на таком входе прежний regex «протекал».
    private val meeting = """<Add><ServerId>3:1</ServerId><ApplicationData>
<calendar:Timezone>xP///w==</calendar:Timezone>
<calendar:DtStamp>20260701T080000Z</calendar:DtStamp>
<calendar:StartTime>20260712T160000Z</calendar:StartTime>
<calendar:Subject>Weekly sync</calendar:Subject>
<calendar:UID>UID-123</calendar:UID>
<calendar:OrganizerName>Andrey</calendar:OrganizerName>
<calendar:OrganizerEmail>andrey@contoso.com</calendar:OrganizerEmail>
<calendar:Attendees><calendar:Attendee><calendar:Email>ivan@contoso.com</calendar:Email><calendar:Name>Ivan &amp; Co</calendar:Name><calendar:AttendeeStatus>3</calendar:AttendeeStatus><calendar:AttendeeType>1</calendar:AttendeeType></calendar:Attendee><calendar:Attendee><calendar:Email>petr@contoso.com</calendar:Email><calendar:Name>Petr</calendar:Name><calendar:AttendeeStatus>4</calendar:AttendeeStatus><calendar:AttendeeType>2</calendar:AttendeeType></calendar:Attendee></calendar:Attendees>
<calendar:EndTime>20260712T170000Z</calendar:EndTime>
<calendar:Recurrence><calendar:Type>1</calendar:Type><calendar:Interval>2</calendar:Interval><calendar:DayOfWeek>4</calendar:DayOfWeek><calendar:Until>20261231T000000Z</calendar:Until><calendar:FirstDayOfWeek>1</calendar:FirstDayOfWeek></calendar:Recurrence>
<airsyncbase:Body><airsyncbase:Type>1</airsyncbase:Type><airsyncbase:Data>Agenda &amp; notes</airsyncbase:Data></airsyncbase:Body>
<calendar:Sensitivity>2</calendar:Sensitivity><calendar:BusyStatus>1</calendar:BusyStatus><calendar:AllDayEvent>0</calendar:AllDayEvent><calendar:Reminder>15</calendar:Reminder>
<calendar:Categories><calendar:Category>Work</calendar:Category><calendar:Category>R&amp;D</calendar:Category></calendar:Categories>
<calendar:Exceptions>
<calendar:Exception><calendar:ExceptionStartTime>20260719T160000Z</calendar:ExceptionStartTime><calendar:Subject>Moved "sync"</calendar:Subject><calendar:Location>Room B</calendar:Location><calendar:StartTime>20260719T170000Z</calendar:StartTime><calendar:EndTime>20260719T180000Z</calendar:EndTime><airsyncbase:Body><airsyncbase:Type>1</airsyncbase:Type><airsyncbase:Data>Moved</airsyncbase:Data></airsyncbase:Body><calendar:MeetingStatus>7</calendar:MeetingStatus><calendar:ResponseType>4</calendar:ResponseType></calendar:Exception>
<calendar:Exception><calendar:ExceptionStartTime>20260726T160000Z</calendar:ExceptionStartTime><calendar:Deleted>1</calendar:Deleted></calendar:Exception>
</calendar:Exceptions>
<calendar:MeetingStatus>1</calendar:MeetingStatus>
<calendar:ResponseRequested>1</calendar:ResponseRequested>
<calendar:AppointmentReplyTime>20260702T090000Z</calendar:AppointmentReplyTime>
<calendar:ResponseType>3</calendar:ResponseType>
<calendar:DisallowNewTimeProposal>1</calendar:DisallowNewTimeProposal>
<calendar:OnlineMeetingExternalLink>https://meet.contoso.com/x?a=1&amp;b=2</calendar:OnlineMeetingExternalLink>
</ApplicationData></Add>"""

    private fun meetingEvent(): EasCalendarEvent = parser.parseCalendarEvents(decoded(sync(meeting))).single()

    // ───────────────────────── эквивалентность ─────────────────────────

    @Test
    fun `meeting - core fields, recurrence and exceptions json`() {
        val e = meetingEvent()
        assertThat(e.serverId).isEqualTo("3:1")
        assertThat(e.subject).isEqualTo("Weekly sync")
        assertThat(e.body).isEqualTo("Agenda & notes")
        assertThat(e.startTime).isEqualTo(easDate("20260712T160000Z"))
        assertThat(e.endTime).isEqualTo(easDate("20260712T170000Z"))
        assertThat(e.lastModified).isEqualTo(easDate("20260701T080000Z"))
        assertThat(e.allDayEvent).isFalse()
        assertThat(e.reminder).isEqualTo(15)
        assertThat(e.busyStatus).isEqualTo(1)
        assertThat(e.sensitivity).isEqualTo(2)
        assertThat(e.organizer).isEqualTo("andrey@contoso.com")
        assertThat(e.organizerName).isEqualTo("Andrey")
        assertThat(e.uid).isEqualTo("UID-123")
        assertThat(e.timezone).isEqualTo("xP///w==")
        assertThat(e.responseRequested).isTrue()
        assertThat(e.appointmentReplyTime).isEqualTo(easDate("20260702T090000Z"))
        assertThat(e.disallowNewTimeProposal).isTrue()
        assertThat(e.onlineMeetingLink).isEqualTo("https://meet.contoso.com/x?a=1&b=2")
        assertThat(e.hasAttachments).isFalse()
        assertThat(e.attachments).isEmpty()
        assertThat(e.isRecurring).isTrue()
        assertThat(e.recurrenceRule).isEqualTo(
            """{"type":1,"interval":2,"dayOfWeek":4,"dayOfMonth":0,"weekOfMonth":0,"monthOfYear":0,"until":${easDate("20261231T000000Z")},"occurrences":0,"firstDayOfWeek":1}"""
        )
        assertThat(e.exceptions).isEqualTo(
            "[" +
                """{"exceptionStartTime":${easDate("20260719T160000Z")},"deleted":false,"subject":"Moved \"sync\"","location":"Room B","startTime":${easDate("20260719T170000Z")},"endTime":${easDate("20260719T180000Z")},"body":"Moved"}""" +
                "," +
                """{"exceptionStartTime":${easDate("20260726T160000Z")},"deleted":true,"subject":"","location":"","startTime":0,"endTime":0,"body":""}""" +
                "]"
        )
    }

    @Test
    fun `appointment defaults, Add and Change in document order, Responses without ApplicationData skipped`() {
        val appointment = """<Add><ServerId>3:2</ServerId><ApplicationData><calendar:Subject>Dentist</calendar:Subject><calendar:StartTime>20260801T070000Z</calendar:StartTime><calendar:EndTime>20260801T080000Z</calendar:EndTime><calendar:Location>Clinic</calendar:Location></ApplicationData></Add>"""
        val change = """<Change><ServerId>3:9</ServerId><ApplicationData><calendar:Subject>Changed</calendar:Subject><calendar:AllDayEvent>1</calendar:AllDayEvent></ApplicationData></Change>"""
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar"><Collections><Collection><SyncKey>8</SyncKey><CollectionId>3</CollectionId><Status>1</Status><Responses><Add><ClientId>c1</ClientId><ServerId>3:50</ServerId><Status>1</Status></Add><Change><ServerId>3:51</ServerId><Status>1</Status></Change></Responses><Commands>$change$appointment<Delete><ServerId>3:7</ServerId></Delete></Commands></Collection></Collections></Sync>"""

        val events = parser.parseCalendarEvents(decoded(xml))

        assertThat(events.map { it.serverId }).containsExactly("3:9", "3:2").inOrder()
        val changed = events[0]
        assertThat(changed.subject).isEqualTo("Changed")
        assertThat(changed.allDayEvent).isTrue()
        val a = events[1]
        assertThat(a.subject).isEqualTo("Dentist")
        assertThat(a.location).isEqualTo("Clinic")
        assertThat(a.startTime).isEqualTo(easDate("20260801T070000Z"))
        assertThat(a.endTime).isEqualTo(easDate("20260801T080000Z"))
        assertThat(a.body).isEmpty()
        assertThat(a.reminder).isEqualTo(0)
        assertThat(a.busyStatus).isEqualTo(2)       // дефолт MS-ASCAL (Busy)
        assertThat(a.sensitivity).isEqualTo(0)
        assertThat(a.meetingStatus).isEqualTo(0)
        assertThat(a.isMeeting).isFalse()
        assertThat(a.responseStatus).isEqualTo(0)
        assertThat(a.isRecurring).isFalse()
        assertThat(a.recurrenceRule).isEmpty()
        assertThat(a.exceptions).isEmpty()
        assertThat(a.attendees).isEmpty()
        assertThat(a.categories).isEmpty()
        assertThat(a.organizer).isEmpty()
        assertThat(a.onlineMeetingLink).isEmpty()
    }

    @Test
    fun `attachments json from AirSyncBase Attachments`() {
        val add = """<Add><ServerId>3:3</ServerId><ApplicationData><calendar:Subject>With files</calendar:Subject><airsyncbase:Attachments>
<airsyncbase:Attachment><airsyncbase:DisplayName>plan.pdf</airsyncbase:DisplayName><airsyncbase:FileReference>3%3a3%3a0</airsyncbase:FileReference><airsyncbase:Method>1</airsyncbase:Method><airsyncbase:EstimatedDataSize>2048</airsyncbase:EstimatedDataSize></airsyncbase:Attachment>
<airsyncbase:Attachment><airsyncbase:DisplayName>logo.png</airsyncbase:DisplayName><airsyncbase:FileReference>3%3a3%3a1</airsyncbase:FileReference><airsyncbase:Method>6</airsyncbase:Method><airsyncbase:EstimatedDataSize>512</airsyncbase:EstimatedDataSize><airsyncbase:IsInline>1</airsyncbase:IsInline><airsyncbase:ContentId>logo@1</airsyncbase:ContentId></airsyncbase:Attachment>
</airsyncbase:Attachments></ApplicationData></Add>"""

        val e = parser.parseCalendarEvents(decoded(sync(add))).single()

        assertThat(e.hasAttachments).isTrue()
        assertThat(e.attachments).isEqualTo(
            """[{"name":"plan.pdf","fileReference":"3%3a3%3a0","size":2048,"isInline":false,"contentId":"","method":1},""" +
                """{"name":"logo.png","fileReference":"3%3a3%3a1","size":512,"isInline":true,"contentId":"logo@1","method":6}]"""
        )
    }

    // ───────────── целостность: обрыв НЕ должен превращаться в «частичный полный снимок» ─────────────
    // Календарь синкается с SyncKey=0 (полный снимок), а isAuthoritativeSnapshot=true разрешает
    // CalendarRepository удалять локальные события, которых нет в ответе. Поэтому оборванный документ
    // обязан бросить исключение (транспорт → EasResult.Error → ветка ошибок синка, без удалений),
    // а не вернуть часть событий. Прежний regex на таком входе возвращал часть — источник потери данных.

    @Test
    fun `truncated inside an event throws instead of returning a partial list`() {
        val second = """<Add><ServerId>3:4</ServerId><ApplicationData><calendar:Subject>Cut</calendar:Subject><calendar:StartTime>20260801T070000Z</calendar:StartTime></ApplicationData></Add>"""
        val full = decoded(sync(meeting, second))
        val cut = full.indexOf("</Subject>", full.indexOf("<ServerId>3:4</ServerId>")) + "</Subject>".length
        val truncated = full.substring(0, cut)

        assertThrows(XmlPullParserException::class.java) { parser.parseCalendarEvents(truncated) }
    }

    @Test
    fun `truncated exactly between events throws instead of returning a partial list`() {
        // Самый коварный обрыв: первое событие целое, документ кончился сразу после него.
        val second = """<Add><ServerId>3:4</ServerId><ApplicationData><calendar:Subject>Lost</calendar:Subject></ApplicationData></Add>"""
        val full = decoded(sync(meeting, second))
        val truncated = full.substring(0, full.indexOf("<Add><ServerId>3:4</ServerId>"))

        assertThrows(XmlPullParserException::class.java) { parser.parseCalendarEvents(truncated) }
    }

    @Test
    fun `transport empty-sync placeholder parses to no events`() {
        // EasTransport.EMPTY_SYNC_RESPONSE — подставляется на пустое тело ответа Sync.
        val empty = """<?xml version="1.0" encoding="UTF-8"?><Sync><Collections><Collection><Status>1</Status></Collection></Collections></Sync>"""
        assertThat(parser.parseCalendarEvents(empty)).isEmpty()
    }

    @Test
    fun `empty ApplicationData and missing ServerId are skipped`() {
        val empty = """<Change><ServerId>3:5</ServerId><ApplicationData/></Change>"""
        val noId = """<Add><ApplicationData><calendar:Subject>Orphan</calendar:Subject></ApplicationData></Add>"""
        assertThat(parser.parseCalendarEvents(decoded(sync(empty, noId)))).isEmpty()
    }

    // ─────────────── исправленные баги (красные на прежнем regex-коде) ───────────────

    @Test
    fun `meeting - attendees are parsed from real decoder output`() {
        val e = meetingEvent()
        assertThat(e.attendees).containsExactly(
            EasAttendee("ivan@contoso.com", "Ivan & Co", 3),
            EasAttendee("petr@contoso.com", "Petr", 4)
        ).inOrder()
    }

    @Test
    fun `meeting - categories are parsed from real decoder output`() {
        assertThat(meetingEvent().categories).containsExactly("Work", "R&D").inOrder()
    }

    @Test
    fun `meeting - exception fields do not leak into the series`() {
        val e = meetingEvent()
        assertThat(e.location).isEmpty()                 // у серии Location нет; "Room B" — поле исключения
        assertThat(e.meetingStatus).isEqualTo(1)         // не 7 из исключения
        assertThat(e.isMeeting).isTrue()
        assertThat(e.responseStatus).isEqualTo(3)        // не 4 из исключения
    }

    @Test
    fun `series without own Body does not take an exception Body`() {
        val add = """<Add><ServerId>3:6</ServerId><ApplicationData><calendar:Subject>No body</calendar:Subject><calendar:Recurrence><calendar:Type>0</calendar:Type></calendar:Recurrence><calendar:Exceptions><calendar:Exception><calendar:ExceptionStartTime>20260719T160000Z</calendar:ExceptionStartTime><airsyncbase:Body><airsyncbase:Type>1</airsyncbase:Type><airsyncbase:Data>Exception only</airsyncbase:Data></airsyncbase:Body></calendar:Exception></calendar:Exceptions></ApplicationData></Add>"""

        val e = parser.parseCalendarEvents(decoded(sync(add))).single()

        assertThat(e.body).isEmpty()
        assertThat(e.exceptions).contains(""""body":"Exception only"""")
    }

    @Test
    fun `attachment display name entities are unescaped`() {
        val add = """<Add><ServerId>3:7</ServerId><ApplicationData><calendar:Subject>S</calendar:Subject><airsyncbase:Attachments><airsyncbase:Attachment><airsyncbase:DisplayName>R&amp;D plan.docx</airsyncbase:DisplayName><airsyncbase:FileReference>f1</airsyncbase:FileReference></airsyncbase:Attachment></airsyncbase:Attachments></ApplicationData></Add>"""
        val e = parser.parseCalendarEvents(decoded(sync(add))).single()
        assertThat(e.attachments).contains(""""name":"R&D plan.docx"""")
    }
}

package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HTML-тело события — от HTTP до парсера и обратно (реальный WBXML-кодек, [FakeExchange]).
 *
 * [MS-ASAIRS] BodyPreference/Type: 1 — plain text, 2 — HTML (для календаря только эти два);
 * TruncationSize — верхняя граница тела. EWS: `t:Body BodyType="HTML"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasCalendarRichBodyTest {

    private val htmlBody = """<div>Line</div><br><div>Line</div><img src="cid:img1@x">"""

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun calendarPage(syncKey: String, body: String): String =
        """<?xml version="1.0" encoding="utf-8"?><Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">""" +
            "<Collections><Collection><SyncKey>$syncKey</SyncKey><CollectionId>$CAL</CollectionId><Status>1</Status>" +
            "<Commands><Add><ServerId>1:1</ServerId><ApplicationData>" +
            "<calendar:Subject>Planning</calendar:Subject>" +
            "<airsyncbase:Body><airsyncbase:Type>2</airsyncbase:Type><airsyncbase:Data>${xmlEscape(body)}</airsyncbase:Data></airsyncbase:Body>" +
            "</ApplicationData></Add></Commands></Collection></Collections></Sync>"

    private fun syncRoute(body: String): (FakeExchange.Call) -> Reply = { call ->
        when {
            call.isEws -> Reply.Text(EwsXml.findItem("", total = 0, includesLast = true))
            call.syncKey == "0" -> Reply.Wbxml(EasSyncXml.initial("K1", CAL))
            call.syncKey == "K1" -> Reply.Wbxml(calendarPage("K2", body))
            else -> Reply.Http(500)
        }
    }

    /** Продвижение ключа 0→K1→K2 и ответ на команду; EWS — FindItem/UpdateItem/CreateItem. */
    private val writeRoute: (FakeExchange.Call) -> Reply = { call ->
        when {
            call.isEws && call.command == "FindItem" -> Reply.Text(EwsXml.findItem(
                """<t:CalendarItem><t:ItemId Id="AAMkCAL1" ChangeKey="CK1"/><t:Subject>Planning</t:Subject></t:CalendarItem>""",
                total = 1, includesLast = true
            ))
            call.isEws && call.command == "UpdateItem" -> Reply.Text(EwsXml.success("UpdateItem"))
            call.isEws && call.command == "CreateItem" ->
                Reply.Text(EwsXml.success("CreateItem", """<t:CalendarItem><t:ItemId Id="NEW" ChangeKey="CKN"/></t:CalendarItem>"""))
            !call.isEws && call.xml.contains("<Commands>") -> Reply.Wbxml(EasSyncXml.initial("K3", CAL))
            !call.isEws && call.syncKey == "0" -> Reply.Wbxml(EasSyncXml.initial("K1", CAL))
            !call.isEws && call.syncKey == "K1" -> Reply.Wbxml(EasSyncXml.page("K2", CAL, emptyList(), more = false))
            else -> Reply.Http(500)
        }
    }

    private val plainAppointment = MeetingEdit(attendees = emptyList(), isOrganizer = true, meetingStatus = 0, sendUpdates = false)

    private fun bodyType(xml: String) = Regex("<Body><Type>(\\d)</Type>").find(xml)?.groupValues?.get(1)

    @Test
    fun `sync asks for HTML with 1 MiB truncation and keeps markup intact`() = runBlocking<Unit> {
        val exchange = FakeExchange(syncRoute(htmlBody))

        val result = exchange.calendarService("14.1", CAL).syncCalendar()

        assertThat(result).isInstanceOf(EasResult.Success::class.java)
        val event = (result as EasResult.Success).data.events.single()
        // Раньше построчная дедупликация превращала теги в \n и выкидывала повторы — разметка пропадала.
        assertThat(event.body).isEqualTo(htmlBody)
        val request = exchange.calls.first { !it.isEws && it.syncKey == "K1" }.xml
        assertThat(request).contains("<BodyPreference><Type>2</Type><TruncationSize>1048576</TruncationSize></BodyPreference>")
    }

    @Test
    fun `EAS 14 update sends HTML body as Type 2 and plain body as Type 1`() = runBlocking<Unit> {
        val html = FakeExchange(writeRoute)
        html.calendarService("14.1", CAL).updateCalendarEvent(
            serverId = "1:5", subject = "Planning", startTime = START, endTime = START + HOUR,
            body = htmlBody, meeting = plainAppointment
        )
        val htmlChange = html.calls.single { !it.isEws && it.xml.contains("<Change>") }.xml
        assertThat(bodyType(htmlChange)).isEqualTo("2")
        // Декодер WBXML фейка экранирует текст: HTML дошёл целиком, как символы, а не как разметка Sync.
        assertThat(htmlChange).contains("<Data>${xmlEscape(htmlBody)}</Data>")

        val plain = FakeExchange(writeRoute)
        plain.calendarService("14.1", CAL).updateCalendarEvent(
            serverId = "1:5", subject = "Planning", startTime = START, endTime = START + HOUR,
            body = "Agenda", meeting = plainAppointment
        )
        assertThat(bodyType(plain.calls.single { !it.isEws && it.xml.contains("<Change>") }.xml)).isEqualTo("1")
    }

    @Test
    fun `EAS 14 create of a plain appointment sends HTML body as Type 2`() = runBlocking<Unit> {
        val exchange = FakeExchange(writeRoute)
        exchange.calendarService("14.1", CAL).createCalendarEvent(
            subject = "Planning", startTime = START, endTime = START + HOUR, body = htmlBody
        )
        val add = exchange.calls.single { !it.isEws && it.xml.contains("<Add>") }.xml
        assertThat(bodyType(add)).isEqualTo("2")
    }

    @Test
    fun `Exchange 2007 update goes through EWS with BodyType HTML`() = runBlocking<Unit> {
        val exchange = FakeExchange(writeRoute)
        exchange.calendarService("12.1", CAL).updateCalendarEvent(
            serverId = "1:5", subject = "Planning", startTime = START, endTime = START + HOUR,
            body = htmlBody, meeting = plainAppointment
        )
        val update = exchange.calls.single { it.isEws && it.command == "UpdateItem" }.xml
        // EWS-запрос — текстовый SOAP: тело экранируется всеми пятью XML-сущностями (XmlUtils.escape).
        assertThat(update).contains("""<t:Body BodyType="HTML">${XmlUtils.escape(htmlBody)}</t:Body>""")
    }

    private companion object {
        const val CAL = "CAL"
        const val START = 1_790_000_000_000L
        const val HOUR = 3_600_000L
    }
}

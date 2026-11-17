package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Дополнение исключений серий через EWS на Exchange 2007: FindItem CalendarView без item:Body и
 * calendar:OriginalStart (иначе весь запрос — ErrorInvalidPropertyForOperation), детали — GetItem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarExceptionServiceEwsTest {

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val base = (System.currentTimeMillis() / hour) * hour + 10 * day
    private val originalStart = base
    private val movedStart = base + 2 * hour

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    private fun calendarItem(id: String, uid: String, type: String, start: Long, subject: String = "Changed") = """
        <t:CalendarItem><t:ItemId Id="$id" ChangeKey="ck"/><t:Subject>$subject</t:Subject>
        <t:Start>${iso(start)}</t:Start><t:End>${iso(start + hour)}</t:End><t:Location>Room &amp; B</t:Location>
        <t:CalendarItemType>$type</t:CalendarItemType><t:UID>$uid</t:UID></t:CalendarItem>"""

    private fun findResponse(vararg items: String) = """<?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage ResponseClass="Success">
        <m:ResponseCode>NoError</m:ResponseCode><m:RootFolder TotalItemsInView="${items.size}" IncludesLastItemInRange="true">
        <t:Items>${items.joinToString("")}</t:Items></m:RootFolder></m:FindItemResponseMessage></m:ResponseMessages>
        </m:FindItemResponse></s:Body></s:Envelope>"""

    private fun getItemMessage(id: String, uid: String, original: Long?, body: String = "<t:Body BodyType=\"Text\">exception body</t:Body>") = """
        <m:GetItemResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode><m:Items>
        <t:CalendarItem><t:ItemId Id="$id" ChangeKey="ck"/>$body
        ${if (original != null) "<t:OriginalStart>${iso(original)}</t:OriginalStart>" else ""}<t:UID>$uid</t:UID>
        </t:CalendarItem></m:Items></m:GetItemResponseMessage>"""

    private fun getResponse(vararg messages: String) = """<?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><m:GetItemResponse><m:ResponseMessages>
        ${messages.joinToString("")}</m:ResponseMessages></m:GetItemResponse></s:Body></s:Envelope>"""

    private val errorMessage = """<m:GetItemResponseMessage ResponseClass="Error"><m:MessageText>not found</m:MessageText>
        <m:ResponseCode>ErrorItemNotFound</m:ResponseCode><m:Items/></m:GetItemResponseMessage>"""

    private class FakeEws(private val find: String, private val get: String?) {
        val calls = mutableListOf<Pair<String, String>>()
        suspend fun request(body: String, operation: String): EasResult<String> {
            calls += operation to body
            return when (operation) {
                "FindItem" -> EasResult.Success(find)
                "GetItem" -> get?.let { EasResult.Success(it) } ?: EasResult.Error("GetItem failed")
                else -> error("unexpected $operation")
            }
        }
    }

    private fun service(ews: FakeEws) = CalendarExceptionService({ it }, { "" }, ews::request)

    private fun series(exceptions: String = "") = listOf(EasCalendarService.RecurringEventInfo("UID-1", "1:1", exceptions))

    private val appFind = findResponse(
        calendarItem("occ-0", "UID-1", "Occurrence", originalStart - day, subject = "Series"),
        calendarItem("occ-1", "UID-1", "Exception", movedStart),
        calendarItem("occ-other", "UID-OTHER", "Exception", movedStart)
    )

    @Test
    fun `FindItem has no GetItem-only properties and details come from GetItem of the exception`() = runBlocking<Unit> {
        val ews = FakeEws(appFind, getResponse(getItemMessage("occ-1", "UID-1", originalStart)))

        val result = service(ews).supplementRecurringExceptionsViaEws(series())

        val (findOp, findBody) = ews.calls[0]
        assertThat(findOp).isEqualTo("FindItem")
        assertThat(findBody).contains("<m:CalendarView ")
        assertThat(findBody).doesNotContain("item:Body")
        assertThat(findBody).doesNotContain("calendar:OriginalStart")
        val (getOp, getBody) = ews.calls[1]
        assertThat(getOp).isEqualTo("GetItem")
        assertThat(getBody).contains("<t:ItemId Id=\"occ-1\"/>")
        assertThat(getBody).doesNotContain("occ-0")
        assertThat(getBody).doesNotContain("occ-other")
        assertThat(getBody).contains("calendar:OriginalStart")

        val arr = JSONArray(result.getValue("UID-1"))
        assertThat(arr.length()).isEqualTo(1)
        val ex = arr.getJSONObject(0)
        assertThat(ex.getLong("exceptionStartTime")).isEqualTo(originalStart)
        assertThat(ex.getLong("startTime")).isEqualTo(movedStart)
        assertThat(ex.getLong("endTime")).isEqualTo(movedStart + hour)
        assertThat(ex.getString("subject")).isEqualTo("Changed")
        assertThat(ex.getString("location")).isEqualTo("Room & B")
        assertThat(ex.getString("body")).isEqualTo("exception body")
        assertThat(ex.getBoolean("deleted")).isFalse()
    }

    @Test
    fun `a failed GetItem, an error message, a missing OriginalStart or another UID change nothing`() = runBlocking<Unit> {
        val local = """[{"exceptionStartTime":$originalStart,"deleted":false,"subject":"Local","startTime":$originalStart,"endTime":${originalStart + hour}}]"""
        val responses = listOf(
            null,
            getResponse(errorMessage),
            getResponse(getItemMessage("occ-1", "UID-1", original = null)),
            getResponse(getItemMessage("occ-1", "UID-ELSE", originalStart)),
            getResponse(),
            "<html>500</html>"
        )
        for (get in responses) {
            val result = service(FakeEws(appFind, get)).supplementRecurringExceptionsViaEws(series(local))
            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `an error FindItem response is not followed by GetItem and changes nothing`() = runBlocking<Unit> {
        val error = """<m:FindItemResponseMessage ResponseClass="Error"><m:MessageText>Недопустимое свойство</m:MessageText>
            <m:ResponseCode>ErrorInvalidPropertyForOperation</m:ResponseCode></m:FindItemResponseMessage>"""
        val ews = FakeEws(error, getResponse(getItemMessage("occ-1", "UID-1", originalStart)))

        assertThat(service(ews).supplementRecurringExceptionsViaEws(series())).isEmpty()
        assertThat(ews.calls.map { it.first }).containsExactly("FindItem")
    }

    @Test
    fun `an exception moved beyond the window edge is kept, an undone one inside the window is dropped`() = runBlocking<Unit> {
        val edgeOriginal = System.currentTimeMillis() + 360 * day
        val edgeMoved = System.currentTimeMillis() + 400 * day
        val undone = originalStart + 2 * day
        val local = "[" +
            """{"exceptionStartTime":$edgeOriginal,"deleted":false,"subject":"Moved far","startTime":$edgeMoved,"endTime":${edgeMoved + hour}},""" +
            """{"exceptionStartTime":$undone,"deleted":false,"subject":"Undone","startTime":$undone,"endTime":${undone + hour}},""" +
            """{"exceptionStartTime":${originalStart + 3 * day},"deleted":true}""" +
            "]"
        val ews = FakeEws(appFind, getResponse(getItemMessage("occ-1", "UID-1", originalStart)))

        val arr = JSONArray(service(ews).supplementRecurringExceptionsViaEws(series(local)).getValue("UID-1"))

        val byOriginal = (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getLong("exceptionStartTime") }
        assertThat(byOriginal.keys).containsExactly(edgeOriginal, originalStart + 3 * day, originalStart)
        assertThat(byOriginal.getValue(edgeOriginal).getString("subject")).isEqualTo("Moved far")
        assertThat(byOriginal.getValue(originalStart + 3 * day).getBoolean("deleted")).isTrue()
    }

    @Test
    fun `many exceptions are fetched in GetItem batches and an empty Body element is accepted`() = runBlocking<Unit> {
        val count = CalendarExceptionService.GET_ITEM_BATCH + 3
        val items = (0 until count).map { calendarItem("occ-$it", "UID-1", "Exception", originalStart + it * day + 2 * hour) }
        val find = findResponse(*items.toTypedArray())
        val calls = mutableListOf<String>()
        val svc = CalendarExceptionService({ it }, { "" }) { body, op ->
            calls += op
            if (op == "FindItem") {
                EasResult.Success(find)
            } else {
                val ids = Regex("<t:ItemId Id=\"occ-(\\d+)\"/>").findAll(body).map { it.groupValues[1].toInt() }.toList()
                EasResult.Success(getResponse(*ids.map {
                    getItemMessage("occ-$it", "UID-1", originalStart + it * day, body = "<t:Body BodyType=\"Text\" />")
                }.toTypedArray()))
            }
        }

        val arr = JSONArray(svc.supplementRecurringExceptionsViaEws(series()).getValue("UID-1"))

        assertThat(calls).containsExactly("FindItem", "GetItem", "GetItem").inOrder()
        assertThat(arr.length()).isEqualTo(count)
        assertThat((0 until count).map { arr.getJSONObject(it).getString("body") }.toSet()).containsExactly("")
    }
}

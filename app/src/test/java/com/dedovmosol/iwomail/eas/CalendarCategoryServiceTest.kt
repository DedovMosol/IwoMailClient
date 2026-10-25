package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Master Category List через EWS GetUserConfiguration — от HTTP-запроса до списка цветов.
 *
 * [MS-OXOCFG] 2.2.5.2.2: XML `<categories xmlns="CategoryList.xsd">` в PidTagRoamingXmlStream (UTF-8),
 * EWS отдаёт его в `t:XmlData` (base64Binary); color вне -1..24 = -1. GetUserConfiguration — Exchange
 * 2010+ (RequestServerVersion Exchange2010); Exchange 2007 (EAS 12.x) запрос не получает.
 * Robolectric — ради настоящего XmlPullParser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarCategoryServiceTest {

    /** Фрагмент примера MCL из [MS-OXOCFG] + граничные случаи: цвет вне диапазона, повтор в другом регистре. */
    private val mcl = "\uFEFF" + """<?xml version="1.0"?>
<categories default="Red Category" lastSavedSession="5" lastSavedTime="2007-12-28T03:01:50.429" xmlns="CategoryList.xsd">
  <category name="Red Category" color="0" keyboardShortcut="0" usageCount="7" lastTimeUsed="2007-11-28T20:05:04.703" lastSessionUsed="3" guid="{2B7FC69C-7046-44A2-8FF3-007D7467DC82}"/>
  <category name="Blue Category" color="7" keyboardShortcut="0" lastTimeUsed="2007-12-28T03:00:07.102" lastSessionUsed="5" guid="{33A1EAE3-8E5E-4912-9580-69FC764FEA35}"/>
  <category name="Проект &amp; Альфа" color="19" keyboardShortcut="0" lastTimeUsed="2007-12-14T02:43:30.719" lastSessionUsed="4" guid="{77EA6484-D31F-496E-AA07-DC4839D4327A}"/>
  <category name="Странный" color="99" keyboardShortcut="0" lastTimeUsed="2007-12-14T02:43:30.719" lastSessionUsed="4" guid="{77EA6484-D31F-496E-AA07-DC4839D4327B}"/>
  <category name="red category" color="4" keyboardShortcut="0" lastTimeUsed="2007-12-14T02:43:30.719" lastSessionUsed="4" guid="{77EA6484-D31F-496E-AA07-DC4839D4327C}"/>
  <category name="" color="1" keyboardShortcut="0" lastTimeUsed="2007-12-14T02:43:30.719" lastSessionUsed="4" guid="{77EA6484-D31F-496E-AA07-DC4839D4327D}"/>
</categories>"""

    private fun base64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `Exchange 2010 request is GetUserConfiguration for CategoryList with Exchange2010 header`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            if (call.isEws) Reply.Text(EwsXml.userConfiguration("CategoryList", base64(mcl))) else Reply.Http(500)
        }

        val result = exchange.calendarService("14.1", "CAL").fetchCategoryList()

        assertThat(result).isEqualTo(EasResult.Success(listOf(
            OutlookCategory("Red Category", 0),
            OutlookCategory("Blue Category", 7),
            OutlookCategory("Проект & Альфа", 19),
            OutlookCategory("Странный", -1)
        )))
        val request = exchange.calls.single { it.isEws }
        assertThat(request.command).isEqualTo("GetUserConfiguration")
        assertThat(request.xml).contains("""<t:RequestServerVersion Version="Exchange2010"/>""")
        assertThat(request.xml).contains("""<m:UserConfigurationName Name="CategoryList">""")
        assertThat(request.xml).contains("""<t:DistinguishedFolderId Id="calendar"/>""")
        assertThat(request.xml).contains("<m:UserConfigurationProperties>XmlData</m:UserConfigurationProperties>")
    }

    @Test
    fun `Exchange 2007 never sends the request`() = runBlocking<Unit> {
        val exchange = FakeExchange { error("Exchange 2007 must not be asked") }

        val result = exchange.calendarService("12.1", "CAL").fetchCategoryList()

        assertThat(result).isEqualTo(EasResult.Success(null))
        assertThat(exchange.calls).isEmpty()
    }

    @Test
    fun `mailbox without a category list is an empty list, other errors are errors`() = runBlocking<Unit> {
        val missing = FakeExchange { Reply.Text(EwsXml.error("GetUserConfiguration", "ErrorItemNotFound")) }
        assertThat(missing.calendarService("14.1", "CAL").fetchCategoryList()).isEqualTo(EasResult.Success(emptyList<OutlookCategory>()))

        val busy = FakeExchange { Reply.Text(EwsXml.error("GetUserConfiguration", "ErrorServerBusy")) }
        assertThat(busy.calendarService("14.1", "CAL").fetchCategoryList()).isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `corrupt payloads are errors so stored colors survive`() {
        assertThat(CalendarCategoryService.parseResponse(EwsXml.userConfiguration("CategoryList", "@@not-base64@@")))
            .isInstanceOf(EasResult.Error::class.java)
        assertThat(CalendarCategoryService.parseResponse(EwsXml.userConfiguration("CategoryList", base64("<categories><category name=\"A\""))))
            .isInstanceOf(EasResult.Error::class.java)
        assertThat(CalendarCategoryService.parseResponse("<html>proxy error</html>"))
            .isInstanceOf(EasResult.Error::class.java)
    }

    @Test
    fun `oversized category list is rejected before decoding`() {
        val huge = "A".repeat(CalendarCategoryService.MAX_XML_BYTES / 3 * 4 + 8)
        assertThat(CalendarCategoryService.parseResponse(EwsXml.userConfiguration("CategoryList", huge)))
            .isInstanceOf(EasResult.Error::class.java)
    }
}

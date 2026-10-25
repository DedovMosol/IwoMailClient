package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Категории события в запросах записи. [MS-ASCAL] Categories/Category (EAS 14+), EWS `Categories`
 * после Body и до ReminderIsSet, UpdateItem `item:Categories`. [MS-ASCMD] Supported: опущенный в
 * Change элемент стирается — поэтому null значит «не трогать», а пустой список — явная очистка.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasCalendarCategoriesWriteTest {

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

    private val appointment = MeetingEdit(attendees = emptyList(), isOrganizer = true, meetingStatus = 0, sendUpdates = false)

    private suspend fun easChange(categories: List<String>?): String {
        val exchange = FakeExchange(writeRoute)
        exchange.calendarService("14.1", CAL).updateCalendarEvent(
            serverId = "1:5", subject = "Planning", startTime = START, endTime = START + HOUR,
            meeting = appointment, categories = categories
        )
        return exchange.calls.single { !it.isEws && it.xml.contains("<Change>") }.xml
    }

    private suspend fun ewsUpdate(categories: List<String>?): String {
        val exchange = FakeExchange(writeRoute)
        exchange.calendarService("12.1", CAL).updateCalendarEvent(
            serverId = "1:5", subject = "Planning", startTime = START, endTime = START + HOUR,
            meeting = appointment, categories = categories
        )
        return exchange.calls.single { it.isEws && it.command == "UpdateItem" }.xml
    }

    @Test
    fun `EAS 14 Add sends categories and omits the element when there are none`() = runBlocking<Unit> {
        val exchange = FakeExchange(writeRoute)
        exchange.calendarService("14.1", CAL).createCalendarEvent(
            subject = "Planning", startTime = START, endTime = START + HOUR, categories = listOf("Red", " red ", "Tom & <Jerry>")
        )
        val add = exchange.calls.single { !it.isEws && it.xml.contains("<Add>") }.xml
        assertThat(add).contains("<Categories><Category>Red</Category><Category>Tom &amp; &lt;Jerry&gt;</Category></Categories>")

        val none = FakeExchange(writeRoute)
        none.calendarService("14.1", CAL).createCalendarEvent(subject = "Planning", startTime = START, endTime = START + HOUR)
        assertThat(none.calls.single { !it.isEws && it.xml.contains("<Add>") }.xml).doesNotContain("Categories")
    }

    @Test
    fun `EAS 14 Change sends the full set, empty clears, null leaves untouched`() = runBlocking<Unit> {
        assertThat(easChange(listOf("Blue"))).contains("<Categories><Category>Blue</Category></Categories>")
        assertThat(easChange(emptyList())).contains("<Categories/>")
        assertThat(easChange(null)).doesNotContain("Categories")
    }

    @Test
    fun `Exchange 2007 update sets or deletes item Categories via EWS`() = runBlocking<Unit> {
        assertThat(ewsUpdate(listOf("A & B"))).contains(
            """<t:SetItemField><t:FieldURI FieldURI="item:Categories"/><t:CalendarItem><t:Categories><t:String>A &amp; B</t:String></t:Categories></t:CalendarItem></t:SetItemField>"""
        )
        assertThat(ewsUpdate(emptyList())).contains(
            """<t:DeleteItemField><t:FieldURI FieldURI="item:Categories"/></t:DeleteItemField>"""
        )
        assertThat(ewsUpdate(null)).doesNotContain("item:Categories")
    }

    @Test
    fun `Exchange 2007 create puts Categories after Body and before ReminderIsSet`() = runBlocking<Unit> {
        val exchange = FakeExchange(writeRoute)
        exchange.calendarService("12.1", CAL).createCalendarEvent(
            subject = "Planning", startTime = START, endTime = START + HOUR, body = "Agenda",
            reminder = 15, categories = listOf("Green")
        )
        val create = exchange.calls.single { it.isEws && it.command == "CreateItem" }.xml
        val body = create.indexOf("<Body ")
        val categories = create.indexOf("<Categories><String>Green</String></Categories>")
        val reminder = create.indexOf("<ReminderIsSet>")
        assertThat(body).isAtLeast(0)
        assertThat(categories).isGreaterThan(body)
        assertThat(reminder).isGreaterThan(categories)
    }

    @Test
    fun `inline FileAttachment carries ContentId and IsInline only from Exchange 2010`() {
        val att = DraftAttachmentData(name = "image_1.png", mimeType = "image/png", data = byteArrayOf(1), isInline = true, contentId = "<img1_1>")
        val v2010 = StringBuilder().also { EwsAttachmentXml.appendFileAttachment(it, att, EwsAttachmentXml.requestVersion(14), XmlUtils::escape) }.toString()
        val v2007 = StringBuilder().also { EwsAttachmentXml.appendFileAttachment(it, att, EwsAttachmentXml.requestVersion(12), XmlUtils::escape) }.toString()

        assertThat(v2010).isEqualTo(
            "<t:FileAttachment><t:Name>image_1.png</t:Name><t:ContentType>image/png</t:ContentType>" +
                "<t:ContentId>img1_1</t:ContentId><t:IsInline>true</t:IsInline><t:Content>AQ==</t:Content></t:FileAttachment>"
        )
        assertThat(v2007).contains("<t:ContentId>img1_1</t:ContentId>")
        assertThat(v2007).doesNotContain("IsInline")
    }

    @Test
    fun `EAS file references are matched to EWS attachment ids, ambiguous ones are skipped`() {
        val onServer = listOf(
            CalendarAttachmentRef("EWS-A", "logo.png", 5, "logo@x"),
            CalendarAttachmentRef("EWS-B", "doc.pdf", 10, ""),
            CalendarAttachmentRef("EWS-C", "dup.txt", 1, ""),
            CalendarAttachmentRef("EWS-D", "dup.txt", 1, "")
        )
        val wanted = listOf(
            CalendarAttachmentRef("5:1:0", "renamed.png", 0, "<logo@x>"),
            CalendarAttachmentRef("5:1:1", "doc.pdf", 10, ""),
            CalendarAttachmentRef("5:1:2", "dup.txt", 1, ""),
            CalendarAttachmentRef("EWS-Z", "", 0, "")
        )

        assertThat(CalendarAttachmentService.resolveEwsAttachmentIds(wanted, onServer))
            .containsExactly("EWS-A", "EWS-B", "EWS-Z").inOrder()
    }

    private companion object {
        const val CAL = "CAL"
        const val START = 1_790_000_000_000L
        const val HOUR = 3_600_000L
    }
}

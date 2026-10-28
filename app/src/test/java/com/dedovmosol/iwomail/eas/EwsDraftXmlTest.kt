package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SOAP черновиков: схема MessageType (порядок Subject, Body, Importance, To/Cc/Bcc — learn.microsoft.com,
 * Message element) и семантика UpdateItem (пустой список получателей — DeleteItemField, как в EWS
 * Managed API; неизвестные скрытые копии не трогаются).
 */
class EwsDraftXmlTest {

    private val escape: (String) -> String = { XmlUtils.escape(it) }
    private val typesNs = "http://schemas.microsoft.com/exchange/services/2006/types"

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream()).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.firstByName(local: String): Element =
        getElementsByTagNameNS(typesNs, local).item(0) as Element

    @Test
    fun `create keeps the MessageType order and escapes text`() {
        val xml = EwsDraftXml.createItem(
            "Exchange2007_SP1", "Счёт <№1> & Co", "<p>Тело</p>",
            to = "a@corp.example, b@corp.example", cc = "", bcc = "boss@corp.example",
            easImportance = 2, escape = escape
        )

        val message = parse(xml).firstByName("Message")
        assertThat(message.children().map { it.localName })
            .containsExactly("Subject", "Body", "Importance", "ToRecipients", "BccRecipients").inOrder()
        assertThat(message.firstByName("Subject").textContent).isEqualTo("Счёт <№1> & Co")
        assertThat(message.firstByName("Body").textContent).isEqualTo("<p>Тело</p>")
        assertThat(message.firstByName("Importance").textContent).isEqualTo("High")
        assertThat(message.firstByName("ToRecipients").getElementsByTagNameNS(typesNs, "EmailAddress").length).isEqualTo(2)
    }

    @Test
    fun `normal importance is not sent on create`() {
        val xml = EwsDraftXml.createItem("Exchange2010_SP1", "S", "B", "a@corp.example", "", "", 1, escape)
        assertThat(xml).doesNotContain("Importance")
    }

    @Test
    fun `update clears emptied recipients with DeleteItemField and leaves unknown Bcc alone`() {
        val xml = EwsDraftXml.updateItem(
            "Exchange2007_SP1", itemId = "AAMk=", changeKey = "CK1",
            subject = "S", htmlBody = "<p>B</p>", to = "a@corp.example", cc = "", bcc = null,
            easImportance = 0, escape = escape
        )

        val root = parse(xml)
        val itemId = root.firstByName("ItemId")
        assertThat(itemId.getAttribute("Id")).isEqualTo("AAMk=")
        assertThat(itemId.getAttribute("ChangeKey")).isEqualTo("CK1")
        val set = root.getElementsByTagNameNS(typesNs, "SetItemField")
        val setFields = (0 until set.length).map { (set.item(it) as Element).firstByName("FieldURI").getAttribute("FieldURI") }
        assertThat(setFields).containsExactly("item:Subject", "item:Body", "item:Importance", "message:ToRecipients")
        val deleted = root.getElementsByTagNameNS(typesNs, "DeleteItemField")
        assertThat((0 until deleted.length).map { (deleted.item(it) as Element).firstByName("FieldURI").getAttribute("FieldURI") })
            .containsExactly("message:CcRecipients")
        assertThat(xml).doesNotContain("BccRecipients")
        assertThat(root.firstByName("Importance").textContent).isEqualTo("Low")
    }

    @Test
    fun `known empty Bcc is cleared, partial update touches only given fields`() {
        val cleared = EwsDraftXml.updateItem("Exchange2010", "ID", null, "S", "B", "", "", "", 1, escape)
        assertThat(cleared).contains("""<t:DeleteItemField><t:FieldURI FieldURI="message:BccRecipients"/></t:DeleteItemField>""")
        assertThat(cleared).doesNotContain("ChangeKey")

        val extras = EwsDraftXml.updateItem(
            "Exchange2010", "ID", null, subject = null, htmlBody = null, to = null, cc = null,
            bcc = "hidden@corp.example", easImportance = null, escape = escape
        )
        val root = parse(extras)
        assertThat(root.getElementsByTagNameNS(typesNs, "SetItemField").length).isEqualTo(1)
        assertThat(root.firstByName("BccRecipients").textContent).isEqualTo("hidden@corp.example")
        assertThat(extras).doesNotContain("item:Subject")
    }

    @Test
    fun `cleanup is a hard delete of exactly that item`() {
        val root = parse(EwsDraftXml.hardDelete("Exchange2007_SP1", "A&B", escape))
        val delete = root.getElementsByTagNameNS("http://schemas.microsoft.com/exchange/services/2006/messages", "DeleteItem")
            .item(0) as Element
        assertThat(delete.getAttribute("DeleteType")).isEqualTo("HardDelete")
        assertThat(root.firstByName("ItemId").getAttribute("Id")).isEqualTo("A&B")
    }

    @Test
    fun `unknown Bcc is appended, never replaced or deleted`() {
        val appended = EwsDraftXml.updateItem(
            "Exchange2007_SP1", "ID", null, "S", "B", "a@corp.example", "", "boss@corp.example", 1, escape, appendBcc = true
        )
        val root = parse(appended)
        val append = root.getElementsByTagNameNS(typesNs, "AppendToItemField").item(0) as Element
        assertThat(append.firstByName("FieldURI").getAttribute("FieldURI")).isEqualTo("message:BccRecipients")
        assertThat(append.firstByName("EmailAddress").textContent).isEqualTo("boss@corp.example")
        val setFields = root.getElementsByTagNameNS(typesNs, "SetItemField")
        assertThat((0 until setFields.length).map { (setFields.item(it) as Element).firstByName("FieldURI").getAttribute("FieldURI") })
            .doesNotContain("message:BccRecipients")

        // Добавлять нечего — поле не трогается (а не DeleteItemField, который стёр бы неизвестные копии).
        val nothing = EwsDraftXml.updateItem("Exchange2007_SP1", "ID", null, "S", "B", "", "", "", 1, escape, appendBcc = true)
        assertThat(nothing).doesNotContain("BccRecipients")
    }

    @Test
    fun `draft details request asks for body, Bcc and the MAPI last-modified time in schema order`() {
        val root = parse(EwsDraftXml.getDraftDetails("Exchange2007_SP1", "AAMk=", escape))
        val shape = root.getElementsByTagNameNS("http://schemas.microsoft.com/exchange/services/2006/messages", "ItemShape")
            .item(0) as Element
        assertThat(shape.children().map { it.localName }).containsExactly("BaseShape", "BodyType", "AdditionalProperties").inOrder()
        val extended = shape.firstByName("ExtendedFieldURI")
        // PR_LAST_MODIFICATION_TIME — есть и на Exchange 2007 SP1 (item:LastModifiedTime — только с 2010).
        assertThat(extended.getAttribute("PropertyTag")).isEqualTo("0x3008")
        assertThat(extended.getAttribute("PropertyType")).isEqualTo("SystemTime")
    }

    @Test
    fun `draft details are parsed - escaped body, SMTP Bcc, last-modified time`() {
        val response = """<m:GetItemResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode><m:Items>
            <t:Message><t:ItemId Id="AAMk=" ChangeKey="CK"/><t:Body BodyType="HTML">&lt;p&gt;Тело &amp;amp; ещё&lt;/p&gt;</t:Body>
            <t:ExtendedProperty><t:ExtendedFieldURI PropertyTag="0x3008" PropertyType="SystemTime"/><t:Value>2026-10-26T10:00:00Z</t:Value></t:ExtendedProperty>
            <t:BccRecipients><t:Mailbox><t:Name>Boss</t:Name><t:EmailAddress>boss@corp.example</t:EmailAddress><t:RoutingType>SMTP</t:RoutingType></t:Mailbox>
            <t:Mailbox><t:EmailAddress>me2@corp.example</t:EmailAddress></t:Mailbox></t:BccRecipients></t:Message></m:Items></m:GetItemResponseMessage>"""

        val details = EwsDraftXml.parseDraftDetails(response)

        assertThat(details.body).isEqualTo("<p>Тело &amp; ещё</p>")
        assertThat(details.bcc).isEqualTo("boss@corp.example, me2@corp.example")
        assertThat(details.lastModified).isEqualTo(java.time.Instant.parse("2026-10-26T10:00:00Z").toEpochMilli())
    }

    @Test
    fun `Bcc with an Exchange-only address is unknown, absent Bcc is empty`() {
        val exchangeOnly = """<t:Message><t:Body BodyType="HTML">x</t:Body><t:BccRecipients><t:Mailbox>
            <t:EmailAddress>/O=CORP/OU=EXCHANGE/CN=RECIPIENTS/CN=BOSS</t:EmailAddress><t:RoutingType>EX</t:RoutingType>
            </t:Mailbox></t:BccRecipients></t:Message>"""
        // X.500-адрес не SMTP: заменить им скрытые копии нельзя — неизвестны (их не трогаем).
        assertThat(EwsDraftXml.parseDraftDetails(exchangeOnly).bcc).isNull()
        // EWS не отдаёт пустые свойства: нет элемента — скрытых копий нет.
        assertThat(EwsDraftXml.parseDraftDetails("""<t:Message><t:Body BodyType="HTML">x</t:Body></t:Message>""").bcc).isEmpty()
        assertThat(EwsDraftXml.parseDraftDetails("""<t:Message><t:Body BodyType="HTML">x</t:Body></t:Message>""").lastModified).isNull()
    }

    @Test
    fun `draft ids - device-only and unconfirmed local drafts are told apart`() {
        val deviceOnly = DraftIds.newDeviceOnly()
        val unconfirmed = DraftIds.newUnconfirmed()
        assertThat(DraftIds.isLocal(deviceOnly)).isTrue()
        assertThat(DraftIds.isLocal(unconfirmed)).isTrue()
        // Только на устройстве — синхронизация не сочтёт его копией серверного по теме.
        assertThat(DraftIds.mayExistOnServer(deviceOnly)).isFalse()
        assertThat(DraftIds.mayExistOnServer(unconfirmed)).isTrue()
        assertThat(DraftIds.isUnconfirmed(unconfirmed)).isTrue()
        assertThat(DraftIds.isUnconfirmed(deviceOnly)).isFalse()
        // Прежний формат (до разделения) — как раньше: может быть на сервере.
        assertThat(DraftIds.mayExistOnServer("local_draft_1700000000000_ab12cd34")).isTrue()
    }

    @Test
    fun `draft ids - EWS, EAS and local are told apart, only a unique match is trusted`() {
        assertThat(DraftIds.isEwsItemId("AAMkADNi+/xyz=")).isTrue()
        assertThat(DraftIds.isEwsItemId("5:12")).isFalse()
        assertThat(DraftIds.isEwsItemId("local_draft_1_abc")).isFalse()
        assertThat(DraftIds.isLocal("local_draft_1_abc")).isTrue()

        assertThat(DraftIds.uniqueMatch(listOf("A"), excludeId = null)).isEqualTo("A")
        assertThat(DraftIds.uniqueMatch(listOf("A", "A"), excludeId = null)).isEqualTo("A")
        assertThat(DraftIds.uniqueMatch(listOf("NEW", "OLD"), excludeId = "NEW")).isEqualTo("OLD")
        // Два черновика с одной темой — не угадываем (правка или удаление ушли бы в чужой).
        assertThat(DraftIds.uniqueMatch(listOf("A", "B"), excludeId = null)).isNull()
        assertThat(DraftIds.uniqueMatch(listOf("NEW"), excludeId = "NEW")).isNull()
    }
}

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

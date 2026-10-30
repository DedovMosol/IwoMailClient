package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Флажки «запросить отчёт» черновика в EWS: IsReadReceiptRequested и IsDeliveryReceiptRequested — сразу после
 * BccRecipients (схема MessageType, learn.microsoft.com: Message element), FieldURI message:… в UpdateItem.
 */
class EwsDraftReceiptsTest {

    private val escape: (String) -> String = { XmlUtils.escape(it) }
    private val typesNs = "http://schemas.microsoft.com/exchange/services/2006/types"

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream()).documentElement

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.firstByName(local: String): Element =
        getElementsByTagNameNS(typesNs, local).item(0) as Element

    @Test
    fun `create puts the receipt flags right after the recipients`() {
        val xml = EwsDraftXml.createItem(
            "Exchange2007_SP1", "S", "<p>B</p>", to = "a@corp.example", cc = "", bcc = "b@corp.example",
            easImportance = 1, escape = escape, receipts = ReceiptRequests(read = true, delivery = true)
        )

        val message = parse(xml).firstByName("Message")
        assertThat(message.children().map { it.localName }).containsExactly(
            "Subject", "Body", "ToRecipients", "BccRecipients", "IsReadReceiptRequested", "IsDeliveryReceiptRequested"
        ).inOrder()
        assertThat(message.firstByName("IsReadReceiptRequested").textContent).isEqualTo("true")
    }

    @Test
    fun `create without requests writes no receipt elements`() {
        for (receipts in listOf(null, ReceiptRequests.NONE)) {
            val xml = EwsDraftXml.createItem(
                "Exchange2007_SP1", "S", "B", to = "a@corp.example", cc = "", bcc = "",
                easImportance = 1, escape = escape, receipts = receipts
            )
            assertThat(xml).doesNotContain("ReceiptRequested")
        }
    }

    @Test
    fun `update sets both flags, so a cleared one is cleared on the server too`() {
        val xml = EwsDraftXml.updateItem(
            "Exchange2007_SP1", "AAMk=", "CK", subject = "S", htmlBody = null, to = null, cc = null, bcc = null,
            easImportance = null, escape = escape, receipts = ReceiptRequests(read = false, delivery = true)
        )

        val fields = parse(xml).getElementsByTagNameNS(typesNs, "SetItemField")
        val uris = (0 until fields.length).map { (fields.item(it) as Element).firstByName("FieldURI").getAttribute("FieldURI") }
        assertThat(uris).containsAtLeast("message:IsReadReceiptRequested", "message:IsDeliveryReceiptRequested")
        val root = parse(xml)
        assertThat(root.firstByName("IsReadReceiptRequested").textContent).isEqualTo("false")
        assertThat(root.firstByName("IsDeliveryReceiptRequested").textContent).isEqualTo("true")
    }

    @Test
    fun `unknown flags are left alone on update`() {
        val xml = EwsDraftXml.updateItem(
            "Exchange2007_SP1", "AAMk=", null, subject = "S", htmlBody = "B", to = "a@corp.example", cc = "", bcc = null,
            easImportance = 1, escape = escape, receipts = null
        )

        assertThat(xml).doesNotContain("ReceiptRequested")
    }

    @Test
    fun `draft details request and parse the flags`() {
        val request = EwsDraftXml.getDraftDetails("Exchange2007_SP1", "AAMk=", escape)
        assertThat(request).contains("FieldURI=\"message:IsReadReceiptRequested\"")
        assertThat(request).contains("FieldURI=\"message:IsDeliveryReceiptRequested\"")

        val response = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <m:GetItemResponse xmlns:m="m" xmlns:t="t"><m:ResponseMessages><m:GetItemResponseMessage ResponseClass="Success">
            <m:ResponseCode>NoError</m:ResponseCode><m:Items><t:Message><t:ItemId Id="AAMk="/>
            <t:Body BodyType="HTML">&lt;p&gt;x&lt;/p&gt;</t:Body>
            <t:IsReadReceiptRequested>true</t:IsReadReceiptRequested>
            <t:IsDeliveryReceiptRequested>false</t:IsDeliveryReceiptRequested>
            </t:Message></m:Items></m:GetItemResponseMessage></m:ResponseMessages></m:GetItemResponse></s:Body></s:Envelope>"""
        assertThat(EwsDraftXml.parseDraftDetails(response).receipts).isEqualTo(ReceiptRequests(read = true, delivery = false))
    }

    @Test
    fun `server without the flags gives unknown receipts`() {
        val response = "<t:Message><t:Body BodyType=\"HTML\">x</t:Body></t:Message>"

        assertThat(EwsDraftXml.parseDraftDetails(response).receipts).isNull()
    }

    @Test
    fun `stored columns map to known or unknown requests`() {
        assertThat(ReceiptRequests.of(null, null)).isNull()
        assertThat(ReceiptRequests.of(true, null)).isEqualTo(ReceiptRequests(read = true, delivery = false))
        assertThat(ReceiptRequests.of(false, false)).isEqualTo(ReceiptRequests.NONE)
    }
}

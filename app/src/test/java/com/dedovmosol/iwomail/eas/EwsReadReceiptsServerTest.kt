package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Отказ от отчёта о прочтении через EWS — от HTTP до разбора ответа ([FakeExchange], реальный транспорт).
 * Ответы — в форме Exchange 2007 SP1 (минимальная поддерживаемая версия; SuppressReadReceipt есть с неё).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EwsReadReceiptsServerTest {

    private fun FakeExchange.readReceipts(): EwsReadReceipts {
        val transport = transport("12.1")
        return EwsReadReceipts(transport::executeEwsWithAuth, transport::buildEwsSoapRequest)
    }

    private fun FakeExchange.ews(command: String) = calls.filter { it.isEws && it.command == command }

    private fun message(id: String) = """<t:Message><t:ItemId Id="$id" ChangeKey="CK-$id"/></t:Message>"""

    private fun found(vararg ids: String) =
        EwsXml.findItem(ids.joinToString("") { message(it) }, total = ids.size, includesLast = true)

    /** CreateItem: по сообщению на каждый ответ ([codes] — ResponseCode по порядку). */
    private fun createItemResponse(vararg codes: String): String =
        """<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""" +
            """<soap:Body><m:CreateItemResponse xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" """ +
            """xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"><m:ResponseMessages>""" +
            codes.joinToString("") { code ->
                val cls = if (code == "NoError") "Success" else "Error"
                """<m:CreateItemResponseMessage ResponseClass="$cls"><m:ResponseCode>$code</m:ResponseCode>""" +
                    "<m:Items/></m:CreateItemResponseMessage>"
            } +
            "</m:ResponseMessages></m:CreateItemResponse></soap:Body></soap:Envelope>"

    private val inbox = EwsMailFolder.Distinguished("inbox")

    @Test
    fun `suppresses the receipt of the exact message found by Message-ID`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when (call.command) {
                "FindItem" -> Reply.Text(found("AAMkR1="))
                "CreateItem" -> Reply.Text(createItemResponse("NoError"))
                else -> Reply.Http(500)
            }
        }

        assertThat(exchange.readReceipts().suppress("<abc&1@corp.example>", inbox)).isTrue()

        val find = exchange.ews("FindItem").single().xml
        assertThat(find).contains("""<t:FieldURI FieldURI="message:InternetMessageId"/>""")
        assertThat(find).contains("""<t:Constant Value="&lt;abc&amp;1@corp.example&gt;"/>""")
        assertThat(find).contains("""<t:DistinguishedFolderId Id="inbox"/>""")
        assertThat(find).contains("""RequestServerVersion Version="Exchange2007_SP1"""")
        val create = exchange.ews("CreateItem").single().xml
        assertThat(create).contains("<t:SuppressReadReceipt>")
        assertThat(create).contains("""<t:ReferenceItemId Id="AAMkR1=" ChangeKey="CK-AAMkR1="/>""")
        // Как EmailMessage.SuppressReadReceipt() в EWS Managed API: без MessageDisposition.
        assertThat(create).doesNotContain("MessageDisposition")
    }

    @Test
    fun `receipt no longer pending counts as declined`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when (call.command) {
                "FindItem" -> Reply.Text(found("AAMkR1="))
                "CreateItem" -> Reply.Text(createItemResponse("ErrorReadReceiptNotPending"))
                else -> Reply.Http(500)
            }
        }

        assertThat(exchange.readReceipts().suppress("<m@x>", inbox)).isTrue()
    }

    @Test
    fun `message not found on the server is not declined`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            if (call.command == "FindItem") Reply.Text(found()) else Reply.Http(500)
        }

        assertThat(exchange.readReceipts().suppress("<m@x>", inbox)).isFalse()
        assertThat(exchange.ews("CreateItem")).isEmpty()
    }

    @Test
    fun `server error on suppress is not declined`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when (call.command) {
                "FindItem" -> Reply.Text(found("AAMkR1="))
                "CreateItem" -> Reply.Text(EwsXml.error("CreateItem", "ErrorAccessDenied"))
                else -> Reply.Http(500)
            }
        }

        assertThat(exchange.readReceipts().suppress("<m@x>", inbox)).isFalse()
    }

    @Test
    fun `every copy of the message must be declined`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when (call.command) {
                "FindItem" -> Reply.Text(found("AAMkR1=", "AAMkR2="))
                "CreateItem" -> Reply.Text(createItemResponse("NoError", "ErrorServerBusy"))
                else -> Reply.Http(500)
            }
        }

        assertThat(exchange.readReceipts().suppress("<m@x>", inbox)).isFalse()
        val create = exchange.ews("CreateItem").single().xml
        assertThat(Regex("<t:SuppressReadReceipt>").findAll(create).count()).isEqualTo(2)
    }

    @Test
    fun `failed search is not declined`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            if (call.command == "FindItem") Reply.Text(EwsXml.error("FindItem", "ErrorServerBusy")) else Reply.Http(500)
        }

        assertThat(exchange.readReceipts().suppress("<m@x>", inbox)).isFalse()
        assertThat(exchange.ews("CreateItem")).isEmpty()
    }

    @Test
    fun `no connection is not declined`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Disconnect }

        assertThat(exchange.readReceipts().suppress("<m@x>", inbox)).isFalse()
    }

    @Test
    fun `user folder is found by its unique name`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            when (call.command) {
                "FindFolder" -> Reply.Text(EwsXml.findFolder(EwsXml.folder("F-PROJ", "IPF.Note", "Projects")))
                "FindItem" -> Reply.Text(found("AAMkR1="))
                "CreateItem" -> Reply.Text(createItemResponse("NoError"))
                else -> Reply.Http(500)
            }
        }

        assertThat(exchange.readReceipts().suppress("<m@x>", EwsMailFolder.Named("Projects"))).isTrue()

        val findFolder = exchange.ews("FindFolder").single().xml
        assertThat(findFolder).contains("""<m:FindFolder Traversal="Deep">""")
        assertThat(findFolder).contains("""<t:Constant Value="Projects"/>""")
        assertThat(exchange.ews("FindItem").single().xml).contains("""<t:FolderId Id="F-PROJ"/>""")
    }

    @Test
    fun `two folders with the same name are not guessed`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            if (call.command == "FindFolder") {
                Reply.Text(EwsXml.findFolder(
                    EwsXml.folder("F-A", "IPF.Note", "Projects") + EwsXml.folder("F-B", "IPF.Note", "Projects")
                ))
            } else Reply.Http(500)
        }

        assertThat(exchange.readReceipts().suppress("<m@x>", EwsMailFolder.Named("Projects"))).isFalse()
        assertThat(exchange.ews("FindItem")).isEmpty()
    }
}

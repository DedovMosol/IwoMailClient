package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MIME письма по EAS ItemOperations (MIMESupport=2, BodyPreference Type 4; EAS 12.1 — Exchange 2007 SP1) — от HTTP
 * и настоящего WBXML до разбора: заголовки отписки из MIME, .eml без base64-обёртки, запасной путь EWS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasMessageMimeServerTest {

    private fun FakeExchange.emailService(): EasEmailService {
        val transport = transport("12.1")
        return EasEmailService(EasEmailService.EmailServiceDependencies(
            executeEasCommand = object : EasEmailService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    transport.executeEasCommand(command, xml, parser = parser)
            },
            executeRequest = { throw UnsupportedOperationException("unused") },
            buildUrl = { it },
            getAuthHeader = { "" },
            getPolicyKey = { null },
            getEasVersion = { transport.easVersion },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Success(transport.easVersion) },
            provision = { EasResult.Error("unused") },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { XmlUtils.escape(it) },
            wbxmlParser = WbxmlParser(),
            getFromEmail = { "user@corp.example" },
            getDeviceId = { "androidtestdevice01" },
            performNtlmHandshake = { null },
            executeNtlmRequest = { _, _ -> null },
            getDeletedItemsFolderId = { null },
            isExchange2007 = { true },
            buildEwsSoapRequest = { it },
            extractEwsError = { it }
        ))
    }

    private fun fetchResponse(type: Int, data: String) =
        """<?xml version="1.0" encoding="utf-8"?><ItemOperations xmlns="ItemOperations" xmlns:airsync="AirSync" """ +
            """xmlns:airsyncbase="AirSyncBase"><Status>1</Status><Response><Fetch><Status>1</Status>""" +
            "<airsync:CollectionId>5</airsync:CollectionId><airsync:ServerId>5:1</airsync:ServerId><airsync:Class>Email</airsync:Class>" +
            "<Properties><airsyncbase:Body><airsyncbase:Type>$type</airsyncbase:Type>" +
            "<airsyncbase:EstimatedDataSize>${data.length}</airsyncbase:EstimatedDataSize>" +
            "<airsyncbase:Data>${XmlUtils.escape(data)}</airsyncbase:Data></airsyncbase:Body></Properties></Fetch></Response></ItemOperations>"

    private val mime = "DKIM-Signature: v=1; a=rsa-sha256; d=shop.example; s=s1;\r\n" +
        " h=From:Subject:List-Unsubscribe:List-Unsubscribe-Post; bh=x; b=y\r\n" +
        "From: News <news@shop.example>\r\n" +
        "List-Unsubscribe: <mailto:leave@shop.example>,\r\n <https://shop.example/u/1>\r\n" +
        "List-Unsubscribe-Post: List-Unsubscribe=One-Click\r\n" +
        "Subject: Sale\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>Скидки</p>\r\n"

    private fun route(mimeData: String): (FakeExchange.Call) -> Reply = { call ->
        when {
            call.command != "ItemOperations" -> Reply.Http(500)
            call.xml.contains("<Type>4</Type>") -> Reply.Wbxml(fetchResponse(4, mimeData))
            else -> Reply.Wbxml(fetchResponse(2, "<p>Скидки</p>"))
        }
    }

    @Test
    fun `opening an email reads List-Unsubscribe from its MIME headers`() = runBlocking<Unit> {
        val exchange = FakeExchange(route(mime))

        val body = (exchange.emailService().fetchEmailBodyWithMdn("5", "5:1") as EasResult.Success).data

        assertThat(body.body).isEqualTo("<p>Скидки</p>")
        assertThat(body.listUnsubscribeOneClick).isTrue()
        val parsed = requireNotNull(ListUnsubscribe.parse(body.listUnsubscribe, body.listUnsubscribeOneClick))
        assertThat(parsed.method).isEqualTo(ListUnsubscribe.Method.OneClick("https://shop.example/u/1"))
    }

    @Test
    fun `Sent items are opened without the MIME request`() = runBlocking<Unit> {
        val exchange = FakeExchange(route(mime))

        val body = (exchange.emailService().fetchEmailBodyWithMdn("5", "5:1", fetchMimeHeaders = false) as EasResult.Success).data

        assertThat(body.listUnsubscribe).isNull()
        assertThat(exchange.calls.none { it.xml.contains("<Type>4</Type>") }).isTrue()
    }

    @Test
    fun `eml is the raw message, a base64 wrapper is decoded`() = runBlocking<Unit> {
        val wrapped = java.util.Base64.getMimeEncoder().encodeToString(mime.toByteArray(Charsets.UTF_8))
        val exchange = FakeExchange(route(wrapped))

        val eas = exchange.emailService().fetchMime("5", "5:1")
        val bytes = MessageMime.choose(eas) { error("EWS is not needed") }

        assertThat((bytes as EasResult.Success).data.toString(Charsets.UTF_8)).isEqualTo(mime)
        val request = exchange.calls.single().xml
        assertThat(request).contains("<MIMESupport>2</MIMESupport>")
    }

    @Test
    fun `headers without List-Unsubscribe are remembered as checked`() = runBlocking<Unit> {
        val plain = "From: a@corp.example\r\nSubject: Hi\r\nContent-Type: text/plain\r\n\r\nHello\r\n"
        val exchange = FakeExchange(route(plain))

        val body = (exchange.emailService().fetchEmailBodyWithMdn("5", "5:1") as EasResult.Success).data

        // "" — заголовки получены, отписки нет: при следующем открытии не спрашивать снова
        assertThat(body.listUnsubscribe).isEmpty()
    }

    @Test
    fun `failed header request leaves List-Unsubscribe unknown to ask later`() = runBlocking<Unit> {
        val exchange = FakeExchange { call ->
            // Сервер не выполнил запрос заголовков (Fetch Status 3 — ошибка сервера): это не «заголовков нет»
            if (call.xml.contains("<Type>4</Type>")) {
                Reply.Wbxml(
                    """<?xml version="1.0" encoding="utf-8"?><ItemOperations xmlns="ItemOperations"><Status>1</Status>""" +
                        "<Response><Fetch><Status>3</Status></Fetch></Response></ItemOperations>"
                )
            } else {
                Reply.Wbxml(fetchResponse(2, "<p>x</p>"))
            }
        }

        val body = (exchange.emailService().fetchEmailBodyWithMdn("5", "5:1") as EasResult.Success).data

        assertThat(body.body).isEqualTo("<p>x</p>")
        assertThat(body.listUnsubscribe).isNull()
    }

    @Test
    fun `header-only request asks for a truncated MIME and returns just the headers`() = runBlocking<Unit> {
        val exchange = FakeExchange(route(mime))

        val headers = (exchange.emailService().fetchTopLevelHeaders("5", "5:1") as EasResult.Success).data

        assertThat(headers).contains("List-Unsubscribe-Post: List-Unsubscribe=One-Click")
        assertThat(headers).doesNotContain("Скидки")
        val request = exchange.calls.single().xml
        assertThat(request).contains("<MIMESupport>2</MIMESupport>")
        assertThat(request).contains("<TruncationSize>65536</TruncationSize>")
    }

    @Test
    fun `missing message gives no MIME`() = runBlocking<Unit> {
        val exchange = FakeExchange {
            Reply.Wbxml("""<?xml version="1.0" encoding="utf-8"?><ItemOperations xmlns="ItemOperations"><Status>1</Status>""" +
                "<Response><Fetch><Status>8</Status></Fetch></Response></ItemOperations>")
        }

        assertThat(exchange.emailService().fetchMime("5", "5:404")).isEqualTo(EasResult.Success(""))
    }
}

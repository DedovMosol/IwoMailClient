package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Команды ComposeMail (SendMail, SmartForward) — от HTTP до разбора ответа ([FakeExchange]).
 * EAS 12.1 (Exchange 2007 SP1): тело — MIME, параметры в URL; EAS 14.1 (Exchange 2010 SP1+): WBXML и Status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasComposeMailServerTest {

    private val provisions = AtomicInteger()

    private fun FakeExchange.emailService(easVersion: String): EasEmailService {
        val exchange = this
        return EasEmailService(EasEmailService.EmailServiceDependencies(
            executeEasCommand = object : EasEmailService.EasCommandExecutor {
                override suspend fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T> =
                    EasResult.Error("unused")
            },
            executeRequest = { request -> exchange.client.newCall(request).execute() },
            buildUrl = { cmd -> "${FakeExchange.BASE_URL}/Microsoft-Server-ActiveSync?Cmd=$cmd&User=user&DeviceId=dev&DeviceType=Android" },
            getAuthHeader = { "Basic dXNlcjpzZWNyZXQ=" },
            getPolicyKey = { "1234" },
            getEasVersion = { easVersion },
            isVersionDetected = { true },
            detectEasVersion = { EasResult.Success(easVersion) },
            provision = { provisions.incrementAndGet(); EasResult.Success("5678") },
            extractValue = { xml, tag -> XmlUtils.extractTagValue(xml, tag) },
            escapeXml = { XmlUtils.escape(it) },
            wbxmlParser = WbxmlParser(),
            getFromEmail = { "me@corp.example" },
            getDeviceId = { "androidtestdevice01" },
            performNtlmHandshake = { null },
            executeNtlmRequest = { _, _ -> null },
            getDeletedItemsFolderId = { null },
            isExchange2007 = { easVersion.startsWith("12") },
            buildEwsSoapRequest = { it },
            extractEwsError = { it }
        ))
    }

    private fun status(code: Int) =
        """<?xml version="1.0" encoding="utf-8"?><SendMail xmlns="ComposeMail"><Status>$code</Status></SendMail>"""

    private fun FakeExchange.sent() = calls.filter { it.command == "SendMail" }

    @Test
    fun `EAS 12_1 sends MIME with parameters in the URL`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Text("") }

        val result = exchange.emailService("12.1").sendMail("a@corp.example", "Hello", "<p>Hi</p>")

        assertThat(result).isEqualTo(EasResult.Success(true))
        val call = exchange.sent().single()
        assertThat(call.contentType).startsWith("message/rfc822")
        assertThat(call.url).contains("Cmd=SendMail")
        assertThat(call.url).contains("SaveInSent=T")
        assertThat(call.xml).contains("To: a@corp.example")
        assertThat(call.xml).contains("Message-ID: <")
    }

    @Test
    fun `EAS 14_1 sends WBXML with the client id and saves a copy`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Wbxml(status(1)) }

        val result = exchange.emailService("14.1").sendMail("a@corp.example", "Hello", "Hi", stableClientId = "cid-42")

        assertThat(result).isEqualTo(EasResult.Success(true))
        val call = exchange.sent().single()
        assertThat(call.contentType).startsWith("application/vnd.ms-sync.wbxml")
        assertThat(call.url).doesNotContain("SaveInSent")
        assertThat(call.xml).contains("<ClientId>cid-42</ClientId>")
        assertThat(call.xml).contains("SaveInSentItems")
    }

    @Test
    fun `already sent with this client id counts as sent`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Wbxml(status(118)) }

        val result = exchange.emailService("14.1").sendMail("a@corp.example", "S", "B", stableClientId = "cid-42")

        assertThat(result).isEqualTo(EasResult.Success(true))
    }

    @Test
    fun `server status error is reported`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Wbxml(status(120)) }

        val result = exchange.emailService("14.1").sendMail("a@corp.example", "S", "B")

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat((result as EasResult.Error).message).contains("Status: 120")
    }

    @Test
    fun `449 renegotiates the policy and retries once`() = runBlocking<Unit> {
        val attempts = AtomicInteger()
        val exchange = FakeExchange { if (attempts.incrementAndGet() == 1) Reply.Http(449) else Reply.Wbxml(status(1)) }

        val result = exchange.emailService("14.1").sendMail("a@corp.example", "S", "B")

        assertThat(result).isEqualTo(EasResult.Success(true))
        assertThat(provisions.get()).isEqualTo(1)
        assertThat(exchange.sent()).hasSize(2)
    }

    @Test
    fun `449 after renegotiation is an error, not an endless loop`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Http(449) }

        val result = exchange.emailService("14.1").sendMail("a@corp.example", "S", "B")

        assertThat(result).isEqualTo(EasResult.Error(composeHttpError(449)))
        assertThat(exchange.sent()).hasSize(2)
    }

    @Test
    fun `HTTP error is reported with its code`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Http(503) }

        val result = exchange.emailService("12.1").sendMail("a@corp.example", "S", "B")

        assertThat(result).isEqualTo(EasResult.Error(composeHttpError(503)))
    }

    @Test
    fun `mail with only blind copies is sent, mail without recipients is not`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Text("") }
        val service = exchange.emailService("12.1")

        assertThat(service.sendMail("", "S", "B", bcc = "hidden@corp.example")).isEqualTo(EasResult.Success(true))
        assertThat(service.sendMail("", "S", "B")).isInstanceOf(EasResult.Error::class.java)
        assertThat(exchange.sent()).hasSize(1)
    }

    @Test
    fun `EAS 12_1 forward names the original message in the URL`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Text("") }

        val result = exchange.emailService("12.1").smartForward(
            to = "a@corp.example", subject = "Fw: S", body = "see below", collectionId = "5", itemId = "5:12"
        )

        assertThat(result).isEqualTo(EasResult.Success(true))
        val call = exchange.calls.single { it.command == "SmartForward" }
        assertThat(call.url).contains("CollectionId=5")
        assertThat(call.url).contains("ItemId=5%3A12")
        assertThat(call.url).contains("SaveInSent=T")
    }
}

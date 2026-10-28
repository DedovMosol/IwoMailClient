package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тела черновиков в EAS Sync. [MS-ASAIRS] TruncationSize: без элемента сервер отдаёт всё содержимое;
 * Truncated (Body) = 1 — тело обрезано (запросом или политикой MaxEmailHTMLBodyTruncationSize).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasEmailSyncDraftBodyTest {

    private fun FakeExchange.emailService(easVersion: String = "12.1"): EasEmailService {
        val transport = transport(easVersion)
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

    private val truncatedAdd =
        "<Add><ServerId>5:1</ServerId><ApplicationData><email:Subject>Long draft</email:Subject>" +
            "<email:DateReceived>2026-10-26T10:00:00.000Z</email:DateReceived><email:Read>1</email:Read>" +
            "<airsyncbase:Body><airsyncbase:Type>2</airsyncbase:Type><airsyncbase:EstimatedDataSize>50000</airsyncbase:EstimatedDataSize>" +
            "<airsyncbase:Truncated>1</airsyncbase:Truncated><airsyncbase:Data>&lt;p&gt;cut</airsyncbase:Data></airsyncbase:Body>" +
            "</ApplicationData></Add>"
    private val fullChange =
        "<Change><ServerId>5:2</ServerId><ApplicationData>" +
            "<airsyncbase:Body><airsyncbase:Type>2</airsyncbase:Type><airsyncbase:Data>&lt;p&gt;whole&lt;/p&gt;</airsyncbase:Data></airsyncbase:Body>" +
            "</ApplicationData></Change>"

    private fun route(): (FakeExchange.Call) -> Reply = { call ->
        if (call.command == "Sync") Reply.Wbxml(EasSyncXml.page("K2", "5", listOf(truncatedAdd, fullChange), more = false))
        else Reply.Http(500)
    }

    @Test
    fun `drafts are requested whole and a truncated body is reported as such`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())

        val result = exchange.emailService().sync("5", "K1", windowSize = 50, fullBody = true)

        val request = exchange.calls.single { it.command == "Sync" }.xml
        assertThat(request).contains("BodyPreference")
        assertThat(request).doesNotContain("TruncationSize")
        val data = (result as EasResult.Success).data
        assertThat(data.emails.single().bodyTruncated).isTrue()
        assertThat(data.changedEmails.single().bodyTruncated).isFalse()
        assertThat(data.changedEmails.single().body).isEqualTo("<p>whole</p>")
    }

    @Test
    fun `attachment type comes from its name - EAS Attachment has no ContentType`() = runBlocking<Unit> {
        // [MS-ASAIRS] 2.2.2.7: DisplayName, FileReference, Method, EstimatedDataSize, ContentId, IsInline — без типа.
        val withAttachments =
            "<Add><ServerId>5:3</ServerId><ApplicationData><email:Subject>Скриншоты</email:Subject>" +
                "<email:DateReceived>2026-06-23T12:07:00.000Z</email:DateReceived><email:Read>1</email:Read>" +
                "<airsyncbase:Attachments>" +
                "<airsyncbase:Attachment><airsyncbase:DisplayName>image001.png</airsyncbase:DisplayName>" +
                "<airsyncbase:FileReference>5%3a3%3a0</airsyncbase:FileReference><airsyncbase:Method>1</airsyncbase:Method>" +
                "<airsyncbase:EstimatedDataSize>48213</airsyncbase:EstimatedDataSize><airsyncbase:ContentId>image001.png@01DD</airsyncbase:ContentId>" +
                "<airsyncbase:IsInline>1</airsyncbase:IsInline></airsyncbase:Attachment>" +
                "<airsyncbase:Attachment><airsyncbase:DisplayName>договор.docx</airsyncbase:DisplayName>" +
                "<airsyncbase:FileReference>5%3a3%3a1</airsyncbase:FileReference><airsyncbase:Method>1</airsyncbase:Method>" +
                "<airsyncbase:EstimatedDataSize>20480</airsyncbase:EstimatedDataSize></airsyncbase:Attachment>" +
                "<airsyncbase:Attachment><airsyncbase:DisplayName>data.bin</airsyncbase:DisplayName>" +
                "<airsyncbase:FileReference>5%3a3%3a2</airsyncbase:FileReference><airsyncbase:Method>1</airsyncbase:Method>" +
                "<airsyncbase:EstimatedDataSize>10</airsyncbase:EstimatedDataSize></airsyncbase:Attachment>" +
                "</airsyncbase:Attachments></ApplicationData></Add>"
        val exchange = FakeExchange { call ->
            if (call.command == "Sync") Reply.Wbxml(EasSyncXml.page("K2", "5", listOf(withAttachments), more = false))
            else Reply.Http(500)
        }

        val email = (exchange.emailService().sync("5", "K1", windowSize = 50, fullBody = true) as EasResult.Success).data.emails.single()

        // Раньше все — application/octet-stream: картинка не считалась картинкой (без миниатюры в списке).
        assertThat(email.attachments.map { it.displayName to it.contentType }).containsExactly(
            "image001.png" to "image/png",
            "договор.docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "data.bin" to "application/octet-stream"
        ).inOrder()
        assertThat(email.attachments.first().fileReference).isEqualTo("5%3a3%3a0")
    }

    @Test
    fun `other folders keep the 10 KB preview truncation`() = runBlocking<Unit> {
        val exchange = FakeExchange(route())

        exchange.emailService().sync("5", "K1", windowSize = 50)

        assertThat(exchange.calls.single { it.command == "Sync" }.xml).contains("10240")
    }
}

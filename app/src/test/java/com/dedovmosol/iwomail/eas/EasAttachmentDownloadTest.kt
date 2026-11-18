package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.eas.FakeExchange.Reply
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Скачивание вложения EAS на фейковом сервере (настоящие WBXML, транспорт и разбор multipart):
 * ответы — в форме, полученной от Exchange 2007 SP2 (output_EasProbe_att2.txt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasAttachmentDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ref = "5%3a12%3a0"
    private val content = ByteArray(300_000) { (it * 31 + 7).toByte() }

    private fun fetchXml(status: Int, part: Int? = 1, global: Int = 1) =
        """<?xml version="1.0" encoding="utf-8"?><ItemOperations xmlns="ItemOperations" xmlns:airsyncbase="AirSyncBase">""" +
            "<Status>$global</Status><Response><Fetch><Status>$status</Status><airsyncbase:FileReference>$ref</airsyncbase:FileReference>" +
            (if (part != null) "<Properties><Part>$part</Part></Properties>" else "") +
            "</Fetch></Response></ItemOperations>"

    /** [MS-ASCMD] MultiPartResponse: PartsCount, (Offset, Length)*, части; int32 little-endian. */
    private fun multipart(vararg parts: ByteArray, declaredLast: Int? = null): ByteArray {
        val header = 4 + 8 * parts.size
        val buf = ByteBuffer.allocate(header + parts.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(parts.size)
        var offset = header
        parts.forEachIndexed { i, p ->
            buf.putInt(offset)
            buf.putInt(if (i == parts.lastIndex && declaredLast != null) declaredLast else p.size)
            offset += p.size
        }
        parts.forEach { buf.put(it) }
        return buf.array()
    }

    private fun multipartReply(xml: String, vararg data: ByteArray) =
        Reply.Raw(multipart(WbxmlParser().generate(xml), *data), "application/vnd.ms-sync.multipart")

    private fun service(exchange: FakeExchange, easVersion: String = "12.1"): EasAttachmentService {
        val transport = exchange.transport(easVersion)
        return EasAttachmentService(EasAttachmentService.AttachmentServiceDependencies(
            executeRequest = { transport.executeRequest(it) },
            openEasCommand = { command, xml, headers, read -> transport.openEasCommand(command, xml, headers, read) },
            parseWbxml = { WbxmlParser().parse(it) },
            buildUrl = { transport.buildUrl(it) },
            getAuthHeader = { transport.getAuthHeader() },
            getPolicyKey = { transport.policyKey },
            getEasVersion = { transport.easVersion },
            provision = { EasResult.Success("key") },
            wbxmlGenerateSendMail = { _, b -> b },
            postComposeMail = { _, _, _, _ -> EasResult.Error("unused") },
            wbxmlGenerateSmartForward = { _, _, _, b -> b },
            getFromEmail = { "user@corp.example" },
            getDeviceId = { "androidtestdevice01" }
        ))
    }

    @Test
    fun `multipart Fetch streams the file part to disk byte for byte`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange { call ->
            assertThat(call.command).isEqualTo("ItemOperations")
            assertThat(call.acceptMultiPart).isTrue()
            multipartReply(fetchXml(1), content)
        }
        val file = tmp.newFile()

        val result = service(exchange).downloadAttachmentToFile(ref, file)

        assertThat(result).isEqualTo(EasResult.Success(file))
        assertThat(file.readBytes()).isEqualTo(content)
        assertThat(exchange.calls).hasSize(1)
        // Ссылка ушла как прислал сервер: раскодированная на Exchange 2007 даёт Status 15
        assertThat(exchange.calls[0].xml).contains(ref)
    }

    @Test
    fun `in memory download returns the same bytes`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange { multipartReply(fetchXml(1), content) }

        val result = service(exchange).downloadAttachment(ref)

        assertThat((result as EasResult.Success).data).isEqualTo(content)
    }

    @Test
    fun `Status 11 is TOO_LARGE at once - no other Fetch variants and no GetAttachment`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange { multipartReply(fetchXml(11, part = null)) }
        val file = tmp.newFile()

        val result = service(exchange).downloadAttachmentToFile(ref, file)

        assertThat(result).isEqualTo(EasResult.Error(AttachmentErrors.TOO_LARGE))
        assertThat(exchange.calls.map { it.command }).containsExactly("ItemOperations")
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `Fetch not found falls back to GetAttachment POST with message rfc822 and the raw file body`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange { call ->
            if (call.command == "GetAttachment") Reply.Raw(content, "application/pdf")
            else multipartReply(fetchXml(6, part = null))
        }
        val file = tmp.newFile()

        val result = service(exchange).downloadAttachmentToFile(ref, file)

        assertThat(result).isEqualTo(EasResult.Success(file))
        assertThat(file.readBytes()).isEqualTo(content)
        val get = exchange.calls.single { it.command == "GetAttachment" }
        // Exchange 2007 отвергает GET (501) и POST без message/rfc822 (400) — проверено на сервере
        assertThat(get.method).isEqualTo("POST")
        assertThat(get.contentType).startsWith("message/rfc822")
        assertThat(get.url).contains("AttachmentName=$ref")
    }

    @Test
    fun `GetAttachment 413 is TOO_LARGE and the other reference is not tried`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange { call ->
            if (call.command == "GetAttachment") Reply.Http(413) else multipartReply(fetchXml(6, part = null))
        }

        val result = service(exchange).downloadAttachmentToFile(ref, tmp.newFile())

        assertThat(result).isEqualTo(EasResult.Error(AttachmentErrors.TOO_LARGE))
        assertThat(exchange.calls.count { it.command == "GetAttachment" }).isEqualTo(1)
    }

    @Test
    fun `EAS 14 has no GetAttachment command`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange { multipartReply(fetchXml(6, part = null)) }

        val result = service(exchange, easVersion = "14.1").downloadAttachmentToFile(ref, tmp.newFile())

        assertThat(result).isEqualTo(EasResult.Error(AttachmentErrors.NOT_FOUND))
        assertThat(exchange.calls.map { it.command }.toSet()).containsExactly("ItemOperations")
    }

    @Test
    fun `a dropped connection is not retried eight times`() = runBlocking<Unit> {
        val exchange = FakeExchange { Reply.Disconnect }
        val file = tmp.newFile()

        val result = service(exchange).downloadAttachmentToFile(ref, file)

        assertThat(com.dedovmosol.iwomail.network.ConnectionFailure.fromMessage((result as EasResult.Error).message)).isNotNull()
        assertThat(exchange.calls).hasSize(1)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `wrong password and blocked device stop at the first request`() = runBlocking<Unit> {
        for ((code, error) in listOf(401 to "UNAUTHORIZED", 403 to "ACCESS_DENIED")) {
            val exchange = FakeExchange { Reply.Http(code) }
            val result = service(exchange).downloadAttachmentToFile(ref, tmp.newFile())
            assertThat(result).isEqualTo(EasResult.Error(error))
            assertThat(exchange.calls).hasSize(1)
        }
    }

    @Test
    fun `a part cut short by the server leaves no file`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange {
            // Заявлено 300 000 байт, пришло 1000: обрыв потока на середине файла
            Reply.Raw(multipart(WbxmlParser().generate(fetchXml(1)), content.copyOf(1000), declaredLast = content.size),
                "application/vnd.ms-sync.multipart")
        }
        val file = tmp.newFile()

        val result = service(exchange).downloadAttachmentToFile(ref, file)

        assertThat(result).isInstanceOf(EasResult.Error::class.java)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a server without multipart support still works with base64 inside WBXML`() = runBlocking<Unit> {
        val small = content.copyOf(5000)
        val b64 = java.util.Base64.getEncoder().encodeToString(small)
        val exchange = FakeExchange {
            Reply.Wbxml(
                """<?xml version="1.0" encoding="utf-8"?><ItemOperations xmlns="ItemOperations"><Status>1</Status><Response><Fetch>""" +
                    "<Status>1</Status><Properties><Data>$b64</Data></Properties></Fetch></Response></ItemOperations>"
            )
        }
        val file = tmp.newFile()

        assertThat(service(exchange).downloadAttachmentToFile(ref, file)).isEqualTo(EasResult.Success(file))
        assertThat(file.readBytes()).isEqualTo(small)
    }

    @Test
    fun `449 provisions once and repeats the Fetch`() = runBlocking<Unit> {
        var first = true
        val exchange = FakeExchange { call ->
            when {
                // Политики нет (Policy Status 2): Provision завершается первой фазой
                call.command == "Provision" -> Reply.Wbxml(
                    """<?xml version="1.0" encoding="utf-8"?><Provision xmlns="Provision"><Status>1</Status><Policies><Policy>""" +
                        "<PolicyType>MS-EAS-Provisioning-WBXML</PolicyType><Status>2</Status></Policy></Policies></Provision>"
                )
                first -> { first = false; Reply.Http(449) }
                else -> multipartReply(fetchXml(1), content)
            }
        }
        val file = tmp.newFile()

        assertThat(service(exchange).downloadAttachmentToFile(ref, file)).isEqualTo(EasResult.Success(file))
        assertThat(exchange.calls.map { it.command }).containsExactly("ItemOperations", "Provision", "ItemOperations").inOrder()
        assertThat(exchange.calls.last().acceptMultiPart).isTrue()
    }

    @Test
    fun `cancellation mid-download deletes the file and propagates`() = runBlocking<Unit> {
        lateinit var exchange: FakeExchange
        exchange = FakeExchange { multipartReply(fetchXml(1), ByteArray(3_000_000)) }
        val file = tmp.newFile()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launchDownload(service(exchange), file)
        job.cancel()
        job.join()
        assertThat(job.isCancelled).isTrue()
        assertThat(file.exists()).isFalse()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchDownload(svc: EasAttachmentService, file: java.io.File) =
        launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { svc.downloadAttachmentToFile(ref, file) }

    @Test
    fun `in-memory buffer refuses to grow past its limit`() {
        val buffer = EasAttachmentService.BoundedBuffer(10)
        buffer.write(ByteArray(10))
        val overflow = runCatching { buffer.write(1) }.exceptionOrNull()
        assertThat(overflow).isInstanceOf(EasAttachmentService.BoundedBuffer.Overflow::class.java)
        assertThat(AttachmentErrors.of(overflow as Exception)).isEqualTo(AttachmentErrors.TOO_LARGE_FOR_MEMORY)
        assertThat(AttachmentErrors.of(java.io.IOException("write failed: ENOSPC (No space left on device)")))
            .isEqualTo(AttachmentErrors.NO_SPACE)
    }
}

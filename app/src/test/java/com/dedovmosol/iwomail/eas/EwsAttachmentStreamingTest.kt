package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Разбор потоков: EWS GetAttachment, multipart ItemOperations, выбор вложения EWS по EAS-вложению. */
class EwsAttachmentStreamingTest {

    private val file = ByteArray(250_000) { (it * 131 + 17).toByte() }

    /** Форма ответа Exchange 2007 SP2 (начало ответа записано в output_EasProbe_att2.txt), base64 с переводами строк. */
    private fun getAttachmentResponse(content: ByteArray, mime: Boolean = true): String {
        val b64 = if (mime) java.util.Base64.getMimeEncoder().encodeToString(content) else java.util.Base64.getEncoder().encodeToString(content)
        return """<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""" +
            """<soap:Header><t:ServerVersionInfo MajorVersion="8" MinorVersion="2" Version="Exchange2007_SP1" xmlns:t="t"/></soap:Header>""" +
            """<soap:Body><m:GetAttachmentResponse><m:ResponseMessages><m:GetAttachmentResponseMessage ResponseClass="Success">""" +
            "<m:ResponseCode>NoError</m:ResponseCode><m:Attachments><t:FileAttachment><t:AttachmentId Id=\"AAMk=\" />" +
            "<t:Name>r&amp;d.pdf</t:Name><t:ContentType>application/pdf</t:ContentType><t:Content>$b64</t:Content>" +
            "</t:FileAttachment></m:Attachments></m:GetAttachmentResponseMessage></m:ResponseMessages></m:GetAttachmentResponse></soap:Body></soap:Envelope>"
    }

    /** Поток, отдающий по [chunk] байт за read — границы буферов в любом месте base64 и тегов. */
    private class Trickle(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun `GetAttachment content is decoded from the stream whatever the read sizes`() {
        for (mime in listOf(true, false)) for (chunk in listOf(1, 7, 1000, 65_536, 1_000_000)) {
            val out = ByteArrayOutputStream()
            val result = EwsAttachmentContent.extract(Trickle(getAttachmentResponse(file, mime).toByteArray(), chunk), out)
            assertThat(result).isEqualTo(EwsAttachmentContent.Result.Written(file.size.toLong()))
            assertThat(out.toByteArray()).isEqualTo(file)
        }
    }

    @Test
    fun `an empty attachment is an empty file, not not-found`() {
        for (body in listOf("<t:Content></t:Content>", "<t:Content/>")) {
            val xml = getAttachmentResponse(ByteArray(0)).replace("<t:Content></t:Content>", body)
            val out = ByteArrayOutputStream()
            assertThat(EwsAttachmentContent.extract(ByteArrayInputStream(xml.toByteArray()), out))
                .isEqualTo(EwsAttachmentContent.Result.Written(0))
            assertThat(out.size()).isEqualTo(0)
        }
    }

    @Test
    fun `a response cut inside Content is an error, never a shorter file`() {
        val xml = getAttachmentResponse(file)
        val cut = xml.substring(0, xml.indexOf("<t:Content>") + 5_000).toByteArray()
        val error = runCatching { EwsAttachmentContent.extract(Trickle(cut, 777), ByteArrayOutputStream()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(EOFException::class.java)
    }

    @Test
    fun `an error response message and a SOAP fault are errors, not empty files`() {
        // Ответ 2007 на неверный AttachmentId (проверено на сервере: HTTP 200, ResponseClass=Error)
        val error = """<s:Envelope><s:Body><m:GetAttachmentResponse><m:ResponseMessages><m:GetAttachmentResponseMessage ResponseClass="Error">""" +
            "<m:MessageText>Id is malformed.</m:MessageText><m:ResponseCode>ErrorInvalidIdMalformed</m:ResponseCode>" +
            "<m:DescriptiveLinkKey>0</m:DescriptiveLinkKey><m:Attachments/></m:GetAttachmentResponseMessage></m:ResponseMessages></m:GetAttachmentResponse></s:Body></s:Envelope>"
        assertThat(EwsAttachmentContent.extract(ByteArrayInputStream(error.toByteArray()), ByteArrayOutputStream()))
            .isEqualTo(EwsAttachmentContent.Result.Error("ErrorInvalidIdMalformed"))

        val fault = """<s:Envelope><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring xml:lang="ru-RU">Запрос не прошел проверку</faultstring></s:Fault></s:Body></s:Envelope>"""
        assertThat(EwsAttachmentContent.extract(ByteArrayInputStream(fault.toByteArray()), ByteArrayOutputStream()))
            .isEqualTo(EwsAttachmentContent.Result.Error("Запрос не прошел проверку"))
    }

    @Test
    fun `an item attachment or a response cut before Content is NoContent`() {
        val itemAttachment = """<m:Attachments><t:ItemAttachment><t:AttachmentId Id="x"/><t:Name>fwd</t:Name><t:Message/></t:ItemAttachment></m:Attachments>"""
        assertThat(EwsAttachmentContent.extract(ByteArrayInputStream(itemAttachment.toByteArray()), ByteArrayOutputStream()))
            .isEqualTo(EwsAttachmentContent.Result.NoContent)
        val cut = getAttachmentResponse(file).substringBefore("<t:Content>")
        assertThat(EwsAttachmentContent.extract(ByteArrayInputStream(cut.toByteArray()), ByteArrayOutputStream()))
            .isEqualTo(EwsAttachmentContent.Result.NoContent)
    }

    @Test
    fun `cancellation stops decoding at the next chunk`() {
        var chunks = 0
        val result = runCatching {
            EwsAttachmentContent.extract(ByteArrayInputStream(getAttachmentResponse(file).toByteArray()), ByteArrayOutputStream()) {
                if (++chunks == 2) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    // ───────────────────────────── multipart ─────────────────────────────

    private fun multipart(count: Int, vararg meta: Pair<Int, Int>, body: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(4 + 8 * meta.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(count)
        meta.forEach { (o, l) -> buf.putInt(o); buf.putInt(l) }
        buf.put(body)
        return buf.array()
    }

    @Test
    fun `multipart header as sent by Exchange 2007 - two parts back to back`() {
        val wbxml = ByteArray(71) { 1 }
        val data = file
        val bytes = multipart(2, 20 to 71, 91 to data.size, body = wbxml + data)
        val reader = EasMultipart.Reader(Trickle(bytes, 13))
        assertThat(reader.readHeader()).containsExactly(EasMultipart.Part(20, 71), EasMultipart.Part(91, data.size.toLong())).inOrder()
        assertThat(reader.readFirstPart()).isEqualTo(wbxml)
        val out = ByteArrayOutputStream()
        assertThat(reader.copyPart(1, out)).isEqualTo(data.size.toLong())
        assertThat(out.toByteArray()).isEqualTo(data)
    }

    @Test
    fun `malformed multipart is rejected instead of writing garbage`() {
        val cases = mapOf(
            "no parts" to multipart(0),
            "too many parts" to multipart(1000),
            "overlapping parts" to multipart(2, 20 to 50, 40 to 10, body = ByteArray(60)),
            "part inside the header" to multipart(1, 4 to 10, body = ByteArray(10)),
            "truncated header" to multipart(2, 20 to 10).copyOf(10)
        )
        for ((name, bytes) in cases) {
            val error = runCatching {
                val reader = EasMultipart.Reader(ByteArrayInputStream(bytes))
                reader.readHeader()
                reader.readFirstPart()
            }.exceptionOrNull()
            assertWithMessage(name).that(error).isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun `a data part shorter than declared is an EOF, and a missing part index is an error`() {
        val bytes = multipart(2, 20 to 4, 24 to 1000, body = ByteArray(4) + ByteArray(100))
        val reader = EasMultipart.Reader(ByteArrayInputStream(bytes))
        reader.readHeader()
        reader.readFirstPart()
        assertThat(runCatching { reader.copyPart(1, ByteArrayOutputStream()) }.exceptionOrNull()).isInstanceOf(EOFException::class.java)
        assertThat(runCatching { reader.copyPart(5, ByteArrayOutputStream()) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `fetch reply statuses are read from the WBXML part`() {
        val reply = EasMultipart.fetchReply(
            "<ItemOperations><Status>1</Status><Response><Fetch><Status>11</Status><FileReference>x</FileReference></Fetch></Response></ItemOperations>"
        )
        assertThat(reply).isEqualTo(EasMultipart.FetchReply(status = "1", fetchStatus = "11", part = null))
        assertThat(EasMultipart.fetchReply("<ItemOperations><Status>1</Status><Response><Fetch><Status>1</Status><Properties><Part>1</Part></Properties></Fetch></Response></ItemOperations>").part)
            .isEqualTo(1)
    }

    // ───────────────────────────── выбор вложения EWS ─────────────────────────────

    private fun getItem(vararg atts: Triple<String, String, String?>) =
        "<m:Items><t:Message><t:Attachments>" + atts.joinToString("") { (id, name, cid) ->
            "<t:FileAttachment><t:AttachmentId Id=\"$id\"/><t:Name>$name</t:Name>" +
                (cid?.let { "<t:ContentId>$it</t:ContentId>" } ?: "") + "<t:ContentType>image/png</t:ContentType></t:FileAttachment>"
        } + "<t:ItemAttachment><t:AttachmentId Id=\"item\"/><t:Name>report.pdf</t:Name></t:ItemAttachment></t:Attachments></t:Message></m:Items>"

    @Test
    fun `EWS attachment is matched by name, duplicates by Content-ID, ambiguity refuses`() {
        val list = EwsAttachments.parse(getItem(
            Triple("A1", "report.pdf", null),
            Triple("A2", "image001.png", "image001.png@01DA"),
            Triple("A3", "image001.png", "image002.png@01DA"),
            Triple("A4", "R&amp;D plan.docx", null)
        ))
        // ItemAttachment (вложенное письмо) — не файл: Content у него нет
        assertThat(list.map { it.id }).containsExactly("A1", "A2", "A3", "A4").inOrder()
        assertThat(EwsAttachments.match(list, "report.pdf", null)?.id).isEqualTo("A1")
        assertThat(EwsAttachments.match(list, "R&D plan.docx", null)?.id).isEqualTo("A4")
        assertThat(EwsAttachments.match(list, " REPORT.PDF ", null)?.id).isEqualTo("A1")
        assertThat(EwsAttachments.match(list, "image001.png", "<image002.png@01DA>")?.id).isEqualTo("A3")
        assertThat(EwsAttachments.match(list, "image001.png", null)).isNull()
        assertThat(EwsAttachments.match(list, "image001.png", "other@x")).isNull()
        assertThat(EwsAttachments.match(list, "missing.txt", null)).isNull()
        assertThat(EwsAttachments.match(list, "", null)).isNull()
    }

    @Test
    fun `EWS requests escape ids and ask Exchange 2007 for attachments only by schema-valid FieldURI`() {
        val list = EwsAttachments.listRequest("AA\"<&>=")
        assertThat(list).contains("Id=\"AA&quot;&lt;&amp;&gt;=\"")
        assertThat(list).contains("FieldURI=\"item:Attachments\"")
        assertThat(list).contains("Version=\"Exchange2007_SP1\"")
        assertThat(EwsAttachments.contentRequest("B+/=")).contains("<t:AttachmentId Id=\"B+/=\"/>")
    }
}

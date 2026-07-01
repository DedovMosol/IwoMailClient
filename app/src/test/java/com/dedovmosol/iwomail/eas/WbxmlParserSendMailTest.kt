package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты `WbxmlParser.generateSendMail` — проверяют предпосылку дедупликации N-11:
 * при СТАБИЛЬНОМ ClientId (переиспользуемом при retry из `outbox.json`) WBXML-запрос
 * детерминирован и содержит этот ClientId, поэтому сервер EAS 14+ отбрасывает дубликат.
 *
 * `WbxmlParser.generateSendMail` — чистая функция (ByteArrayOutputStream, без Android), тестируется в JVM.
 */
class WbxmlParserSendMailTest {

    private val parser = WbxmlParser()

    @Test
    fun `stable clientId yields identical WBXML (server can dedup)`() {
        val mime = "From: a@b.com\r\nSubject: hi\r\n\r\nbody".toByteArray()
        val first = parser.generateSendMail("stable-outbox-cid-123", mime)
        val second = parser.generateSendMail("stable-outbox-cid-123", mime)
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `different clientIds yield different WBXML`() {
        val mime = "m".toByteArray()
        assertThat(parser.generateSendMail("id-A", mime))
            .isNotEqualTo(parser.generateSendMail("id-B", mime))
    }

    @Test
    fun `output embeds the clientId bytes`() {
        val clientId = "OUTBOX_CID_9f8e7d"
        val out = parser.generateSendMail(clientId, "x".toByteArray())
        // ClientId записан как inline-строка WBXML → ASCII-байты присутствуют в выводе.
        assertThat(String(out, Charsets.ISO_8859_1)).contains(clientId)
    }

    @Test
    fun `output embeds the raw MIME payload (opaque, not base64)`() {
        val mime = "RAW-MIME-MARKER".toByteArray()
        val out = parser.generateSendMail("cid", mime)
        assertThat(String(out, Charsets.ISO_8859_1)).contains("RAW-MIME-MARKER")
    }
}

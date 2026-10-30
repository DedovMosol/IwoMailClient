package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.util.MimeHtmlProcessor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Выбор MIME для .eml: EAS, а при потерях при разборе или без MIME — точная копия по EWS. */
class MessageMimeTest {

    private val raw = "From: a@corp.example\r\nSubject: x\r\n\r\nbody"
    private val ewsBytes = byteArrayOf(0x46, 0xE9.toByte()) // 8-битный байт cp1251 как есть

    @Test
    fun `clean EAS MIME is used without asking EWS`() = runBlocking<Unit> {
        var asked = false

        val result = MessageMime.choose(EasResult.Success(raw)) { asked = true; null }

        assertThat((result as EasResult.Success).data.toString(Charsets.UTF_8)).isEqualTo(raw)
        assertThat(asked).isFalse()
    }

    @Test
    fun `EAS MIME damaged by UTF-8 decoding is replaced by the exact EWS copy`() = runBlocking<Unit> {
        val result = MessageMime.choose(EasResult.Success("Subject: \uFFFD\r\n\r\nx")) { EasResult.Success(ewsBytes) }

        assertThat((result as EasResult.Success).data).isEqualTo(ewsBytes)
    }

    @Test
    fun `damaged EAS MIME is still saved when EWS is unavailable`() = runBlocking<Unit> {
        val damaged = "Subject: \uFFFD\r\n\r\nx"

        val result = MessageMime.choose(EasResult.Success(damaged)) { EasResult.Error("EWS disabled") }

        assertThat((result as EasResult.Success).data.toString(Charsets.UTF_8)).isEqualTo(damaged)
    }

    @Test
    fun `no EAS MIME falls back to EWS, and both failing reports the EAS reason`() = runBlocking<Unit> {
        assertThat((MessageMime.choose(EasResult.Success("")) { EasResult.Success(ewsBytes) } as EasResult.Success).data)
            .isEqualTo(ewsBytes)
        assertThat(MessageMime.choose(EasResult.Error("HTTP 503")) { EasResult.Error("EWS down") })
            .isEqualTo(EasResult.Error("HTTP 503"))
        assertThat(MessageMime.choose(EasResult.Success("")) { EasResult.Error("EWS down") })
            .isEqualTo(EasResult.Error("EWS down"))
        assertThat((MessageMime.choose(EasResult.Success("")) { null } as EasResult.Success).data).isEmpty()
    }

    @Test
    fun `mime bytes decode a base64 wrapper and keep plain MIME as UTF-8`() {
        val wrapped = java.util.Base64.getMimeEncoder().encodeToString(raw.repeat(5).toByteArray())

        assertThat(MimeHtmlProcessor.mimeBytes(wrapped).toString(Charsets.UTF_8)).isEqualTo(raw.repeat(5))
        assertThat(MimeHtmlProcessor.mimeBytes("Subject: Привет\r\n\r\nx").toString(Charsets.UTF_8)).isEqualTo("Subject: Привет\r\n\r\nx")
        // Короткая строка из «base64-символов» — не обёртка
        assertThat(MimeHtmlProcessor.mimeBytes("abcd").toString(Charsets.UTF_8)).isEqualTo("abcd")
    }
}

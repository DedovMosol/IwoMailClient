package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Общая логика inline-картинок (`cid:` ↔ data URL) для писем и календаря.
 * Content-ID: RFC 2392 (`cid:` в URL, %-кодирование), заголовок с угловыми скобками.
 */
class InlineImagesTest {

    @Test
    fun `token replacement does not corrupt longer cid with the same prefix`() {
        // Прежняя посимвольная замена превращала cid:img10 в "<data img1>0".
        val html = """<img src="cid:img1"><img src="cid:img10">"""
        val result = replaceInlineCids(html, mapOf("img1" to "DATA1", "img10" to "DATA10"))
        assertThat(result).isEqualTo("""<img src="DATA1"><img src="DATA10">""")
    }

    @Test
    fun `angle-bracket Content-ID key matches bare cid reference and vice versa`() {
        assertThat(replaceInlineCids("""<img src="cid:abc@x">""", mapOf("<abc@x>" to "D")))
            .isEqualTo("""<img src="D">""")
        assertThat(replaceInlineCids("""<img src='cid:abc'>""", mapOf("abc" to "D")))
            .isEqualTo("""<img src='D'>""")
    }

    @Test
    fun `percent-encoded cid url is decoded for lookup`() {
        val result = replaceInlineCids("""<img src="cid:image001.png%4001D9">""", mapOf("image001.png@01D9" to "D"))
        assertThat(result).isEqualTo("""<img src="D">""")
    }

    @Test
    fun `unknown cid is left untouched and css url is supported`() {
        val html = """<img src="cid:missing"><div style="background:url(cid:bg)">"""
        val result = replaceInlineCids(html, mapOf("bg" to "D"))
        assertThat(result).isEqualTo("""<img src="cid:missing"><div style="background:url(D)">""")
    }

    @Test
    fun `no cid means identity`() {
        assertThat(replaceInlineCids("<p>hello</p>", mapOf("a" to "b"))).isEqualTo("<p>hello</p>")
        assertThat(replaceInlineCids("cid:a", emptyMap())).isEqualTo("cid:a")
    }

    @Test
    fun `referenced ids are normalized`() {
        val html = """<img src="cid:a@b"><img src="cid:%3Cc%3E"><a href="mailto:x">"""
        assertThat(referencedContentIds(html)).containsExactly("a@b", "c")
        assertThat(referencedContentIds("<p>none</p>")).isEmpty()
    }

    @Test
    fun `image mime is detected from signature only`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val gif = "GIF89a".toByteArray()
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertThat(detectImageMimeType(png)).isEqualTo("image/png")
        assertThat(detectImageMimeType(jpeg)).isEqualTo("image/jpeg")
        assertThat(detectImageMimeType(gif)).isEqualTo("image/gif")
        assertThat(detectImageMimeType(webp)).isEqualTo("image/webp")
        assertThat(detectImageMimeType(byteArrayOf(0x42, 0x4D, 0, 0))).isEqualTo("image/bmp")
        // Не картинка — не подставляется, даже если короче сигнатуры.
        assertThat(detectImageMimeType("%PDF-1.4".toByteArray())).isNull()
        assertThat(detectImageMimeType(byteArrayOf(0x89.toByte()))).isNull()
        assertThat(detectImageMimeType(ByteArray(0))).isNull()
    }
}

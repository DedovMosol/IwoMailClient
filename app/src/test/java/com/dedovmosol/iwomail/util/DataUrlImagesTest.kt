package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** data URL тела → `cid:` + inline-вложения (обратная к [replaceInlineCids]). */
class DataUrlImagesTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val pngB64 = java.util.Base64.getEncoder().encodeToString(png)
    private val pngUrl = "data:image/png;base64,$pngB64"

    @Test
    fun `known data URL returns to its cid without brackets and is not re-uploaded`() {
        val (body, images) = extractDataUrlImages(
            """<p><img src="$pngUrl"></p>""",
            knownCids = mapOf(pngUrl to "<image001.png@01D9>")
        ) { "new$it" }

        assertThat(body).isEqualTo("""<p><img src="cid:image001.png@01D9"></p>""")
        assertThat(images).isEmpty()
    }

    @Test
    fun `new image is decoded once even when inserted twice`() {
        val (body, images) = extractDataUrlImages("""<img src="$pngUrl"><img src="$pngUrl">""") { "img$it" }

        assertThat(body).isEqualTo("""<img src="cid:img1"><img src="cid:img1">""")
        assertThat(images).hasSize(1)
        assertThat(images.single().contentId).isEqualTo("img1")
        assertThat(images.single().mimeType).isEqualTo("image/png")
        assertThat(images.single().bytes).isEqualTo(png)
    }

    @Test
    fun `broken base64 stays in the body`() {
        val html = """<img src="data:image/png;base64,@@@">"""
        val (body, images) = extractDataUrlImages(html) { "img$it" }

        assertThat(body).isEqualTo(html)
        assertThat(images).isEmpty()
    }

    @Test
    fun `html without data URLs is returned as is`() {
        val html = """<img src="cid:a@b">text"""
        assertThat(extractDataUrlImages(html) { "x" }).isEqualTo(html to emptyList<InlineDataImage>())
    }

    @Test
    fun `file extension follows mime type`() {
        assertThat(imageFileExtension("image/JPEG")).isEqualTo(".jpg")
        assertThat(imageFileExtension("image/gif")).isEqualTo(".gif")
        assertThat(imageFileExtension("image/webp")).isEqualTo(".webp")
        assertThat(imageFileExtension("image/x-unknown")).isEqualTo(".png")
    }
}

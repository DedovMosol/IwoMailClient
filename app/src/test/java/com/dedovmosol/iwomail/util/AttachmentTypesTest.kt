package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Тип вложения: EAS его не передаёт ([MS-ASAIRS] Attachment), поэтому — по имени или по байтам картинки. */
class AttachmentTypesTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test
    fun `type from the server wins, a missing or generic one comes from the file name`() {
        assertThat(attachmentMimeType("image/gif", "photo.png")).isEqualTo("image/gif")
        assertThat(attachmentMimeType(null, "Photo.JPG")).isEqualTo("image/jpeg")
        assertThat(attachmentMimeType("application/octet-stream", "scan.webp")).isEqualTo("image/webp")
        assertThat(attachmentMimeType("  ", "report.pdf")).isEqualTo("application/pdf")
        assertThat(attachmentMimeType(null, "data.bin")).isEqualTo(OCTET_STREAM)
        assertThat(attachmentMimeType(null, "no-extension")).isEqualTo(OCTET_STREAM)
    }

    @Test
    fun `image attachment is recognized by type or by name`() {
        assertThat(isImageAttachment("application/octet-stream", "image001.png")).isTrue()
        assertThat(isImageAttachment("image/jpeg", "attachment")).isTrue()
        assertThat(isImageAttachment("application/octet-stream", "report.pdf")).isFalse()
        assertThat(isImageAttachment(null, "archive.zip")).isFalse()
    }

    @Test
    fun `data url type comes from the picture bytes first, never octet-stream`() {
        // Имя и тип врут — байты нет (Outlook называет вставленное image001.png, а это JPEG)
        assertThat(dataUrlImageMimeType(jpeg, "application/octet-stream", "image001.png")).isEqualTo("image/jpeg")
        assertThat(dataUrlImageMimeType(png, null, "x")).isEqualTo("image/png")
        // Байты не распознаны — тип картинки по имени, иначе image/png
        assertThat(dataUrlImageMimeType(byteArrayOf(1, 2, 3), "application/octet-stream", "pic.gif")).isEqualTo("image/gif")
        assertThat(dataUrlImageMimeType(byteArrayOf(1, 2, 3), "application/octet-stream", "pic")).isEqualTo("image/png")
    }
}

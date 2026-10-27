package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Локальный черновик → сервер: картинки тела как cid:, сохранённые вложения без дублей и потерь. */
class DraftUploadTest {

    // 1×1 PNG
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    private fun att(name: String, isInline: Boolean, contentId: String?, type: String = "image/png") =
        AttachmentEntity(
            emailId = "1_local_draft_x", fileReference = "", displayName = name, contentType = type,
            estimatedSize = 3, isInline = isInline, contentId = contentId, localPath = "/files/$name", downloaded = true
        )

    @Test
    fun `data images become cid parts, their stale stored copies are not duplicated`() {
        val body = """<p>Hi</p><img src="data:image/png;base64,$png">"""
        val stored = listOf(att("image_1.png", isInline = true, contentId = "img0_OLD"), att("report.pdf", false, null, "application/pdf"))

        val (serverBody, parts) = DraftUpload.prepare(body, stored, stamp = 7L) { byteArrayOf(1, 2, 3) }

        assertThat(serverBody).contains("""src="cid:img1_7"""")
        assertThat(serverBody).doesNotContain("data:")
        assertThat(parts.map { it.name to it.isInline }).containsExactly("image_1.png" to true, "report.pdf" to false)
        assertThat(parts.first().contentId).isEqualTo("img1_7")
    }

    @Test
    fun `cid body keeps referenced inline files and plain files, missing files are skipped`() {
        // Крупный черновик хранится с cid: (окно курсора БД), картинки — файлы вложений.
        val body = """<p>Hi</p><img src="cid:IMG1@corp">"""
        val stored = listOf(
            att("photo.jpg", isInline = true, contentId = "<img1@corp>", type = "image/jpeg"),
            att("unreferenced.png", isInline = true, contentId = "img9"),
            att("logo.png", isInline = false, contentId = "logo@corp"),
            att("gone.pdf", isInline = false, contentId = null, type = "application/pdf")
        )

        val (serverBody, parts) = DraftUpload.prepare(body, stored, stamp = 1L) { a ->
            if (a.displayName == "gone.pdf") null else byteArrayOf(9)
        }

        assertThat(serverBody).isEqualTo(body)
        assertThat(parts.map { Triple(it.name, it.isInline, it.contentId) }).containsExactly(
            Triple("photo.jpg", true, "img1@corp"),
            // Content-ID без ссылки в теле — обычное вложение, а не пропавшая картинка.
            Triple("logo.png", false, null)
        )
    }
}

package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.repository.DraftAttachmentRole.FILE
import com.dedovmosol.iwomail.data.repository.DraftAttachmentRole.INLINE
import com.dedovmosol.iwomail.util.EmbeddedImages
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Вложения черновика при открытии на редактирование: картинка в тексте или файл. Ошибка в сторону
 * «картинка» теряла файл при пересохранении (вложение уходило в карту картинок, а тело на него не ссылалось).
 */
class DraftAttachmentRoleTest {

    private val cidBody = """<p>Отчёт</p><img src="cid:IMAGE001.PNG@01D9">"""

    @Test
    fun `referenced content id is an inline picture, regardless of case and angle brackets`() {
        assertThat(draftAttachmentRole(cidBody, "<image001.png@01D9>")).isEqualTo(INLINE)
        assertThat(draftAttachmentRole(cidBody, "image001.png@01D9")).isEqualTo(INLINE)
    }

    @Test
    fun `content id without a reference in the body is a file`() {
        // Outlook и Apple Mail ставят Content-ID и обычным файлам; синхронизация тогда помечает их inline.
        assertThat(draftAttachmentRole(cidBody, "report.pdf@01D9")).isEqualTo(FILE)
        assertThat(draftAttachmentRole(cidBody, null)).isEqualTo(FILE)
        assertThat(draftAttachmentRole(cidBody, "  ")).isEqualTo(FILE)
    }

    @Test
    fun `a data url picture in the body does not hide a file with a content id - only its own bytes do`() {
        val logo = byteArrayOf(1, 2, 3, 4)
        val body = """<p>Отчёт</p><img src="data:image/jpeg;base64,${java.util.Base64.getEncoder().encodeToString(logo)}">"""
        // Раньше: помеченное inline без ссылки при data:-картинке в теле считалось «уже в теле» — report.pdf пропадал.
        assertThat(draftAttachmentRole(body, "report.pdf@01D9")).isEqualTo(FILE)

        val embedded = EmbeddedImages(body)
        assertThat(embedded.dataUrlOf(byteArrayOf(9, 9, 9))).isNull()
        // Та же картинка (MIME в data URL и у вложения может различаться) — уже в теле.
        assertThat(embedded.dataUrlOf(logo)).startsWith("data:image/jpeg;base64,")
    }
}

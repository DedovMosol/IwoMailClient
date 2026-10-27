package com.dedovmosol.iwomail.ui.screens.compose

import com.dedovmosol.iwomail.ui.screens.compose.DraftAttachmentRole.EMBEDDED
import com.dedovmosol.iwomail.ui.screens.compose.DraftAttachmentRole.FILE
import com.dedovmosol.iwomail.ui.screens.compose.DraftAttachmentRole.INLINE
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
        assertThat(draftAttachmentRole(cidBody, "<image001.png@01D9>", isInline = true)).isEqualTo(INLINE)
        // Сервер не пометил inline, но тело на картинку ссылается — всё равно картинка.
        assertThat(draftAttachmentRole(cidBody, "image001.png@01D9", isInline = false)).isEqualTo(INLINE)
    }

    @Test
    fun `content id without a reference in the body is a file`() {
        // Outlook и Apple Mail ставят Content-ID и обычным файлам; EAS тогда отдаёт IsInline / ContentId.
        assertThat(draftAttachmentRole(cidBody, "report.pdf@01D9", isInline = true)).isEqualTo(FILE)
        assertThat(draftAttachmentRole(cidBody, null, isInline = false)).isEqualTo(FILE)
        assertThat(draftAttachmentRole(cidBody, "  ", isInline = true)).isEqualTo(FILE)
    }

    @Test
    fun `inline picture already embedded as data url is not added twice`() {
        val embedded = """<p>Отчёт</p><img src="data:image/png;base64,AAAA">"""
        assertThat(draftAttachmentRole(embedded, "image001.png@01D9", isInline = true)).isEqualTo(EMBEDDED)
        // Обычный файл рядом со встроенной картинкой остаётся файлом.
        assertThat(draftAttachmentRole(embedded, null, isInline = false)).isEqualTo(FILE)
    }
}

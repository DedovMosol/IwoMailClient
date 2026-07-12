package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты [EasAttachment.effectiveIsInline] — единой классификации inline-вложений.
 *
 * Инвариант: inline, если сервер выставил IsInline ЛИБО у вложения есть ContentId.
 * Второе критично для Exchange 2007 SP1, который для inline-картинок черновиков часто
 * не выставляет IsInline (MS: inline-вложения идентифицируются по Content-ID). Без единого
 * источника истины sync и reconcile классифицировали inline по-разному → картинка
 * дублировалась (в теле через MIME + как файловое вложение).
 */
class EasAttachmentTest {

    private fun att(isInline: Boolean = false, contentId: String? = null) =
        EasAttachment(
            fileReference = "ref",
            displayName = "image.png",
            contentType = "image/png",
            estimatedSize = 100,
            isInline = isInline,
            contentId = contentId
        )

    @Test
    fun `server IsInline flag alone marks inline`() {
        assertThat(att(isInline = true, contentId = null).effectiveIsInline).isTrue()
    }

    @Test
    fun `ContentId alone marks inline even without IsInline (Exchange 2007 drafts)`() {
        assertThat(att(isInline = false, contentId = "image1@corp.ru").effectiveIsInline).isTrue()
    }

    @Test
    fun `no IsInline and no ContentId is a regular attachment`() {
        assertThat(att(isInline = false, contentId = null).effectiveIsInline).isFalse()
    }

    @Test
    fun `blank ContentId is not inline`() {
        assertThat(att(isInline = false, contentId = "").effectiveIsInline).isFalse()
        assertThat(att(isInline = false, contentId = "   ").effectiveIsInline).isFalse()
    }

    @Test
    fun `both flag and ContentId present is inline`() {
        assertThat(att(isInline = true, contentId = "cid1").effectiveIsInline).isTrue()
    }
}

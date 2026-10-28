package com.dedovmosol.iwomail.ui.screens.emaildetail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Вложение — картинка в тексте (не в списке файлов) или файл. Синхронизация помечает inline всё с
 * Content-ID, а Outlook и Apple Mail ставят Content-ID и файлам — решает ссылка cid: в теле.
 */
class InlineAttachmentsTest {

    private val refs = setOf("image001.png@01D9")
    private val shown = mapOf("<image001.png@01D9>" to "data:image/png;base64,AAAA")

    @Test
    fun `picture referenced by the body and shown in it is not listed`() {
        assertThat(isShownInBody("<IMAGE001.PNG@01D9>", "image001.png", isInline = true, refs, shown, inlineImagesLoading = false)).isTrue()
        // Пока картинки загружаются — тоже в теле (без мигания списка).
        assertThat(isShownInBody("image001.png@01D9", "image001.png", isInline = true, refs, emptyMap(), inlineImagesLoading = true)).isTrue()
    }

    @Test
    fun `content id without a reference in the body is a file and is listed`() {
        assertThat(isShownInBody("report.pdf@01D9", "report.pdf", isInline = true, refs, shown, inlineImagesLoading = false)).isFalse()
    }

    @Test
    fun `referenced picture that did not load is listed, so it stays reachable`() {
        assertThat(isShownInBody("image001.png@01D9", "image001.png", isInline = true, refs, emptyMap(), inlineImagesLoading = false)).isFalse()
    }

    @Test
    fun `until the body is parsed the sync flag decides`() {
        assertThat(isShownInBody("x@y", "x.png", isInline = true, referencedIds = null, shown, inlineImagesLoading = false)).isTrue()
        assertThat(isShownInBody(null, "a.pdf", isInline = false, referencedIds = null, shown, inlineImagesLoading = false)).isFalse()
    }

    @Test
    fun `picture referenced by its file name is matched by name`() {
        val byName = setOf("photo.jpg")
        val images = mapOf("photo.jpg" to "data:image/jpeg;base64,AAAA")
        assertThat(isShownInBody(null, "photo.jpg", isInline = true, byName, images, inlineImagesLoading = false)).isTrue()
    }
}

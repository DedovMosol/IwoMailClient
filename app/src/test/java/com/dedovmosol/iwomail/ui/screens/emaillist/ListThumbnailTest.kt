package com.dedovmosol.iwomail.ui.screens.emaillist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Одна миниатюра на строку и «+N» — сколько картинок ещё. */
class ListThumbnailTest {

    @Test
    fun `more count is the pictures beyond the one shown`() {
        assertThat(ListPreview("/p", imageCount = 1).moreCount).isEqualTo(0)
        assertThat(ListPreview("/p", imageCount = 3).moreCount).isEqualTo(2)
        assertThat(ListPreview("/p", imageCount = 0).moreCount).isEqualTo(0)
    }

    @Test
    fun `badge text is capped`() {
        assertThat(moreBadgeText(2)).isEqualTo("+2")
        assertThat(moreBadgeText(250)).isEqualTo("+99")
    }

    @Test
    fun `accessibility description declines the word`() {
        assertThat(thumbnailDescription(1, russian = true)).isEqualTo("1 изображение")
        assertThat(thumbnailDescription(3, russian = true)).isEqualTo("3 изображения")
        assertThat(thumbnailDescription(5, russian = true)).isEqualTo("5 изображений")
        assertThat(thumbnailDescription(11, russian = true)).isEqualTo("11 изображений")
        assertThat(thumbnailDescription(21, russian = true)).isEqualTo("21 изображение")
        assertThat(thumbnailDescription(1, russian = false)).isEqualTo("1 image")
        assertThat(thumbnailDescription(4, russian = false)).isEqualTo("4 images")
    }
}

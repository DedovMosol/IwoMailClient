package com.dedovmosol.iwomail.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Высота карточки письма по замеру содержимого: без пустых белых листов под письмом. */
class HtmlBodyViewHeightTest {

    private val density = 2.75f // типичный экран: 1080 px ширины = 392.7 dp

    @Test
    fun `ordinary email - css height maps to dp through the real width`() {
        // Ширина видимой области = ширина экрана в CSS px: 1 CSS px = 1 dp.
        assertThat(contentHeightDp("\"800,392.7272\"", viewWidthPx = 1080, density = density)).isEqualTo(800)
    }

    @Test
    fun `wide newsletter zoomed out to fit - height shrinks with the zoom, not stays 3x`() {
        // Рассылка 1200 CSS px шириной, уменьшена под экран: 1080 px / 1200 CSS px. Раньше (WebView.scale до
        // применения уменьшения) — 3000 dp, втрое выше письма.
        assertThat(contentHeightDp("\"3000,1200\"", viewWidthPx = 1080, density = density)).isEqualTo(981)
    }

    @Test
    fun `unusable results keep the previous height`() {
        assertThat(contentHeightDp(null, 1080, density)).isNull()
        assertThat(contentHeightDp("\"\"", 1080, density)).isNull()
        assertThat(contentHeightDp("\"800,0\"", 1080, density)).isNull()
        assertThat(contentHeightDp("\"0,392\"", 1080, density)).isNull()
        // WebView ещё не размечен
        assertThat(contentHeightDp("\"800,392\"", viewWidthPx = 0, density = density)).isNull()
        // Явно ошибочный замер не растягивает карточку
        assertThat(contentHeightDp("\"99999999,392.7272\"", 1080, density)).isNull()
    }
}

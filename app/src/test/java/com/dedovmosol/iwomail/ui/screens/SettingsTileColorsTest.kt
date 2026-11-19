package com.dedovmosol.iwomail.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dedovmosol.iwomail.ui.components.ScrollbarColor
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Плитки иконок настроек — статичные цвета палитры скроллбара. Глиф на плитке — значимая графика: контраст не ниже
 * 3:1 (WCAG 2.1, 1.4.11) для каждого цвета палитры, на залитой плитке — по обоим краям перехода, на тональной —
 * в светлой и тёмной теме.
 */
class SettingsTileColorsTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    @Test
    fun `white glyph on a filled tile keeps 3 to 1 across the gradient`() {
        for (hue in ScrollbarColor.entries) {
            for (stop in SettingsTileColors.filled(hue.color)) {
                assertWithMessage("$hue at $stop").that(contrast(Color.White, stop)).isAtLeast(3.0)
            }
        }
    }

    @Test
    fun `tonal tile glyph keeps 3 to 1 in light and dark`() {
        for (hue in ScrollbarColor.entries) {
            for (dark in listOf(false, true)) {
                val bg = SettingsTileColors.tonalBackground(hue.color, dark)
                val glyph = SettingsTileColors.tonalGlyph(hue.color, dark)
                assertWithMessage("$hue dark=$dark").that(contrast(glyph, bg)).isAtLeast(3.0)
            }
        }
    }

    @Test
    fun `hub sections use distinct palette colours`() {
        val hues = SettingsSearchDest.entries.map { it.hue }
        assertThat(hues).containsNoDuplicates()
    }
}

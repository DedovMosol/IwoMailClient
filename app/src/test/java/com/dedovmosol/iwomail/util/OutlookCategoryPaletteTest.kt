package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Палитра категорий Outlook — [MS-OXOCFG] 2.2.5.2.2, атрибут color: индексы -1..24 и базовые RGB
 * из таблицы спецификации; значение вне диапазона MUST трактоваться как -1.
 */
class OutlookCategoryPaletteTest {

    private fun rgbOf(color: Int): Triple<Int, Int, Int>? = OutlookCategoryPalette.argb(color)?.let {
        Triple((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF)
    }

    @Test
    fun `rgb values match the MS-OXOCFG table`() {
        assertThat(rgbOf(0)).isEqualTo(Triple(214, 37, 46))     // Red
        assertThat(rgbOf(2)).isEqualTo(Triple(255, 202, 76))    // Peach
        assertThat(rgbOf(7)).isEqualTo(Triple(50, 103, 184))    // Blue
        assertThat(rgbOf(10)).isEqualTo(Triple(196, 204, 221))  // Steel
        assertThat(rgbOf(14)).isEqualTo(Triple(28, 28, 28))     // Black
        assertThat(rgbOf(18)).isEqualTo(Triple(153, 148, 0))    // Dark yellow
        assertThat(rgbOf(24)).isEqualTo(Triple(130, 55, 95))    // Dark maroon
        // Непрозрачные цвета.
        assertThat(OutlookCategoryPalette.argb(5)!! ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun `no color and out of range values`() {
        assertThat(OutlookCategoryPalette.argb(OutlookCategoryPalette.NONE)).isNull()
        assertThat(OutlookCategoryPalette.argb(25)).isNull()
        assertThat(OutlookCategoryPalette.normalize(25)).isEqualTo(-1)
        assertThat(OutlookCategoryPalette.normalize(-7)).isEqualTo(-1)
        assertThat(OutlookCategoryPalette.normalize(null)).isEqualTo(-1)
        assertThat(OutlookCategoryPalette.normalize(24)).isEqualTo(24)
        assertThat(OutlookCategoryPalette.ALL).hasSize(26)
        assertThat(OutlookCategoryPalette.ALL.first()).isEqualTo(-1)
    }

    @Test
    fun `default Outlook category names map to their colors in English and Russian`() {
        assertThat(OutlookCategoryPalette.presetColorFor("Red Category")).isEqualTo(0)
        assertThat(OutlookCategoryPalette.presetColorFor("  blue   CATEGORY ")).isEqualTo(7)
        assertThat(OutlookCategoryPalette.presetColorFor("Purple Category")).isEqualTo(8)
        assertThat(OutlookCategoryPalette.presetColorFor("Красная категория")).isEqualTo(0)
        assertThat(OutlookCategoryPalette.presetColorFor("Жёлтая категория")).isEqualTo(3)
        assertThat(OutlookCategoryPalette.presetColorFor("зеленая КАТЕГОРИЯ")).isEqualTo(4)
        assertThat(OutlookCategoryPalette.presetColorFor("Проект Альфа")).isNull()
    }

    @Test
    fun `auto color is deterministic, case-insensitive and never dull or empty`() {
        val names = listOf("Проект Альфа", "Отпуск", "Client X", "Важно", "a", "b", "c", "d", "e", "f")
        for (name in names) {
            val color = OutlookCategoryPalette.autoColorFor(name)
            assertThat(color).isEqualTo(OutlookCategoryPalette.autoColorFor(name.uppercase()))
            assertThat(color).isIn((0..9) + (15..24))
        }
    }
}

package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Имена категорий: уникальны без учёта регистра и без запятых ([MS-OXOCFG] 2.2.5.2.2) — отсюда CSV. */
class CalendarCategoriesTest {

    @Test
    fun `csv is trimmed, keeps order and drops case-insensitive duplicates`() {
        assertThat(CalendarCategories.parse(" Работа ,Личное,,работа, Red Category "))
            .containsExactly("Работа", "Личное", "Red Category").inOrder()
        assertThat(CalendarCategories.parse("")).isEmpty()
        assertThat(CalendarCategories.parse(" , ")).isEmpty()
    }

    @Test
    fun `toCsv is the inverse of parse`() {
        val names = listOf("Работа", " Личное ", "РАБОТА", "")
        assertThat(CalendarCategories.toCsv(names)).isEqualTo("Работа,Личное")
        assertThat(CalendarCategories.parse(CalendarCategories.toCsv(names))).containsExactly("Работа", "Личное").inOrder()
    }

    @Test
    fun `key ignores case and surrounding spaces independent of device locale`() {
        assertThat(CalendarCategories.key("  Red Category ")).isEqualTo(CalendarCategories.key("red category"))
        // Турецкая «I» не должна ломать сравнение: ключ — Locale.ROOT.
        assertThat(CalendarCategories.key("TITLE")).isEqualTo("title")
    }
}

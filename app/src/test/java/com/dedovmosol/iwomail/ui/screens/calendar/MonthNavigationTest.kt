package com.dedovmosol.iwomail.ui.screens.calendar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar

/** PIM-01: стрелки месяца не зависят от дня выбранной даты (29-31) и идут через границу года. */
class MonthNavigationTest {

    @Test fun `next month from January is February regardless of the selected day`() {
        assertThat(shiftMonth(2026, Calendar.JANUARY, 1)).isEqualTo(2026 to Calendar.FEBRUARY)
    }

    @Test fun `previous month from March is February`() {
        assertThat(shiftMonth(2026, Calendar.MARCH, -1)).isEqualTo(2026 to Calendar.FEBRUARY)
    }

    @Test fun `year boundaries in both directions`() {
        assertThat(shiftMonth(2026, Calendar.DECEMBER, 1)).isEqualTo(2027 to Calendar.JANUARY)
        assertThat(shiftMonth(2026, Calendar.JANUARY, -1)).isEqualTo(2025 to Calendar.DECEMBER)
        assertThat(shiftMonth(2026, Calendar.JUNE, -18)).isEqualTo(2024 to Calendar.DECEMBER)
        assertThat(shiftMonth(2026, Calendar.JUNE, 30)).isEqualTo(2028 to Calendar.DECEMBER)
    }

    @Test fun `every month forward and back over two years visits each month once`() {
        var ym = 2025 to Calendar.JANUARY
        val seen = mutableListOf(ym)
        repeat(24) { ym = shiftMonth(ym.first, ym.second, 1); seen += ym }
        assertThat(seen.distinct()).hasSize(25)
        repeat(24) { ym = shiftMonth(ym.first, ym.second, -1) }
        assertThat(ym).isEqualTo(2025 to Calendar.JANUARY)
    }
}

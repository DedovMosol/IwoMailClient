package com.dedovmosol.iwomail.widget

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты чистой функции [isSameLocalDay] — основа выбора «сегодня → время, иначе → дата»
 * в метке синхронизации виджета ([formatSyncAgo]) и в списке последних писем.
 *
 * Регрессия: раньше синк «вчера в 14:30» рендерился как «в 14:30» и выглядел сегодняшним, потому
 * что сравнения дня не было вовсе. Тест фиксирует границу суток и разные годы с одним днём года.
 *
 * `isSameLocalDay` считает по ДЕФОЛТНОЙ таймзоне (`Calendar.getInstance()`), поэтому в тесте
 * жёстко ставим дефолтную TZ = UTC и строим моменты тоже в UTC — иначе на не-UTC агенте CI
 * «00:01 UTC» и «23:59 UTC» одного дня могли бы попасть в разные локальные сутки. Плейн JUnit,
 * никакого Android SDK — функция не трогает `Context`.
 */
class MailWidgetFormatTest {

    private var savedTz: TimeZone? = null

    @Before
    fun forceUtc() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTz() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    private fun at(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month0, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `same calendar day, different times, is true`() {
        val morning = at(2026, Calendar.JUNE, 30, 0, 1)
        val nearMidnight = at(2026, Calendar.JUNE, 30, 23, 59)
        assertThat(isSameLocalDay(morning, nearMidnight)).isTrue()
    }

    @Test
    fun `one minute across midnight is a different day`() {
        val before = at(2026, Calendar.JUNE, 30, 23, 59)
        val after = at(2026, Calendar.JULY, 1, 0, 1)
        assertThat(isSameLocalDay(before, after)).isFalse()
    }

    @Test
    fun `same day-of-year but different year is false`() {
        // Один и тот же DAY_OF_YEAR (1 января) — проверяем, что год тоже учитывается.
        val y2025 = at(2025, Calendar.JANUARY, 1, 12, 0)
        val y2026 = at(2026, Calendar.JANUARY, 1, 12, 0)
        assertThat(isSameLocalDay(y2025, y2026)).isFalse()
    }

    @Test
    fun `identical instant is same day`() {
        val t = at(2026, Calendar.MARCH, 15, 9, 30)
        assertThat(isSameLocalDay(t, t)).isTrue()
    }

    @Test
    fun `relation is symmetric`() {
        val a = at(2026, Calendar.MARCH, 10, 9, 0)
        val b = at(2026, Calendar.MARCH, 11, 9, 0)
        assertThat(isSameLocalDay(a, b)).isEqualTo(isSameLocalDay(b, a))
    }
}

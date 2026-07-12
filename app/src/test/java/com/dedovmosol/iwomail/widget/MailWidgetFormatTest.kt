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

    // ===================== formatSyncLabel =====================

    private fun label(
        lastSync: Long,
        now: Long,
        sameDay: Boolean,
        isRu: Boolean = false
    ) = formatSyncLabel(
        lastSyncMillis = lastSync,
        nowMillis = now,
        sameLocalDay = sameDay,
        isRussian = isRu,
        justNowText = "just now",
        dateText = "30.06",
        timeText = "14:30"
    )

    @Test
    fun `no sync yet yields empty`() {
        assertThat(label(lastSync = 0L, now = 1_000_000L, sameDay = true)).isEmpty()
    }

    @Test
    fun `under one minute is just now`() {
        val now = 5_000_000L
        assertThat(label(lastSync = now - 30_000L, now = now, sameDay = true)).isEqualTo("just now")
    }

    @Test
    fun `exactly one minute is not just now`() {
        val now = 5_000_000L
        // diff == 60_000 → НЕ «только что» (условие diff < 60_000 строгое)
        assertThat(label(lastSync = now - 60_000L, now = now, sameDay = true)).isEqualTo("at 14:30")
    }

    @Test
    fun `clock moved backwards yields just now not a stale time`() {
        val now = 5_000_000L
        // lastSync в будущем (часы уехали назад) → diff < 0 → «только что», а не «в будущем»
        assertThat(label(lastSync = now + 120_000L, now = now, sameDay = true)).isEqualTo("just now")
    }

    @Test
    fun `not same day shows date not time`() {
        val now = 100_000_000L
        assertThat(label(lastSync = now - 5_000_000L, now = now, sameDay = false)).isEqualTo("30.06")
    }

    @Test
    fun `same day shows localized time prefix`() {
        val now = 100_000_000L
        val lastSync = now - 5_000_000L
        assertThat(label(lastSync = lastSync, now = now, sameDay = true, isRu = false)).isEqualTo("at 14:30")
        assertThat(label(lastSync = lastSync, now = now, sameDay = true, isRu = true)).isEqualTo("в 14:30")
    }
}

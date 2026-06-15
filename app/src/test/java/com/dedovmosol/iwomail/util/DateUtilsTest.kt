package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.TimeZone

/**
 * Юнит-тесты для DateUtils.
 * TimeZone закреплён в UTC: в UTC любая полночь кратна 86_400_000 мс и нет DST,
 * поэтому проверки инвариантны и не зависят от окружения CI.
 */
class DateUtilsTest {

    private lateinit var originalTz: TimeZone
    private val dayMs = 86_400_000L

    @Before
    fun setUp() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
    }

    @Test
    fun `getStartOfDay returns UTC midnight not after the input`() {
        val input = Date(1_700_000_000_000L) // 2023-11-14T22:13:20Z
        val start = DateUtils.getStartOfDay(input)

        assertThat(start % dayMs).isEqualTo(0L)
        assertThat(start).isAtMost(input.time)
        assertThat(input.time - start).isLessThan(dayMs)
    }

    @Test
    fun `getStartOfDay with default now is a UTC midnight`() {
        assertThat(DateUtils.getStartOfDay() % dayMs).isEqualTo(0L)
    }

    @Test
    fun `getEndOfDay is start plus one day minus one millisecond`() {
        val input = Date(1_700_000_000_000L)
        val start = DateUtils.getStartOfDay(input)
        val end = DateUtils.getEndOfDay(input)

        assertThat(end - start).isEqualTo(dayMs - 1)
        assertThat(end).isAtLeast(input.time)
    }

    @Test
    fun `getDayRange spans exactly one day starting at start of day`() {
        val input = Date(1_700_000_000_000L)
        val (start, end) = DateUtils.getDayRange(input)

        assertThat(start).isEqualTo(DateUtils.getStartOfDay(input))
        assertThat(end - start).isEqualTo(dayMs)
    }
}

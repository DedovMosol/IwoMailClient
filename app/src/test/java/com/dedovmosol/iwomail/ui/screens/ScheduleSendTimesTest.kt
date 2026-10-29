package com.dedovmosol.iwomail.ui.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ScheduleSendTimesTest {

    private val zone = TimeZone.getTimeZone("Europe/Moscow")

    private fun time(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.parse(text)!!.time

    private fun text(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).apply { timeZone = zone }.format(millis)

    // 2026-10-26 — понедельник, 2026-10-29 — четверг

    @Test
    fun `tomorrow at the hour sharp`() {
        assertThat(text(ScheduleSendTimes.tomorrowAt(time("2026-10-29 17:42"), 8, zone))).isEqualTo("2026-10-30 08:00:00.000")
    }

    @Test
    fun `next Monday from a weekday`() {
        assertThat(text(ScheduleSendTimes.nextMondayAt(time("2026-10-29 17:42"), 8, zone))).isEqualTo("2026-11-02 08:00:00.000")
    }

    @Test
    fun `Monday before the hour is today`() {
        assertThat(text(ScheduleSendTimes.nextMondayAt(time("2026-10-26 06:30"), 8, zone))).isEqualTo("2026-10-26 08:00:00.000")
    }

    @Test
    fun `Monday after the hour is next week`() {
        assertThat(text(ScheduleSendTimes.nextMondayAt(time("2026-10-26 09:00"), 8, zone))).isEqualTo("2026-11-02 08:00:00.000")
    }

    @Test
    fun `Monday exactly at the hour is next week - the time must be in the future`() {
        assertThat(text(ScheduleSendTimes.nextMondayAt(time("2026-10-26 08:00"), 8, zone))).isEqualTo("2026-11-02 08:00:00.000")
    }

    @Test
    fun `Sunday evening is the next day`() {
        assertThat(text(ScheduleSendTimes.nextMondayAt(time("2026-11-01 23:59"), 8, zone))).isEqualTo("2026-11-02 08:00:00.000")
    }
}

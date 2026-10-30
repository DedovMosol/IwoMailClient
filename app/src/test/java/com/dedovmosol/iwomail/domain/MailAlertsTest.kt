package com.dedovmosol.iwomail.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.TimeZone

/** Тихие часы (в том числе через полночь) и важные отправители. */
class MailAlertsTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `window across midnight`() {
        val quiet = QuietHours(enabled = true, startMinutes = at(22), endMinutes = at(7))

        assertThat(quiet.isActive(at(22))).isTrue()
        assertThat(quiet.isActive(at(23, 59))).isTrue()
        assertThat(quiet.isActive(at(0))).isTrue()
        assertThat(quiet.isActive(at(6, 59))).isTrue()
        assertThat(quiet.isActive(at(7))).isFalse()
        assertThat(quiet.isActive(at(21, 59))).isFalse()
    }

    @Test
    fun `window within a day`() {
        val quiet = QuietHours(enabled = true, startMinutes = at(13), endMinutes = at(14))

        assertThat(quiet.isActive(at(13, 30))).isTrue()
        assertThat(quiet.isActive(at(14))).isFalse()
        assertThat(quiet.isActive(at(12, 59))).isFalse()
    }

    @Test
    fun `disabled or empty window is never quiet`() {
        assertThat(QuietHours(enabled = false, startMinutes = at(0), endMinutes = at(23, 59)).isActive(at(12))).isFalse()
        // Начало равно концу — не «круглые сутки»
        assertThat(QuietHours(enabled = true, startMinutes = at(8), endMinutes = at(8)).isActive(at(8))).isFalse()
    }

    @Test
    fun `minute of day uses the local time zone`() {
        val utcNoon = 12L * 3_600_000L // 1970-01-01 12:00 UTC
        assertThat(QuietHours.minuteOfDay(utcNoon, TimeZone.getTimeZone("UTC"))).isEqualTo(at(12))
        assertThat(QuietHours.minuteOfDay(utcNoon, TimeZone.getTimeZone("Europe/Moscow"))).isEqualTo(at(15))
    }

    @Test
    fun `stored minutes are sanitized`() {
        assertThat(QuietHours.sanitize(null, 5)).isEqualTo(5)
        assertThat(QuietHours.sanitize(-1, 5)).isEqualTo(5)
        assertThat(QuietHours.sanitize(24 * 60, 5)).isEqualTo(5)
        assertThat(QuietHours.sanitize(at(23, 59), 5)).isEqualTo(at(23, 59))
    }

    private val night = QuietHours(enabled = true, startMinutes = at(22), endMinutes = at(7))

    @Test
    fun `quiet hours mute ordinary mail`() {
        assertThat(MailAlert.of(listOf("a@corp.example"), emptySet(), night, at(23)))
            .isEqualTo(MailAlert(vip = false, silent = true))
        assertThat(MailAlert.of(listOf("a@corp.example"), emptySet(), night, at(12)))
            .isEqualTo(MailAlert(vip = false, silent = false))
    }

    @Test
    fun `important sender alerts even at night, matched by address regardless of name and case`() {
        val alert = MailAlert.of(listOf("x@corp.example", "\"Boss\" <Boss@Corp.Example>"), setOf("boss@corp.example"), night, at(3))

        assertThat(alert).isEqualTo(MailAlert(vip = true, silent = false))
    }
}

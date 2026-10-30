package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.eas.MailFlag
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class FlagPresetsTest {

    private val zone = TimeZone.getTimeZone("Europe/Moscow")
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }

    private fun time(text: String): Long = format.parse(text)!!.time
    private fun day(text: String): Long = time("$text 00:00")

    private fun flag(preset: FlagPreset, now: String, current: MailFlag = MailFlag.NONE) =
        FlagPresets.flag(preset, current, time(now), zone)

    // 2026-10-26 — понедельник, 29-е — четверг, 30-е — пятница, 31-е — суббота, 1 ноября — воскресенье

    @Test
    fun `today and tomorrow`() {
        assertThat(flag(FlagPreset.TODAY, "2026-10-29 15:00")).isEqualTo(
            MailFlag(MailFlag.STATUS_ACTIVE, day("2026-10-29"), day("2026-10-29"), null)
        )
        assertThat(flag(FlagPreset.TOMORROW, "2026-10-29 15:00")).isEqualTo(
            MailFlag(MailFlag.STATUS_ACTIVE, day("2026-10-30"), day("2026-10-30"), null)
        )
    }

    @Test
    fun `this week is due on Friday and starts in two days at most`() {
        assertThat(flag(FlagPreset.THIS_WEEK, "2026-10-26 09:00")).isEqualTo(
            MailFlag(MailFlag.STATUS_ACTIVE, day("2026-10-28"), day("2026-10-30"), null)
        )
        assertThat(flag(FlagPreset.THIS_WEEK, "2026-10-29 09:00")).isEqualTo(
            MailFlag(MailFlag.STATUS_ACTIVE, day("2026-10-30"), day("2026-10-30"), null)
        )
        assertThat(flag(FlagPreset.THIS_WEEK, "2026-10-30 09:00")).isEqualTo(
            MailFlag(MailFlag.STATUS_ACTIVE, day("2026-10-30"), day("2026-10-30"), null)
        )
    }

    @Test
    fun `this week on the weekend is today, not a Friday in the past`() {
        assertThat(flag(FlagPreset.THIS_WEEK, "2026-10-31 12:00").dueDate).isEqualTo(day("2026-10-31"))
        assertThat(flag(FlagPreset.THIS_WEEK, "2026-11-01 12:00").dueDate).isEqualTo(day("2026-11-01"))
    }

    @Test
    fun `next week is Monday to Friday`() {
        for (now in listOf("2026-10-26 09:00", "2026-10-29 23:59", "2026-11-01 10:00")) {
            assertThat(flag(FlagPreset.NEXT_WEEK, now)).isEqualTo(
                MailFlag(MailFlag.STATUS_ACTIVE, day("2026-11-02"), day("2026-11-06"), null)
            )
        }
    }

    @Test
    fun `no date flag`() {
        assertThat(flag(FlagPreset.NO_DATE, "2026-10-29 09:00")).isEqualTo(MailFlag(MailFlag.STATUS_ACTIVE))
    }

    @Test
    fun `changing the date keeps a future reminder and drops a past one`() {
        val future = MailFlag(MailFlag.STATUS_ACTIVE, day("2026-10-29"), day("2026-10-29"), time("2026-10-30 09:00"))
        assertThat(flag(FlagPreset.NEXT_WEEK, "2026-10-29 12:00", future).reminderTime).isEqualTo(time("2026-10-30 09:00"))
        val past = future.copy(reminderTime = time("2026-10-29 08:00"))
        assertThat(flag(FlagPreset.NEXT_WEEK, "2026-10-29 12:00", past).reminderTime).isNull()
    }

    @Test
    fun `reminder on an unflagged email flags it for that day`() {
        val reminder = time("2026-11-03 10:30")
        assertThat(FlagPresets.withReminder(MailFlag.NONE, reminder, zone)).isEqualTo(
            MailFlag(MailFlag.STATUS_ACTIVE, day("2026-11-03"), day("2026-11-03"), reminder)
        )
        val dated = MailFlag(MailFlag.STATUS_ACTIVE, day("2026-10-29"), day("2026-10-30"))
        assertThat(FlagPresets.withReminder(dated, reminder, zone)).isEqualTo(dated.copy(reminderTime = reminder))
    }

    @Test
    fun `star toggles a no-date flag`() {
        assertThat(FlagPresets.toggled(MailFlag.NONE)).isEqualTo(MailFlag(MailFlag.STATUS_ACTIVE))
        assertThat(FlagPresets.toggled(MailFlag(MailFlag.STATUS_ACTIVE, 1L, 2L, 3L))).isEqualTo(MailFlag.NONE)
        // Выполненный — снова в работу
        assertThat(FlagPresets.toggled(MailFlag(MailFlag.STATUS_COMPLETE))).isEqualTo(MailFlag(MailFlag.STATUS_ACTIVE))
    }

    @Test
    fun `due state relative to today`() {
        val now = time("2026-10-29 15:00")
        assertThat(FlagDue.of(day("2026-10-28"), now, zone)).isEqualTo(FlagDue.OVERDUE)
        assertThat(FlagDue.of(day("2026-10-29"), now, zone)).isEqualTo(FlagDue.TODAY)
        assertThat(FlagDue.of(day("2026-10-30"), now, zone)).isEqualTo(FlagDue.TOMORROW)
        assertThat(FlagDue.of(day("2026-11-06"), now, zone)).isEqualTo(FlagDue.LATER)
    }
}

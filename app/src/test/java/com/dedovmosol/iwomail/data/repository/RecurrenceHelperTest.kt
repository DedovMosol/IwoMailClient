package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * PIM-02 и PIM-12: вхождения серий сверяются с эталоном (перебор дней), как в docs/audit/experiments/RecSimFix.java.
 * Robolectric — ради настоящего org.json в RecurrenceHelper.parseRule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecurrenceHelperTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before fun saveDefaults() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
    }

    @After fun restoreDefaults() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    private val day = 24L * 60 * 60 * 1000

    private fun today(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 16, 0, 0, 0) // понедельник
    }

    private fun event(start: Long, type: Int, mask: Int = 0, occurrences: Int = 0, interval: Int = 1) = CalendarEventEntity(
        id = "1_e", accountId = 1, serverId = "e", subject = "Планёрка",
        startTime = start, endTime = start + 30 * 60_000, isRecurring = true,
        recurrenceRule = """{"type":$type,"interval":$interval,"dayOfWeek":$mask,"dayOfMonth":0,"weekOfMonth":0,""" +
            """"monthOfYear":0,"until":0,"occurrences":$occurrences,"firstDayOfWeek":0}"""
    )

    /** Эталон: вхождения 09:00 по дням маски (или каждый день) от начала серии, первые [occurrences], в окне. */
    private fun expected(start: Calendar, mask: Int?, rangeStart: Long, rangeEnd: Long, occurrences: Int): Int {
        val c = start.clone() as Calendar
        var n = 0
        var index = 0
        while (c.timeInMillis <= rangeEnd) {
            if (mask == null || (mask and (1 shl (c.get(Calendar.DAY_OF_WEEK) - 1))) != 0) {
                if (occurrences in 1..index) break
                index++
                if (c.timeInMillis >= rangeStart) n++
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return minOf(n, 500)
    }

    private fun windows(today: Calendar): List<Pair<Long, Long>> {
        fun shifted(field: Int, amount: Int) = (today.clone() as Calendar).apply { add(field, amount) }.timeInMillis
        val t = today.timeInMillis
        return listOf(
            t to shifted(Calendar.DAY_OF_YEAR, 1),            // Сегодня
            t to shifted(Calendar.DAY_OF_YEAR, 7),            // Неделя
            t to shifted(Calendar.MONTH, 1),                  // Месяц
            shifted(Calendar.DAY_OF_YEAR, -90) to shifted(Calendar.DAY_OF_YEAR, 365) // Все
        )
    }

    private fun count(e: CalendarEventEntity, w: Pair<Long, Long>) = RecurrenceHelper.generateOccurrences(e, w.first, w.second).size

    private fun inLocale(tag: String, zone: String = "Europe/Moscow", block: () -> Unit) {
        Locale.setDefault(Locale.forLanguageTag(tag))
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        block()
    }

    @Test fun `daily series of any age shows every occurrence of the window`() {
        for (tag in listOf("ru-RU", "en-US")) inLocale(tag) {
            for (age in listOf(1, 30, 100, 135, 300, 500, 591, 2_000, 10_000)) for (occ in listOf(0, 10, 200)) {
                val start = today().apply { add(Calendar.DAY_OF_YEAR, -age); set(Calendar.HOUR_OF_DAY, 9) }
                val e = event(start.timeInMillis, type = 0, occurrences = occ)
                for (w in windows(today())) {
                    assertThat(count(e, w)).isEqualTo(expected(start, null, w.first, w.second, occ))
                }
            }
        }
    }

    @Test fun `two-year-old daily standup is still in Today and in the whole year ahead`() {
        inLocale("ru-RU") {
            val start = today().apply { add(Calendar.DAY_OF_YEAR, -730); set(Calendar.HOUR_OF_DAY, 9) }
            val e = event(start.timeInMillis, type = 0)
            val (todayW, _, _, all) = windows(today())
            assertThat(count(e, todayW)).isEqualTo(1)
            assertThat(count(e, all)).isEqualTo(455)
        }
    }

    @Test fun `weekly series match the reference in Monday-first and Sunday-first locales`() {
        val masks = listOf(62 /*Пн-Пт*/, 2 /*Пн*/, 1 /*Вс*/, 65 /*Сб+Вс*/, 127)
        for ((tag, zone) in listOf("ru-RU" to "Europe/Moscow", "de-DE" to "Europe/Berlin", "en-US" to "America/New_York")) inLocale(tag, zone) {
            for (k in 0..6) {
                val today = today().apply { add(Calendar.DAY_OF_YEAR, k) }
                for (mask in masks) for (age in listOf(3, 10, 100, 700, 3_000)) for (occ in listOf(0, 15)) {
                    val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -age) }
                    while ((1 shl (start.get(Calendar.DAY_OF_WEEK) - 1)) and mask == 0) start.add(Calendar.DAY_OF_YEAR, 1)
                    start.set(Calendar.HOUR_OF_DAY, 9)
                    val e = event(start.timeInMillis, type = 1, mask = mask, occurrences = occ)
                    for (w in windows(today)) {
                        assertThat(count(e, w)).isEqualTo(expected(start, mask, w.first, w.second, occ))
                    }
                }
            }
        }
    }

    @Test fun `workday series shows today's meeting on every workday in ru locale`() {
        inLocale("ru-RU") {
            for (k in 0..4) { // Пн..Пт
                val today = today().apply { add(Calendar.DAY_OF_YEAR, k) }
                val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -98); set(Calendar.HOUR_OF_DAY, 9) }
                val e = event(start.timeInMillis, type = 1, mask = 62)
                assertThat(count(e, today.timeInMillis to today.timeInMillis + day)).isEqualTo(1)
            }
        }
    }

    @Test fun `weekly series does not produce occurrences before its start`() {
        inLocale("ru-RU") {
            // Серия Пн-Пт, начата в среду: понедельник и вторник той недели — не её вхождения
            val wednesday = today().apply { add(Calendar.DAY_OF_YEAR, 2); set(Calendar.HOUR_OF_DAY, 9) }
            val e = event(wednesday.timeInMillis, type = 1, mask = 62)
            val week = today().timeInMillis to today().timeInMillis + 7 * day

            val starts = RecurrenceHelper.generateOccurrences(e, week.first, week.second).map { it.startTime }

            assertThat(starts.first()).isEqualTo(wednesday.timeInMillis)
            assertThat(starts).hasSize(3)
        }
    }

    @Test fun `zero or negative interval does not hang`() {
        inLocale("ru-RU") {
            val start = today().apply { add(Calendar.DAY_OF_YEAR, -400); set(Calendar.HOUR_OF_DAY, 9) }
            for (interval in listOf(0, -3)) {
                val daily = event(start.timeInMillis, type = 0, interval = interval)
                val weekly = event(start.timeInMillis, type = 1, mask = 2, interval = interval)
                val (todayW, _, _, all) = windows(today())
                assertThat(count(daily, todayW)).isEqualTo(1)
                assertThat(count(weekly, all)).isGreaterThan(0)
            }
        }
    }
}

package com.dedovmosol.iwomail.ui.screens

import java.util.Calendar
import java.util.TimeZone

/** Варианты «Отправить позже» — чистые функции (тестируются без UI). Время — ровно час:00:00.000. */
internal object ScheduleSendTimes {

    /** Завтра в [hour]:00. */
    fun tomorrowAt(now: Long, hour: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        at(now, hour, zone) { add(Calendar.DAY_OF_YEAR, 1) }

    /**
     * Ближайший понедельник в [hour]:00, строго позже [now]: в понедельник до этого часа — сегодня, после — через неделю.
     * Раньше время сравнивалось до установки часа: в понедельник утром выбиралась следующая неделя.
     */
    fun nextMondayAt(now: Long, hour: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val candidate = at(now, hour, zone) {
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_YEAR, 1)
        }
        return if (candidate > now) candidate else at(candidate, hour, zone) { add(Calendar.WEEK_OF_YEAR, 1) }
    }

    private inline fun at(from: Long, hour: Int, zone: TimeZone, shift: Calendar.() -> Unit): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            shift()
        }.timeInMillis
}

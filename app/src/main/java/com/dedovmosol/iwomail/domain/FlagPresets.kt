package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.eas.MailFlag
import java.util.Calendar
import java.util.TimeZone

/** Быстрые сроки флага — как в Outlook («Сегодня», «Завтра», «На этой неделе», «На следующей неделе», «Без срока»). */
enum class FlagPreset { TODAY, TOMORROW, THIS_WEEK, NEXT_WEEK, NO_DATE }

/** Флаги по выбору пользователя — чистые функции (неделя — с понедельника, рабочие дни — пн–пт). */
object FlagPresets {

    /**
     * Активный флаг со сроком [preset]. Напоминание [current] сохраняется, если оно ещё впереди: смена срока его не
     * снимает.
     * - На этой неделе: срок — пятница (в выходные — сегодня), начало — через два дня, но не позже срока.
     * - На следующей неделе: с понедельника по пятницу следующей недели.
     */
    fun flag(preset: FlagPreset, current: MailFlag, now: Long, zone: TimeZone = TimeZone.getDefault()): MailFlag {
        val reminder = current.reminderTime?.takeIf { current.active && it > now }
        val today = startOfDay(now, zone)
        val weekday = mondayIndex(today, zone)
        return when (preset) {
            FlagPreset.TODAY -> MailFlag.due(today, today, reminder)
            FlagPreset.TOMORROW -> plusDays(today, 1, zone).let { MailFlag.due(it, it, reminder) }
            FlagPreset.THIS_WEEK -> {
                val untilFriday = (FRIDAY - weekday).coerceAtLeast(0)
                MailFlag.due(plusDays(today, minOf(2, untilFriday), zone), plusDays(today, untilFriday, zone), reminder)
            }
            FlagPreset.NEXT_WEEK -> {
                val monday = plusDays(today, 7 - weekday, zone)
                MailFlag.due(monday, plusDays(monday, FRIDAY, zone), reminder)
            }
            FlagPreset.NO_DATE -> MailFlag(MailFlag.STATUS_ACTIVE, reminderTime = reminder)
        }
    }

    /** Напомнить в [time]: у флага без срока срок — день напоминания (иначе Outlook показал бы флаг «без даты»). */
    fun withReminder(current: MailFlag, time: Long, zone: TimeZone = TimeZone.getDefault()): MailFlag {
        if (current.active && current.dueDate != null) return current.copy(reminderTime = time)
        val day = startOfDay(time, zone)
        return MailFlag.due(day, day, time)
    }

    /** Отметка, которую ставит звёздочка: снять или поставить без срока. */
    fun toggled(current: MailFlag): MailFlag = if (current.active) MailFlag.NONE else MailFlag(MailFlag.STATUS_ACTIVE)

    private const val FRIDAY = 4

    internal fun startOfDay(millis: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Понедельник — 0, воскресенье — 6. */
    private fun mondayIndex(day: Long, zone: TimeZone): Int =
        (Calendar.getInstance(zone).apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK) + 5) % 7

    /** Календарные дни (с переходом на летнее время — полночь остаётся полночью). */
    private fun plusDays(day: Long, days: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply { timeInMillis = day; add(Calendar.DAY_OF_YEAR, days) }.timeInMillis
}

/** Срок флага относительно сегодняшнего дня. */
enum class FlagDue {
    OVERDUE, TODAY, TOMORROW, LATER;

    companion object {
        /** [dueDay] — локальная полночь дня срока ([MailFlag.dueDate]). */
        fun of(dueDay: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): FlagDue {
            val today = FlagPresets.startOfDay(now, zone)
            val due = FlagPresets.startOfDay(dueDay, zone)
            val tomorrow = Calendar.getInstance(zone).apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            return when {
                due < today -> OVERDUE
                due == today -> TODAY
                due == tomorrow -> TOMORROW
                else -> LATER
            }
        }
    }
}

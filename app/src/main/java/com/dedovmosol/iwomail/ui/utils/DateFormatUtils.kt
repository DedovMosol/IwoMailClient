package com.dedovmosol.iwomail.ui.utils

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }
private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat("EEE", Locale.getDefault()) }
private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("d MMM", Locale.getDefault()) }
private val fullDateFormat = ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yy", Locale.getDefault()) }

private val reusableCalendar = ThreadLocal.withInitial { Calendar.getInstance() }

@Volatile private var cachedTodayDayOfYear = -1
@Volatile private var cachedTodayYear = -1
@Volatile private var cachedTodayTimestamp = 0L

private fun ensureTodayCached() {
    val now = System.currentTimeMillis()
    if (now - cachedTodayTimestamp > 60_000) {
        val today = Calendar.getInstance()
        cachedTodayDayOfYear = today.get(Calendar.DAY_OF_YEAR)
        cachedTodayYear = today.get(Calendar.YEAR)
        cachedTodayTimestamp = now
    }
}

fun formatRelativeDate(timestamp: Long): String {
    ensureTodayCached()
    val now = cachedTodayTimestamp
    val diff = now - timestamp
    val calendar = reusableCalendar.get()!!.apply { timeInMillis = timestamp }

    return when {
        diff < 24 * 60 * 60 * 1000
                && calendar.get(Calendar.DAY_OF_YEAR) == cachedTodayDayOfYear
                && calendar.get(Calendar.YEAR) == cachedTodayYear ->
            timeFormat.get()!!.format(timestamp)
        diff < 7 * 24 * 60 * 60 * 1000 ->
            dayFormat.get()!!.format(timestamp)
        calendar.get(Calendar.YEAR) == cachedTodayYear ->
            dateFormat.get()!!.format(timestamp)
        else ->
            fullDateFormat.get()!!.format(timestamp)
    }
}

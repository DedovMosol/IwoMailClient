package com.dedovmosol.iwomail.ui.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dayFormat = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val fullDateFormat = DateTimeFormatter.ofPattern("dd.MM.yy", Locale.getDefault())

private val zoneId = ZoneId.systemDefault()

@Volatile private var cachedTodayDayOfYear = -1
@Volatile private var cachedTodayYear = -1
@Volatile private var cachedTodayTimestamp = 0L

private fun ensureTodayCached() {
    val now = System.currentTimeMillis()
    if (now - cachedTodayTimestamp > 60_000) {
        val today = java.time.LocalDate.now(zoneId)
        cachedTodayDayOfYear = today.dayOfYear
        cachedTodayYear = today.year
        cachedTodayTimestamp = now
    }
}

fun formatRelativeDate(timestamp: Long): String {
    ensureTodayCached()
    val diff = cachedTodayTimestamp - timestamp
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(zoneId)

    return when {
        diff < 24 * 60 * 60 * 1000
                && dateTime.dayOfYear == cachedTodayDayOfYear
                && dateTime.year == cachedTodayYear ->
            dateTime.format(timeFormat)
        diff < 7 * 24 * 60 * 60 * 1000 ->
            dateTime.format(dayFormat)
        dateTime.year == cachedTodayYear ->
            dateTime.format(dateFormat)
        else ->
            dateTime.format(fullDateFormat)
    }
}

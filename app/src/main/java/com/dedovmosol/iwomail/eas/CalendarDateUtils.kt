package com.dedovmosol.iwomail.eas

import java.text.SimpleDateFormat
import java.util.*

/**
 * Stateless date/time/timezone utilities for EAS Calendar operations.
 *
 * Extracted from EasCalendarService (Phase 1 of H-12 decomposition).
 *
 * Covers:
 *  - EAS compact date formatting (yyyyMMdd'T'HHmmss'Z')
 *  - EWS ISO 8601 date formatting
 *  - All-day event normalization (UTC midnight)
 *  - EAS timezone blob (TIME_ZONE_INFORMATION, 172 bytes, MS-ASDTYPE 2.2.7)
 *  - EWS MeetingTimeZone XML (Exchange 2007 SP1: TimeZoneName sufficient)
 *  - EAS↔EWS recurrence converters (DaysOfWeek bitmask, day/month names)
 *  - BusyStatus mapping (EAS Int → EWS String)
 *  - JSON string escaping
 *  - Duplicate line removal (Exchange body normalization)
 *
 * Thread-safety: All methods are stateless or use local formatters.
 *   formatEasDate() is @Synchronized on the object monitor.
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
object CalendarDateUtils {

    // ========================= EAS Date Formatting =========================

    fun createEasDateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    @Synchronized
    fun formatEasDate(timestamp: Long): String {
        return createEasDateFormat().format(Date(timestamp))
    }

    fun formatEwsDate(timestamp: Long): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date(timestamp))
    }

    /**
     * All-day events in EWS: extract date from UTC-midnight timestamp
     * and format as "YYYY-MM-DDT00:00:00" WITHOUT 'Z' suffix.
     * Exchange interprets time without Z in mailbox timezone,
     * correctly recognizing midnight→midnight as all-day.
     */
    fun formatEwsAllDayDate(utcMidnightTimestamp: Long): String {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCal.timeInMillis = utcMidnightTimestamp
        return String.format(
            Locale.US, "%04d-%02d-%02dT00:00:00",
            utcCal.get(Calendar.YEAR),
            utcCal.get(Calendar.MONTH) + 1,
            utcCal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Takes date (year/month/day) from local-timestamp, returns midnight UTC of that date.
     * [addDay]=true → +1 day (for End of all-day events).
     */
    fun normalizeAllDayUtcMidnight(localTimestamp: Long, addDay: Boolean): Long {
        val local = Calendar.getInstance()
        local.timeInMillis = localTimestamp
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
            0, 0, 0
        )
        if (addDay) utc.add(Calendar.DAY_OF_MONTH, 1)
        return utc.timeInMillis
    }

    // ========================= EWS DateTime Parsing =========================

    fun parseEwsDateTime(dateStr: String?): Long? {
        if (dateStr.isNullOrEmpty()) return null
        return try {
            val cleaned = dateStr
                .replace(Regex("\\.\\d+"), "")
                .replace("Z", "")
                .replace(Regex("[+-]\\d{2}:\\d{2}$"), "")
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(cleaned)?.time
        } catch (_: Exception) {
            null
        }
    }

    // ========================= ISO Calendar Helpers =========================

    fun localCalFromIso(isoDate: String): Calendar? {
        return try {
            val df: SimpleDateFormat
            if (isoDate.endsWith("Z")) {
                df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                df.timeZone = TimeZone.getTimeZone("UTC")
            } else {
                df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            }
            val date = df.parse(isoDate) ?: return null
            Calendar.getInstance().apply { time = date }
        } catch (_: Exception) { null }
    }

    fun localDateFromIso(isoDate: String): String {
        val cal = localCalFromIso(isoDate) ?: return isoDate.substringBefore("T")
        return String.format(
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun ewsDayNameFromIso(isoDate: String): String {
        val cal = localCalFromIso(isoDate) ?: return "Monday"
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Sunday"
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> "Monday"
        }
    }

    fun ewsDayOfWeekIndex(cal: Calendar): String {
        return when (cal.get(Calendar.DAY_OF_WEEK_IN_MONTH)) {
            1 -> "First"
            2 -> "Second"
            3 -> "Third"
            4 -> "Fourth"
            else -> "Last"
        }
    }

    fun ewsMonthName(month: Int): String = when (month) {
        1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
        5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
        9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"
        else -> "January"
    }

    // ========================= EWS ↔ EAS Converters =========================

    /** EWS DaysOfWeek string → bitmask (Sun=1,Mon=2,Tue=4,...,Sat=64). MS-ASCAL 2.2.2.14. */
    fun ewsDaysOfWeekToBitmask(days: String): Int {
        var mask = 0
        if (days.contains("Sunday")) mask = mask or 1
        if (days.contains("Monday")) mask = mask or 2
        if (days.contains("Tuesday")) mask = mask or 4
        if (days.contains("Wednesday")) mask = mask or 8
        if (days.contains("Thursday")) mask = mask or 16
        if (days.contains("Friday")) mask = mask or 32
        if (days.contains("Saturday")) mask = mask or 64
        return mask
    }

    fun ewsDayNameToNumber(name: String): Int = when (name) {
        "Sunday" -> 0; "Monday" -> 1; "Tuesday" -> 2; "Wednesday" -> 3
        "Thursday" -> 4; "Friday" -> 5; "Saturday" -> 6; else -> 0
    }

    fun ewsWeekIndexToNumber(index: String): Int = when (index) {
        "First" -> 1; "Second" -> 2; "Third" -> 3; "Fourth" -> 4; "Last" -> 5; else -> 0
    }

    fun ewsMonthToNumber(month: String): Int = when (month) {
        "January" -> 1; "February" -> 2; "March" -> 3; "April" -> 4
        "May" -> 5; "June" -> 6; "July" -> 7; "August" -> 8
        "September" -> 9; "October" -> 10; "November" -> 11; "December" -> 12
        else -> 0
    }

    /** EAS busyStatus (Int) → EWS LegacyFreeBusyStatus (String). */
    fun mapBusyStatusToEws(busyStatus: Int): String = when (busyStatus) {
        0 -> "Free"
        1 -> "Tentative"
        3 -> "OOF"
        else -> "Busy"
    }

    // ========================= Timezone =========================

    /**
     * EAS <calendar:Timezone> blob from device timezone.
     * TIME_ZONE_INFORMATION struct (172 bytes): Bias + StandardName + StandardDate +
     * StandardBias + DaylightName + DaylightDate + DaylightBias.
     * MS-ASDTYPE 2.2.7.
     */
    fun buildDeviceTimezoneBlob(): String {
        val tz = TimeZone.getDefault()
        val biasMinutes = -(tz.rawOffset / 60_000)
        val buf = java.nio.ByteBuffer.allocate(172).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(biasMinutes)
        repeat(32) { buf.putShort(0) }
        if (tz.useDaylightTime()) {
            try {
                val rules = java.time.ZoneId.systemDefault().rules
                val trRules = rules.transitionRules
                val toStd = trRules.firstOrNull { it.offsetAfter.totalSeconds < it.offsetBefore.totalSeconds }
                val toDst = trRules.firstOrNull { it.offsetAfter.totalSeconds > it.offsetBefore.totalSeconds }
                fun writeSystemTime(r: java.time.zone.ZoneOffsetTransitionRule?) {
                    if (r != null) {
                        buf.putShort(0)
                        buf.putShort(r.month.value.toShort())
                        buf.putShort((r.dayOfWeek.value % 7).toShort())
                        val week = if (r.dayOfMonthIndicator < 0) 5 else ((r.dayOfMonthIndicator - 1) / 7 + 1)
                        buf.putShort(week.toShort())
                        buf.putShort(r.localTime.hour.toShort())
                        buf.putShort(r.localTime.minute.toShort())
                        buf.putShort(0); buf.putShort(0)
                    } else repeat(8) { buf.putShort(0) }
                }
                writeSystemTime(toStd)
                buf.putInt(0)
                repeat(32) { buf.putShort(0) }
                writeSystemTime(toDst)
                buf.putInt(-(tz.dstSavings / 60_000))
            } catch (_: Exception) {
                while (buf.hasRemaining()) buf.put(0)
            }
        } else {
            repeat(8) { buf.putShort(0) }
            buf.putInt(0)
            repeat(32) { buf.putShort(0) }
            repeat(8) { buf.putShort(0) }
            buf.putInt(0)
        }
        return android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP)
    }

    /**
     * EWS <MeetingTimeZone> XML for Exchange 2007 SP1.
     * Uses full BaseOffset + Standard/Daylight elements (works on both RTM and SP1).
     */
    fun buildMeetingTimeZoneXml(): String {
        val tz = TimeZone.getDefault()
        val rawMinutes = tz.rawOffset / 60_000
        val biasMinutes = -rawMinutes
        val absH = kotlin.math.abs(rawMinutes) / 60
        val absM = kotlin.math.abs(rawMinutes) % 60
        val sign = if (rawMinutes >= 0) "+" else "-"
        val displayName = "(UTC${sign}${String.format(Locale.US, "%02d:%02d", absH, absM)})"

        fun durationStr(min: Int): String {
            val s = if (min < 0) "-" else ""
            val a = kotlin.math.abs(min)
            return "${s}P0DT${a / 60}H${a % 60}M0.0S"
        }

        val sb = StringBuilder()
        sb.append("<t:MeetingTimeZone TimeZoneName=\"$displayName\">")
        sb.append("<t:BaseOffset>${durationStr(biasMinutes)}</t:BaseOffset>")
        if (tz.useDaylightTime()) {
            try {
                val rules = java.time.ZoneId.systemDefault().rules
                val trRules = rules.transitionRules
                val toStd = trRules.firstOrNull { it.offsetAfter.totalSeconds < it.offsetBefore.totalSeconds }
                val toDst = trRules.firstOrNull { it.offsetAfter.totalSeconds > it.offsetBefore.totalSeconds }
                fun weekIndex(day: Int) = when {
                    day < 0 -> "Last"; day <= 7 -> "First"; day <= 14 -> "Second"
                    day <= 21 -> "Third"; day <= 28 -> "Fourth"; else -> "Last"
                }
                fun dayName(d: java.time.DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }
                fun monthName(m: java.time.Month) = m.name.lowercase().replaceFirstChar { it.uppercase() }
                if (toStd != null) {
                    sb.append("<t:Standard><t:Offset>P0DT0H0M0.0S</t:Offset>")
                    sb.append("<t:RelativeYearlyRecurrence>")
                    sb.append("<t:DaysOfWeek>${dayName(toStd.dayOfWeek)}</t:DaysOfWeek>")
                    sb.append("<t:DayOfWeekIndex>${weekIndex(toStd.dayOfMonthIndicator)}</t:DayOfWeekIndex>")
                    sb.append("<t:Month>${monthName(toStd.month)}</t:Month>")
                    sb.append("</t:RelativeYearlyRecurrence>")
                    sb.append("<t:Time>${toStd.localTime}:00.0000000</t:Time></t:Standard>")
                }
                if (toDst != null) {
                    sb.append("<t:Daylight><t:Offset>${durationStr(-(tz.dstSavings / 60_000))}</t:Offset>")
                    sb.append("<t:RelativeYearlyRecurrence>")
                    sb.append("<t:DaysOfWeek>${dayName(toDst.dayOfWeek)}</t:DaysOfWeek>")
                    sb.append("<t:DayOfWeekIndex>${weekIndex(toDst.dayOfMonthIndicator)}</t:DayOfWeekIndex>")
                    sb.append("<t:Month>${monthName(toDst.month)}</t:Month>")
                    sb.append("</t:RelativeYearlyRecurrence>")
                    sb.append("<t:Time>${toDst.localTime}:00.0000000</t:Time></t:Daylight>")
                }
            } catch (_: Exception) { /* omit Standard/Daylight on error */ }
        }
        sb.append("</t:MeetingTimeZone>")
        return sb.toString()
    }

    // ========================= String Utils =========================

    fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun removeDuplicateLines(text: String): String {
        val normalized = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?div[^>]*>", RegexOption.IGNORE_CASE), "\n")

        val lines = normalized.lines()
        val seen = mutableSetOf<String>()
        return lines.filter { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) true else seen.add(trimmed)
        }.joinToString("\n")
    }
}

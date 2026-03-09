package com.dedovmosol.iwomail.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * iCalendar (RFC 5545) parsing utilities.
 *
 * Supports Exchange 2007 SP1 meeting invitations:
 * - DTSTART/DTEND: UTC (Z suffix), TZID parameter, DATE-only (all-day)
 * - SUMMARY, LOCATION, DESCRIPTION, ORGANIZER, METHOD (REQUEST/CANCEL/REPLY)
 * - LINE FOLDING: RFC 5545 §3.1 — lines folded at 75 octets with CRLF + SPACE/TAB
 *
 * Ignores X-MICROSOFT-CDO-* extensions (non-standard).
 */
object ICalParser {

    private val METHOD_REGEX = Regex("METHOD:([A-Z]+)")
    private val UID_REGEX = Regex("UID:([^\\r\\n]+)")

    data class MeetingInfo(
        val summary: String,
        val dtStart: Long?,
        val dtEnd: Long?,
        val location: String,
        val description: String,
        val organizer: String,
        val method: String,
        val uid: String
    )

    data class TaskInfo(
        val subject: String,
        val dueDate: Long,
        val description: String
    )

    /**
     * Parses an iCalendar date string with optional TZID.
     * Formats: 20260115T100000Z (UTC), 20260115T100000 (local/TZID), 20260115 (all-day).
     */
    fun parseICalDate(dateStr: String?, tzid: String? = null): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            val isUtc = dateStr.endsWith("Z")
            val cleanDate = dateStr.removeSuffix("Z")
            val format = when (cleanDate.length) {
                8 -> SimpleDateFormat("yyyyMMdd", Locale.US)
                15 -> SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                else -> return null
            }
            format.timeZone = when {
                isUtc -> TimeZone.getTimeZone("UTC")
                !tzid.isNullOrBlank() -> TimeZone.getTimeZone(tzid)
                else -> TimeZone.getDefault()
            }
            format.parse(cleanDate)?.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts meeting info from iCalendar content.
     * Must be called on content with LINE FOLDING already resolved.
     */
    fun parseMeetingFromIcal(icalContent: String): MeetingInfo? {
        if (!icalContent.contains("BEGIN:VEVENT", ignoreCase = true) &&
            !icalContent.contains("BEGIN:VCALENDAR", ignoreCase = true)
        ) return null

        val organizerMatch = ICAL_ORGANIZER_REGEX.find(icalContent)
        val organizer = organizerMatch?.groupValues?.get(1) ?: ""

        val summaryMatch = ICAL_SUMMARY_REGEX.find(icalContent)
        val summary = summaryMatch?.groupValues?.get(1)
            ?.replace("\\n", " ")?.replace("\\,", ",")?.trim() ?: ""

        val dtStartMatch = ICAL_DTSTART_REGEX.find(icalContent)
        val dtStartTzid = dtStartMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        val dtStartStr = dtStartMatch?.groupValues?.get(2)

        val dtEndMatch = ICAL_DTEND_REGEX.find(icalContent)
        val dtEndTzid = dtEndMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        val dtEndStr = dtEndMatch?.groupValues?.get(2)

        val location = ICAL_LOCATION_REGEX.find(icalContent)?.groupValues?.get(1)
            ?.replace("\\n", " ")?.replace("\\,", ",")?.trim() ?: ""
        val description = ICAL_DESCRIPTION_REGEX.find(icalContent)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\,", ",")?.trim() ?: ""

        val method = METHOD_REGEX.find(icalContent)?.groupValues?.get(1) ?: ""
        val uid = UID_REGEX.find(icalContent)?.groupValues?.get(1)?.trim() ?: ""

        return MeetingInfo(
            summary = summary,
            dtStart = parseICalDate(dtStartStr, dtStartTzid),
            dtEnd = parseICalDate(dtEndStr, dtEndTzid),
            location = location,
            description = description,
            organizer = organizer,
            method = method,
            uid = uid
        )
    }

    /**
     * Parses task data from email body.
     * Supports "Задача:/Task:" subject prefix and "Срок выполнения:/Due date:" patterns.
     */
    fun parseTaskFromEmailBody(subject: String, bodyHtml: String): TaskInfo? {
        val isTask = subject.startsWith("Задача:", ignoreCase = true) ||
                subject.startsWith("Task:", ignoreCase = true)
        if (!isTask) return null

        val taskTitle = TASK_SUBJECT_REGEX.find(subject)?.let {
            it.groupValues[1].ifEmpty { it.groupValues[2] }
        } ?: subject.removePrefix("Задача:").removePrefix("Task:").trim()

        val bodyText = bodyHtml
            .replace(HtmlRegex.TAG, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        val dueDateMatch = TASK_DUE_DATE_REGEX.find(bodyText)
        val dueDate = dueDateMatch?.let {
            val dateStr = it.groupValues[1].ifEmpty { it.groupValues[2] }
            try {
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).parse(dateStr)?.time
                    ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis() + 86_400_000L
            }
        } ?: (System.currentTimeMillis() + 86_400_000L)

        val description = TASK_DESCRIPTION_REGEX.find(bodyText)?.let {
            it.groupValues[1].ifEmpty { it.groupValues[2] }.trim()
        } ?: ""

        return TaskInfo(subject = taskTitle, dueDate = dueDate, description = description)
    }
}

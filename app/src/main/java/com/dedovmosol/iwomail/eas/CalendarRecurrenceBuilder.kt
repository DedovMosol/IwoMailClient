package com.dedovmosol.iwomail.eas

import java.util.Calendar

/**
 * Builds EAS/EWS recurrence XML for calendar event CRUD operations.
 *
 * Extracted from EasCalendarService (Phase 3 of H-12 decomposition).
 *
 * All methods are stateless, using CalendarDateUtils for date/time helpers.
 *
 * EAS Recurrence: MS-ASCAL 2.2.2.39 — Type, Interval, DayOfWeek (bitmask), DayOfMonth, MonthOfYear
 * EWS Recurrence: DailyRecurrence, WeeklyRecurrence, AbsoluteMonthly, RelativeMonthly,
 *                 AbsoluteYearly, RelativeYearly + NoEndRecurrence/EndDateRecurrence/NumberedRecurrence
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
object CalendarRecurrenceBuilder {

    /**
     * EAS <calendar:Recurrence> XML for Sync Add/Change.
     * @param recurrenceType -1=none, 0=Daily, 1=Weekly, 2=Monthly, 5=Yearly (MS-ASCAL Type values)
     * @param startTime event start timestamp (determines day-of-week/month)
     */
    fun buildEasRecurrenceXml(recurrenceType: Int, startTime: Long): String {
        if (recurrenceType < 0) return ""

        val cal = Calendar.getInstance()
        cal.timeInMillis = startTime

        return buildString {
            append("<calendar:Recurrence>")
            append("<calendar:Type>$recurrenceType</calendar:Type>")
            append("<calendar:Interval>1</calendar:Interval>")

            when (recurrenceType) {
                0 -> { /* Daily — no extra elements */ }
                1 -> {
                    val dayBitmask = 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
                    append("<calendar:DayOfWeek>$dayBitmask</calendar:DayOfWeek>")
                }
                2 -> {
                    append("<calendar:DayOfMonth>${cal.get(Calendar.DAY_OF_MONTH)}</calendar:DayOfMonth>")
                }
                5 -> {
                    append("<calendar:DayOfMonth>${cal.get(Calendar.DAY_OF_MONTH)}</calendar:DayOfMonth>")
                    append("<calendar:MonthOfYear>${cal.get(Calendar.MONTH) + 1}</calendar:MonthOfYear>")
                }
            }

            append("</calendar:Recurrence>")
        }
    }

    /**
     * EWS <Recurrence> XML for CreateItem (inside CalendarItem — no t: prefix).
     * @param recurrenceType -1=none, 0=Daily, 1=Weekly, 2=Monthly, 3=RelativeMonthly, 5=Yearly, 6=RelativeYearly
     * @param startTimeStr ISO date string of event start
     */
    fun buildEwsRecurrenceXml(recurrenceType: Int, startTimeStr: String): String {
        if (recurrenceType < 0) return ""

        val localCal = CalendarDateUtils.localCalFromIso(startTimeStr)
        val startDate = CalendarDateUtils.localDateFromIso(startTimeStr)

        return buildString {
            append("<Recurrence>")
            appendRecurrencePattern(this, recurrenceType, startTimeStr, localCal, prefix = "")
            append("<NoEndRecurrence>")
            append("<StartDate>$startDate</StartDate>")
            append("</NoEndRecurrence>")
            append("</Recurrence>")
        }
    }

    /**
     * EWS SetItemField for Recurrence in UpdateItem (t: prefixed elements).
     * @param recurrenceType -1=none, 0=Daily, 1=Weekly, 2=Monthly, 3=RelativeMonthly, 5=Yearly, 6=RelativeYearly
     * @param startTimeStr ISO date string of event start
     */
    fun buildEwsRecurrenceUpdateXml(recurrenceType: Int, startTimeStr: String): String {
        if (recurrenceType < 0) return ""

        val localCal = CalendarDateUtils.localCalFromIso(startTimeStr)
        val startDate = CalendarDateUtils.localDateFromIso(startTimeStr)

        return buildString {
            append("<t:SetItemField>")
            append("""<t:FieldURI FieldURI="calendar:Recurrence"/>""")
            append("<t:CalendarItem>")
            append("<t:Recurrence>")
            appendRecurrencePattern(this, recurrenceType, startTimeStr, localCal, prefix = "t:")
            append("<t:NoEndRecurrence>")
            append("<t:StartDate>$startDate</t:StartDate>")
            append("</t:NoEndRecurrence>")
            append("</t:Recurrence>")
            append("</t:CalendarItem>")
            append("</t:SetItemField>")
        }
    }

    private fun appendRecurrencePattern(
        sb: StringBuilder,
        recurrenceType: Int,
        startTimeStr: String,
        localCal: Calendar?,
        prefix: String
    ) {
        val p = prefix
        when (recurrenceType) {
            0 -> {
                sb.append("<${p}DailyRecurrence>")
                sb.append("<${p}Interval>1</${p}Interval>")
                sb.append("</${p}DailyRecurrence>")
            }
            1 -> {
                val ewsDayName = CalendarDateUtils.ewsDayNameFromIso(startTimeStr)
                sb.append("<${p}WeeklyRecurrence>")
                sb.append("<${p}Interval>1</${p}Interval>")
                sb.append("<${p}DaysOfWeek>$ewsDayName</${p}DaysOfWeek>")
                sb.append("</${p}WeeklyRecurrence>")
            }
            2 -> {
                val dayOfMonth = localCal?.get(Calendar.DAY_OF_MONTH) ?: 1
                sb.append("<${p}AbsoluteMonthlyRecurrence>")
                sb.append("<${p}Interval>1</${p}Interval>")
                sb.append("<${p}DayOfMonth>$dayOfMonth</${p}DayOfMonth>")
                sb.append("</${p}AbsoluteMonthlyRecurrence>")
            }
            3 -> {
                val ewsDayName = CalendarDateUtils.ewsDayNameFromIso(startTimeStr)
                val dayOfWeekIndex = localCal?.let { CalendarDateUtils.ewsDayOfWeekIndex(it) } ?: "First"
                sb.append("<${p}RelativeMonthlyRecurrence>")
                sb.append("<${p}Interval>1</${p}Interval>")
                sb.append("<${p}DaysOfWeek>$ewsDayName</${p}DaysOfWeek>")
                sb.append("<${p}DayOfWeekIndex>$dayOfWeekIndex</${p}DayOfWeekIndex>")
                sb.append("</${p}RelativeMonthlyRecurrence>")
            }
            5 -> {
                val dayOfMonth = localCal?.get(Calendar.DAY_OF_MONTH) ?: 1
                val monthNum = localCal?.let { it.get(Calendar.MONTH) + 1 } ?: 1
                val monthName = CalendarDateUtils.ewsMonthName(monthNum)
                sb.append("<${p}AbsoluteYearlyRecurrence>")
                sb.append("<${p}DayOfMonth>$dayOfMonth</${p}DayOfMonth>")
                sb.append("<${p}Month>$monthName</${p}Month>")
                sb.append("</${p}AbsoluteYearlyRecurrence>")
            }
            6 -> {
                val ewsDayName = CalendarDateUtils.ewsDayNameFromIso(startTimeStr)
                val dayOfWeekIndex = localCal?.let { CalendarDateUtils.ewsDayOfWeekIndex(it) } ?: "First"
                val monthNum = localCal?.let { it.get(Calendar.MONTH) + 1 } ?: 1
                val monthName = CalendarDateUtils.ewsMonthName(monthNum)
                sb.append("<${p}RelativeYearlyRecurrence>")
                sb.append("<${p}DaysOfWeek>$ewsDayName</${p}DaysOfWeek>")
                sb.append("<${p}DayOfWeekIndex>$dayOfWeekIndex</${p}DayOfWeekIndex>")
                sb.append("<${p}Month>$monthName</${p}Month>")
                sb.append("</${p}RelativeYearlyRecurrence>")
            }
        }
    }
}

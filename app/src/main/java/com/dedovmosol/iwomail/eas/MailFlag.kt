package com.dedovmosol.iwomail.eas

import java.util.Calendar
import java.util.TimeZone

/**
 * Флаг письма ([MS-ASEMAIL] Flag, EAS 12.0+ — Exchange 2007–2013): отметка «к исполнению», срок и напоминание.
 *
 * Даты срока — локальный день (полночь в часовом поясе устройства, epoch ms). В EAS их четыре:
 * tasks:StartDate/DueDate — локальное время (запись как UTC «стенные часы»), tasks:UtcStartDate/UtcDueDate — тот же
 * момент в UTC. Все четыре задаются вместе или не задаются вовсе (иначе Sync Status 6).
 */
data class MailFlag(
    val status: Int = STATUS_CLEARED,
    val startDate: Long? = null,
    val dueDate: Long? = null,
    /** Напоминание (UTC, epoch ms): tasks:ReminderSet=1 + tasks:ReminderTime. */
    val reminderTime: Long? = null
) {
    val active: Boolean get() = status == STATUS_ACTIVE
    val completed: Boolean get() = status == STATUS_COMPLETE

    companion object {
        const val STATUS_CLEARED = 0
        const val STATUS_COMPLETE = 1
        const val STATUS_ACTIVE = 2

        val NONE = MailFlag()

        /** Тип флага, как у Outlook ([MS-ASEMAIL] FlagType: обычно «for Follow Up»). */
        const val FOLLOW_UP = "for Follow Up"

        /** Активный флаг со сроком [startDate]..[dueDate] (локальные дни) и, при желании, напоминанием. */
        fun due(startDate: Long, dueDate: Long, reminderTime: Long? = null) =
            MailFlag(STATUS_ACTIVE, minOf(startDate, dueDate), dueDate, reminderTime)
    }
}

/** Разбор и запись флага письма в XML EAS (до WBXML). */
object MailFlags {

    /** Содержимое `<Flag>` ([flagXml] — то, что внутри элемента); null или пусто — флага нет. */
    fun parse(flagXml: String?, zone: TimeZone = TimeZone.getDefault()): MailFlag {
        if (flagXml.isNullOrBlank()) return MailFlag.NONE
        val status = value(flagXml, "FlagStatus")?.toIntOrNull() ?: MailFlag.STATUS_CLEARED
        if (status == MailFlag.STATUS_CLEARED) return MailFlag.NONE
        // Срок и напоминание — только у активного флага (у выполненного Outlook их не показывает)
        if (status != MailFlag.STATUS_ACTIVE) return MailFlag(status)
        val start = localDay(value(flagXml, "StartDate"), value(flagXml, "UtcStartDate"), zone)
        val due = localDay(value(flagXml, "DueDate"), value(flagXml, "UtcDueDate"), zone)
        val reminder = if (value(flagXml, "ReminderSet") == "1") parseUtc(value(flagXml, "ReminderTime")) else null
        return MailFlag(status, start, due, reminder)
    }

    /**
     * `<Flag>` для Sync Change. Отсутствующий дочерний элемент сервер удаляет ([MS-ASEMAIL] 4.2: implicit deletes),
     * поэтому флаг пишется целиком. Порядок элементов — как в [MS-ASEMAIL] 2.2.2.34.
     */
    fun toXml(flag: MailFlag, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
        buildString {
            append("<Flag xmlns=\"Email\">")
            when (flag.status) {
                MailFlag.STATUS_COMPLETE -> {
                    append("<FlagStatus>1</FlagStatus>")
                    append("<FlagType>${MailFlag.FOLLOW_UP}</FlagType>")
                    // CompleteTime и tasks:DateCompleted обязательны для «выполнено»
                    append("<tasks:DateCompleted>${utc(now)}</tasks:DateCompleted>")
                    append("<CompleteTime>${utc(now)}</CompleteTime>")
                }
                MailFlag.STATUS_ACTIVE -> {
                    append("<FlagStatus>2</FlagStatus>")
                    append("<FlagType>${MailFlag.FOLLOW_UP}</FlagType>")
                    val due = flag.dueDate
                    if (due != null) {
                        val start = flag.startDate?.coerceAtMost(due) ?: due
                        append("<tasks:StartDate>${wallClock(start, zone)}</tasks:StartDate>")
                        append("<tasks:DueDate>${wallClock(due, zone)}</tasks:DueDate>")
                        append("<tasks:UtcStartDate>${utc(start)}</tasks:UtcStartDate>")
                        append("<tasks:UtcDueDate>${utc(due)}</tasks:UtcDueDate>")
                    }
                    val reminder = flag.reminderTime
                    if (reminder != null) {
                        append("<tasks:ReminderSet>1</tasks:ReminderSet>")
                        append("<tasks:ReminderTime>${utc(reminder)}</tasks:ReminderTime>")
                    } else {
                        append("<tasks:ReminderSet>0</tasks:ReminderSet>")
                    }
                }
                else -> append("<FlagStatus>0</FlagStatus>")
            }
            append("</Flag>")
        }

    /** Значение элемента флага с любым префиксом пространства имён (разбор — на каждое письмо синхронизации). */
    private val TAGS = listOf(
        "FlagStatus", "StartDate", "UtcStartDate", "DueDate", "UtcDueDate", "ReminderSet", "ReminderTime"
    ).associateWith { tag -> Regex("<(?:[A-Za-z][\\w.-]*:)?$tag>([^<]*)</(?:[A-Za-z][\\w.-]*:)?$tag>") }

    private fun value(xml: String, tag: String): String? =
        TAGS.getValue(tag).find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** [MS-ASDTYPE] dateTime: «2026-10-30T00:00:00.000Z». */
    private fun utc(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).let { instant ->
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(instant)
        }

    /** Локальный день [localMidnight] «стенными часами»: та же дата и 00:00 с меткой Z (StartDate/DueDate). */
    private fun wallClock(localMidnight: Long, zone: TimeZone): String {
        val local = Calendar.getInstance(zone).apply { timeInMillis = localMidnight }
        return String.format(
            java.util.Locale.ROOT, "%04d-%02d-%02dT00:00:00.000Z",
            local.get(Calendar.YEAR), local.get(Calendar.MONTH) + 1, local.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun parseUtc(text: String?): Long? = text?.let { CalendarDateUtils.parseEwsDateTime(it) }

    /**
     * Локальный день срока: по StartDate/DueDate (дата «стенных часов» — не зависит от часового пояса ящика), иначе по
     * Utc-значению в поясе устройства. Полночь этого дня в [zone].
     */
    private fun localDay(wall: String?, utcValue: String?, zone: TimeZone): Long? {
        val utc = TimeZone.getTimeZone("UTC")
        val (year, month, day) = parseUtc(wall)?.let { millis ->
            Calendar.getInstance(utc).apply { timeInMillis = millis }
        }?.let { Triple(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH)) }
            ?: parseUtc(utcValue)?.let { millis ->
                Calendar.getInstance(zone).apply { timeInMillis = millis }
            }?.let { Triple(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH)) }
            ?: return null
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, 0, 0, 0)
        }.timeInMillis
    }
}

package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import org.json.JSONObject
import org.json.JSONArray
import java.util.*

/**
 * Генератор экземпляров повторяющихся событий календаря.
 * 
 * Работает с единым JSON форматом recurrenceRule:
 * {"type","interval","dayOfWeek","dayOfMonth","weekOfMonth","monthOfYear","until","occurrences","firstDayOfWeek"}
 * 
 * Типы повторений (MS-ASCAL):
 * 0 = Daily, 1 = Weekly, 2 = Monthly (абсолютный), 3 = Monthly (относительный),
 * 5 = Yearly (абсолютный), 6 = Yearly (относительный)
 */
object RecurrenceHelper {
    
    /** Максимум экземпляров для защиты от бесконечных циклов */
    private const val MAX_OCCURRENCES = 500
    
    /** Горизонт генерации по умолчанию — 1 год вперёд */
    private const val DEFAULT_HORIZON_MS = 365L * 24 * 60 * 60 * 1000
    
    /**
     * Данные правила повторения (парсится из JSON)
     */
    data class RecurrenceRule(
        val type: Int = 0,
        val interval: Int = 1,
        val dayOfWeek: Int = 0,
        val dayOfMonth: Int = 0,
        val weekOfMonth: Int = 0,
        val monthOfYear: Int = 0,
        val until: Long = 0,
        val occurrences: Int = 0,
        val firstDayOfWeek: Int = 0
    )
    
    /**
     * Исключение из серии повторений
     */
    data class RecurrenceException(
        val exceptionStartTime: Long,
        val deleted: Boolean,
        val subject: String = "",
        val location: String = "",
        val startTime: Long = 0,
        val endTime: Long = 0
    )
    
    /**
     * Виртуальный экземпляр повторяющегося события
     */
    data class OccurrenceInstance(
        val originalEvent: CalendarEventEntity,
        val startTime: Long,
        val endTime: Long,
        val subject: String,
        val location: String,
        val isException: Boolean = false
    )
    
    /**
     * Парсит recurrenceRule JSON в структуру
     */
    fun parseRule(json: String): RecurrenceRule? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            RecurrenceRule(
                type = obj.optInt("type", 0),
                interval = obj.optInt("interval", 1).coerceAtLeast(1),
                dayOfWeek = obj.optInt("dayOfWeek", 0),
                dayOfMonth = obj.optInt("dayOfMonth", 0),
                weekOfMonth = obj.optInt("weekOfMonth", 0),
                monthOfYear = obj.optInt("monthOfYear", 0),
                until = obj.optLong("until", 0),
                occurrences = obj.optInt("occurrences", 0),
                firstDayOfWeek = obj.optInt("firstDayOfWeek", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Парсит exceptions JSON в список исключений
     */
    fun parseExceptions(json: String): List<RecurrenceException> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RecurrenceException(
                    exceptionStartTime = obj.optLong("exceptionStartTime", 0),
                    deleted = obj.optBoolean("deleted", false),
                    subject = obj.optString("subject", ""),
                    location = obj.optString("location", ""),
                    startTime = obj.optLong("startTime", 0),
                    endTime = obj.optLong("endTime", 0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Генерирует экземпляры повторяющегося события для заданного диапазона дат.
     * 
     * @param event Исходное событие с recurrenceRule и exceptions
     * @param rangeStart Начало диапазона (timestamp)
     * @param rangeEnd Конец диапазона (timestamp)
     * @return Список виртуальных экземпляров в заданном диапазоне
     */
    fun generateOccurrences(
        event: CalendarEventEntity,
        rangeStart: Long,
        rangeEnd: Long
    ): List<OccurrenceInstance> {
        if (!event.isRecurring) return emptyList()
        
        val rule = parseRule(event.recurrenceRule) ?: return emptyList()
        val exceptions = parseExceptions(event.exceptions)
        val deletedTimes = exceptions.filter { it.deleted }.map { it.exceptionStartTime }.toSet()
        val modifiedExceptions = exceptions.filter { !it.deleted }.associateBy { it.exceptionStartTime }
        
        val duration = event.endTime - event.startTime
        val dates = generateDates(rule, event.startTime, rangeStart, rangeEnd)
        
        return dates.mapNotNull { occurrenceStart ->
            // Пропускаем удалённые экземпляры
            if (occurrenceStart in deletedTimes) return@mapNotNull null
            
            val occurrenceEnd = occurrenceStart + duration
            
            // Применяем модифицированное исключение
            val exception = modifiedExceptions[occurrenceStart]
            if (exception != null) {
                val actualStart = if (exception.startTime > 0) exception.startTime else occurrenceStart
                val actualEnd = if (exception.endTime > 0) exception.endTime else actualStart + duration
                OccurrenceInstance(
                    originalEvent = event,
                    startTime = actualStart,
                    endTime = actualEnd,
                    subject = exception.subject.ifBlank { event.subject },
                    location = exception.location.ifBlank { event.location },
                    isException = true
                )
            } else {
                OccurrenceInstance(
                    originalEvent = event,
                    startTime = occurrenceStart,
                    endTime = occurrenceEnd,
                    subject = event.subject,
                    location = event.location,
                    isException = false
                )
            }
        }
    }
    
    /**
     * Генерирует даты (timestamps начала) экземпляров серии в заданном диапазоне
     */
    private fun generateDates(
        rule: RecurrenceRule,
        seriesStart: Long,
        rangeStart: Long,
        rangeEnd: Long
    ): List<Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = seriesStart
        
        val effectiveEnd = when {
            rule.until > 0 -> minOf(rule.until, rangeEnd)
            else -> rangeEnd
        }
        
        val maxCount = if (rule.occurrences > 0) rule.occurrences else MAX_OCCURRENCES
        val results = mutableListOf<Long>()
        
        when (rule.type) {
            0 -> generateDaily(cal, rule.interval, rangeStart, effectiveEnd, maxCount, results)
            1 -> generateWeekly(cal, rule.interval, rule.dayOfWeek, rangeStart, effectiveEnd, maxCount, results)
            2 -> generateMonthlyAbsolute(cal, rule.interval, rule.dayOfMonth, rangeStart, effectiveEnd, maxCount, results)
            3 -> generateMonthlyRelative(cal, rule.interval, rule.dayOfWeek, rule.weekOfMonth, rangeStart, effectiveEnd, maxCount, results)
            5 -> generateYearlyAbsolute(cal, rule.dayOfMonth, rule.monthOfYear, rangeStart, effectiveEnd, maxCount, results)
            6 -> generateYearlyRelative(cal, rule.dayOfWeek, rule.weekOfMonth, rule.monthOfYear, rangeStart, effectiveEnd, maxCount, results)
        }
        
        return results
    }
    
    /** Daily: каждые N дней */
    private fun generateDaily(
        cal: Calendar, interval: Int,
        rangeStart: Long, rangeEnd: Long, maxCount: Int,
        results: MutableList<Long>
    ) {
        // Fast-forward: пропускаем даты до rangeStart
        var skippedCount = 0
        if (cal.timeInMillis < rangeStart && interval > 0) {
            val diffDays = (rangeStart - cal.timeInMillis) / (24L * 60 * 60 * 1000)
            val skipIntervals = (diffDays / interval).toInt()
            if (skipIntervals > 1) {
                cal.add(Calendar.DAY_OF_YEAR, (skipIntervals - 1) * interval)
                skippedCount = skipIntervals - 1
            }
        }
        
        // count учитывает пропущенные occurrence для корректного ограничения при occurrences > 0
        var count = skippedCount
        while (cal.timeInMillis <= rangeEnd && count < maxCount) {
            if (cal.timeInMillis >= rangeStart) {
                results.add(cal.timeInMillis)
            }
            cal.add(Calendar.DAY_OF_YEAR, interval)
            count++
        }
    }
    
    /** Weekly: каждые N недель по указанным дням (bitmask) */
    private fun generateWeekly(
        cal: Calendar, interval: Int, dayOfWeekMask: Int,
        rangeStart: Long, rangeEnd: Long, maxCount: Int,
        results: MutableList<Long>
    ) {
        // Если маска пуста — берём день недели из начального события
        val mask = if (dayOfWeekMask == 0) {
            1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1) // Calendar.SUNDAY=1 → bit 0
        } else {
            dayOfWeekMask
        }
        
        // Сохраняем время дня из исходного события
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        
        // Переходим к началу недели серии
        val startOfWeek = cal.clone() as Calendar
        startOfWeek.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        
        // Fast-forward: пропускаем недели до rangeStart
        var skippedOccurrences = 0
        if (startOfWeek.timeInMillis < rangeStart && interval > 0) {
            val diffWeeks = (rangeStart - startOfWeek.timeInMillis) / (7L * 24 * 60 * 60 * 1000)
            val skipIntervals = (diffWeeks / interval).toInt()
            if (skipIntervals > 1) {
                startOfWeek.add(Calendar.WEEK_OF_YEAR, (skipIntervals - 1) * interval)
                // Количество дней в неделе по маске (для корректного ограничения при occurrences > 0)
                val daysPerWeek = (0..6).count { mask and (1 shl it) != 0 }
                skippedOccurrences = (skipIntervals - 1) * daysPerWeek
            }
        }
        
        var count = skippedOccurrences
        var safetyCounter = 0
        
        while (startOfWeek.timeInMillis <= rangeEnd && count < maxCount && safetyCounter < MAX_OCCURRENCES) {
            for (dayBit in 0..6) {
                if (mask and (1 shl dayBit) != 0) {
                    val dayCal = startOfWeek.clone() as Calendar
                    dayCal.set(Calendar.DAY_OF_WEEK, dayBit + 1) // Calendar.SUNDAY = 1
                    dayCal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    dayCal.set(Calendar.MINUTE, minute)
                    dayCal.set(Calendar.SECOND, second)
                    
                    val time = dayCal.timeInMillis
                    if (time >= rangeStart && time <= rangeEnd && count < maxCount) {
                        results.add(time)
                        count++
                    }
                }
            }
            
            startOfWeek.add(Calendar.WEEK_OF_YEAR, interval)
            safetyCounter++
        }
    }
    
    /** Monthly absolute: каждые N месяцев в день dayOfMonth */
    private fun generateMonthlyAbsolute(
        cal: Calendar, interval: Int, dayOfMonth: Int,
        rangeStart: Long, rangeEnd: Long, maxCount: Int,
        results: MutableList<Long>
    ) {
        val targetDay = if (dayOfMonth > 0) dayOfMonth else cal.get(Calendar.DAY_OF_MONTH)
        
        // Fast-forward: пропускаем месяцы до rangeStart
        var skippedCount = 0
        if (cal.timeInMillis < rangeStart && interval > 0) {
            val diffMonths = ((rangeStart - cal.timeInMillis) / (30L * 24 * 60 * 60 * 1000)).toInt()
            val skipIntervals = diffMonths / interval
            if (skipIntervals > 1) {
                cal.add(Calendar.MONTH, (skipIntervals - 1) * interval)
                skippedCount = skipIntervals - 1
            }
        }
        
        // count учитывает пропущенные occurrence для корректного ограничения при occurrences > 0
        var count = skippedCount
        while (cal.timeInMillis <= rangeEnd && count < maxCount) {
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, minOf(targetDay, maxDay))
            
            if (cal.timeInMillis >= rangeStart && cal.timeInMillis <= rangeEnd) {
                results.add(cal.timeInMillis)
            }
            cal.add(Calendar.MONTH, interval)
            count++
        }
    }
    
    /** Monthly relative: каждые N месяцев в weekOfMonth-й dayOfWeek */
    private fun generateMonthlyRelative(
        cal: Calendar, interval: Int, dayOfWeekMask: Int, weekOfMonth: Int,
        rangeStart: Long, rangeEnd: Long, maxCount: Int,
        results: MutableList<Long>
    ) {
        val targetDayOfWeek = bitmaskToCalendarDay(dayOfWeekMask)
        
        var count = 0
        while (cal.timeInMillis <= rangeEnd && count < maxCount) {
            val resolved = resolveRelativeDay(cal, targetDayOfWeek, weekOfMonth)
            if (resolved != null && resolved >= rangeStart && resolved <= rangeEnd) {
                results.add(resolved)
            }
            cal.add(Calendar.MONTH, interval)
            count++
        }
    }
    
    /** Yearly absolute: каждый год в monthOfYear/dayOfMonth */
    private fun generateYearlyAbsolute(
        cal: Calendar, dayOfMonth: Int, monthOfYear: Int,
        rangeStart: Long, rangeEnd: Long, maxCount: Int,
        results: MutableList<Long>
    ) {
        val targetMonth = if (monthOfYear > 0) monthOfYear - 1 else cal.get(Calendar.MONTH) // Calendar.JANUARY = 0
        val targetDay = if (dayOfMonth > 0) dayOfMonth else cal.get(Calendar.DAY_OF_MONTH)
        
        var count = 0
        while (cal.timeInMillis <= rangeEnd && count < maxCount) {
            cal.set(Calendar.MONTH, targetMonth)
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(Calendar.DAY_OF_MONTH, minOf(targetDay, maxDay))
            
            if (cal.timeInMillis >= rangeStart && cal.timeInMillis <= rangeEnd) {
                results.add(cal.timeInMillis)
            }
            cal.add(Calendar.YEAR, 1)
            count++
        }
    }
    
    /** Yearly relative: каждый год в monthOfYear, weekOfMonth-й dayOfWeek */
    private fun generateYearlyRelative(
        cal: Calendar, dayOfWeekMask: Int, weekOfMonth: Int, monthOfYear: Int,
        rangeStart: Long, rangeEnd: Long, maxCount: Int,
        results: MutableList<Long>
    ) {
        val targetDayOfWeek = bitmaskToCalendarDay(dayOfWeekMask)
        val targetMonth = if (monthOfYear > 0) monthOfYear - 1 else cal.get(Calendar.MONTH)
        
        var count = 0
        while (cal.timeInMillis <= rangeEnd && count < maxCount) {
            cal.set(Calendar.MONTH, targetMonth)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            
            val resolved = resolveRelativeDay(cal, targetDayOfWeek, weekOfMonth)
            if (resolved != null && resolved >= rangeStart && resolved <= rangeEnd) {
                results.add(resolved)
            }
            cal.add(Calendar.YEAR, 1)
            count++
        }
    }
    
    /**
     * Вычисляет дату weekOfMonth-го дня недели в месяце из cal
     * Сохраняет время дня из cal
     */
    private fun resolveRelativeDay(cal: Calendar, dayOfWeek: Int, weekOfMonth: Int): Long? {
        val tempCal = cal.clone() as Calendar
        val hourOfDay = tempCal.get(Calendar.HOUR_OF_DAY)
        val minute = tempCal.get(Calendar.MINUTE)
        val second = tempCal.get(Calendar.SECOND)
        
        if (weekOfMonth == 5) {
            // Last — находим последний день недели в месяце
            tempCal.set(Calendar.DAY_OF_MONTH, tempCal.getActualMaximum(Calendar.DAY_OF_MONTH))
            while (tempCal.get(Calendar.DAY_OF_WEEK) != dayOfWeek) {
                tempCal.add(Calendar.DAY_OF_MONTH, -1)
            }
        } else {
            // First/Second/Third/Fourth
            tempCal.set(Calendar.DAY_OF_MONTH, 1)
            while (tempCal.get(Calendar.DAY_OF_WEEK) != dayOfWeek) {
                tempCal.add(Calendar.DAY_OF_MONTH, 1)
            }
            // Сдвигаемся на нужную неделю
            tempCal.add(Calendar.WEEK_OF_YEAR, weekOfMonth - 1)
            
            // Проверяем, что не вышли за месяц
            if (tempCal.get(Calendar.MONTH) != cal.get(Calendar.MONTH)) return null
        }
        
        tempCal.set(Calendar.HOUR_OF_DAY, hourOfDay)
        tempCal.set(Calendar.MINUTE, minute)
        tempCal.set(Calendar.SECOND, second)
        
        return tempCal.timeInMillis
    }
    
    /**
     * Конвертирует bitmask дня недели (Sun=1, Mon=2, Tue=4, ...) в Calendar.DAY_OF_WEEK
     * Берёт первый установленный бит
     */
    private fun bitmaskToCalendarDay(mask: Int): Int {
        for (bit in 0..6) {
            if (mask and (1 shl bit) != 0) {
                return bit + 1 // Calendar.SUNDAY = 1, Calendar.MONDAY = 2, ...
            }
        }
        return Calendar.MONDAY // fallback
    }
    
    /**
     * Формирует recurrenceRule JSON из типа повторения и startTime.
     * Используется при создании/обновлении события из UI.
     * @param type Тип повторения: 0=Daily, 1=Weekly, 2=Monthly, 5=Yearly
     * @param startTime Время начала события (для определения дня недели/месяца)
     */
    fun buildRuleJson(type: Int, startTime: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startTime
        
        val interval = 1
        val dayOfWeek = when (type) {
            1 -> 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
            else -> 0
        }
        val dayOfMonth = when (type) {
            2 -> cal.get(Calendar.DAY_OF_MONTH)
            5 -> cal.get(Calendar.DAY_OF_MONTH)
            else -> 0
        }
        val monthOfYear = when (type) {
            5 -> cal.get(Calendar.MONTH) + 1
            else -> 0
        }
        
        return """{"type":$type,"interval":$interval,"dayOfWeek":$dayOfWeek,"dayOfMonth":$dayOfMonth,"weekOfMonth":0,"monthOfYear":$monthOfYear,"until":0,"occurrences":0,"firstDayOfWeek":0}"""
    }
    
    /**
     * Формирует человекочитаемое описание правила повторения
     * @param isRussian Язык интерфейса
     */
    fun describeRule(json: String, isRussian: Boolean): String {
        val rule = parseRule(json) ?: return ""
        
        return when (rule.type) {
            0 -> {
                if (rule.interval == 1) {
                    if (isRussian) "Каждый день" else "Every day"
                } else {
                    if (isRussian) "Каждые ${rule.interval} дн." else "Every ${rule.interval} days"
                }
            }
            1 -> {
                val days = bitmaskToDayNames(rule.dayOfWeek, isRussian)
                if (rule.interval == 1) {
                    if (isRussian) "Каждую неделю ($days)" else "Every week ($days)"
                } else {
                    if (isRussian) "Каждые ${rule.interval} нед. ($days)" else "Every ${rule.interval} weeks ($days)"
                }
            }
            2 -> {
                val day = rule.dayOfMonth
                if (rule.interval == 1) {
                    if (isRussian) "Каждый месяц, $day-го" else "Monthly, on the ${day}th"
                } else {
                    if (isRussian) "Каждые ${rule.interval} мес., $day-го" else "Every ${rule.interval} months, on the ${day}th"
                }
            }
            3 -> {
                val weekName = weekOfMonthName(rule.weekOfMonth, isRussian)
                val dayName = bitmaskToDayNames(rule.dayOfWeek, isRussian)
                if (isRussian) "Каждый месяц, $weekName $dayName" else "Monthly, $weekName $dayName"
            }
            5 -> {
                val monthName = monthName(rule.monthOfYear, isRussian)
                if (isRussian) "Каждый год, ${rule.dayOfMonth} $monthName" else "Yearly, ${monthName} ${rule.dayOfMonth}"
            }
            6 -> {
                val weekName = weekOfMonthName(rule.weekOfMonth, isRussian)
                val dayName = bitmaskToDayNames(rule.dayOfWeek, isRussian)
                val monthName = monthName(rule.monthOfYear, isRussian)
                if (isRussian) "Каждый год, $weekName $dayName $monthName" else "Yearly, $weekName $dayName of $monthName"
            }
            else -> ""
        }
    }
    
    private fun bitmaskToDayNames(mask: Int, isRussian: Boolean): String {
        val ruDays = arrayOf("Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб")
        val enDays = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val days = if (isRussian) ruDays else enDays
        
        return (0..6).filter { mask and (1 shl it) != 0 }.joinToString(", ") { days[it] }
    }
    
    private fun weekOfMonthName(week: Int, isRussian: Boolean): String {
        if (isRussian) return when (week) {
            1 -> "первый"; 2 -> "второй"; 3 -> "третий"; 4 -> "четвёртый"; 5 -> "последний"; else -> ""
        }
        return when (week) {
            1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "last"; else -> ""
        }
    }
    
    private fun monthName(month: Int, isRussian: Boolean): String {
        if (isRussian) return when (month) {
            1 -> "января"; 2 -> "февраля"; 3 -> "марта"; 4 -> "апреля"
            5 -> "мая"; 6 -> "июня"; 7 -> "июля"; 8 -> "августа"
            9 -> "сентября"; 10 -> "октября"; 11 -> "ноября"; 12 -> "декабря"; else -> ""
        }
        return when (month) {
            1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
            5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
            9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"; else -> ""
        }
    }
}

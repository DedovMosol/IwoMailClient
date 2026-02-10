package com.dedovmosol.iwomail.util

import java.util.Calendar
import java.util.Date

/**
 * Утилиты для работы с датами
 */
object DateUtils {
    
    /**
     * Возвращает timestamp начала дня (00:00:00.000)
     */
    fun getStartOfDay(date: Date = Date()): Long {
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    
    /**
     * Возвращает timestamp конца дня (23:59:59.999)
     */
    fun getEndOfDay(date: Date = Date()): Long {
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
    
    /**
     * Возвращает пару (startOfDay, endOfDay) для указанной даты
     */
    fun getDayRange(date: Date = Date()): Pair<Long, Long> {
        val calendar = Calendar.getInstance().apply { time = date }
        
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val end = calendar.timeInMillis
        
        return start to end
    }
}

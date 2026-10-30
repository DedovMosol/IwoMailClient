package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.util.RemoteImages

/**
 * Тихие часы: окно в минутах от полуночи по местному времени; может переходить через полночь (22:00–07:00).
 * Начало равно концу — окно пустое: так почту нельзя заглушить на сутки по ошибке.
 */
data class QuietHours(val enabled: Boolean, val startMinutes: Int, val endMinutes: Int) {

    fun isActive(minuteOfDay: Int): Boolean {
        if (!enabled || startMinutes == endMinutes) return false
        return if (startMinutes < endMinutes) {
            minuteOfDay in startMinutes until endMinutes
        } else {
            minuteOfDay >= startMinutes || minuteOfDay < endMinutes
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_START = 22 * 60
        const val DEFAULT_END = 7 * 60

        /** Минута суток [timeMillis] по местному времени (с переходом на летнее время). */
        fun minuteOfDay(timeMillis: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
            val calendar = java.util.Calendar.getInstance(zone).apply { this.timeInMillis = timeMillis }
            return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        }

        /** Сохранённое значение — минута суток, иначе [default]. */
        fun sanitize(minutes: Int?, default: Int): Int = minutes?.takeIf { it in 0 until MINUTES_PER_DAY } ?: default
    }
}

/**
 * Как уведомить о новых письмах аккаунта.
 * - [vip] — среди них письмо важного отправителя: канал «Важные отправители», звук и в тихие часы.
 * - [silent] — без звука и вибрации (тихие часы, важных писем нет); уведомление всё равно появляется в шторке.
 */
data class MailAlert(val vip: Boolean, val silent: Boolean) {
    companion object {
        fun of(senders: List<String>, vipSenders: Set<String>, quiet: QuietHours, minuteOfDay: Int): MailAlert {
            val vip = vipSenders.isNotEmpty() && senders.any { RemoteImages.senderKey(it) in vipSenders }
            return MailAlert(vip = vip, silent = !vip && quiet.isActive(minuteOfDay))
        }
    }
}

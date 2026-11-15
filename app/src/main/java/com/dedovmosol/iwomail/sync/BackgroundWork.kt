package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Разрешения, без которых новые письма приходят с опозданием: работа в фоне (исключение из оптимизации батареи — иначе
 * Doze откладывает Push и синхронизацию) и точные будильники (напоминания, отложенная отправка в срок).
 */
object BackgroundWork {

    /** Приложение исключено из оптимизации батареи. */
    fun isAllowed(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Точные будильники разрешены (до Android 12 — всегда). */
    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /**
     * RTC_WAKEUP: точный alarm, если система даёт, иначе обычный.
     * OEM-квота `setExact` бросает [SecurityException] даже после [canScheduleExactAlarms] —
     * тогда тоже неточный [AlarmManager.set], чтобы воркер не считал весь проход успешным вхолостую.
     */
    fun scheduleRtcWakeup(alarmManager: AlarmManager, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "Exact alarm denied, inexact fallback: ${e.message}")
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (e2: SecurityException) {
                android.util.Log.w(TAG, "while-idle denied, using set(): ${e2.message}")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
    }

    private const val TAG = "BackgroundWork"
}

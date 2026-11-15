package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
     * Сколько ближайших напоминаний держим в AlarmManager (календарь, задачи, флаги — по столько).
     * Тысячи будущих строк в БД не ставим: OEM-квота ~500 на UID, exact будит устройство.
     * После срабатывания [RescheduleRemindersWorker] подтягивает следующие.
     */
    const val MAX_REMINDER_ALARMS = 50

    /**
     * requestCode / notificationId. Без % 1_000_000 — при тысячах id модуль давал коллизии.
     * [kind] обязан быть разным у календаря / задач / флагов: NotificationManager общий на приложение,
     * разные Receiver спасают только PendingIntent, не id уведомления.
     * [slot] — alarm / content / snooze одного id.
     */
    fun pendingCode(kind: Int, id: String, slot: Int = 0): Int = id.hashCode() xor kind xor slot

    /** requestCode до смены схемы (hash % 1_000_000) — снять, иначе дубль до первого срабатывания. */
    fun legacyAlarmCode(id: String): Int = (id.hashCode() and 0x7FFFFFFF) % 1_000_000

    fun cancelBroadcast(context: Context, requestCode: Int, intent: Intent) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
    }

    const val KIND_CALENDAR = 0x51C0C0
    const val KIND_TASK = 0x7A5A00
    const val KIND_FLAG = 0x41A600

    /** @return true, если поставлен точный alarm. [SecurityException] не пробрасывается: один отказ не рвёт цикл остальных напоминаний. */
    fun scheduleRtcWakeup(alarmManager: AlarmManager, triggerAtMillis: Long, pendingIntent: PendingIntent): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                return false
            }
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            return true
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "Exact alarm denied, inexact fallback: ${e.message}")
            return try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                false
            } catch (e2: SecurityException) {
                android.util.Log.w(TAG, "while-idle denied, using set(): ${e2.message}")
                try {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    false
                } catch (e3: SecurityException) {
                    android.util.Log.e(TAG, "All alarm APIs denied", e3)
                    false
                }
            }
        }
    }

    private const val TAG = "BackgroundWork"
}

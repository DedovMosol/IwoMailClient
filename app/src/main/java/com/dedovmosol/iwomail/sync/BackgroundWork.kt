package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
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
}

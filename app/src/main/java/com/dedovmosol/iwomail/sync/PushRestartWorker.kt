package com.dedovmosol.iwomail.sync

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Лёгкий Worker для перезапуска PushService после его убийства системой.
 * Используется как fallback-стратегия рестарта на Android 12+ (API 31),
 * где startForegroundService() из фона запрещён.
 *
 * WorkManager гарантирует запуск даже на агрессивных OEM (Xiaomi, Huawei).
 */
class PushRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            PushService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // На Android 12+ это тоже может не сработать если нет foreground exemption.
            // В таком случае AlarmManager-стратегия должна была сработать раньше.
            android.util.Log.w("PushRestartWorker", "Failed to restart PushService", e)
            Result.failure()
        }
    }
}

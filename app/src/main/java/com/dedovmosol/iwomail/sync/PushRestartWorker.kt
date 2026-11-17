package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.SyncMode

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
        // BG-01: перезапуск устарел — PUSH-аккаунтов не осталось или сервис остановлен явно
        val prefs = applicationContext.getSharedPreferences("push_service", Context.MODE_PRIVATE)
        if (prefs.getBoolean("explicit_stop", false)) return Result.success()
        val hasPushAccounts = MailDatabase.getInstance(applicationContext).accountDao().getAllAccountsList().any {
            it.accountType == AccountType.EXCHANGE.name && it.syncMode == SyncMode.PUSH.name
        }
        if (!hasPushAccounts) return Result.success()

        return try {
            PushService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // BG-03: из фона без исключения старт FGS запрещён — повторы бесполезны (раньше retry() был бесконечным).
            // Будильник того же requestRestart (точный — разрешённое исключение) и периодический SyncWorker остаются.
            android.util.Log.w("PushRestartWorker", "Failed to restart PushService", e)
            Result.failure()
        }
    }
}

package com.dedovmosol.iwomail.sync

import android.content.Context

/**
 * SYNC-3: единый circuit breaker автоматических перезапусков [PushService].
 *
 * Проблема: на устройствах, где фоновый старт FGS запрещён (Android 12+), или при исчерпании
 * суточного лимита dataSync (SYNC-1), цепочка onDestroy → scheduleRestart → requestRestart →
 * alarm → start → startForeground FAIL → stopSelf → onDestroy … крутится ~каждые 60 c, разряжая
 * батарею и засоряя логи. Общего ограничителя частоты/числа попыток не было.
 *
 * Решение: все АВТОМАТИЧЕСКИЕ перезапуски идут через единую точку [PushService.requestRestart],
 * которая спрашивает этот breaker. Экспоненциальный backoff между попытками, и после N попыток
 * за окно цепь РАЗМЫКАЕТСЯ — восстановление отдаётся периодическому SyncWorker (безопасный медленный
 * ретрай). Успешный выход сервиса в foreground замыкает цепь ([onForegroundStarted]).
 *
 * Состояние — в SharedPreferences: каждый авто-рестарт = НОВЫЙ процесс, in-memory счётчик не выжил бы.
 * Намеренно НЕ ограничивает пользовательские/системные старты (app start, boot, добавление
 * аккаунта, тумблер push) — они идут через start() напрямую, минуя breaker.
 *
 * Решение о задержке вынесено в чистую [decideRestart] — покрыто PushRestartPolicyTest.
 */
internal object PushRestartPolicy {
    private const val PREFS = "push_service"
    private const val KEY_COUNT = "restart_attempts"
    private const val KEY_WINDOW_START = "restart_window_start"

    const val WINDOW_MS = 10 * 60 * 1000L   // окно учёта попыток — 10 минут
    const val MAX_ATTEMPTS = 5              // > 5 попыток за окно → разомкнуть цепь
    const val BASE_BACKOFF_SEC = 30L        // базовая задержка backoff
    const val MAX_BACKOFF_SEC = 15 * 60L    // потолок задержки — 15 минут

    /**
     * Регистрирует попытку авто-перезапуска и возвращает эффективную задержку (сек) с
     * экспоненциальным backoff, либо null — если цепь разомкнута (попыток слишком много).
     */
    fun nextRestartDelaySec(context: Context, requestedDelaySec: Long): Long? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val decision = decideRestart(
            now = System.currentTimeMillis(),
            prevWindowStart = prefs.getLong(KEY_WINDOW_START, 0L),
            prevCount = prefs.getInt(KEY_COUNT, 0),
            requestedDelaySec = requestedDelaySec,
            windowMs = WINDOW_MS,
            maxAttempts = MAX_ATTEMPTS,
            baseBackoffSec = BASE_BACKOFF_SEC,
            maxBackoffSec = MAX_BACKOFF_SEC
        )
        prefs.edit()
            .putLong(KEY_WINDOW_START, decision.windowStart)
            .putInt(KEY_COUNT, decision.count)
            .apply()
        return decision.delaySec
    }

    /** Успешный выход в foreground — цепь замыкается (счётчик попыток сбрасывается). */
    fun onForegroundStarted(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_COUNT).remove(KEY_WINDOW_START).apply()
    }
}

/** Результат решения о перезапуске: обновлённое окно/счётчик + задержка (null = цепь разомкнута). */
internal data class RestartDecision(val windowStart: Long, val count: Int, val delaySec: Long?)

/**
 * SYNC-3: чистая логика circuit breaker. Если окно истекло — счётчик обнуляется. Попытка
 * инкрементирует счётчик; при превышении [maxAttempts] возвращается delaySec=null (цепь разомкнута),
 * иначе — max(запрошенная задержка, экспоненциальный backoff, ограниченный [maxBackoffSec]).
 */
internal fun decideRestart(
    now: Long,
    prevWindowStart: Long,
    prevCount: Int,
    requestedDelaySec: Long,
    windowMs: Long,
    maxAttempts: Int,
    baseBackoffSec: Long,
    maxBackoffSec: Long
): RestartDecision {
    val windowExpired = now - prevWindowStart > windowMs
    val windowStart = if (windowExpired) now else prevWindowStart
    val count = (if (windowExpired) 0 else prevCount) + 1
    if (count > maxAttempts) return RestartDecision(windowStart, count, null)
    val backoff = (baseBackoffSec shl (count - 1)).coerceAtMost(maxBackoffSec)
    return RestartDecision(windowStart, count, maxOf(requestedDelaySec, backoff))
}

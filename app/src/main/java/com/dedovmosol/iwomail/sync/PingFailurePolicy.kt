package com.dedovmosol.iwomail.sync

import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.network.ConnectionFailure

/**
 * Реакция Direct Push (цикл Ping) на сбои подряд — чистая логика без Android (SoC, тестируется).
 *
 * - Обрыв связи: пауза 10 с, 30 с, затем 1, 2, 4, 8 и не больше 15 мин; каждые
 *   [SWITCH_EVERY_N_FAILURES] сбоя — переключение на другой сервер, если он настроен.
 *   «Ping не поддерживается» при обрыве связи НЕ ставится: кратковременный простой сервера не
 *   должен выключать Direct Push на сутки (раньше так и было после 3 сбоев без резервного).
 * - Авторизация и TLS/сертификат детерминированы: сразу максимальная пауза. Частые повторы с
 *   неверным паролем блокируют учётную запись в AD (политика блокировки после N неудачных входов).
 * - Сервер отвечает, но Ping отвергает (HTTP 4xx/501, Status 8 и т.п.) [SWITCH_EVERY_N_FAILURES]
 *   раза подряд — Ping считается неподдерживаемым (24 ч), почта идёт периодической синхронизацией.
 * - Heartbeat уменьшается только при таймауте или обрыве соединения (NAT/прокси рвёт долгий запрос).
 */
internal object PingFailurePolicy {
    const val SWITCH_EVERY_N_FAILURES = 3
    const val MAX_BACKOFF_MS = 15 * 60_000L
    private const val FIRST_BACKOFF_MS = 10_000L
    private const val SECOND_BACKOFF_MS = 30_000L
    private const val BASE_BACKOFF_MS = 60_000L

    data class Decision(
        val backoffMs: Long,
        val shrinkHeartbeat: Boolean,
        val trySwitchEndpoint: Boolean,
        val markPingUnsupported: Boolean
    )

    /** @param consecutiveErrors сбоев подряд, включая текущий (от 1). */
    fun decide(consecutiveErrors: Int, kind: ErrorKind, failure: ConnectionFailure?, hasAlternate: Boolean): Decision {
        val deterministic = kind == ErrorKind.Auth || kind == ErrorKind.Cert
        val connectionLevel = !deterministic && (failure?.connectionLevel
            ?: (kind == ErrorKind.Network || kind == ErrorKind.Timeout || kind == ErrorKind.Server5xx))
        val backoffMs = when {
            deterministic -> MAX_BACKOFF_MS
            consecutiveErrors <= 1 -> FIRST_BACKOFF_MS
            consecutiveErrors == 2 -> SECOND_BACKOFF_MS
            else -> (BASE_BACKOFF_MS shl (consecutiveErrors - 3).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)
        }
        return Decision(
            backoffMs = backoffMs,
            shrinkHeartbeat = failure == ConnectionFailure.TIMEOUT || failure == ConnectionFailure.RESET,
            trySwitchEndpoint = connectionLevel && hasAlternate && consecutiveErrors % SWITCH_EVERY_N_FAILURES == 0,
            markPingUnsupported = !connectionLevel && !deterministic && consecutiveErrors >= SWITCH_EVERY_N_FAILURES
        )
    }
}

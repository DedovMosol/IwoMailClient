package com.dedovmosol.iwomail.sync

import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.network.ConnectionFailure

/**
 * Реакция Direct Push (цикл Ping) на сбои подряд — чистая логика без Android (SoC, тестируется).
 *
 * - Сервер недостижим (DNS, отказ, нет маршрута, таймаут подключения): пауза 10 с, 30 с, затем 1, 2, 4,
 *   8 и не больше 15 мин; каждые [SWITCH_EVERY_N_FAILURES] сбоя — переключение на другой сервер, если
 *   он настроен. Ping при этом НЕ выключается: простой сервера не должен отключать Direct Push на сутки.
 * - Сервер отвечает, но Ping не проходит (HTTP 5xx, 4xx/501, Status 8, обрыв уже установленного
 *   соединения — так прокси рвут долгие запросы): после [SWITCH_EVERY_N_FAILURES] сбоев подряд (и
 *   попытки другого сервера, если он есть) Ping считается неподдерживаемым на 24 ч — почта идёт
 *   периодической синхронизацией. Без этого у Push-аккаунта не было бы синхронизации вовсе
 *   (периодическая для Push-аккаунтов не планируется).
 * - Авторизация и TLS/сертификат детерминированы: сразу максимальная пауза. Частые повторы с неверным
 *   паролем блокируют учётную запись в AD (политика блокировки после N неудачных входов).
 * - Heartbeat уменьшается только при таймауте или обрыве соединения (NAT/прокси рвёт долгий запрос).
 */
internal object PingFailurePolicy {
    const val SWITCH_EVERY_N_FAILURES = 3
    const val MAX_BACKOFF_MS = 15 * 60_000L
    private const val FIRST_BACKOFF_MS = 10_000L
    private const val SECOND_BACKOFF_MS = 30_000L
    private const val BASE_BACKOFF_MS = 60_000L

    /** Соединение с сервером не установлено вовсе — сервер выключен или недоступен из этой сети. */
    private val UNREACHABLE = setOf(
        ConnectionFailure.DNS, ConnectionFailure.REFUSED, ConnectionFailure.UNREACHABLE, ConnectionFailure.TIMEOUT
    )

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
        val unreachable = failure in UNREACHABLE
        val canSwitch = connectionLevel && hasAlternate
        val backoffMs = when {
            deterministic -> MAX_BACKOFF_MS
            consecutiveErrors <= 1 -> FIRST_BACKOFF_MS
            consecutiveErrors == 2 -> SECOND_BACKOFF_MS
            else -> (BASE_BACKOFF_MS shl (consecutiveErrors - 3).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)
        }
        return Decision(
            backoffMs = backoffMs,
            shrinkHeartbeat = failure == ConnectionFailure.TIMEOUT || failure == ConnectionFailure.RESET,
            trySwitchEndpoint = canSwitch && consecutiveErrors % SWITCH_EVERY_N_FAILURES == 0,
            // Другой сервер сначала получает свой шанс (ещё один цикл), потом — периодическая синхронизация.
            markPingUnsupported = !deterministic && !unreachable &&
                consecutiveErrors >= SWITCH_EVERY_N_FAILURES * (if (canSwitch) 2 else 1)
        )
    }
}

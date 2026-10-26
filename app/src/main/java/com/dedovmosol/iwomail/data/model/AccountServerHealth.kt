package com.dedovmosol.iwomail.data.model

import com.dedovmosol.iwomail.network.ConnectionFailure

enum class ServerProblemType {
    None,
    PrimaryDownNoFallback,
    PrimaryDownUsingFallback,
    BothServersDown,
    AuthError,
    CertError,
    ServerError,
    Timeout
}

enum class ErrorKind {
    Network,
    Timeout,
    Auth,
    Cert,
    Server5xx,
    Unknown;

    val isImmediate: Boolean
        get() = this == Auth || this == Cert
}

data class AccountServerHealth(
    val accountId: Long,
    val problemType: ServerProblemType = ServerProblemType.None,
    val primaryConsecutiveFailures: Int = 0,
    val alternateConsecutiveFailures: Int = 0,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastErrorMessage: String? = null,
    val lastErrorKind: ErrorKind? = null,
    val isUsingFallback: Boolean = false,
    val serverDisplayName: String? = null
) {
    val isCritical: Boolean
        get() = problemType in CRITICAL_TYPES

    val showBanner: Boolean
        get() = problemType != ServerProblemType.None
            && problemType != ServerProblemType.PrimaryDownUsingFallback

    val isStaleData: Boolean
        get() = lastSuccessAt > 0L
            && System.currentTimeMillis() - lastSuccessAt > STALE_THRESHOLD_MS

    companion object {
        private const val STALE_THRESHOLD_MS = 15 * 60 * 1000L

        private val CRITICAL_TYPES = setOf(
            ServerProblemType.PrimaryDownNoFallback,
            ServerProblemType.BothServersDown,
            ServerProblemType.AuthError,
            ServerProblemType.CertError
        )
    }
}

private val HTTP_5XX_REGEX = Regex("\\bHTTP\\s+5\\d{2}\\b", RegexOption.IGNORE_CASE)

/** Коды 401/403 отдельным числом: «(port 40135)» в тексте таймаута Android — не ошибка авторизации. */
private val HTTP_AUTH_CODE_REGEX = Regex("\\b40[13]\\b")

/**
 * Стоит ли пробовать резервный сервер: основной недостижим, соединение оборвалось или сервер
 * перегружен (5xx). Авторизация, политика, TLS/сертификат и ответы EAS — нет: у резервного те же
 * учётные данные и тот же ящик, а сертификат основного обходить молча нельзя.
 */
fun isConnectionLevelError(errorMessage: String?): Boolean {
    if (errorMessage == null) return false
    ConnectionFailure.fromMessage(errorMessage)?.let { return it.connectionLevel }
    return when (classifyError(errorMessage, null)) {
        ErrorKind.Network, ErrorKind.Timeout, ErrorKind.Server5xx -> true
        else -> false
    }
}

/**
 * Итог проверки основного и резервного адреса (настройка аккаунта): успех резервного; его ошибка,
 * если резервный ответил (например, неверный пароль — именно это и надо исправлять); иначе ошибка
 * основного (недоступны оба — показываем причину основного).
 */
fun <T> preferredDualUrlResult(
    primary: com.dedovmosol.iwomail.eas.EasResult<T>,
    alternate: com.dedovmosol.iwomail.eas.EasResult<T>
): com.dedovmosol.iwomail.eas.EasResult<T> = when {
    alternate is com.dedovmosol.iwomail.eas.EasResult.Success -> alternate
    alternate is com.dedovmosol.iwomail.eas.EasResult.Error && !isConnectionLevelError(alternate.message) -> alternate
    else -> primary
}

/** Сетевая причина → категория health: TLS и сертификат — Cert (детерминированно, без порога). */
internal fun ConnectionFailure.toErrorKind(): ErrorKind = when (this) {
    ConnectionFailure.TIMEOUT -> ErrorKind.Timeout
    ConnectionFailure.DNS, ConnectionFailure.REFUSED, ConnectionFailure.UNREACHABLE,
    ConnectionFailure.RESET, ConnectionFailure.IO -> ErrorKind.Network
    else -> ErrorKind.Cert
}

fun classifyError(errorMessage: String?, exception: Throwable?): ErrorKind {
    if (exception != null) {
        return ConnectionFailure.classify(exception)?.toErrorKind() ?: ErrorKind.Unknown
    }
    if (errorMessage != null) {
        // Метка транспорта точнее любого разбора текста (сообщение исключения может содержать что угодно).
        ConnectionFailure.fromMessage(errorMessage)?.let { return it.toErrorKind() }
        val msg = errorMessage.lowercase()
        if (HTTP_AUTH_CODE_REGEX.containsMatchIn(msg) || "unauthorized" in msg || "forbidden" in msg
            || "access_denied" in msg || "provision" in msg
        ) {
            return ErrorKind.Auth
        }
        if ("certificate" in msg || "pinning" in msg || "pin mismatch" in msg) {
            return ErrorKind.Cert
        }
        if (HTTP_5XX_REGEX.containsMatchIn(errorMessage)) {
            return ErrorKind.Server5xx
        }
        if ("timeout" in msg || "timed out" in msg) {
            return ErrorKind.Timeout
        }
        if ("unreachable" in msg || "refused" in msg || "reset" in msg
            || "unknownhost" in msg || "no route" in msg
        ) {
            return ErrorKind.Network
        }
    }
    return ErrorKind.Unknown
}

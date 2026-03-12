package com.dedovmosol.iwomail.data.model

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

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

/**
 * Определяет, является ли ошибка сетевой/серверной (стоит пробовать alternate URL)
 * или авторизационной (alternate с теми же credentials бесполезен).
 * Переиспользует classifyError() — DRY.
 */
fun isConnectionLevelError(errorMessage: String?): Boolean {
    if (errorMessage == null) return false
    val kind = classifyError(errorMessage, null)
    return kind != ErrorKind.Auth && kind != ErrorKind.Cert
}

fun classifyError(errorMessage: String?, exception: Throwable?): ErrorKind {
    if (exception != null) {
        return when (exception) {
            is SocketTimeoutException -> ErrorKind.Timeout
            is SSLException -> ErrorKind.Cert
            is UnknownHostException, is ConnectException -> ErrorKind.Network
            is java.io.IOException -> ErrorKind.Network
            else -> ErrorKind.Unknown
        }
    }
    if (errorMessage != null) {
        val msg = errorMessage.lowercase()
        if ("401" in msg || "403" in msg || "unauthorized" in msg || "forbidden" in msg
            || "access_denied" in msg || "provision" in msg
        ) {
            return ErrorKind.Auth
        }
        if ("ssl" in msg || "certificate" in msg || "cert" in msg || "pinning" in msg) {
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

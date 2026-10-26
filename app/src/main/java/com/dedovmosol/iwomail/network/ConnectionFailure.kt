package com.dedovmosol.iwomail.network

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Причина сетевого сбоя — стабильный код вместо разбора текста исключения в каждом слое (SoC, DRY).
 *
 * Транспорт ставит метку [tag] (`[NET:КОД]`) в начало текста EasResult.Error, UI по ней показывает
 * понятное описание на языке интерфейса, а health/Dual-URL решают по [connectionLevel], поможет ли
 * резервный сервер.
 *
 * [connectionLevel]: сервер недостижим или соединение оборвалось — другой адрес может помочь.
 * TLS и сертификат — детерминированная проблема хоста и доверия к нему: повтор не поможет, а молча
 * уйти на другой сервер значит скрыть проблему сертификата основного.
 *
 * [rank]: чем меньше, тем конкретнее причина. OkHttp при повторной попытке (другой ConnectionSpec,
 * другой IP) бросает ПЕРВОЕ исключение, а исход следующих кладёт в `suppressed`
 * (RetryAndFollowUpInterceptor: `firstConnectException.withSuppressed`). Поэтому выбирается самая
 * конкретная причина по всему графу cause/suppressed, а не верхнее исключение.
 */
enum class ConnectionFailure(val connectionLevel: Boolean, internal val rank: Int) {
    CERT_EXPIRED(false, 0),
    CERT_NOT_YET_VALID(false, 0),
    CERT_HOSTNAME(false, 0),
    CERT_PIN(false, 0),
    CERT_UNTRUSTED(false, 1),
    CLIENT_CERT(false, 1),
    TLS_PLAINTEXT(false, 2),
    TLS_HANDSHAKE(false, 2),
    TLS_VERSION(false, 3),
    CLEARTEXT_BLOCKED(false, 3),
    DNS(true, 4),
    REFUSED(true, 4),
    UNREACHABLE(true, 4),
    TIMEOUT(true, 4),
    RESET(true, 5),
    IO(true, 6);

    /** Метка в тексте ошибки: по ней причина восстанавливается без разбора текста исключения. */
    val tag: String get() = "[NET:$name]"

    companion object {
        private val TAG_REGEX = Regex("""\[NET:([A-Z_]+)]""")
        private const val MAX_GRAPH_NODES = 32
        private const val MAX_DETAILS_LENGTH = 700

        /** Причина по метке `[NET:КОД]` в тексте ошибки; null — метки нет. */
        fun fromMessage(message: String?): ConnectionFailure? {
            val code = message?.let { TAG_REGEX.find(it)?.groupValues?.get(1) } ?: return null
            return entries.firstOrNull { it.name == code }
        }

        /** Текст ошибки без метки (для показа технических подробностей). */
        fun stripTag(message: String): String = message.replace(TAG_REGEX, "").replace("  ", " ").trim()

        /** Самая конкретная сетевая причина в графе исключения; null — сбой не сетевой. */
        fun classify(error: Throwable): ConnectionFailure? = mostSpecific(error)?.first

        /**
         * Текст для EasResult.Error: метка причины + цепочка «класс: сообщение» (верхнее исключение,
         * самое конкретное и его первопричина). Технические подробности остаются на английском —
         * это текст исключений; описание на языке интерфейса добавляет UI по метке.
         */
        fun describe(error: Throwable): String {
            val best = mostSpecific(error)
            val chain = LinkedHashSet<Throwable>()
            chain += error
            best?.second?.let { chain += it }
            chain += rootCause(best?.second ?: error)
            val details = chain.joinToString(" | ") { summary(it) }.take(MAX_DETAILS_LENGTH)
            return if (best != null) "${best.first.tag} $details" else details
        }

        private fun summary(t: Throwable): String {
            val message = t.message?.trim().orEmpty()
            return if (message.isEmpty()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
        }

        private fun rootCause(t: Throwable): Throwable {
            var current = t
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            while (seen.add(current)) current = current.cause ?: return current
            return current
        }

        /** Обход cause + suppressed в ширину (с защитой от циклов) с выбором минимального [rank]. */
        private fun mostSpecific(error: Throwable): Pair<ConnectionFailure, Throwable>? {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val queue = ArrayDeque<Throwable>()
            queue += error
            var best: Pair<ConnectionFailure, Throwable>? = null
            while (queue.isNotEmpty() && seen.size < MAX_GRAPH_NODES) {
                val t = queue.removeFirst()
                if (!seen.add(t)) continue
                val kind = classifyOne(t)
                val current = best
                if (kind != null && (current == null || kind.rank < current.first.rank)) best = kind to t
                t.cause?.let { queue += it }
                t.suppressed.forEach { queue += it }
            }
            return best
        }

        private fun classifyOne(t: Throwable): ConnectionFailure? {
            val message = t.message.orEmpty()
            return when (t) {
                is CertificateExpiredException -> CERT_EXPIRED
                is CertificateNotYetValidException -> CERT_NOT_YET_VALID
                is CertificatePinMismatchException -> CERT_PIN
                is CertPathValidatorException -> when (t.reason) {
                    CertPathValidatorException.BasicReason.EXPIRED -> CERT_EXPIRED
                    CertPathValidatorException.BasicReason.NOT_YET_VALID -> CERT_NOT_YET_VALID
                    else -> CERT_UNTRUSTED
                }
                is CertificateException -> CERT_UNTRUSTED
                is SSLPeerUnverifiedException -> if (message.contains("pinning", ignoreCase = true)) CERT_PIN else CERT_HOSTNAME
                is SSLException -> classifyTls(message, isHandshake = t is SSLHandshakeException)
                is UnknownServiceException -> if (message.contains("CLEARTEXT", ignoreCase = true)) CLEARTEXT_BLOCKED else IO
                is UnknownHostException -> DNS
                is NoRouteToHostException, is PortUnreachableException -> UNREACHABLE
                is ConnectException -> when {
                    message.containsAny(UNREACHABLE_MARKERS) -> UNREACHABLE
                    message.containsAny(TIMEOUT_MARKERS) -> TIMEOUT
                    else -> REFUSED
                }
                is SocketTimeoutException -> TIMEOUT
                // OkHttp AsyncTimeout (callTimeout/таймаут записи) бросает InterruptedIOException("timeout").
                is InterruptedIOException -> if (message.equals("timeout", ignoreCase = true)) TIMEOUT else IO
                is SocketException -> if (message.containsAny(UNREACHABLE_MARKERS)) UNREACHABLE else RESET
                is EOFException -> RESET
                is IOException -> if (message.containsAny(RESET_MARKERS)) RESET else IO
                else -> null
            }
        }

        /** Причины BoringSSL (Conscrypt, `OPENSSL_internal:…`) и SunJSSE (JVM) по тексту SSLException. */
        private fun classifyTls(message: String, isHandshake: Boolean): ConnectionFailure? = when {
            message.containsAny(RESET_MARKERS) -> RESET
            message.containsAny(PLAINTEXT_MARKERS) -> TLS_PLAINTEXT
            message.contains("expired", ignoreCase = true) -> CERT_EXPIRED
            message.contains("not yet valid", ignoreCase = true) -> CERT_NOT_YET_VALID
            message.contains("pin mismatch", ignoreCase = true) -> CERT_PIN
            message.containsAny(UNTRUSTED_MARKERS) -> CERT_UNTRUSTED
            message.containsAny(CLIENT_CERT_MARKERS) -> CLIENT_CERT
            message.containsAny(VERSION_MARKERS) -> TLS_VERSION
            message.containsAny(HANDSHAKE_MARKERS) || isHandshake -> TLS_HANDSHAKE
            else -> IO
        }

        private fun String.containsAny(markers: Array<String>): Boolean = markers.any { contains(it, ignoreCase = true) }

        private val UNREACHABLE_MARKERS = arrayOf("ENETUNREACH", "EHOSTUNREACH", "Network is unreachable", "No route to host")
        private val TIMEOUT_MARKERS = arrayOf("ETIMEDOUT", "timed out")
        private val RESET_MARKERS = arrayOf(
            "Connection reset", "reset by peer", "ECONNRESET", "Broken pipe", "EPIPE", "closed by peer",
            "connection abort", "Remote host terminated the handshake", "unexpected end of stream"
        )
        private val PLAINTEXT_MARKERS = arrayOf("WRONG_VERSION_NUMBER", "HTTP_REQUEST", "HTTPS_PROXY_REQUEST", "plaintext connection")
        private val UNTRUSTED_MARKERS = arrayOf("Trust anchor", "PKIX path", "not trusted", "CERTIFICATE_VERIFY_FAILED")
        private val CLIENT_CERT_MARKERS = arrayOf(
            "CERTIFICATE_REQUIRED", "BAD_CERTIFICATE", "CERTIFICATE_UNKNOWN", "UNKNOWN_CA", "ALERT_ACCESS_DENIED",
            "PEER_DID_NOT_RETURN_A_CERTIFICATE", "certificate_required", "bad_certificate"
        )
        private val VERSION_MARKERS = arrayOf(
            "UNSUPPORTED_PROTOCOL", "PROTOCOL_VERSION", "NO_SUPPORTED_VERSIONS_ENABLED", "INAPPROPRIATE_FALLBACK",
            "No appropriate protocol", "protocol_version"
        )
        private val HANDSHAKE_MARKERS = arrayOf(
            "HANDSHAKE_FAILURE", "NO_SHARED_CIPHER", "NO_CIPHERS_AVAILABLE", "INSUFFICIENT_SECURITY", "NO_CIPHER_MATCH",
            "handshake_failure", "insufficient_security"
        )
    }
}

/** Сертификат сервера не совпал с закреплённым (Certificate Pinning) — отдельная причина [ConnectionFailure.CERT_PIN]. */
class CertificatePinMismatchException(message: String) : CertificateException(message)

package com.dedovmosol.iwomail.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Классификация сетевых сбоев по исключениям Android (libcore/OkHttp/BoringSSL) и JVM. */
class ConnectionFailureTest {

    /** Текст со скриншота пользователя (Exchange 2007, только TLS 1.0, bundled Conscrypt). */
    private val screenshotMessage = "Read error: ssl=0xb40000750eda0158: Failure in SSL library, usually a protocol error\n" +
        "error:100000f0:SSL routines:OPENSSL_internal:UNSUPPORTED_PROTOCOL " +
        "(/usr/local/google/home/prb/src/boringssl/ssl/handshake_client.cc:584 0x7446fa2222:0x00000000)"

    private fun classify(t: Throwable) = ConnectionFailure.classify(t)

    private fun handshake(message: String, cause: Throwable? = null) =
        SSLHandshakeException(message).apply { cause?.let { initCause(it) } }

    @Test
    fun `server offering only TLS 1_0 to a TLS 1_2+ attempt is a version failure`() {
        assertThat(classify(handshake(screenshotMessage))).isEqualTo(ConnectionFailure.TLS_VERSION)
        assertThat(classify(handshake("…OPENSSL_internal:TLSV1_ALERT_PROTOCOL_VERSION…"))).isEqualTo(ConnectionFailure.TLS_VERSION)
    }

    @Test
    fun `OkHttp retry outcome in suppressed wins over the first exception`() {
        val first = handshake(screenshotMessage)
        first.addSuppressed(handshake("java.security.cert.CertificateExpiredException", CertificateExpiredException("NotAfter: Thu Feb 29 2024")))

        assertThat(classify(first)).isEqualTo(ConnectionFailure.CERT_EXPIRED)
        val text = ConnectionFailure.describe(first)
        assertThat(text).startsWith("[NET:CERT_EXPIRED] SSLHandshakeException: Read error")
        assertThat(text).contains("CertificateExpiredException: NotAfter: Thu Feb 29 2024")
    }

    @Test
    fun `certificate problems`() {
        assertThat(classify(handshake("x", CertPathValidatorException("Trust anchor for certification path not found."))))
            .isEqualTo(ConnectionFailure.CERT_UNTRUSTED)
        assertThat(classify(handshake("x", CertificateException("Certificate not trusted"))))
            .isEqualTo(ConnectionFailure.CERT_UNTRUSTED)
        val expiredPath = CertPathValidatorException("timestamp check failed", null, null, -1, CertPathValidatorException.BasicReason.EXPIRED)
        assertThat(classify(handshake("x", expiredPath))).isEqualTo(ConnectionFailure.CERT_EXPIRED)
        assertThat(classify(handshake("x", CertificatePinMismatchException("Certificate pin mismatch"))))
            .isEqualTo(ConnectionFailure.CERT_PIN)
        assertThat(classify(SSLPeerUnverifiedException("Hostname 10.0.0.5 not verified:\n    DN: CN=mail.company.local")))
            .isEqualTo(ConnectionFailure.CERT_HOSTNAME)
    }

    @Test
    fun `TLS negotiation problems`() {
        assertThat(classify(handshake("…OPENSSL_internal:WRONG_VERSION_NUMBER…"))).isEqualTo(ConnectionFailure.TLS_PLAINTEXT)
        assertThat(classify(handshake("…OPENSSL_internal:SSLV3_ALERT_HANDSHAKE_FAILURE…"))).isEqualTo(ConnectionFailure.TLS_HANDSHAKE)
        assertThat(classify(handshake("…OPENSSL_internal:TLSV13_ALERT_CERTIFICATE_REQUIRED…"))).isEqualTo(ConnectionFailure.CLIENT_CERT)
        assertThat(classify(handshake("Handshake failed"))).isEqualTo(ConnectionFailure.TLS_HANDSHAKE)
    }

    @Test
    fun `network problems`() {
        assertThat(classify(UnknownHostException("Unable to resolve host \"mail.x\": No address associated with hostname")))
            .isEqualTo(ConnectionFailure.DNS)
        assertThat(classify(ConnectException("failed to connect to mail.x/10.0.0.5 (port 443) after 30000ms: isConnected failed: ECONNREFUSED (Connection refused)")))
            .isEqualTo(ConnectionFailure.REFUSED)
        assertThat(classify(ConnectException("isConnected failed: EHOSTUNREACH (No route to host)"))).isEqualTo(ConnectionFailure.UNREACHABLE)
        assertThat(classify(NoRouteToHostException("No route to host"))).isEqualTo(ConnectionFailure.UNREACHABLE)
        assertThat(classify(SocketTimeoutException("failed to connect to mail.x/10.0.0.5 (port 443) after 30000ms")))
            .isEqualTo(ConnectionFailure.TIMEOUT)
        assertThat(classify(InterruptedIOException("timeout"))).isEqualTo(ConnectionFailure.TIMEOUT)
        assertThat(classify(SocketException("Connection reset"))).isEqualTo(ConnectionFailure.RESET)
        assertThat(classify(EOFException())).isEqualTo(ConnectionFailure.RESET)
        assertThat(classify(IOException("unexpected end of stream on https://mail.x/..."))).isEqualTo(ConnectionFailure.RESET)
        assertThat(classify(UnknownServiceException("CLEARTEXT communication to mail.x not permitted by network security policy")))
            .isEqualTo(ConnectionFailure.CLEARTEXT_BLOCKED)
    }

    @Test
    fun `reset during TLS is a network failure, not a certificate one`() {
        val reset = SSLException("Read error: ssl=0x1: I/O error during system call, Connection reset by peer")
        assertThat(classify(reset)).isEqualTo(ConnectionFailure.RESET)
        assertThat(ConnectionFailure.RESET.connectionLevel).isTrue()
    }

    @Test
    fun `only reachability failures allow switching to the backup server`() {
        val connectionLevel = ConnectionFailure.entries.filter { it.connectionLevel }
        assertThat(connectionLevel).containsExactly(
            ConnectionFailure.DNS, ConnectionFailure.REFUSED, ConnectionFailure.UNREACHABLE,
            ConnectionFailure.TIMEOUT, ConnectionFailure.RESET, ConnectionFailure.IO
        )
    }

    @Test
    fun `non network errors are untagged`() {
        assertThat(classify(IllegalStateException("boom"))).isNull()
        assertThat(ConnectionFailure.describe(IllegalStateException("boom"))).isEqualTo("IllegalStateException: boom")
    }

    @Test
    fun `tag round trip`() {
        val message = "Provision phase 1 failed: [NET:TIMEOUT] SocketTimeoutException: timeout (EAS: 12.1)"
        assertThat(ConnectionFailure.fromMessage(message)).isEqualTo(ConnectionFailure.TIMEOUT)
        assertThat(ConnectionFailure.fromMessage("no tag here")).isNull()
        assertThat(ConnectionFailure.fromMessage("[NET:NOT_A_CODE] x")).isNull()
        assertThat(ConnectionFailure.stripTag(message))
            .isEqualTo("Provision phase 1 failed: SocketTimeoutException: timeout (EAS: 12.1)")
    }

    @Test
    fun `cyclic suppressed graph terminates`() {
        val a = IOException("a")
        val b = SocketException("Connection reset")
        a.addSuppressed(b)
        b.addSuppressed(a)
        assertThat(classify(a)).isEqualTo(ConnectionFailure.RESET)
    }
}

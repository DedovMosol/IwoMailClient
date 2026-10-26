package com.dedovmosol.iwomail.data.model

import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.net.ssl.SSLException

/** Решение «пробовать ли резервный сервер» и категория health по тексту ошибки EasResult. */
class AccountServerHealthClassificationTest {

    @Test
    fun `transport tag wins over the step prefix`() {
        val wrapped = "Provision phase 1 failed: [NET:TIMEOUT] SocketTimeoutException: timeout (EAS: 12.1)"
        assertThat(classifyError(wrapped, null)).isEqualTo(ErrorKind.Timeout)
        assertThat(isConnectionLevelError(wrapped)).isTrue()
    }

    @Test
    fun `TLS and certificate failures are Cert and never trigger failover`() {
        assertThat(classifyError("[NET:TLS_VERSION] SSLHandshakeException: x", null)).isEqualTo(ErrorKind.Cert)
        assertThat(isConnectionLevelError("[NET:CERT_EXPIRED] SSLHandshakeException: x")).isFalse()
        assertThat(isConnectionLevelError("[NET:CERT_HOSTNAME] SSLPeerUnverifiedException: x")).isFalse()
    }

    @Test
    fun `port numbers in an Android timeout are not an HTTP 401`() {
        val message = "failed to connect to mail.x/10.0.0.5 (port 443) from /10.0.2.15 (port 40135) after 30000ms"
        assertThat(classifyError(message, null)).isNotEqualTo(ErrorKind.Auth)
        assertThat(classifyError("HTTP 401: Unauthorized", null)).isEqualTo(ErrorKind.Auth)
    }

    @Test
    fun `answers of a reachable server do not switch servers, overload does`() {
        assertThat(isConnectionLevelError("UNAUTHORIZED")).isFalse()
        assertThat(isConnectionLevelError("REMOTE_WIPE")).isFalse()
        assertThat(isConnectionLevelError("Ошибка синхронизации папки: status=3")).isFalse()
        assertThat(isConnectionLevelError("HTTP 503: Service Unavailable")).isTrue()
    }

    @Test
    fun `exceptions - reset over TLS is network, unknown is unknown`() {
        assertThat(classifyError(null, SSLException("Read error: ssl=0x1: I/O error during system call, Connection reset by peer")))
            .isEqualTo(ErrorKind.Network)
        assertThat(classifyError(null, IllegalStateException())).isEqualTo(ErrorKind.Unknown)
    }

    @Test
    fun `dual url result prefers an alternate that answered`() {
        val primary: EasResult<Unit> = EasResult.Error("[NET:TIMEOUT] SocketTimeoutException: timeout")

        assertThat(preferredDualUrlResult(primary, EasResult.Success(Unit))).isEqualTo(EasResult.Success(Unit))
        // Резервный ответил «неверный пароль» — это и надо исправлять (основной всё равно недоступен).
        assertThat(preferredDualUrlResult(primary, EasResult.Error("UNAUTHORIZED"))).isEqualTo(EasResult.Error("UNAUTHORIZED"))
        // Недоступны оба — причина основного.
        assertThat(preferredDualUrlResult(primary, EasResult.Error("[NET:DNS] UnknownHostException: x"))).isSameInstanceAs(primary)
    }
}

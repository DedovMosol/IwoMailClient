package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Ping: таймаут ЧТЕНИЯ — обычное истечение heartbeat, таймаут ПОДКЛЮЧЕНИЯ — сервер недоступен
 * (раньше он тоже считался «Ping истёк», и упавший сервер выглядел здоровым).
 */
class EasClientConnectTimeoutTest {

    @Test
    fun `connect timeouts of Android and JVM are recognized`() {
        assertThat(EasClient.isConnectTimeout(SocketTimeoutException(
            "failed to connect to mail.corp/10.0.0.5 (port 443) from /10.0.2.15 (port 40135) after 30000ms"
        ))).isTrue()
        assertThat(EasClient.isConnectTimeout(SocketTimeoutException("Connect timed out"))).isTrue()
    }

    @Test
    fun `read timeouts of the long poll are not connect timeouts`() {
        assertThat(EasClient.isConnectTimeout(SocketTimeoutException("timeout"))).isFalse()
        assertThat(EasClient.isConnectTimeout(SocketTimeoutException("Read timed out"))).isFalse()
        assertThat(EasClient.isConnectTimeout(SocketTimeoutException())).isFalse()
    }
}

package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.eas.composeHttpError
import com.dedovmosol.iwomail.network.ConnectionFailure
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SendFailuresTest {

    @Test
    fun `no connection is retried`() {
        for (failure in ConnectionFailure.entries.filter { it.connectionLevel }) {
            assertThat(isRetryableSendError("Ошибка отправки письма: ${failure.tag} java.io.IOException")).isTrue()
        }
    }

    @Test
    fun `certificate and TLS problems are not retried`() {
        for (failure in ConnectionFailure.entries.filter { !it.connectionLevel }) {
            assertThat(isRetryableSendError("Ошибка отправки письма: ${failure.tag} cert")).isFalse()
        }
    }

    @Test
    fun `busy server is retried`() {
        for (code in listOf(429, 502, 503, 504)) assertThat(isRetryableSendError(composeHttpError(code))).isTrue()
    }

    @Test
    fun `rejection that a retry will not fix`() {
        for (code in listOf(400, 401, 403, 413, 500, 501)) assertThat(isRetryableSendError(composeHttpError(code))).isFalse()
        assertThat(isRetryableSendError("Неверный адрес получателя: x")).isFalse()
        assertThat(isRetryableSendError("Ошибка отправки: MessageTooLarge (Status: 115, EAS: 14.1)")).isFalse()
    }
}

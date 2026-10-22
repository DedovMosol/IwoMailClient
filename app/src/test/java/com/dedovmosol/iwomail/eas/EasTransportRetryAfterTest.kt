package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * EAS-3: Retry-After (секунды) → миллисекунды с ограничением сверху.
 * Защита Exchange 2007 SP2 от «долбёжки» при троттлинге (HTTP 503).
 */
class EasTransportRetryAfterTest {

    @Test
    fun numericSeconds_convertedToMillis() {
        assertThat(EasTransport.parseRetryAfterMs("5")).isEqualTo(5_000L)
        assertThat(EasTransport.parseRetryAfterMs("  12 ")).isEqualTo(12_000L)
    }

    @Test
    fun cappedAtMax() {
        assertThat(EasTransport.parseRetryAfterMs("600")).isEqualTo(EasTransport.MAX_RETRY_AFTER_MS)
    }

    @Test
    fun nullBlankOrHttpDate_isZero() {
        assertThat(EasTransport.parseRetryAfterMs(null)).isEqualTo(0L)
        assertThat(EasTransport.parseRetryAfterMs("")).isEqualTo(0L)
        // HTTP-date форма не поддерживается → 0 (паузу делает верхний слой).
        assertThat(EasTransport.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(0L)
    }

    @Test
    fun zeroOrNegative_isZero() {
        assertThat(EasTransport.parseRetryAfterMs("0")).isEqualTo(0L)
        assertThat(EasTransport.parseRetryAfterMs("-3")).isEqualTo(0L)
    }
}

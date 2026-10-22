package com.dedovmosol.iwomail.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SYNC-3: чистая логика circuit breaker перезапусков PushService (decideRestart).
 * Проверяем backoff, размыкание цепи при превышении лимита и сброс по истечении окна.
 */
class PushRestartPolicyTest {

    private fun decide(now: Long, windowStart: Long, count: Int, requested: Long) =
        decideRestart(
            now = now, prevWindowStart = windowStart, prevCount = count, requestedDelaySec = requested,
            windowMs = 600_000L, maxAttempts = 5, baseBackoffSec = 30L, maxBackoffSec = 900L
        )

    @Test
    fun firstAttempt_resetsWindow_countOne_backoffBase() {
        val d = decide(now = 1_000_000L, windowStart = 0L, count = 0, requested = 10L)
        assertThat(d.windowStart).isEqualTo(1_000_000L)
        assertThat(d.count).isEqualTo(1)
        assertThat(d.delaySec).isEqualTo(30L) // max(10, 30)
    }

    @Test
    fun backoffDoublesPerAttemptWithinWindow() {
        val base = 500_000L
        assertThat(decide(base + 1, base, 0, 0).delaySec).isEqualTo(30L)
        assertThat(decide(base + 1, base, 1, 0).delaySec).isEqualTo(60L)
        assertThat(decide(base + 1, base, 2, 0).delaySec).isEqualTo(120L)
        assertThat(decide(base + 1, base, 3, 0).delaySec).isEqualTo(240L)
        assertThat(decide(base + 1, base, 4, 0).delaySec).isEqualTo(480L)
    }

    @Test
    fun circuitOpensWhenCountExceedsMax() {
        val d = decide(now = 500_001L, windowStart = 500_000L, count = 5, requested = 0L)
        assertThat(d.count).isEqualTo(6)
        assertThat(d.delaySec).isNull() // цепь разомкнута
    }

    @Test
    fun windowExpiryResetsCount() {
        val d = decide(now = 2_000_000L, windowStart = 1_000_000L, count = 5, requested = 0L)
        assertThat(d.windowStart).isEqualTo(2_000_000L)
        assertThat(d.count).isEqualTo(1)
        assertThat(d.delaySec).isEqualTo(30L)
    }

    @Test
    fun requestedDelayRespectedWhenLargerThanBackoff() {
        val d = decide(now = 500_001L, windowStart = 500_000L, count = 0, requested = 120L)
        assertThat(d.delaySec).isEqualTo(120L) // max(120, 30)
    }

    @Test
    fun backoffCappedAtMax() {
        // count=5 → 30<<4=480, но cap=100 → 100
        val d = decideRestart(
            now = 1L, prevWindowStart = 0L, prevCount = 4, requestedDelaySec = 0L,
            windowMs = 600_000L, maxAttempts = 10, baseBackoffSec = 30L, maxBackoffSec = 100L
        )
        assertThat(d.count).isEqualTo(5)
        assertThat(d.delaySec).isEqualTo(100L)
    }
}

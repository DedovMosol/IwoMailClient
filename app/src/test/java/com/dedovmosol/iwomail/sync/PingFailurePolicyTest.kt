package com.dedovmosol.iwomail.sync

import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.network.ConnectionFailure
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Реакция Direct Push на сбои Ping подряд. */
class PingFailurePolicyTest {

    private fun network(n: Int, hasAlternate: Boolean = true, failure: ConnectionFailure = ConnectionFailure.TIMEOUT) =
        PingFailurePolicy.decide(n, ErrorKind.Timeout, failure, hasAlternate)

    @Test
    fun `outage backs off exponentially up to 15 minutes and never disables push`() {
        val delays = (1..9).map { network(it).backoffMs }
        assertThat(delays).containsExactly(10_000L, 30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L, 900_000L).inOrder()
        assertThat((1..20).none { network(it, hasAlternate = false).markPingUnsupported }).isTrue()
    }

    @Test
    fun `server switch is tried every third failure and only with a backup server`() {
        assertThat((1..9).filter { network(it).trySwitchEndpoint }).containsExactly(3, 6, 9)
        assertThat((1..9).none { network(it, hasAlternate = false).trySwitchEndpoint }).isTrue()
    }

    @Test
    fun `wrong password waits the maximum and neither switches nor disables push`() {
        (1..6).map { PingFailurePolicy.decide(it, ErrorKind.Auth, null, hasAlternate = true) }.forEach { d ->
            assertThat(d.backoffMs).isEqualTo(PingFailurePolicy.MAX_BACKOFF_MS)
            assertThat(d.trySwitchEndpoint).isFalse()
            assertThat(d.markPingUnsupported).isFalse()
        }
    }

    @Test
    fun `certificate problem is not treated as an outage`() {
        val d = PingFailurePolicy.decide(3, ErrorKind.Cert, ConnectionFailure.CERT_EXPIRED, hasAlternate = true)
        assertThat(d.trySwitchEndpoint).isFalse()
        assertThat(d.backoffMs).isEqualTo(PingFailurePolicy.MAX_BACKOFF_MS)
    }

    @Test
    fun `server that keeps rejecting Ping switches to periodic sync after three answers`() {
        val decisions = (1..3).map { PingFailurePolicy.decide(it, ErrorKind.Unknown, null, hasAlternate = true) }
        assertThat(decisions.map { it.markPingUnsupported }).containsExactly(false, false, true).inOrder()
    }

    @Test
    fun `heartbeat shrinks only when the long poll is cut`() {
        assertThat(network(1, failure = ConnectionFailure.TIMEOUT).shrinkHeartbeat).isTrue()
        assertThat(network(1, failure = ConnectionFailure.RESET).shrinkHeartbeat).isTrue()
        assertThat(network(1, failure = ConnectionFailure.DNS).shrinkHeartbeat).isFalse()
        assertThat(PingFailurePolicy.decide(1, ErrorKind.Auth, null, true).shrinkHeartbeat).isFalse()
    }
}

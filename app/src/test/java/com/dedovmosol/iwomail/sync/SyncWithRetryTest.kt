package com.dedovmosol.iwomail.sync

import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.data.model.ServerProblemType
import com.dedovmosol.iwomail.data.repository.AccountServerHealthRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Стратегия синхронизации папки с Dual-URL ([syncWithRetry]) на фейках: какие попытки делаются,
 * когда переключается сервер и что попадает в health.
 */
class SyncWithRetryTest {

    private val id = 7L
    private val dns = "[NET:DNS] UnknownHostException: Unable to resolve host \"mail.corp.local\""
    private val timeout = "[NET:TIMEOUT] SocketTimeoutException: failed to connect after 30000ms"

    /** Фейк переключателя: запоминает переключения; [canSwitch] = false — другой сервер не отвечает. */
    private class FakeFailover(
        var hasAlternate: Boolean = true,
        var onFallback: Boolean = false,
        var canSwitch: Boolean = true
    ) : EndpointFailover {
        val switchesFrom = mutableListOf<Boolean>()
        override suspend fun hasAlternateUrl(accountId: Long) = hasAlternate
        override fun isUsingFallback(accountId: Long) = onFallback
        override suspend fun switchToOtherEndpoint(accountId: Long, fromFallback: Boolean): Boolean {
            switchesFrom += fromFallback
            if (!canSwitch) return false
            onFallback = !fromFallback
            return true
        }
    }

    /** Ответы синхронизации по очереди; [forceFullCalls] — значения forceFullSync каждого вызова. */
    private class ScriptedSync(vararg results: EasResult<Unit>) {
        private val queue = ArrayDeque(results.toList())
        val forceFullCalls = mutableListOf<Boolean>()
        suspend operator fun invoke(forceFull: Boolean): EasResult<Unit> {
            forceFullCalls += forceFull
            return queue.removeFirstOrNull() ?: EasResult.Success(Unit)
        }
    }

    private val health get() = AccountServerHealthRepository.healthSnapshot(id)

    private fun run(failover: EndpointFailover?, sync: ScriptedSync, allowFullResync: Boolean = true): Boolean = runBlocking {
        syncWithRetry(id, 1_000L, 1_000L, allowFullResync, "test", failover) { sync(it) }
    }

    @Before
    fun setUp() = AccountServerHealthRepository.reset()

    @Test
    fun `unreachable primary - no full resync, switch to backup, retry succeeds`() {
        val failover = FakeFailover()
        val sync = ScriptedSync(EasResult.Error(dns), EasResult.Success(Unit))

        assertThat(run(failover, sync)).isTrue()

        // Полный resync (SyncKey=0) при обрыве связи не делается — только инкрементальные попытки.
        assertThat(sync.forceFullCalls).containsExactly(false, false).inOrder()
        assertThat(failover.switchesFrom).containsExactly(false)
        assertThat(health.problemType).isEqualTo(ServerProblemType.PrimaryDownUsingFallback)
        assertThat(health.isUsingFallback).isTrue()
    }

    @Test
    fun `protocol error gets a full resync and no server switch`() {
        val failover = FakeFailover()
        val sync = ScriptedSync(EasResult.Error("Ошибка синхронизации папки: status=3"), EasResult.Success(Unit))

        assertThat(run(failover, sync)).isTrue()

        assertThat(sync.forceFullCalls).containsExactly(false, true).inOrder()
        assertThat(failover.switchesFrom).isEmpty()
        assertThat(health.problemType).isEqualTo(ServerProblemType.None)
    }

    @Test
    fun `wrong password - no full resync, no switch, auth error at once`() {
        val failover = FakeFailover()
        val sync = ScriptedSync(EasResult.Error("UNAUTHORIZED"))

        assertThat(run(failover, sync)).isFalse()

        // Лишний вход с неверным паролем приближает блокировку учётной записи в AD.
        assertThat(sync.forceFullCalls).containsExactly(false)
        assertThat(failover.switchesFrom).isEmpty()
        assertThat(health.problemType).isEqualTo(ServerProblemType.AuthError)
    }

    @Test
    fun `backup also unreachable - both servers down after three runs`() {
        val failover = FakeFailover(canSwitch = false)

        repeat(3) { assertThat(run(failover, ScriptedSync(EasResult.Error(timeout)))).isFalse() }

        assertThat(health.problemType).isEqualTo(ServerProblemType.BothServersDown)
        assertThat(failover.onFallback).isFalse()
    }

    @Test
    fun `failure on the backup switches back to the primary`() {
        val failover = FakeFailover(onFallback = true)
        val sync = ScriptedSync(EasResult.Error("[NET:REFUSED] ConnectException: Connection refused"), EasResult.Success(Unit))

        assertThat(run(failover, sync)).isTrue()

        assertThat(failover.switchesFrom).containsExactly(true)
        assertThat(failover.onFallback).isFalse()
        assertThat(health.problemType).isEqualTo(ServerProblemType.None)
    }

    @Test
    fun `retry after switch also cut off counts both servers`() {
        val failover = FakeFailover()
        repeat(3) {
            failover.onFallback = false
            run(failover, ScriptedSync(EasResult.Error(dns), EasResult.Error(timeout)))
        }

        assertThat(health.problemType).isEqualTo(ServerProblemType.BothServersDown)
    }

    @Test
    fun `attempt timeout is a connection failure without full resync`() {
        val calls = mutableListOf<Boolean>()

        val ok = runBlocking {
            syncWithRetry(id, 50L, 50L, allowFullResync = true, tag = "test", failover = null) { forceFull ->
                calls += forceFull
                awaitCancellation()
            }
        }

        assertThat(ok).isFalse()
        assertThat(calls).containsExactly(false)
        assertThat(health.lastErrorKind).isEqualTo(ErrorKind.Timeout)
        assertThat(health.lastErrorMessage).startsWith("[NET:TIMEOUT]")
    }

    @Test
    fun `without a backup server a network failure is just reported`() {
        val sync = ScriptedSync(EasResult.Error(dns))

        assertThat(run(FakeFailover(hasAlternate = false), sync)).isFalse()
        assertThat(sync.forceFullCalls).containsExactly(false)
        assertThat(health.primaryConsecutiveFailures).isEqualTo(1)
    }

    @Test
    fun `cancellation is never swallowed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                syncWithRetry(id, 1_000L, 1_000L, true, "test", FakeFailover()) { throw CancellationException("stop") }
            }
        }
    }

    @Test
    fun `single request failover retries once after switching`() = runBlocking<Unit> {
        val failover = FakeFailover()
        val answers = ArrayDeque(listOf<EasResult<String>>(EasResult.Error(dns), EasResult.Success("folders")))

        val result = withEndpointFailover(id, failover) { answers.removeFirst() }

        assertThat(result).isEqualTo(EasResult.Success("folders"))
        assertThat(failover.switchesFrom).containsExactly(false)

        val authOnly = withEndpointFailover(id, FakeFailover()) { EasResult.Error("UNAUTHORIZED") }
        assertThat(authOnly).isEqualTo(EasResult.Error("UNAUTHORIZED"))
    }
}

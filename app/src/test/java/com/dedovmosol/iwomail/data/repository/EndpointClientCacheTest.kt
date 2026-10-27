package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Кэш клиентов и выбор сервера Dual-URL: переключение, возврат на основной, инвалидация, гонки. */
class EndpointClientCacheTest {

    private val id = 1L
    private val primary = "mail.corp.example"
    private val backup = "10.0.0.5:8443"

    /** Фейк: клиенты — строки «primary#N»/«backup#N»; [gate] держит пробу, пока тест не отпустит. */
    private inner class FakeBackend : EndpointClientCache.Backend<String> {
        var endpoints: EndpointClientCache.Endpoints? = EndpointClientCache.Endpoints(primary, backup, 443)
        val reachable = mutableMapOf(primary to true, backup to true)
        var network = true
        val created = mutableListOf<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        var onCreate: (() -> Unit)? = null

        override suspend fun endpoints(accountId: Long) = endpoints
        override suspend fun create(accountId: Long, useAlternate: Boolean): String {
            created += useAlternate
            onCreate?.invoke()
            return "${if (useAlternate) "backup" else "primary"}#${created.size}"
        }
        override suspend fun isReachable(serverUrl: String, port: Int): Boolean {
            gate?.await()
            return reachable[serverUrl] ?: false
        }
        override fun hasNetwork() = network
    }

    private var clock = 0L
    private val backend = FakeBackend()
    private val cache = EndpointClientCache(backend, fallbackTtlMs = 1_000L, now = { clock })

    @Test
    fun `primary client is built once and reused`() = runTest {
        assertThat(cache.get(id)).isEqualTo("primary#1")
        assertThat(cache.get(id)).isEqualTo("primary#1")
        assertThat(backend.created).containsExactly(false)
        assertThat(cache.isUsingFallback(id)).isFalse()
    }

    @Test
    fun `switch to the reachable backup and notify push about the new client`() = runTest {
        cache.get(id)
        clock = 50L

        assertThat(cache.switchToOtherEndpoint(id, fromFallback = false)).isTrue()

        assertThat(cache.isUsingFallback(id)).isTrue()
        assertThat(cache.get(id)).isEqualTo("backup#2")
        assertThat(cache.lastInvalidation(id)).isEqualTo(50L)
    }

    @Test
    fun `no switch without a reachable backup, a backup address or a network`() = runTest {
        backend.reachable[backup] = false
        assertThat(cache.switchToOtherEndpoint(id, fromFallback = false)).isFalse()

        backend.reachable[backup] = true
        backend.network = false
        assertThat(cache.switchToOtherEndpoint(id, fromFallback = false)).isFalse()

        backend.network = true
        backend.endpoints = EndpointClientCache.Endpoints(primary, null, 443)
        assertThat(cache.switchToOtherEndpoint(id, fromFallback = false)).isFalse()

        assertThat(backend.created).isEmpty()
        assertThat(cache.isUsingFallback(id)).isFalse()
    }

    @Test
    fun `concurrent reports of the same outage switch once`() = runTest {
        backend.gate = CompletableDeferred()
        val first = async { cache.switchToOtherEndpoint(id, fromFallback = false) }
        val second = async { cache.switchToOtherEndpoint(id, fromFallback = false) }
        runCurrent() // оба ждут пробу
        backend.gate!!.complete(Unit)

        assertThat(first.await()).isTrue()
        assertThat(second.await()).isTrue()
        assertThat(backend.created).containsExactly(true) // один клиент резервного, без flip-flop
        assertThat(cache.isUsingFallback(id)).isTrue()
    }

    @Test
    fun `failure on the backup switches back to the primary`() = runTest {
        cache.switchToOtherEndpoint(id, fromFallback = false)

        assertThat(cache.switchToOtherEndpoint(id, fromFallback = true)).isTrue()

        assertThat(cache.isUsingFallback(id)).isFalse()
        assertThat(cache.get(id)).isEqualTo("primary#2")
    }

    @Test
    fun `after the interval the primary is probed before returning to it`() = runTest {
        cache.switchToOtherEndpoint(id, fromFallback = false)

        // Основной всё ещё лежит: остаёмся на резервном ещё интервал, без нового клиента.
        backend.reachable[primary] = false
        clock = 1_500L
        assertThat(cache.get(id)).isEqualTo("backup#1")
        assertThat(cache.isUsingFallback(id)).isTrue()
        clock = 2_000L
        assertThat(cache.get(id)).isEqualTo("backup#1") // интервал отсчитан заново от 1500

        // Основной ожил — возврат.
        backend.reachable[primary] = true
        clock = 2_600L
        assertThat(cache.get(id)).isEqualTo("primary#2")
        assertThat(cache.isUsingFallback(id)).isFalse()
        assertThat(cache.lastInvalidation(id)).isEqualTo(2_600L)
    }

    @Test
    fun `reset returns to the primary, refresh keeps the backup`() = runTest {
        cache.switchToOtherEndpoint(id, fromFallback = false)

        cache.refresh(id)
        assertThat(cache.isUsingFallback(id)).isTrue()
        assertThat(cache.get(id)).isEqualTo("backup#2")

        cache.reset(id)
        assertThat(cache.isUsingFallback(id)).isFalse()
        assertThat(cache.get(id)).isEqualTo("primary#3")
    }

    @Test
    fun `client built while settings change is not cached`() = runTest {
        // Пользователь сохранил настройки, пока собирался клиент по старым.
        backend.onCreate = { backend.onCreate = null; cache.reset(id) }

        assertThat(cache.get(id)).isEqualTo("primary#2")
        assertThat(cache.get(id)).isEqualTo("primary#2")
        assertThat(backend.created).hasSize(2)
    }

    @Test
    fun `outage reported for old settings does not switch`() = runTest {
        backend.gate = CompletableDeferred()
        val report = async { cache.switchToOtherEndpoint(id, fromFallback = false) }
        runCurrent()
        cache.reset(id) // настройки сменились, пока шла проба
        backend.gate!!.complete(Unit)

        assertThat(report.await()).isFalse()
        assertThat(cache.isUsingFallback(id)).isFalse()
        assertThat(backend.created).isEmpty()
    }

    @Test
    fun `reachability is probed on the server the account currently uses`() = runTest {
        backend.reachable[primary] = false
        assertThat(cache.isCurrentEndpointReachable(id)).isFalse()

        cache.switchToOtherEndpoint(id, fromFallback = false)
        assertThat(cache.isCurrentEndpointReachable(id)).isTrue() // резервный отвечает

        backend.network = false
        assertThat(cache.isCurrentEndpointReachable(id)).isFalse()
        backend.network = true
        backend.endpoints = null // аккаунт удалён
        assertThat(cache.isCurrentEndpointReachable(id)).isFalse()
    }

    @Test
    fun `forgotten account starts from scratch`() = runTest {
        cache.switchToOtherEndpoint(id, fromFallback = false)
        cache.forget(id)

        assertThat(cache.isUsingFallback(id)).isFalse()
        assertThat(cache.lastInvalidation(id)).isEqualTo(0L)
        assertThat(cache.get(id)).isEqualTo("primary#2")
    }
}

package com.dedovmosol.iwomail.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Клиенты Exchange по аккаунтам и выбор сервера: основной ↔ резервный (Dual-URL).
 *
 * Вынесено из AccountRepository (SRP): выбор сервера, кэш и инвалидация проверяются тестами без
 * Android и Room; доступ к аккаунту, созданию клиента и сети — через [Backend].
 *
 * - Клиент кэшируется на аккаунт (каждый держит OkHttp-клиент — пересоздание = новые соединения).
 * - На резервный переключает только [switchToOtherEndpoint] после TCP-пробы. Через [fallbackTtlMs] —
 *   проба основного: отвечает → возврат на основной, нет → резервный ещё на интервал.
 * - [reset] (сменились адреса, учётные данные) сбрасывает и выбор сервера, [refresh] (сменился
 *   сертификат или пин) — только клиент. Клиент, собранный по настройкам, которые сменились во время
 *   сборки, в кэш не попадает (поколение аккаунта): иначе он жил бы до следующей инвалидации.
 * - Сборка клиента и переключение — под per-account Mutex; короткие изменения состояния — под
 *   synchronized, поэтому [reset]/[refresh] можно звать из любого потока, не дожидаясь сети.
 */
internal class EndpointClientCache<C : Any>(
    private val backend: Backend<C>,
    private val fallbackTtlMs: Long = DEFAULT_FALLBACK_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    interface Backend<C> {
        /** Адреса аккаунта; null — аккаунта нет. */
        suspend fun endpoints(accountId: Long): Endpoints?

        /** Клиент основного или резервного сервера по ТЕКУЩИМ настройкам; null — создать нельзя. */
        suspend fun create(accountId: Long, useAlternate: Boolean): C?

        /** TCP-проба сервера (учётные данные не отправляются). */
        suspend fun isReachable(serverUrl: String, port: Int): Boolean

        /** Есть ли сеть вообще (не обязательно интернет — Exchange может быть в сети организации). */
        fun hasNetwork(): Boolean
    }

    data class Endpoints(val primaryUrl: String, val alternateUrl: String?, val port: Int)

    /** Состояние аккаунта; поля меняются только под synchronized(this). */
    private class State<C> {
        var client: C? = null
        /** С какого момента аккаунт на резервном сервере; null — на основном. */
        var fallbackSince: Long? = null
        /** Растёт при каждой инвалидации: клиент, собранный до неё, в кэш не попадает. */
        var generation = 0L
        var invalidatedAt = 0L
    }

    private val states = ConcurrentHashMap<Long, State<C>>()
    // Мьютексы не удаляются: иначе ожидающий получил бы другой экземпляр, и в секции оказались бы двое.
    private val locks = ConcurrentHashMap<Long, Mutex>()

    private fun state(accountId: Long): State<C> = states.computeIfAbsent(accountId) { State() }
    private fun lock(accountId: Long): Mutex = locks.computeIfAbsent(accountId) { Mutex() }

    fun isUsingFallback(accountId: Long): Boolean {
        val state = states[accountId] ?: return false
        return synchronized(state) { state.fallbackSince != null }
    }

    /** Когда клиент аккаунта последний раз сменился (PushService держит свой кэш и сверяется с этим). */
    fun lastInvalidation(accountId: Long): Long {
        val state = states[accountId] ?: return 0L
        return synchronized(state) { state.invalidatedAt }
    }

    suspend fun get(accountId: Long): C? {
        cachedClient(accountId)?.let { return it }
        return lock(accountId).withLock {
            val state = state(accountId)
            if (synchronized(state) { fallbackExpired(state) }) returnToPrimaryIfReachable(accountId, state)
            cachedClient(accountId)?.let { return@withLock it }
            var client: C? = null
            for (attempt in 1..MAX_BUILD_ATTEMPTS) {
                val (generation, useAlternate) = synchronized(state) { state.generation to (state.fallbackSince != null) }
                client = backend.create(accountId, useAlternate) ?: return@withLock null
                val cached = synchronized(state) {
                    (state.generation == generation).also { current -> if (current) state.client = client }
                }
                if (cached) break
            }
            // Настройки менялись при каждой попытке — клиент отдаётся без кэша (следующий запрос соберёт заново).
            client
        }
    }

    /**
     * Переключает аккаунт на ДРУГОЙ сервер: с основного на резервный или, если сбой был на резервном
     * ([fromFallback]), обратно. Перед переключением — TCP-проба целевого сервера.
     *
     * Если аккаунт уже переключил параллельный вызов (состояние не совпадает с [fromFallback]) —
     * true без повторного переключения (без flip-flop). Если во время пробы сменились настройки — false:
     * сбой относился к прежним.
     */
    suspend fun switchToOtherEndpoint(accountId: Long, fromFallback: Boolean): Boolean {
        val state = state(accountId)
        val generation = synchronized(state) {
            if ((state.fallbackSince != null) != fromFallback) return true
            state.generation
        }
        val endpoints = backend.endpoints(accountId) ?: return false
        val alternate = endpoints.alternateUrl?.takeIf { it.isNotBlank() } ?: return false
        val target = if (fromFallback) endpoints.primaryUrl else alternate
        if (!backend.hasNetwork() || !backend.isReachable(target, endpoints.port)) return false
        return lock(accountId).withLock {
            synchronized(state) {
                if ((state.fallbackSince != null) != fromFallback) return@withLock true
                if (state.generation != generation) return@withLock false
            }
            val client = backend.create(accountId, useAlternate = !fromFallback) ?: return@withLock false
            synchronized(state) {
                if (state.generation != generation) return@withLock false
                val time = now()
                state.client = client
                state.fallbackSince = if (fromFallback) null else time
                state.invalidatedAt = time
            }
            true
        }
    }

    /** TCP-проба сервера, через который аккаунт работает сейчас (основного или резервного). */
    suspend fun isCurrentEndpointReachable(accountId: Long): Boolean {
        val endpoints = backend.endpoints(accountId) ?: return false
        val alternate = endpoints.alternateUrl?.takeIf { it.isNotBlank() }
        val current = if (isUsingFallback(accountId) && alternate != null) alternate else endpoints.primaryUrl
        return backend.hasNetwork() && backend.isReachable(current, endpoints.port)
    }

    /** Сменились адреса, учётные данные или аккаунт: новый клиент и снова основной сервер. */
    fun reset(accountId: Long) = invalidate(accountId, keepEndpoint = false)

    /** Сменились сертификат или пин: новый клиент для ТОГО ЖЕ сервера (резервный остаётся резервным). */
    fun refresh(accountId: Long) = invalidate(accountId, keepEndpoint = true)

    /** Аккаунт удалён. */
    fun forget(accountId: Long) {
        val state = states.remove(accountId) ?: return
        synchronized(state) {
            state.generation++
            state.client = null
            state.fallbackSince = null
        }
    }

    private fun invalidate(accountId: Long, keepEndpoint: Boolean) {
        val state = state(accountId)
        synchronized(state) {
            state.generation++
            state.client = null
            if (!keepEndpoint) state.fallbackSince = null
            state.invalidatedAt = now()
        }
    }

    /** Кэшированный клиент, если резервный не отслужил интервал (иначе решение — под локом). */
    private fun cachedClient(accountId: Long): C? {
        val state = states[accountId] ?: return null
        return synchronized(state) { if (fallbackExpired(state)) null else state.client }
    }

    /** Вызывать под synchronized(state). */
    private fun fallbackExpired(state: State<C>): Boolean {
        val since = state.fallbackSince ?: return false
        return now() - since > fallbackTtlMs
    }

    /**
     * Резервный отслужил интервал: основной отвечает (или аккаунта нет) → возврат на основной, иначе
     * резервный ещё на интервал — без запроса в недоступный основной (таймаут подключения до 30 с).
     */
    private suspend fun returnToPrimaryIfReachable(accountId: Long, state: State<C>) {
        val generation = synchronized(state) { state.generation }
        val endpoints = backend.endpoints(accountId)
        val primaryUp = endpoints == null || backend.isReachable(endpoints.primaryUrl, endpoints.port)
        synchronized(state) {
            if (state.generation != generation) return // настройки сменились во время пробы — состояние уже свежее
            if (primaryUp) {
                state.client = null
                state.fallbackSince = null
                state.invalidatedAt = now()
            } else {
                state.fallbackSince = now()
            }
        }
    }

    companion object {
        const val DEFAULT_FALLBACK_TTL_MS = 10 * 60 * 1000L
        private const val MAX_BUILD_ATTEMPTS = 2
    }
}

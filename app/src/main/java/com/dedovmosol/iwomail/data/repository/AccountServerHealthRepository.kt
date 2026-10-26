package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.model.AccountServerHealth
import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.data.model.ServerProblemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-account server health tracker.
 *
 * Hysteresis: enters bad state after [FAILURE_THRESHOLD] consecutive failures,
 * exits on first success. Auth/Cert errors bypass the threshold (deterministic).
 *
 * Thread-safe: per-account synchronized blocks protect read-modify-write of counters;
 * StateFlow emissions are atomic by contract.
 */
object AccountServerHealthRepository {

    private const val FAILURE_THRESHOLD = 3

    private val healthMap = ConcurrentHashMap<Long, MutableStateFlow<AccountServerHealth>>()
    private val locks = ConcurrentHashMap<Long, Any>()

    fun healthFlow(accountId: Long): StateFlow<AccountServerHealth> =
        getOrCreate(accountId)

    fun healthSnapshot(accountId: Long): AccountServerHealth =
        getOrCreate(accountId).value

    fun aggregatedHealthFlow(accountIds: List<Long>): Flow<Map<Long, AccountServerHealth>> {
        if (accountIds.isEmpty()) {
            return MutableStateFlow(emptyMap())
        }
        val flows = accountIds.map { id -> getOrCreate(id) }
        return combine(flows) { values ->
            accountIds.zip(values.toList()).toMap()
        }
    }

    /**
     * @param usedFallback исход получен на резервном сервере.
     * @param bothFailed сбой связи на текущем сервере, и другой тоже не ответил (недоступен или
     *   сбой связи после переключения): засчитывается обоим счётчикам.
     */
    fun reportSyncOutcome(
        accountId: Long,
        success: Boolean,
        errorKind: ErrorKind? = null,
        errorMessage: String? = null,
        usedFallback: Boolean = false,
        hasAlternateUrl: Boolean = false,
        serverDisplayName: String? = null,
        bothFailed: Boolean = false
    ) {
        val lock = locks.computeIfAbsent(accountId) { Any() }
        synchronized(lock) {
            val flow = getOrCreate(accountId)
            val current = flow.value
            val now = System.currentTimeMillis()

            if (success) {
                // Почта идёт через резервный сервер: основной по-прежнему считается недоступным
                // (состояние информационное — без баннера и уведомления, см. showBanner).
                flow.value = current.copy(
                    problemType = if (usedFallback) ServerProblemType.PrimaryDownUsingFallback else ServerProblemType.None,
                    primaryConsecutiveFailures = if (usedFallback) current.primaryConsecutiveFailures else 0,
                    alternateConsecutiveFailures = 0,
                    lastSuccessAt = now,
                    lastErrorMessage = null,
                    lastErrorKind = null,
                    isUsingFallback = usedFallback,
                    serverDisplayName = serverDisplayName ?: current.serverDisplayName
                )
                return
            }

            val kind = errorKind ?: ErrorKind.Unknown

            val primaryFails = current.primaryConsecutiveFailures + if (!usedFallback || bothFailed) 1 else 0
            val altFails = current.alternateConsecutiveFailures + if (usedFallback || bothFailed) 1 else 0

            val problemType = deriveProblemType(
                primaryFails, altFails, hasAlternateUrl, kind, onFallback = usedFallback && !bothFailed
            )

            flow.value = current.copy(
                problemType = problemType,
                primaryConsecutiveFailures = primaryFails,
                alternateConsecutiveFailures = altFails,
                lastFailureAt = now,
                lastErrorMessage = errorMessage,
                lastErrorKind = kind,
                isUsingFallback = usedFallback,
                serverDisplayName = serverDisplayName ?: current.serverDisplayName
            )
        }
    }

    fun clearAccount(accountId: Long) {
        healthMap.remove(accountId)
        locks.remove(accountId)
    }

    /**
     * Сбрасывает health в начальное состояние, сохраняя Flow (UI-коллекторы продолжают получать обновления).
     * Вызывается при смене serverUrl/alternateServerUrl в настройках.
     */
    fun resetHealth(accountId: Long) {
        val lock = locks.computeIfAbsent(accountId) { Any() }
        synchronized(lock) {
            val flow = getOrCreate(accountId)
            flow.value = AccountServerHealth(accountId = accountId)
        }
    }

    fun reset() {
        healthMap.clear()
        locks.clear()
    }

    private fun getOrCreate(accountId: Long): MutableStateFlow<AccountServerHealth> =
        healthMap.computeIfAbsent(accountId) {
            MutableStateFlow(AccountServerHealth(accountId = accountId))
        }

    /**
     * @param onFallback сбой случился на резервном сервере, а основной к этому моменту уже не отвечал.
     *   «Используется резервный» — только тогда: раньше этот статус ставился за любые 3 сбоя основного
     *   при настроенном резервном, даже если резервный тоже недоступен (вместо «оба недоступны»).
     */
    private fun deriveProblemType(
        primaryFails: Int,
        altFails: Int,
        hasAlternate: Boolean,
        errorKind: ErrorKind,
        onFallback: Boolean
    ): ServerProblemType {
        if (errorKind == ErrorKind.Auth) return ServerProblemType.AuthError
        if (errorKind == ErrorKind.Cert) return ServerProblemType.CertError

        if (primaryFails < FAILURE_THRESHOLD && altFails < FAILURE_THRESHOLD) {
            return ServerProblemType.None
        }

        if (primaryFails >= FAILURE_THRESHOLD && hasAlternate) {
            if (altFails >= FAILURE_THRESHOLD) return ServerProblemType.BothServersDown
            if (onFallback) return ServerProblemType.PrimaryDownUsingFallback
        }

        if (primaryFails >= FAILURE_THRESHOLD) {
            return when (errorKind) {
                ErrorKind.Server5xx -> ServerProblemType.ServerError
                ErrorKind.Timeout -> ServerProblemType.Timeout
                else -> ServerProblemType.PrimaryDownNoFallback
            }
        }

        if (altFails >= FAILURE_THRESHOLD) {
            return ServerProblemType.BothServersDown
        }

        return ServerProblemType.None
    }
}

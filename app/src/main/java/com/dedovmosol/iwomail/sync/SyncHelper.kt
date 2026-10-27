package com.dedovmosol.iwomail.sync

import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.data.model.classifyError
import com.dedovmosol.iwomail.data.model.isConnectionLevelError
import com.dedovmosol.iwomail.data.repository.AccountServerHealthRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.network.ConnectionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Переключение между основным и резервным сервером аккаунта (Dual-URL) для синхронизации.
 * Абстракция (DIP): стратегия повтора тестируется без Android и БД; реализация — AccountRepository.
 */
interface EndpointFailover {
    suspend fun hasAlternateUrl(accountId: Long): Boolean

    /** Аккаунт сейчас работает через резервный сервер. */
    fun isUsingFallback(accountId: Long): Boolean

    /**
     * Переключиться на другой сервер (с основного на резервный или обратно), если он отвечает.
     * [fromFallback] — на каком сервере случился сбой. Если аккаунт уже переключили параллельно,
     * вернуть true без повторного переключения (иначе два потока перекидывали бы его туда-обратно).
     */
    suspend fun switchToOtherEndpoint(accountId: Long, fromFallback: Boolean): Boolean

    /**
     * Отвечает ли по TCP сервер, через который аккаунт работает сейчас (учётные данные не отправляются).
     * Нужна, когда попытка не уложилась во время: иначе медленный сервер не отличить от недоступного.
     */
    suspend fun isCurrentEndpointReachable(accountId: Long): Boolean
}

/**
 * Синхронизация папки с повтором — общая для SyncWorker, PushService и InitialSyncController (DRY).
 * Стратегия — см. [syncWithRetry].
 *
 * @param allowFullResync false — без тяжёлого полного resync (пользовательские папки сверх лимита).
 * @param accountRepo Dual-URL: переключение на другой сервер при обрыве связи.
 * @return true, если синхронизация прошла без ошибок
 */
suspend fun syncFolderWithRetry(
    mailRepo: MailRepository,
    accountId: Long,
    folderId: String,
    incrementalTimeoutMs: Long = 60_000L,
    fullResyncTimeoutMs: Long = 300_000L,
    allowFullResync: Boolean = true,
    tag: String = "SyncHelper",
    accountRepo: EndpointFailover? = null
): Boolean = syncWithRetry(accountId, incrementalTimeoutMs, fullResyncTimeoutMs, allowFullResync, tag, accountRepo) { forceFull ->
    mailRepo.syncEmails(accountId, folderId, forceFullSync = forceFull)
}

/**
 * 1. Инкрементальный sync ([incrementalTimeoutMs]).
 * 2. Сервер ответил ошибкой (протокол, SyncKey) → полный resync с SyncKey=0, если [allowFullResync].
 *    При обрыве связи полный resync НЕ делается: он бесполезен (сеть та же), а SyncKey сбрасывается
 *    до запроса — следующая удачная синхронизация стала бы полной перезагрузкой папки.
 * 3. Обрыв связи (таймаут, DNS, отказ, сброс, 5xx) и есть резервный адрес → переключение на другой
 *    сервер (TCP-проба без учётных данных) и один повтор. Авторизация, политика и сертификат не
 *    переключают: у резервного те же учётные данные, а сертификат основного нельзя обходить молча.
 *    Попытка не уложилась во время — переключение, только если текущий сервер не отвечает и по TCP.
 * 4. CancellationException всегда пробрасывается.
 *
 * Исход попадает в [AccountServerHealthRepository] с тем сервером, где он случился.
 */
internal suspend fun syncWithRetry(
    accountId: Long,
    incrementalTimeoutMs: Long,
    fullResyncTimeoutMs: Long,
    allowFullResync: Boolean,
    tag: String,
    failover: EndpointFailover?,
    sync: suspend (forceFullSync: Boolean) -> EasResult<*>
): Boolean {
    val hasAlternate = failover?.hasAlternateUrl(accountId) ?: false
    val startedOnFallback = failover?.isUsingFallback(accountId) ?: false

    var failure = attempt(incrementalTimeoutMs) { sync(false) }
        ?: return reportSuccess(accountId, hasAlternate, usedFallback = startedOnFallback)
    // Отказ авторизации/политики/TLS полный resync не лечит, а лишний вход с неверным паролем
    // приближает блокировку учётной записи в AD. Не уложились во время — тоже нет: SyncKey
    // сохраняется постранично, следующий запуск продолжит, а полный resync начал бы с нуля.
    if (allowFullResync && !failure.connectionLevel && !failure.deterministic && !failure.isTimeout) {
        android.util.Log.w(tag, "Incremental sync failed (${failure.summary}), trying full resync")
        failure = attempt(fullResyncTimeoutMs) { sync(true) }
            ?: return reportSuccess(accountId, hasAlternate, usedFallback = startedOnFallback)
    }
    // Попытка не уложилась во время. Сервер отвечает по TCP — он просто медленный (большая папка), это не
    // повод уходить на резервный. Не отвечает — обрыв связи: время попытки съели таймауты подключения
    // (OkHttp перебирает адреса хоста по очереди, по 30 с на каждый) или DNS вместе с подключением.
    val slowButReachable = failure.isTimeout && failover != null && hasAlternate &&
        failover.isCurrentEndpointReachable(accountId)
    if (failover == null || !hasAlternate || !failure.connectionLevel || slowButReachable) {
        reportFailure(accountId, hasAlternate, failure, usedFallback = startedOnFallback)
        return false
    }
    if (!failover.switchToOtherEndpoint(accountId, fromFallback = startedOnFallback)) {
        // Текущий сервер не отвечает, а другой недоступен даже по TCP — недоступны оба.
        reportFailure(accountId, hasAlternate, failure, usedFallback = startedOnFallback, bothFailed = true)
        return false
    }
    android.util.Log.i(tag, "Dual-URL: retrying via ${if (startedOnFallback) "primary" else "alternate"} server")
    val retryFailure = attempt(fullResyncTimeoutMs) { sync(false) }
        ?: return reportSuccess(accountId, hasAlternate, usedFallback = !startedOnFallback)
    reportFailure(
        accountId, hasAlternate, retryFailure,
        usedFallback = !startedOnFallback,
        bothFailed = retryFailure.connectionLevel
    )
    return false
}

/**
 * Один запрос с Dual-URL (например, FolderSync первичной синхронизации): при обрыве связи —
 * переключение на другой сервер и один повтор. Health здесь не обновляется — это делает вызывающий.
 */
suspend fun <T> withEndpointFailover(
    accountId: Long,
    failover: EndpointFailover?,
    request: suspend () -> EasResult<T>
): EasResult<T> {
    val startedOnFallback = failover?.isUsingFallback(accountId) ?: false
    val result = request()
    if (result !is EasResult.Error || failover == null || !isConnectionLevelError(result.message)) return result
    if (!failover.hasAlternateUrl(accountId) || !failover.switchToOtherEndpoint(accountId, startedOnFallback)) return result
    return request()
}

/** Неудачная попытка: текст ошибки, исключение или таймаут всей попытки (оба null). */
private class SyncFailure(val message: String?, val exception: Throwable?) {
    val isTimeout: Boolean get() = message == null && exception == null

    /** Обрыв связи: другой сервер может помочь, а полный resync — нет. */
    val connectionLevel: Boolean = when {
        exception != null -> ConnectionFailure.classify(exception)?.connectionLevel ?: false
        message == null -> true
        else -> isConnectionLevelError(message)
    }

    /** Авторизация, политика, TLS/сертификат: повтор даст тот же отказ. */
    val deterministic: Boolean
        get() = classifyError(message, exception).let { it == ErrorKind.Auth || it == ErrorKind.Cert }

    val summary: String
        get() = message ?: exception?.let { ConnectionFailure.describe(it) } ?: SYNC_TIMEOUT_MESSAGE
}

private val SYNC_TIMEOUT_MESSAGE = "${ConnectionFailure.TIMEOUT.tag} Sync attempt timed out"

/** null — успех; иначе причина сбоя. */
private suspend fun attempt(timeoutMs: Long, block: suspend () -> EasResult<*>): SyncFailure? {
    val result = try {
        withTimeoutOrNull(timeoutMs) { block() }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return SyncFailure(e.message, e)
    }
    return when (result) {
        null -> SyncFailure(null, null)
        is EasResult.Success -> null
        is EasResult.Error -> SyncFailure(result.message, null)
    }
}

private fun reportSuccess(accountId: Long, hasAlternate: Boolean, usedFallback: Boolean): Boolean {
    AccountServerHealthRepository.reportSyncOutcome(
        accountId, success = true, usedFallback = usedFallback, hasAlternateUrl = hasAlternate
    )
    return true
}

private fun reportFailure(
    accountId: Long,
    hasAlternate: Boolean,
    failure: SyncFailure,
    usedFallback: Boolean,
    bothFailed: Boolean = false
) {
    AccountServerHealthRepository.reportSyncOutcome(
        accountId = accountId,
        success = false,
        errorKind = if (failure.isTimeout) ErrorKind.Timeout else classifyError(failure.message, failure.exception),
        errorMessage = failure.summary,
        usedFallback = usedFallback,
        hasAlternateUrl = hasAlternate,
        bothFailed = bothFailed
    )
}

package com.dedovmosol.iwomail.sync

import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.data.model.classifyError
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.AccountServerHealthRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Общая логика синхронизации папки с retry-стратегией.
 * Используется в SyncWorker и PushService для устранения дублирования (DRY).
 *
 * Стратегия:
 * 1. Инкрементальный sync (60с таймаут) — быстрый delta-sync, выполняется ВСЕГДА
 * 2. При таймауте/ошибке/исключении → полный resync (300с таймаут), если [allowFullResync] = true
 * 3. Dual-URL fallback: если оба sync-а провалились И настроен alternate URL → пробуем alternate
 * 4. CancellationException ВСЕГДА пробрасывается (Kotlin docs: rethrow for cancellation propagation)
 *
 * @param allowFullResync если false — при ошибке инкрементального sync НЕ делаем тяжёлый full resync.
 *   Используется для пользовательских папок сверх лимита: они получают быстрый инкрементальный sync,
 *   но не блокируют Worker тяжёлым full resync (до 280с/папка).
 * @param accountRepo опционально: для Dual-URL fallback при ошибке соединения
 * @return true если синхронизация прошла без ошибок
 */
suspend fun syncFolderWithRetry(
    mailRepo: MailRepository,
    accountId: Long,
    folderId: String,
    incrementalTimeoutMs: Long = 60_000L,
    fullResyncTimeoutMs: Long = 300_000L,
    allowFullResync: Boolean = true,
    tag: String = "SyncHelper",
    accountRepo: AccountRepository? = null
): Boolean {
    val hasAlternate = accountRepo?.hasAlternateUrl(accountId) ?: false
    try {
        val result = withTimeoutOrNull(incrementalTimeoutMs) {
            mailRepo.syncEmails(accountId, folderId, forceFullSync = false)
        }
        if (result == null || result is EasResult.Error) {
            if (!allowFullResync) {
                return tryDualUrlFallback(accountRepo, accountId, folderId, mailRepo, fullResyncTimeoutMs, tag, hasAlternate)
            }
            if (result == null) {
                android.util.Log.w(tag, "Incremental sync timeout for folder $folderId, falling back to full resync")
            }
            val retryResult = withTimeoutOrNull(fullResyncTimeoutMs) {
                mailRepo.syncEmails(accountId, folderId, forceFullSync = true)
            }
            if (retryResult == null || retryResult is EasResult.Error) {
                return tryDualUrlFallback(
                    accountRepo, accountId, folderId, mailRepo, fullResyncTimeoutMs, tag, hasAlternate,
                    lastError = (retryResult as? EasResult.Error)?.message ?: (result as? EasResult.Error)?.message
                )
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (!allowFullResync) {
            return tryDualUrlFallback(accountRepo, accountId, folderId, mailRepo, fullResyncTimeoutMs, tag, hasAlternate, exception = e)
        }
        try {
            val retryResult = withTimeoutOrNull(fullResyncTimeoutMs) {
                mailRepo.syncEmails(accountId, folderId, forceFullSync = true)
            }
            if (retryResult == null || retryResult is EasResult.Error) {
                return tryDualUrlFallback(
                    accountRepo, accountId, folderId, mailRepo, fullResyncTimeoutMs, tag, hasAlternate,
                    lastError = (retryResult as? EasResult.Error)?.message ?: e.message,
                    exception = e
                )
            }
        } catch (e2: Exception) {
            if (e2 is CancellationException) throw e2
            return tryDualUrlFallback(accountRepo, accountId, folderId, mailRepo, fullResyncTimeoutMs, tag, hasAlternate, exception = e2)
        }
    }
    AccountServerHealthRepository.reportSyncOutcome(
        accountId, success = true, hasAlternateUrl = hasAlternate
    )
    return true
}

/**
 * Dual-URL fallback: при провале sync через primary URL пробует alternate.
 * Не тратит батарею на probe при отсутствии интернета.
 */
private suspend fun tryDualUrlFallback(
    accountRepo: AccountRepository?,
    accountId: Long,
    folderId: String,
    mailRepo: MailRepository,
    timeoutMs: Long,
    tag: String,
    hasAlternate: Boolean,
    lastError: String? = null,
    exception: Throwable? = null
): Boolean {
    if (accountRepo == null) {
        reportFailure(accountId, hasAlternate, lastError, exception, usedFallback = false)
        return false
    }
    accountRepo.tryFallbackToAlternate(accountId) ?: run {
        reportFailure(accountId, hasAlternate, lastError, exception, usedFallback = false)
        return false
    }
    android.util.Log.i(tag, "Dual-URL: retrying folder $folderId via alternate server")
    return try {
        val result = withTimeoutOrNull(timeoutMs) {
            mailRepo.syncEmails(accountId, folderId, forceFullSync = false)
        }
        if (result is EasResult.Success) {
            AccountServerHealthRepository.reportSyncOutcome(
                accountId, success = true, usedFallback = true, hasAlternateUrl = true
            )
            true
        } else {
            accountRepo.clearEasClientCache(accountId)
            reportFailure(accountId, hasAlternate = true,
                errorMessage = (result as? EasResult.Error)?.message ?: lastError,
                exception = null, usedFallback = true)
            false
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        accountRepo.clearEasClientCache(accountId)
        reportFailure(accountId, hasAlternate = true, errorMessage = lastError, exception = e, usedFallback = true)
        false
    }
}

private fun reportFailure(
    accountId: Long,
    hasAlternate: Boolean,
    errorMessage: String?,
    exception: Throwable?,
    usedFallback: Boolean
) {
    val kind = classifyError(errorMessage, exception)
    AccountServerHealthRepository.reportSyncOutcome(
        accountId = accountId,
        success = false,
        errorKind = kind,
        errorMessage = errorMessage ?: exception?.message,
        usedFallback = usedFallback,
        hasAlternateUrl = hasAlternate
    )
}

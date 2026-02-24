package com.dedovmosol.iwomail.sync

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
 * 3. CancellationException ВСЕГДА пробрасывается (Kotlin docs: rethrow for cancellation propagation)
 *
 * @param allowFullResync если false — при ошибке инкрементального sync НЕ делаем тяжёлый full resync.
 *   Используется для пользовательских папок сверх лимита: они получают быстрый инкрементальный sync,
 *   но не блокируют Worker тяжёлым full resync (до 280с/папка).
 * @return true если синхронизация прошла без ошибок
 */
suspend fun syncFolderWithRetry(
    mailRepo: MailRepository,
    accountId: Long,
    folderId: String,
    incrementalTimeoutMs: Long = 60_000L,
    fullResyncTimeoutMs: Long = 300_000L,
    allowFullResync: Boolean = true,
    tag: String = "SyncHelper"
): Boolean {
    try {
        // 1. Инкрементальный delta-sync (экономит трафик/батарею)
        val result = withTimeoutOrNull(incrementalTimeoutMs) {
            mailRepo.syncEmails(accountId, folderId, forceFullSync = false)
        }
        if (result == null || result is EasResult.Error) {
            if (!allowFullResync) {
                // Лимит full resync: не тратим 280с на эту папку, синхронизируем в следующем цикле
                return false
            }
            // Таймаут или ошибка — fallback на полный resync
            if (result == null) {
                android.util.Log.w(tag, "Incremental sync timeout for folder $folderId, falling back to full resync")
            }
            // 2. Полный resync (3000+ писем могут потребовать больше времени)
            val retryResult = withTimeoutOrNull(fullResyncTimeoutMs) {
                mailRepo.syncEmails(accountId, folderId, forceFullSync = true)
            }
            if (retryResult == null || retryResult is EasResult.Error) {
                return false
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (!allowFullResync) return false
        // 3. При исключении тоже пробуем полный resync
        try {
            withTimeoutOrNull(fullResyncTimeoutMs) {
                mailRepo.syncEmails(accountId, folderId, forceFullSync = true)
            }
        } catch (e2: Exception) {
            if (e2 is CancellationException) throw e2
            return false
        }
    }
    return true
}

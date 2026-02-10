package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.imap.ImapClient
import com.dedovmosol.iwomail.pop3.Pop3Client

/**
 * Extension функции для репозиториев
 * Принцип DRY: устранение повторяющихся паттернов создания клиентов
 */

/**
 * Создает EAS клиент или возвращает ошибку
 * Упрощает повторяющийся паттерн:
 * ```
 * val client = accountRepo.createEasClient(accountId) 
 *     ?: return EasResult.Error("Не удалось создать клиент")
 * ```
 * 
 * Использование:
 * ```
 * val client = accountRepo.getEasClientOrError<String>(accountId) { return it }
 * ```
 */
suspend inline fun <T> AccountRepository.getEasClientOrError(
    accountId: Long,
    errorMessage: String = "Не удалось создать клиент",
    onError: (EasResult.Error) -> Nothing
): EasClient {
    return createEasClient(accountId) ?: onError(EasResult.Error(errorMessage))
}

/**
 * Создает IMAP клиент или возвращает ошибку
 */
suspend inline fun <T> AccountRepository.getImapClientOrError(
    accountId: Long,
    errorMessage: String = "Не удалось создать IMAP клиент",
    onError: (EasResult.Error) -> Nothing
): ImapClient {
    return createImapClient(accountId) ?: onError(EasResult.Error(errorMessage))
}

/**
 * Создает POP3 клиент или возвращает ошибку
 */
suspend inline fun <T> AccountRepository.getPop3ClientOrError(
    accountId: Long,
    errorMessage: String = "Не удалось создать POP3 клиент",
    onError: (EasResult.Error) -> Nothing
): Pop3Client {
    return createPop3Client(accountId) ?: onError(EasResult.Error(errorMessage))
}

package com.dedovmosol.iwomail.eas

/**
 * Extension функции для упрощения работы с EasResult.
 * Следуют принципу KISS - делают код более читаемым и лаконичным.
 */

/**
 * Трансформирует успешный результат, оставляя ошибку без изменений.
 * 
 * Пример:
 * ```
 * val result: EasResult<String> = easClient.sync()
 * val mapped: EasResult<Int> = result.mapResult { it.length }
 * ```
 */
inline fun <T, R> EasResult<T>.mapResult(transform: (T) -> R): EasResult<R> = when (this) {
    is EasResult.Success -> EasResult.Success(transform(data))
    is EasResult.Error -> EasResult.Error(message)
}

/**
 * Выполняет действие при успешном результате.
 * Возвращает исходный результат для цепочки вызовов.
 * 
 * Пример:
 * ```
 * easClient.sync()
 *     .onSuccessResult { data -> Log.d("TAG", "Success: $data") }
 *     .onErrorResult { error -> Log.e("TAG", "Error: $error") }
 * ```
 */
inline fun <T> EasResult<T>.onSuccessResult(action: (T) -> Unit): EasResult<T> = apply {
    if (this is EasResult.Success) action(data)
}

/**
 * Выполняет действие при ошибке.
 * Возвращает исходный результат для цепочки вызовов.
 */
inline fun <T> EasResult<T>.onErrorResult(action: (String) -> Unit): EasResult<T> = apply {
    if (this is EasResult.Error) action(message)
}

/**
 * Возвращает данные при успехе или дефолтное значение при ошибке.
 * 
 * Пример:
 * ```
 * val count = easClient.syncEmails().getResultOrElse { 0 }
 * ```
 */
inline fun <T> EasResult<T>.getResultOrElse(default: () -> T): T = when (this) {
    is EasResult.Success -> data
    is EasResult.Error -> default()
}

/**
 * Возвращает данные при успехе или null при ошибке.
 * 
 * Пример:
 * ```
 * val data = easClient.sync().getResultOrNull()
 * if (data != null) { /* обработка */ }
 * ```
 */
fun <T> EasResult<T>.getResultOrNull(): T? = when (this) {
    is EasResult.Success -> data
    is EasResult.Error -> null
}

/**
 * Проверяет, является ли результат успешным.
 */
fun <T> EasResult<T>.isSuccessResult(): Boolean = this is EasResult.Success

/**
 * Проверяет, является ли результат ошибкой.
 */
fun <T> EasResult<T>.isErrorResult(): Boolean = this is EasResult.Error

/**
 * Возвращает сообщение об ошибке или null при успехе.
 */
fun <T> EasResult<T>.errorMessageOrNull(): String? = when (this) {
    is EasResult.Success -> null
    is EasResult.Error -> message
}

/**
 * Трансформирует результат в другой тип с возможностью обработки ошибок.
 * 
 * Пример:
 * ```
 * val result = easClient.sync()
 *     .flatMapResult { data -> easClient.processData(data) }
 * ```
 */
inline fun <T, R> EasResult<T>.flatMapResult(transform: (T) -> EasResult<R>): EasResult<R> = when (this) {
    is EasResult.Success -> transform(data)
    is EasResult.Error -> EasResult.Error(message)
}

/**
 * Восстанавливается от ошибки, возвращая альтернативный результат.
 * 
 * Пример:
 * ```
 * val result = easClient.sync()
 *     .recoverResult { error -> EasResult.Success(emptyList()) }
 * ```
 */
inline fun <T> EasResult<T>.recoverResult(recovery: (String) -> EasResult<T>): EasResult<T> = when (this) {
    is EasResult.Success -> this
    is EasResult.Error -> recovery(message)
}

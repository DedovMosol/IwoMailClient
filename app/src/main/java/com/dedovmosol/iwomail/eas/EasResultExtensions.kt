package com.dedovmosol.iwomail.eas

/**
 * Extension функции для упрощения работы с EasResult.
 */

inline fun <T, R> EasResult<T>.mapResult(transform: (T) -> R): EasResult<R> = when (this) {
    is EasResult.Success -> EasResult.Success(transform(data))
    is EasResult.Error -> EasResult.Error(message)
}

inline fun <T> EasResult<T>.onSuccessResult(action: (T) -> Unit): EasResult<T> = apply {
    if (this is EasResult.Success) action(data)
}

inline fun <T, R> EasResult<T>.flatMapResult(transform: (T) -> EasResult<R>): EasResult<R> = when (this) {
    is EasResult.Success -> transform(data)
    is EasResult.Error -> EasResult.Error(message)
}

private val RETRYABLE_KEYWORDS = arrayOf("Status=", "failed", "error")

fun <T> EasResult<T>.isRetryable(): Boolean =
    this is EasResult.Error && RETRYABLE_KEYWORDS.any { message.contains(it, ignoreCase = true) }

suspend fun <T> withEasRetry(
    delayMs: Long = 1000L,
    action: suspend () -> EasResult<T>
): EasResult<T> {
    var result = action()
    if (result.isRetryable()) {
        kotlinx.coroutines.delay(delayMs)
        result = action()
    }
    return result
}

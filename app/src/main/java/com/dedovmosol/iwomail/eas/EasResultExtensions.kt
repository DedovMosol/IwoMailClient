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

/**
 * DATA-6: терминальные маркеры — повтор бесполезен и вреден (аутентификация/политика/очистка
 * не «починятся» ретраем; их обрабатывает отдельная ветка provision/re-auth). Проверяются ПЕРВЫМИ,
 * чтобы такие ошибки не ретраились, даже если текст случайно содержит retryable-слово.
 */
private val NON_RETRYABLE_MARKERS = arrayOf(
    "UNAUTHORIZED", "ACCESS_DENIED", "PROVISION_REQUIRED", "REMOTE_WIPE"
)

fun <T> EasResult<T>.isRetryable(): Boolean {
    if (this !is EasResult.Error) return false
    if (NON_RETRYABLE_MARKERS.any { message.contains(it, ignoreCase = true) }) return false
    return RETRYABLE_KEYWORDS.any { message.contains(it, ignoreCase = true) }
}

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

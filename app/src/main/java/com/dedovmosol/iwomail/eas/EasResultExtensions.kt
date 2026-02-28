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

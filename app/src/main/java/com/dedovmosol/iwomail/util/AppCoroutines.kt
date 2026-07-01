package com.dedovmosol.iwomail.util

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Создаёт долгоживущий [CoroutineScope] на `SupervisorJob` + [CoroutineExceptionHandler].
 *
 * Зачем оба (проверено по kotlinx.coroutines docs):
 * - `SupervisorJob` — сбой одного `launch` НЕ отменяет соседей;
 * - `CoroutineExceptionHandler` — сам `SupervisorJob` НЕ ловит краш: непойманное исключение из
 *   `launch` (в т.ч. `Error`/`OutOfMemoryError`, которые `catch(Exception)` не ловит) иначе
 *   уронит процесс. Handler срабатывает, т.к. `launch` — прямой child этого scope.
 *
 * Единая замена ручных `CoroutineScope(SupervisorJob() + dispatcher)` без обработчика
 * (app-scope, push/sync-сервисы, ресиверы, репозитории) — DRY, «краши недопустимы».
 * Аналог UI-стороны — `rememberSafeScope`/`rememberSyncScope` в `ComposableUtils`.
 */
fun supervisedScope(dispatcher: CoroutineDispatcher, tag: String = "AppScope"): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, e -> Log.e(tag, "Unhandled coroutine error", e) }
    )

/**
 * [CoroutineExceptionHandler] для `viewModelScope.launch(handler) { … }`.
 *
 * `viewModelScope` = `SupervisorJob() + Dispatchers.Main.immediate` **без** handler'а (проверено —
 * androidx lifecycle): непойманное исключение из `launch` (напр. ошибка Room-Flow в реактивном
 * `collect`) иначе уронит процесс. Передаём этот handler в наблюдающие (`collect`) launch'и, где
 * inline `try/catch` неудобен (вложенные потоки) — CR-2. Одноразовые mutation-launch'и остаются
 * на паттерне `try/catch(CancellationException){throw}` (M-1).
 */
fun loggingExceptionHandler(tag: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e -> Log.e(tag, "Unhandled coroutine error", e) }

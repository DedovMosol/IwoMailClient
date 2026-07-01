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

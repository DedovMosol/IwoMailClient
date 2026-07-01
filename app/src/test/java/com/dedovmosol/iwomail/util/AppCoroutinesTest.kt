package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

/**
 * Юнит-тесты `supervisedScope` — крах-безопасного долгоживущего scope (класс UI-1 вне UI).
 *
 * Проверяем ключевое свойство: `SupervisorJob` + `CoroutineExceptionHandler` вместе — сбой одного
 * `launch` (непойманное исключение, в т.ч. из репозитория/сервиса) НЕ отменяет соседей и НЕ
 * пробрасывается наружу (иначе — краш процесса). `android.util.Log` в handler'е под
 * `unitTests.isReturnDefaultValues = true` — no-op.
 */
class AppCoroutinesTest {

    @Test
    fun `failing launch does not cancel siblings and does not propagate`() = runBlocking {
        val scope = supervisedScope(Dispatchers.Default, "test")
        val siblingDone = CompletableDeferred<Boolean>()

        scope.launch { throw RuntimeException("boom") } // handler ловит, scope жив
        scope.launch {
            delay(50)
            siblingDone.complete(true)
        }

        // Если бы был обычный Job / не было handler'а — сбой отменил бы scope → сосед бы не завершился.
        assertThat(withTimeoutOrNull(2_000) { siblingDone.await() }).isTrue()
        scope.cancel()
    }

    @Test
    fun `scope carries a CoroutineExceptionHandler`() {
        val scope = supervisedScope(Dispatchers.Unconfined, "test")
        assertThat(scope.coroutineContext[CoroutineExceptionHandler]).isNotNull()
        scope.cancel()
    }

    @Test
    fun `loggingExceptionHandler on a supervisor-scope launch swallows an uncaught error (CR-2)`() = runBlocking {
        // Паттерн CR-2: launch(handler) на scope с SupervisorJob (как viewModelScope).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val siblingDone = CompletableDeferred<Boolean>()
        scope.launch(loggingExceptionHandler("test")) { throw RuntimeException("boom") }
        scope.launch { delay(50); siblingDone.complete(true) }
        assertThat(withTimeoutOrNull(2_000) { siblingDone.await() }).isTrue()
        scope.cancel()
    }
}

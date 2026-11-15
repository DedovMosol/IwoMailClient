package com.dedovmosol.iwomail.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Корутины, которые не должны умирать вместе с композицией (поворот экрана).
 * Не замена [ComposeViewModel]: только scope. Отправка и сохранение черновика
 * в [com.dedovmosol.iwomail.ui.screens.ComposeScreen] живут здесь.
 */
class RetainScopeViewModel : ViewModel() {
    fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(
            CoroutineExceptionHandler { _, e ->
                android.util.Log.e(TAG, "Unhandled coroutine error", e)
            },
            block = block
        )

    companion object {
        private const val TAG = "RetainScope"
    }
}

package com.dedovmosol.iwomail.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Корутины и флаги отправки/черновика, которые переживают поворот.
 * Флаги нельзя держать в remember: finally старого Job писал в мёртвый state,
 * новый экран залипал в «отправка» или давал вторую отправку.
 */
class RetainScopeViewModel : ViewModel() {
    var sending by mutableStateOf(false)
    var savingDraft by mutableStateOf(false)
    var leaving by mutableStateOf(false)
    var hideEditor by mutableStateOf(false)

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

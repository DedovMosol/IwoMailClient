package com.dedovmosol.iwomail.ui.components

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import com.dedovmosol.iwomail.data.security.AppLockManager

/**
 * Пикер/SAF/внешний VIEW: перед launch помечаем уход, чтобы ON_STOP процесса не ставил lock.
 */
fun <I> ManagedActivityResultLauncher<I, *>.launchLeavingApp(input: I) {
    AppLockManager.noteLeavingForExternalUi()
    launch(input)
}

@Composable
fun <I, O> rememberLeavingAppLauncher(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): ManagedActivityResultLauncher<I, O> =
    rememberLauncherForActivityResult(contract) { value ->
        AppLockManager.onExternalUiReturned()
        onResult(value)
    }

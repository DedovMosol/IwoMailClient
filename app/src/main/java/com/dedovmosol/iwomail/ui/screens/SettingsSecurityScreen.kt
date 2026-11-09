package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.launch

@Composable
internal fun SettingsSecurityScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val settingsRepo = remember { RepositoryProvider.getSettingsRepository(context) }
    val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle(initialValue = false)
    val appLockBiometricEnabled by settingsRepo.appLockBiometricEnabled.collectAsStateWithLifecycle(
        initialValue = settingsRepo.getAppLockBiometricEnabledSync()
    )
    var lockPasswordSet by remember {
        mutableStateOf(com.dedovmosol.iwomail.data.security.AppLockManager.isPasswordSet())
    }
    var lockDialog by remember { mutableStateOf<LockDialogType?>(null) }
    val biometricAvailable = remember {
        androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }

    when (lockDialog) {
        LockDialogType.SetPassword -> LockPasswordDialog(
            title = Strings.appLockSetPassword,
            requireCurrentPassword = false,
            requireNewPassword = true,
            onCancel = { lockDialog = null },
            onSubmit = { _, newPassword ->
                if (com.dedovmosol.iwomail.data.security.AppLockManager.setPassword(newPassword)) {
                    lockPasswordSet = true
                    lockDialog = null
                    scope.launch { settingsRepo.setAppLockEnabled(true) }
                }
            }
        )
        LockDialogType.ChangePassword -> LockPasswordDialog(
            title = Strings.appLockChangePassword,
            requireCurrentPassword = true,
            requireNewPassword = true,
            onCancel = { lockDialog = null },
            onSubmit = { _, newPassword ->
                com.dedovmosol.iwomail.data.security.AppLockManager.setPassword(newPassword)
                lockDialog = null
            }
        )
        LockDialogType.Disable -> LockPasswordDialog(
            title = Strings.appLockDisable,
            requireCurrentPassword = true,
            requireNewPassword = false,
            hint = Strings.appLockRemoveHint,
            onCancel = { lockDialog = null },
            onSubmit = { _, _ ->
                com.dedovmosol.iwomail.data.security.AppLockManager.removePassword()
                lockPasswordSet = false
                lockDialog = null
                scope.launch {
                    settingsRepo.setAppLockBiometricEnabled(false)
                    settingsRepo.setAppLockEnabled(false)
                }
            }
        )
        null -> Unit
    }

    SettingsPageScaffold(title = Strings.appLockSection, onBackClick = onBackClick) {
        item {
            ListItem(
                headlineContent = { Text(Strings.appLockTitle) },
                supportingContent = { Text(Strings.appLockDesc) },
                leadingContent = { Icon(AppIcons.Lock, null) },
                trailingContent = {
                    Switch(
                        checked = appLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (lockPasswordSet) {
                                    scope.launch { settingsRepo.setAppLockEnabled(true) }
                                } else {
                                    lockDialog = LockDialogType.SetPassword
                                }
                            } else {
                                lockDialog = LockDialogType.Disable
                            }
                        }
                    )
                }
            )
        }
        if (appLockEnabled) {
            item {
                ListItem(
                    headlineContent = { Text(Strings.appLockChangePassword) },
                    leadingContent = { Icon(AppIcons.Security, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { lockDialog = LockDialogType.ChangePassword }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(Strings.appLockFingerprintTitle) },
                    supportingContent = {
                        Text(
                            if (biometricAvailable) Strings.appLockFingerprintDesc
                            else Strings.appLockBiometricUnavailable
                        )
                    },
                    leadingContent = { Icon(AppIcons.Fingerprint, null) },
                    trailingContent = {
                        Switch(
                            checked = appLockBiometricEnabled,
                            enabled = biometricAvailable,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepo.setAppLockBiometricEnabled(enabled) }
                            }
                        )
                    }
                )
            }
        }
    }
}

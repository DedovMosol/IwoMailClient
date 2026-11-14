package com.dedovmosol.iwomail.ui.screens

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                com.dedovmosol.iwomail.data.security.AppLockManager.setPassword(newPassword)
            },
            onSuccess = {
                lockPasswordSet = true
                lockDialog = null
                scope.launch { settingsRepo.setAppLockEnabled(true) }
            }
        )
        LockDialogType.ChangePassword -> LockPasswordDialog(
            title = Strings.appLockChangePassword,
            requireCurrentPassword = true,
            requireNewPassword = true,
            onCancel = { lockDialog = null },
            onSubmit = { _, newPassword ->
                com.dedovmosol.iwomail.data.security.AppLockManager.setPassword(newPassword)
            },
            onSuccess = { lockDialog = null }
        )
        LockDialogType.Disable -> LockPasswordDialog(
            title = Strings.appLockDisable,
            requireCurrentPassword = true,
            requireNewPassword = false,
            hint = Strings.appLockRemoveHint,
            onCancel = { lockDialog = null },
            onSubmit = { _, _ ->
                com.dedovmosol.iwomail.data.security.AppLockManager.removePassword()
                true
            },
            onSuccess = {
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
            SettingsGroup {
                SettingsSwitchRow(
                    title = Strings.appLockTitle,
                    description = Strings.appLockDesc,
                    icon = { Icon(AppIcons.Lock, null) },
                    checked = appLockEnabled,
                    onChange = { enabled ->
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
                if (appLockEnabled) {
                    SettingsNavRow(
                        title = Strings.appLockChangePassword,
                        icon = AppIcons.Security,
                        onClick = { lockDialog = LockDialogType.ChangePassword }
                    )
                    SettingsSwitchRow(
                        title = Strings.appLockFingerprintTitle,
                        description = if (biometricAvailable) Strings.appLockFingerprintDesc
                        else Strings.appLockBiometricUnavailable,
                        icon = { Icon(AppIcons.Fingerprint, null) },
                        checked = appLockBiometricEnabled,
                        enabled = biometricAvailable || appLockBiometricEnabled,
                        onChange = { enabled ->
                            if (enabled && !biometricAvailable) return@SettingsSwitchRow
                            scope.launch { settingsRepo.setAppLockBiometricEnabled(enabled) }
                        }
                    )
                }
            }
        }
    }
}

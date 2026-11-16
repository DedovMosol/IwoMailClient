package com.dedovmosol.iwomail.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToAccounts: () -> Unit = {},
    onNavigateToMail: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToPersonalization: () -> Unit = {},
    onNavigateToSecurity: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNoAccountsLeft: () -> Unit = {}
) {
    val context = LocalContext.current
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val accounts by accountRepo.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val accountCount = accounts.size
    val accountsSubtitle = settingsAccountsSubtitle(
        count = accountCount,
        firstEmail = accounts.firstOrNull()?.email.orEmpty(),
        emptyLabel = Strings.addAccount,
        manyLabel = if (accountCount > 1) Strings.accountsCount(accountCount) else ""
    )

    val colors = MaterialTheme.colorScheme
    SettingsPageScaffold(
        title = Strings.settings,
        onBackClick = {
            if (accounts.isEmpty()) onNoAccountsLeft() else onBackClick()
        }
    ) {
        item {
            SettingsGroup {
                SettingsHubRow(
                    title = Strings.accounts,
                    subtitle = accountsSubtitle,
                    icon = AppIcons.Person,
                    iconContainer = colors.primaryContainer,
                    iconContent = colors.onPrimaryContainer,
                    onClick = onNavigateToAccounts
                )
            }
        }
        item {
            SettingsGroup {
                SettingsHubRow(
                    title = Strings.mailSection,
                    subtitle = Strings.settingsHubMailDesc,
                    icon = AppIcons.Email,
                    iconContainer = colors.tertiaryContainer,
                    iconContent = colors.onTertiaryContainer,
                    onClick = onNavigateToMail,
                    showDivider = true
                )
                SettingsHubRow(
                    title = Strings.mailAlertsSection,
                    subtitle = Strings.settingsHubAlertsDesc,
                    icon = AppIcons.Notifications,
                    iconContainer = colors.errorContainer,
                    iconContent = colors.onErrorContainer,
                    onClick = onNavigateToNotifications
                )
            }
        }
        item {
            SettingsGroup {
                SettingsHubRow(
                    title = Strings.appearance,
                    subtitle = Strings.settingsHubAppearanceDesc,
                    icon = AppIcons.Palette,
                    iconContainer = colors.secondaryContainer,
                    iconContent = colors.onSecondaryContainer,
                    onClick = onNavigateToPersonalization,
                    showDivider = true
                )
                SettingsHubRow(
                    title = Strings.appLockSection,
                    subtitle = Strings.appLockDesc,
                    icon = AppIcons.Lock,
                    iconContainer = colors.primaryContainer,
                    iconContent = colors.onPrimaryContainer,
                    onClick = onNavigateToSecurity
                )
            }
        }
        item {
            SettingsGroup {
                SettingsHubRow(
                    title = Strings.aboutApp,
                    subtitle = Strings.settingsHubAboutDesc,
                    icon = AppIcons.Info,
                    iconContainer = colors.surfaceVariant,
                    iconContent = colors.onSurfaceVariant,
                    onClick = onNavigateToAbout
                )
            }
        }
    }
}

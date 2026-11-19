package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.Strings

@Composable
internal fun SettingsMailScreen(onBackClick: () -> Unit, highlight: String = "") {
    val context = LocalContext.current
    val settingsRepo = remember { RepositoryProvider.getSettingsRepository(context) }
    SettingsPageScaffold(title = Strings.mailSection, onBackClick = onBackClick, hue = SettingsSearchDest.Mail.hue) {
        item {
            SettingsGroup {
                MailSettingsSection(settingsRepo, highlight)
            }
        }
    }
}

@Composable
internal fun SettingsNotificationsScreen(onBackClick: () -> Unit, highlight: String = "") {
    val context = LocalContext.current
    val settingsRepo = remember { RepositoryProvider.getSettingsRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val accounts by accountRepo.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    SettingsPageScaffold(title = Strings.mailAlertsSection, onBackClick = onBackClick, hue = SettingsSearchDest.Alerts.hue) {
        item {
            SettingsGroup {
                MailAlertsSettingsSection(settingsRepo, accounts, highlight)
            }
        }
    }
}

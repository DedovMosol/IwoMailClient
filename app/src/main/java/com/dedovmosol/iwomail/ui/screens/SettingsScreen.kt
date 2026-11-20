package com.dedovmosol.iwomail.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.ScrollbarColor
import com.dedovmosol.iwomail.ui.theme.AppIcons

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToAccounts: () -> Unit = {},
    onNavigateToMail: (String) -> Unit = {},
    onNavigateToNotifications: (String) -> Unit = {},
    onNavigateToPersonalization: (String) -> Unit = {},
    onNavigateToSecurity: (String) -> Unit = {},
    onNavigateToAbout: (String) -> Unit = {},
    onNavigateToAccountSettings: (accountId: Long, focus: String) -> Unit = { _, _ -> },
    onNavigateToSyncCleanup: (accountId: Long, focus: String) -> Unit = { _, _ -> },
    onAddAccount: () -> Unit = onNavigateToAccounts,
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

    val keyboard = LocalSoftwareKeyboardController.current
    val settingsRepo = remember { RepositoryProvider.getSettingsRepository(context) }
    val appLockEnabled by settingsRepo.appLockEnabled.collectAsStateWithLifecycle(initialValue = false)
    val dailyThemesEnabled by settingsRepo.dailyThemesEnabled.collectAsStateWithLifecycle(initialValue = false)
    val images by settingsRepo.remoteImagesPrefs.collectAsStateWithLifecycle(initialValue = null)
    val alerts by settingsRepo.mailAlertPrefs.collectAsStateWithLifecycle(initialValue = null)
    val degraded = remember { usesFallbackStorage(context) }
    val catalog = rememberSettingsCatalog(SettingsSearchState(appLockEnabled, dailyThemesEnabled, accounts,
        trustedSenderCount = images?.trustedSenders?.size ?: 0,
        quietHoursEnabled = alerts?.quietHours?.enabled == true, storageDegraded = degraded))
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query, catalog) { filterSettingsEntries(query, catalog) }
    val searching = query.trim().isNotEmpty()
    BackHandler(enabled = searching) {
        query = ""
        keyboard?.hide()
    }

    fun open(dest: SettingsSearchDest, focus: String = "", account: SettingsAccountTarget? = null) {
        keyboard?.hide()
        if (account != null) {
            if (account.sync) onNavigateToSyncCleanup(account.accountId, focus) else onNavigateToAccountSettings(account.accountId, focus)
            return
        }
        when (dest) {
            SettingsSearchDest.Accounts -> if (focus == "addAccount") onAddAccount() else onNavigateToAccounts()
            SettingsSearchDest.Mail -> onNavigateToMail(focus)
            SettingsSearchDest.Alerts -> onNavigateToNotifications(focus)
            SettingsSearchDest.Appearance -> onNavigateToPersonalization(focus)
            SettingsSearchDest.Security -> onNavigateToSecurity(focus)
            SettingsSearchDest.About -> onNavigateToAbout(focus)
        }
    }

    SettingsPageScaffold(
        title = Strings.settings,
        onBackClick = {
            if (accounts.isEmpty()) onNoAccountsLeft() else onBackClick()
        }
    ) {
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsTokens.HorizontalPadding, vertical = 8.dp),
                placeholder = { Text(Strings.searchSettings) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(AppIcons.Clear, Strings.clear)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SettingsTokens.cardColor,
                    unfocusedContainerColor = SettingsTokens.cardColor,
                    disabledContainerColor = SettingsTokens.cardColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
        }
        if (searching) {
            if (hits.isEmpty()) {
                item {
                    Text(
                        Strings.noResults,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                }
            } else {
                item {
                    SettingsGroup {
                        hits.forEach { hit ->
                            SettingsHubRow(
                                title = hit.title,
                                subtitle = hit.section,
                                icon = hit.dest.icon,
                                hue = hit.dest.hue,
                                onClick = { open(hit.dest, hit.focus, hit.account) }
                            )
                        }
                    }
                }
            }
        } else {
            item {
                SettingsGroup {
                    SettingsHubRow(
                        title = Strings.accounts,
                        subtitle = accountsSubtitle,
                        icon = SettingsSearchDest.Accounts.icon,
                        hue = SettingsSearchDest.Accounts.hue,
                        onClick = onNavigateToAccounts
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsHubRow(
                        title = Strings.mailSection,
                        subtitle = Strings.settingsHubMailDesc,
                        icon = SettingsSearchDest.Mail.icon,
                        hue = SettingsSearchDest.Mail.hue,
                        onClick = { onNavigateToMail("") }
                    )
                    SettingsHubRow(
                        title = Strings.mailAlertsSection,
                        subtitle = Strings.settingsHubAlertsDesc,
                        icon = SettingsSearchDest.Alerts.icon,
                        hue = SettingsSearchDest.Alerts.hue,
                        onClick = { onNavigateToNotifications("") }
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsHubRow(
                        title = Strings.appearance,
                        subtitle = Strings.settingsHubAppearanceDesc,
                        icon = SettingsSearchDest.Appearance.icon,
                        hue = SettingsSearchDest.Appearance.hue,
                        onClick = { onNavigateToPersonalization("") }
                    )
                    SettingsHubRow(
                        title = Strings.appLockSection,
                        subtitle = Strings.appLockDesc,
                        icon = SettingsSearchDest.Security.icon,
                        hue = SettingsSearchDest.Security.hue,
                        onClick = { onNavigateToSecurity("") }
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsHubRow(
                        title = Strings.aboutApp,
                        subtitle = Strings.settingsHubAboutDesc,
                        icon = SettingsSearchDest.About.icon,
                        hue = SettingsSearchDest.About.hue,
                        onClick = { onNavigateToAbout("") }
                    )
                }
            }
            // Что настраивается у каждого аккаунта отдельно и где: остальное на этих экранах — общее
            item { SettingsInfoNote(Strings.settingsPerAccountHint) }
        }
    }
}

/**
 * Иконка и статичный цвет раздела ([LocalSettingsHue]) — одни на строке корня, в поиске и в плитках самого раздела.
 * Цвета — из палитры скроллбара, не из темы.
 */
internal val SettingsSearchDest.icon: ImageVector
    @Composable get() = when (this) {
        SettingsSearchDest.Accounts -> AppIcons.HubAccounts
        SettingsSearchDest.Mail -> AppIcons.HubMail
        SettingsSearchDest.Alerts -> AppIcons.HubAlerts
        SettingsSearchDest.Appearance -> AppIcons.HubAppearance
        SettingsSearchDest.Security -> AppIcons.HubSecurity
        SettingsSearchDest.About -> AppIcons.HubAbout
    }

internal val SettingsSearchDest.hue: ScrollbarColor
    get() = when (this) {
        SettingsSearchDest.Accounts -> ScrollbarColor.BLUE
        SettingsSearchDest.Mail -> ScrollbarColor.ORANGE
        SettingsSearchDest.Alerts -> ScrollbarColor.RED
        SettingsSearchDest.Appearance -> ScrollbarColor.PURPLE
        SettingsSearchDest.Security -> ScrollbarColor.GREEN
        SettingsSearchDest.About -> ScrollbarColor.GRAY
    }

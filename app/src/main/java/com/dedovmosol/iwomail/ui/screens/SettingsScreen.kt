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
    val catalog = rememberSettingsCatalog()
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query, catalog) { filterSettingsEntries(query, catalog) }
    val searching = query.trim().isNotEmpty()
    BackHandler(enabled = searching) {
        query = ""
        keyboard?.hide()
    }

    fun open(dest: SettingsSearchDest, focus: String = "") {
        keyboard?.hide()
        when (dest) {
            SettingsSearchDest.Accounts -> onNavigateToAccounts()
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(Strings.searchSettings) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(AppIcons.Clear, Strings.cancel)
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
                                icon = destIcon(hit.dest),
                                onClick = { open(hit.dest, hit.focus); keyboard?.hide() }
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
                        icon = AppIcons.Person,
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
                        onClick = { onNavigateToMail("") }
                    )
                    SettingsHubRow(
                        title = Strings.mailAlertsSection,
                        subtitle = Strings.settingsHubAlertsDesc,
                        icon = AppIcons.Notifications,
                        onClick = { onNavigateToNotifications("") }
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsHubRow(
                        title = Strings.appearance,
                        subtitle = Strings.settingsHubAppearanceDesc,
                        icon = AppIcons.Palette,
                        onClick = { onNavigateToPersonalization("") }
                    )
                    SettingsHubRow(
                        title = Strings.appLockSection,
                        subtitle = Strings.appLockDesc,
                        icon = AppIcons.Lock,
                        onClick = { onNavigateToSecurity("") }
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsHubRow(
                        title = Strings.aboutApp,
                        subtitle = Strings.settingsHubAboutDesc,
                        icon = AppIcons.Info,
                        onClick = { onNavigateToAbout("") }
                    )
                }
            }
        }
    }
}

@Composable
private fun destIcon(dest: SettingsSearchDest): ImageVector = when (dest) {
    SettingsSearchDest.Accounts -> AppIcons.Person
    SettingsSearchDest.Mail -> AppIcons.Email
    SettingsSearchDest.Alerts -> AppIcons.Notifications
    SettingsSearchDest.Appearance -> AppIcons.Palette
    SettingsSearchDest.Security -> AppIcons.Lock
    SettingsSearchDest.About -> AppIcons.Info
}

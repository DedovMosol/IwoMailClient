package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.launch

/**
 * Аккаунты в настройках. Нажатие на аккаунт делает его активным и открывает главный экран с его папками
 * ([onAccountOpened]); шестерёнка справа — настройки аккаунта.
 */
@Composable
internal fun SettingsAccountsScreen(
    onBackClick: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountOpened: () -> Unit,
    onNavigateToAccountSettings: (Long) -> Unit
) {
    val context = LocalContext.current
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val accounts by accountRepo.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    // Двойное нажатие — одно переключение и один переход
    var opening by remember { mutableStateOf(false) }

    SettingsPageScaffold(title = Strings.accounts, onBackClick = onBackClick, hue = SettingsSearchDest.Accounts.hue) {
        items(accounts, key = { it.id }) { account ->
            SettingsGroup {
                AccountCard(
                    account = account,
                    onOpenClick = {
                        if (opening) return@AccountCard
                        opening = true
                        scope.launch {
                            try {
                                // Переход — после записи: главный экран сразу показывает папки этого аккаунта
                                accountRepo.setActiveAccount(account.id)
                                onAccountOpened()
                            } finally {
                                opening = false
                            }
                        }
                    },
                    onSettingsClick = { onNavigateToAccountSettings(account.id) }
                )
            }
        }
        item {
            SettingsGroup {
                SettingsHubRow(
                    title = Strings.addAccount,
                    subtitle = null,
                    icon = AppIcons.Add,
                    hue = SettingsSearchDest.Accounts.hue,
                    onClick = onAddAccount
                )
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: AccountEntity,
    onOpenClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val accountType = AccountType.fromStored(account.accountType)
    val accountColor = Color(account.color)
    val glyph = Color(contrastingContentArgb(account.color))
    ListItem(
        modifier = Modifier.clickable(
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = Strings.openAccountMail,
            onClick = onOpenClick
        ),
        headlineContent = {
            Text(
                account.displayName,
                fontWeight = if (account.isActive) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Column {
                Text(account.email)
                Text(
                    accountType.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accountColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = account.displayName.firstOrNull()?.uppercase() ?: "?",
                    color = glyph,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onSettingsClick) {
                Icon(AppIcons.Settings, contentDescription = "${Strings.accountSettings}: ${account.displayName}")
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

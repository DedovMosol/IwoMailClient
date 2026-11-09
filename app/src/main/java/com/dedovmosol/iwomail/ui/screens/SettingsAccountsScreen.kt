package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
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

@Composable
internal fun SettingsAccountsScreen(
    onBackClick: () -> Unit,
    onAddAccount: () -> Unit,
    onNavigateToAccountSettings: (Long) -> Unit,
    onNoAccountsLeft: () -> Unit
) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val accounts by accountRepo.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    var accountToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val accountToDelete = accountToDeleteId?.let { id -> accounts.find { it.id == id } }

    accountToDelete?.let { account ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { accountToDeleteId = null },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteAccount) },
            text = { Text("${account.displayName}: ${Strings.deleteAccountConfirm}") },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        scope.launch {
                            accountRepo.deleteAccount(account.id)
                            accountToDeleteId = null
                            if (accounts.size <= 1) onNoAccountsLeft()
                        }
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { accountToDeleteId = null },
                    text = Strings.cancel
                )
            }
        )
    }

    SettingsPageScaffold(title = Strings.accounts, onBackClick = onBackClick) {
        items(accounts, key = { it.id }) { account ->
            AccountCard(
                account = account,
                onDeleteClick = { accountToDeleteId = account.id },
                onOpenClick = { onNavigateToAccountSettings(account.id) }
            )
        }
        item {
            ListItem(
                headlineContent = { Text(Strings.addAccount) },
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            AppIcons.Add,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onAddAccount)
            )
        }
    }
}

@Composable
private fun AccountCard(
    account: AccountEntity,
    onDeleteClick: () -> Unit,
    onOpenClick: () -> Unit
) {
    val accountType = try {
        AccountType.valueOf(account.accountType)
    } catch (_: Exception) {
        AccountType.EXCHANGE
    }
    val accountColor = try {
        Color(account.color)
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = account.displayName, onClick = onOpenClick),
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
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            trailingContent = {
                Row {
                    Icon(AppIcons.ChevronRight, contentDescription = null)
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            AppIcons.Delete,
                            Strings.delete,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

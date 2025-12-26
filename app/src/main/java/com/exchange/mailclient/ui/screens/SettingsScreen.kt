package com.exchange.mailclient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exchange.mailclient.data.database.AccountEntity
import com.exchange.mailclient.data.database.AccountType
import com.exchange.mailclient.data.database.SyncMode
import com.exchange.mailclient.data.repository.AccountRepository
import com.exchange.mailclient.data.repository.SettingsRepository
import com.exchange.mailclient.sync.SyncWorker
import com.exchange.mailclient.ui.AppLanguage
import com.exchange.mailclient.ui.LocalLanguage
import com.exchange.mailclient.ui.Strings
import com.exchange.mailclient.ui.isRussian
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onAddAccount: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { AccountRepository(context) }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val isRu = isRussian()
    
    val accounts by accountRepo.accounts.collectAsState(initial = emptyList())
    var accountToDelete by remember { mutableStateOf<AccountEntity?>(null) }
    
    // Настройки синхронизации
    val syncOnWifiOnly by settingsRepo.syncOnWifiOnly.collectAsState(initial = false)
    val notificationsEnabled by settingsRepo.notificationsEnabled.collectAsState(initial = true)
    val nightModeEnabled by settingsRepo.nightModeEnabled.collectAsState(initial = false)
    
    // Настройки языка
    val currentLanguage = LocalLanguage.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    
    // Настройки размера шрифта
    val fontSize by settingsRepo.fontSize.collectAsState(initial = SettingsRepository.FontSize.MEDIUM)
    var showFontSizeDialog by remember { mutableStateOf(false) }
    
    // Диалог выбора размера шрифта
    if (showFontSizeDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = { Text(Strings.selectFontSize) },
            text = {
                Column {
                    SettingsRepository.FontSize.entries.forEach { size ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settingsRepo.setFontSize(size)
                                    }
                                    showFontSizeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = fontSize == size,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(size.getDisplayName(isRu))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontSizeDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог выбора языка
    if (showLanguageDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(Strings.selectLanguage) },
            text = {
                Column {
                    AppLanguage.entries.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settingsRepo.setLanguage(lang.code)
                                    }
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLanguage == lang,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(lang.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог подтверждения удаления
    accountToDelete?.let { account ->
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = { Text(Strings.deleteAccount) },
            text = { Text("${account.displayName}: ${Strings.deleteAccountConfirm}") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            accountRepo.deleteAccount(account.id)
                            accountToDelete = null
                        }
                    }
                ) {
                    Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.settings, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, Strings.back, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF7C4DFF),
                            Color(0xFF448AFF)
                        )
                    )
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Text(
                    text = Strings.accounts,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            items(accounts) { account ->
                AccountSettingsItem(
                    account = account,
                    onEditClick = { onEditAccount(account.id) },
                    onDeleteClick = { accountToDelete = account },
                    onSyncModeChange = { mode ->
                        scope.launch {
                            accountRepo.updateSyncMode(account.id, mode)
                            // Перезапускаем сервисы
                            if (mode == SyncMode.PUSH) {
                                com.exchange.mailclient.sync.PushService.start(context)
                            }
                            SyncWorker.scheduleWithNightMode(context)
                        }
                    },
                    onSyncIntervalChange = { minutes ->
                        scope.launch {
                            accountRepo.updateSyncInterval(account.id, minutes)
                            SyncWorker.scheduleWithNightMode(context)
                        }
                    }
                )
            }
            
            // Кнопка добавления аккаунта
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
                                Icons.Default.Add,
                                null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    },
                    modifier = Modifier.clickable(onClick = onAddAccount)
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            item {
                Text(
                    text = Strings.general,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            // Выбор языка
            item {
                ListItem(
                    headlineContent = { Text(Strings.language) },
                    supportingContent = { Text(currentLanguage.displayName) },
                    leadingContent = { Icon(Icons.Default.Language, null) },
                    modifier = Modifier.clickable { showLanguageDialog = true }
                )
            }
            
            // Выбор размера шрифта
            item {
                ListItem(
                    headlineContent = { Text(Strings.fontSize) },
                    supportingContent = { Text(fontSize.getDisplayName(isRu)) },
                    leadingContent = { Icon(Icons.Default.TextFields, null) },
                    modifier = Modifier.clickable { showFontSizeDialog = true }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.wifiOnly) },
                    supportingContent = { 
                        Text(if (syncOnWifiOnly) Strings.wifiOnlyDesc else Strings.anyNetwork) 
                    },
                    leadingContent = { Icon(Icons.Default.Wifi, null) },
                    trailingContent = {
                        Switch(
                            checked = syncOnWifiOnly,
                            onCheckedChange = { wifiOnly ->
                                scope.launch {
                                    settingsRepo.setSyncOnWifiOnly(wifiOnly)
                                    SyncWorker.scheduleWithNightMode(context)
                                }
                            }
                        )
                    }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.nightMode) },
                    supportingContent = { Text(Strings.nightModeDesc) },
                    leadingContent = { Icon(Icons.Default.NightsStay, null) },
                    trailingContent = {
                        Switch(
                            checked = nightModeEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsRepo.setNightModeEnabled(enabled)
                                }
                            }
                        )
                    }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.notifications) },
                    supportingContent = { Text(if (notificationsEnabled) Strings.enabled else Strings.disabled) },
                    leadingContent = { Icon(Icons.Default.Notifications, null) },
                    trailingContent = {
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsRepo.setNotificationsEnabled(enabled)
                                }
                            }
                        )
                    }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            item {
                Text(
                    text = Strings.aboutApp,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text("iwo Mail Client") },
                    supportingContent = { Text("${Strings.version} 1.0.6c") },
                    leadingContent = { Icon(Icons.Default.Info, null) }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.developer) },
                    supportingContent = { Text("DedovMosol") },
                    leadingContent = { Icon(Icons.Default.Person, null) }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.supportedProtocols) },
                    supportingContent = { Text("Exchange (EAS), IMAP, POP3") },
                    leadingContent = { Icon(Icons.Default.Business, null) }
                )
            }
        }
    }
}

@Composable
private fun AccountSettingsItem(
    account: AccountEntity,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSyncModeChange: (SyncMode) -> Unit,
    onSyncIntervalChange: (Int) -> Unit
) {
    val isRu = isRussian()
    var showSyncModeDialog by remember { mutableStateOf(false) }
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
    
    val accountType = try {
        AccountType.valueOf(account.accountType)
    } catch (_: Exception) {
        AccountType.EXCHANGE
    }
    
    val syncMode = try {
        SyncMode.valueOf(account.syncMode)
    } catch (_: Exception) {
        SyncMode.PUSH
    }
    
    // Диалог выбора режима синхронизации (только для Exchange)
    if (showSyncModeDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showSyncModeDialog = false },
            title = { Text(Strings.syncMode) },
            text = {
                Column {
                    SyncMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSyncModeChange(mode)
                                    showSyncModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = syncMode == mode,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(mode.getDisplayName(isRu))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncModeDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог выбора интервала синхронизации
    if (showSyncIntervalDialog) {
        val intervals = listOf(1, 2, 3, 5, 10, 15, 30)
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showSyncIntervalDialog = false },
            title = { Text(Strings.syncInterval) },
            text = {
                Column {
                    intervals.forEach { minutes ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSyncIntervalChange(minutes)
                                    showSyncIntervalDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = account.syncIntervalMinutes == minutes,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(Strings.minutes(minutes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncIntervalDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            // Заголовок аккаунта
            ListItem(
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
                            .background(Color(account.color)),
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
                        IconButton(onClick = onEditClick) {
                            Icon(Icons.Default.Edit, Strings.edit)
                        }
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                Icons.Default.Delete, 
                                Strings.delete,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
            
            // Настройки синхронизации для Exchange
            if (accountType == AccountType.EXCHANGE) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                
                // Режим синхронизации
                ListItem(
                    headlineContent = { Text(Strings.syncMode, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text(syncMode.getDisplayName(isRu), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.Sync, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.clickable { showSyncModeDialog = true }
                )
                
                // Интервал синхронизации (только для SCHEDULED режима)
                if (syncMode == SyncMode.SCHEDULED) {
                    ListItem(
                        headlineContent = { Text(Strings.syncInterval, style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text(Strings.minutes(account.syncIntervalMinutes), style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(Icons.Default.Schedule, null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.clickable { showSyncIntervalDialog = true }
                    )
                }
            } else {
                // Для IMAP/POP3 — только интервал (Push не поддерживается)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text(Strings.syncInterval, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text(Strings.minutes(account.syncIntervalMinutes), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Default.Schedule, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.clickable { showSyncIntervalDialog = true }
                )
            }
        }
    }
}


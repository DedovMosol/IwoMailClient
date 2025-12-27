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
import com.exchange.mailclient.ui.theme.LocalColorTheme
import com.exchange.mailclient.ui.theme.AppColorTheme
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
    
    // Настройки цветовой темы
    val colorThemeCode by settingsRepo.colorTheme.collectAsState(initial = "purple")
    val dailyThemesEnabled by settingsRepo.dailyThemesEnabled.collectAsState(initial = false)
    val animationsEnabled by settingsRepo.animationsEnabled.collectAsState(initial = true)
    var showColorThemeDialog by remember { mutableStateOf(false) }
    var showDailyThemesDialog by remember { mutableStateOf(false) }
    
    // Темы по дням недели
    val mondayTheme by settingsRepo.getDayTheme(java.util.Calendar.MONDAY).collectAsState(initial = "purple")
    val tuesdayTheme by settingsRepo.getDayTheme(java.util.Calendar.TUESDAY).collectAsState(initial = "blue")
    val wednesdayTheme by settingsRepo.getDayTheme(java.util.Calendar.WEDNESDAY).collectAsState(initial = "green")
    val thursdayTheme by settingsRepo.getDayTheme(java.util.Calendar.THURSDAY).collectAsState(initial = "orange")
    val fridayTheme by settingsRepo.getDayTheme(java.util.Calendar.FRIDAY).collectAsState(initial = "red")
    val saturdayTheme by settingsRepo.getDayTheme(java.util.Calendar.SATURDAY).collectAsState(initial = "pink")
    val sundayTheme by settingsRepo.getDayTheme(java.util.Calendar.SUNDAY).collectAsState(initial = "yellow")
    
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
    
    // Диалог выбора цветовой темы
    if (showColorThemeDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showColorThemeDialog = false },
            title = { Text(Strings.selectColorTheme) },
            text = {
                Column {
                    AppColorTheme.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settingsRepo.setColorTheme(theme.code)
                                    }
                                    showColorThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = colorThemeCode == theme.code,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(theme.gradientStart)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(getThemeDisplayName(theme, isRu))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorThemeDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог настройки тем по дням недели
    if (showDailyThemesDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDailyThemesDialog = false },
            title = { Text(Strings.configureDailyThemes) },
            text = {
                Column {
                    DayThemeRow(Strings.monday, mondayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.MONDAY, theme) }
                    }
                    DayThemeRow(Strings.tuesday, tuesdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.TUESDAY, theme) }
                    }
                    DayThemeRow(Strings.wednesday, wednesdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.WEDNESDAY, theme) }
                    }
                    DayThemeRow(Strings.thursday, thursdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.THURSDAY, theme) }
                    }
                    DayThemeRow(Strings.friday, fridayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.FRIDAY, theme) }
                    }
                    DayThemeRow(Strings.saturday, saturdayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.SATURDAY, theme) }
                    }
                    DayThemeRow(Strings.sunday, sundayTheme, isRu) { theme ->
                        scope.launch { settingsRepo.setDayTheme(java.util.Calendar.SUNDAY, theme) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDailyThemesDialog = false }) {
                    Text(Strings.done)
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
                            LocalColorTheme.current.gradientStart,
                            LocalColorTheme.current.gradientEnd
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
                    },
                    onSignatureChange = { signature ->
                        scope.launch {
                            accountRepo.updateSignature(account.id, signature)
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
            
            // === СЕКЦИЯ: ВНЕШНИЙ ВИД ===
            item {
                Text(
                    text = Strings.appearance,
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
            
            // Выбор цветовой темы (неактивно если включены темы по дням)
            item {
                val currentTheme = AppColorTheme.fromCode(colorThemeCode)
                val isEnabled = !dailyThemesEnabled
                ListItem(
                    headlineContent = { 
                        Text(
                            Strings.colorTheme,
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurface 
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        ) 
                    },
                    supportingContent = { 
                        Text(
                            if (dailyThemesEnabled) Strings.dailyThemesActive else getThemeDisplayName(currentTheme, isRu),
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant 
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        ) 
                    },
                    leadingContent = { 
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isEnabled) currentTheme.gradientStart 
                                    else currentTheme.gradientStart.copy(alpha = 0.38f)
                                )
                        )
                    },
                    modifier = if (isEnabled) Modifier.clickable { showColorThemeDialog = true } else Modifier
                )
            }
            
            // Темы по дням недели
            item {
                ListItem(
                    headlineContent = { Text(Strings.dailyThemes) },
                    supportingContent = { Text(Strings.dailyThemesDesc) },
                    leadingContent = { Icon(Icons.Default.CalendarMonth, null) },
                    trailingContent = {
                        Switch(
                            checked = dailyThemesEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsRepo.setDailyThemesEnabled(enabled)
                                }
                            }
                        )
                    }
                )
            }
            
            // Настройка тем по дням (показывается только если включено)
            if (dailyThemesEnabled) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.configureDailyThemes) },
                        leadingContent = { Icon(Icons.Default.Settings, null) },
                        modifier = Modifier.clickable { showDailyThemesDialog = true }
                    )
                }
            }
            
            // Анимации интерфейса
            item {
                ListItem(
                    headlineContent = { Text(Strings.animations) },
                    supportingContent = { Text(Strings.animationsDesc) },
                    leadingContent = { Icon(Icons.Default.Animation, null) },
                    trailingContent = {
                        Switch(
                            checked = animationsEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsRepo.setAnimationsEnabled(enabled)
                                }
                            }
                        )
                    }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // === СЕКЦИЯ: СИНХРОНИЗАЦИЯ ===
            item {
                Text(
                    text = Strings.syncSettings,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
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
            
            // Автоочистка корзины
            item {
                val autoEmptyDays by settingsRepo.autoEmptyTrashDays.collectAsState(initial = 30)
                var showAutoEmptyDialog by remember { mutableStateOf(false) }
                val currentOption = SettingsRepository.AutoEmptyTrashDays.fromDays(autoEmptyDays)
                
                if (showAutoEmptyDialog) {
                    com.exchange.mailclient.ui.theme.ScaledAlertDialog(
                        onDismissRequest = { showAutoEmptyDialog = false },
                        title = { Text(Strings.autoEmptyTrash) },
                        text = {
                            Column {
                                SettingsRepository.AutoEmptyTrashDays.entries.forEach { option ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch {
                                                    settingsRepo.setAutoEmptyTrashDays(option.days)
                                                }
                                                showAutoEmptyDialog = false
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = autoEmptyDays == option.days,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(option.getDisplayName(isRu))
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAutoEmptyDialog = false }) {
                                Text(Strings.cancel)
                            }
                        }
                    )
                }
                
                ListItem(
                    headlineContent = { Text(Strings.autoEmptyTrash) },
                    supportingContent = { Text(currentOption.getDisplayName(isRu)) },
                    leadingContent = { Icon(Icons.Default.AutoDelete, null) },
                    modifier = Modifier.clickable { showAutoEmptyDialog = true }
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
                    headlineContent = { Text("Exchange Mail Client") },
                    supportingContent = { Text("${Strings.version} 1.0.8") },
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
            
            // Ссылка на политику конфиденциальности
            item {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                ListItem(
                    headlineContent = { Text(Strings.privacyPolicy) },
                    leadingContent = { Icon(Icons.Default.Policy, null) },
                    trailingContent = { Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/DedovMosol/ExchangeMailClient/blob/main/PRIVACY_POLICY.md")
                    }
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
    onSyncIntervalChange: (Int) -> Unit,
    onSignatureChange: (String) -> Unit
) {
    val isRu = isRussian()
    var showSyncModeDialog by remember { mutableStateOf(false) }
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var signatureText by remember(account.signature) { mutableStateOf(account.signature) }
    
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
    
    // Диалог редактирования подписи
    if (showSignatureDialog) {
        com.exchange.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            title = { Text(Strings.editSignature) },
            text = {
                OutlinedTextField(
                    value = signatureText,
                    onValueChange = { signatureText = it },
                    label = { Text(Strings.signatureHint) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSignatureChange(signatureText)
                        showSignatureDialog = false
                    }
                ) {
                    Text(Strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    signatureText = account.signature
                    showSignatureDialog = false 
                }) {
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
            
            // Подпись (для всех типов аккаунтов)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(Strings.signature, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = { 
                    Text(
                        if (account.signature.isBlank()) Strings.noSignature else account.signature.take(50) + if (account.signature.length > 50) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    ) 
                },
                leadingContent = { Icon(Icons.Default.Draw, null, modifier = Modifier.size(20.dp)) },
                modifier = Modifier.clickable { showSignatureDialog = true }
            )
        }
    }
}

/**
 * Получить локализованное название темы
 */
@Composable
private fun getThemeDisplayName(theme: AppColorTheme, isRu: Boolean): String {
    return when (theme) {
        AppColorTheme.PURPLE -> Strings.themePurple
        AppColorTheme.BLUE -> Strings.themeBlue
        AppColorTheme.RED -> Strings.themeRed
        AppColorTheme.YELLOW -> Strings.themeYellow
        AppColorTheme.ORANGE -> Strings.themeOrange
        AppColorTheme.GREEN -> Strings.themeGreen
        AppColorTheme.PINK -> Strings.themePink
    }
}

/**
 * Строка выбора темы для дня недели
 */
@Composable
private fun DayThemeRow(
    dayName: String,
    currentThemeCode: String,
    isRu: Boolean,
    onThemeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentTheme = AppColorTheme.fromCode(currentThemeCode)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Название дня - фиксированная ширина
        Text(
            text = dayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp)
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Кружок с цветом - выровнен по правому краю
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(currentTheme.gradientStart)
        )
        
        // Название темы - фиксированная ширина для выравнивания
        Text(
            text = getThemeDisplayName(currentTheme, isRu),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(100.dp)
                .padding(start = 8.dp)
        )
        
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AppColorTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(theme.gradientStart)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(getThemeDisplayName(theme, isRu))
                        }
                    },
                    onClick = {
                        onThemeSelected(theme.code)
                        expanded = false
                    }
                )
            }
        }
    }
}


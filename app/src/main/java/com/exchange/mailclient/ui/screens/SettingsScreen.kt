package com.exchange.mailclient.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    onAddAccount: () -> Unit = {},
    onNavigateToPersonalization: () -> Unit = {}
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
    val ignoreBatterySaver by settingsRepo.ignoreBatterySaver.collectAsState(initial = false)
    
    // Проверяем активен ли Battery Saver
    val isBatterySaverActive = remember { settingsRepo.isBatterySaverActive() }
    
    // Диалог подтверждения удаления
    accountToDelete?.let { account ->
        com.exchange.mailclient.ui.theme.StyledAlertDialog(
            onDismissRequest = { accountToDelete = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(Strings.deleteAccount) },
            text = { 
                Column {
                    Text("${account.displayName}: ${Strings.deleteAccountConfirm}")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Кнопки в разных сторонах
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { accountToDelete = null }) {
                            Text(Strings.cancel)
                        }
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
                    }
                }
            },
            confirmButton = {}
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
                            } else {
                                // При смене на SCHEDULED останавливаем PushService
                                com.exchange.mailclient.sync.PushService.stop(context)
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
                    },
                    onCertificateChange = { newPath ->
                        scope.launch {
                            accountRepo.updateCertificatePath(account.id, newPath)
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
            
            // Персонализация интерфейса - открывает отдельный экран
            item {
                ListItem(
                    headlineContent = { Text(Strings.interfacePersonalization) },
                    supportingContent = { Text(Strings.interfacePersonalizationDesc) },
                    leadingContent = { Icon(Icons.Default.Palette, null) },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, null)
                    },
                    modifier = Modifier.clickable { onNavigateToPersonalization() }
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
            
            // Игнорировать режим экономии батареи
            item {
                ListItem(
                    headlineContent = { Text(Strings.ignoreBatterySaver) },
                    leadingContent = { Icon(Icons.Default.BatterySaver, null) },
                    trailingContent = {
                        Switch(
                            checked = ignoreBatterySaver,
                            onCheckedChange = { ignore ->
                                scope.launch {
                                    settingsRepo.setIgnoreBatterySaver(ignore)
                                    SyncWorker.scheduleWithNightMode(context)
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
                    supportingContent = { Text("${Strings.version} 1.1.2") },
                    leadingContent = { Icon(Icons.Default.Info, null) }
                )
            }
            
            item {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                ListItem(
                    headlineContent = { Text(Strings.developer) },
                    supportingContent = { Text("DedovMosol") },
                    leadingContent = { Icon(Icons.Default.Person, null) },
                    trailingContent = { Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/DedovMosol/")
                    }
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
    onSignatureChange: (String) -> Unit,
    onCertificateChange: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRu = isRussian()
    var showSyncModeDialog by remember { mutableStateOf(false) }
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showCertificateDialog by remember { mutableStateOf(false) }
    var signatureText by remember(account.signature) { mutableStateOf(account.signature) }
    
    // Получаем имя файла сертификата для экспорта
    val certFileName = account.certificatePath?.let { java.io.File(it).name } ?: "certificate.cer"
    
    // Пикер для экспорта сертификата (пользователь выбирает куда сохранить)
    val exportCertificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { destUri ->
            scope.launch {
                try {
                    account.certificatePath?.let { certPath ->
                        val certFile = java.io.File(certPath)
                        if (certFile.exists()) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                context.contentResolver.openOutputStream(destUri)?.use { output ->
                                    certFile.inputStream().use { input ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (isRu) "Сертификат экспортирован" else "Certificate exported",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        if (isRu) "Ошибка экспорта" else "Export error",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
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
    
    // Пикер для замены сертификата
    val certificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    val validExtensions = listOf("cer", "crt", "pem", "der", "p12", "pfx", "p7b", "p7c")
                    var originalFileName: String? = null
                    val cursor = context.contentResolver.query(selectedUri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                originalFileName = it.getString(nameIndex)
                            }
                        }
                    }
                    
                    val extension = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                    if (extension !in validExtensions) {
                        android.widget.Toast.makeText(
                            context,
                            if (isRu) "Неверный формат файла" else "Invalid file format",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    
                    // Удаляем старый сертификат
                    account.certificatePath?.let { oldPath ->
                        try { java.io.File(oldPath).delete() } catch (_: Exception) {}
                    }
                    
                    // Копируем новый
                    val fileName = "cert_${account.id}_${System.currentTimeMillis()}.$extension"
                    val certFile = java.io.File(context.filesDir, fileName)
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            certFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    onCertificateChange(certFile.absolutePath)
                    android.widget.Toast.makeText(
                        context,
                        if (isRu) "Сертификат обновлён" else "Certificate updated",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        if (isRu) "Ошибка загрузки сертификата" else "Certificate loading error",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    // Диалог управления сертификатом
    if (showCertificateDialog && !account.certificatePath.isNullOrBlank()) {
        val certFile = java.io.File(account.certificatePath!!)
        val certFileName = certFile.name
        val certFileSize = if (certFile.exists()) "${certFile.length() / 1024} KB" else "—"
        
        com.exchange.mailclient.ui.theme.StyledAlertDialog(
            onDismissRequest = { showCertificateDialog = false },
            icon = { Icon(Icons.Default.Lock, null) },
            title = { Text(Strings.serverCertificate) },
            text = {
                Column {
                    // Информация о сертификате
                    Text(
                        if (isRu) "Файл:" else "File:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(certFileName, style = MaterialTheme.typography.bodyMedium)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        if (isRu) "Размер:" else "Size:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(certFileSize, style = MaterialTheme.typography.bodyMedium)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Кнопки действий
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Экспортировать
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                exportCertificatePicker.launch(certFileName)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRu) "Экспортировать" else "Export")
                        }
                        
                        // Заменить
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                certificatePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRu) "Заменить" else "Replace")
                        }
                        
                        // Удалить
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                // Удаляем файл
                                try { certFile.delete() } catch (_: Exception) {}
                                onCertificateChange(null)
                                android.widget.Toast.makeText(
                                    context,
                                    if (isRu) "Сертификат удалён" else "Certificate removed",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRu) "Удалить" else "Remove")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCertificateDialog = false }) {
                    Text(Strings.close)
                }
            }
        )
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
                Column {
                    OutlinedTextField(
                        value = signatureText,
                        onValueChange = { signatureText = it },
                        label = { Text(Strings.signatureHint) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        maxLines = 6
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Кнопки в разных сторонах
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { 
                            signatureText = account.signature
                            showSignatureDialog = false 
                        }) {
                            Text(Strings.cancel)
                        }
                        TextButton(
                            onClick = {
                                onSignatureChange(signatureText)
                                showSignatureDialog = false
                            }
                        ) {
                            Text(Strings.save)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
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
                        // Показываем сертификат если есть (кликабельный)
                        if (!account.certificatePath.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { showCertificateDialog = true }
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = Strings.serverCertificate,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
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


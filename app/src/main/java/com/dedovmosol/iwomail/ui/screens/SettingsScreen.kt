package com.dedovmosol.iwomail.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.util.HtmlRegex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onAddAccount: () -> Unit = {},
    onNavigateToPersonalization: () -> Unit = {},
    onNavigateToAccountSettings: (Long) -> Unit = {},
    onNoAccountsLeft: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val isRu = isRussian()
    
    val accounts by accountRepo.accounts.collectAsState(initial = emptyList())
    var accountToDelete by remember { mutableStateOf<AccountEntity?>(null) }
    
    // Диалог подтверждения удаления — стиль единый с удалением писем, событий, задач и т.д.
    accountToDelete?.let { account ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { accountToDelete = null },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteAccount) },
            text = { Text("${account.displayName}: ${Strings.deleteAccountConfirm}") },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        scope.launch {
                            accountRepo.deleteAccount(account.id)
                            accountToDelete = null
                        }
                    },
                    text = Strings.delete
                )
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
                    IconButton(onClick = {
                        if (accounts.isEmpty()) {
                            onNoAccountsLeft()
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
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
            
            items(accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    onDeleteClick = { accountToDelete = account },
                    onSettingsClick = { onNavigateToAccountSettings(account.id) }
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
                                AppIcons.Add,
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
                    leadingContent = { Icon(AppIcons.Palette, null) },
                    trailingContent = {
                        Icon(AppIcons.ChevronRight, null)
                    },
                    modifier = Modifier.clickable { onNavigateToPersonalization() }
                )
            }
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // О приложении - открывает отдельный экран
            item {
                ListItem(
                    headlineContent = { Text(Strings.aboutApp) },
                    supportingContent = { Text("${Strings.version} ${com.dedovmosol.iwomail.BuildConfig.VERSION_NAME}") },
                    leadingContent = { Icon(AppIcons.Info, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { onNavigateToAbout() }
                )
            }
        }
    }
}

/**
 * Компактная карточка аккаунта с кнопкой настроек
 */
@Composable
private fun AccountCard(
    account: AccountEntity,
    onDeleteClick: () -> Unit,
    onSettingsClick: () -> Unit
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
                        // Показываем сертификат если есть (кликабельный — ведёт в настройки аккаунта)
                        if (!account.certificatePath.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { onSettingsClick() }
                            ) {
                                Icon(
                                    AppIcons.Lock,
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
                                    AppIcons.ChevronRight,
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
                        IconButton(onClick = onSettingsClick) {
                            Icon(AppIcons.Settings, Strings.accountSettings)
                        }
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                AppIcons.Delete, 
                                Strings.delete,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier.padding(horizontal = 0.dp)
            )
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
    onCertificateChange: (String?) -> Unit = {},
    onAutoCleanupTrashChange: (Int) -> Unit = {},
    onAutoCleanupDraftsChange: (Int) -> Unit = {},
    onAutoCleanupSpamChange: (Int) -> Unit = {},
    onContactsSyncIntervalChange: (Int) -> Unit = {}
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
                                NotificationStrings.getCertificateExported(isRu),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        NotificationStrings.getExportError(isRu),
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
    
    val accountColor = try {
        Color(account.color)
    } catch (_: Exception) {
        MaterialTheme.colorScheme.primary
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
                            NotificationStrings.getInvalidFileFormat(isRu),
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
                        NotificationStrings.getCertificateUpdated(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        NotificationStrings.getCertificateLoadingError(isRu),
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
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showCertificateDialog = false },
            icon = { Icon(AppIcons.Lock, null) },
            title = { Text(Strings.serverCertificate) },
            text = {
                Column {
                    // Информация о сертификате
                    Text(
                        Strings.fileLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(certFileName, style = MaterialTheme.typography.bodyMedium)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        Strings.sizeLabel,
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
                            Icon(AppIcons.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(Strings.exportAction)
                        }
                        
                        // Заменить
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                certificatePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(Strings.replaceAction)
                        }
                        
                        // Удалить
                        var showDeleteConfirm by remember { mutableStateOf(false) }
                        
                        if (showDeleteConfirm) {
                            com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                icon = { Icon(AppIcons.Warning, null, tint = MaterialTheme.colorScheme.error) },
                                title = { Text(Strings.removeCertificateTitle) },
                                text = {
                                    Text(Strings.removeCertificateWarning)
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showDeleteConfirm = false
                                            showCertificateDialog = false
                                            // Удаляем файл
                                            try { certFile.delete() } catch (_: Exception) {}
                                            onCertificateChange(null)
                                            android.widget.Toast.makeText(
                                                context,
                                                NotificationStrings.getCertificateRemoved(isRu),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    ) {
                                        Text(Strings.removeAction, color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirm = false }) {
                                        Text(Strings.cancel)
                                    }
                                }
                            )
                        }
                        
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(Strings.removeAction)
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
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
                                    AppIcons.Lock,
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
                                    AppIcons.ChevronRight,
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
                        IconButton(onClick = onEditClick) {
                            Icon(AppIcons.Edit, Strings.edit)
                        }
                        IconButton(onClick = onDeleteClick) {
                            Icon(
                                AppIcons.Delete, 
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
                    leadingContent = { Icon(AppIcons.Sync, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.clickable { showSyncModeDialog = true }
                )
                
                // Интервал синхронизации (только для SCHEDULED режима)
                if (syncMode == SyncMode.SCHEDULED) {
                    ListItem(
                        headlineContent = { Text(Strings.syncInterval, style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text(Strings.minutes(account.syncIntervalMinutes), style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(AppIcons.Schedule, null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.clickable { showSyncIntervalDialog = true }
                    )
                }
            } else {
                // Для IMAP/POP3 — только интервал (Push не поддерживается)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text(Strings.syncInterval, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text(Strings.minutes(account.syncIntervalMinutes), style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(AppIcons.Schedule, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.clickable { showSyncIntervalDialog = true }
                )
            }
            
            // Подписи (несколько для каждого аккаунта)
            var showSignaturesDialog by remember { mutableStateOf(false) }
            var signatures by remember { mutableStateOf<List<com.dedovmosol.iwomail.data.database.SignatureEntity>>(emptyList()) }
            val database = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
            
            // Загружаем подписи
            LaunchedEffect(account.id) {
                signatures = database.signatureDao().getSignaturesByAccountList(account.id)
            }
            
            if (showSignaturesDialog) {
                SignaturesManagementDialog(
                    isRu = isRu,
                    accountId = account.id,
                    signatures = signatures,
                    onSignaturesChanged = { newSignatures ->
                        signatures = newSignatures
                    },
                    onDismiss = { showSignaturesDialog = false }
                )
            }
            
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(Strings.signature, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = { 
                    Text(
                        if (signatures.isEmpty()) Strings.noSignature 
                        else Strings.signaturesCount(signatures.size),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    ) 
                },
                leadingContent = { Icon(AppIcons.Draw, null, modifier = Modifier.size(20.dp)) },
                trailingContent = { Icon(AppIcons.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.clickable { showSignaturesDialog = true }
            )
            
            // Автоматическая очистка
            var showAutoCleanupDialog by remember { mutableStateOf(false) }
            
            if (showAutoCleanupDialog) {
                AutoCleanupDialog(
                    isRu = isRu,
                    trashDays = account.autoCleanupTrashDays,
                    draftsDays = account.autoCleanupDraftsDays,
                    spamDays = account.autoCleanupSpamDays,
                    onTrashDaysChange = onAutoCleanupTrashChange,
                    onDraftsDaysChange = onAutoCleanupDraftsChange,
                    onSpamDaysChange = onAutoCleanupSpamChange,
                    onDismiss = { showAutoCleanupDialog = false }
                )
            }
            
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(Strings.autoCleanup, style = MaterialTheme.typography.bodyMedium) },
                supportingContent = { Text(Strings.autoCleanupDesc, style = MaterialTheme.typography.bodySmall) },
                leadingContent = { Icon(AppIcons.AutoDelete, null, modifier = Modifier.size(20.dp)) },
                trailingContent = { Icon(AppIcons.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.clickable { showAutoCleanupDialog = true }
            )
            
            // Синхронизация контактов (только для Exchange)
            if (accountType == AccountType.EXCHANGE) {
                var showContactsSyncDialog by remember { mutableStateOf(false) }
                
                if (showContactsSyncDialog) {
                    ContactsSyncDialog(
                        isRu = isRu,
                        currentDays = account.contactsSyncIntervalDays,
                        onDaysChange = onContactsSyncIntervalChange,
                        onDismiss = { showContactsSyncDialog = false }
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text(Strings.contactsSync, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { 
                        Text(
                            getContactsSyncIntervalText(account.contactsSyncIntervalDays, isRu),
                            style = MaterialTheme.typography.bodySmall
                        ) 
                    },
                    leadingContent = { Icon(AppIcons.ContactPhone, null, modifier = Modifier.size(20.dp)) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.clickable { showContactsSyncDialog = true }
                )
            }
        }
    }
}

/**
 * Получить текст интервала синхронизации контактов
 */
fun getContactsSyncIntervalText(days: Int, isRu: Boolean): String {
    return when (days) {
        0 -> NotificationStrings.getNever(isRu)
        1 -> NotificationStrings.getDaily(isRu)
        7 -> NotificationStrings.getWeekly(isRu)
        14 -> NotificationStrings.getEveryTwoWeeks(isRu)
        30 -> NotificationStrings.getMonthly(isRu)
        else -> NotificationStrings.getEveryNDays(days, isRu)
    }
}

private val SIGNATURE_BLOCK_END = Regex("</(?:p|div|li|tr)>", RegexOption.IGNORE_CASE)
private val SIGNATURE_TD_END = Regex("</td>", RegexOption.IGNORE_CASE)
private val SIGNATURE_SPACES = Regex("[ \\t]+")
private val SIGNATURE_NEWLINES = Regex("\\n{2,}")

private fun stripHtmlForPreview(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(HtmlRegex.BR, "\n")
        .replace(SIGNATURE_BLOCK_END, "\n")
        .replace(SIGNATURE_TD_END, "\t")
        .replace(HtmlRegex.STYLE, "")
        .replace(HtmlRegex.SCRIPT, "")
        .replace(HtmlRegex.COMMENT, "")
        .replace(HtmlRegex.TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(SIGNATURE_SPACES, " ")
        .replace(SIGNATURE_NEWLINES, "\n")
        .trim()
}

/**
 * Диалог настройки синхронизации контактов
 */
@Composable
fun ContactsSyncDialog(
    isRu: Boolean,
    currentDays: Int,
    onDaysChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        0 to (NotificationStrings.getNever(isRu)),
        1 to (NotificationStrings.getDaily(isRu)),
        7 to (NotificationStrings.getWeekly(isRu)),
        14 to (NotificationStrings.getEveryTwoWeeks(isRu)),
        30 to (NotificationStrings.getMonthly(isRu))
    )
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.contactsSync) },
        text = {
            Column {
                Text(
                    Strings.contactsSyncDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                options.forEach { (days, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDaysChange(days)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentDays == days,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        }
    )
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
            AppIcons.ArrowDropDown,
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

/**
 * Диалог настройки автоматической очистки папок
 */
@Composable
fun AutoCleanupDialog(
    isRu: Boolean,
    trashDays: Int,
    draftsDays: Int,
    spamDays: Int,
    onTrashDaysChange: (Int) -> Unit,
    onDraftsDaysChange: (Int) -> Unit,
    onSpamDaysChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.autoCleanup) },
        text = {
            Column {
                // Корзина
                AutoCleanupFolderItem(
                    icon = AppIcons.Delete,
                    title = Strings.autoCleanupTrash,
                    currentDays = trashDays,
                    isRu = isRu,
                    isExpanded = selectedFolder == "trash",
                    onExpandClick = { selectedFolder = if (selectedFolder == "trash") null else "trash" },
                    onDaysChange = onTrashDaysChange
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Черновики
                AutoCleanupFolderItem(
                    icon = AppIcons.Drafts,
                    title = Strings.autoCleanupDrafts,
                    currentDays = draftsDays,
                    isRu = isRu,
                    isExpanded = selectedFolder == "drafts",
                    onExpandClick = { selectedFolder = if (selectedFolder == "drafts") null else "drafts" },
                    onDaysChange = onDraftsDaysChange
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Спам
                AutoCleanupFolderItem(
                    icon = AppIcons.Report,
                    title = Strings.autoCleanupSpam,
                    currentDays = spamDays,
                    isRu = isRu,
                    isExpanded = selectedFolder == "spam",
                    onExpandClick = { selectedFolder = if (selectedFolder == "spam") null else "spam" },
                    onDaysChange = onSpamDaysChange
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.close)
            }
        }
    )
}

@Composable
private fun AutoCleanupFolderItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    currentDays: Int,
    isRu: Boolean,
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    onDaysChange: (Int) -> Unit
) {
    val currentOption = SettingsRepository.AutoCleanupDays.fromDays(currentDays)
    
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandClick() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = currentOption.getDisplayName(isRu),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (isExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (isExpanded) {
            Column(modifier = Modifier.padding(start = 36.dp)) {
                SettingsRepository.AutoCleanupDays.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDaysChange(option.days) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentDays == option.days,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = option.getDisplayName(isRu),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}


/**
 * Диалог управления подписями аккаунта
 */
@Composable
fun SignaturesManagementDialog(
    isRu: Boolean,
    accountId: Long,
    signatures: List<com.dedovmosol.iwomail.data.database.SignatureEntity>,
    onSignaturesChanged: (List<com.dedovmosol.iwomail.data.database.SignatureEntity>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context) }
    
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSignature by remember { mutableStateOf<com.dedovmosol.iwomail.data.database.SignatureEntity?>(null) }
    var signatureToDelete by remember { mutableStateOf<com.dedovmosol.iwomail.data.database.SignatureEntity?>(null) }
    
    // Диалог добавления/редактирования подписи
    if (showAddDialog || editingSignature != null) {
        // Первая подпись всегда default
        val isFirstSignature = signatures.isEmpty() && editingSignature == null
        // Если редактируем единственную подпись — она тоже всегда default
        val isOnlySignature = signatures.size == 1 && editingSignature != null
        val forceDefault = isFirstSignature || isOnlySignature
        
        SignatureEditDialog(
            isRu = isRu,
            signature = editingSignature,
            forceDefault = forceDefault,
            currentIsDefault = editingSignature?.isDefault ?: false,
            onSave = { name, text, isDefault, isHtml ->
                scope.launch(Dispatchers.IO) {
                    // Если ставим новый default — сбрасываем старый
                    val actualIsDefault = if (forceDefault) true else isDefault
                    if (actualIsDefault) {
                        database.signatureDao().clearDefaultForAccount(accountId)
                    }
                    
                    val currentEditing = editingSignature
                    if (currentEditing != null) {
                        // Редактирование — update
                        val updated = currentEditing.copy(
                            name = name,
                            text = text,
                            isDefault = actualIsDefault,
                            isHtml = isHtml
                        )
                        database.signatureDao().update(updated)
                    } else {
                        // Создание новой
                        val newSignature = com.dedovmosol.iwomail.data.database.SignatureEntity(
                            accountId = accountId,
                            name = name,
                            text = text,
                            isDefault = actualIsDefault,
                            sortOrder = signatures.size,
                            isHtml = isHtml
                        )
                        database.signatureDao().insert(newSignature)
                    }
                    val updatedSignatures = database.signatureDao().getSignaturesByAccountList(accountId)
                    withContext(Dispatchers.Main) {
                        onSignaturesChanged(updatedSignatures)
                    }
                }
                showAddDialog = false
                editingSignature = null
            },
            onDismiss = {
                showAddDialog = false
                editingSignature = null
            }
        )
    }
    
    // Диалог подтверждения удаления
    signatureToDelete?.let { signature ->
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { signatureToDelete = null },
            icon = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(Strings.deleteSignatureTitle) },
            text = { Text(signature.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val wasDefault = signature.isDefault
                            database.signatureDao().delete(signature.id)
                            
                            // Если удалили default подпись — назначить следующую по createdAt
                            if (wasDefault) {
                                val remaining = database.signatureDao().getSignaturesByAccountList(accountId)
                                if (remaining.isNotEmpty()) {
                                    // Сортируем по createdAt и берём первую
                                    val nextDefault = remaining.minByOrNull { it.createdAt }
                                    nextDefault?.let {
                                        database.signatureDao().setDefault(it.id)
                                    }
                                }
                            }
                            
                            onSignaturesChanged(database.signatureDao().getSignaturesByAccountList(accountId))
                        }
                        signatureToDelete = null
                    }
                ) {
                    Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { signatureToDelete = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.signaturesTitle) },
        text = {
            Column {
                if (signatures.isEmpty()) {
                    Text(
                        Strings.noSignaturesHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    signatures.forEach { signature ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingSignature = signature }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        signature.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (signature.isDefault) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (signature.isDefault) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            Strings.defaultLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                val preview = if (signature.isHtml) {
                                    stripHtmlForPreview(signature.text)
                                } else {
                                    signature.text
                                }
                                val oneLinePreview = preview.replace("\n", " ").trim()
                                val truncatedPreview = if (oneLinePreview.length > 40) {
                                    oneLinePreview.take(40) + "..."
                                } else {
                                    oneLinePreview
                                }
                                Text(
                                    truncatedPreview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { signatureToDelete = signature }) {
                                Icon(
                                    AppIcons.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                
                // Кнопка добавления (максимум 5 подписей)
                if (signatures.size < 5) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(AppIcons.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Strings.addSignatureAction)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.close)
            }
        }
    )
}

/**
 * Диалог редактирования подписи
 */
@Composable
private fun SignatureEditDialog(
    isRu: Boolean,
    signature: com.dedovmosol.iwomail.data.database.SignatureEntity?,
    forceDefault: Boolean = false,
    currentIsDefault: Boolean = false,
    onSave: (name: String, text: String, isDefault: Boolean, isHtml: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(signature?.name ?: "") }
    var text by remember { mutableStateOf(signature?.text ?: "") }
    var isDefault by remember { mutableStateOf(if (forceDefault) true else (signature?.isDefault ?: false)) }
    var isHtml by remember { mutableStateOf(signature?.isHtml ?: false) }
    
    // Функция конвертации plain text → HTML
    fun plainToHtml(plain: String): String {
        return plain
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
    }
    
    // Функция конвертации HTML → plain text (простая)
    fun htmlToPlain(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "") // Удаляем все теги
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (signature != null) Strings.editTitle else Strings.newSignatureTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(Strings.nameLabel) },
                    placeholder = { Text(Strings.namePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Переключатель HTML форматирования
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            // Конвертируем текст при переключении режима
                            text = if (isHtml) {
                                // HTML → plain text
                                htmlToPlain(text)
                            } else {
                                // plain text → HTML
                                plainToHtml(text)
                            }
                            isHtml = !isHtml 
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isHtml,
                        onCheckedChange = { newValue ->
                            // Конвертируем текст при переключении режима
                            text = if (isHtml && !newValue) {
                                // HTML → plain text
                                htmlToPlain(text)
                            } else if (!isHtml && newValue) {
                                // plain text → HTML
                                plainToHtml(text)
                            } else {
                                text
                            }
                            isHtml = newValue
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isRu) "HTML форматирование" else "HTML formatting")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (isHtml) {
                    // HTML редактор
                    val richTextController = com.dedovmosol.iwomail.ui.components.rememberRichTextEditorController()
                    val isDarkTheme = !MaterialTheme.colorScheme.background.luminance().let { it > 0.5f }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 200.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column {
                            com.dedovmosol.iwomail.ui.components.RichTextToolbar(
                                controller = richTextController,
                                onInsertImage = { }, // Изображения в подписи не поддерживаем
                                modifier = Modifier.fillMaxWidth(),
                                showInsertImage = false
                            )
                            HorizontalDivider()
                            com.dedovmosol.iwomail.ui.components.RichTextEditor(
                                initialHtml = text,
                                onHtmlChanged = { text = it },
                                controller = richTextController,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp),
                                placeholder = if (isRu) "Текст подписи" else "Signature text",
                                minHeight = 80,
                                isDarkTheme = isDarkTheme
                            )
                        }
                    }
                } else {
                    // Plain text редактор
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(Strings.signatureTextLabel) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                        maxLines = 6
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Галочка "По умолчанию" — нельзя снять если это единственная/первая подпись или уже default
                val canToggleDefault = !forceDefault && !currentIsDefault
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (canToggleDefault) Modifier.clickable { isDefault = !isDefault } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isDefault,
                        onCheckedChange = if (canToggleDefault) { { isDefault = it } } else null,
                        enabled = canToggleDefault
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        Strings.defaultCheckbox,
                        color = if (canToggleDefault) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, text, isDefault, isHtml) },
                enabled = name.isNotBlank() && text.isNotBlank()
            ) {
                Text(Strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        }
    )
}

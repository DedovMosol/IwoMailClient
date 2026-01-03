package com.iwo.mailclient.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape

import com.iwo.mailclient.ui.theme.AppIcons
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
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.SyncMode
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.sync.SyncWorker
import com.iwo.mailclient.ui.NotificationStrings
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.isRussian
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    accountId: Long,
    onBackClick: () -> Unit,
    onEditCredentials: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { AccountRepository(context) }
    val database = remember { MailDatabase.getInstance(context) }
    val isRu = isRussian()
    
    var account by remember { mutableStateOf<AccountEntity?>(null) }
    var signatures by remember { mutableStateOf<List<com.iwo.mailclient.data.database.SignatureEntity>>(emptyList()) }
    
    // Загружаем аккаунт
    LaunchedEffect(accountId) {
        account = accountRepo.getAccount(accountId)
        signatures = database.signatureDao().getSignaturesByAccountList(accountId)
    }
    
    val currentAccount = account ?: return
    
    val accountType = try {
        AccountType.valueOf(currentAccount.accountType)
    } catch (_: Exception) {
        AccountType.EXCHANGE
    }
    
    val syncMode = try {
        SyncMode.valueOf(currentAccount.syncMode)
    } catch (_: Exception) {
        SyncMode.PUSH
    }

    
    // Диалоги
    var showSyncModeDialog by remember { mutableStateOf(false) }
    var showSyncIntervalDialog by remember { mutableStateOf(false) }
    var showSignaturesDialog by remember { mutableStateOf(false) }
    var showAutoCleanupDialog by remember { mutableStateOf(false) }
    var showContactsSyncDialog by remember { mutableStateOf(false) }
    var showNotesSyncDialog by remember { mutableStateOf(false) }
    var showCalendarSyncDialog by remember { mutableStateOf(false) }
    var showTasksSyncDialog by remember { mutableStateOf(false) }
    var showCertificateDialog by remember { mutableStateOf(false) }
    
    // Пикеры для сертификата
    val certFileName = currentAccount.certificatePath?.let { java.io.File(it).name } ?: "certificate.cer"
    
    val exportCertificatePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let { destUri ->
            scope.launch {
                try {
                    currentAccount.certificatePath?.let { certPath ->
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
                                com.iwo.mailclient.ui.NotificationStrings.getCertificateExported(isRu),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        com.iwo.mailclient.ui.NotificationStrings.getExportError(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
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
                            com.iwo.mailclient.ui.NotificationStrings.getInvalidFileFormat(isRu),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    
                    currentAccount.certificatePath?.let { oldPath ->
                        try { java.io.File(oldPath).delete() } catch (_: Exception) {}
                    }
                    
                    val fileName = "cert_${currentAccount.id}_${System.currentTimeMillis()}.$extension"
                    val certFile = java.io.File(context.filesDir, fileName)
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            certFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    
                    accountRepo.updateCertificatePath(accountId, certFile.absolutePath)
                    account = accountRepo.getAccount(accountId)
                    android.widget.Toast.makeText(
                        context,
                        com.iwo.mailclient.ui.NotificationStrings.getCertificateUpdated(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        com.iwo.mailclient.ui.NotificationStrings.getCertificateLoadingError(isRu),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    
    // Диалог режима синхронизации
    if (showSyncModeDialog) {
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showSyncModeDialog = false },
            title = { Text(Strings.syncMode) },
            text = {
                Column {
                    SyncMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        accountRepo.updateSyncMode(accountId, mode)
                                        if (mode == SyncMode.PUSH) {
                                            com.iwo.mailclient.sync.PushService.start(context)
                                        } else {
                                            com.iwo.mailclient.sync.PushService.stop(context)
                                        }
                                        SyncWorker.scheduleWithNightMode(context)
                                        account = accountRepo.getAccount(accountId)
                                    }
                                    showSyncModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = syncMode == mode, onClick = null)
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
    
    // Диалог интервала синхронизации
    if (showSyncIntervalDialog) {
        val intervals = listOf(1, 2, 3, 5, 10, 15, 30)
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showSyncIntervalDialog = false },
            title = { Text(Strings.syncInterval) },
            text = {
                Column {
                    intervals.forEach { minutes ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        accountRepo.updateSyncInterval(accountId, minutes)
                                        SyncWorker.scheduleWithNightMode(context)
                                        account = accountRepo.getAccount(accountId)
                                    }
                                    showSyncIntervalDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = currentAccount.syncIntervalMinutes == minutes, onClick = null)
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
    
    // Диалог подписей
    if (showSignaturesDialog) {
        SignaturesManagementDialog(
            isRu = isRu,
            accountId = accountId,
            signatures = signatures,
            onSignaturesChanged = { newSignatures ->
                signatures = newSignatures
            },
            onDismiss = { showSignaturesDialog = false }
        )
    }
    
    // Диалог автоочистки
    if (showAutoCleanupDialog) {
        AutoCleanupDialog(
            isRu = isRu,
            trashDays = currentAccount.autoCleanupTrashDays,
            draftsDays = currentAccount.autoCleanupDraftsDays,
            spamDays = currentAccount.autoCleanupSpamDays,
            onTrashDaysChange = { days ->
                scope.launch {
                    accountRepo.updateAutoCleanupTrashDays(accountId, days)
                    account = accountRepo.getAccount(accountId)
                }
            },
            onDraftsDaysChange = { days ->
                scope.launch {
                    accountRepo.updateAutoCleanupDraftsDays(accountId, days)
                    account = accountRepo.getAccount(accountId)
                }
            },
            onSpamDaysChange = { days ->
                scope.launch {
                    accountRepo.updateAutoCleanupSpamDays(accountId, days)
                    account = accountRepo.getAccount(accountId)
                }
            },
            onDismiss = { showAutoCleanupDialog = false }
        )
    }
    
    // Диалог синхронизации контактов
    if (showContactsSyncDialog) {
        ContactsSyncDialog(
            isRu = isRu,
            currentDays = currentAccount.contactsSyncIntervalDays,
            onDaysChange = { days ->
                scope.launch {
                    accountRepo.updateContactsSyncInterval(accountId, days)
                    account = accountRepo.getAccount(accountId)
                }
            },
            onDismiss = { showContactsSyncDialog = false }
        )
    }
    
    // Диалог синхронизации заметок
    if (showNotesSyncDialog) {
        SyncIntervalDialog(
            isRu = isRu,
            title = com.iwo.mailclient.ui.NotificationStrings.getNotesSyncTitle(isRu),
            currentDays = currentAccount.notesSyncIntervalDays,
            onDaysChange = { days ->
                scope.launch {
                    accountRepo.updateNotesSyncInterval(accountId, days)
                    account = accountRepo.getAccount(accountId)
                }
            },
            onDismiss = { showNotesSyncDialog = false }
        )
    }
    
    // Диалог синхронизации календаря
    if (showCalendarSyncDialog) {
        SyncIntervalDialog(
            isRu = isRu,
            title = com.iwo.mailclient.ui.NotificationStrings.getCalendarSyncTitle(isRu),
            currentDays = currentAccount.calendarSyncIntervalDays,
            onDaysChange = { days ->
                scope.launch {
                    accountRepo.updateCalendarSyncInterval(accountId, days)
                    account = accountRepo.getAccount(accountId)
                }
            },
            onDismiss = { showCalendarSyncDialog = false }
        )
    }
    
    // Диалог синхронизации задач
    if (showTasksSyncDialog) {
        SyncIntervalDialog(
            isRu = isRu,
            title = if (isRu) "Синхронизация задач" else "Tasks sync",
            currentDays = currentAccount.tasksSyncIntervalDays,
            onDaysChange = { days ->
                scope.launch {
                    accountRepo.updateTasksSyncInterval(accountId, days)
                    account = accountRepo.getAccount(accountId)
                }
            },
            onDismiss = { showTasksSyncDialog = false }
        )
    }

    
    // Диалог сертификата
    if (showCertificateDialog && !currentAccount.certificatePath.isNullOrBlank()) {
        val certFile = java.io.File(currentAccount.certificatePath!!)
        val certFileSize = if (certFile.exists()) "${certFile.length() / 1024} KB" else "—"
        var showDeleteConfirm by remember { mutableStateOf(false) }
        
        if (showDeleteConfirm) {
            com.iwo.mailclient.ui.theme.StyledAlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                icon = { Icon(AppIcons.Warning, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(com.iwo.mailclient.ui.NotificationStrings.getDeleteCertificateTitle(isRu)) },
                text = {
                    Text(com.iwo.mailclient.ui.NotificationStrings.getDeleteCertificateWarning(isRu))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            showCertificateDialog = false
                            try { certFile.delete() } catch (_: Exception) {}
                            scope.launch {
                                accountRepo.updateCertificatePath(accountId, null)
                                account = accountRepo.getAccount(accountId)
                            }
                            android.widget.Toast.makeText(
                                context,
                                com.iwo.mailclient.ui.NotificationStrings.getCertificateRemoved(isRu),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text(com.iwo.mailclient.ui.NotificationStrings.getRemove(isRu), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(Strings.cancel)
                    }
                }
            )
        }
        
        com.iwo.mailclient.ui.theme.StyledAlertDialog(
            onDismissRequest = { showCertificateDialog = false },
            icon = { Icon(AppIcons.Lock, null) },
            title = { Text(Strings.serverCertificate) },
            text = {
                Column {
                    Text(com.iwo.mailclient.ui.NotificationStrings.getFileLabel(isRu), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(certFile.name, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(com.iwo.mailclient.ui.NotificationStrings.getSizeLabel(isRu), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(certFileSize, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                exportCertificatePicker.launch(certFile.name)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.iwo.mailclient.ui.NotificationStrings.getExport(isRu))
                        }
                        OutlinedButton(
                            onClick = {
                                showCertificateDialog = false
                                certificatePicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.SwapHoriz, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.iwo.mailclient.ui.NotificationStrings.getReplace(isRu))
                        }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(com.iwo.mailclient.ui.NotificationStrings.getRemove(isRu))
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

    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.accountSettings, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
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
            // Заголовок аккаунта
            item {
                ListItem(
                    headlineContent = { 
                        Text(currentAccount.displayName, fontWeight = FontWeight.Bold)
                    },
                    supportingContent = { 
                        Column {
                            Text(currentAccount.email)
                            Text(accountType.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(currentAccount.color)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentAccount.displayName.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                )
            }
            
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            
            // Изменить учётные данные
            item {
                ListItem(
                    headlineContent = { Text(Strings.changeCredentials) },
                    leadingContent = { Icon(AppIcons.Edit, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { onEditCredentials(accountId) }
                )
            }
            
            // Сертификат (если есть)
            if (!currentAccount.certificatePath.isNullOrBlank()) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.serverCertificate) },
                        supportingContent = { Text(java.io.File(currentAccount.certificatePath!!).name) },
                        leadingContent = { Icon(AppIcons.Lock, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showCertificateDialog = true }
                    )
                }
            }
            
            // Режим синхронизации (только для Exchange)
            if (accountType == AccountType.EXCHANGE) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.syncMode) },
                        supportingContent = { Text(syncMode.getDisplayName(isRu)) },
                        leadingContent = { Icon(AppIcons.Sync, null) },
                        modifier = Modifier.clickable { showSyncModeDialog = true }
                    )
                }
            }
            
            // Интервал синхронизации
            if (accountType != AccountType.EXCHANGE || syncMode == SyncMode.SCHEDULED) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.syncInterval) },
                        supportingContent = { Text(Strings.minutes(currentAccount.syncIntervalMinutes)) },
                        leadingContent = { Icon(AppIcons.Schedule, null) },
                        modifier = Modifier.clickable { showSyncIntervalDialog = true }
                    )
                }
            }
            
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            
            // Подписи
            item {
                ListItem(
                    headlineContent = { Text(Strings.signature) },
                    supportingContent = { 
                        Text(
                            if (signatures.isEmpty()) Strings.noSignature 
                            else Strings.signaturesCount(signatures.size)
                        ) 
                    },
                    leadingContent = { Icon(AppIcons.Draw, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { showSignaturesDialog = true }
                )
            }
            
            // Автоочистка
            item {
                ListItem(
                    headlineContent = { Text(Strings.autoCleanup) },
                    supportingContent = { Text(Strings.autoCleanupDesc) },
                    leadingContent = { Icon(AppIcons.AutoDelete, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { showAutoCleanupDialog = true }
                )
            }
            
            // Синхронизация контактов (только для Exchange)
            if (accountType == AccountType.EXCHANGE) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.contactsSync) },
                        supportingContent = { Text(getContactsSyncIntervalText(currentAccount.contactsSyncIntervalDays, isRu)) },
                        leadingContent = { Icon(AppIcons.ContactPhone, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showContactsSyncDialog = true }
                    )
                }
                
                // Синхронизация заметок
                item {
                    ListItem(
                        headlineContent = { Text(Strings.notesSync) },
                        supportingContent = { Text(getContactsSyncIntervalText(currentAccount.notesSyncIntervalDays, isRu)) },
                        leadingContent = { Icon(AppIcons.StickyNote, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showNotesSyncDialog = true }
                    )
                }
                
                // Синхронизация календаря
                item {
                    ListItem(
                        headlineContent = { Text(Strings.calendarSync) },
                        supportingContent = { Text(getContactsSyncIntervalText(currentAccount.calendarSyncIntervalDays, isRu)) },
                        leadingContent = { Icon(AppIcons.CalendarMonth, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showCalendarSyncDialog = true }
                    )
                }
                
                // Синхронизация задач
                item {
                    ListItem(
                        headlineContent = { Text(Strings.tasksSync) },
                        supportingContent = { Text(getContactsSyncIntervalText(currentAccount.tasksSyncIntervalDays, isRu)) },
                        leadingContent = { Icon(AppIcons.Task, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showTasksSyncDialog = true }
                    )
                }
            }
        }
    }
}

/**
 * Универсальный диалог выбора интервала синхронизации
 */
@Composable
private fun SyncIntervalDialog(
    isRu: Boolean,
    title: String,
    currentDays: Int,
    onDaysChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val intervals = listOf(
        0 to (NotificationStrings.getNever(isRu)),
        1 to (NotificationStrings.getDaily(isRu)),
        7 to (NotificationStrings.getWeekly(isRu)),
        14 to (NotificationStrings.getEveryTwoWeeks(isRu)),
        30 to (NotificationStrings.getMonthly(isRu))
    )
    
    com.iwo.mailclient.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                intervals.forEach { (days, label) ->
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
                        RadioButton(selected = currentDays == days, onClick = null)
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




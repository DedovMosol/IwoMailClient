package com.iwo.mailclient.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.SyncMode
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.sync.SyncWorker
import com.iwo.mailclient.ui.NotificationStrings
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.isRussian
import com.iwo.mailclient.ui.theme.AppIcons
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncCleanupScreen(
    accountId: Long,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountRepo = remember { AccountRepository(context) }
    val isRu = isRussian()
    
    var account by remember { mutableStateOf<AccountEntity?>(null) }
    
    LaunchedEffect(accountId) {
        account = accountRepo.getAccount(accountId)
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
    var showAutoCleanupDialog by remember { mutableStateOf(false) }
    var showContactsSyncDialog by remember { mutableStateOf(false) }
    var showNotesSyncDialog by remember { mutableStateOf(false) }
    var showCalendarSyncDialog by remember { mutableStateOf(false) }
    var showTasksSyncDialog by remember { mutableStateOf(false) }

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
            title = NotificationStrings.getNotesSyncTitle(isRu),
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
            title = NotificationStrings.getCalendarSyncTitle(isRu),
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.syncAndCleanup, color = Color.White) },
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
            // === СЕКЦИЯ: СИНХРОНИЗАЦИЯ ===
            item {
                Text(
                    text = Strings.syncSettings,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            // Режим синхронизации (только для Exchange)
            if (accountType == AccountType.EXCHANGE) {
                item {
                    ListItem(
                        headlineContent = { Text(Strings.syncMode) },
                        supportingContent = { Text(syncMode.getDisplayName(isRu)) },
                        leadingContent = { Icon(AppIcons.Sync, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
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
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showSyncIntervalDialog = true }
                    )
                }
            }
            
            // Ночной режим
            item {
                ListItem(
                    headlineContent = { Text(Strings.nightMode) },
                    supportingContent = { Text(Strings.nightModeDesc) },
                    leadingContent = { Icon(AppIcons.NightsStay, null) },
                    trailingContent = {
                        Switch(
                            checked = currentAccount.nightModeEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    accountRepo.updateNightModeEnabled(accountId, enabled)
                                    SyncWorker.scheduleWithNightMode(context)
                                    account = accountRepo.getAccount(accountId)
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
                    leadingContent = { Icon(AppIcons.BatterySaver, null) },
                    trailingContent = {
                        Switch(
                            checked = currentAccount.ignoreBatterySaver,
                            onCheckedChange = { ignore ->
                                scope.launch {
                                    accountRepo.updateIgnoreBatterySaver(accountId, ignore)
                                    SyncWorker.scheduleWithNightMode(context)
                                    account = accountRepo.getAccount(accountId)
                                }
                            }
                        )
                    }
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
                
                item {
                    ListItem(
                        headlineContent = { Text(Strings.notesSync) },
                        supportingContent = { Text(getContactsSyncIntervalText(currentAccount.notesSyncIntervalDays, isRu)) },
                        leadingContent = { Icon(AppIcons.StickyNote, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showNotesSyncDialog = true }
                    )
                }
                
                item {
                    ListItem(
                        headlineContent = { Text(Strings.calendarSync) },
                        supportingContent = { Text(getContactsSyncIntervalText(currentAccount.calendarSyncIntervalDays, isRu)) },
                        leadingContent = { Icon(AppIcons.CalendarMonth, null) },
                        trailingContent = { Icon(AppIcons.ChevronRight, null) },
                        modifier = Modifier.clickable { showCalendarSyncDialog = true }
                    )
                }
                
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
            
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            
            // === СЕКЦИЯ: ОЧИСТКА ===
            item {
                Text(
                    text = Strings.cleanupSection,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.autoCleanup) },
                    supportingContent = { Text(Strings.autoCleanupDesc) },
                    leadingContent = { Icon(AppIcons.AutoDelete, null) },
                    trailingContent = { Icon(AppIcons.ChevronRight, null) },
                    modifier = Modifier.clickable { showAutoCleanupDialog = true }
                )
            }
        }
    }
}

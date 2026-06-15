package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncCleanupScreen(
    accountId: Long,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val isRu = isRussian()

    val viewModel: SyncCleanupViewModel = viewModel(
        factory = SyncCleanupViewModel.provideFactory(
            context.applicationContext as android.app.Application,
            accountId
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    val currentAccount = uiState.account ?: return
    val downloadsDays = uiState.downloadsDays
    val rollbackDays = uiState.rollbackDays
    
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
    var showSyncModeDialog by rememberSaveable { mutableStateOf(false) }
    var showSyncIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var showAutoCleanupDialog by rememberSaveable { mutableStateOf(false) }
    var showContactsSyncDialog by rememberSaveable { mutableStateOf(false) }
    var showNotesSyncDialog by rememberSaveable { mutableStateOf(false) }
    var showCalendarSyncDialog by rememberSaveable { mutableStateOf(false) }
    var showTasksSyncDialog by rememberSaveable { mutableStateOf(false) }

    // Диалог режима синхронизации
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
                                    viewModel.setSyncMode(mode)
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
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showSyncModeDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }
    
    // Диалог интервала синхронизации
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
                                    viewModel.setSyncInterval(minutes)
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
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showSyncIntervalDialog = false },
                    text = Strings.cancel
                )
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
            downloadsDays = downloadsDays,
            rollbackDays = rollbackDays,
            onTrashDaysChange = { days -> viewModel.setAutoCleanupTrashDays(days) },
            onDraftsDaysChange = { days -> viewModel.setAutoCleanupDraftsDays(days) },
            onSpamDaysChange = { days -> viewModel.setAutoCleanupSpamDays(days) },
            onDownloadsDaysChange = { days -> viewModel.setDownloadsDays(days) },
            onRollbackDaysChange = { days -> viewModel.setRollbackDays(days) },
            onDismiss = { showAutoCleanupDialog = false }
        )
    }
    
    // Диалог синхронизации контактов
    if (showContactsSyncDialog) {
        ContactsSyncDialog(
            isRu = isRu,
            currentDays = currentAccount.contactsSyncIntervalDays,
            onDaysChange = { days -> viewModel.setContactsSyncInterval(days) },
            onDismiss = { showContactsSyncDialog = false }
        )
    }
    
    // Диалог синхронизации заметок
    if (showNotesSyncDialog) {
        SyncIntervalDialog(
            isRu = isRu,
            title = NotificationStrings.getNotesSyncTitle(isRu),
            currentDays = currentAccount.notesSyncIntervalDays,
            onDaysChange = { days -> viewModel.setNotesSyncInterval(days) },
            onDismiss = { showNotesSyncDialog = false }
        )
    }
    
    // Диалог синхронизации календаря
    if (showCalendarSyncDialog) {
        SyncIntervalDialog(
            isRu = isRu,
            title = NotificationStrings.getCalendarSyncTitle(isRu),
            currentDays = currentAccount.calendarSyncIntervalDays,
            onDaysChange = { days -> viewModel.setCalendarSyncInterval(days) },
            onDismiss = { showCalendarSyncDialog = false }
        )
    }
    
    // Диалог синхронизации задач
    if (showTasksSyncDialog) {
        SyncIntervalDialog(
            isRu = isRu,
            title = if (isRu) "Синхронизация задач" else "Tasks sync",
            currentDays = currentAccount.tasksSyncIntervalDays,
            onDaysChange = { days -> viewModel.setTasksSyncInterval(days) },
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
        val listState = rememberLazyListState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
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
                            onCheckedChange = { enabled -> viewModel.setNightModeEnabled(enabled) }
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
                            onCheckedChange = { ignore -> viewModel.setIgnoreBatterySaver(ignore) }
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
        LazyColumnScrollbar(listState)
        }
    }
}

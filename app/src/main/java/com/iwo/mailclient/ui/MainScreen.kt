package com.iwo.mailclient.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.cancel
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.FolderEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.data.repository.RepositoryProvider
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.network.NetworkMonitor
import com.iwo.mailclient.ui.components.NetworkBanner
import com.iwo.mailclient.network.rememberNetworkState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * ������-������� �������������
 * ���������� ��������� scope ��� �������������
 */

// Data classes ��� UI
private data class FolderDisplayData(val id: String, val name: String, val count: Int, val unreadCount: Int, val type: Int)
private data class FolderColorsData(val icon: ImageVector, val gradientColors: List<Color>)

/**
 * ���������� ��� ����� ��� �������������� �������� UI
 */
object FoldersCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<Long, List<FolderEntity>>()
    
    fun get(accountId: Long): List<FolderEntity> = cache[accountId] ?: emptyList()
    
    fun set(accountId: Long, folders: List<FolderEntity>) {
        cache[accountId] = folders
    }
    
    fun clear() {
        cache.clear()
    }
    
    fun clearAccount(accountId: Long) {
        cache.remove(accountId)
    }
}

object InitialSyncController {
    // Отслеживаем синхронизацию для каждого аккаунта отдельно
    private val syncingAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val syncedAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val syncJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Реактивные состояния для UI
    var isSyncing by mutableStateOf(false)
        private set
    var syncDone by mutableStateOf(false)
        private set
    var noNetwork by mutableStateOf(false)
        private set
    
    /**
     * Проверяет наличие активного интернет-соединения
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    // Проверка синхронизации конкретного аккаунта
    fun isSyncingAccount(accountId: Long): Boolean = accountId in syncingAccounts
    fun isSyncedAccount(accountId: Long): Boolean = accountId in syncedAccounts
    
    private fun updateState() {
        isSyncing = syncingAccounts.isNotEmpty()
        syncDone = syncedAccounts.isNotEmpty()
    }
    
    fun startSyncIfNeeded(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) {
        // Пропускаем если этот аккаунт уже синхронизирован
        if (accountId in syncedAccounts) return
        
        // Пропускаем если этот аккаунт уже синхронизируется
        if (accountId in syncingAccounts) return
        if (syncJobs[accountId]?.isActive == true) return
        
        // Проверяем наличие сети
        if (!isNetworkAvailable(context)) {
            noNetwork = true
            return
        }
        noNetwork = false
        
        syncingAccounts.add(accountId)
        updateState()
        
        syncJobs[accountId] = syncScope.launch {
            try {
                delay(100)
                
                withTimeoutOrNull(300_000L) {
                    withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                    
                    delay(200)
                    
                    // Синхронизируем все почтовые папки
                    val emailFolderTypes = listOf(1, 2, 3, 4, 5, 6, 11, 12)
                    val currentFolders = withContext(Dispatchers.IO) {
                        com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                            .folderDao().getFoldersByAccountList(accountId)
                    }
                    val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                    
                    withContext(Dispatchers.IO) {
                        supervisorScope {
                            foldersToSync.map { folder ->
                                launch {
                                    try {
                                        withTimeoutOrNull(120_000L) {
                                            mailRepo.syncEmails(accountId, folder.id)
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                    }
                    
                    // Предзагружаем тела последних 7 писем из Inbox для офлайн-доступа
                    withContext(Dispatchers.IO) {
                        try {
                            mailRepo.prefetchEmailBodies(accountId, 7)
                        } catch (_: Exception) { }
                    }
                    
                    // Синхронизируем контакты, заметки, календарь, задачи параллельно
                    withContext(Dispatchers.IO) {
                        supervisorScope {
                            launch {
                                try {
                                    withTimeoutOrNull(120_000L) {
                                        val contactRepo = com.iwo.mailclient.data.repository.ContactRepository(context)
                                        contactRepo.syncExchangeContacts(accountId)
                                        contactRepo.syncGalContactsToDb(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                            launch {
                                try {
                                    withTimeoutOrNull(60_000L) {
                                        val noteRepo = com.iwo.mailclient.data.repository.NoteRepository(context)
                                        noteRepo.syncNotes(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                            launch {
                                try {
                                    withTimeoutOrNull(60_000L) {
                                        val calendarRepo = com.iwo.mailclient.data.repository.CalendarRepository(context)
                                        calendarRepo.syncCalendar(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                            launch {
                                try {
                                    withTimeoutOrNull(60_000L) {
                                        val taskRepo = com.iwo.mailclient.data.repository.TaskRepository(context)
                                        taskRepo.syncTasks(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    
                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                }
                
                syncedAccounts.add(accountId)
                updateState()
            } catch (_: CancellationException) {
                // Отмена — не помечаем как синхронизированный
                updateState()
            } catch (_: Exception) {
                syncedAccounts.add(accountId)
                updateState()
            } finally {
                syncingAccounts.remove(accountId)
                syncJobs.remove(accountId)
                updateState()
            }
        }
    }
    fun reset() {
        syncJobs.values.forEach { it.cancel() }
        syncJobs.clear()
        syncedAccounts.clear()
        syncingAccounts.clear()
        noNetwork = false
        updateState()
    }
    
    fun resetAccount(accountId: Long) {
        syncedAccounts.remove(accountId)
        syncJobs[accountId]?.cancel()
        syncJobs.remove(accountId)
        syncingAccounts.remove(accountId)
        noNetwork = false
        updateState()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToEmailList: (String) -> Unit,
    onNavigateToCompose: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToOnboarding: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToEmailDetail: (String) -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
    onNavigateToNotes: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // ���������� RepositoryProvider ��� ������� ������������� (����������� ������)
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val mailRepo = remember { RepositoryProvider.getMailRepository(context) }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val noteRepo = remember { RepositoryProvider.getNoteRepository(context) }
    val calendarRepo = remember { RepositoryProvider.getCalendarRepository(context) }
    val taskRepo = remember { RepositoryProvider.getTaskRepository(context) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    
    val accounts by accountRepo.accounts.collectAsState(initial = emptyList())
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    
    // Если все аккаунты удалены — переходим на экран авторизации
    var accountsInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(accounts) {
        // Пропускаем первую проверку (initial = emptyList)
        if (!accountsInitialized && accounts.isEmpty()) {
            accountsInitialized = true
            return@LaunchedEffect
        }
        accountsInitialized = true
        if (accounts.isEmpty()) {
            onNavigateToSetup()
        }
    }
    
    // ���������� ��� ��� �������������� �������� ��� �������� �� �����
    var folders by remember { mutableStateOf(FoldersCache.get(activeAccount?.id ?: 0L)) }
    var flaggedCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var accountsLoaded by remember { mutableStateOf(false) }
    // ���� ��� ������ ���������
    var dataLoaded by remember { mutableStateOf(folders.isNotEmpty()) }
    
    // �������� ������������� �������� �� �����������
    val isSyncing = InitialSyncController.isSyncing
    val initialSyncDone = InitialSyncController.syncDone
    
    // Время последней синхронизации (для обновления статистики)
    val lastSyncTime by settingsRepo.lastSyncTime.collectAsState(initial = 0L)
    
   // ������ �������� �����
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    
    // ������ �������� �����
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var isDeletingFolder by remember { mutableStateOf(false) }
    
    // ������ �������������� �����
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var isRenamingFolder by remember { mutableStateOf(false) }
    
    // ���� �������� � ������ (������ �������)
    var folderForMenu by remember { mutableStateOf<FolderEntity?>(null) }
    
    // ���� ��� ��������� ������� �������� (������ ��� ������ �������)
    var firstAccountActivated by rememberSaveable { mutableStateOf(false) }
    
    // ���������� ������ ������� ������ ��� ������ �������, ���� ��� ���������
    // �� ����������� �� ������ ��� �������� � ������ ���������� ��������
    LaunchedEffect(Unit) {
        if (!firstAccountActivated) {
            firstAccountActivated = true
            // ��������� �������� � ��, � �� ����� Flow
            val hasActive = accountRepo.getActiveAccountSync() != null
            if (!hasActive) {
                // ���� ������ �������� �� Flow ���������
                val accountsList = accountRepo.accounts.first()
                accountsList.firstOrNull()?.let { 
                    accountRepo.setActiveAccount(it.id) 
                }
            }
        }
    }
    
    // Загружаем папки и счётчики после смены аккаунта
    var notesCount by rememberSaveable { mutableStateOf(0) }
    var eventsCount by rememberSaveable { mutableStateOf(0) }
    var tasksCount by rememberSaveable { mutableStateOf(0) }
    
    // Статистика за сегодня
    var todayEmailsCount by rememberSaveable { mutableStateOf(0) }
    var todayEventsCount by rememberSaveable { mutableStateOf(0) }
    var todayTasksCount by rememberSaveable { mutableStateOf(0) }
    
    // Загрузка папок — отдельный эффект без зависимости от initialSyncDone
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        // Сначала загружаем из кэша
        val cached = FoldersCache.get(accountId)
        if (cached.isNotEmpty()) {
            folders = cached
            dataLoaded = true
        }
        // Подписываемся на Flow — он автоматически обновится когда данные в БД изменятся
        mailRepo.getFolders(accountId).collect { 
            folders = it
            FoldersCache.set(accountId, it)
            dataLoaded = true 
        }
    }
    
    // Загрузка счётчиков — отдельные эффекты
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        mailRepo.getFlaggedCount(accountId).collect { newCount ->
            if (newCount > 0 || flaggedCount == 0) {
                flaggedCount = newCount
            }
        }
    }
    
    LaunchedEffect(activeAccount?.id, lastSyncTime) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        noteRepo.getNotesCount(accountId).collect { newCount ->
            if (newCount >= notesCount || (!isSyncing && notesCount == 0)) {
                notesCount = newCount
            }
        }
    }
    
    LaunchedEffect(activeAccount?.id, lastSyncTime) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        // Сначала загружаем текущее значение напрямую
        val initialCount = calendarRepo.getEventsCountSync(accountId)
        if (initialCount > 0) eventsCount = initialCount
        // Затем подписываемся на изменения
        calendarRepo.getEventsCount(accountId).collect { newCount ->
            // Во время синхронизации не уменьшаем счётчик (защита от промежуточных состояний)
            // После синхронизации lastSyncTime изменится и LaunchedEffect перезапустится
            if (newCount >= eventsCount || (!isSyncing && eventsCount == 0)) {
                eventsCount = newCount
            }
        }
    }
    
    LaunchedEffect(activeAccount?.id, lastSyncTime) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        taskRepo.getActiveTasksCount(accountId).collect { newCount ->
            if (newCount >= tasksCount || (!isSyncing && tasksCount == 0)) {
                tasksCount = newCount
            }
        }
    }
    
    // Загрузка статистики за сегодня (при смене аккаунта и после синхронизации)
    LaunchedEffect(activeAccount?.id, lastSyncTime) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        todayEmailsCount = mailRepo.getTodayEmailsCount(accountId)
        todayEventsCount = calendarRepo.getEventsCountForDay(accountId, java.util.Date())
        todayTasksCount = taskRepo.getTodayTasksCount(accountId)
    }
    
    // Дополнительно подписываемся на изменения событий календаря
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        calendarRepo.getEventsCount(accountId).collect {
            // При изменении общего количества событий — обновляем статистику за сегодня
            todayEventsCount = calendarRepo.getEventsCountForDay(accountId, java.util.Date())
        }
    }

    
    // �������������� ������������� ��� PUSH ��� ����� �������� ����������
    // ���������� ��������� ������������� ��� �������������
    LaunchedEffect(activeAccount?.id) {
        val account = activeAccount ?: return@LaunchedEffect
        InitialSyncController.startSyncIfNeeded(context, account.id, mailRepo, settingsRepo)
    }
    
    // �������� ���������� � ������ ��� ��������� (������ ������ ������)
    var initialCheckDone by rememberSaveable { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        if (initialCheckDone) return@LaunchedEffect
        
        val count = accountRepo.getAccountCount()
        if (count == 0) {
            onNavigateToSetup()
        }
        accountsLoaded = true
        initialCheckDone = true
    }
    
    // Автопроверка обновлений
    var showAutoUpdateDialog by remember { mutableStateOf(false) }
    var autoUpdateInfo by remember { mutableStateOf<com.iwo.mailclient.update.UpdateInfo?>(null) }
    
    LaunchedEffect(initialCheckDone) {
        if (!initialCheckDone) return@LaunchedEffect
        
        // Проверяем настройку интервала
        val interval = settingsRepo.getUpdateCheckIntervalSync()
        if (interval == com.iwo.mailclient.data.repository.SettingsRepository.UpdateCheckInterval.NEVER) return@LaunchedEffect
        
        val lastCheck = settingsRepo.getLastUpdateCheckTimeSync()
        val intervalMs = interval.days * 24 * 60 * 60 * 1000L
        
        // Проверяем прошёл ли интервал
        if (System.currentTimeMillis() - lastCheck < intervalMs) return@LaunchedEffect
        
        // Проверяем обновления
        kotlinx.coroutines.delay(2000) // Даём приложению загрузиться
        try {
            val updateChecker = com.iwo.mailclient.update.UpdateChecker(context)
            val isRussian = settingsRepo.getLanguageSync() == "ru"
            when (val result = updateChecker.checkForUpdate(isRussian)) {
                is com.iwo.mailclient.update.UpdateResult.Available -> {
                    if (settingsRepo.shouldShowUpdateDialog(result.info.versionCode)) {
                        autoUpdateInfo = result.info
                        showAutoUpdateDialog = true
                        settingsRepo.setLastUpdateCheckTime(System.currentTimeMillis())
                    }
                }
                else -> {
                    settingsRepo.setLastUpdateCheckTime(System.currentTimeMillis())
                }
            }
        } catch (_: Exception) { }
    }
    
   // ����������� �������� ��������� (������ ������ �������� �� Flow)
    LaunchedEffect(accounts) {
        // ���� ���� Flow ��������� ���������� (�� ����������� �� ��������� emptyList)
        if (!accountsLoaded || !initialCheckDone) return@LaunchedEffect
        
        // ���� ����� Flow ��������� ����������
        kotlinx.coroutines.delay(500)
        
        // ������������� ����� ������ ������ � ��
        val actualCount = accountRepo.getAccountCount()
        if (actualCount == 0) {
            onNavigateToSetup()
        }
    }
    
    // ���������� ��������� scope ��� �������������, ����� ��� �� ����������� ��� �������� ������
    val manualSyncScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    
    // ������� scope ��� ����������� Composable
    DisposableEffect(Unit) {
        onDispose {
            manualSyncScope.cancel()
        }
    }

    
    fun syncFolders() {
        // Проверяем сеть перед синхронизацией
        if (!NetworkMonitor.isNetworkAvailable(context)) {
            val isRussian = settingsRepo.getLanguageSync() == "ru"
            val message = if (isRussian) "Нет сети" else "No network"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }
        
        activeAccount?.let { account ->
            manualSyncScope.launch {
                isLoading = true
                
                try {
                   // ������� �� ��� ������������� - 60 ������
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                         // �������������� �����
                        val result = withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                        
                        if (result is com.iwo.mailclient.eas.EasResult.Error) {
                            return@withTimeoutOrNull
                        }
                        
                        // �������������� ������ ��� ���� ����� � ��������
                        val emailFolderTypes = listOf(1, 2, 3, 4, 5, 6, 11, 12)
                        val currentFolders = withContext(Dispatchers.IO) {
                            com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                                .folderDao().getFoldersByAccountList(account.id)
                        }
                        val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                        
                        // �������������� ��������������� � ��������� �� ������
                        withContext(Dispatchers.IO) {
                            kotlinx.coroutines.supervisorScope {
                                foldersToSync.map { folder ->
                                    launch {
                                        try {
                                            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                                                mailRepo.syncEmails(account.id, folder.id)
                                            }
                                        } catch (_: Exception) { }
                                    }
                                }.forEach { it.join() }
                            }
                        }
                        
                        // ��������� ����� �������������
                        settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    }
                } catch (_: Exception) { }
                
                isLoading = false
            }
        }
    }
    
    // ������ �������� �����
    if (showCreateFolderDialog) {
        val folderCreatedMsg = Strings.folderCreated
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { 
                showCreateFolderDialog = false
                newFolderName = ""
            },
            title = { Text(Strings.createFolder) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(Strings.folderName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
                TextButton(
                    onClick = {
                        activeAccount?.let { account ->
                            scope.launch {
                                isCreatingFolder = true
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.createFolder(account.id, newFolderName)
                                }
                                isCreatingFolder = false
                                when (result) {
                                    is com.iwo.mailclient.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderCreatedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    is com.iwo.mailclient.eas.EasResult.Error -> {
                                        val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                        android.widget.Toast.makeText(
                                            context, 
                                            localizedMsg, 
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                showCreateFolderDialog = false
                                newFolderName = ""
                            }
                        }
                    },
                    enabled = newFolderName.isNotBlank() && !isCreatingFolder
                ) {
                    if (isCreatingFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.save)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showCreateFolderDialog = false
                    newFolderName = ""
                }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // ������ �������� �����
    folderToDelete?.let { folder ->
        val folderDeletedMsg = Strings.folderDeleted
        val isRussianLang = LocalLanguage.current == AppLanguage.RUSSIAN
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(Strings.deleteFolder) },
            text = { 
                Text(Strings.deleteFolderConfirm) 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeAccount?.let { account ->
                            scope.launch {
                                isDeletingFolder = true
                                com.iwo.mailclient.util.SoundPlayer.playDeleteSound(context)
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.deleteFolder(account.id, folder.id)
                                }
                                isDeletingFolder = false
                                when (result) {
                                    is com.iwo.mailclient.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderDeletedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        // �ަ-�-�-�-��TϦ��- T�����T��-�� ���-���-��
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                                    }
                                    is com.iwo.mailclient.eas.EasResult.Error -> {
                                        val localizedMsg = NotificationStrings.localizeError(result.message, isRussianLang)
                                        android.widget.Toast.makeText(
                                            context, 
                                            localizedMsg, 
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                folderToDelete = null
                            }
                        }
                    },
                    enabled = !isDeletingFolder
                ) {
                    if (isDeletingFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.yes)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(Strings.no)
                }
            }
        )
    }
    
    // �ܦ��-T� �+����T�T¦-���� T� ���-�����-�� (��T��� �+�-�����-�- �-�-���-T¦���)
    folderForMenu?.let { folder ->
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderForMenu = null },
            title = { Text(folder.displayName) },
            text = {
                Column {
                    // �ߦ�T������-���-�-�-�-T�T�
                    ListItem(
                        headlineContent = { Text(Strings.rename) },
                        leadingContent = { Icon(AppIcons.Edit, null) },
                        modifier = Modifier.clickable {
                            folderForMenu = null
                            renameNewName = folder.displayName
                            folderToRename = folder
                        }
                    )
                    // ��+�-����T�T�
                    ListItem(
                        headlineContent = { Text(Strings.delete, color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(AppIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable {
                            folderForMenu = null
                            folderToDelete = folder
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { folderForMenu = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // �Ԧ��-���-�� ����T������-���-�-�-�-�-��T� ���-������
    folderToRename?.let { folder ->
        val folderRenamedMsg = Strings.folderRenamed
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { 
                folderToRename = null
                renameNewName = ""
            },
            title = { Text(Strings.renameFolder) },
            text = {
                OutlinedTextField(
                    value = renameNewName,
                    onValueChange = { renameNewName = it },
                    label = { Text(Strings.newName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeAccount?.let { account ->
                            scope.launch {
                                isRenamingFolder = true
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.renameFolder(account.id, folder.id, renameNewName)
                                }
                                isRenamingFolder = false
                                when (result) {
                                    is com.iwo.mailclient.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderRenamedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        // �ަ-�-�-�-��TϦ��- T�����T��-�� ���-���-��
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                                    }
                                    is com.iwo.mailclient.eas.EasResult.Error -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            result.message, 
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                folderToRename = null
                                renameNewName = ""
                            }
                        }
                    },
                    enabled = renameNewName.isNotBlank() && renameNewName != folder.displayName && !isRenamingFolder
                ) {
                    if (isRenamingFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(Strings.rename)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    folderToRename = null
                    renameNewName = ""
                }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог автообновления
    if (showAutoUpdateDialog && autoUpdateInfo != null) {
        AutoUpdateDialog(
            updateInfo = autoUpdateInfo!!,
            context = context,
            settingsRepo = settingsRepo,
            onDismiss = { 
                showAutoUpdateDialog = false 
            },
            onLater = {
                // Запоминаем что пользователь отложил эту версию
                scope.launch {
                    settingsRepo.setUpdateDismissedVersion(autoUpdateInfo!!.versionCode)
                    settingsRepo.setLastUpdateCheckTime(System.currentTimeMillis())
                }
                showAutoUpdateDialog = false
            }
        )
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                DrawerContent(
                    accounts = accounts,
                    activeAccount = activeAccount,
                    folders = folders,
                    flaggedCount = flaggedCount,
                    notesCount = notesCount,
                    eventsCount = eventsCount,
                    tasksCount = tasksCount,
                    showAccountPicker = showAccountPicker,
                    onToggleAccountPicker = { showAccountPicker = !showAccountPicker },
                    onAccountSelected = { account ->
                        scope.launch {
                            accountRepo.setActiveAccount(account.id)
                            showAccountPicker = false
                        }
                    },
                    onAddAccount = {
                        scope.launch { drawerState.close() }
                        onNavigateToSetup()
                    },
                    onFolderSelected = { folder ->
                        scope.launch { drawerState.close() }
                        onNavigateToEmailList(folder.id)
                    },
                    onFavoritesClick = {
                        scope.launch { drawerState.close() }
                        // �ߦ�T���TŦ-�+���- �-�- Tͦ�T��-�- �����-T��-�-�-T�T� (��T����-��Ț�Tæ��- T�����TƦ��-��Ț-T˦� ID)
                        onNavigateToEmailList("favorites")
                    },
                    onSettingsClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings()
                    },
                    onContactsClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToContacts()
                    },
                    onNotesClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToNotes()
                    },
                    onCalendarClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToCalendar()
                    },
                    onTasksClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToTasks()
                    },
                    onCreateFolder = {
                        scope.launch { drawerState.close() }
                        showCreateFolderDialog = true
                    },
                    onFolderLongClick = { folder ->
                        scope.launch { drawerState.close() }
                        folderForMenu = folder
                    }
                )
            }
        }
    ) {
        // �ߦ-���-��T˦-�-���- ���-��T�Tæ���T� ���-���- �-�����-Tæ-T� �-�� ���-��T�Tæ����-
        if (activeAccount == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        Strings.loading,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@ModalNavigationDrawer
        }
        
        Scaffold(
            topBar = {
                SearchTopBar(
                    accountName = activeAccount?.displayName ?: "",
                    accountColor = activeAccount?.color ?: 0xFF1976D2.toInt(),
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onSearchClick = onNavigateToSearch
                )
            },
            floatingActionButton = {
                val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
                val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                
                // Анимация покачивания карандашика и пульсации
                val pencilRotation: Float
                val fabScale: Float
                if (animationsEnabled) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pencil")
                    pencilRotation = infiniteTransition.animateFloat(
                        initialValue = -8f,
                        targetValue = 8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pencilRotation"
                    ).value
                    fabScale = infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "fabScale"
                    ).value
                } else {
                    pencilRotation = 0f
                    fabScale = 1f
                }
                
                FloatingActionButton(
                    onClick = onNavigateToCompose,
                    containerColor = colorTheme.gradientStart,
                    contentColor = Color.White,
                    modifier = Modifier.graphicsLayer {
                        scaleX = fabScale
                        scaleY = fabScale
                    }
                ) {
                    Icon(
                        AppIcons.Edit, 
                        Strings.compose,
                        modifier = Modifier.graphicsLayer {
                            rotationZ = pencilRotation
                        }
                    )
                }
            }
        ) { padding ->
            // Основное содержимое с карточками и папками
            HomeContent(
                activeAccount = activeAccount,
                folders = folders,
                flaggedCount = flaggedCount,
                notesCount = notesCount,
                eventsCount = eventsCount,
                tasksCount = tasksCount,
                todayEmailsCount = todayEmailsCount,
                todayEventsCount = todayEventsCount,
                todayTasksCount = todayTasksCount,
                isLoading = isLoading,
                isSyncing = isSyncing,
                noNetwork = InitialSyncController.noNetwork,
                onSyncFolders = { syncFolders() },
                onFolderClick = onNavigateToEmailList,
                onContactsClick = onNavigateToContacts,
                onNotesClick = onNavigateToNotes,
                onCalendarClick = onNavigateToCalendar,
                onTasksClick = onNavigateToTasks,
                onSettingsClick = onNavigateToSettings,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
private fun HomeContent(
    activeAccount: AccountEntity?,
    folders: List<FolderEntity>,
    flaggedCount: Int,
    notesCount: Int = 0,
    eventsCount: Int = 0,
    tasksCount: Int = 0,
    todayEmailsCount: Int = 0,
    todayEventsCount: Int = 0,
    todayTasksCount: Int = 0,
    isLoading: Boolean,
    isSyncing: Boolean = false,
    noNetwork: Boolean = false,
    onSyncFolders: () -> Unit,
    onFolderClick: (String) -> Unit,
    onContactsClick: () -> Unit,
    onNotesClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onTasksClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDonateDialog by rememberSaveable { mutableStateOf(false) }
    
    // Pull-to-refresh state
    val isRefreshing = isSyncing || isLoading
    val pullRefreshState = rememberPullRefreshState(isRefreshing, onSyncFolders)
    
    // ��T����-T� ���-T������+�-���� T����-T�T��-�-�����-TƦ���
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val lastSyncTime by settingsRepo.lastSyncTime.collectAsState(initial = 0L)
    
    // ����������� ��������� Battery Saver ����� BroadcastReceiver (���������� �������)
    val isBatterySaverActive by settingsRepo.batterySaverState.collectAsState(initial = settingsRepo.isBatterySaverActive())
    // Per-account: показываем предупреждение если активный аккаунт не игнорирует Battery Saver
    val showBatterySaverWarning = isBatterySaverActive && (activeAccount?.ignoreBatterySaver != true)
    
    // ��������� ��� ������� ������������ (����������� ��� �������� ������, ������������ ��� �����������)
    var isRecommendationDismissed by rememberSaveable { mutableStateOf(false) }
    
    // �������������� �������� ����� (��������� ��� LazyColumn)
    val inboxName = Strings.inbox
    val draftsName = Strings.drafts
    val trashName = Strings.trash
    val sentName = Strings.sent
    val favoritesName = Strings.favorites
    val notesName = Strings.notes
    val calendarName = Strings.calendar
    val tasksName = Strings.tasks
    val foldersTitle = Strings.folders
    val refreshText = Strings.refresh
    val emailsCountText = Strings.emailsCount
    val emptyText = Strings.empty
    val contactsName = Strings.contacts
    
    // Переменные для диалога доната (вынесены для использования вне LazyColumn)
    val accountCopiedText = Strings.accountCopied
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // Реактивный баннер "Нет сети" - показывается сразу при отключении
        item {
            NetworkBanner(modifier = Modifier.padding(bottom = 4.dp))
        }
        
        // Индикатор синхронизации (карточка)
        if ((isSyncing || isLoading) && !noNetwork) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = Strings.syncingMail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        
        // �ئ-�+�����-T¦-T� Battery Saver
        if (showBatterySaverWarning) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSettingsClick() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.BatterySaver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = Strings.batterySaverActive,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
        
        // ��T����-��T�T�T¦-���-�-�-T� ���-T�T¦-TǦ��- ��� T��-�-T����-���-�-T˦� ��T��-�+�����-T¦-T˦� T�T¦���T� T� �-�-���-�-TƦ�����
        item {
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            var welcomeVisible by remember { mutableStateOf(!animationsEnabled) }
            LaunchedEffect(animationsEnabled) { welcomeVisible = true }
            
            val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
            val cardContent = @Composable {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = colorTheme.gradientStart
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        colorTheme.gradientStart,
                                        colorTheme.gradientEnd
                                    )
                                )
                            )
                            .padding(24.dp)
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // �Ц-�-T¦-T� �-�����-Tæ-T¦-
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeAccount?.color ?: 0xFF1976D2.toInt())),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = activeAccount?.displayName?.firstOrNull()?.uppercase() ?: "����",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = Strings.hello,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        // Анимированная рука 👋
                                        val handRotation: Float
                                        if (animationsEnabled) {
                                            val infiniteTransition = rememberInfiniteTransition(label = "wave")
                                            handRotation = infiniteTransition.animateFloat(
                                                initialValue = -15f,
                                                targetValue = 15f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(400, easing = FastOutSlowInEasing),
                                                    repeatMode = RepeatMode.Reverse
                                                ),
                                                label = "handRotation"
                                            ).value
                                        } else {
                                            handRotation = 0f
                                        }
                                        Text(
                                            text = Strings.waveEmoji,
                                            style = MaterialTheme.typography.headlineSmall,
                                            modifier = Modifier.graphicsLayer {
                                                rotationZ = handRotation
                                                transformOrigin = TransformOrigin(0.7f, 0.9f) // Вращение от запястья
                                            }
                                        )
                                    }
                                    Text(
                                        text = activeAccount?.email ?: Strings.loading,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            
                            // ��T����-T� ���-T������+�-���� T����-T�T��-�-�����-TƦ��� - ���-���-��T˦-�-���- T¦-��Ț��- ���-���+�- �-�� ���+T�T� T����-T�T��-�-�����-TƦ�T�
                            if (!isSyncing && !isLoading && lastSyncTime > 0) {
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                val formatter = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                                val syncTimeText = "${Strings.lastSync} ${formatter.format(java.util.Date(lastSyncTime))}"
                                
                                Text(
                                    text = syncTimeText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
            
            if (animationsEnabled) {
                AnimatedVisibility(
                    visible = welcomeVisible,
                    enter = fadeIn(animationSpec = tween(400)) + 
                            scaleIn(
                                initialScale = 0.92f,
                                animationSpec = tween(400, easing = FastOutSlowInEasing)
                            )
                ) {
                    cardContent()
                }
            } else {
                cardContent()
            }
        }
        
        // Карточка "Сегодня" — статистика за день
        val hasTodayStats = todayEmailsCount > 0 || todayEventsCount > 0 || todayTasksCount > 0
        if (hasTodayStats) {
            item {
                TodayStatsCard(
                    emailsCount = todayEmailsCount,
                    eventsCount = todayEventsCount,
                    tasksCount = todayTasksCount
                )
            }
        }
        
        // �দ���-�-���-�+�-TƦ�T� �+�-T� - ��T����� ��T�T�T� ���-������ T� > 1000 ����T����-
        val foldersOver1000 = folders.filter { 
            it.type in listOf(2, 3, 4, 5) && it.totalCount > 1000 
        }
        if (foldersOver1000.isNotEmpty() && !isRecommendationDismissed) {
            item {
                val folderNames = foldersOver1000.map { folder ->
                    when (folder.type) {
                        2 -> inboxName
                        3 -> draftsName
                        4 -> trashName
                        5 -> sentName
                        else -> folder.displayName
                    }
                }
                val recommendationText = if (folderNames.size == 1) {
                    Strings.cleanupFolderRecommendation(folderNames.first())
                } else {
                    Strings.cleanupFoldersRecommendation(folderNames.joinToString(", "))
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = Strings.recommendationOfDay,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = recommendationText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        IconButton(onClick = { isRecommendationDismissed = true }) {
                            Icon(
                                AppIcons.Close,
                                contentDescription = Strings.close,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        
        // ������� ������ �� ����������
        if (folders.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = foldersTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onSyncFolders) {
                        Icon(AppIcons.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(refreshText)
                    }
                }
            }
            
            // �������� ����� �� �����
            val mainFolders = folders.filter { it.type in listOf(2, 3, 4, 5) }
            
            // �������: ��������, ������������, ���������, ���������, ���������, ��������
            val orderedFolders = mutableListOf<FolderDisplayData>()
            
            // �������� (type 2)
            mainFolders.find { it.type == 2 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, inboxName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ������������ (type 5)
            mainFolders.find { it.type == 5 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, sentName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ��������� (type 3)
            mainFolders.find { it.type == 3 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, draftsName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ��������� (type 4)
            mainFolders.find { it.type == 4 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, trashName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // ���������
            orderedFolders.add(FolderDisplayData("favorites", favoritesName, flaggedCount, 0, -1))
            // ��������
            orderedFolders.add(FolderDisplayData("contacts", contactsName, 0, 0, -2))
            // �������
            orderedFolders.add(FolderDisplayData("notes", notesName, notesCount, 0, -3))
            // ���������
            orderedFolders.add(FolderDisplayData("calendar", calendarName, eventsCount, 0, -4))
            // ������
            orderedFolders.add(FolderDisplayData("tasks", tasksName, tasksCount, 0, -5))
            
            val displayFolders: List<FolderDisplayData> = orderedFolders.toList()
            
            val chunkedFolders: List<List<FolderDisplayData>> = displayFolders.chunked(2)
            itemsIndexed(chunkedFolders, key = { _, row -> row.firstOrNull()?.id ?: "" }) { index: Int, rowFolders: List<FolderDisplayData> ->
                val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                // Анимация только для первых 4 рядов при первом показе
                val shouldAnimate = animationsEnabled && index < 4
                var visible by rememberSaveable { mutableStateOf(!shouldAnimate) }
                LaunchedEffect(Unit) {
                    if (shouldAnimate && !visible) {
                        kotlinx.coroutines.delay(index * 80L)
                        visible = true
                    }
                }
                
                if (shouldAnimate && !visible) {
                    // Placeholder пока анимация не началась
                    Spacer(modifier = Modifier.height(80.dp))
                } else if (shouldAnimate) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(300)) + 
                                slideInVertically(
                                    initialOffsetY = { it / 2 },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                )
                    ) {
                        FolderRow(rowFolders, onFolderClick, onContactsClick, onNotesClick, onCalendarClick, onTasksClick)
                    }
                } else {
                    FolderRow(rowFolders, onFolderClick, onContactsClick, onNotesClick, onCalendarClick, onTasksClick)
                }
            }
        } else if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            Strings.loadingFolders,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            AppIcons.FolderOff,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            Strings.noFoldersFound,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            Strings.tapToSync,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = onSyncFolders) {
                            Icon(AppIcons.Sync, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(Strings.synchronize)
                        }
                    }
                }
            }
        }
        
        // Кнопка "Посмотреть историю изменений" в changelog
        item {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val isRu = LocalLanguage.current == AppLanguage.RUSSIAN
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            val changelogUrl = if (isRu) 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_RU.md"
            else 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_EN.md"
            
            // �Ц-���-�-TƦ�T� ��Tæ�T�T��-TƦ��� (T¦-��Ț��- ��T����� �-�-���-�-TƦ��� �-����T�TǦ��-T�)
            val pulseScale: Float = if (animationsEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "changelogPulse")
                infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.02f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "changelogScale"
                ).value
            } else {
                1f
            }
            
            // �Ц-���-�-TƦ�T� T��-��TǦ��-��T� ��T��-�-��T�T�
            val borderAlpha: Float = if (animationsEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "changelogBorder")
                infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "borderAlpha"
                ).value
            } else {
                1f
            }
            
            OutlinedButton(
                onClick = { uriHandler.openUri(changelogUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .scale(pulseScale),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = com.iwo.mailclient.ui.theme.LocalColorTheme.current.gradientStart
                ),
                border = BorderStroke(
                    1.5.dp, 
                    com.iwo.mailclient.ui.theme.LocalColorTheme.current.gradientStart.copy(alpha = borderAlpha)
                )
            ) {
                Icon(
                    AppIcons.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(Strings.viewChangelog)
            }
        }
        
        // Кнопка поддержать разработчика
        item {
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            
            // Пульсирующая анимация
            val pulseScale: Float = if (animationsEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.03f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                ).value
            } else {
                1f
            }
            
            Button(
                onClick = { showDonateDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .scale(pulseScale),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE91E63)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(AppIcons.Favorite, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(Strings.supportDeveloper, fontWeight = FontWeight.SemiBold)
            }
        }
        
        // Отступ снизу для FAB
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
    
    // Диалог доната - вынесен за пределы LazyColumn для сохранения при повороте экрана
    if (showDonateDialog) {
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDonateDialog = false },
            icon = { Icon(AppIcons.Favorite, null, tint = Color(0xFFE91E63)) },
            title = { 
                Text(
                    Strings.supportDeveloper,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ) 
            },
            text = {
                Column {
                    Text(
                        Strings.supportText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            DonateInfoRow(Strings.recipient, "Додонов Андрей Игоревич")
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    Strings.accountNumber,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = false,
                                    modifier = Modifier.widthIn(min = 70.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                SelectionContainer {
                                    Text(
                                        "4081 7810 3544 0529 6071",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            DonateInfoRow(Strings.bank, "Поволжский Банк ПАО Сбербанк")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDonateDialog = false }) {
                    Text(Strings.closeDialog)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Account", "40817810354405296071")
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, accountCopiedText, android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(AppIcons.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Strings.copyAccount)
                }
            }
        )
    }
        
        // PullRefreshIndicator убран — используется карточка "Синхронизация..."
    } // Box with pullRefresh
}

/**
 * Карточка статистики за сегодня
 * Адаптивная для портретной и альбомной ориентации
 */
@Composable
private fun TodayStatsCard(
    emailsCount: Int,
    eventsCount: Int,
    tasksCount: Int
) {
    val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
    val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    
    // Анимация появления
    var visible by remember { mutableStateOf(!animationsEnabled) }
    LaunchedEffect(animationsEnabled) { visible = true }
    
    val cardContent = @Composable {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = MaterialTheme.shapes.large,
                    ambientColor = colorTheme.gradientStart.copy(alpha = 0.1f),
                    spotColor = colorTheme.gradientStart.copy(alpha = 0.15f)
                ),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        colorTheme.gradientStart.copy(alpha = 0.6f),
                        colorTheme.gradientEnd.copy(alpha = 0.4f)
                    )
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Заголовок с акцентным фоном
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    colorTheme.gradientStart.copy(alpha = 0.1f),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        AppIcons.Schedule,
                        contentDescription = null,
                        tint = colorTheme.gradientStart,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRussian) "Сегодня" else "Today",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorTheme.gradientStart
                    )
                }
                
                // Статистика в ряд (адаптивно)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Письма — цвет как у Inbox
                    TodayStatItem(
                        icon = AppIcons.Email,
                        count = emailsCount,
                        label = if (isRussian) "получено" else "received",
                        color = Color(0xFF5C6BC0) // Indigo — как Inbox
                    )
                    
                    // Разделитель
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                    
                    // События — цвет как у Календаря
                    TodayStatItem(
                        icon = AppIcons.CalendarMonth,
                        count = eventsCount,
                        label = if (isRussian) "событий" else "events",
                        color = Color(0xFF42A5F5) // Blue — как Календарь
                    )
                    
                    // Разделитель
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                    
                    // Задачи — цвет как у Задач
                    TodayStatItem(
                        icon = AppIcons.Task,
                        count = tasksCount,
                        label = if (isRussian) "задач" else "tasks",
                        color = Color(0xFFAB47BC) // Purple — как Задачи
                    )
                }
            }
        }
    }
    
    if (animationsEnabled) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(300)) + 
                    slideInVertically(
                        initialOffsetY = { -it / 4 },
                        animationSpec = tween(300, easing = FastOutSlowInEasing)
                    )
        ) {
            cardContent()
        }
    } else {
        cardContent()
    }
}

/**
 * Элемент статистики для карточки "Сегодня"
 */
@Composable
private fun TodayStatItem(
    icon: ImageVector,
    count: Int,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun DonateInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier.widthIn(min = 70.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderCard(
    folder: FolderEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FolderCardDisplay(
        id = folder.id,
        name = folder.displayName,
        count = folder.totalCount,
        unreadCount = folder.unreadCount,
        type = folder.type,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun FolderRow(
    rowFolders: List<FolderDisplayData>,
    onFolderClick: (String) -> Unit,
    onContactsClick: () -> Unit,
    onNotesClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onTasksClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (rowFolders.size == 1) Arrangement.Center else Arrangement.spacedBy(12.dp)
    ) {
        rowFolders.forEach { folder: FolderDisplayData ->
            FolderCardDisplay(
                id = folder.id,
                name = folder.name,
                count = folder.count,
                unreadCount = folder.unreadCount,
                type = folder.type,
                onClick = { 
                    when (folder.id) {
                        "contacts" -> onContactsClick()
                        "notes" -> onNotesClick()
                        "calendar" -> onCalendarClick()
                        "tasks" -> onTasksClick()
                        else -> onFolderClick(folder.id)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
        // Если в ряду только одна карточка, добавляем пустое место справа для центрирования
        if (rowFolders.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderCardDisplay(
    id: String,
    name: String,
    count: Int,
    unreadCount: Int = 0,
    type: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // �Ц-���-�-TƦ�T� �-�-T�T�T¦-�-�- ��T��� �-�-���-T¦��� (T¦-��Ț��- ��T����� �-�-���-�-TƦ��� �-����T�TǦ��-T�)
    val scale by animateFloatAsState(
        targetValue = if (animationsEnabled && isPressed) 0.96f else 1f,
        animationSpec = if (animationsEnabled) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        } else {
            snap()
        },
        label = "scale"
    )
    
    // �Ц-���-�-TƦ�T� T¦��-�� ��T��� �-�-���-T¦���
    val elevation by animateDpAsState(
        targetValue = if (animationsEnabled && isPressed) 1.dp else 4.dp,
        animationSpec = if (animationsEnabled) tween(150) else snap(),
        label = "elevation"
    )
    
    // ��Tæ�T�T��-TƦ�T� �� ���-���-TǦ��-�-�-���� �����-�-���� (T¦-��Ț��- ��T����� �-�-���-�-TƦ��� �-����T�TǦ��-T�)
    val iconScale: Float
    val iconRotation: Float
    
    if (animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "icon")
        iconScale = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconScale"
        ).value
        
        iconRotation = infiniteTransition.animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconRotation"
        ).value
    } else {
        iconScale = 1f
        iconRotation = 0f
    }
    
    // ���������� ����� � ������ ��� ������ ����� �����
    val folderColors = when (type) {
        2 -> FolderColorsData(
            AppIcons.Inbox, 
            listOf(Color(0xFF5C6BC0), Color(0xFF3949AB)) // Indigo
        )
        3 -> FolderColorsData(
            AppIcons.Drafts, 
            listOf(Color(0xFF78909C), Color(0xFF546E7A)) // Blue Grey
        )
        4 -> FolderColorsData(
            AppIcons.Delete, 
            listOf(Color(0xFFEF5350), Color(0xFFE53935)) // Red
        )
        5 -> FolderColorsData(
            AppIcons.Send, 
            listOf(Color(0xFF7E57C2), Color(0xFF5E35B1)) // Deep Purple
        )
        6 -> FolderColorsData(
            AppIcons.Outbox, 
            listOf(Color(0xFF26A69A), Color(0xFF00897B)) // Teal
        )
        -1 -> FolderColorsData(
            AppIcons.Star, 
            listOf(Color(0xFFFFCA28), Color(0xFFFFA000)) // Amber
        )
        -2 -> FolderColorsData(
            AppIcons.Contacts, 
            listOf(Color(0xFF4FC3F7), Color(0xFF29B6F6)) // Light Blue
        )
        -3 -> FolderColorsData(
            AppIcons.StickyNote, 
            listOf(Color(0xFF81C784), Color(0xFF66BB6A)) // Green
        )
        -4 -> FolderColorsData(
            AppIcons.Calendar, 
            listOf(Color(0xFF42A5F5), Color(0xFF1E88E5)) // Blue
        )
        -5 -> FolderColorsData(
            AppIcons.CheckCircle, 
            listOf(Color(0xFFAB47BC), Color(0xFF8E24AA)) // Purple
        )
        else -> FolderColorsData(
            AppIcons.Folder, 
            listOf(Color(0xFF90A4AE), Color(0xFF78909C)) // Blue Grey Light
        )
    }
    
    Card(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 80.dp)
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        interactionSource = interactionSource
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(folderColors.gradientColors)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // �ئ��-�-���- T� ���-��Tæ�T��-��T��-TǦ-T˦- TĦ-�-�-�- �� �-�-���-�-TƦ�����
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                        .scale(if (animationsEnabled) iconScale else 1f)
                        .rotate(if (animationsEnabled) iconRotation else 0f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        folderColors.icon, 
                        null, 
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 120.dp)
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // Счётчик элементов
                    if (type != -2 && count > 0) {
                        Text(
                            text = "$count ${when (type) {
                                -3 -> Strings.pluralNotes(count)  // Заметки
                                -4 -> Strings.pluralEvents(count)  // Календарь
                                -5 -> Strings.pluralTasks(count)  // Задачи
                                else -> Strings.pluralEmails(count)  // Письма
                            }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                // Badge с непрочитанными или с избранными (только когда включены анимации)
                // Для черновиков (type 3) badge не показываем - они не имеют статуса "прочитано"
                if (unreadCount > 0 && type != 3 && type != 4) {
                    val badgeScale: Float = if (animationsEnabled) {
                        val badgeTransition = rememberInfiniteTransition(label = "badge")
                        badgeTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "badgeScale"
                        ).value
                    } else {
                        1f
                    }
                    
                    Badge(
                        modifier = Modifier.scale(badgeScale),
                        containerColor = folderColors.gradientColors.first(),
                        contentColor = Color.White
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    accountName: String,
    accountColor: Int,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
    // ��T��-�+�����-T¦-T˦� TĦ-�- �+��T� T¦-���-�-T��-
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        colorTheme.gradientStart,
                        colorTheme.gradientEnd
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSearchClick)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(AppIcons.Menu, Strings.menu, tint = colorTheme.primaryLight)
                }
                
                Text(
                    text = Strings.searchInMail,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(accountColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = accountName.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun DrawerContent(
    accounts: List<AccountEntity>,
    activeAccount: AccountEntity?,
    folders: List<FolderEntity>,
    flaggedCount: Int,
    notesCount: Int = 0,
    eventsCount: Int = 0,
    tasksCount: Int = 0,
    showAccountPicker: Boolean,
    onToggleAccountPicker: () -> Unit,
    onAccountSelected: (AccountEntity) -> Unit,
    onAddAccount: () -> Unit,
    onFolderSelected: (FolderEntity) -> Unit,
    onFavoritesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onContactsClick: () -> Unit = {},
    onNotesClick: () -> Unit = {},
    onCalendarClick: () -> Unit = {},
    onTasksClick: () -> Unit = {},
    onCreateFolder: () -> Unit = {},
    onFolderLongClick: (FolderEntity) -> Unit = {}
) {
    LazyColumn {
        // �צ-���-���-�-�-�� T� �-�����-Tæ-T¦-�-
        item {
            DrawerHeader(
                account = activeAccount,
                showPicker = showAccountPicker,
                onToggle = onToggleAccountPicker
            )
        }
        
        // ��T˦-�-T� �-�����-Tæ-T¦-
        if (showAccountPicker) {
            items(accounts, key = { it.id }) { account ->
                AccountItem(
                    account = account,
                    isActive = account.id == activeAccount?.id,
                    onClick = { onAccountSelected(account) }
                )
            }
            
            item {
                ListItem(
                    headlineContent = { Text(Strings.addAccount) },
                    leadingContent = { Icon(AppIcons.Add, null) },
                    modifier = Modifier.clickable(onClick = onAddAccount)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        
        // �ߦ-������ - T��-�-TǦ-���- �-T��-�-�-�-T˦� (��TŦ-�+T�Tɦ���, �禦T��-�-�-������, ��+�-��TѦ-�-T˦�, ��T¦�T��-�-�����-�-T˦�, ��T�TŦ-�+T�Tɦ���, �᦬�-�-)
        val mainFolderTypes = listOf(2, 3, 4, 5, 6, 11)
        val mainFolders = folders.filter { it.type in mainFolderTypes }
            .sortedBy { mainFolderTypes.indexOf(it.type) }
        
        items(mainFolders, key = { it.id }) { folder ->
            FolderItem(
                folder = folder,
                onClick = { onFolderSelected(folder) }
            )
        }
        
        // �ئ��-T��-�-�-T˦� - ���-T����� ��T�TŦ-�+T�Tɦ�T�
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onFavoritesClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.Star, null, tint = Color(0xFFFFB300))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.favorites,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (flaggedCount > 0) {
                        Text(
                            text = flaggedCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // ��T�T¦-��Ț-T˦� ���-������ (��T��-�-�� Contacts - type 9, T� �-�-T� T��-�-�� Tͦ�T��-�- ���-�-T¦-��T¦-�-)
        // Остальные папки (скрываем Contacts, Calendar, Notes, Tasks - они показаны отдельно)
        val hiddenFolderTypes = listOf(7, 8, 9, 10) // Tasks, Calendar, Contacts, Notes
        val otherFolders = folders.filter { folder ->
            folder.type !in mainFolderTypes && 
            folder.type !in hiddenFolderTypes
        }
        
        // Контакты
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onContactsClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.People, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.contacts,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        
        // �������
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onNotesClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.StickyNote, null, tint = Color(0xFFFF9800))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.notes,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (notesCount > 0) {
                        Text(
                            text = notesCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // ���������
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onCalendarClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.CalendarMonth, null, tint = Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.calendar,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (eventsCount > 0) {
                        Text(
                            text = eventsCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // ������
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clickable(onClick = onTasksClick),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(AppIcons.CheckCircle, null, tint = Color(0xFF9C27B0))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = Strings.tasks,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (tasksCount > 0) {
                        Text(
                            text = tasksCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Остальные папки
        if (otherFolders.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            items(otherFolders, key = { it.id }) { folder ->
                FolderItem(
                    folder = folder,
                    onClick = { onFolderSelected(folder) },
                    onLongClick = { onFolderLongClick(folder) }
                )
            }
        }
        
        // Создать папку
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(Strings.createFolder) },
                leadingContent = { Icon(AppIcons.CreateNewFolder, null) },
                modifier = Modifier.clickable(onClick = onCreateFolder)
            )
        }
        
        // Настройки
        item {
            ListItem(
                headlineContent = { Text(Strings.settings) },
                leadingContent = { Icon(AppIcons.Settings, null) },
                modifier = Modifier.clickable(onClick = onSettingsClick)
            )
        }
    }
}

@Composable
private fun DrawerHeader(
    account: AccountEntity?,
    showPicker: Boolean,
    onToggle: () -> Unit
) {
    val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
    // ��T��-�+�����-T¦-T˦� TŦ��+��T� ���-�� �- SetupScreen
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        colorTheme.gradientStart,
                        colorTheme.gradientEnd
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .padding(top = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(account?.color ?: 0xFF1976D2.toInt())),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = account?.displayName?.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account?.displayName ?: Strings.noAccount,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = account?.email ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Icon(
                    imageVector = if (showPicker) AppIcons.KeyboardArrowUp 
                                  else AppIcons.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun AccountItem(
    account: AccountEntity,
    isActive: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(account.displayName) },
        supportingContent = { Text(account.email) },
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
            if (isActive) {
                Icon(AppIcons.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItem(
    folder: FolderEntity,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val icon = when (folder.type) {
        2 -> AppIcons.Inbox
        3 -> AppIcons.Drafts
        4 -> AppIcons.Delete
        5 -> AppIcons.Send
        6 -> AppIcons.Outbox
        7 -> AppIcons.Task
        8 -> AppIcons.CalendarMonth
        9 -> AppIcons.Contacts
        11 -> AppIcons.Report // Junk/Spam
        else -> AppIcons.Folder
    }
    
    // ��-��T� �����-�-���� - ��T��-T��-T˦� �+��T� T����-�-�- �� ���-T������-T�
    val iconTint = when (folder.type) {
        4 -> MaterialTheme.colorScheme.error // ��+�-��TѦ-�-T˦�
        11 -> Color(0xFFE53935) // �᦬�-�- - ��T��-T��-T˦�
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    // Локализованное название папки
    val displayName = Strings.getFolderName(folder.type, folder.displayName)
    
    // �᦬T�T¦��-�-T˦� ���-������ �-����Ț�T� Tæ+�-��T�T�T� (�-����T�TǦ-T� �᦬�-�-)
    val isSystemFolder = folder.type in listOf(2, 3, 4, 5, 6, 11)
    
    // ��T����-��Ț�Tæ��- Surface T� combinedClickable �-�-��T�T¦- NavigationDrawerItem
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (!isSystemFolder && onLongClick != null) onLongClick else null
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = iconTint)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (folder.totalCount > 0) {
                Text(
                    text = folder.totalCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Диалог автоматического обновления
 */
@Composable
private fun AutoUpdateDialog(
    updateInfo: com.iwo.mailclient.update.UpdateInfo,
    context: android.content.Context,
    settingsRepo: com.iwo.mailclient.data.repository.SettingsRepository,
    onDismiss: () -> Unit,
    onLater: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }
    
    val updateChecker = remember { com.iwo.mailclient.update.UpdateChecker(context) }
    
    com.iwo.mailclient.ui.theme.ScaledAlertDialog(
        onDismissRequest = { if (downloadState !is DownloadState.Downloading) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    com.iwo.mailclient.ui.theme.AppIcons.Update,
                    null,
                    tint = colorTheme.gradientStart
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRussian) "Доступно обновление" else "Update available")
            }
        },
        text = {
            Column {
                Text(
                    text = "${if (isRussian) "Новая версия" else "New version"}: ${updateInfo.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                
                if (updateInfo.changelog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = updateInfo.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Прогресс скачивания
                if (downloadState is DownloadState.Downloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$downloadProgress%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (downloadState is DownloadState.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = (downloadState as DownloadState.Error).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is DownloadState.Idle, is DownloadState.Error -> {
                    Button(
                        onClick = {
                            scope.launch {
                                updateChecker.downloadUpdate(updateInfo.apkUrl).collect { progress ->
                                    when (progress) {
                                        is com.iwo.mailclient.update.DownloadProgress.Starting -> {
                                            downloadState = DownloadState.Downloading
                                        }
                                        is com.iwo.mailclient.update.DownloadProgress.Downloading -> {
                                            downloadProgress = progress.progress
                                        }
                                        is com.iwo.mailclient.update.DownloadProgress.Completed -> {
                                            downloadedFile = progress.file
                                            downloadState = DownloadState.Completed
                                        }
                                        is com.iwo.mailclient.update.DownloadProgress.Error -> {
                                            downloadState = DownloadState.Error(progress.message)
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colorTheme.gradientStart)
                    ) {
                        Text(if (isRussian) "Скачать" else "Download")
                    }
                }
                is DownloadState.Downloading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                is DownloadState.Completed -> {
                    Button(
                        onClick = {
                            downloadedFile?.let { updateChecker.installApk(it) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colorTheme.gradientStart)
                    ) {
                        Text(if (isRussian) "Установить" else "Install")
                    }
                }
            }
        },
        dismissButton = {
            if (downloadState !is DownloadState.Downloading) {
                TextButton(onClick = onLater) {
                    Text(if (isRussian) "Позже" else "Later")
                }
            }
        }
    )
}

private sealed class DownloadState {
    object Idle : DownloadState()
    object Downloading : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

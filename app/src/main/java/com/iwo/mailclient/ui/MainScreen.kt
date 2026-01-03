package com.iwo.mailclient.ui

import android.content.Context
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
import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Мульти-аккаунт синхронизация
 * Использует отдельный scope для синхронизации
 */

// Data classes для UI
private data class FolderDisplayData(val id: String, val name: String, val count: Int, val unreadCount: Int, val type: Int)
private data class FolderColorsData(val icon: ImageVector, val gradientColors: List<Color>)

/**
 * Глобальный кэш папок для предотвращения мерцания UI
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
    var isSyncing by mutableStateOf(false)
        private set
    var syncDone by mutableStateOf(false)
        private set
    
    private var syncJob: Job? = null
    // Используем ConcurrentHashMap.newKeySet() для потокобезопасного доступа
    private val syncedAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var currentSyncingAccountId: Long? = null
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val syncLock = kotlinx.coroutines.sync.Mutex()
    
    fun startSyncIfNeeded(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) {
        syncScope.launch {
            syncLock.withLock {
                // Пропускаем если этот аккаунт уже синхронизирован
                if (accountId in syncedAccounts) {
                    if (currentSyncingAccountId == null) {
                        isSyncing = false
                        syncDone = true
                    }
                    return@withLock
                }
                
                // Пропускаем если этот аккаунт уже синхронизируется и job активен
                if (currentSyncingAccountId == accountId && syncJob?.isActive == true) {
                    return@withLock
                }
                
                // Если идёт синхронизация другого аккаунта — отменяем её
                if (syncJob?.isActive == true && currentSyncingAccountId != accountId) {
                    syncJob?.cancel()
                    syncJob = null
                }
                
                currentSyncingAccountId = accountId
                isSyncing = true
                syncDone = false
            }
            
            // После освобождения lock - проверяем нужно ли запускать syncJob
            if (currentSyncingAccountId != accountId) {
                return@launch
            }
            
            if (syncJob?.isActive == true) {
                return@launch
            }
            
            // Сохраняем accountId для использования в finally
            val syncingAccountId = accountId
            
            syncJob = syncScope.launch {
                try {
                    // Проверяем что корутина не отменена перед каждой операцией
                    ensureActive()
                    delay(100)
                    
                    withTimeoutOrNull(300_000L) {
                        ensureActive()
                        withContext(Dispatchers.IO) { mailRepo.syncFolders(syncingAccountId) }
                        
                        ensureActive()
                        delay(200)
                        
                        // Синхронизируем для получения activeAccount?.id
                        val emailFolderTypes = listOf(1, 2, 3, 4, 5, 6, 11, 12)
                        val currentFolders = withContext(Dispatchers.IO) {
                            com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                                .folderDao().getFoldersByAccountList(syncingAccountId)
                        }
                        val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                        
                        ensureActive()
                        withContext(Dispatchers.IO) {
                            supervisorScope {
                                foldersToSync.map { folder ->
                                    launch {
                                        try {
                                            withTimeoutOrNull(120_000L) {
                                                mailRepo.syncEmails(syncingAccountId, folder.id)
                                            }
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                        }
                        
                        ensureActive()
                        // Синхронизируем контакты, заметки и календарь параллельно
                        withContext(Dispatchers.IO) {
                            supervisorScope {
                                // Контакты (личные + GAL)
                                launch {
                                    try {
                                        withTimeoutOrNull(120_000L) {
                                            val contactRepo = com.iwo.mailclient.data.repository.ContactRepository(context)
                                            // Синхронизируем личные контакты из папки Contacts
                                            contactRepo.syncExchangeContacts(syncingAccountId)
                                            // Синхронизируем контакты из глобальной адресной книги (GAL)
                                            contactRepo.syncGalContactsToDb(syncingAccountId)
                                        }
                                    } catch (_: Exception) { }
                                }
                                
                                // Заметки
                                launch {
                                    try {
                                        android.util.Log.d("InitialSync", "Starting notes sync for account $syncingAccountId")
                                        withTimeoutOrNull(60_000L) {
                                            val noteRepo = com.iwo.mailclient.data.repository.NoteRepository(context)
                                            val result = noteRepo.syncNotes(syncingAccountId)
                                            android.util.Log.d("InitialSync", "Notes sync result: $result")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("InitialSync", "Notes sync failed: ${e.message}", e)
                                    }
                                }
                                
                                // Календарь
                                launch {
                                    try {
                                        withTimeoutOrNull(60_000L) {
                                            val calendarRepo = com.iwo.mailclient.data.repository.CalendarRepository(context)
                                            calendarRepo.syncCalendar(syncingAccountId)
                                        }
                                    } catch (_: Exception) { }
                                }
                                
                                // Задачи
                                launch {
                                    try {
                                        withTimeoutOrNull(60_000L) {
                                            val taskRepo = com.iwo.mailclient.data.repository.TaskRepository(context)
                                            taskRepo.syncTasks(syncingAccountId)
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                        
                        settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    }
                    
                    // Помечаем аккаунт как синхронизированный только если не отменено
                    syncedAccounts.add(syncingAccountId)
                } catch (_: CancellationException) {
                    // Корутина отменена — не помечаем как синхронизированный
                } catch (_: Exception) {
                    // Другие ошибки — всё равно помечаем чтобы не зациклиться
                    syncedAccounts.add(syncingAccountId)
                } finally {
                    // Сбрасываем состояние только если это наш аккаунт
                    if (currentSyncingAccountId == syncingAccountId) {
                        isSyncing = false
                        syncDone = true
                        currentSyncingAccountId = null
                    }
                }
            }
        }
    }
    
    fun reset() {
        syncJob?.cancel()
        syncJob = null
        syncedAccounts.clear()
        currentSyncingAccountId = null
        syncDone = false
        isSyncing = false
    }
    
    fun resetAccount(accountId: Long) {
        syncedAccounts.remove(accountId)
        if (currentSyncingAccountId == accountId) {
            syncJob?.cancel()
            syncJob = null
            currentSyncingAccountId = null
            isSyncing = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToEmailList: (String) -> Unit,
    onNavigateToCompose: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToEmailDetail: (String) -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
    onNavigateToNotes: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Используем RepositoryProvider для ленивой инициализации (оптимизация памяти)
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val mailRepo = remember { RepositoryProvider.getMailRepository(context) }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val noteRepo = remember { RepositoryProvider.getNoteRepository(context) }
    val calendarRepo = remember { RepositoryProvider.getCalendarRepository(context) }
    val taskRepo = remember { RepositoryProvider.getTaskRepository(context) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    
    val accounts by accountRepo.accounts.collectAsState(initial = emptyList())
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    
    // Используем кэш для предотвращения мерцания при возврате на экран
    var folders by remember { mutableStateOf(FoldersCache.get(activeAccount?.id ?: 0L)) }
    var flaggedCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var accountsLoaded by remember { mutableStateOf(false) }
    // Флаг что данные загружены
    var dataLoaded by remember { mutableStateOf(folders.isNotEmpty()) }
    
    // Получаем синхронизацию напрямую из контроллера
    val isSyncing = InitialSyncController.isSyncing
    val initialSyncDone = InitialSyncController.syncDone
    
   // Диалог создания папки
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    
    // Диалог удаления папки
    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var isDeletingFolder by remember { mutableStateOf(false) }
    
    // Диалог переименования папки
    var folderToRename by remember { mutableStateOf<FolderEntity?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var isRenamingFolder by remember { mutableStateOf(false) }
    
    // Меню действий с папкой (долгое нажатие)
    var folderForMenu by remember { mutableStateOf<FolderEntity?>(null) }
    
    // Флаг для активации первого аккаунта (только при первом запуске)
    var firstAccountActivated by rememberSaveable { mutableStateOf(false) }
    
    // Активируем первый аккаунт только при первом запуске, если нет активного
    // НЕ переключаем на первый при возврате с экрана добавления аккаунта
    LaunchedEffect(Unit) {
        if (!firstAccountActivated) {
            firstAccountActivated = true
            // Проверяем напрямую в БД, а не через Flow
            val hasActive = accountRepo.getActiveAccountSync() != null
            if (!hasActive) {
                // Ждём первое значение из Flow аккаунтов
                val accountsList = accountRepo.accounts.first()
                accountsList.firstOrNull()?.let { 
                    accountRepo.setActiveAccount(it.id) 
                }
            }
        }
    }
    
       // Загружаем папки и счётчики после смены аккаунта (объединено для оптимизации)
    var notesCount by remember { mutableStateOf(0) }
    var eventsCount by remember { mutableStateOf(0) }
    var tasksCount by remember { mutableStateOf(0) }
    
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        // Сначала загружаем из кэша
        val cached = FoldersCache.get(accountId)
        if (cached.isNotEmpty()) {
            folders = cached
            dataLoaded = true
        }
        // Запускаем все Flow параллельно
        launch { mailRepo.getFolders(accountId).collect { folders = it; FoldersCache.set(accountId, it); dataLoaded = true } }
        launch { mailRepo.getFlaggedCount(accountId).collect { flaggedCount = it } }
        launch { noteRepo.getNotesCount(accountId).collect { notesCount = it } }
        launch { calendarRepo.getEventsCount(accountId).collect { eventsCount = it } }
        launch { taskRepo.getActiveTasksCount(accountId).collect { tasksCount = it } }
    }

    
    // Первоначальная синхронизация при PUSH или после загрузки приложения
    // Используем отдельный синхронизатор для синхронизации
    LaunchedEffect(activeAccount?.id) {
        val account = activeAccount ?: return@LaunchedEffect
        InitialSyncController.startSyncIfNeeded(context, account.id, mailRepo, settingsRepo)
    }
    
    // Проверка обновлений — запуск при активации (убрали лишний запуск)
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
    
   // Отслеживаем удаление аккаунтов (убрали лишнюю подписку на Flow)
    LaunchedEffect(accounts) {
        // Ждём пока Flow аккаунтов загрузится (не срабатываем на начальный emptyList)
        if (!accountsLoaded || !initialCheckDone) return@LaunchedEffect
        
        // Даём время Flow аккаунтов обновиться
        kotlinx.coroutines.delay(500)
        
        // Перепроверяем через прямой запрос к БД
        val actualCount = accountRepo.getAccountCount()
        if (actualCount == 0) {
            onNavigateToSetup()
        }
    }
    
    // Используем отдельный scope для синхронизации, чтобы она не прерывалась при повороте экрана
    val manualSyncScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    
    // Очищаем scope при уничтожении Composable
    DisposableEffect(Unit) {
        onDispose {
            manualSyncScope.cancel()
        }
    }

    
    fun syncFolders() {
        activeAccount?.let { account ->
            manualSyncScope.launch {
                isLoading = true
                
                try {
                   // Таймаут на всю синхронизацию - 60 секунд
                    kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                         // Синхронизируем папки
                        val result = withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                        
                        if (result is com.iwo.mailclient.eas.EasResult.Error) {
                            return@withTimeoutOrNull
                        }
                        
                        // Синхронизируем письма для всех папок с письмами
                        val emailFolderTypes = listOf(1, 2, 3, 4, 5, 6, 11, 12)
                        val currentFolders = withContext(Dispatchers.IO) {
                            com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                                .folderDao().getFoldersByAccountList(account.id)
                        }
                        val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                        
                        // Синхронизируем последовательно с проверкой на отмену
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
                        
                        // Обновляем время синхронизации
                        settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    }
                } catch (_: Exception) { }
                
                isLoading = false
            }
        }
    }
    
    // Диалог создания папки
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
    
    // Диалог удаления папки
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
                                        // пїЅЮ¦-пїЅ-пїЅ-пїЅ-пїЅпїЅTП¦пїЅпїЅ- TпїЅпїЅпїЅпїЅпїЅTпїЅпїЅ-пїЅпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅпїЅ
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
    
    // пїЅЬ¦пїЅпїЅ-TпїЅ пїЅ+пїЅпїЅпїЅпїЅTпїЅTВ¦-пїЅпїЅпїЅпїЅ TпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅ-пїЅпїЅ (пїЅпїЅTпїЅпїЅпїЅ пїЅ+пїЅ-пїЅпїЅпїЅпїЅпїЅ-пїЅ- пїЅ-пїЅ-пїЅпїЅпїЅ-TВ¦пїЅпїЅпїЅ)
    folderForMenu?.let { folder ->
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderForMenu = null },
            title = { Text(folder.displayName) },
            text = {
                Column {
                    // пїЅЯ¦пїЅTпїЅпїЅпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅ-пїЅ-пїЅ-TпїЅTпїЅ
                    ListItem(
                        headlineContent = { Text(Strings.rename) },
                        leadingContent = { Icon(AppIcons.Edit, null) },
                        modifier = Modifier.clickable {
                            folderForMenu = null
                            renameNewName = folder.displayName
                            folderToRename = folder
                        }
                    )
                    // пїЅпїЅ+пїЅ-пїЅпїЅпїЅпїЅTпїЅTпїЅ
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
    
    // пїЅФ¦пїЅпїЅ-пїЅпїЅпїЅ-пїЅпїЅ пїЅпїЅпїЅпїЅTпїЅпїЅпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅ-пїЅ-пїЅ-пїЅ-пїЅпїЅTпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅпїЅ
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
                                        // пїЅЮ¦-пїЅ-пїЅ-пїЅ-пїЅпїЅTП¦пїЅпїЅ- TпїЅпїЅпїЅпїЅпїЅTпїЅпїЅ-пїЅпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅпїЅ
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
                        // пїЅЯ¦пїЅTпїЅпїЅпїЅTЕ¦-пїЅ+пїЅпїЅпїЅ- пїЅ-пїЅ- TН¦пїЅTпїЅпїЅ-пїЅ- пїЅпїЅпїЅпїЅпїЅ-TпїЅпїЅ-пїЅ-пїЅ-TпїЅTпїЅ (пїЅпїЅTпїЅпїЅпїЅпїЅ-пїЅпїЅTМ¦пїЅTГ¦пїЅпїЅ- TпїЅпїЅпїЅпїЅпїЅTЖ¦пїЅпїЅ-пїЅпїЅTМ¦-TЛ¦пїЅ ID)
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
        // пїЅЯ¦-пїЅпїЅпїЅ-пїЅпїЅTЛ¦-пїЅ-пїЅпїЅпїЅ- пїЅпїЅпїЅ-пїЅпїЅTпїЅTГ¦пїЅпїЅпїЅTпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅ- пїЅ-пїЅпїЅпїЅпїЅпїЅ-TГ¦-TпїЅ пїЅ-пїЅпїЅ пїЅпїЅпїЅ-пїЅпїЅTпїЅTГ¦пїЅпїЅпїЅпїЅ-
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
                FloatingActionButton(
                    onClick = onNavigateToCompose,
                    containerColor = colorTheme.gradientStart,
                    contentColor = Color.White
                ) {
                    Icon(AppIcons.Edit, Strings.compose)
                }
            }
        ) { padding ->
            // РћСЃРЅРѕРІРЅРѕРµ СЃРѕРґРµСЂР¶РёРјРѕРµ СЃ РєР°СЂС‚РѕС‡РєР°РјРё Рё РїР°РїРєР°РјРё
            HomeContent(
                activeAccount = activeAccount,
                folders = folders,
                flaggedCount = flaggedCount,
                notesCount = notesCount,
                eventsCount = eventsCount,
                tasksCount = tasksCount,
                isLoading = isLoading,
                isSyncing = isSyncing,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(
    activeAccount: AccountEntity?,
    folders: List<FolderEntity>,
    flaggedCount: Int,
    notesCount: Int = 0,
    eventsCount: Int = 0,
    tasksCount: Int = 0,
    isLoading: Boolean,
    isSyncing: Boolean = false,
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
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }
    var tipsExpanded by rememberSaveable { mutableStateOf(false) }
    
    // пїЅпїЅTпїЅпїЅпїЅпїЅ-TпїЅ пїЅпїЅпїЅ-TпїЅпїЅпїЅпїЅпїЅпїЅ+пїЅ-пїЅпїЅпїЅпїЅ TпїЅпїЅпїЅпїЅ-TпїЅTпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅпїЅ-TЖ¦пїЅпїЅпїЅ
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val lastSyncTime by settingsRepo.lastSyncTime.collectAsState(initial = 0L)
    
    // Отслеживаем состояние Battery Saver через BroadcastReceiver (мгновенная реакция)
    val isBatterySaverActive by settingsRepo.batterySaverState.collectAsState(initial = settingsRepo.isBatterySaverActive())
    val ignoreBatterySaver by settingsRepo.ignoreBatterySaver.collectAsState(initial = false)
    val showBatterySaverWarning = isBatterySaverActive && !ignoreBatterySaver
    
    // Состояние для скрытия рекомендации (сохраняется при повороте экрана, сбрасывается при перезапуске)
    var isRecommendationDismissed by rememberSaveable { mutableStateOf(false) }
    
    // Локализованные названия папок (вычисляем вне LazyColumn)
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
    
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // пїЅпїЅTВ¦-TпїЅTпїЅTпїЅ TпїЅпїЅпїЅпїЅ-TпїЅTпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅпїЅ-TЖ¦пїЅпїЅпїЅ пїЅпїЅпїЅ TпїЅпїЅ-пїЅ-TпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅ-TЛ¦пїЅ пїЅ-пїЅпїЅпїЅ+
        if (isSyncing || isLoading) {
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
        
        // пїЅШ¦-пїЅ+пїЅпїЅпїЅпїЅпїЅ-TВ¦-TпїЅ Battery Saver
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
        
        // пїЅпїЅTпїЅпїЅпїЅпїЅ-пїЅпїЅTпїЅTпїЅTВ¦-пїЅпїЅпїЅ-пїЅ-пїЅ-TпїЅ пїЅпїЅпїЅ-TпїЅTВ¦-TЗ¦пїЅпїЅ- пїЅпїЅпїЅ TпїЅпїЅ-пїЅ-TпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅ-TЛ¦пїЅ пїЅпїЅTпїЅпїЅ-пїЅ+пїЅпїЅпїЅпїЅпїЅ-TВ¦-TЛ¦пїЅ TпїЅTВ¦пїЅпїЅпїЅTпїЅ TпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅпїЅпїЅ
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
                                // пїЅР¦-пїЅ-TВ¦-TпїЅ пїЅ-пїЅпїЅпїЅпїЅпїЅ-TГ¦-TВ¦-
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeAccount?.color ?: 0xFF1976D2.toInt())),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = activeAccount?.displayName?.firstOrNull()?.uppercase() ?: "пїЅпїЅпїЅпїЅ",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = Strings.hello,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = activeAccount?.email ?: Strings.loading,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            
                            // пїЅпїЅTпїЅпїЅпїЅпїЅ-TпїЅ пїЅпїЅпїЅ-TпїЅпїЅпїЅпїЅпїЅпїЅ+пїЅ-пїЅпїЅпїЅпїЅ TпїЅпїЅпїЅпїЅ-TпїЅTпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅпїЅ-TЖ¦пїЅпїЅпїЅ - пїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅпїЅTЛ¦-пїЅ-пїЅпїЅпїЅ- TВ¦-пїЅпїЅTМ¦пїЅпїЅ- пїЅпїЅпїЅ-пїЅпїЅпїЅ+пїЅ- пїЅ-пїЅпїЅ пїЅпїЅпїЅ+TпїЅTпїЅ TпїЅпїЅпїЅпїЅ-TпїЅTпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅпїЅ-TЖ¦пїЅTпїЅ
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
        
        // пїЅа¦¦пїЅпїЅпїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ+пїЅ-TЖ¦пїЅTпїЅ пїЅ+пїЅ-TпїЅ - пїЅпїЅTпїЅпїЅпїЅпїЅпїЅ пїЅпїЅTпїЅTпїЅTпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅпїЅ TпїЅ > 1000 пїЅпїЅпїЅпїЅTпїЅпїЅпїЅпїЅ-
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
        
        // Быстрый доступ по категориям
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
            
            // Основные папки по типам
            val mainFolders = folders.filter { it.type in listOf(2, 3, 4, 5) }
            
            // Порядок: Входящие, Отправленные, Черновики, Удалённые, Избранные, Контакты
            val orderedFolders = mutableListOf<FolderDisplayData>()
            
            // Входящие (type 2)
            mainFolders.find { it.type == 2 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, inboxName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // Отправленные (type 5)
            mainFolders.find { it.type == 5 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, sentName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // Черновики (type 3)
            mainFolders.find { it.type == 3 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, draftsName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // Удалённые (type 4)
            mainFolders.find { it.type == 4 }?.let { folder ->
                orderedFolders.add(FolderDisplayData(folder.id, trashName, folder.totalCount, folder.unreadCount, folder.type))
            }
            // Избранные
            orderedFolders.add(FolderDisplayData("favorites", favoritesName, flaggedCount, 0, -1))
            // Контакты
            orderedFolders.add(FolderDisplayData("contacts", contactsName, 0, 0, -2))
            // Заметки
            orderedFolders.add(FolderDisplayData("notes", notesName, notesCount, 0, -3))
            // Календарь
            orderedFolders.add(FolderDisplayData("calendar", calendarName, eventsCount, 0, -4))
            // Задачи
            orderedFolders.add(FolderDisplayData("tasks", tasksName, tasksCount, 0, -5))
            
            val displayFolders: List<FolderDisplayData> = orderedFolders.toList()
            
            val chunkedFolders: List<List<FolderDisplayData>> = displayFolders.chunked(2)
            itemsIndexed(chunkedFolders) { index: Int, rowFolders: List<FolderDisplayData> ->
                val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                // Анимация появления карточек
                var visible by remember { mutableStateOf(!animationsEnabled) }
                LaunchedEffect(animationsEnabled) {
                    if (animationsEnabled) {
                        kotlinx.coroutines.delay(index * 80L)
                        visible = true
                    } else {
                        visible = true
                    }
                }
                
                if (animationsEnabled) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(300)) + 
                                slideInVertically(
                                    initialOffsetY = { it / 2 },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                )
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
                                        // РЎР±СЂР°СЃС‹РІР°РµРј СЂР°СЃРєСЂС‹С‚С‹Рµ СЃРµРєС†РёРё РїСЂРё РїРµСЂРµС…РѕРґРµ РІ РїР°РїРєСѓ
                                        tipsExpanded = false
                                        aboutExpanded = false
                                        when (folder.id) {
                                            "contacts" -> onContactsClick()
                                            "notes" -> onNotesClick()
                                            "calendar" -> onCalendarClick()
                                            "tasks" -> onTasksClick()
                                            else -> onFolderClick(folder.id)
                                        }
                                    },
                                    modifier = if (rowFolders.size == 1) {
                                        Modifier.fillMaxWidth(0.48f)
                                    } else {
                                        Modifier.weight(1f)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Р‘РµР· Р°РЅРёРјР°С†РёРё РґР»СЏ Р±С‹СЃС‚СЂРѕР№ РѕС‚СЂРёСЃРѕРІРєРё
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (rowFolders.size == 1) Arrangement.Center else Arrangement.spacedBy(12.dp)
                    ) {
                        rowFolders.forEach { folder ->
                            FolderCardDisplay(
                                id = folder.id,
                                name = folder.name,
                                count = folder.count,
                                unreadCount = folder.unreadCount,
                                type = folder.type,
                                onClick = { 
                                    // РЎР±СЂР°СЃС‹РІР°РµРј СЂР°СЃРєСЂС‹С‚С‹Рµ СЃРµРєС†РёРё РїСЂРё РїРµСЂРµС…РѕРґРµ РІ РїР°РїРєСѓ
                                    tipsExpanded = false
                                    aboutExpanded = false
                                    when (folder.id) {
                                        "contacts" -> onContactsClick()
                                        "notes" -> onNotesClick()
                                        "calendar" -> onCalendarClick()
                                        "tasks" -> onTasksClick()
                                        else -> onFolderClick(folder.id)
                                    }
                                },
                                modifier = if (rowFolders.size == 1) {
                                    Modifier.fillMaxWidth(0.48f)
                                } else {
                                    Modifier.weight(1f)
                                }
                            )
                        }
                    }
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
        
        // пїЅпїЅ-пїЅ-пїЅпїЅTпїЅTпїЅ пїЅпїЅпїЅ- TпїЅпїЅ-пїЅ-пїЅ-TВ¦пїЅ TпїЅ пїЅпїЅTпїЅпїЅпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅ-
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { tipsExpanded = !tipsExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // пїЅР¦-пїЅпїЅпїЅ-пїЅпїЅTпїЅпїЅ-пїЅ-пїЅ-пїЅ-пїЅ-пїЅ-TпїЅ пїЅпїЅпїЅ-пїЅ-пїЅпїЅпїЅ-TЗ¦пїЅпїЅ-
                        val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                        
                        val bulbScale: Float
                        val bulbAlpha: Float
                        
                        if (animationsEnabled) {
                            val infiniteTransition = rememberInfiniteTransition(label = "bulb")
                            bulbScale = infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.15f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "bulbScale"
                            ).value
                            bulbAlpha = infiniteTransition.animateFloat(
                                initialValue = 0.7f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "bulbAlpha"
                            ).value
                        } else {
                            bulbScale = 1f
                            bulbAlpha = 1f
                        }
                        
                        Icon(
                            imageVector = AppIcons.Lightbulb,
                            contentDescription = null,
                            tint = Color(0xFFFFB300).copy(alpha = bulbAlpha),
                            modifier = Modifier
                                .size(24.dp)
                                .scale(bulbScale)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = Strings.tipsTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (tipsExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    AnimatedVisibility(
                        visible = tipsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TipItem(
                                icon = AppIcons.Notifications,
                                text = Strings.tipNotification,
                                iconColor = Color(0xFFFF9800),
                                iconBackgroundColor = Color(0xFFFFF3E0)
                            )
                            TipItem(
                                icon = AppIcons.BatteryChargingFull,
                                text = Strings.tipBattery,
                                iconColor = Color(0xFF4CAF50),
                                iconBackgroundColor = Color(0xFFE8F5E9)
                            )
                            TipItem(
                                icon = AppIcons.Lock,
                                text = Strings.tipCertificate,
                                iconColor = Color(0xFF9C27B0),
                                iconBackgroundColor = Color(0xFFF3E5F5)
                            )
                            TipItem(
                                icon = AppIcons.Info,
                                text = Strings.tipBeta,
                                iconColor = Color(0xFF2196F3),
                                iconBackgroundColor = Color(0xFFE3F2FD)
                            )
                        }
                    }
                }
            }
        }
        
        // пїЅпїЅ пїЅпїЅTпїЅпїЅпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅпїЅ пїЅпїЅпїЅ пїЅпїЅпїЅ-пїЅ-пїЅпїЅпїЅ-пїЅпїЅTВ¦-TЛ¦пїЅ TпїЅпїЅ-пїЅ-TпїЅпїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅ-TЛ¦пїЅ пїЅ-пїЅпїЅпїЅ+
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { aboutExpanded = !aboutExpanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // пїЅР¦-пїЅпїЅпїЅ-пїЅпїЅTпїЅпїЅ-пїЅ-пїЅ-пїЅ-пїЅ-TЛ¦пїЅ пїЅпїЅпїЅ-пїЅ-пїЅ-пїЅпїЅTпїЅTВ¦пїЅпїЅпїЅ TпїЅ пїЅпїЅTпїЅпїЅ-пїЅ+пїЅпїЅпїЅпїЅпїЅ-TВ¦-пїЅ-
                        val colorTheme = com.iwo.mailclient.ui.theme.LocalColorTheme.current
                        val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
                        
                        val envelopeScale: Float
                        val envelopeRotation: Float
                        
                        if (animationsEnabled) {
                            val infiniteTransition = rememberInfiniteTransition(label = "envelope")
                            envelopeScale = infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 1.08f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "envelopeScale"
                            ).value
                            envelopeRotation = infiniteTransition.animateFloat(
                                initialValue = -3f,
                                targetValue = 3f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(2000, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "envelopeRotation"
                            ).value
                        } else {
                            envelopeScale = 1f
                            envelopeRotation = 0f
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .scale(envelopeScale)
                                .clip(MaterialTheme.shapes.medium)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            colorTheme.gradientStart,
                                            colorTheme.gradientEnd
                                        )
                                    )
                                )
                                .shadow(
                                    elevation = 4.dp,
                                    shape = MaterialTheme.shapes.medium,
                                    ambientColor = colorTheme.gradientStart.copy(alpha = 0.3f),
                                    spotColor = colorTheme.gradientStart.copy(alpha = 0.3f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                AppIcons.Email,
                                null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(26.dp)
                                    .rotate(envelopeRotation)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "iwo Mail Client",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "v1.5.0",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (aboutExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    AnimatedVisibility(
                        visible = aboutExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp)
                        ) {
                            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                            
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = Strings.appDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Р’РѕР·РјРѕР¶РЅРѕСЃС‚Рё РІ РІРёРґРµ С‡РёРїРѕРІ
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FeatureChip(Strings.featureSync)
                                FeatureChip(Strings.featureAttachments)
                                FeatureChip(Strings.featureSend)
                                FeatureChip(Strings.featureSearch)
                                FeatureChip(Strings.featureFolders)
                                FeatureChip(Strings.featureContacts)
                                FeatureChip(Strings.featureNotes)
                                FeatureChip(Strings.featureCalendar)
                                FeatureChip(Strings.featureTasks)
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // пїЅпїЅ-пїЅпїЅTпїЅпїЅ-пїЅ-пїЅ-TпїЅTЗ¦пїЅпїЅпїЅ
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Person,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${Strings.developerLabel} DedovMosol",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { uriHandler.openUri("mailto:andreyid@outlook.com") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Email,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "andreyid@outlook.com",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            // Telegram пїЅпїЅпїЅ-пїЅ-пїЅ-пїЅпїЅ
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { uriHandler.openUri("https://t.me/i_wantout") },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    AppIcons.Send,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Telegram: @i_wantout",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "© 2025",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        
        // пїЅЪ¦-пїЅ-пїЅпїЅпїЅпїЅпїЅ- "пїЅЮ¦пїЅпїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-пїЅпїЅTпїЅTпїЅTпїЅTпїЅ TпїЅ TпїЅпїЅ-пїЅпїЅпїЅ-пїЅпїЅTВ¦пїЅпїЅпїЅпїЅ- пїЅпїЅTпїЅпїЅ-пїЅпїЅTпїЅпїЅ-пїЅ-пїЅ-TпїЅ" TпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅпїЅпїЅ
        item {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val isRu = LocalLanguage.current == AppLanguage.RUSSIAN
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            val changelogUrl = if (isRu) 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_RU.md"
            else 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_EN.md"
            
            // пїЅР¦-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅTпїЅ пїЅпїЅTГ¦пїЅTпїЅTпїЅпїЅ-TЖ¦пїЅпїЅпїЅ (TВ¦-пїЅпїЅTМ¦пїЅпїЅ- пїЅпїЅTпїЅпїЅпїЅпїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅ пїЅ-пїЅпїЅпїЅпїЅTпїЅTЗ¦пїЅпїЅ-TпїЅ)
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
            
            // пїЅР¦-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅTпїЅ TпїЅпїЅ-пїЅпїЅTЗ¦пїЅпїЅ-пїЅпїЅTпїЅ пїЅпїЅTпїЅпїЅ-пїЅ-пїЅпїЅTпїЅTпїЅ
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
        
        // пїЅЪ¦-пїЅ-пїЅпїЅпїЅпїЅпїЅ- пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅTпїЅTВ¦-пїЅ-пїЅ-пїЅ-пїЅ-пїЅпїЅпїЅпїЅ TпїЅ пїЅпїЅTГ¦пїЅTпїЅTпїЅпїЅпїЅTпїЅTпїЅTпїЅTЙ¦пїЅпїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅпїЅпїЅ
        item {
            var showDonateDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current
            val accountCopiedText = Strings.accountCopied
            val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
            
            // пїЅпїЅTГ¦пїЅTпїЅTпїЅпїЅпїЅTпїЅTпїЅTпїЅTЙ¦-TпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅTпїЅ (TВ¦-пїЅпїЅTМ¦пїЅпїЅ- пїЅпїЅTпїЅпїЅпїЅпїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅ пїЅ-пїЅпїЅпїЅпїЅTпїЅTЗ¦пїЅпїЅ-TпїЅ)
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
                                    //  Номер счёта с выделением
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { showDonateDialog = false }) {
                                Text(Strings.closeDialog)
                            }
                            TextButton(
                                onClick = {
                                    // РљРѕРїРёСЂСѓРµРј РЅРѕРјРµСЂ СЃС‡С‘С‚Р°
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
                    },
                    dismissButton = { }
                )
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
        
        // пїЅпїЅTпїЅTпїЅTпїЅTГ¦пїЅ TпїЅпїЅ-пїЅпїЅпїЅпїЅTпїЅ пїЅ+пїЅпїЅTпїЅ FAB
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
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
private fun FeatureChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall
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
    
    // пїЅР¦-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅTпїЅ пїЅ-пїЅ-TпїЅTпїЅTВ¦-пїЅ-пїЅ- пїЅпїЅTпїЅпїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-TВ¦пїЅпїЅпїЅ (TВ¦-пїЅпїЅTМ¦пїЅпїЅ- пїЅпїЅTпїЅпїЅпїЅпїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅ пїЅ-пїЅпїЅпїЅпїЅTпїЅTЗ¦пїЅпїЅ-TпїЅ)
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
    
    // пїЅР¦-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅTпїЅ TВ¦пїЅпїЅ-пїЅпїЅ пїЅпїЅTпїЅпїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-TВ¦пїЅпїЅпїЅ
    val elevation by animateDpAsState(
        targetValue = if (animationsEnabled && isPressed) 1.dp else 4.dp,
        animationSpec = if (animationsEnabled) tween(150) else snap(),
        label = "elevation"
    )
    
    // пїЅпїЅTГ¦пїЅTпїЅTпїЅпїЅ-TЖ¦пїЅTпїЅ пїЅпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅ-TЗ¦пїЅпїЅ-пїЅ-пїЅ-пїЅпїЅпїЅпїЅ пїЅпїЅпїЅпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅ (TВ¦-пїЅпїЅTМ¦пїЅпїЅ- пїЅпїЅTпїЅпїЅпїЅпїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅ пїЅ-пїЅпїЅпїЅпїЅTпїЅTЗ¦пїЅпїЅ-TпїЅ)
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
    
    // Определяем цвета и иконку для разных типов папок
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
                // пїЅШ¦пїЅпїЅ-пїЅ-пїЅпїЅпїЅ- TпїЅ пїЅпїЅпїЅ-пїЅпїЅTГ¦пїЅTпїЅпїЅ-пїЅпїЅTпїЅпїЅ-TЗ¦-TЛ¦- TД¦-пїЅ-пїЅ-пїЅ- пїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅпїЅпїЅпїЅпїЅ
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
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // РЎС‡С‘С‚С‡РёРє СЌР»РµРјРµРЅС‚РѕРІ
                    if (type != -2 && count > 0) {
                        Text(
                            text = "$count ${when (type) {
                                -3 -> Strings.pluralNotes(count)  // Заметки
                                -4 -> Strings.pluralEvents(count)  // Календарь
                                -5 -> Strings.pluralTasks(count)  // Задачи
                                else -> Strings.emailsCount
                            }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                
                // Badge СЃ РЅРµРїСЂРѕС‡РёС‚Р°РЅРЅС‹РјРё РёР»Рё СЃ РёР·Р±СЂР°РЅРЅС‹РјРё (С‚РѕР»СЊРєРѕ РєРѕРіРґР° РІРєР»СЋС‡РµРЅС‹ Р°РЅРёРјР°С†РёРё)
                // Р”Р»СЏ С‡РµСЂРЅРѕРІРёРєРѕРІ (type 3) badge РЅРµ РїРѕРєР°Р·С‹РІР°РµРј - РѕРЅРё РЅРµ РёРјРµСЋС‚ СЃС‚Р°С‚СѓСЃР° "РїСЂРѕС‡РёС‚Р°РЅРѕ"
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
    // пїЅпїЅTпїЅпїЅ-пїЅ+пїЅпїЅпїЅпїЅпїЅ-TВ¦-TЛ¦пїЅ TД¦-пїЅ- пїЅ+пїЅпїЅTпїЅ TВ¦-пїЅпїЅпїЅ-пїЅ-TпїЅпїЅ-
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
            color = Color.White.copy(alpha = 0.95f),
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
        // пїЅЧ¦-пїЅпїЅпїЅ-пїЅпїЅпїЅ-пїЅ-пїЅ-пїЅпїЅ TпїЅ пїЅ-пїЅпїЅпїЅпїЅпїЅ-TГ¦-TВ¦-пїЅ-
        item {
            DrawerHeader(
                account = activeAccount,
                showPicker = showAccountPicker,
                onToggle = onToggleAccountPicker
            )
        }
        
        // пїЅпїЅTЛ¦-пїЅ-TпїЅ пїЅ-пїЅпїЅпїЅпїЅпїЅ-TГ¦-TВ¦-
        if (showAccountPicker) {
            items(accounts) { account ->
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
        
        // пїЅЯ¦-пїЅпїЅпїЅпїЅпїЅпїЅ - TпїЅпїЅ-пїЅ-TЗ¦-пїЅпїЅпїЅ- пїЅ-TпїЅпїЅ-пїЅ-пїЅ-пїЅ-TЛ¦пїЅ (пїЅпїЅTЕ¦-пїЅ+TпїЅTЙ¦пїЅпїЅпїЅ, пїЅз¦¦TпїЅпїЅ-пїЅ-пїЅ-пїЅпїЅпїЅпїЅпїЅпїЅ, пїЅпїЅ+пїЅ-пїЅпїЅTС¦-пїЅ-TЛ¦пїЅ, пїЅпїЅTВ¦пїЅTпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅпїЅ-пїЅ-TЛ¦пїЅ, пїЅпїЅTпїЅTЕ¦-пїЅ+TпїЅTЙ¦пїЅпїЅпїЅ, пїЅб¦¬пїЅ-пїЅ-)
        val mainFolderTypes = listOf(2, 3, 4, 5, 6, 11)
        val mainFolders = folders.filter { it.type in mainFolderTypes }
            .sortedBy { mainFolderTypes.indexOf(it.type) }
        
        items(mainFolders) { folder ->
            FolderItem(
                folder = folder,
                onClick = { onFolderSelected(folder) }
            )
        }
        
        // пїЅШ¦пїЅпїЅ-TпїЅпїЅ-пїЅ-пїЅ-TЛ¦пїЅ - пїЅпїЅпїЅ-TпїЅпїЅпїЅпїЅпїЅ пїЅпїЅTпїЅTЕ¦-пїЅ+TпїЅTЙ¦пїЅTпїЅ
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
        
        // пїЅпїЅTпїЅTВ¦-пїЅпїЅTМ¦-TЛ¦пїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅпїЅ (пїЅпїЅTпїЅпїЅ-пїЅ-пїЅпїЅ Contacts - type 9, TпїЅ пїЅ-пїЅ-TпїЅ TпїЅпїЅ-пїЅ-пїЅпїЅ TН¦пїЅTпїЅпїЅ-пїЅ- пїЅпїЅпїЅ-пїЅ-TВ¦-пїЅпїЅTВ¦-пїЅ-)
        // РћСЃС‚Р°Р»СЊРЅС‹Рµ РїР°РїРєРё (СЃРєСЂС‹РІР°РµРј Contacts, Calendar, Notes - РѕРЅРё РїРѕРєР°Р·Р°РЅС‹ РѕС‚РґРµР»СЊРЅРѕ)
        val hiddenFolderTypes = listOf(8, 9, 10) // Calendar, Contacts, Notes
        val otherFolders = folders.filter { it.type !in mainFolderTypes && it.type !in hiddenFolderTypes }
        
        // РљРѕРЅС‚Р°РєС‚С‹
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
        
        // Заметки
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
        
        // Календарь
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
        
        // Задачи
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
        
        // РћСЃС‚Р°Р»СЊРЅС‹Рµ РїР°РїРєРё
        if (otherFolders.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            items(otherFolders) { folder ->
                FolderItem(
                    folder = folder,
                    onClick = { onFolderSelected(folder) },
                    onLongClick = { onFolderLongClick(folder) }
                )
            }
        }
        
        // РЎРѕР·РґР°С‚СЊ РїР°РїРєСѓ
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(Strings.createFolder) },
                leadingContent = { Icon(AppIcons.CreateNewFolder, null) },
                modifier = Modifier.clickable(onClick = onCreateFolder)
            )
        }
        
        // РќР°СЃС‚СЂРѕР№РєРё
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
    // пїЅпїЅTпїЅпїЅ-пїЅ+пїЅпїЅпїЅпїЅпїЅ-TВ¦-TЛ¦пїЅ TЕ¦пїЅпїЅ+пїЅпїЅTпїЅ пїЅпїЅпїЅ-пїЅпїЅ пїЅ- SetupScreen
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
    
    // пїЅпїЅ-пїЅпїЅTпїЅ пїЅпїЅпїЅпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅ - пїЅпїЅTпїЅпїЅ-TпїЅпїЅ-TЛ¦пїЅ пїЅ+пїЅпїЅTпїЅ TпїЅпїЅпїЅпїЅ-пїЅ-пїЅ- пїЅпїЅ пїЅпїЅпїЅ-TпїЅпїЅпїЅпїЅпїЅпїЅ-TпїЅ
    val iconTint = when (folder.type) {
        4 -> MaterialTheme.colorScheme.error // пїЅпїЅ+пїЅ-пїЅпїЅTС¦-пїЅ-TЛ¦пїЅ
        11 -> Color(0xFFE53935) // пїЅб¦¬пїЅ-пїЅ- - пїЅпїЅTпїЅпїЅ-TпїЅпїЅ-TЛ¦пїЅ
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    // пїЅЫ¦-пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅпїЅпїЅ-пїЅ-пїЅ-пїЅ-пїЅ-пїЅ-пїЅпїЅ пїЅ-пїЅ-пїЅпїЅпїЅ-пїЅ-пїЅ-пїЅпїЅпїЅпїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅпїЅ
    val displayName = Strings.getFolderName(folder.type, folder.displayName)
    
    // пїЅб¦¬TпїЅTВ¦пїЅпїЅ-пїЅ-TЛ¦пїЅ пїЅпїЅпїЅ-пїЅпїЅпїЅпїЅпїЅпїЅ пїЅ-пїЅпїЅпїЅпїЅTМ¦пїЅTпїЅ TГ¦+пїЅ-пїЅпїЅTпїЅTпїЅTпїЅ (пїЅ-пїЅпїЅпїЅпїЅTпїЅTЗ¦-TпїЅ пїЅб¦¬пїЅ-пїЅ-)
    val isSystemFolder = folder.type in listOf(2, 3, 4, 5, 6, 11)
    
    // пїЅпїЅTпїЅпїЅпїЅпїЅ-пїЅпїЅTМ¦пїЅTГ¦пїЅпїЅ- Surface TпїЅ combinedClickable пїЅ-пїЅ-пїЅпїЅTпїЅTВ¦- NavigationDrawerItem
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


@Composable
private fun TipItem(
    icon: ImageVector,
    text: String,
    iconColor: Color = MaterialTheme.colorScheme.tertiary,
    iconBackgroundColor: Color = MaterialTheme.colorScheme.tertiaryContainer
) {
    val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
    
    // пїЅР¦-пїЅпїЅпїЅ-пїЅ-TЖ¦пїЅTпїЅ пїЅпїЅTГ¦пїЅTпїЅTпїЅпїЅ-TЖ¦пїЅпїЅпїЅ пїЅпїЅпїЅпїЅпїЅ-пїЅ-пїЅпїЅпїЅпїЅ
    val iconScale: Float
    if (animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "tipIcon")
        iconScale = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "tipIconScale"
        ).value
    } else {
        iconScale = 1f
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .scale(iconScale)
                .clip(CircleShape)
                .background(iconBackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = iconColor
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

package com.dedovmosol.iwomail.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.network.NetworkMonitor
import com.dedovmosol.iwomail.ui.components.NetworkBanner
import com.dedovmosol.iwomail.ui.utils.rememberPulseScale
import com.dedovmosol.iwomail.ui.utils.rememberWobble
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Главный экран приложения
 * Использует общий scope для синхронизации
 */

// Data classes для UI
private data class FolderDisplayData(val id: String, val name: String, val count: Int, val unreadCount: Int, val type: Int)
private data class FolderColorsData(val icon: ImageVector, val gradientColors: List<Color>)

/**
 * Кэш папок для ускорения работы UI
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
    
    // Счётчик попыток синхронизации для защиты от бесконечных повторов при timeout
    private val syncAttempts = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private const val MAX_SYNC_ATTEMPTS = 3
    
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
        // Пропускаем если этот аккаунт уже синхронизирован в этой сессии
        if (accountId in syncedAccounts) return
        
        // Пропускаем если этот аккаунт уже синхронизируется
        if (accountId in syncingAccounts) return
        if (syncJobs[accountId]?.isActive == true) return
        
        // КРИТИЧНО: Защита от бесконечных повторов при timeout
        val attempts = syncAttempts.getOrDefault(accountId, 0)
        if (attempts >= MAX_SYNC_ATTEMPTS) {
            // Превышен лимит попыток - помечаем как завершённую чтобы не блокировать UI
            syncedAccounts.add(accountId)
            updateState()
            return
        }
        
        // Проверяем наличие сети
        if (!isNetworkAvailable(context)) {
            noNetwork = true
            return
        }
        noNetwork = false
        
        syncingAccounts.add(accountId)
        syncAttempts[accountId] = attempts + 1
        updateState()
        
        syncJobs[accountId] = syncScope.launch {
            try {
                // Проверяем флаг первой синхронизации из DataStore
                val isFirstSync = withContext(Dispatchers.IO) {
                    !settingsRepo.isInitialSyncCompleted(accountId)
                }
                
                // КРИТИЧНО: Если БД была пересоздана (destructive migration),
                // принудительно запускаем полную синхронизацию, даже если DataStore
                // считает что первичная синхронизация уже была выполнена.
                val dbWasRecreated = com.dedovmosol.iwomail.data.database.MailDatabase.wasDestructivelyMigrated
                if (dbWasRecreated) {
                    com.dedovmosol.iwomail.data.database.MailDatabase.clearDestructiveMigrationFlag()
                }
                
                if (isFirstSync || dbWasRecreated) {
                    // Первая синхронизация - блокирующая, с UI индикатором
                    delay(100)
                    performFullSync(context, accountId, mailRepo, settingsRepo)
                } else {
                    // Повторный запуск - фоновая быстрая синхронизация
                    // Помечаем как завершённую сразу (данные уже есть)
                    syncedAccounts.add(accountId)
                    updateState()
                    
                    // Запускаем лёгкую фоновую синхронизацию
                    launch(Dispatchers.IO) {
                        performBackgroundSync(context, accountId, mailRepo, settingsRepo)
                    }
                }
            } catch (_: CancellationException) {
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
    
    /**
     * Полная синхронизация при первом запуске (блокирующая)
     */
    private suspend fun performFullSync(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) {
        val syncResult = withTimeoutOrNull(600_000L) {  // 10 минут - достаточно для больших папок
                    withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                    
                    delay(200)
                    
                    // Синхронизируем все почтовые папки
                    val emailFolderTypes = listOf(
                        1, FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                        FolderType.SENT_ITEMS, FolderType.OUTBOX, FolderType.JUNK_EMAIL,
                        FolderType.USER_CREATED
                    )
                    val currentFolders = withContext(Dispatchers.IO) {
                        com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                            .folderDao().getFoldersByAccountList(accountId)
                    }
                    val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                    
                    // Ограничиваем параллельность до 2 папок одновременно,
                    // чтобы не перегружать Exchange сервер запросами
                    val syncSemaphore = kotlinx.coroutines.sync.Semaphore(2)
                    withContext(Dispatchers.IO) {
                        supervisorScope {
                            foldersToSync.map { folder ->
                                launch {
                                    syncSemaphore.acquire()
                                    try {
                                        mailRepo.syncEmails(accountId, folder.id)
                                    } catch (_: Exception) {
                                    } finally {
                                        syncSemaphore.release()
                                    }
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
                                        val contactRepo = com.dedovmosol.iwomail.data.repository.ContactRepository(context)
                                        contactRepo.syncExchangeContacts(accountId)
                                        contactRepo.syncGalContactsToDb(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                            launch {
                                try {
                                    withTimeoutOrNull(60_000L) {
                                        val noteRepo = com.dedovmosol.iwomail.data.repository.NoteRepository(context)
                                        noteRepo.syncNotes(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                            launch {
                                try {
                                    withTimeoutOrNull(60_000L) {
                                        val calendarRepo = com.dedovmosol.iwomail.data.repository.CalendarRepository(context)
                                        calendarRepo.syncCalendar(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                            launch {
                                try {
                                    withTimeoutOrNull(60_000L) {
                                        val taskRepo = com.dedovmosol.iwomail.data.repository.TaskRepository(context)
                                        taskRepo.syncTasks(accountId)
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    
                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    
                    // Помечаем что первая синхронизация завершена
                    settingsRepo.setInitialSyncCompleted(accountId, true)
                    
                    // Обновляем виджет после синхронизации
                    com.dedovmosol.iwomail.widget.updateMailWidget(context)
                    
                    true // Возвращаем успех
                }
                
                // КРИТИЧНО: Помечаем как завершённую ТОЛЬКО если синхронизация успешна
                // Если timeout (syncResult == null) - НЕ помечаем, чтобы можно было повторить
                if (syncResult == true) {
                    syncedAccounts.add(accountId)
                    syncAttempts.remove(accountId) // Сбрасываем счётчик при успехе
                    updateState()
                }
    }
    
    /**
     * Фоновая синхронизация для повторных запусков (быстрая, инкрементальная)
     * Не блокирует UI, не очищает локальные данные
     */
    private suspend fun performBackgroundSync(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository
    ) {
        try {
            withTimeoutOrNull(120_000L) {
                // КРИТИЧНО: Всегда синхронизируем папки первым делом.
                // Без этого, если БД была пересоздана (destructive migration),
                // таблица folders будет пустой и синхронизация писем не запустится.
                withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                
                // Синхронизируем только основные папки (Inbox, Sent, Drafts)
                val mainFolderTypes = listOf(
                    FolderType.INBOX,
                    FolderType.SENT_ITEMS,
                    FolderType.DRAFTS
                )
                
                val currentFolders = withContext(Dispatchers.IO) {
                    com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                        .folderDao().getFoldersByAccountList(accountId)
                }
                val foldersToSync = currentFolders.filter { it.type in mainFolderTypes }
                
                // Инкрементальная синхронизация (forceFullSync = false)
                withContext(Dispatchers.IO) {
                    supervisorScope {
                        foldersToSync.forEach { folder ->
                            launch {
                                try {
                                    withTimeoutOrNull(60_000L) {
                                        mailRepo.syncEmails(accountId, folder.id, forceFullSync = false)
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
                
                settingsRepo.setLastSyncTime(System.currentTimeMillis())
                com.dedovmosol.iwomail.widget.updateMailWidget(context)
            }
        } catch (_: Exception) {
            // Игнорируем ошибки фоновой синхронизации
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
        syncAttempts.remove(accountId)
        syncJobs[accountId]?.cancel()
        syncJobs.remove(accountId)
        syncingAccounts.remove(accountId)
        noNetwork = false
        updateState()
    }
    
    /**
     * Ручная синхронизация (pull-to-refresh)
     * Не прерывается при повороте экрана
     */
    fun manualSync(
        context: Context,
        accountId: Long,
        mailRepo: MailRepository,
        settingsRepo: SettingsRepository,
        onComplete: () -> Unit = {}
    ) {
        // Проверяем наличие сети
        if (!isNetworkAvailable(context)) {
            noNetwork = true
            onComplete()
            return
        }
        noNetwork = false
        
        // Если уже синхронизируется — не запускаем повторно
        if (accountId in syncingAccounts || syncJobs[accountId]?.isActive == true) {
            return
        }
        
        syncingAccounts.add(accountId)
        updateState()
        
        syncJobs[accountId] = syncScope.launch {
            try {
                // КРИТИЧНО: 60 сек недостаточно для ручной синхронизации при forceFullSync
                // Папка Отправленные с 500+ письмами на Exchange 2007 SP1 может синхронизироваться 2-3 мин
                withTimeoutOrNull(300_000L) {
                    // Синхронизируем папки
                    val result = withContext(Dispatchers.IO) { mailRepo.syncFolders(accountId) }
                    
                    if (result is com.dedovmosol.iwomail.eas.EasResult.Error) {
                        return@withTimeoutOrNull
                    }
                    
                    // Синхронизируем письма для всех почтовых папок
                    val emailFolderTypes = listOf(
                        1, FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                        FolderType.SENT_ITEMS, FolderType.OUTBOX, FolderType.JUNK_EMAIL,
                        FolderType.USER_CREATED
                    )
                    val currentFolders = withContext(Dispatchers.IO) {
                        com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                            .folderDao().getFoldersByAccountList(accountId)
                    }
                    val foldersToSync = currentFolders.filter { it.type in emailFolderTypes }
                    
                    // Синхронизируем параллельно
                    // КРИТИЧНО: Определяем режим синхронизации аккаунта
                    val account = withContext(Dispatchers.IO) {
                        com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                            .accountDao().getAccount(accountId)
                    }
                    val isExchange = account?.accountType == com.dedovmosol.iwomail.data.database.AccountType.EXCHANGE.name
                    
                    withContext(Dispatchers.IO) {
                        supervisorScope {
                            foldersToSync.map { folder ->
                                launch {
                                    try {
                                        // КРИТИЧНО: Для INBOX и SENT_ITEMS Exchange аккаунтов ВСЕГДА используем forceFullSync
                                        // при РУЧНОЙ синхронизации. Это гарантирует получение новых писем,
                                        // даже если SyncKey устарел или был получен на другом устройстве.
                                        // Для SENT_ITEMS: после отправки письмо может не появиться из-за гонки syncKey.
                                        val forceFullSync = isExchange && (folder.type == FolderType.INBOX || folder.type == FolderType.SENT_ITEMS)
                                        // КРИТИЧНО: При forceFullSync (полная ресинхронизация) нужен увеличенный таймаут!
                                        // 30 секунд категорически недостаточно для папки с 500+ письмами (Exchange 2007 SP1).
                                        // Каждый пакет ~100 писем = 3-5 сек, для 700 писем = 7 итераций × 5 сек = 35+ сек.
                                        val timeout = if (forceFullSync) 180_000L else 30_000L
                                        withTimeoutOrNull(timeout) {
                                            mailRepo.syncEmails(accountId, folder.id, forceFullSync = forceFullSync)
                                        }
                                    } catch (_: Exception) { }
                                }
                            }.forEach { it.join() }
                        }
                    }
                    
                    // Обновляем время синхронизации
                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    
                    // Обновляем виджет
                    com.dedovmosol.iwomail.widget.updateMailWidget(context)
                }
            } catch (_: CancellationException) {
                // Отмена
            } catch (_: Exception) {
            } finally {
                syncingAccounts.remove(accountId)
                syncJobs.remove(accountId)
                updateState()
                onComplete()
            }
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
    onNavigateToOnboarding: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToEmailDetail: (String) -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
    onNavigateToNotes: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {},
    onNavigateToUserFolders: () -> Unit = {},
    // Навигация из карточки "Сегодня" с фильтрами
    onNavigateToEmailListWithDateFilter: (String, String) -> Unit = { _, _ -> },
    onNavigateToCalendarToday: () -> Unit = {},
    onNavigateToTasksToday: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Получаем репозитории из RepositoryProvider (ленивые singletons)
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
    
    // Состояния для отображения папок и счетчиков
    var folders by remember { mutableStateOf(FoldersCache.get(activeAccount?.id ?: 0L)) }
    var flaggedCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var accountsLoaded by remember { mutableStateOf(false) }
    // Флаг: есть ли локальные данные
    var dataLoaded by remember { mutableStateOf(folders.isNotEmpty()) }
    
    // Состояние синхронизации из контроллера
    val isSyncing = InitialSyncController.isSyncing
    val initialSyncDone = InitialSyncController.syncDone
    
    // Время последней синхронизации (для обновления статистики)
    val lastSyncTime by settingsRepo.lastSyncTime.collectAsState(initial = 0L)
    
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
    
    // Папка для контекстного меню (долгое нажатие)
    var folderForMenu by remember { mutableStateOf<FolderEntity?>(null) }
    
    // Флаг активации первого аккаунта (один раз)
    var firstAccountActivated by rememberSaveable { mutableStateOf(false) }
    
    // Подстраховка: активируем первый аккаунт, если активный не выбран
    // и пока данные ещё не пришли по Flow
    LaunchedEffect(Unit) {
        if (!firstAccountActivated) {
            firstAccountActivated = true
            // Проверяем активный аккаунт синхронно, не через Flow
            val hasActive = accountRepo.getActiveAccountSync() != null
            if (!hasActive) {
                // Если активного нет — берём первый из Flow
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
    
    // КРИТИЧНО: Обновляем счетчики после каждой синхронизации
    // Это гарантирует что UI покажет актуальные данные даже после поворота экрана
    LaunchedEffect(activeAccount?.id, lastSyncTime) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        if (lastSyncTime > 0) {
            withContext(Dispatchers.IO) {
                mailRepo.refreshFolderCounts(accountId)
            }
        }
    }
    
    // Проверка версии приложения при запуске
    // Синхронизируем папки при КАЖДОМ запуске если есть аккаунты (обновляет SyncKey)
    LaunchedEffect(Unit) {
        try {
            val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            val lastVersion = withContext(Dispatchers.IO) { settingsRepo.getLastAppVersion() }
            
            // Синхронизируем папки ТОЛЬКО если:
            // 1. Это НЕ первый запуск (lastVersion != 0)
            // 2. И версия ИЗМЕНИЛАСЬ (lastVersion != currentVersion)
            val shouldSync = lastVersion != 0 && lastVersion != currentVersion
            
            if (shouldSync) {
                android.util.Log.d("MainScreen", "App reinstalled or updated (v$lastVersion → v$currentVersion), syncing folders...")
                
                // Синхронизируем папки для всех аккаунтов
                val allAccounts = withContext(Dispatchers.IO) { 
                    com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                        .accountDao().getAllAccountsList()
                }
                
                allAccounts.forEach { account ->
                    withContext(Dispatchers.IO) {
                        try {
                            mailRepo.syncFolders(account.id)
                            android.util.Log.d("MainScreen", "Folders synced for account ${account.email}")
                        } catch (e: Exception) {
                            android.util.Log.e("MainScreen", "Failed to sync folders for ${account.email}: ${e.message}")
                        }
                    }
                }
            }
            
            // Сохраняем текущую версию
            withContext(Dispatchers.IO) { settingsRepo.setLastAppVersion(currentVersion) }
        } catch (e: Exception) {
            android.util.Log.e("MainScreen", "Version check failed: ${e.message}")
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
    
    // Room Flows автоматически обновляются при изменениях в БД — НЕ зависят от lastSyncTime
    // (lastSyncTime вызывал лишний restart collect → кратковременный сброс счётчиков при синхронизации)
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        noteRepo.getNotesCount(accountId).collect { newCount ->
            notesCount = newCount
        }
    }
    
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        calendarRepo.getEventsCount(accountId).collect { newCount ->
            eventsCount = newCount
        }
    }
    
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        taskRepo.getActiveTasksCount(accountId).collect { newCount ->
            tasksCount = newCount
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

    
    // Запускаем первичную синхронизацию (для PUSH после входа)
    // Обновляем состояния синхронизации для UI
    LaunchedEffect(activeAccount?.id) {
        val account = activeAccount ?: return@LaunchedEffect
        InitialSyncController.startSyncIfNeeded(context, account.id, mailRepo, settingsRepo)
    }
    
    // Первичная проверка аккаунтов (выполняем один раз)
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
    var autoUpdateInfo by remember { mutableStateOf<com.dedovmosol.iwomail.update.UpdateInfo?>(null) }
    
    LaunchedEffect(initialCheckDone) {
        if (!initialCheckDone) return@LaunchedEffect
        
        // Проверяем настройку интервала
        val interval = settingsRepo.getUpdateCheckIntervalSync()
        if (interval == com.dedovmosol.iwomail.data.repository.SettingsRepository.UpdateCheckInterval.NEVER) return@LaunchedEffect
        
        val lastCheck = settingsRepo.getLastUpdateCheckTimeSync()
        val intervalMs = interval.days * 24 * 60 * 60 * 1000L
        
        // Проверяем прошёл ли интервал
        if (System.currentTimeMillis() - lastCheck < intervalMs) return@LaunchedEffect
        
        // Проверяем обновления
        kotlinx.coroutines.delay(2000) // Даём приложению загрузиться
        try {
            val updateChecker = com.dedovmosol.iwomail.update.UpdateChecker(context)
            val isRussian = settingsRepo.getLanguageSync() == "ru"
            when (val result = updateChecker.checkForUpdate(isRussian)) {
                is com.dedovmosol.iwomail.update.UpdateResult.Available -> {
                    if (settingsRepo.shouldShowUpdateDialog(result.info.versionCode)) {
                        autoUpdateInfo = result.info
                        showAutoUpdateDialog = true
                        settingsRepo.setLastUpdateCheckTime(System.currentTimeMillis())
                        
                        // Push-уведомление о доступном обновлении
                        try {
                            showUpdateNotification(context, result.info.versionName, isRussian)
                        } catch (_: Exception) { }
                    }
                }
                else -> {
                    settingsRepo.setLastUpdateCheckTime(System.currentTimeMillis())
                }
            }
        } catch (_: Exception) { }
    }
    
    // Отслеживание удаления аккаунтов (только после загрузки из Flow)
    LaunchedEffect(accounts) {
        // Ждём пока Flow загрузит аккаунты (не используем начальный emptyList)
        if (!accountsLoaded || !initialCheckDone) return@LaunchedEffect
        
        // Даём время Flow обновить состояние
        kotlinx.coroutines.delay(500)
        
        // Перепроверяем число аккаунтов в БД
        val actualCount = accountRepo.getAccountCount()
        if (actualCount == 0) {
            onNavigateToSetup()
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
            isLoading = true
            InitialSyncController.manualSync(
                context = context,
                accountId = account.id,
                mailRepo = mailRepo,
                settingsRepo = settingsRepo,
                onComplete = { isLoading = false }
            )
        }
    }
    
    // Диалог создания папки
    if (showCreateFolderDialog) {
        val folderCreatedMsg = Strings.folderCreated
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderCreatedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {
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
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { folderToDelete = null },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteFolder) },
            text = { 
                Text(Strings.deleteFolderConfirm) 
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        activeAccount?.let { account ->
                            scope.launch {
                                isDeletingFolder = true
                                com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.deleteFolder(account.id, folder.id)
                                }
                                isDeletingFolder = false
                                when (result) {
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderDeletedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        // Синхронизируем папки после удаления
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {
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
                    text = if (isDeletingFolder) {
                        ""
                    } else {
                        Strings.yes
                    },
                    enabled = !isDeletingFolder,
                    modifier = if (isDeletingFolder) {
                        Modifier.width(100.dp)
                    } else {
                        Modifier
                    }
                )
                
                // Индикатор загрузки отдельно
                if (isDeletingFolder) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(Strings.no)
                }
            }
        )
    }
    
    // Контекстное меню папки (переименовать/удалить)
    folderForMenu?.let { folder ->
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { folderForMenu = null },
            title = { Text(folder.displayName) },
            text = {
                Column {
                    // Переименовать папку
                    ListItem(
                        headlineContent = { Text(Strings.rename) },
                        leadingContent = { Icon(AppIcons.Edit, null) },
                        modifier = Modifier.clickable {
                            folderForMenu = null
                            renameNewName = folder.displayName
                            folderToRename = folder
                        }
                    )
                    // Удалить папку
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
    
    // Диалог переименования папки
    folderToRename?.let { folder ->
        val folderRenamedMsg = Strings.folderRenamed
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
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
                                    is com.dedovmosol.iwomail.eas.EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context, 
                                            folderRenamedMsg, 
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        // Синхронизируем папки после переименования
                                        withContext(Dispatchers.IO) { mailRepo.syncFolders(account.id) }
                                    }
                                    is com.dedovmosol.iwomail.eas.EasResult.Error -> {
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
                        // Переходим в виртуальную папку Избранное (фиксированный ID)
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
        // Пока аккаунт не выбран — показываем экран загрузки
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
                val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
                val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
                
                // Анимация покачивания карандашика и пульсации
                val pencilRotation = rememberWobble(animationsEnabled, amplitude = 8f, durationMs = 600)
                val fabScale = rememberPulseScale(animationsEnabled, from = 1f, to = 1.08f, durationMs = 800)
                
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
                onUserFoldersClick = onNavigateToUserFolders,
                onSettingsClick = onNavigateToSettings,
                onEmailsTodayClick = onNavigateToEmailListWithDateFilter,
                onEventsTodayClick = onNavigateToCalendarToday,
                onTasksTodayClick = onNavigateToTasksToday,
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
    onUserFoldersClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    // Навигация из карточки "Сегодня" с фильтрами
    onEmailsTodayClick: (String, String) -> Unit = { _, _ -> },
    onEventsTodayClick: () -> Unit = {},
    onTasksTodayClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDonateDialog by rememberSaveable { mutableStateOf(false) }
    
    // Pull-to-refresh state
    val isRefreshing = isSyncing || isLoading
    val pullRefreshState = rememberPullRefreshState(isRefreshing, onSyncFolders)
    
    // Подписка на время последней синхронизации
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val lastSyncTime by settingsRepo.lastSyncTime.collectAsState(initial = 0L)
    
    // Отслеживаем Battery Saver через настройки (BroadcastReceiver)
    val isBatterySaverActive by settingsRepo.batterySaverState.collectAsState(initial = settingsRepo.isBatterySaverActive())
    // Per-account: показываем предупреждение если активный аккаунт не игнорирует Battery Saver
    val showBatterySaverWarning = isBatterySaverActive && (activeAccount?.ignoreBatterySaver != true)
    
    // Флаг для карточки-рекомендации по очистке
    var isRecommendationDismissed by rememberSaveable { mutableStateOf(false) }
    
    // Локализованные строки для списка
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
    val userFoldersName = Strings.userFolders
    
    // Переменные для диалога доната (вынесены для использования вне LazyColumn)
    val accountCopiedText = Strings.accountCopied
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        val mainListState = rememberLazyListState()
        LazyColumn(
            state = mainListState,
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
        
        // Предупреждение о Battery Saver
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
        
        // Приветственная карточка с анимацией
        item {
            val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
            var welcomeVisible by remember { mutableStateOf(!animationsEnabled) }
            LaunchedEffect(animationsEnabled) { welcomeVisible = true }
            
            val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
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
                            val safeAccountColor = try {
                                Color(activeAccount?.color ?: 0xFF1976D2.toInt())
                            } catch (_: Exception) {
                                Color(0xFF1976D2)
                            }
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Аватар аккаунта
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(safeAccountColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = activeAccount?.displayName?.firstOrNull()?.uppercase() ?: "?",
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
                                        val handRotation = rememberWobble(animationsEnabled, amplitude = 15f, durationMs = 400)
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
                            
                            // Время последней синхронизации — показываем после первой синхронизации
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
        
        // Карточка "Сегодня" — статистика за день (кликабельная)
        val hasTodayStats = todayEmailsCount > 0 || todayEventsCount > 0 || todayTasksCount > 0
        if (hasTodayStats) {
            item {
                TodayStatsCard(
                    emailsCount = todayEmailsCount,
                    eventsCount = todayEventsCount,
                    tasksCount = todayTasksCount,
                    onEmailsClick = {
                        // TODAY_ALL — кросс-папочный список (Inbox + пользовательские папки)
                        onEmailsTodayClick("TODAY_ALL", "")
                    },
                    onEventsClick = { onEventsTodayClick() },
                    onTasksClick = { onTasksTodayClick() }
                )
            }
        }
        
        // Рекомендация по очистке при > 1000 писем
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
        
        // Список папок и кнопка обновления
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
            
            // Основные папки для отображения
            val mainFolders = folders.filter { it.type in listOf(2, 3, 4, 5) }
            
            // Порядок: входящие, отправленные, черновики, удалённые, избранные, контакты
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
            // Пользовательские ПОЧТОВЫЕ папки — одна карточка-группа (сразу после черновиков)
            // При нажатии открывается UserFoldersScreen со списком всех папок
            val userMailFolderTypes = listOf(1, FolderType.USER_CREATED)
            val userFoldersList = folders.filter { it.type in userMailFolderTypes }
            val userFoldersCount = userFoldersList.size
            val userFoldersTotalUnread = userFoldersList.sumOf { it.unreadCount }
            if (userFoldersCount > 0) {
                orderedFolders.add(FolderDisplayData(
                    "user_folders", userFoldersName, userFoldersCount, userFoldersTotalUnread, -6
                ))
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
            itemsIndexed(chunkedFolders, key = { _, row -> row.firstOrNull()?.id ?: "" }) { index: Int, rowFolders: List<FolderDisplayData> ->
                val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
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
                        FolderRow(rowFolders, onFolderClick, onContactsClick, onNotesClick, onCalendarClick, onTasksClick, onUserFoldersClick)
                    }
                } else {
                    FolderRow(rowFolders, onFolderClick, onContactsClick, onNotesClick, onCalendarClick, onTasksClick, onUserFoldersClick)
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
            val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
            val changelogUrl = if (isRu) 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_RU.md"
            else 
                "https://github.com/DedovMosol/IwoMailClient/blob/main/CHANGELOG_EN.md"
            
            // Пульсация масштаба кнопки
            val pulseScale = rememberPulseScale(animationsEnabled, from = 1f, to = 1.02f, durationMs = 1500)
            
            // Анимация прозрачности рамки
            val borderAlpha = rememberPulseScale(animationsEnabled, from = 0.6f, to = 1f, durationMs = 1200)
            
            OutlinedButton(
                onClick = { uriHandler.openUri(changelogUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .scale(pulseScale),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current.gradientStart
                ),
                border = BorderStroke(
                    1.5.dp, 
                    com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current.gradientStart.copy(alpha = borderAlpha)
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
            val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
            val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
            
            // Пульсирующая анимация
            val pulseScale = rememberPulseScale(animationsEnabled, from = 1f, to = 1.03f, durationMs = 1000)

            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .scale(pulseScale)
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(colorTheme.gradientStart, colorTheme.gradientEnd)
                        )
                    )
                    .clickable { showDonateDialog = true }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Rocket, null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Strings.supportDeveloper, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
        
        // Отступ снизу для FAB
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
    
    // Диалог связи с разработчиком
    // Состояние вложенного диалога вынесено наружу чтобы сохранялось при повороте
    var showFinancialSupport by rememberSaveable { mutableStateOf(false) }
    
    if (showDonateDialog) {
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val useRowButtons = configuration.screenWidthDp >= 360
        val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
        
        // Пульсирующая анимация для кнопок диалога
        val tgPulse = rememberPulseScale(animationsEnabled, from = 1f, to = 1.04f, durationMs = 1200)
        val emailPulse = rememberPulseScale(animationsEnabled, from = 1f, to = 1.04f, durationMs = 1400)
        val supportPulse = rememberPulseScale(animationsEnabled, from = 1f, to = 1.03f, durationMs = 1000)
        
        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDonateDialog = false },
            modifier = Modifier.widthIn(max = 420.dp),
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🚀", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            },
            title = {
                Text(
                    Strings.supportDeveloper,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        if (LocalLanguage.current == AppLanguage.RUSSIAN)
                            "Проект активно развивается. Любая помощь важна: от отзывов и поиска багов — до пожертвований."
                        else
                            "The project is actively developing. Any help is valuable: from feedback and bug reports to donations.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { uriHandler.openUri("https://t.me/I_wantout") },
                            modifier = Modifier
                                .weight(1f)
                                .scale(tgPulse),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0088CC)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(AppIcons.Send, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("TG", style = MaterialTheme.typography.labelLarge)
                        }
                        
                        Button(
                            onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse("mailto:andreyid@outlook.com")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .scale(emailPulse),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0078D4)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(AppIcons.Email, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Email", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    
                    Button(
                        onClick = { showFinancialSupport = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(supportPulse),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE91E63)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(AppIcons.Favorite, null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (LocalLanguage.current == AppLanguage.RUSSIAN) "Поддержать разработчика"
                            else "Support developer"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDonateDialog = false }) {
                    Text(Strings.closeDialog)
                }
            }
        )
        
        // Вложенный диалог с реквизитами
        if (showFinancialSupport) {
            val accountNumberDisplay = "4081 7810 3544 0529 6071"
            val accountNumberRaw = "40817810354405296071"
            val isRu = LocalLanguage.current == AppLanguage.RUSSIAN
            val accountCopiedText = if (isRu) "Номер счёта скопирован" else "Account number copied"
            val copyAccountNumber = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Account", accountNumberRaw)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(context, accountCopiedText, android.widget.Toast.LENGTH_SHORT).show()
            }
            
            com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
                onDismissRequest = { showFinancialSupport = false },
                modifier = Modifier.widthIn(max = 420.dp),
                scrollable = false,
                icon = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("❤", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                },
                title = { 
                    Text(
                        Strings.supportDeveloper,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            if (isRu) "Реквизиты для поддержки проекта" else "Payment details for supporting the project",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        DonateInfoRow(Strings.recipient, "Додонов Андрей Игоревич")
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = Strings.accountNumber,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                onClick = copyAccountNumber
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = accountNumberDisplay,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        AppIcons.ContentCopy, 
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        
                        DonateInfoRow(Strings.bank, "Поволжский Банк ПАО Сбербанк")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showFinancialSupport = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(Strings.closeDialog)
                    }
                }
            )
        }
    }
        
        LazyColumnScrollbar(mainListState)
        
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
    tasksCount: Int,
    onEmailsClick: () -> Unit = {},
    onEventsClick: () -> Unit = {},
    onTasksClick: () -> Unit = {}
) {
    val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
    val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
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
                        label = if (isRussian) Strings.pluralEmails(emailsCount) else if (emailsCount == 1) "email" else "emails",
                        color = Color(0xFF5C6BC0), // Indigo — как Inbox
                        onClick = onEmailsClick
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
                        label = if (isRussian) Strings.pluralEvents(eventsCount) else if (eventsCount == 1) "event" else "events",
                        color = Color(0xFF42A5F5), // Blue — как Календарь
                        onClick = onEventsClick
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
                        label = if (isRussian) Strings.pluralTasks(tasksCount) else if (tasksCount == 1) "task" else "tasks",
                        color = Color(0xFFAB47BC), // Purple — как Задачи
                        onClick = onTasksClick
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
    color: Color,
    onClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
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
    onTasksClick: () -> Unit,
    onUserFoldersClick: () -> Unit = {}
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
                        "user_folders" -> onUserFoldersClick()
                        else -> onFolderClick(folder.id)
                    }
                },
                // Если одна карточка — не растягиваем, иначе weight(1f)
                modifier = if (rowFolders.size == 1) Modifier.fillMaxWidth(0.48f) else Modifier.weight(1f)
            )
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
    val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Анимация масштаба при нажатии
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
    
    // Анимация высоты карточки
    val elevation by animateDpAsState(
        targetValue = if (animationsEnabled && isPressed) 1.dp else 4.dp,
        animationSpec = if (animationsEnabled) tween(150) else snap(),
        label = "elevation"
    )
    
    // Анимация иконки (масштаб/поворот)
    val iconScale = rememberPulseScale(animationsEnabled, from = 1f, to = 1.08f, durationMs = 1200)
    val iconRotation = rememberWobble(animationsEnabled, amplitude = 2f, durationMs = 2000)
    
    // Цвета папок по типу
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
        -6 -> FolderColorsData(
            AppIcons.Folder, 
            listOf(Color(0xFFFF7043), Color(0xFFF4511E)) // Deep Orange
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
                // Иконка папки в карточке
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
                                -6 -> Strings.pluralFolders(count)  // Папки
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
                // Для черновиков (type 3), удалённых (type 4) и отправленных (type 5) badge не показываем
                if (unreadCount > 0 && type != 3 && type != 4 && type != 5) {
                    val badgeScale = rememberPulseScale(animationsEnabled, from = 1f, to = 1.15f, durationMs = 600)
                    
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
    val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
    val safeAccountColor = try {
        Color(accountColor)
    } catch (_: Exception) {
        Color(0xFF1976D2)
    }
    
    // Градиентная шапка поиска
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
                        .background(safeAccountColor),
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

// DrawerContent, DrawerHeader, AccountItem, FolderItem moved to MainScreenDrawer.kt
/**
 * Показывает push-уведомление о доступном обновлении.
 * При нажатии открывает экран обновлений.
 */
private fun showUpdateNotification(context: android.content.Context, versionName: String, isRussian: Boolean) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return
    }
    
    val intent = android.content.Intent(context, com.dedovmosol.iwomail.MainActivity::class.java).apply {
        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(com.dedovmosol.iwomail.MainActivity.EXTRA_OPEN_UPDATES, true)
    }
    
    val pendingIntent = android.app.PendingIntent.getActivity(
        context, 5000, intent,
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    )
    
    val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
    
    val builder = androidx.core.app.NotificationCompat.Builder(context, com.dedovmosol.iwomail.MailApplication.CHANNEL_UPDATE)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(com.dedovmosol.iwomail.ui.NotificationStrings.getUpdateAvailableTitle(isRussian))
        .setContentText(com.dedovmosol.iwomail.ui.NotificationStrings.getUpdateAvailableText(versionName, isRussian))
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
    
    // Уникальный ID для уведомления об обновлении (один на всё приложение)
    notificationManager.notify(5000, builder.build())
}

/**
 * Диалог автоматического обновления
 */
@Composable
private fun AutoUpdateDialog(
    updateInfo: com.dedovmosol.iwomail.update.UpdateInfo,
    context: android.content.Context,
    settingsRepo: com.dedovmosol.iwomail.data.repository.SettingsRepository,
    onDismiss: () -> Unit,
    onLater: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colorTheme = com.dedovmosol.iwomail.ui.theme.LocalColorTheme.current
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }
    
    val updateChecker = remember { com.dedovmosol.iwomail.update.UpdateChecker(context) }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = { if (downloadState !is DownloadState.Downloading) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    com.dedovmosol.iwomail.ui.theme.AppIcons.Update,
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
                                        is com.dedovmosol.iwomail.update.DownloadProgress.Starting -> {
                                            downloadState = DownloadState.Downloading
                                        }
                                        is com.dedovmosol.iwomail.update.DownloadProgress.Downloading -> {
                                            downloadProgress = progress.progress
                                        }
                                        is com.dedovmosol.iwomail.update.DownloadProgress.Completed -> {
                                            downloadedFile = progress.file
                                            downloadState = DownloadState.Completed
                                        }
                                        is com.dedovmosol.iwomail.update.DownloadProgress.Error -> {
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

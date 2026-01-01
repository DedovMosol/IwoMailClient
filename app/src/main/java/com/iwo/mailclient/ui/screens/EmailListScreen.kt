package com.iwo.mailclient.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.iwo.mailclient.data.database.AttachmentEntity
import com.iwo.mailclient.data.database.EmailEntity
import com.iwo.mailclient.data.database.FolderEntity
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.LocalLanguage
import com.iwo.mailclient.ui.AppLanguage
import com.iwo.mailclient.ui.NotificationStrings
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())
private val fullDateFormat = SimpleDateFormat("dd.MM.yy", Locale.getDefault())

// Цвета для аватаров как в Gmail — стабильные для каждой буквы
private val avatarColors = listOf(
    // Красные/розовые
    Color(0xFFE53935), // Red
    Color(0xFFD81B60), // Pink
    Color(0xFFC2185B), // Pink Dark
    // Фиолетовые
    Color(0xFF8E24AA), // Purple
    Color(0xFF7B1FA2), // Purple Dark
    Color(0xFF5E35B1), // Deep Purple
    Color(0xFF512DA8), // Deep Purple Dark
    // Синие
    Color(0xFF3949AB), // Indigo
    Color(0xFF303F9F), // Indigo Dark
    Color(0xFF1E88E5), // Blue
    Color(0xFF1976D2), // Blue Dark
    Color(0xFF039BE5), // Light Blue
    Color(0xFF0288D1), // Light Blue Dark
    // Голубые/бирюзовые
    Color(0xFF00ACC1), // Cyan
    Color(0xFF0097A7), // Cyan Dark
    Color(0xFF00897B), // Teal
    Color(0xFF00796B), // Teal Dark
    // Зелёные
    Color(0xFF43A047), // Green
    Color(0xFF388E3C), // Green Dark
    Color(0xFF7CB342), // Light Green
    Color(0xFF689F38), // Light Green Dark
    // Жёлтые/оранжевые
    Color(0xFFC0CA33), // Lime
    Color(0xFFAFB42B), // Lime Dark
    Color(0xFFFDD835), // Yellow
    Color(0xFFFBC02D), // Yellow Dark
    Color(0xFFFFB300), // Amber
    Color(0xFFFFA000), // Amber Dark
    Color(0xFFFB8C00), // Orange
    Color(0xFFF57C00), // Orange Dark
    Color(0xFFF4511E), // Deep Orange
    Color(0xFFE64A19), // Deep Orange Dark
    // Коричневые/серые
    Color(0xFF6D4C41), // Brown
    Color(0xFF5D4037), // Brown Dark
    Color(0xFF546E7A), // Blue Grey
    Color(0xFF455A64)  // Blue Grey Dark
)

/**
 * Генерирует стабильный цвет для аватара на основе имени/email
 * Одинаковые имена всегда получают одинаковый цвет
 */
private fun getAvatarColor(name: String): Color {
    if (name.isBlank()) return avatarColors[0]
    // Используем хэш от имени для стабильного цвета
    val hash = name.lowercase().hashCode()
    val index = (hash and 0x7FFFFFFF) % avatarColors.size
    return avatarColors[index]
}

// Фильтры
enum class MailFilter {
    ALL, UNREAD, STARRED, WITH_ATTACHMENTS, IMPORTANT
}

@Composable
fun MailFilter.label(): String = when (this) {
    MailFilter.ALL -> Strings.allMail
    MailFilter.UNREAD -> Strings.unreadOnly
    MailFilter.STARRED -> Strings.starred
    MailFilter.WITH_ATTACHMENTS -> Strings.withAttachments
    MailFilter.IMPORTANT -> Strings.important
}

enum class EmailDateFilter(val days: Int?) {
    ALL(null), TODAY(1), WEEK(7), MONTH(30), YEAR(365)
}

@Composable
fun EmailDateFilter.label(): String = when (this) {
    EmailDateFilter.ALL -> Strings.allDates
    EmailDateFilter.TODAY -> Strings.today
    EmailDateFilter.WEEK -> Strings.week
    EmailDateFilter.MONTH -> Strings.month
    EmailDateFilter.YEAR -> Strings.year
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailListScreen(
    folderId: String,
    onEmailClick: (String) -> Unit,
    onDraftClick: (String) -> Unit = onEmailClick, // Для черновиков - открыть в ComposeScreen
    onBackClick: () -> Unit,
    onComposeClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    initialFilter: MailFilter = MailFilter.ALL
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mailRepo = remember { MailRepository(context) }
    val database = remember { MailDatabase.getInstance(context) }
    val accountRepo = remember { com.iwo.mailclient.data.repository.AccountRepository(context) }
    val currentLanguage = LocalLanguage.current
    val isRussian = currentLanguage == AppLanguage.RUSSIAN
    
    val isFavorites = folderId == "favorites"
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    
    // Подписываемся на Flow напрямую - Room автоматически обновляет при изменении данных
    val emails by remember(folderId) { 
        mailRepo.getEmails(folderId) 
    }.collectAsState(initial = emptyList())
    
    val favoriteEmails by remember(activeAccount?.id) {
        mailRepo.getFlaggedEmails(activeAccount?.id ?: 0)
    }.collectAsState(initial = emptyList())
    
    var folder by remember { mutableStateOf<FolderEntity?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Определяем тип папки
    val isSpamFolder = folder?.type == 11 // type 11 = Spam/Junk
    val isTrashFolder = folder?.type == 4 // type 4 = Deleted Items
    val isDraftsFolder = folder?.type == 3 // type 3 = Drafts
    val isSentFolder = folder?.type == 5 // type 5 = Sent Items
    
    // Фильтры - используем initialFilter как начальное значение
    var showFilters by rememberSaveable { mutableStateOf(initialFilter != MailFilter.ALL) }
    var mailFilter by rememberSaveable { mutableStateOf(initialFilter) }
    var dateFilter by rememberSaveable { mutableStateOf(EmailDateFilter.ALL) }
    var fromFilter by rememberSaveable { mutableStateOf("") }
    var toFilter by rememberSaveable { mutableStateOf("") }
    
    // Режим выбора
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var showMoveDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeletePermanentlyDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<FolderEntity>>(emptyList()) }

    // Используем Flow для всех папок включая Черновики (теперь они серверные)
    val displayEmails = when {
        isFavorites -> favoriteEmails
        else -> emails
    }

    // Применяем фильтры
    val filteredEmails = remember(displayEmails, mailFilter, dateFilter, fromFilter, toFilter) {
        displayEmails.filter { email ->
            val matchesMail = when (mailFilter) {
                MailFilter.ALL -> true
                MailFilter.UNREAD -> !email.read
                MailFilter.STARRED -> email.flagged
                MailFilter.WITH_ATTACHMENTS -> email.hasAttachments
                MailFilter.IMPORTANT -> email.importance == 2
            }
            
            val matchesDate = dateFilter.days?.let { days ->
                val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
                email.dateReceived >= cutoff
            } ?: true
            
            val matchesFrom = fromFilter.isEmpty() || 
                email.from.contains(fromFilter, ignoreCase = true) ||
                email.fromName.contains(fromFilter, ignoreCase = true)
            
            val matchesTo = toFilter.isEmpty() || 
                email.to.contains(toFilter, ignoreCase = true)
            
            matchesMail && matchesDate && matchesFrom && matchesTo
        }.sortedByDescending { it.dateReceived }
    }
    
    // Количество активных фильтров
    val activeFiltersCount = listOf(
        mailFilter != MailFilter.ALL,
        dateFilter != EmailDateFilter.ALL,
        fromFilter.isNotEmpty(),
        toFilter.isNotEmpty()
    ).count { it }
    
    // Загружаем папки для перемещения
    LaunchedEffect(activeAccount?.id) {
        val accountId = activeAccount?.id ?: return@LaunchedEffect
        folders = withContext(Dispatchers.IO) {
            database.folderDao().getFoldersByAccountList(accountId)
        }
    }
    
    var folderSynced by rememberSaveable { mutableStateOf(false) }
    
    // Загружаем папку
    LaunchedEffect(folderId, activeAccount?.id) {
        if (!isFavorites) {
            val loadedFolder = withContext(Dispatchers.IO) { database.folderDao().getFolder(folderId) }
            folder = loadedFolder
        }
    }

    fun refresh() {
        if (isFavorites) return
        folder?.let { f ->
            scope.launch {
                isRefreshing = true
                errorMessage = null
                
                // Синхронизируем папку (для Черновиков вызовется syncDrafts)
                val result = withContext(Dispatchers.IO) { mailRepo.syncEmails(f.accountId, folderId) }
                when (result) {
                    is EasResult.Success -> {}
                    is EasResult.Error -> errorMessage = result.message
                }
                isRefreshing = false
            }
        }
    }
    
    fun deleteSelected() {
        val isRussian = currentLanguage == AppLanguage.RUSSIAN
        scope.launch {
            com.iwo.mailclient.util.SoundPlayer.playDeleteSound(context)
            // Перемещаем в корзину на сервере (не удаляем локально)
            val result = withContext(Dispatchers.IO) {
                mailRepo.moveToTrash(selectedIds.toList())
            }
            when (result) {
                is EasResult.Success -> {
                    // result.data > 0 означает перемещение, 0 означает окончательное удаление
                    val message = if (result.data > 0) {
                        NotificationStrings.getMovedToTrash(isRussian)
                    } else {
                        NotificationStrings.getDeletedPermanently(isRussian)
                    }
                    android.widget.Toast.makeText(
                        context, 
                        message, 
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is EasResult.Error -> {
                    val localizedMessage = NotificationStrings.localizeError(result.message, isRussian)
                    android.widget.Toast.makeText(
                        context, 
                        localizedMessage, 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            selectedIds = emptySet()
        }
    }
    
    fun deleteSelectedPermanently() {
        val isRussian = currentLanguage == AppLanguage.RUSSIAN
        scope.launch {
            com.iwo.mailclient.util.SoundPlayer.playDeleteSound(context)
            val result = withContext(Dispatchers.IO) {
                mailRepo.deleteEmailsPermanently(selectedIds.toList())
            }
            when (result) {
                is EasResult.Success -> {
                    android.widget.Toast.makeText(
                        context, 
                        NotificationStrings.getDeletedPermanently(isRussian), 
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is EasResult.Error -> {
                    val localizedMessage = NotificationStrings.localizeError(result.message, isRussian)
                    android.widget.Toast.makeText(
                        context, 
                        localizedMessage, 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            selectedIds = emptySet()
        }
    }
    
    fun markSelectedAsRead(read: Boolean) {
        scope.launch {
            selectedIds.forEach { id -> mailRepo.markAsRead(id, read) }
            selectedIds = emptySet()
        }
    }
    
    fun starSelected() {
        scope.launch {
            selectedIds.forEach { id -> mailRepo.toggleFlag(id) }
            selectedIds = emptySet()
        }
    }
    
    fun clearFilters() {
        mailFilter = MailFilter.ALL
        dateFilter = EmailDateFilter.ALL
        fromFilter = ""
        toFilter = ""
    }

    // Диалог подтверждения удаления (в корзину)
    if (showDeleteDialog) {
        val count = selectedIds.size
        com.iwo.mailclient.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(if (count == 1) Strings.deleteEmail else Strings.deleteEmails) },
            text = { 
                Text(
                    if (count == 1) Strings.emailWillBeMovedToTrash 
                    else Strings.emailsWillBeMovedToTrash(count)
                ) 
            },
            confirmButton = {
                com.iwo.mailclient.ui.theme.GradientDialogButton(
                    onClick = {
                        showDeleteDialog = false
                        deleteSelected()
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(Strings.no)
                }
            }
        )
    }
    
    // Диалог подтверждения окончательного удаления
    if (showDeletePermanentlyDialog) {
        val count = selectedIds.size
        com.iwo.mailclient.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeletePermanentlyDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.deleteForever) },
            text = { 
                Text(
                    if (count == 1) Strings.emailWillBeDeletedPermanently 
                    else Strings.emailsWillBeDeletedPermanently(count)
                ) 
            },
            confirmButton = {
                com.iwo.mailclient.ui.theme.GradientDialogButton(
                    onClick = {
                        showDeletePermanentlyDialog = false
                        deleteSelectedPermanently()
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeletePermanentlyDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог очистки корзины
    if (showEmptyTrashDialog) {
        val deletionController = com.iwo.mailclient.ui.components.LocalDeletionController.current
        val deletingMessage = Strings.deletingEmails(displayEmails.size)
        val trashEmptiedMsg = Strings.trashEmptied
        
        com.iwo.mailclient.ui.theme.StyledAlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.emptyTrash) },
            text = { Text(Strings.emptyTrashConfirm) },
            confirmButton = {
                com.iwo.mailclient.ui.theme.GradientDialogButton(
                    onClick = {
                        showEmptyTrashDialog = false
                        com.iwo.mailclient.util.SoundPlayer.playDeleteSound(context)
                        
                        val allEmailIds = displayEmails.map { it.id }
                        if (allEmailIds.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = allEmailIds,
                                message = deletingMessage,
                                scope = scope
                            ) { emailIds, onProgress ->
                                // Удаление с реальным прогрессом
                                val result = withContext(Dispatchers.IO) {
                                    mailRepo.deleteEmailsPermanentlyWithProgress(emailIds) { deleted, total ->
                                        onProgress(deleted, total)
                                    }
                                }
                                when (result) {
                                    is EasResult.Success -> {
                                        android.widget.Toast.makeText(
                                            context,
                                            trashEmptiedMsg,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    is EasResult.Error -> {
                                        val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                        android.widget.Toast.makeText(context, localizedMsg, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }

    // Диалог перемещения
    if (showMoveDialog) {
        var isMoving by remember { mutableStateOf(false) }
        
        // Системные типы папок
        // 2=Inbox, 3=Drafts, 4=Deleted, 5=Sent, 6=Outbox, 11=Spam
        val systemFolderTypes = setOf(2, 3, 4, 5, 6, 11)
        
        // Определяем тип текущей папки
        val currentFolderType = folder?.type ?: 0
        val isCurrentFolderSystem = currentFolderType in systemFolderTypes
        
        // Логика фильтрации:
        // - Из системной папки → показываем только пользовательские
        // - Из пользовательской папки → показываем все (системные + пользовательские)
        val availableFolders = folders.filter { targetFolder ->
            // Исключаем текущую папку
            if (targetFolder.id == folderId) return@filter false
            
            // Исключаем служебные папки (Outbox, Drafts)
            if (targetFolder.type == 6 || targetFolder.type == 3) return@filter false
            
            if (isCurrentFolderSystem) {
                // Из системной → только пользовательские
                targetFolder.type !in systemFolderTypes
            } else {
                // Из пользовательской → все папки (кроме Outbox и Drafts)
                true
            }
        }
        
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { if (!isMoving) showMoveDialog = false },
            title = { Text(Strings.moveTo) },
            text = {
                if (availableFolders.isEmpty()) {
                    Text(
                        Strings.noUserFolders,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn {
                        items(availableFolders) { targetFolder ->
                            ListItem(
                                headlineContent = { Text(Strings.getFolderName(targetFolder.type, targetFolder.displayName)) },
                                leadingContent = {
                                    Icon(getFolderIcon(targetFolder.type), null)
                                },
                                modifier = Modifier.clickable(enabled = !isMoving) {
                                    scope.launch {
                                        isMoving = true
                                        val result = withContext(Dispatchers.IO) {
                                            mailRepo.moveEmails(selectedIds.toList(), targetFolder.id)
                                        }
                                        isMoving = false
                                        showMoveDialog = false
                                        
                                        when (result) {
                                            is EasResult.Success -> {
                                                val msg = NotificationStrings.getMoved(isRussian) + ": ${result.data}"
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            is EasResult.Error -> {
                                                val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                                android.widget.Toast.makeText(context, localizedMsg, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                        selectedIds = emptySet()
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = { 
                if (isMoving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { showMoveDialog = false }) { Text(Strings.cancel) } 
                }
            }
        )
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onClearSelection = { selectedIds = emptySet() },
                    onMove = { showMoveDialog = true },
                    onRestore = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                mailRepo.restoreFromTrash(selectedIds.toList())
                            }
                            when (result) {
                                is EasResult.Success -> {
                                    val msg = NotificationStrings.getRestored(isRussian) + ": ${result.data}"
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                                is EasResult.Error -> {
                                    val localizedMsg = NotificationStrings.localizeError(result.message, isRussian)
                                    android.widget.Toast.makeText(context, localizedMsg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            selectedIds = emptySet()
                        }
                    },
                    onDelete = { 
                        // Для черновиков и удалённых - окончательное удаление
                        if (isDraftsFolder || isTrashFolder) {
                            showDeletePermanentlyDialog = true
                        } else {
                            showDeleteDialog = true
                        }
                    },
                    onMarkRead = { markSelectedAsRead(true) },
                    onStar = { starSelected() },
                    onMarkUnread = { markSelectedAsRead(false) },
                    onSpam = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                mailRepo.moveToSpam(selectedIds.toList())
                            }
                            when (result) {
                                is EasResult.Success -> {
                                    val msg = NotificationStrings.getMovedToSpam(isRussian) + ": ${result.data}"
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                                is EasResult.Error -> {
                                    android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            selectedIds = emptySet()
                        }
                    },
                    onDeletePermanently = { showDeletePermanentlyDialog = true },
                    isSpamFolder = isSpamFolder,
                    isTrashFolder = isTrashFolder,
                    showMoreMenu = showMoreMenu,
                    onToggleMoreMenu = { showMoreMenu = it }
                )
            } else {
                TopAppBar(
                    title = { 
                        val folderName = if (isFavorites) {
                            Strings.favorites
                        } else {
                            folder?.let { Strings.getFolderName(it.type, it.displayName) } ?: Strings.emails
                        }
                        Text(folderName, color = Color.White)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                        }
                    },
                    actions = {
                        // В корзине показываем кнопку очистки вместо поиска
                        if (isTrashFolder) {
                            IconButton(
                                onClick = { showEmptyTrashDialog = true },
                                enabled = displayEmails.isNotEmpty()
                            ) {
                                Icon(AppIcons.DeleteForever, Strings.emptyTrash, tint = Color.White)
                            }
                        } else {
                            IconButton(onClick = onSearchClick) {
                                Icon(AppIcons.Search, Strings.search, tint = Color.White)
                            }
                        }
                        if (!isFavorites) {
                            IconButton(onClick = { refresh() }, enabled = !isRefreshing) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Icon(AppIcons.Refresh, Strings.refresh, tint = Color.White)
                                }
                            }
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
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = onComposeClick,
                    containerColor = LocalColorTheme.current.gradientStart
                ) {
                    // FAB всегда карандаш в списке писем
                    Icon(
                        AppIcons.Edit, 
                        Strings.compose, 
                        tint = Color.White
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Панель фильтров
            FilterPanel(
                showFilters = showFilters,
                onToggleFilters = { showFilters = !showFilters },
                mailFilter = mailFilter,
                onMailFilterChange = { mailFilter = it },
                dateFilter = dateFilter,
                onDateFilterChange = { dateFilter = it },
                fromFilter = fromFilter,
                onFromFilterChange = { fromFilter = it },
                toFilter = toFilter,
                onToFilterChange = { toFilter = it },
                activeFiltersCount = activeFiltersCount,
                onClearFilters = { clearFilters() },
                totalCount = displayEmails.size,
                filteredCount = filteredEmails.size
            )
            
            // Список писем
            EmailList(
                emails = filteredEmails,
                selectedIds = selectedIds,
                isSelectionMode = isSelectionMode,
                isRefreshing = isRefreshing,
                errorMessage = errorMessage,
                isFavorites = isFavorites,
                isTrashOrSpam = isTrashFolder || isSpamFolder,
                isDrafts = isDraftsFolder,
                isSent = isSentFolder,
                onEmailClick = { email ->
                    if (isSelectionMode) {
                        selectedIds = if (email.id in selectedIds) selectedIds - email.id else selectedIds + email.id
                    } else {
                        // Для черновиков открываем ComposeScreen, для остальных - EmailDetailScreen
                        if (isDraftsFolder) {
                            onDraftClick(email.id)
                        } else {
                            onEmailClick(email.id)
                        }
                    }
                },
                onLongClick = { email -> selectedIds = selectedIds + email.id },
                onStarClick = { email -> scope.launch { mailRepo.toggleFlag(email.id) } },
                onSelectAll = { selectedIds = if (selectedIds.size == filteredEmails.size) emptySet() else filteredEmails.map { it.id }.toSet() },
                onRetry = { refresh() },
                onDismissError = { errorMessage = null }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterPanel(
    showFilters: Boolean,
    onToggleFilters: () -> Unit,
    mailFilter: MailFilter,
    onMailFilterChange: (MailFilter) -> Unit,
    dateFilter: EmailDateFilter,
    onDateFilterChange: (EmailDateFilter) -> Unit,
    fromFilter: String,
    onFromFilterChange: (String) -> Unit,
    toFilter: String,
    onToFilterChange: (String) -> Unit,
    activeFiltersCount: Int,
    onClearFilters: () -> Unit,
    totalCount: Int,
    filteredCount: Int
) {
    Column {
        // Строка с кнопкой фильтров
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (activeFiltersCount > 0) "${Strings.shown}: $filteredCount ${Strings.of} $totalCount" else "${Strings.total}: $totalCount",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            TextButton(onClick = onToggleFilters) {
                Icon(
                    AppIcons.FilterList,
                    null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showFilters) Strings.hideFilters else if (activeFiltersCount > 0) "${Strings.filters} ($activeFiltersCount)" else Strings.showFilters)
            }
        }
        
        // Панель фильтров (скрываемая)
        AnimatedVisibility(
            visible = showFilters,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                // Быстрые фильтры - чипы
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Фильтр типа почты
                    MailFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = mailFilter == filter,
                            onClick = { onMailFilterChange(if (mailFilter == filter) MailFilter.ALL else filter) },
                            label = { Text(filter.label()) }
                        )
                    }
                }

                // Фильтры по дате
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EmailDateFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = dateFilter == filter,
                            onClick = { onDateFilterChange(if (dateFilter == filter) EmailDateFilter.ALL else filter) },
                            label = { Text(filter.label()) },
                            leadingIcon = if (filter != EmailDateFilter.ALL) {
                                { Icon(AppIcons.DateRange, null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
                
                // Расширенные фильтры
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = fromFilter,
                            onValueChange = onFromFilterChange,
                            label = { Text(Strings.sender) },
                            placeholder = { Text(Strings.nameOrEmail) },
                            singleLine = true,
                            leadingIcon = { Icon(AppIcons.Person, null) },
                            trailingIcon = {
                                if (fromFilter.isNotEmpty()) {
                                    IconButton(onClick = { onFromFilterChange("") }) {
                                        Icon(AppIcons.Clear, Strings.close)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = toFilter,
                            onValueChange = onToFilterChange,
                            label = { Text(Strings.to) },
                            placeholder = { Text(Strings.nameOrEmail) },
                            singleLine = true,
                            leadingIcon = { Icon(AppIcons.PersonOutline, null) },
                            trailingIcon = {
                                if (toFilter.isNotEmpty()) {
                                    IconButton(onClick = { onToFilterChange("") }) {
                                        Icon(AppIcons.Clear, Strings.close)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        if (activeFiltersCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = onClearFilters,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(AppIcons.Clear, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(Strings.resetAll)
                            }
                        }
                    }
                }
            }
        }
        
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onMove: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onMarkRead: () -> Unit,
    onStar: () -> Unit,
    onMarkUnread: () -> Unit,
    onSpam: () -> Unit,
    onDeletePermanently: () -> Unit,
    isSpamFolder: Boolean,
    isTrashFolder: Boolean,
    showMoreMenu: Boolean,
    onToggleMoreMenu: (Boolean) -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount") },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(AppIcons.ArrowBack, Strings.cancelSelection)
            }
        },
        actions = {
            // В корзине - кнопка Восстановить, иначе - Переместить
            if (isTrashFolder) {
                IconButton(onClick = onRestore) { Icon(AppIcons.Restore, Strings.restore) }
            } else {
                IconButton(onClick = onMove) { Icon(AppIcons.DriveFileMove, Strings.moveTo) }
            }
            IconButton(onClick = onDelete) { Icon(AppIcons.Delete, Strings.delete) }
            Box {
                IconButton(onClick = { onToggleMoreMenu(true) }) {
                    Icon(AppIcons.MoreVert, Strings.more)
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { onToggleMoreMenu(false) }) {
                    // Пометить и Непрочитанное только если НЕ в корзине и НЕ в спаме
                    if (!isTrashFolder && !isSpamFolder) {
                        DropdownMenuItem(
                            text = { Text(Strings.star) },
                            onClick = { onToggleMoreMenu(false); onStar() },
                            leadingIcon = { Icon(AppIcons.Star, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.read) },
                            onClick = { onToggleMoreMenu(false); onMarkRead() },
                            leadingIcon = { Icon(AppIcons.MarkEmailRead, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.unreadAction) },
                            onClick = { onToggleMoreMenu(false); onMarkUnread() },
                            leadingIcon = { Icon(AppIcons.MarkEmailUnread, null) }
                        )
                    }
                    // В спаме - "Удалить окончательно", в корзине и обычных папках - "В спам"
                    if (isSpamFolder) {
                        DropdownMenuItem(
                            text = { Text(Strings.deletePermanently) },
                            onClick = { onToggleMoreMenu(false); onDeletePermanently() },
                            leadingIcon = { Icon(AppIcons.DeleteForever, null) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(Strings.toSpam) },
                            onClick = { onToggleMoreMenu(false); onSpam() },
                            leadingIcon = { Icon(AppIcons.Report, null) }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    )
}

@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
private fun EmailList(
    emails: List<EmailEntity>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    isRefreshing: Boolean,
    errorMessage: String?,
    isFavorites: Boolean,
    isTrashOrSpam: Boolean = false,
    isDrafts: Boolean = false,
    isSent: Boolean = false,
    onEmailClick: (EmailEntity) -> Unit,
    onLongClick: (EmailEntity) -> Unit,
    onStarClick: (EmailEntity) -> Unit,
    onSelectAll: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex < 3 } }
    val showScrollButton = emails.size > 5
    val pullRefreshState = rememberPullRefreshState(isRefreshing, onRetry)
    
    // Автоскролл вверх при входе в режим выделения
    LaunchedEffect(isSelectionMode) {
        if (isSelectionMode) {
            listState.animateScrollToItem(0)
        }
    }
    
    // Автоскролл вверх при появлении новых писем (во время синхронизации)
    var previousEmailCount by remember { mutableStateOf(emails.size) }
    LaunchedEffect(emails.size) {
        if (emails.size > previousEmailCount && previousEmailCount > 0) {
            // Новые письма появились — скроллим вверх
            listState.animateScrollToItem(0)
        }
        previousEmailCount = emails.size
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            errorMessage != null && emails.isEmpty() -> {
                ErrorContent(message = errorMessage, onRetry = onRetry, modifier = Modifier.fillMaxSize())
            }
            emails.isEmpty() && !isRefreshing -> {
                // Pull-to-refresh для пустого состояния
                Box(
                    modifier = if (!isFavorites) Modifier.fillMaxSize().pullRefresh(pullRefreshState) else Modifier.fillMaxSize()
                ) {
                    EmptyContent(
                        message = if (isFavorites) Strings.noFavoriteEmails else Strings.noEmails,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Индикатор обновления для пустого состояния
                    if (!isFavorites) {
                        EnvelopeRefreshIndicator(
                            refreshing = isRefreshing,
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }
            }
            else -> {
                // Pull-to-refresh (только если не Избранное)
                Box(
                    modifier = if (!isFavorites) Modifier.pullRefresh(pullRefreshState) else Modifier
                ) {
                    EmailListContent(
                        emails = emails,
                        selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        isTrashOrSpam = isTrashOrSpam,
                        isDrafts = isDrafts,
                        isSent = isSent,
                        listState = listState,
                        errorMessage = errorMessage,
                        onEmailClick = onEmailClick,
                        onLongClick = onLongClick,
                        onStarClick = onStarClick,
                        onSelectAll = onSelectAll,
                        onDismissError = onDismissError
                    )
                    
                    // Кастомный индикатор с конвертиком
                    if (!isFavorites) {
                        EnvelopeRefreshIndicator(
                            refreshing = isRefreshing,
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }

                if (showScrollButton && !isSelectionMode) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                if (isAtTop) listState.scrollToItem(emails.size - 1)
                                else listState.scrollToItem(0)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 24.dp).size(48.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(
                            imageVector = if (isAtTop) AppIcons.KeyboardArrowDown else AppIcons.KeyboardArrowUp,
                            contentDescription = if (isAtTop) Strings.toOld else Strings.toNew
                        )
                    }
                }
            }
        }
    }
}

/**
 * Кастомный индикатор pull-to-refresh с анимированным конвертиком
 */
@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
private fun EnvelopeRefreshIndicator(
    refreshing: Boolean,
    state: androidx.compose.material.pullrefresh.PullRefreshState,
    modifier: Modifier = Modifier
) {
    val colorTheme = LocalColorTheme.current
    val animationsEnabled = com.iwo.mailclient.ui.theme.LocalAnimationsEnabled.current
    
    // Анимации (только если включены)
    val rotation: Float
    val scale: Float
    val wobble: Float
    
    if (animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "envelope")
        rotation = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        ).value
        
        scale = infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        ).value
        
        wobble = infiniteTransition.animateFloat(
            initialValue = -15f,
            targetValue = 15f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "wobble"
        ).value
    } else {
        rotation = 0f
        scale = 1f
        wobble = 0f
    }
    
    // Прогресс вытягивания (0..1)
    val progress = if (refreshing) 1f else state.progress.coerceIn(0f, 1f)
    
    Box(
        modifier = modifier
            .padding(top = 16.dp)
            .size(56.dp)
            .graphicsLayer {
                // Появление при вытягивании
                alpha = progress
                translationY = (1f - progress) * -50f
            }
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colorTheme.gradientStart.copy(alpha = 0.2f),
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Фоновый круг
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = colorTheme.gradientStart.copy(alpha = 0.15f),
                    shape = CircleShape
                )
        )
        
        // Конвертик
        Icon(
            imageVector = AppIcons.Email,
            contentDescription = null,
            tint = colorTheme.gradientStart,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    if (refreshing) {
                        // При загрузке — вращение и пульсация
                        rotationZ = rotation
                        scaleX = scale
                        scaleY = scale
                    } else {
                        // При вытягивании — покачивание и масштаб по прогрессу
                        rotationZ = wobble * progress
                        val pullScale = 0.5f + (progress * 0.5f)
                        scaleX = pullScale
                        scaleY = pullScale
                    }
                }
        )
    }
}

@Composable
private fun EmailListContent(
    emails: List<EmailEntity>,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    isTrashOrSpam: Boolean,
    isDrafts: Boolean = false,
    isSent: Boolean = false,
    listState: androidx.compose.foundation.lazy.LazyListState,
    errorMessage: String?,
    onEmailClick: (EmailEntity) -> Unit,
    onLongClick: (EmailEntity) -> Unit,
    onStarClick: (EmailEntity) -> Unit,
    onSelectAll: () -> Unit,
    onDismissError: () -> Unit
) {
    val context = LocalContext.current
    val database = remember { MailDatabase.getInstance(context) }
    
    // Кэш превью изображений для писем с вложениями
    var imagePreviewCache by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    
    // Загружаем превью для писем с вложениями
    LaunchedEffect(emails) {
        val emailsWithAttachments = emails.filter { it.hasAttachments && it.id !in imagePreviewCache }
        if (emailsWithAttachments.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val newPreviews = mutableMapOf<String, String?>()
                emailsWithAttachments.forEach { email ->
                    val attachments = database.attachmentDao().getAttachmentsList(email.id)
                    // Ищем первое скачанное изображение
                    val imageAttachment = attachments.firstOrNull { att ->
                        att.downloaded && att.localPath != null && 
                        att.contentType.startsWith("image/", ignoreCase = true)
                    }
                    newPreviews[email.id] = imageAttachment?.localPath
                }
                imagePreviewCache = imagePreviewCache + newPreviews
            }
        }
    }
    
    LazyColumn(state = listState) {
        if (isSelectionMode) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onSelectAll).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = selectedIds.size == emails.size, onCheckedChange = { onSelectAll() })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Strings.selectAll)
                }
                HorizontalDivider()
            }
        }
        
        if (errorMessage != null) {
            item { ErrorBanner(message = errorMessage, onDismiss = onDismissError) }
        }
        
        items(emails, key = { it.id }) { email ->
            EmailListItem(
                email = email,
                isSelected = email.id in selectedIds,
                isSelectionMode = isSelectionMode,
                showStar = !isTrashOrSpam,
                isSent = isSent,
                imagePreviewPath = imagePreviewCache[email.id],
                onClick = { onEmailClick(email) },
                onLongClick = { onLongClick(email) },
                onStarClick = { onStarClick(email) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailListItem(
    email: EmailEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    showStar: Boolean = true,
    isSent: Boolean = false,
    imagePreviewPath: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            !email.read -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else -> MaterialTheme.colorScheme.surface
        },
        label = "bg"
    )
    
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick, 
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }
        ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Для папки Отправленные показываем получателя, иначе отправителя
            val displayName = if (isSent) {
                // Извлекаем имя получателя из поля to
                val toField = email.to
                val recipientName = when {
                    toField.contains("<") -> toField.substringBefore("<").trim().trim('"')
                    toField.contains("@") -> toField.substringBefore("@")
                    else -> toField
                }.ifEmpty { toField }
                "${Strings.toPrefix} $recipientName"
            } else {
                email.fromName.ifEmpty { email.from }
            }
            
            // Аватар с цветом на основе имени (получателя для Отправленных, отправителя для остальных)
            val avatarName = if (isSent) {
                // Извлекаем чистое имя для аватара
                val toField = email.to
                when {
                    toField.contains("<") -> toField.substringBefore("<").trim().trim('"')
                    toField.contains("@") -> toField.substringBefore("@")
                    else -> toField
                }.ifEmpty { toField }
            } else {
                email.fromName.ifEmpty { email.from }
            }
            val avatarColor = getAvatarColor(avatarName)
            
            // Первая буква/цифра для аватара
            val avatarInitial = avatarName
                .trim()
                .firstOrNull { it.isLetterOrDigit() }
                ?.uppercase()
                ?: "?"
            
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else avatarColor)
                    .combinedClickable(
                        onClick = { 
                            // Клик на аватар = клик на письмо (открыть или выделить/снять в режиме выделения)
                            onClick()
                        },
                        onLongClick = {
                            // Долгий клик = выделение
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = avatarInitial,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (email.read) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Иконка высокого приоритета (важное)
                    if (email.importance == 2) {
                        Icon(
                            AppIcons.PriorityHigh, 
                            contentDescription = Strings.important,
                            modifier = Modifier.size(16.dp), 
                            tint = Color(0xFFE53935) // Красный
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (email.hasAttachments) {
                        Icon(AppIcons.Attachment, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatDate(email.dateReceived),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (email.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                
                // Строка с темой и превью картинки
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = email.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (email.read) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // Миниатюра изображения (если есть скачанная картинка)
                    if (imagePreviewPath != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(java.io.File(imagePreviewPath))
                                .crossfade(true)
                                .size(48)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = email.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            if (showStar) {
                IconButton(onClick = onStarClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (email.flagged) AppIcons.Star else AppIcons.StarOutline,
                        contentDescription = Strings.favorites,
                        tint = if (email.flagged) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(AppIcons.ErrorOutline, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(Strings.retry) }
    }
}

@Composable
private fun EmptyContent(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(AppIcons.Inbox, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(AppIcons.Close, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun getFolderIcon(type: Int) = when (type) {
    2 -> AppIcons.Inbox
    3 -> AppIcons.Drafts
    4 -> AppIcons.Delete
    5 -> AppIcons.Send
    else -> AppIcons.Folder
}

private fun formatDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    
    return when {
        diff < 24 * 60 * 60 * 1000 && calendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> timeFormat.format(timestamp)
        diff < 7 * 24 * 60 * 60 * 1000 -> dayFormat.format(timestamp)
        calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> dateFormat.format(timestamp)
        else -> fullDateFormat.format(timestamp)
    }
}

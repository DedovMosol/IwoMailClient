package com.dedovmosol.iwomail.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape

import com.dedovmosol.iwomail.ui.components.DragSelectionIndicator
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.NetworkBanner
import com.dedovmosol.iwomail.ui.utils.rememberRotation
import com.dedovmosol.iwomail.ui.utils.rememberPulseScale
import com.dedovmosol.iwomail.ui.utils.rememberShake
import com.dedovmosol.iwomail.ui.utils.getAvatarColor
import com.dedovmosol.iwomail.ui.utils.formatRelativeDate
import com.dedovmosol.iwomail.network.NetworkMonitor
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

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
    initialFilter: MailFilter = MailFilter.ALL,
    initialDateFilter: EmailDateFilter = EmailDateFilter.ALL
) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val currentLanguage = LocalLanguage.current
    val isRussian = currentLanguage == AppLanguage.RUSSIAN
    val hapticScreen = LocalHapticFeedback.current
    val deletionController = com.dedovmosol.iwomail.ui.components.LocalDeletionController.current

    val viewModel: EmailListViewModel = viewModel(
        factory = EmailListViewModel.provideFactory(
            context.applicationContext as android.app.Application,
            folderId,
            initialFilter,
            initialDateFilter
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Производные значения состояния (только чтение из VM).
    val isFavorites = viewModel.isFavorites
    val isTodayAll = viewModel.isTodayAll
    val folder = uiState.folder
    val folders = uiState.folders
    val displayEmails = uiState.emails
    val mailFilter = uiState.mailFilter
    val dateFilter = uiState.dateFilter
    val showFilters = uiState.showFilters
    val selectedIds = uiState.selectedIds
    val isSelectionMode = uiState.isSelectionMode
    val isRefreshing = uiState.isRefreshing
    val errorMessage = uiState.errorMessage

    // Тип папки
    val isSpamFolder = folder?.type == FolderType.JUNK_EMAIL
    val isTrashFolder = folder?.type == FolderType.DELETED_ITEMS
    val isDraftsFolder = folder?.type == FolderType.DRAFTS
    val isSentFolder = folder?.type == FolderType.SENT_ITEMS

    val nothingDeletedMsg = if (isRussian) "Ничего не удалено" else "Nothing deleted"

    // UI-состояние диалогов/меню (не относится к данным) — переживает поворот.
    var showMoveDialog by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDeletePermanentlyDialog by rememberSaveable { mutableStateOf(false) }
    var showEmptyTrashDialog by rememberSaveable { mutableStateOf(false) }
    val deletingSelectedMessage = Strings.deletingEmails(selectedIds.size)

    // Применяем фильтры
    // PERF: вычисляем cutoff-дату ОДИН раз, а не на каждое письмо
    val filteredEmails = remember(displayEmails, mailFilter, dateFilter, isTodayAll) {
        val dateCutoff: Long = if (isTodayAll) 0L else when (dateFilter) {
            EmailDateFilter.ALL -> 0L
            EmailDateFilter.TODAY -> {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            else -> dateFilter.days?.let { days ->
                System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
            } ?: 0L
        }
        val needsDateFilter = dateCutoff > 0L
        val needsMailFilter = mailFilter != MailFilter.ALL

        displayEmails
            .distinctBy { it.id }
            .filter { email ->
                val matchesMail = if (!needsMailFilter) true else when (mailFilter) {
                    MailFilter.ALL -> true
                    MailFilter.UNREAD -> !email.read
                    MailFilter.STARRED -> email.flagged
                    MailFilter.WITH_ATTACHMENTS -> email.hasAttachments
                    MailFilter.IMPORTANT -> email.importance == 2
                }
                val matchesDate = !needsDateFilter || email.dateReceived >= dateCutoff
                matchesMail && matchesDate
            }
    }

    // Количество активных фильтров (рекомпозиция при изменении uiState).
    val activeFiltersCount = listOf(mailFilter != MailFilter.ALL, dateFilter != EmailDateFilter.ALL).count { it }

    // Одноразовые события из VM → локализованные тосты (VM эмитит семантические события).
    // LaunchedEffect(Unit) живёт дольше рекомпозиции, а язык (LocalLanguage) может смениться в
    // рантайме без пересоздания Activity, поэтому читаем актуальный флаг через rememberUpdatedState.
    val currentIsRussian by rememberUpdatedState(isRussian)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val ru = currentIsRussian
            val nothingDeleted = if (ru) "Ничего не удалено" else "Nothing deleted"
            when (event) {
                is EmailListEvent.MovedToTrash -> com.dedovmosol.iwomail.util.SafeToast.short(
                    context,
                    if (event.count > 0) NotificationStrings.getMovedToTrash(ru) else nothingDeleted
                )
                is EmailListEvent.DeletedPermanently -> com.dedovmosol.iwomail.util.SafeToast.short(
                    context,
                    if (event.count > 0) NotificationStrings.getDeletedPermanently(ru) else nothingDeleted
                )
                is EmailListEvent.Moved -> com.dedovmosol.iwomail.util.SafeToast.short(
                    context, NotificationStrings.getMoved(ru) + ": ${event.count}"
                )
                is EmailListEvent.Restored -> com.dedovmosol.iwomail.util.SafeToast.short(
                    context, NotificationStrings.getRestored(ru) + ": ${event.count}"
                )
                is EmailListEvent.MovedToSpam -> com.dedovmosol.iwomail.util.SafeToast.short(
                    context, NotificationStrings.getMovedToSpam(ru) + ": ${event.count}"
                )
                is EmailListEvent.Error -> com.dedovmosol.iwomail.util.SafeToast.long(
                    context, NotificationStrings.localizeError(event.message, ru)
                )
            }
        }
    }

    // Отмена уведомления о новых письмах при входе в папку «Входящие» — Android side-effect, в UI.
    LaunchedEffect(folder?.type, folder?.accountId) {
        val f = folder
        if (f != null && f.type == FolderType.INBOX && f.accountId > 0L) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(com.dedovmosol.iwomail.sync.NotificationHelper.notificationIdForAccount(f.accountId))
        }
    }

    // Тонкие обёртки UI-концернов (сеть/звук/контроллер удаления); сами операции — в ViewModel
    // (viewModelScope), поэтому переживают поворот экрана.
    fun refresh() {
        if (isFavorites || isTodayAll) return
        if (isRefreshing) return
        // Проверка сети требует Context → остаётся в UI.
        if (!NetworkMonitor.isNetworkAvailable(context)) {
            com.dedovmosol.iwomail.util.SafeToast.short(context, if (isRussian) "Нет сети" else "No network")
            return
        }
        viewModel.refresh()
    }

    fun deleteSelected() {
        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
        viewModel.deleteSelectedToTrash()
    }

    fun deleteSelectedPermanently() {
        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
        val emailIds = selectedIds.toList()
        if (emailIds.isEmpty()) {
            viewModel.clearSelection()
            return
        }

        // Для черновиков — окончательное удаление с сервера без прогресс-бара (через EWS deleteDraft).
        if (isDraftsFolder) {
            viewModel.deleteSelectedDrafts()
            return
        }

        // Для корзины/спама — удаление с реальным прогрессом через DeletionController (собственный
        // scope контроллера → переживает выход с экрана). Выделение снимаем сразу.
        viewModel.clearSelection()
        deletionController.startDeletion(
            emailIds = emailIds,
            message = deletingSelectedMessage,
            scope = scope
        ) { ids, onProgress ->
            when (val result = viewModel.deleteEmailsPermanently(ids) { deleted, total -> onProgress(deleted, total) }) {
                is EasResult.Success -> com.dedovmosol.iwomail.util.SafeToast.short(
                    context,
                    if (result.data > 0) NotificationStrings.getDeletedPermanently(isRussian) else nothingDeletedMsg
                )
                is EasResult.Error -> com.dedovmosol.iwomail.util.SafeToast.long(
                    context, NotificationStrings.localizeError(result.message, isRussian)
                )
            }
        }
    }

    // Диалог подтверждения удаления (в корзину)
    if (showDeleteDialog) {
        val count = selectedIds.size
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
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
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showDeleteDialog = false
                        deleteSelected()
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeleteDialog = false },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог подтверждения окончательного удаления
    if (showDeletePermanentlyDialog) {
        val count = selectedIds.size
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
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
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showDeletePermanentlyDialog = false
                        deleteSelectedPermanently()
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeletePermanentlyDialog = false },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог очистки корзины
    if (showEmptyTrashDialog) {
        val deletingMessage = Strings.deletingEmails(displayEmails.size)
        val trashEmptiedMsg = Strings.trashEmptied

        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.emptyTrash) },
            text = { Text(Strings.emptyTrashConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showEmptyTrashDialog = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)

                        val allEmailIds = displayEmails.map { it.id }
                        if (allEmailIds.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = allEmailIds,
                                message = deletingMessage,
                                scope = scope
                            ) { ids, onProgress ->
                                // Удаление с реальным прогрессом (репозиторий инкапсулирован в VM)
                                when (val result = viewModel.deleteEmailsPermanently(ids) { deleted, total -> onProgress(deleted, total) }) {
                                    is EasResult.Success -> com.dedovmosol.iwomail.util.SafeToast.short(context, trashEmptiedMsg)
                                    is EasResult.Error -> com.dedovmosol.iwomail.util.SafeToast.long(
                                        context, NotificationStrings.localizeError(result.message, isRussian)
                                    )
                                }
                            }
                        }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showEmptyTrashDialog = false },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог перемещения
    if (showMoveDialog) {
        // Типы папок НЕ для писем — скрыты в навигации, не должны появляться в диалоге переноса.
        // КРИТИЧНО: Должны совпадать с фильтром в EmailDetailScreen!
        // 7=Tasks, 8=Calendar, 9=Contacts, 10=Notes,
        // 13=User-created Calendar, 14=User-created Contacts,
        // 15=User-created Tasks, 17=User-created Mail (alias), 18=User-created Calendar (alias)
        // Типы 13-18 — служебные Exchange-папки, которые скрыты в навигации приложения.
        // Без этого фильтра пользователь видит их в диалоге переноса, но не может
        // найти перенесённые письма в приложении.
        val nonMailFolderTypes = setOf(
            FolderType.TASKS, FolderType.CALENDAR, FolderType.CONTACTS, FolderType.NOTES,
            FolderType.OUTBOX, FolderType.DRAFTS,
            13, 14, 15, 17, 18  // Journal, RecipientInfo и пользовательские Calendar/Contacts/Tasks
        )

        val availableFolders = folders.filter { targetFolder ->
            // Исключаем текущую папку
            if (targetFolder.id == folderId) return@filter false

            // Исключаем все не-почтовые и служебные папки
            if (targetFolder.type in nonMailFolderTypes) return@filter false

            true
        }

        com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(Strings.moveTo) },
            text = {
                if (availableFolders.isEmpty()) {
                    Text(
                        Strings.noUserFolders,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val lazyListState = rememberLazyListState()
                    // Ограничиваем высоту LazyColumn чтобы избежать вложенного скролла
                    Box(modifier = Modifier.heightIn(max = 300.dp)) {
                        LazyColumn(state = lazyListState) {
                            items(availableFolders, key = { it.id }) { targetFolder ->
                                ListItem(
                                    headlineContent = { Text(Strings.getFolderName(targetFolder.type, targetFolder.displayName)) },
                                    leadingContent = {
                                        Icon(getFolderIcon(targetFolder.type), null)
                                    },
                                    modifier = Modifier.clickable {
                                        // Перемещение выполняется в viewModelScope (переживает поворот);
                                        // диалог закрываем сразу, результат — тостом через канал событий.
                                        viewModel.moveSelectedTo(targetFolder.id)
                                        showMoveDialog = false
                                    }
                                )
                            }
                        }
                        LazyColumnScrollbar(lazyListState)
                    }
                }
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showMoveDialog = false },
                    text = Strings.cancel
                )
            }
        )
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onClearSelection = { viewModel.clearSelection() },
                    onMove = { showMoveDialog = true },
                    onRestore = { viewModel.restoreSelected() },
                    onDelete = {
                        // Для черновиков и удалённых - окончательное удаление
                        if (isDraftsFolder || isTrashFolder) {
                            showDeletePermanentlyDialog = true
                        } else {
                            showDeleteDialog = true
                        }
                    },
                    onMarkRead = { viewModel.markSelectedAsRead(true) },
                    onStar = { viewModel.starSelected() },
                    onUnstar = { viewModel.starSelected() },
                    onMarkUnread = { viewModel.markSelectedAsRead(false) },
                    isFavorites = isFavorites,
                    onSpam = { viewModel.moveSelectedToSpam() },
                    onDeletePermanently = {
                        showDeletePermanentlyDialog = true
                    },
                    isSentFolder = isSentFolder,
                    isSpamFolder = isSpamFolder,
                    isTrashFolder = isTrashFolder,
                    isDraftsFolder = isDraftsFolder,
                    showMoreMenu = showMoreMenu,
                    onToggleMoreMenu = { showMoreMenu = it }
                )
            } else {
                TopAppBar(
                    title = {
                        val folderName = when {
                            isFavorites -> Strings.favorites
                            isTodayAll -> Strings.today
                            else -> folder?.let { Strings.getFolderName(it.type, it.displayName) } ?: Strings.emails
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
                                onClick = {
                                    showEmptyTrashDialog = true
                                },
                                enabled = displayEmails.isNotEmpty()
                            ) {
                                Icon(AppIcons.DeleteForever, Strings.emptyTrash, tint = Color.White)
                            }
                        } else {
                            IconButton(onClick = onSearchClick) {
                                Icon(AppIcons.Search, Strings.search, tint = Color.White)
                            }
                        }
                        if (!isFavorites && !isTodayAll) {
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
                com.dedovmosol.iwomail.ui.theme.AnimatedFab(
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
            // Баннер "Нет сети"
            NetworkBanner()

            // Панель фильтров
            FilterPanel(
                showFilters = showFilters,
                onToggleFilters = { viewModel.toggleFilters() },
                mailFilter = mailFilter,
                onMailFilterChange = { viewModel.setMailFilter(it) },
                dateFilter = dateFilter,
                onDateFilterChange = { viewModel.setDateFilter(it) },
                activeFiltersCount = activeFiltersCount,
                onClearFilters = { viewModel.clearFilters() },
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
                isTodayAll = isTodayAll,
                isTrashOrSpam = isTrashFolder || isSpamFolder,
                isDrafts = isDraftsFolder,
                isSent = isSentFolder,
                onEmailClick = { email ->
                    if (isSelectionMode) {
                        hapticScreen.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.toggleSelection(email.id)
                    } else {
                        // Для черновиков открываем ComposeScreen, для остальных - EmailDetailScreen
                        if (isDraftsFolder) {
                            onDraftClick(email.id)
                        } else {
                            onEmailClick(email.id)
                        }
                    }
                },
                onLongClick = { email ->
                    viewModel.addToSelection(email.id)
                },
                onStarClick = { email -> viewModel.toggleFlag(email.id) },
                onSelectAll = { viewModel.selectAll(filteredEmails.map { it.id }) },
                onRetry = { refresh() },
                onDismissError = { viewModel.dismissError() },
                onDragSelect = { newIds -> viewModel.setSelection(newIds) }
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

                // Пользователь сам выбирает «Все письма» в фильтре при необходимости
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
    onUnstar: () -> Unit = {},
    onMarkUnread: () -> Unit,
    onSpam: () -> Unit,
    onDeletePermanently: () -> Unit,
    isFavorites: Boolean = false,
    isSentFolder: Boolean = false,
    isSpamFolder: Boolean,
    isTrashFolder: Boolean,
    isDraftsFolder: Boolean = false,
    showMoreMenu: Boolean,
    onToggleMoreMenu: (Boolean) -> Unit
) {
    val colorTheme = LocalColorTheme.current
    TopAppBar(
        title = { Text("$selectedCount", color = Color.White) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(AppIcons.ArrowBack, Strings.cancelSelection, tint = Color.White)
            }
        },
        actions = {
            // В избранном - кнопка "Убрать из избранного"
            if (isFavorites) {
                IconButton(onClick = onUnstar) { Icon(AppIcons.StarOutline, Strings.removeFromFavorites, tint = Color.White) }
            }
            // В корзине - кнопка Восстановить, иначе - Переместить
            if (isTrashFolder) {
                IconButton(onClick = onRestore) { Icon(AppIcons.Restore, Strings.restore, tint = Color.White) }
            } else {
                IconButton(onClick = onMove) { Icon(AppIcons.DriveFileMove, Strings.moveTo, tint = Color.White) }
            }
            IconButton(onClick = onDelete) { Icon(AppIcons.Delete, Strings.delete, tint = Color.White) }
            Box {
                IconButton(onClick = { onToggleMoreMenu(true) }) {
                    Icon(AppIcons.MoreVert, Strings.more, tint = Color.White)
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { onToggleMoreMenu(false) }) {
                    if (!isTrashFolder && !isSpamFolder && !isDraftsFolder) {
                        if (!isFavorites) {
                            DropdownMenuItem(
                                text = { Text(Strings.star) },
                                onClick = { onToggleMoreMenu(false); onStar() },
                                leadingIcon = { Icon(AppIcons.Star, null) }
                            )
                        }
                        if (!isSentFolder) {
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
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colorTheme.gradientStart)
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
    isTodayAll: Boolean = false,
    isTrashOrSpam: Boolean = false,
    isDrafts: Boolean = false,
    isSent: Boolean = false,
    onEmailClick: (EmailEntity) -> Unit,
    onLongClick: (EmailEntity) -> Unit,
    onStarClick: (EmailEntity) -> Unit,
    onSelectAll: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onDragSelect: (Set<String>) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex < 3 } }
    val showScrollButton = emails.size > 5
    val pullRefreshState = rememberPullRefreshState(isRefreshing, onRetry)

    var previousEmailCount by remember { mutableStateOf(emails.size) }
    LaunchedEffect(emails.size) {
        if (emails.size > previousEmailCount && previousEmailCount > 0 &&
            listState.firstVisibleItemIndex == 0) {
            listState.animateScrollToItem(0)
        }
        previousEmailCount = emails.size
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            errorMessage != null && emails.isEmpty() -> {
                ErrorContent(message = errorMessage, onRetry = onRetry, modifier = Modifier.fillMaxSize())
            }
            emails.isEmpty() -> {
                // Пустое состояние — загрузка или нет писем
                Box(
                    modifier = if (!isFavorites && !isTodayAll) Modifier.fillMaxSize().pullRefresh(pullRefreshState) else Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRefreshing) {
                        // Крутящийся конверт по центру при синхронизации пустой папки
                        EnvelopeRefreshIndicator(
                            refreshing = true,
                            state = pullRefreshState
                        )
                    } else {
                        EmptyContent(
                            message = if (isFavorites) Strings.noFavoriteEmails else Strings.noEmails,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Индикатор при pull-to-refresh пустого списка
                        if (!isFavorites && !isTodayAll && pullRefreshState.progress > 0) {
                            EnvelopeRefreshIndicator(
                                refreshing = false,
                                state = pullRefreshState
                            )
                        }
                    }
                }
            }
            else -> {
                // Pull-to-refresh (только если не Избранное)
                Box(
                    modifier = if (!isFavorites && !isTodayAll) Modifier.pullRefresh(pullRefreshState) else Modifier
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
                        onDismissError = onDismissError,
                        onDragSelect = onDragSelect
                    )

                    // Скроллбар
                    LazyColumnScrollbar(listState)

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
    val animationsEnabled = com.dedovmosol.iwomail.ui.theme.LocalAnimationsEnabled.current

    // Анимации (только если включены)
    val rotation = rememberRotation(animationsEnabled, durationMs = 1000)
    val scale = rememberPulseScale(animationsEnabled, from = 0.9f, to = 1.1f, durationMs = 500)
    val wobble = rememberShake(animationsEnabled, amplitude = 15f, durationMs = 300)

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
    onDismissError: () -> Unit,
    onDragSelect: (Set<String>) -> Unit = {}
) {
    val context = LocalContext.current
    val mailRepo = remember { RepositoryProvider.getMailRepository(context) }
    val database = remember { MailDatabase.getInstance(context) }

    val stableOnEmailClick by rememberUpdatedState(onEmailClick)
    val stableOnLongClick by rememberUpdatedState(onLongClick)
    val stableOnStarClick by rememberUpdatedState(onStarClick)

    var imagePreviewCache by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    val emailById = remember(emails) { emails.associateBy { it.id } }

    LaunchedEffect(emails) {
        val emailIds = emails.map { it.id }.toSet()
        val staleKeys = imagePreviewCache.keys - emailIds
        if (staleKeys.isNotEmpty()) {
            imagePreviewCache = imagePreviewCache - staleKeys
        }
    }

    LaunchedEffect(emailById, listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { it.key as? String }
                .filter { id -> emailById[id]?.hasAttachments == true }
                .distinct()
        }.distinctUntilChanged().collect { visibleEmailIds ->
            val missingIds = visibleEmailIds.filter { it !in imagePreviewCache }
            if (missingIds.isEmpty()) return@collect

            val newPreviews = withContext(Dispatchers.IO) {
                val allAttachments = missingIds.chunked(50).flatMap { database.attachmentDao().getAttachmentsForEmails(it) }
                val attachmentsByEmail = allAttachments.groupBy { it.emailId }

                missingIds.associateWith { emailId ->
                    attachmentsByEmail[emailId]
                        ?.firstOrNull { att ->
                            att.downloaded &&
                                att.localPath != null &&
                                att.contentType.startsWith("image/", ignoreCase = true)
                        }
                        ?.localPath
                }
            }

            if (newPreviews.isNotEmpty()) {
                val combined = imagePreviewCache + newPreviews
                imagePreviewCache = if (combined.size > 200) {
                    combined.entries.toList().takeLast(200).associate { it.toPair() }
                } else {
                    combined
                }
            }
        }
    }

    // === Drag Selection (переиспользуемый модификатор) ===
    val emailKeys = remember(emails) { emails.map { it.id } }
    val dragSelectModifier = com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier(
        listState = listState,
        itemKeys = emailKeys,
        selectedIds = selectedIds,
        onSelectionChange = onDragSelect
    )

    LazyColumn(
        state = listState,
        modifier = dragSelectModifier
    ) {
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
            val latestEmail by rememberUpdatedState(email)
            EmailListItem(
                email = email,
                mailRepo = mailRepo,
                context = context,
                isSelected = email.id in selectedIds,
                isSelectionMode = isSelectionMode,
                showStar = !isTrashOrSpam && !isDrafts,
                isSent = isSent,
                isDrafts = isDrafts,
                imagePreviewPath = imagePreviewCache[email.id],
                onClick = remember { { stableOnEmailClick(latestEmail) } },
                onLongClick = remember { { stableOnLongClick(latestEmail) } },
                onStarClick = remember { { stableOnStarClick(latestEmail) } }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailListItem(
    email: EmailEntity,
    mailRepo: com.dedovmosol.iwomail.data.repository.MailRepository,
    context: android.content.Context,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    showStar: Boolean = true,
    isSent: Boolean = false,
    isDrafts: Boolean = false,
    imagePreviewPath: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val longClickHandler = remember(onLongClick) {
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        }
    }

    val backgroundColor = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            !email.read -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else -> MaterialTheme.colorScheme.surface
        }

    Surface(
        modifier = Modifier.fillMaxWidth().then(
            if (isSelectionMode) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = longClickHandler
                )
            }
        ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            val cachedSenderName = remember(email.from) {
                if (email.fromName.isBlank() || email.fromName.contains("@")) {
                    mailRepo.getCachedName(email.from)
                } else {
                    mailRepo.cacheName(email.from, email.fromName)
                    null
                }
            }

            // Очищаем fromName от email части (формат "Имя <email>" или "Имя/email")
            val cleanFromName = remember(email.fromName) {
                val cleaned = email.fromName
                    .substringBefore("<").trim()  // Убираем всё после <
                    .substringBefore("/").trim()  // Убираем всё после /
                    .trim('"')  // Убираем кавычки
                    .trim()  // Убираем пробелы

                // КРИТИЧНО: Если после очистки остался email - возвращаем null
                if (cleaned.isBlank() || cleaned.contains("@")) {
                    null
                } else {
                    cleaned
                }
            }

            // Для папки Отправленные показываем получателя, иначе отправителя
            val displayName = if (isSent) {
                val toField = email.to
                val recipientName = when {
                    toField.contains("<") -> toField.substringBefore("<").trim().trim('"')
                    toField.contains("@") -> toField.substringBefore("@")
                    else -> toField
                }.ifEmpty { toField }
                "${Strings.toPrefix} $recipientName"
            } else {
                val finalName = if (cleanFromName != null && !cleanFromName.contains("@")) {
                    cleanFromName
                } else {
                    val cleanFrom = email.from
                        .substringBefore("<").trim()
                        .trim('"')
                        .trim()
                    cachedSenderName ?: cleanFrom
                }
                finalName.ifBlank { Strings.unknownSender }
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
                cachedSenderName ?: cleanFromName ?: email.from
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
                    .size(44.dp)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = longClickHandler
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(avatarColor)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarInitial,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                if (isSelectionMode) {
                    DragSelectionIndicator(
                        selected = isSelected,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(22.dp)
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
                            tint = com.dedovmosol.iwomail.ui.theme.AppColors.trash
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (email.hasAttachments) {
                        Icon(AppIcons.Attachment, Strings.attachments, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (isDrafts) {
                        val isLocalDraft = email.serverId.startsWith("local_draft_")
                        Text(
                            text = if (isLocalDraft) "[${Strings.localDraft}]" else "[${Strings.serverDraft}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLocalDraft) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = formatRelativeDate(email.dateReceived),
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
                        val imageModel = remember(imagePreviewPath) {
                            ImageRequest.Builder(context)
                                .data(java.io.File(imagePreviewPath))
                                .crossfade(true)
                                .size(48)
                                .build()
                        }
                        AsyncImage(
                            model = imageModel,
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
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onStarClick()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (email.flagged) AppIcons.Star else AppIcons.StarOutline,
                        contentDescription = if (email.flagged) Strings.removeFromFavorites else Strings.addToFavorites,
                        tint = if (email.flagged) com.dedovmosol.iwomail.ui.theme.AppColors.favorites else MaterialTheme.colorScheme.onSurfaceVariant,
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
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
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

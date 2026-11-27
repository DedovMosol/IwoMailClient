package com.dedovmosol.iwomail.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import com.dedovmosol.iwomail.ui.utils.rememberAppHaptics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.database.flag
import com.dedovmosol.iwomail.domain.SwipeAction
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.MailFlag
import com.dedovmosol.iwomail.ui.screens.emaillist.ContextMailLabel
import com.dedovmosol.iwomail.ui.screens.emaillist.MailConfirmation
import com.dedovmosol.iwomail.ui.screens.emaillist.MailListQuickScroll
import com.dedovmosol.iwomail.ui.screens.emaillist.FolderListRows
import com.dedovmosol.iwomail.ui.screens.emaillist.FolderMailList
import com.dedovmosol.iwomail.ui.screens.emaillist.MailRow
import com.dedovmosol.iwomail.ui.screens.emaillist.mailListContentPadding
import com.dedovmosol.iwomail.ui.screens.emaillist.mailListLabel
import com.dedovmosol.iwomail.ui.screens.emaillist.rememberFolderMailList
import com.dedovmosol.iwomail.ui.screens.emaillist.SwipeableEmailRow
import com.dedovmosol.iwomail.ui.screens.emaillist.SwipeRules
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
import kotlinx.coroutines.flow.first
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
    val currentLanguage = LocalLanguage.current
    val isRussian = currentLanguage == AppLanguage.RUSSIAN
    val hapticScreen = rememberAppHaptics()
    val deletionController = com.dedovmosol.iwomail.ui.components.LocalDeletionController.current

    val viewModel: EmailListViewModel = viewModel(
        factory = EmailListViewModel.provideFactory(
            context.applicationContext as android.app.Application,
            folderId,
            initialFilter,
            initialDateFilter
        )
    )
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value

    // Производные значения состояния (только чтение из VM).
    val isFavorites = viewModel.isFavorites
    val isTodayAll = viewModel.isTodayAll
    val folder = uiState.folder
    val folders = uiState.folders
    val mailFilter = uiState.mailFilter
    val dateFilter = uiState.dateFilter
    val showFilters = uiState.showFilters
    val isRefreshing = uiState.isRefreshing
    val errorMessage = uiState.errorMessage

    // Тип папки
    val isSpamFolder = folder?.type == FolderType.JUNK_EMAIL
    val isTrashFolder = folder?.type == FolderType.DELETED_ITEMS
    val isDraftsFolder = folder?.type == FolderType.DRAFTS
    val isSentFolder = folder?.type == FolderType.SENT_ITEMS
    val saveSenders = com.dedovmosol.iwomail.ui.components.rememberSaveSendersToContacts()
    val recipientFolderIds = remember(folders) {
        folders.filter { com.dedovmosol.iwomail.ui.screens.emaillist.folderShowsRecipient(it.type) }
            .mapTo(HashSet()) { it.id }
    }

    val nothingDeletedMsg = if (isRussian) "Ничего не удалено" else "Nothing deleted"

    // Свайпы по папке каждого письма (в «Избранном» и «Сегодня» папки разные); в режиме выбора свайпов нет
    val swipeSettings by remember { RepositoryProvider.getSettingsRepository(context).swipeActions }
        .collectAsStateWithLifecycle(initialValue = SwipeAction.DEFAULT_START to SwipeAction.DEFAULT_END)
    // Цепочки писем (настройка); в черновиках — нет: каждый черновик открывается в редакторе отдельно
    val listSettings = remember { RepositoryProvider.getSettingsRepository(context) }
    val conversationView by remember { listSettings.conversationView }
        .collectAsStateWithLifecycle(initialValue = listSettings.getConversationViewSync())
    val onlyThreads = uiState.onlyThreads && !isDraftsFolder
    var moveConfirmation by remember { mutableStateOf<MailConfirmation?>(null) }
    var deleteConfirmation by remember { mutableStateOf<MailConfirmation?>(null) }
    var permanentConfirmation by remember { mutableStateOf<MailConfirmation?>(null) }
    var emptyTrashConfirmation by remember { mutableStateOf<MailConfirmation?>(null) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }

    val folderList = if (uiState.sourceReady) {
        rememberFolderMailList(
            emails = uiState.emails,
            peerEmails = uiState.peerEmails,
            pendingRemovalIds = uiState.pendingRemovalIds,
            mailFilter = mailFilter,
            dateFilter = dateFilter,
            isTodayAll = isTodayAll,
            threaded = (conversationView || onlyThreads) && !isDraftsFolder,
            onlyThreads = onlyThreads
        )
    } else null
    // Keep the mounted list on a same-account refresh (scroll/preview caches), but only a
    // prepared model matching the current snapshot can supply action targets.
    var previousList by remember(uiState.accountId, folderId, folder?.type) { mutableStateOf<FolderMailList?>(null) }
    SideEffect { if (folderList != null) previousList = folderList }
    val visibleList = folderList ?: previousList
    // Действия (перенос, удаление, флажок, свайп, открытие) — только по готовой модели текущего снимка: [mailScope],
    // [displayEmails]. Показ, счётчики и выделение — по последней готовой модели [shownScope]: пока новая строится
    // в фоне (каждый батч синхронизации), выделение и жест протягивания не сбрасываются, «Всего: 0» не мигает.
    val displayEmails = folderList?.displayEmails.orEmpty()
    val mailScope = folderList?.scope
    val shownScope = visibleList?.scope
    val selectedIds = remember(shownScope, uiState.selectedIds) {
        shownScope?.selectOnly(uiState.selectedIds).orEmpty()
    }
    val isSelectionMode = selectedIds.isNotEmpty()
    val swipeRules = remember(swipeSettings, folders, isSelectionMode) {
        if (isSelectionMode) SwipeRules.NONE else SwipeRules.of(swipeSettings.first, swipeSettings.second, folders)
    }
    BackHandler(enabled = uiState.isSelectionMode) { viewModel.clearSelection() }
    SideEffect {
        if (mailScope != null && selectedIds != uiState.selectedIds && viewModel.uiState.value === uiState) {
            viewModel.setSelection(selectedIds)
        }
        if (mailScope != null) {
            if (moveConfirmation?.matches(selectedIds, displayEmails) == false) moveConfirmation = null
            if (deleteConfirmation?.matches(selectedIds, displayEmails) == false) deleteConfirmation = null
            if (permanentConfirmation?.matches(selectedIds, displayEmails) == false) permanentConfirmation = null
        }
        if (emptyTrashConfirmation != null && !isTrashFolder) emptyTrashConfirmation = null
    }
    // Выделение — не действие: не сверяем снимок, иначе обновления протягивания между публикацией состояния VM и
    // перекомпоновкой терялись. Ушедшие с сервера письма отсечёт свежая модель (SideEffect выше), действия сверяют
    // снимок сами ([withSelection]).
    fun updateSelection(ids: Set<String>) {
        shownScope?.let { viewModel.setSelection(it.selectOnly(ids)) }
    }
    fun withSelection(confirmation: MailConfirmation? = null, action: () -> Unit) {
        // If the VM has published a new source/filter/selection and Compose hasn't rendered it yet,
        // fail closed. In particular a confirmation must not follow a replaced ServerId.
        if (mailScope != null && viewModel.uiState.value === uiState && selectedIds.isNotEmpty() && selectedIds == uiState.selectedIds &&
            (confirmation == null || confirmation.matches(selectedIds, displayEmails))) action()
    }
    val activeFiltersCount = listOf(mailFilter != MailFilter.ALL, dateFilter != EmailDateFilter.ALL, onlyThreads).count { it }

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

    val deletingSelectedMessage = Strings.deletingEmails(selectedIds.size)
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
    deleteConfirmation?.let { confirmation ->
        val count = confirmation.count
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { deleteConfirmation = null },
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
                        deleteConfirmation = null
                        withSelection(confirmation) { deleteSelected() }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { deleteConfirmation = null },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог подтверждения окончательного удаления
    permanentConfirmation?.let { confirmation ->
        val count = confirmation.count
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { permanentConfirmation = null },
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
                        permanentConfirmation = null
                        withSelection(confirmation) { deleteSelectedPermanently() }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { permanentConfirmation = null },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог очистки корзины
    emptyTrashConfirmation?.let { confirmation ->
        val deletingMessage = Strings.deletingEmails(confirmation.count)
        val trashEmptiedMsg = Strings.trashEmptied

        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { emptyTrashConfirmation = null },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.emptyTrash) },
            text = { Text(Strings.emptyTrashConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = confirm@{
                        emptyTrashConfirmation = null
                        val currentIds = displayEmails.mapTo(LinkedHashSet()) { it.id }
                        if (viewModel.uiState.value !== uiState || !confirmation.matches(currentIds, displayEmails)) return@confirm
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)

                        val allEmailIds = currentIds.toList()
                        if (allEmailIds.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = allEmailIds,
                                message = deletingMessage,
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
                    onClick = { emptyTrashConfirmation = null },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог перемещения
    moveConfirmation?.let { confirmation ->
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
            onDismissRequest = { moveConfirmation = null },
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
                                        withSelection(confirmation) { viewModel.moveSelectedTo(targetFolder.id) }
                                        moveConfirmation = null
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
                    onClick = { moveConfirmation = null },
                    text = Strings.cancel
                )
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (isSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    onClearSelection = { viewModel.clearSelection() },
                    onMove = { moveConfirmation = MailConfirmation(selectedIds, displayEmails) },
                    onRestore = { withSelection { viewModel.restoreSelected() } },
                    onDelete = {
                        // Для черновиков и удалённых - окончательное удаление
                        if (isDraftsFolder || isTrashFolder) {
                            permanentConfirmation = MailConfirmation(selectedIds, displayEmails)
                        } else {
                            deleteConfirmation = MailConfirmation(selectedIds, displayEmails)
                        }
                    },
                    onMarkRead = { withSelection { viewModel.markSelectedAsRead(true) } },
                    onStar = { withSelection { viewModel.starSelected() } },
                    onUnstar = { withSelection { viewModel.starSelected() } },
                    onMarkUnread = { withSelection { viewModel.markSelectedAsRead(false) } },
                    isFavorites = isFavorites,
                    onSpam = { withSelection { viewModel.moveSelectedToSpam() } },
                    onDeletePermanently = {
                        permanentConfirmation = MailConfirmation(selectedIds, displayEmails)
                    },
                    isSentFolder = isSentFolder,
                    isSpamFolder = isSpamFolder,
                    isTrashFolder = isTrashFolder,
                    isDraftsFolder = isDraftsFolder,
                    onSaveSenders = if (isSentFolder || isDraftsFolder || folder?.type == FolderType.OUTBOX) null else {
                        {
                            withSelection {
                                val ids = selectedIds
                                saveSenders(displayEmails.filter { it.id in ids })
                                viewModel.clearSelection()
                            }
                        }
                    },
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
                                    emptyTrashConfirmation = MailConfirmation(displayEmails.mapTo(LinkedHashSet()) { it.id }, displayEmails)
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
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
                onlyThreads = onlyThreads,
                onToggleThreads = if (isDraftsFolder) null else ({ viewModel.setOnlyThreads(!onlyThreads) }),
                activeFiltersCount = activeFiltersCount,
                onClearFilters = { viewModel.clearFilters() },
                totalCount = visibleList?.displayEmails?.size ?: 0,
                filteredCount = shownScope?.selectableIds?.size ?: 0
            )

            // Список писем
            EmailList(
                folderList = visibleList,
                selectedIds = selectedIds,
                isSelectionMode = isSelectionMode,
                isRefreshing = isRefreshing,
                errorMessage = errorMessage,
                isFavorites = isFavorites,
                isTodayAll = isTodayAll,
                isTrashOrSpam = isTrashFolder || isSpamFolder,
                isDrafts = isDraftsFolder,
                isSent = isSentFolder,
                recipientFolderIds = recipientFolderIds,
                onEmailClick = click@{ email ->
                    if (isSelectionMode) {
                        hapticScreen.tick()
                        updateSelection(MailRow.toggleThread(selectedIds, listOf(email.id)))
                        return@click
                    }
                    if (mailScope == null || viewModel.uiState.value !== uiState) return@click
                    // Для черновиков открываем ComposeScreen, для остальных - EmailDetailScreen
                    if (isDraftsFolder) onDraftClick(email.id) else onEmailClick(email.id)
                },
                onLongClick = { email ->
                    updateSelection(selectedIds + email.id)
                },
                onStarClick = { email ->
                    if (viewModel.uiState.value === uiState && email.id in mailScope?.selectableIds.orEmpty()) viewModel.toggleFlag(email.id)
                },
                onFlagChosen = { email, flag ->
                    if (viewModel.uiState.value === uiState && email.id in mailScope?.selectableIds.orEmpty()) viewModel.setFlag(email.id, flag)
                },
                // Свайп сверяет снимок сам (onSwipe ниже) и строку всегда возвращает: отключать свайпы на кадр
                // перестройки модели не нужно — это пересоздавало строки и обрывало жесты
                swipeRules = swipeRules,
                onSwipe = swipe@{ email, action ->
                    if (viewModel.uiState.value !== uiState || email.id !in mailScope?.selectableIds.orEmpty()) return@swipe
                    if (action == SwipeAction.DELETE) com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                    viewModel.swipe(email, action)
                },
                onSelectAll = {
                    val all = shownScope?.selectableIds.orEmpty()
                    updateSelection(if (selectedIds == all) emptySet() else all)
                },
                onRetry = { refresh() },
                onDismissError = { viewModel.dismissError() },
                onDragSelect = ::updateSelection
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
    onlyThreads: Boolean,
    onToggleThreads: (() -> Unit)?,
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
                    if (onToggleThreads != null) FilterChip(
                        selected = onlyThreads,
                        onClick = onToggleThreads,
                        label = { Text(mailListLabel("Только цепочки", "Threads only")) }
                    )
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

                // Кнопка сброса всех фильтров (видна только когда есть активные)
                if (activeFiltersCount > 0) {
                    TextButton(
                        onClick = onClearFilters,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(AppIcons.Clear, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(Strings.resetAll)
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
    onUnstar: () -> Unit = {},
    onMarkUnread: () -> Unit,
    onSpam: () -> Unit,
    onDeletePermanently: () -> Unit,
    isFavorites: Boolean = false,
    isSentFolder: Boolean = false,
    isSpamFolder: Boolean,
    isTrashFolder: Boolean,
    isDraftsFolder: Boolean = false,
    /** «Отправителей в контакты»; null — пункта нет (свои письма: «Отправленные», черновики, «Исходящие»). */
    onSaveSenders: (() -> Unit)? = null,
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
                    if (onSaveSenders != null) {
                        DropdownMenuItem(
                            text = { Text(if (selectedCount == 1) Strings.addSenderToContacts else Strings.addSendersToContacts) },
                            onClick = { onToggleMoreMenu(false); onSaveSenders() },
                            leadingIcon = { Icon(AppIcons.PersonAdd, null) }
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
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colorTheme.gradientStart)
    )
}

@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
private fun EmailList(
    folderList: FolderMailList?,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    isRefreshing: Boolean,
    errorMessage: String?,
    isFavorites: Boolean,
    isTodayAll: Boolean = false,
    isTrashOrSpam: Boolean = false,
    isDrafts: Boolean = false,
    isSent: Boolean = false,
    recipientFolderIds: Set<String> = emptySet(),
    onEmailClick: (EmailEntity) -> Unit,
    onLongClick: (EmailEntity) -> Unit,
    onStarClick: (EmailEntity) -> Unit,
    onFlagChosen: (EmailEntity, MailFlag) -> Unit,
    swipeRules: SwipeRules,
    onSwipe: (EmailEntity, SwipeAction) -> Unit,
    onSelectAll: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onDragSelect: (Set<String>) -> Unit
) {
    val listState = rememberLazyListState()
    var expandedThreads by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val emailCount = folderList?.scope?.selectableIds?.size
    val pullRefreshState = rememberPullRefreshState(isRefreshing, onRetry)

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            folderList == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            errorMessage != null && emailCount == 0 -> {
                ErrorContent(message = errorMessage, onRetry = onRetry, modifier = Modifier.fillMaxSize())
            }
            emailCount == 0 -> {
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
                        folderList = folderList,
                        expandedThreads = expandedThreads,
                        onToggleThread = { threadKey ->
                            expandedThreads = if (threadKey in expandedThreads) expandedThreads - threadKey else expandedThreads + threadKey
                        },
                        selectedIds = selectedIds,
                        isSelectionMode = isSelectionMode,
                        isTrashOrSpam = isTrashOrSpam,
                        isDrafts = isDrafts,
                        isSent = isSent,
                        recipientFolderIds = recipientFolderIds,
                        listState = listState,
                        errorMessage = errorMessage,
                        onEmailClick = onEmailClick,
                        onLongClick = onLongClick,
                        onStarClick = onStarClick,
                        onFlagChosen = onFlagChosen,
                        swipeRules = swipeRules,
                        onSwipe = onSwipe,
                        onSelectAll = onSelectAll,
                        onDismissError = onDismissError,
                        onDragSelect = onDragSelect
                    )

                    // Скроллбар
                    LazyColumnScrollbar(listState)

                    // Кастомный индикатор с конвертиком.
                    // Гейт по видимости обязателен: внутри 3 InfiniteTransition, которые
                    // рекомпозируют индикатор каждый кадр — без гейта это происходит
                    // постоянно (при alpha=0), съедая FPS списка и батарею
                    if (!isFavorites && (isRefreshing || pullRefreshState.progress > 0f)) {
                        EnvelopeRefreshIndicator(
                            refreshing = isRefreshing,
                            state = pullRefreshState,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }

                MailListQuickScroll(listState, enabled = !isSelectionMode, modifier = Modifier.align(Alignment.BottomStart))
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

    // Прогресс вытягивания (0..1)
    val progress = if (refreshing) 1f else state.progress.coerceIn(0f, 1f)

    // Анимации (только если включены) — и только пока они видны: бесконечная анимация скрытого индикатора
    // гоняла бы кадры над списком всё время (ONB-19). Значения читаются при отрисовке слоя, не в композиции.
    val rotation = rememberRotation(animationsEnabled && refreshing, durationMs = 1000)
    val scale = rememberPulseScale(animationsEnabled && refreshing, from = 0.9f, to = 1.1f, durationMs = 500)
    val wobble = rememberShake(animationsEnabled && !refreshing && progress > 0f, amplitude = 15f, durationMs = 300)

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
                        rotationZ = rotation.value
                        scaleX = scale.value
                        scaleY = scale.value
                    } else {
                        // При вытягивании — покачивание и масштаб по прогрессу
                        rotationZ = wobble.value * progress
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
    folderList: FolderMailList,
    expandedThreads: List<String>,
    onToggleThread: (String) -> Unit,
    selectedIds: Set<String>,
    isSelectionMode: Boolean,
    isTrashOrSpam: Boolean,
    isDrafts: Boolean = false,
    isSent: Boolean = false,
    recipientFolderIds: Set<String> = emptySet(),
    listState: androidx.compose.foundation.lazy.LazyListState,
    errorMessage: String?,
    onEmailClick: (EmailEntity) -> Unit,
    onLongClick: (EmailEntity) -> Unit,
    onStarClick: (EmailEntity) -> Unit,
    onFlagChosen: (EmailEntity, MailFlag) -> Unit,
    swipeRules: SwipeRules,
    onSwipe: (EmailEntity, SwipeAction) -> Unit,
    onSelectAll: () -> Unit,
    onDismissError: () -> Unit,
    onDragSelect: (Set<String>) -> Unit
) {
    val mailScope = folderList.scope
    val context = LocalContext.current
    val mailRepo = remember { RepositoryProvider.getMailRepository(context) }
    val stableOnFlagChosen by rememberUpdatedState(onFlagChosen)
    val stableOnSwipe by rememberUpdatedState(onSwipe)
    val database = remember { MailDatabase.getInstance(context) }

    val stableOnEmailClick by rememberUpdatedState(onEmailClick)
    val stableOnLongClick by rememberUpdatedState(onLongClick)
    val stableOnStarClick by rememberUpdatedState(onStarClick)

    // Миниатюры — только найденные: письма без картинки проверяются снова (её могли скачать при открытии письма)
    var imagePreviewCache by remember { mutableStateOf<Map<String, com.dedovmosol.iwomail.ui.screens.emaillist.ListPreview>>(emptyMap()) }
    val emailById = folderList.emailsById
    val previewScope = rememberCoroutineScope()
    val imagePreviews = remember(mailRepo) {
        val settingsRepo = com.dedovmosol.iwomail.data.repository.SettingsRepository.getInstance(context)
        com.dedovmosol.iwomail.ui.screens.emaillist.ListImagePreviews(
            attachmentsOf = { ids ->
                withContext(Dispatchers.IO) { ids.chunked(50).flatMap { database.attachmentDao().getAttachmentsForEmails(it) } }
            },
            downloadToFile = { accountId, row ->
                withContext(Dispatchers.IO) { mailRepo.downloadDraftAttachmentToFile(accountId, row) }
            },
            fileExists = { path -> withContext(Dispatchers.IO) { java.io.File(path).exists() } },
            // «Синхронизация только по Wi-Fi» — и миниатюры не качаем по мобильной сети
            downloadAllowed = {
                !settingsRepo.syncOnWifiOnly.first() ||
                    context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == false
            }
        )
    }
    fun withPreviews(
        added: Map<String, com.dedovmosol.iwomail.ui.screens.emaillist.ListPreview>
    ): Map<String, com.dedovmosol.iwomail.ui.screens.emaillist.ListPreview> {
        val combined = imagePreviewCache + added
        return if (combined.size > 200) combined.entries.toList().takeLast(200).associate { it.toPair() } else combined
    }

    LaunchedEffect(mailScope) {
        val staleKeys = imagePreviewCache.keys - emailById.keys
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
            // Черновикам недостающая картинка скачивается (свои, их немного); остальным — только скачанные.
            val found = imagePreviews.find(missingIds, download = isDrafts && imagePreviews.downloadAllowed())
            if (found.local.isNotEmpty()) imagePreviewCache = withPreviews(found.local)
            for ((emailId, pending) in found.toDownload) {
                val accountId = emailById[emailId]?.accountId ?: continue
                // Не в collect: загрузка не задерживает разбор следующей прокрутки
                previewScope.launch {
                    imagePreviews.download(accountId, pending)?.let { preview -> imagePreviewCache = withPreviews(mapOf(emailId to preview)) }
                }
            }
        }
    }

    val stableOnToggleThread by rememberUpdatedState(onToggleThread)
    val rows = remember(folderList, expandedThreads) {
        if (expandedThreads.isEmpty()) folderList.collapsedRows
        else FolderListRows(mailScope.rows(expandedThreads.toSet()))
    }

    // Drag selection uses the same scope as the toolbar and select-all.
    val dragSelectModifier = com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier(
        listState = listState,
        itemKeys = rows.keys,
        selectedIds = selectedIds,
        onSelectionChange = onDragSelect,
        expand = { key -> mailScope.dragTargets(key, rows.threadSelection) }
    )
    val haptic = rememberAppHaptics()
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentSelectionMode by rememberUpdatedState(isSelectionMode)
    val stableOnDragSelect by rememberUpdatedState(onDragSelect)

    LazyColumn(
        state = listState,
        modifier = dragSelectModifier,
        contentPadding = mailListContentPadding(isSelectionMode)
    ) {
        if (isSelectionMode) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onSelectAll).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = selectedIds == mailScope.selectableIds, onCheckedChange = { onSelectAll() })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Strings.selectAll)
                }
                HorizontalDivider()
            }
        }

        if (errorMessage != null) {
            item { ErrorBanner(message = errorMessage, onDismiss = onDismissError) }
        }

        items(rows.items, key = { it.key }, contentType = { it::class }) { row ->
            if (row is MailRow.Header) {
                // Нажатие — раскрыть или свернуть; в режиме выбора нажатие и долгое нажатие — выбрать все письма цепочки.
                // Свайпа и флажка нет: действие над всей цепочкой легко принять за действие над одним письмом
                val latestHeader by rememberUpdatedState(row)
                EmailListItem(
                    email = row.email,
                    mailRepo = mailRepo,
                    isSelected = selectedIds.containsAll(row.selectableIds),
                    isSelectionMode = isSelectionMode,
                    showStar = false,
                    isSent = isSent,
                    isDrafts = isDrafts,
                    recipientFolderIds = recipientFolderIds,
                    thread = row.thread,
                    expanded = row.expanded,
                    onClickLabel = if (isSelectionMode) null else if (row.expanded) Strings.collapseThread else Strings.expandThread,
                    onClick = remember {
                        {
                            val thread = latestHeader.thread
                            if (currentSelectionMode) {
                                haptic.tick()
                                stableOnDragSelect(MailRow.toggleThread(currentSelectedIds, latestHeader.selectableIds))
                            } else {
                                stableOnToggleThread(thread.key)
                            }
                        }
                    },
                    onLongClick = remember { { stableOnDragSelect(currentSelectedIds + latestHeader.selectableIds) } },
                    onStarClick = {},
                    onFlagChosen = {}
                )
                return@items
            }
            val email = row.email
            val selectable = email.id in mailScope.selectableIds
            val latestEmail by rememberUpdatedState(email)
            SwipeableEmailRow(
                startAction = if (selectable) swipeRules.start(email.folderId) else SwipeAction.NONE,
                endAction = if (selectable) swipeRules.end(email.folderId) else SwipeAction.NONE,
                read = email.read,
                flagged = email.flagged,
                onSwipe = remember { { action: SwipeAction -> stableOnSwipe(latestEmail, action) } }
            ) {
                EmailListItem(
                    email = email,
                    mailRepo = mailRepo,
                    isSelected = email.id in selectedIds,
                    isSelectionMode = isSelectionMode,
                    showStar = selectable && !isTrashOrSpam && !isDrafts,
                    contextOnly = !selectable,
                    isSent = isSent,
                    isDrafts = isDrafts,
                    recipientFolderIds = recipientFolderIds,
                    nested = row is MailRow.Child,
                    imagePreview = imagePreviewCache[email.id],
                    onClick = remember { { stableOnEmailClick(latestEmail) } },
                    onLongClick = remember { { stableOnLongClick(latestEmail) } },
                    onStarClick = remember { { stableOnStarClick(latestEmail) } },
                    onFlagChosen = remember { { flag: MailFlag -> stableOnFlagChosen(latestEmail, flag) } }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailListItem(
    email: EmailEntity,
    mailRepo: com.dedovmosol.iwomail.data.repository.MailRepository,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    showStar: Boolean = true,
    contextOnly: Boolean = false,
    isSent: Boolean = false,
    isDrafts: Boolean = false,
    recipientFolderIds: Set<String> = emptySet(),
    imagePreview: com.dedovmosol.iwomail.ui.screens.emaillist.ListPreview? = null,
    /** Строка цепочки: число писем, сводный флажок, стрелка вместо флажка. */
    thread: com.dedovmosol.iwomail.domain.EmailThread? = null,
    expanded: Boolean = false,
    /** Письмо раскрытой цепочки — с отступом. */
    nested: Boolean = false,
    onClickLabel: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStarClick: () -> Unit,
    onFlagChosen: (MailFlag) -> Unit
) {
    val haptic = rememberAppHaptics()
    val longClickHandler = remember(onLongClick) {
        {
            haptic.confirm()
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
            if (contextOnly && isSelectionMode) {
                Modifier
            } else if (contextOnly) {
                Modifier.clickable(onClick = onClick)
            } else if (isSelectionMode) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier.combinedClickable(
                    onClickLabel = onClickLabel,
                    onClick = onClick,
                    onLongClick = longClickHandler
                )
            }
        ),
        color = backgroundColor
    ) {
        if (thread != null && expanded) {
            // Не дублировать latest: шапка только сворачивает, письма — строки Child, включая самое новое
            ExpandedThreadBar(
                count = thread.emails.size,
                unread = thread.unreadCount > 0,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected
            )
        } else {
        Row(
            modifier = Modifier.padding(start = if (nested) 16.dp + NESTED_INDENT else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Отправленные и черновики — «Кому»: отправитель в них вы сами (как в Outlook). У черновика из Outlook
            // отправителя нет вовсе (From появляется при отправке), у созданного приложением — имя аккаунта в приложении.
            val showsRecipient = isSent || isDrafts || email.folderId in recipientFolderIds
            val recipientName = remember(email.to, email.cc, email.bcc) {
                com.dedovmosol.iwomail.ui.screens.emaillist.firstRecipientName(email.to, email.cc, email.bcc)
            }
            val cachedSenderName = remember(email.from) {
                if (isDrafts) {
                    null // своё имя в черновике — не имя отправителя для кэша имён
                } else if (email.fromName.isBlank() || email.fromName.contains("@")) {
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

            val displayName = if (showsRecipient) {
                if (recipientName.isBlank()) Strings.noRecipients else "${Strings.toPrefix} $recipientName"
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

            // Аватар с цветом на основе имени (получателя для Отправленных и черновиков, отправителя для остальных)
            val avatarName = if (showsRecipient) {
                recipientName
            } else {
                cachedSenderName ?: cleanFromName ?: email.from
            }
            val avatarColor = getAvatarColor(avatarName)
            // Адрес для фото из адресной книги: получатель (Отправленные, черновики) или отправитель
            val avatarAddress = remember(email.from, email.to, email.cc, email.bcc, showsRecipient) {
                if (showsRecipient) {
                    val field = com.dedovmosol.iwomail.ui.screens.emaillist.firstRecipientField(email.to, email.cc, email.bcc)
                    field.split(',', ';').firstNotNullOfOrNull { part ->
                        com.dedovmosol.iwomail.util.extractEmailAddress(part.trim()).takeIf { it.contains('@') }
                    }.orEmpty()
                } else {
                    com.dedovmosol.iwomail.util.extractEmailAddress(email.from)
                }
            }

            // Первая буква/цифра для аватара
            val avatarInitial = avatarName
                .trim()
                .firstOrNull { it.isLetterOrDigit() }
                ?.uppercase()
                ?: "?"

            Box(
                modifier = Modifier
                    .size(44.dp)
                    // Как у строки: в режиме выбора — без долгого нажатия. Иначе после него обработчик аватара
                    // поглощал движения пальца, и протягивание, начатое на аватаре, обрывалось.
                    .then(when {
                        contextOnly -> Modifier
                        isSelectionMode -> Modifier.clickable(onClick = onClick)
                        else -> Modifier.combinedClickable(onClick = onClick, onLongClick = longClickHandler)
                    })
            ) {
                com.dedovmosol.iwomail.ui.components.SenderAvatar(
                    accountId = email.accountId,
                    address = avatarAddress,
                    initial = avatarInitial,
                    color = avatarColor,
                    modifier = Modifier.size(40.dp).align(Alignment.Center)
                )
                if (isSelectionMode && !contextOnly) {
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
                    // Имя и число писем цепочки сразу за ним; дата и значки — у правого края
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (email.read) FontWeight.Normal else FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (thread != null) ThreadCountBadge(count = thread.emails.size, unread = thread.unreadCount > 0)
                    }
                    // Флажок хотя бы у одного письма цепочки — только знак: отмечается отдельное письмо
                    if (thread?.anyFlagged == true) {
                        Icon(
                            AppIcons.Star,
                            contentDescription = Strings.starred,
                            modifier = Modifier.size(16.dp),
                            tint = com.dedovmosol.iwomail.ui.theme.AppColors.favorites
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    // Запись RSS-канала из Outlook (отправитель — название канала)
                    if (com.dedovmosol.iwomail.eas.RssItems.isRss(email.messageClass)) {
                        Icon(
                            AppIcons.RssFeed,
                            contentDescription = Strings.rssFeed,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
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

                if (contextOnly) ContextMailLabel()
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

                    // Миниатюра изображения (если есть скачанная картинка); картинок несколько — «+N»
                    if (imagePreview != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        com.dedovmosol.iwomail.ui.screens.emaillist.ListThumbnail(imagePreview)
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
                if (email.flagged && email.flagDueDate != null) {
                    com.dedovmosol.iwomail.ui.components.FlagDueText(email.flag)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (thread != null) {
                // Стрелка на месте флажка; нажимается вся строка (её действие — в onClickLabel)
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (showStar) {
                // Нажатие — отметить/снять; долгое нажатие — срок, напоминание, «выполнено»
                val flag = email.flag
                var flagMenuOpen by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        // 48 dp — минимальная зона нажатия (Material, доступность); круглая волна, как у IconButton
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .combinedClickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClick = {
                                haptic.tick()
                                onStarClick()
                            },
                            onLongClick = {
                                haptic.confirm()
                                flagMenuOpen = true
                            },
                            onLongClickLabel = Strings.flagRemind
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = com.dedovmosol.iwomail.ui.components.flagIcon(flag),
                        contentDescription = if (email.flagged) Strings.removeFromFavorites else Strings.addToFavorites,
                        tint = com.dedovmosol.iwomail.ui.components.flagTint(flag),
                        modifier = Modifier.size(24.dp)
                    )
                    com.dedovmosol.iwomail.ui.components.FlagMenu(
                        expanded = flagMenuOpen,
                        flag = flag,
                        onDismiss = { flagMenuOpen = false },
                        onChoose = onFlagChosen
                    )
                }
            }
        }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(
            start = when {
                thread != null && expanded -> 16.dp
                nested -> 68.dp + NESTED_INDENT
                else -> 68.dp
            }
        )
    )
}

/** Отступ писем раскрытой цепочки. */
private val NESTED_INDENT = 24.dp

/** Раскрытая цепочка: шапка без превью latest — иначе то же письмо дважды. Нажатие — на Surface снаружи. */
@Composable
private fun ExpandedThreadBar(
    count: Int,
    unread: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            DragSelectionIndicator(selected = isSelected, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        ThreadCountBadge(count = count, unread = unread, startPadding = 0.dp)
        Spacer(modifier = Modifier.width(8.dp))
        if (isSelectionMode) {
            // Нажатие выбирает всю цепочку, не сворачивает — не писать «Свернуть»
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Text(
                text = Strings.collapseThread,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = AppIcons.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** Число писем цепочки; есть непрочитанные — цветом темы. */
@Composable
private fun ThreadCountBadge(count: Int, unread: Boolean, startPadding: androidx.compose.ui.unit.Dp = 6.dp) {
    val description = "$count ${Strings.pluralEmails(count)}"
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (unread) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(start = startPadding)
            .semantics { contentDescription = description }
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (unread) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
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
        Row(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Текст выделяется и копируется (технические подробности нужны администратору)
            androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
            com.dedovmosol.iwomail.ui.components.CopyErrorButton(copyText = message, tint = MaterialTheme.colorScheme.onErrorContainer)
            // Без явного size у IconButton: минимальный тач-таргет 48dp (Material/WCAG)
            IconButton(onClick = onDismiss) {
                Icon(AppIcons.Close, Strings.close, modifier = Modifier.size(16.dp))
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

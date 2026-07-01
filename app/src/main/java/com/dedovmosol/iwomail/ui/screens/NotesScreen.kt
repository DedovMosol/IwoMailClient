package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.util.SafeToast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.dedovmosol.iwomail.ui.components.DragSelectionIndicator
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.components.rememberDebouncedState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dedovmosol.iwomail.data.database.NoteEntity
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

// Паттерн для URL (компилируется один раз)
private val urlPattern = Regex("https?://[\\w\\-.]+\\.[a-z]{2,}[\\w\\-._~:/?#\\[\\]@!%&'()*+,;=]*", RegexOption.IGNORE_CASE)
// Паттерн для email
private val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
// Паттерн для телефонов (российские и международные)
private val phonePattern = Regex("(?:\\+7|8)[\\s-]?\\(?\\d{3}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}|\\+?\\d{1,3}[\\s-]?\\(?\\d{2,4}\\)?[\\s-]?\\d{2,4}[\\s-]?\\d{2,4}")
private val PHONE_CLEAN_REGEX = Regex("[\\s()-]")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val deletionController = com.dedovmosol.iwomail.ui.components.LocalDeletionController.current
    val haptic = LocalHapticFeedback.current

    val viewModel: NotesViewModel = viewModel(
        factory = NotesViewModel.provideFactory(context.applicationContext as android.app.Application)
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Производные значения состояния (только чтение из VM).
    val accountId = uiState.accountId
    val notes = uiState.notes
    val deletedNotes = uiState.deletedNotes
    val deletedCount = uiState.deletedCount
    val selectedTab = uiState.selectedTab
    val selectedIds = uiState.selectedIds
    val isSelectionMode = selectedIds.isNotEmpty()
    val isSyncing = uiState.isSyncing
    val isCreating = uiState.isCreating
    val isInitialLoadDone = uiState.isInitialLoadDone

    // Локализованные строки для канала событий (VM эмитит семантические события без ресурсов).
    val notesSyncedText = Strings.notesSynced
    val noteMovedToTrashText = Strings.noteMovedToTrash
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN
    val noteCreatedText = Strings.noteCreated
    val noteUpdatedText = Strings.noteUpdated
    val noteRestoredText = Strings.noteRestored
    val notesRestoredText = Strings.notesRestored

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // === UI-состояние (навигация диалогов/просмотра, сортировка, фокус) — не относится к данным ===
    var selectedNoteId by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    val selectedNote = remember(selectedNoteId, notes, deletedNotes) {
        selectedNoteId?.let { id -> notes.find { it.id == id } ?: deletedNotes.find { it.id == id } }
    }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingNoteId by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    val editingNote = remember(editingNoteId, notes) {
        editingNoteId?.let { id -> notes.find { it.id == id } }
    }
    var showEmptyTrashConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteSelectedDialog by rememberSaveable { mutableStateOf(false) }
    var showDeletePermanentlyDialog by rememberSaveable { mutableStateOf(false) }

    // Сохранение фокуса поиска при повороте экрана
    val searchFocusRequester = remember { FocusRequester() }
    var isSearchFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isSearchFocused) {
        if (isSearchFocused) {
            kotlinx.coroutines.delay(100)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // Состояние списка для автоскролла
    val listState = rememberLazyListState()

    // Состояние сортировки (true = новые сверху, false = старые сверху)
    var sortDescending by rememberSaveable { mutableStateOf(true) }

    val debouncedSearchQuery by rememberDebouncedState(uiState.query)

    // Одноразовые события из VM → тосты/автоскролл (локализация в UI).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is NotesEvent.Synced -> SafeToast.short(context, "$notesSyncedText: ${event.count}")
                is NotesEvent.MovedToTrash ->
                    SafeToast.short(context, if (event.count == 1) noteMovedToTrashText else Strings.notesMovedToTrash(event.count, isRussian))
                NotesEvent.NoteCreated -> {
                    SafeToast.short(context, noteCreatedText)
                    showCreateDialog = false
                    editingNoteId = null
                }
                NotesEvent.NoteUpdated -> {
                    SafeToast.short(context, noteUpdatedText)
                    showCreateDialog = false
                    editingNoteId = null
                }
                NotesEvent.ScrollToTop -> {
                    delay(100)
                    listState.animateScrollToItem(0)
                }
                is NotesEvent.Error -> SafeToast.long(context, event.message)
            }
        }
    }

    // Фильтрация по поиску (учитываем выбранный таб) — чистая производная от состояния VM.
    val currentNotes = if (selectedTab == 0) notes else deletedNotes
    val filteredNotes = remember(currentNotes, debouncedSearchQuery, sortDescending) {
        val filtered = if (debouncedSearchQuery.isBlank()) {
            currentNotes
        } else {
            currentNotes.filter { note ->
                note.subject.contains(debouncedSearchQuery, ignoreCase = true) ||
                note.body.contains(debouncedSearchQuery, ignoreCase = true)
            }
        }
        if (sortDescending) {
            filtered.sortedByDescending { it.lastModified }
        } else {
            filtered.sortedBy { it.lastModified }
        }
    }

    // Диалог просмотра заметки
    selectedNote?.let { note ->
        val deletingOneNoteText = Strings.deletingNotes(1)

        NoteDetailDialog(
            note = note,
            onDismiss = { selectedNoteId = null },
            onEditClick = {
                editingNoteId = note.id
                selectedNoteId = null
                showCreateDialog = true
            },
            onDeleteClick = {
                selectedNoteId = null  // Закрываем диалог сразу

                if (note.isDeleted) {
                    // Окончательное удаление с прогрессом
                    com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)

                    deletionController.startDeletion(
                        emailIds = listOf(note.id),
                        message = deletingOneNoteText,
                        scope = scope,
                        isRestore = false
                    ) { _, onProgress ->
                        val result = viewModel.deleteNotePermanently(note)
                        onProgress(1, 1)
                        if (result is EasResult.Error) {
                            SafeToast.long(context, result.message)
                        }
                    }
                } else {
                    // Обычное удаление в корзину — тост через канал событий VM
                    viewModel.deleteNoteToTrash(note)
                }
            },
            onRestoreClick = {
                selectedNoteId = null  // Закрываем диалог

                // Восстанавливаем заметку с задержкой и возможностью отмены
                deletionController.startDeletion(
                    emailIds = listOf(note.id),
                    message = noteRestoredText,
                    scope = scope,
                    isRestore = true
                ) { _, onProgress ->
                    val result = viewModel.restoreNote(note)
                    onProgress(1, 1)
                    if (result is EasResult.Error) {
                        SafeToast.long(context, result.message)
                    }
                }
            }
        )
    }

    // Диалог подтверждения очистки корзины заметок
    if (showEmptyTrashConfirm) {
        val deletingMessage = Strings.deletingNotes(deletedNotes.size)
        val notesTrashEmptiedText = Strings.notesTrashEmptied

        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.emptyTrash) },
            text = { Text(Strings.emptyNotesTrashConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showEmptyTrashConfirm = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)

                        val noteIds = deletedNotes.map { it.id }
                        val serverIds = deletedNotes.map { it.serverId }
                        if (noteIds.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = noteIds,
                                message = deletingMessage,
                                scope = scope,
                                isRestore = false
                            ) { _, onProgress ->
                                when (val result = viewModel.emptyTrash(serverIds) { deleted, total -> onProgress(deleted, total) }) {
                                    is EasResult.Success -> SafeToast.short(context, notesTrashEmptiedText)
                                    is EasResult.Error -> SafeToast.long(context, result.message)
                                }
                            }
                        }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showEmptyTrashConfirm = false },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог подтверждения удаления выбранных заметок (в корзину)
    // Soft-delete: без прогресс-бара, просто удаляем
    if (showDeleteSelectedDialog) {
        val count = selectedIds.size
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.delete) },
            text = { Text(if (count == 1) Strings.deleteNoteConfirm else Strings.deleteNotesConfirm(count)) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showDeleteSelectedDialog = false
                        if (notes.any { it.id in selectedIds }) {
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        }
                        viewModel.deleteSelectedToTrash()
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeleteSelectedDialog = false },
                    text = Strings.no
                )
            }
        )
    }

    // Диалог подтверждения окончательного удаления выбранных заметок
    if (showDeletePermanentlyDialog) {
        val count = selectedIds.size
        val deletingPermanentlyMessage = Strings.deletingNotes(count)
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeletePermanentlyDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.deletePermanently) },
            text = { Text(if (count == 1) Strings.deleteNotePermanentlyConfirm else "${Strings.deletePermanently}: $count") },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showDeletePermanentlyDialog = false
                        val notesToDelete = deletedNotes.filter { it.id in selectedIds }
                        if (notesToDelete.isNotEmpty()) {
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)

                            val noteIds = notesToDelete.map { it.id }
                            viewModel.clearSelection()

                            deletionController.startDeletion(
                                emailIds = noteIds,
                                message = deletingPermanentlyMessage,
                                scope = scope,
                                isRestore = false
                            ) { _, onProgress ->
                                val result = viewModel.deleteNotesPermanently(notesToDelete) { deleted, total ->
                                    onProgress(deleted, total)
                                }
                                if (result is EasResult.Error) {
                                    SafeToast.long(context, result.message)
                                }
                            }
                        }
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

    // Диалог создания/редактирования заметки
    if (showCreateDialog) {
        CreateNoteDialog(
            note = editingNote,
            isCreating = isCreating,
            onDismiss = {
                showCreateDialog = false
                editingNoteId = null
            },
            onSave = { subject, body ->
                // Создание/обновление + защита от double-tap в VM; закрытие диалога
                // и автоскролл — по событиям NoteCreated/NoteUpdated/ScrollToTop.
                viewModel.saveNote(editingNote, subject, body)
            }
        )
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                val restoringMessage = Strings.restoringNotes(selectedIds.size)
                NotesSelectionTopBar(
                    selectedCount = selectedIds.size,
                    isDeletedTab = selectedTab == 1,
                    onClearSelection = { viewModel.clearSelection() },
                    onRestore = {
                        val notesToRestore = deletedNotes.filter { it.id in selectedIds }
                        if (notesToRestore.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = notesToRestore.map { it.id },
                                message = restoringMessage,
                                scope = scope,
                                isRestore = true
                            ) { _, onProgress ->
                                val result = viewModel.restoreNotes(notesToRestore) { restored, total ->
                                    onProgress(restored, total)
                                }
                                when (result) {
                                    is EasResult.Success ->
                                        SafeToast.short(context, if (notesToRestore.size > 1) notesRestoredText else noteRestoredText)
                                    is EasResult.Error -> SafeToast.long(context, result.message)
                                }
                                viewModel.clearSelection()
                            }
                        }
                    },
                    onDelete = {
                        if (selectedTab == 0) {
                            showDeleteSelectedDialog = true
                        } else {
                            showDeletePermanentlyDialog = true
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(Strings.notes, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                        }
                    },
                    actions = {
                        // Кнопка синхронизации (результат — через канал событий VM)
                        IconButton(
                            onClick = { viewModel.syncNotes() },
                            enabled = !isSyncing
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(AppIcons.Sync, Strings.syncNotes, tint = Color.White)
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
            // FAB только для активных заметок
            if (selectedTab == 0 && !isSelectionMode) {
                com.dedovmosol.iwomail.ui.theme.AnimatedFab(
                    onClick = {
                        editingNoteId = null
                        showCreateDialog = true
                    },
                    containerColor = LocalColorTheme.current.gradientStart
                ) {
                    Icon(AppIcons.Add, Strings.newNote, tint = Color.White)
                }
            } else if (deletedCount > 0 && !isSelectionMode) {
                com.dedovmosol.iwomail.ui.theme.AnimatedFab(
                    onClick = { showEmptyTrashConfirm = true },
                    containerColor = LocalColorTheme.current.gradientStart
                ) {
                    Icon(AppIcons.DeleteForever, Strings.emptyTrash, tint = Color.White)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Поле поиска (на всю ширину)
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { isSearchFocused = it.isFocused },
                placeholder = { Text(Strings.searchNotes) },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearQuery() }) {
                            Icon(AppIcons.Clear, Strings.clear)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Табы: Активные / Удалённые
            // Показываем только если есть удалённые заметки
            if (deletedNotes.isNotEmpty()) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { viewModel.selectTab(0) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Strings.notes)
                                if (notes.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Badge { Text("${notes.size}") }
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { viewModel.selectTab(1) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Strings.deleted)
                                if (deletedCount > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Badge { Text("$deletedCount") }
                                }
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Счётчик
            Text(
                text = "${Strings.total}: ${filteredNotes.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (filteredNotes.isEmpty()) {
                // Пустой список
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!isInitialLoadDone || isSyncing) {
                        CircularProgressIndicator()
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                AppIcons.StickyNote,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = Strings.noNotes,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // Список заметок
                // Drag selection
                val noteKeys = remember(filteredNotes) { filteredNotes.map { it.id } }
                val dragModifier = com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier(
                    listState = listState,
                    itemKeys = noteKeys,
                    selectedIds = selectedIds,
                    onSelectionChange = { newIds -> viewModel.setSelection(newIds) }
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = dragModifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    if (isSelectionMode) {
                        item {
                            val allSelected = filteredNotes.isNotEmpty() && selectedIds.size == filteredNotes.size
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setSelection(
                                            if (allSelected) emptySet() else filteredNotes.map { it.id }.toSet()
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = {
                                        viewModel.setSelection(
                                            if (allSelected) emptySet() else filteredNotes.map { it.id }.toSet()
                                        )
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(Strings.selectAll)
                            }
                            HorizontalDivider()
                        }
                    }
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            isSelected = note.id in selectedIds,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.toggleSelection(note.id)
                                } else {
                                    selectedNoteId = note.id
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.addToSelection(note.id)
                                }
                            }
                        )
                    }
                }
                    LazyColumnScrollbar(listState)
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val formattedDate = remember(note.lastModified) { dateFormat.format(Date(note.lastModified)) }
    val categoryChips = remember(note.categories) {
        if (note.categories.isNotBlank()) note.categories.split(",").take(2).map { it.trim() }.filter { it.isNotBlank() }
        else emptyList()
    }
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelectionMode) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (isSelectionMode) {
                DragSelectionIndicator(
                    selected = isSelected,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Заголовок
                Text(
                    text = note.subject.ifBlank { Strings.noTitle },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Превью текста
                if (note.body.isNotBlank()) {
                    Text(
                        text = note.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Дата и категории
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )

                    if (categoryChips.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            categoryChips.forEach { trimmed ->
                                AssistChip(
                                    onClick = { },
                                    label = { Text(trimmed, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(24.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        labelColor = MaterialTheme.colorScheme.primary
                                    ),
                                    border = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSelectionTopBar(
    selectedCount: Int,
    isDeletedTab: Boolean,
    onClearSelection: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount", color = Color.White) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(AppIcons.ArrowBack, Strings.cancelSelection, tint = Color.White)
            }
        },
        actions = {
            if (isDeletedTab) {
                IconButton(onClick = onRestore) {
                    Icon(AppIcons.Restore, Strings.restore, tint = Color.White)
                }
                IconButton(onClick = onDelete) {
                    Icon(AppIcons.DeleteForever, Strings.deletePermanently, tint = Color.White)
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(AppIcons.Delete, Strings.delete, tint = Color.White)
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

@Composable
private fun NoteDetailDialog(
    note: NoteEntity,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onRestoreClick: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val formattedDate = remember(note.lastModified) { dateFormat.format(Date(note.lastModified)) }
    val categoryChips = remember(note.categories) {
        if (note.categories.isNotBlank()) note.categories.split(",").map { it.trim() }.filter { it.isNotBlank() }
        else emptyList()
    }

    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = note.subject.ifBlank { Strings.noSubject },
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (categoryChips.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        categoryChips.forEach { trimmed ->
                            if (trimmed.isNotBlank()) {
                                AssistChip(
                                    onClick = { },
                                    label = { Text(trimmed, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(24.dp),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        labelColor = MaterialTheme.colorScheme.primary
                                    ),
                                    border = null
                                )
                            }
                        }
                    }
                }

                // Текст заметки с кликабельными ссылками
                if (note.body.isNotBlank()) {
                    // Очищаем body от дубликатов
                    val cleanBody = remember(note.body) {
                        val lines = note.body.lines()
                        var prev = ""
                        lines.mapNotNull { line ->
                            val trimmed = line.trim()
                            if (trimmed.isBlank()) null
                            else if (trimmed.lowercase() == prev) null
                            else { prev = trimmed.lowercase(); trimmed }
                        }.joinToString("\n")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        ClickableNoteText(
                            text = cleanBody,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (note.isDeleted) {
                // Для удалённых заметок - восстановить слева, удалить справа
                OutlinedButton(
                    onClick = onDeleteClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                    ),
                    border = BorderStroke(1.dp, com.dedovmosol.iwomail.ui.theme.AppColors.delete)
                ) {
                    Icon(
                        AppIcons.DeleteForever,
                        contentDescription = Strings.deletePermanently,
                        modifier = Modifier.size(20.dp),
                        tint = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                    )
                }
            } else {
                // Для обычных заметок - удалить справа
                OutlinedButton(
                    onClick = onDeleteClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                    ),
                    border = BorderStroke(1.dp, com.dedovmosol.iwomail.ui.theme.AppColors.delete)
                ) {
                    Icon(
                        AppIcons.Delete,
                        contentDescription = Strings.delete,
                        modifier = Modifier.size(20.dp),
                        tint = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                    )
                }
            }
        },
        dismissButton = {
            if (note.isDeleted) {
                // Для удалённых заметок - восстановить слева
                OutlinedButton(
                    onClick = onRestoreClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = LocalColorTheme.current.gradientStart
                    )
                ) {
                    Icon(
                        AppIcons.Restore,
                        contentDescription = Strings.restore,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // Для обычных заметок - редактировать слева (цвет из темы)
                OutlinedButton(
                    onClick = onEditClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = LocalColorTheme.current.gradientStart
                    )
                ) {
                    Icon(
                        AppIcons.Edit,
                        contentDescription = Strings.edit,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    )
}

/**
 * Текст с кликабельными ссылками
 */
@Composable
private fun ClickableNoteText(
    text: String,
    style: androidx.compose.ui.text.TextStyle
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val couldNotOpenLinkText = Strings.couldNotOpenLink

    // Тип ссылки
    data class Part(val content: String, val isLink: Boolean, val url: String = "")

    // Функция для поиска всех ссылок в строке (URL, email, телефон)
    fun findAllLinks(line: String): List<Part> {
        data class LinkMatch(val range: IntRange, val text: String, val url: String)
        val allMatches = mutableListOf<LinkMatch>()

        // URL
        urlPattern.findAll(line).forEach { match ->
            allMatches.add(LinkMatch(match.range, match.value, match.value))
        }

        // Email
        emailPattern.findAll(line).forEach { match ->
            // Проверяем что не пересекается с URL
            val overlaps = allMatches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                allMatches.add(LinkMatch(match.range, match.value, "mailto:${match.value}"))
            }
        }

        // Телефон
        phonePattern.findAll(line).forEach { match ->
            // Проверяем что не пересекается с другими ссылками
            val overlaps = allMatches.any { it.range.first <= match.range.last && it.range.last >= match.range.first }
            if (!overlaps) {
                val cleanPhone = match.value.replace(PHONE_CLEAN_REGEX, "")
                allMatches.add(LinkMatch(match.range, match.value, "tel:$cleanPhone"))
            }
        }

        // Сортируем по позиции
        allMatches.sortBy { it.range.first }

        // Собираем части
        val parts = mutableListOf<Part>()
        var lastIdx = 0

        allMatches.forEach { match ->
            if (match.range.first > lastIdx) {
                parts.add(Part(line.substring(lastIdx, match.range.first), false))
            }
            parts.add(Part(match.text, true, match.url))
            lastIdx = match.range.last + 1
        }

        if (lastIdx < line.length) {
            parts.add(Part(line.substring(lastIdx), false))
        }

        if (parts.isEmpty()) {
            parts.add(Part(line, false))
        }

        return parts
    }

    val parsedLines = remember(text) {
        text.split("\n").map { line ->
            if (line.isBlank()) null else findAllLinks(line)
        }
    }

    Column {
        parsedLines.forEachIndexed { lineIndex, lineParts ->
            if (lineParts == null) {
                Spacer(modifier = Modifier.height(8.dp))
            } else {

                // Отображаем строку
                Row(modifier = Modifier.fillMaxWidth()) {
                    lineParts.forEach { part ->
                        if (part.isLink) {
                            Text(
                                text = part.content,
                                style = style.copy(
                                    color = primaryColor,
                                    textDecoration = TextDecoration.Underline
                                ),
                                modifier = Modifier.clickable {
                                    try {
                                        uriHandler.openUri(part.url)
                                    } catch (e: Exception) {
                                        SafeToast.short(context, couldNotOpenLinkText)
                                    }
                                }
                            )
                        } else {
                            Text(
                                text = part.content,
                                style = style,
                                color = onSurfaceColor
                            )
                        }
                    }
                }
            }

            // Добавляем отступ между строками (кроме последней)
            if (lineIndex < parsedLines.size - 1 && parsedLines[lineIndex + 1] != null) {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}


/**
 * Диалог создания/редактирования заметки
 */
@Composable
private fun CreateNoteDialog(
    note: NoteEntity?,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onSave: (subject: String, body: String) -> Unit
) {
    val isEditing = note != null

    var subject by rememberSaveable { mutableStateOf(note?.subject ?: "") }
    var body by rememberSaveable { mutableStateOf(note?.body ?: "") }

    val isValid = subject.isNotBlank()

    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        scrollable = false,
        title = { Text(if (isEditing) Strings.editNote else Strings.newNote) },
        text = {
            val noteScrollState = rememberScrollState()
            Box {
                Column(
                    modifier = Modifier.verticalScroll(noteScrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Заголовок
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text(Strings.noteTitle) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = subject.isBlank()
                    )

                    // Текст заметки
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text(Strings.noteBody) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        maxLines = 10
                    )
                }
                ScrollColumnScrollbar(noteScrollState)
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = {
                    if (isValid) {
                        onSave(subject, body)
                    }
                },
                enabled = isValid && !isCreating,
                isLoading = isCreating,
                text = Strings.save
            )
        },
        dismissButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.cancel,
                enabled = !isCreating
            )
        }
    )
}

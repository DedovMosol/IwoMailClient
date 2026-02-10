package com.dedovmosol.iwomail.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.NoteEntity
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

// Паттерн для URL (компилируется один раз)
private val urlPattern = Regex("https?://[\\w\\-.]+\\.[a-z]{2,}[\\w\\-._~:/?#\\[\\]@!%&'()*+,;=]*", RegexOption.IGNORE_CASE)
// Паттерн для email
private val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
// Паттерн для телефонов (российские и международные)
private val phonePattern = Regex("(?:\\+7|8)[\\s-]?\\(?\\d{3}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}|\\+?\\d{1,3}[\\s-]?\\(?\\d{2,4}\\)?[\\s-]?\\d{2,4}[\\s-]?\\d{2,4}")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noteRepo = remember { RepositoryProvider.getNoteRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val deletionController = com.dedovmosol.iwomail.ui.components.LocalDeletionController.current
    
    // Отдельный scope для синхронизации, чтобы не отменялась при навигации
    val syncScope = com.dedovmosol.iwomail.ui.components.rememberSyncScope()
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L
    
    val notes by remember(accountId) { noteRepo.getNotes(accountId) }.collectAsState(initial = emptyList())
    val deletedNotes by remember(accountId) { noteRepo.getDeletedNotes(accountId) }.collectAsState(initial = emptyList())
    val deletedCount by remember(accountId) { noteRepo.getDeletedNotesCount(accountId) }.collectAsState(initial = 0)
    val deletingNotesText = Strings.deletingNotes(deletedNotes.size)
    val notesTrashEmptiedText = Strings.notesTrashEmptied
    val noteDeletedText = Strings.noteDeleted
    val notesDeletedText = Strings.notesDeleted
    val noteRestoredText = Strings.noteRestored
    val notesRestoredText = Strings.notesRestored
    val deletedPermanentlyText = Strings.deletedPermanently
    
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    
    // Таб: 0 = Активные, 1 = Удалённые
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showDeletedTab by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    
 var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    
    // Debounce поиска для оптимизации
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedSearchQuery = searchQuery
    }
    var isSyncing by rememberSaveable { mutableStateOf(false) }
    // КРИТИЧНО: rememberSaveable чтобы при повороте экрана НЕ запускалась повторная синхронизация
    var dataLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notes) {
        if (notes.isNotEmpty()) dataLoaded = true
    }
    
    // Автоматическая синхронизация при первом открытии если нет данных
    LaunchedEffect(accountId) {
        if (accountId > 0 && !dataLoaded) {
            delay(500)
            if (notes.isEmpty() && !isSyncing) {
                dataLoaded = true
                isSyncing = true
                syncScope.launch {
                    withContext(Dispatchers.IO) {
                        noteRepo.syncNotes(accountId)
                    }
                    isSyncing = false
                }
            }
        }
    }
    
    // Синхронизация при переключении на вкладку "Удалённые"
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && accountId > 0 && !isSyncing) {
            isSyncing = true
            syncScope.launch {
                withContext(Dispatchers.IO) {
                    noteRepo.syncNotes(accountId, skipRecentDeleteCheck = true)
                }
                isSyncing = false
            }
        }
    }
    var selectedNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var showEmptyTrashConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteSelectedDialog by rememberSaveable { mutableStateOf(false) }
    var showDeletePermanentlyDialog by rememberSaveable { mutableStateOf(false) }
    
    // Состояние списка для автоскролла
    val listState = rememberLazyListState()
    
    // Состояние сортировки (true = новые сверху, false = старые сверху)
    var sortDescending by rememberSaveable { mutableStateOf(true) }

    // Фильтрация по поиску (учитываем выбранный таб)
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
        val noteDeletedText = Strings.noteDeleted
        val noteDeletedPermanentlyText = Strings.deletedPermanently
        val noteRestoredText = Strings.noteRestored
        val undoText = Strings.undo
        val deletingOneNoteText = Strings.deletingNotes(1)
        
        NoteDetailDialog(
            note = note,
            onDismiss = { selectedNote = null },
            onEditClick = {
                editingNote = note
                selectedNote = null
                showCreateDialog = true
            },
            onDeleteClick = {
                selectedNote = null  // Закрываем диалог сразу
                
                if (note.isDeleted) {
                    // Окончательное удаление с прогрессом
                    com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                    
                    deletionController.startDeletion(
                        emailIds = listOf(note.id),
                        message = deletingOneNoteText,
                        scope = scope,
                        isRestore = false
                    ) { ids, onProgress ->
                        val result = withContext(Dispatchers.IO) {
                            noteRepo.deleteNotePermanently(note)
                        }
                        onProgress(1, 1)
                        when (result) {
                            is EasResult.Error -> {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                            else -> {}
                        }
                    }
                } else {
                    // Обычное удаление в корзину (без прогресса)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            noteRepo.deleteNote(note)
                        }
                        when (result) {
                            is EasResult.Success -> {
                                Toast.makeText(context, noteDeletedText, Toast.LENGTH_SHORT).show()
                                showDeletedTab = true
                                // Синхронизация уже происходит внутри deleteNote()
                            }
                            is EasResult.Error -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
            onRestoreClick = {
                selectedNote = null  // Закрываем диалог
                
                // Восстанавливаем заметку с задержкой и возможностью отмены
                deletionController.startDeletion(
                    emailIds = listOf(note.id),
                    message = noteRestoredText,
                    scope = scope,
                    isRestore = true
                ) { ids, onProgress ->
                    val result = withContext(Dispatchers.IO) {
                        noteRepo.restoreNote(note)
                    }
                    onProgress(1, 1)
                    when (result) {
                        is EasResult.Success -> {
                            // Toast уже показан через message
                        }
                        is EasResult.Error -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        )
    }
    
    // Управляем видимостью вкладки "Удалённые"
    // Если удалённых заметок нет и мы на вкладке удалённых - возвращаемся на активные
    LaunchedEffect(deletedNotes.size) {
        if (deletedNotes.isEmpty() && selectedTab == 1) {
            selectedTab = 0
        }
    }

    LaunchedEffect(selectedTab) {
        selectedIds = emptySet()
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
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        showEmptyTrashConfirm = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        
                        val noteIds = deletedNotes.map { it.id }
                        if (noteIds.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = noteIds,
                                message = deletingMessage,
                                scope = scope,
                                isRestore = false
                            ) { ids, onProgress ->
                                // Удаление с реальным прогрессом
                                val serverIds = deletedNotes.map { it.serverId }
                                val result = withContext(Dispatchers.IO) {
                                    noteRepo.emptyNotesTrashWithProgress(accountId, serverIds) { deleted, total ->
                                        onProgress(deleted, total)
                                    }
                                }
                                when (result) {
                                    is EasResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, notesTrashEmptiedText, Toast.LENGTH_SHORT).show()
                                            showDeletedTab = false
                                        }
                                    }
                                    is EasResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    text = Strings.emptyTrash
                )
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirm = false }) {
                    Text(Strings.cancel)
                }
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
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        showDeleteSelectedDialog = false
                        val notesToDelete = notes.filter { it.id in selectedIds }
                        if (notesToDelete.isNotEmpty()) {
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    noteRepo.deleteNotes(notesToDelete)
                                }
                                when (result) {
                                    is EasResult.Success -> {
                                        val msg = if (notesToDelete.size > 1) notesDeletedText else noteDeletedText
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        showDeletedTab = true
                                    }
                                    is EasResult.Error -> {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                        selectedIds = emptySet()
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text(Strings.cancel) }
            }
        )
    }

    // Диалог подтверждения окончательного удаления выбранных заметок
    if (showDeletePermanentlyDialog) {
        val count = selectedIds.size
        val deletingPermanentlyMessage = Strings.deletingNotes(count)
        val undoText = Strings.undo
        val notesRestoredText = Strings.notesRestored
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeletePermanentlyDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.deletePermanently) },
            text = { Text(if (count == 1) Strings.deleteNotePermanentlyConfirm else "${Strings.deletePermanently}: $count") },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        showDeletePermanentlyDialog = false
                        val notesToDelete = deletedNotes.filter { it.id in selectedIds }
                        if (notesToDelete.isNotEmpty()) {
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                            
                            val noteIds = notesToDelete.map { it.id }
                            selectedIds = emptySet()
                            
                            deletionController.startDeletion(
                                emailIds = noteIds,
                                message = deletingPermanentlyMessage,
                                scope = scope,
                                isRestore = false
                            ) { ids, onProgress ->
                                val result = withContext(Dispatchers.IO) {
                                    noteRepo.deleteNotesPermanentlyWithProgress(notesToDelete) { deleted, total ->
                                        onProgress(deleted, total)
                                    }
                                }
                                when (result) {
                                    is EasResult.Error -> {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeletePermanentlyDialog = false }) { Text(Strings.cancel) }
            }
        )
    }
    
    // Диалог создания/редактирования заметки
    if (showCreateDialog) {
        val noteUpdatedText = Strings.noteUpdated
        val noteCreatedText = Strings.noteCreated
        val isEditing = editingNote != null
        CreateNoteDialog(
            note = editingNote,
            isCreating = isCreating,
            onDismiss = {
                showCreateDialog = false
                editingNote = null
            },
            onSave = { subject, body ->
                // Защита от double-tap: если уже создаём — игнорируем
                if (isCreating) return@CreateNoteDialog
                isCreating = true
                scope.launch {
                    // Захватываем editingNote в локальную переменную для безопасного доступа
                    val noteToEdit = editingNote
                    val result = if (noteToEdit != null) {
                        withContext(Dispatchers.IO) {
                            noteRepo.updateNote(noteToEdit, subject, body)
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            noteRepo.createNote(accountId, subject, body)
                        }
                    }
                    isCreating = false
                    when (result) {
                        is EasResult.Success -> {
                            Toast.makeText(
                                context,
                                if (isEditing) noteUpdatedText else noteCreatedText,
                                Toast.LENGTH_SHORT
                            ).show()
                            showCreateDialog = false
                            editingNote = null
                            // Автоскролл вверх после создания (с задержкой для обновления списка)
                            if (!isEditing) {
                                kotlinx.coroutines.delay(100)
                                listState.animateScrollToItem(0)
                            }
                        }
                        is EasResult.Error -> {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
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
                    onClearSelection = { selectedIds = emptySet() },
                    onRestore = {
                        val notesToRestore = deletedNotes.filter { it.id in selectedIds }
                        if (notesToRestore.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = notesToRestore.map { it.id },
                                message = restoringMessage,
                                scope = scope,
                                isRestore = true
                            ) { _, onProgress ->
                                val result = withContext(Dispatchers.IO) {
                                    noteRepo.restoreNotesWithProgress(notesToRestore) { restored, total ->
                                        onProgress(restored, total)
                                    }
                                }
                                when (result) {
                                    is EasResult.Success -> {
                                        val msg = if (notesToRestore.size > 1) notesRestoredText else noteRestoredText
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                    is EasResult.Error -> {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                                selectedIds = emptySet()
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
                        // Кнопка синхронизации
                        val notesSyncedText = Strings.notesSynced
                        IconButton(
                            onClick = {
                                syncScope.launch {
                                    isSyncing = true
                                    val result = withContext(Dispatchers.IO) {
                                        noteRepo.syncNotes(accountId)
                                    }
                                    isSyncing = false
                                    when (result) {
                                        is EasResult.Success -> {
                                            Toast.makeText(
                                                context,
                                                "$notesSyncedText: ${result.data}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        is EasResult.Error -> {
                                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
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
                        editingNote = null
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
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(Strings.searchNotes) },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
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
                        onClick = { selectedTab = 0 },
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
                        onClick = { selectedTab = 1 },
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
            } else {
                // Список заметок
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
                                        selectedIds = if (allSelected) {
                                            emptySet()
                                        } else {
                                            filteredNotes.map { it.id }.toSet()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = {
                                        selectedIds = if (allSelected) {
                                            emptySet()
                                        } else {
                                            filteredNotes.map { it.id }.toSet()
                                        }
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
                                    selectedIds = if (note.id in selectedIds) selectedIds - note.id else selectedIds + note.id
                                } else {
                                    selectedNote = note
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    selectedIds = selectedIds + note.id
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
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "noteBg"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
                Spacer(modifier = Modifier.width(8.dp))
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
                        text = dateFormat.format(Date(note.lastModified)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    
                    if (note.categories.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            note.categories.split(",").take(2).forEach { category ->
                                val trimmed = category.trim()
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
                // Дата
                Text(
                    text = dateFormat.format(Date(note.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Категории
                if (note.categories.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        note.categories.split(",").forEach { category ->
                            val trimmed = category.trim()
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
                        val seen = mutableSetOf<String>()
                        lines.mapNotNull { line ->
                            val trimmed = line.trim()
                            if (trimmed.isBlank()) null
                            else if (seen.add(trimmed.lowercase())) trimmed
                            else null
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
                        contentColor = Color(0xFFF44336)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFF44336))
                ) {
                    Icon(
                        AppIcons.DeleteForever,
                        contentDescription = Strings.deletePermanently,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFF44336)
                    )
                }
            } else {
                // Для обычных заметок - удалить справа
                OutlinedButton(
                    onClick = onDeleteClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFF44336)
                    ),
                    border = BorderStroke(1.dp, Color(0xFFF44336))
                ) {
                    Icon(
                        AppIcons.Delete,
                        contentDescription = Strings.delete,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFF44336)
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
                val cleanPhone = match.value.replace(Regex("[\\s()-]"), "")
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
    
    Column {
        val lines = text.split("\n")
        lines.forEachIndexed { lineIndex, line ->
            if (line.isBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                val lineParts = findAllLinks(line)
                
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
                                        Toast.makeText(context, couldNotOpenLinkText, Toast.LENGTH_SHORT).show()
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
            if (lineIndex < lines.size - 1 && lines[lineIndex + 1].isNotBlank()) {
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
            TextButton(
                onClick = {
                    if (isValid) {
                        onSave(subject, body)
                    }
                },
                enabled = isValid && !isCreating
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(Strings.save)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating
            ) {
                Text(Strings.cancel)
            }
        }
    )
}

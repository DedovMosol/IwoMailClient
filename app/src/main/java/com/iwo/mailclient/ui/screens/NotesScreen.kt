package com.iwo.mailclient.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.iwo.mailclient.data.database.NoteEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.NoteRepository
import com.iwo.mailclient.data.repository.RepositoryProvider
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.theme.AppIcons
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    
    // Отдельный scope для синхронизации, чтобы не отменялась при навигации
    val syncScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) {
        onDispose { syncScope.cancel() }
    }
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L
    
    val notes by remember(accountId) { noteRepo.getNotes(accountId) }.collectAsState(initial = emptyList())
    
 var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    
    // Debounce поиска для оптимизации
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedSearchQuery = searchQuery
    }
    var isSyncing by rememberSaveable { mutableStateOf(false) }
    
    // Автоматическая синхронизация при первом открытии если нет данных
    LaunchedEffect(accountId, notes.isEmpty()) {
        if (accountId > 0 && notes.isEmpty() && !isSyncing) {
            isSyncing = true
            syncScope.launch {
                withContext(Dispatchers.IO) {
                    noteRepo.syncNotes(accountId)
                }
                isSyncing = false
            }
        }
    }
    var selectedNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    
    // Состояние списка для автоскролла
    val listState = rememberLazyListState()
    
    // Состояние сортировки (true = новые сверху, false = старые сверху)
    var sortDescending by rememberSaveable { mutableStateOf(true) }

    // Фильтрация по поиску
    val filteredNotes = remember(notes, debouncedSearchQuery, sortDescending) {
        val filtered = if (debouncedSearchQuery.isBlank()) {
            notes
        } else {
            notes.filter { note ->
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
        NoteDetailDialog(
            note = note,
            onDismiss = { selectedNote = null },
            onEditClick = {
                editingNote = note
                selectedNote = null
                showCreateDialog = true
            },
            onDeleteClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        noteRepo.deleteNote(note)
                    }
                    when (result) {
                        is EasResult.Success -> {
                            Toast.makeText(context, noteDeletedText, Toast.LENGTH_SHORT).show()
                        }
                        is EasResult.Error -> {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                    selectedNote = null
                }
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
                scope.launch {
                    isCreating = true
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
        topBar = {
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
        },
        floatingActionButton = {
            com.iwo.mailclient.ui.theme.AnimatedFab(
                onClick = {
                    editingNote = null
                    showCreateDialog = true
                },
                containerColor = LocalColorTheme.current.gradientStart
            ) {
                Icon(AppIcons.Add, Strings.newNote, tint = Color.White)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Поле поиска с кнопкой сортировки
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
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
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Кнопка сортировки
                IconButton(onClick = { 
                    sortDescending = !sortDescending
                    scope.launch { listState.animateScrollToItem(0) }
                }) {
                    Icon(
                        if (sortDescending) AppIcons.KeyboardArrowDown else AppIcons.KeyboardArrowUp,
                        contentDescription = if (sortDescending) Strings.sortNewestFirst else Strings.sortOldestFirst,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { selectedNote = note }
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun NoteCard(
    note: NoteEntity,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
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
                    text = note.body.take(100).replace("\n", " "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
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
                    Text(
                        text = note.categories.split(",").first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteDetailDialog(
    note: NoteEntity,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // Диалог подтверждения удаления
    if (showDeleteConfirm) {
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(Strings.deleteNote) },
            text = { Text(Strings.deleteNoteConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(Strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    com.iwo.mailclient.ui.theme.ScaledAlertDialog(
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
                    Text(
                        text = note.categories,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Текст заметки с кликабельными ссылками
                if (note.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        ClickableNoteText(
                            text = note.body,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Кнопка редактирования
            TextButton(onClick = onEditClick) {
                Icon(AppIcons.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(Strings.edit)
            }
        },
        dismissButton = {
            // Кнопка удаления
            TextButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(AppIcons.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(Strings.delete)
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
    
    com.iwo.mailclient.ui.theme.ScaledAlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text(if (isEditing) Strings.editNote else Strings.newNote) },
        text = {
            Column(
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

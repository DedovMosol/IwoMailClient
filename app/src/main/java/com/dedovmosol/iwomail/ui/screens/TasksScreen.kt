package com.dedovmosol.iwomail.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.data.database.TaskImportance
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.TaskRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.ContactPickerDialog
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

// Кэшированные SimpleDateFormat для утилитных функций
private val TASK_PARSE_DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
private val TASK_PARSE_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

enum class TaskFilter {
    ALL, TODAY, ACTIVE, COMPLETED, HIGH_PRIORITY, OVERDUE, DELETED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBackClick: () -> Unit,
    initialFilter: TaskFilter = TaskFilter.ALL
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val taskRepo = remember { RepositoryProvider.getTaskRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val deletionController = com.dedovmosol.iwomail.ui.components.LocalDeletionController.current
    
    // Отдельный scope для синхронизации, чтобы не отменялась при навигации
    val syncScope = com.dedovmosol.iwomail.ui.components.rememberSyncScope()
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L
    
    val allTasks by remember(accountId) { taskRepo.getTasks(accountId) }.collectAsState(initial = emptyList())
    val deletedTasks by remember(accountId) { taskRepo.getDeletedTasks(accountId) }.collectAsState(initial = emptyList())
    
    // Флаг: данные из Room загружены (Flow эмитнул хотя бы раз)
    // КРИТИЧНО: rememberSaveable чтобы при повороте экрана НЕ запускалась повторная синхронизация
    var dataLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(allTasks) {
        if (allTasks.isNotEmpty()) dataLoaded = true
    }
    
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    
    // Debounce поиска для оптимизации
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedSearchQuery = searchQuery
    }
    var isSyncing by rememberSaveable { mutableStateOf(false) }
    
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var currentFilter by rememberSaveable { mutableStateOf(initialFilter) }
    var showEmptyTrashDialog by rememberSaveable { mutableStateOf(false) }
    
    // Множественный выбор для активных задач
    var selectedTaskIds by remember { mutableStateOf(setOf<String>()) }
    // Множественный выбор для удалённых задач
    var selectedDeletedIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedTaskIds.isNotEmpty() || selectedDeletedIds.isNotEmpty()
    val isDeletedSelectionMode = currentFilter == TaskFilter.DELETED && selectedDeletedIds.isNotEmpty()
    val isActiveSelectionMode = currentFilter != TaskFilter.DELETED && selectedTaskIds.isNotEmpty()
    
    // Диалог подтверждения удаления
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmCount by rememberSaveable { mutableStateOf(0) }
    var deleteConfirmIsPermanent by rememberSaveable { mutableStateOf(false) }
    
    // Автоматическая синхронизация при первом открытии если нет данных
    // Ждём загрузку из Room (небольшая задержка) перед тем как решить что данных нет
    // КРИТИЧНО: проверяем !dataLoaded чтобы при повороте экрана НЕ запускать повторную синхронизацию
    LaunchedEffect(accountId) {
        if (accountId > 0 && !dataLoaded) {
            // Даём Room время эмитнуть кэшированные данные
            kotlinx.coroutines.delay(500)
            if (allTasks.isEmpty() && !isSyncing) {
                dataLoaded = true // Данных реально нет — помечаем как загружено
                isSyncing = true
                syncScope.launch {
                    withContext(Dispatchers.IO) {
                        taskRepo.syncTasks(accountId)
                    }
                    isSyncing = false
                }
            }
        }
    }
    
    // Синхронизация при переключении на фильтр "Удалённые"
    LaunchedEffect(currentFilter) {
        if (currentFilter == TaskFilter.DELETED && accountId > 0 && !isSyncing) {
            isSyncing = true
            syncScope.launch {
                withContext(Dispatchers.IO) {
                    taskRepo.syncTasks(accountId)
                }
                isSyncing = false
            }
        }
        // Сбрасываем выбор при смене фильтра
        selectedTaskIds = emptySet()
        selectedDeletedIds = emptySet()
    }
    
    val listState = rememberLazyListState()
    
    // Состояние сортировки (true = новые сверху, false = старые сверху)
    var sortDescending by rememberSaveable { mutableStateOf(true) }

    // Фильтрация задач
    val filteredTasks = remember(allTasks, deletedTasks, debouncedSearchQuery, currentFilter, sortDescending) {
        // Выбираем базовый набор задач в зависимости от фильтра
        val baseTasks = when (currentFilter) {
            TaskFilter.ALL -> allTasks + deletedTasks
            TaskFilter.DELETED -> deletedTasks
            else -> allTasks
        }
        
        // Применяем поиск
        val searchFiltered = if (debouncedSearchQuery.isBlank()) {
            baseTasks
        } else {
            baseTasks.filter { task ->
                task.subject.contains(debouncedSearchQuery, ignoreCase = true) ||
                task.body.contains(debouncedSearchQuery, ignoreCase = true)
            }
        }
        
        // Применяем фильтр
        val filtered = when (currentFilter) {
            TaskFilter.ALL, TaskFilter.DELETED -> searchFiltered
            TaskFilter.TODAY -> {
                // Соответствует логике getTodayTasksCount в TaskDao:
                // задачи с dueDate до конца сегодняшнего дня, не завершённые (включая просроченные)
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                cal.set(java.util.Calendar.MINUTE, 59)
                cal.set(java.util.Calendar.SECOND, 59)
                cal.set(java.util.Calendar.MILLISECOND, 999)
                val endOfDay = cal.timeInMillis
                searchFiltered.filter { task ->
                    task.dueDate > 0 && task.dueDate <= endOfDay && !task.complete
                }
            }
            TaskFilter.ACTIVE -> searchFiltered.filter { !it.complete }
            TaskFilter.COMPLETED -> searchFiltered.filter { it.complete }
            TaskFilter.HIGH_PRIORITY -> searchFiltered.filter { it.importance == TaskImportance.HIGH.value && !it.complete }
            TaskFilter.OVERDUE -> searchFiltered.filter { 
                it.dueDate > 0 && it.dueDate < System.currentTimeMillis() && !it.complete 
            }
        }
        
        // Сортировка
        if (sortDescending) {
            filtered.sortedByDescending { if (it.dueDate > 0) it.dueDate else it.startDate }
        } else {
            filtered.sortedBy { if (it.dueDate > 0) it.dueDate else it.startDate }
        }
    }
    
    // Строки для диалогов (вынесены из @Composable контекста)
    val taskDeletedText = Strings.taskDeleted
    val taskRestoredText = Strings.taskRestored
    val taskDeletedPermanentlyText = Strings.taskDeletedPermanently
    val taskCompletedText = Strings.taskCompleted
    val taskNotCompletedText = Strings.taskNotCompleted
    val deletingOneTaskText = Strings.deletingTasks(1)
    
    // Диалог просмотра/редактирования задачи
    selectedTask?.let { task ->
        
        if (task.isDeleted) {
            // Диалог для удалённой задачи — восстановить или удалить навсегда
            DeletedTaskDetailDialog(
                task = task,
                onDismiss = { selectedTask = null },
                onRestoreClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            taskRepo.restoreTask(task)
                        }
                        when (result) {
                            is EasResult.Success -> {
                                Toast.makeText(context, taskRestoredText, Toast.LENGTH_SHORT).show()
                            }
                            is EasResult.Error -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                        selectedTask = null
                    }
                },
                onDeletePermanentlyClick = {
                    selectedTask = null  // Закрываем диалог СРАЗУ
                    com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                    
                    deletionController.startDeletion(
                        emailIds = listOf(task.id),
                        message = deletingOneTaskText,
                        scope = scope,
                        isRestore = false
                    ) { ids, onProgress ->
                        val result = withContext(Dispatchers.IO) {
                            taskRepo.deleteTaskPermanently(task)
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
                }
            )
        } else {
            // Обычный диалог для активной задачи
            TaskDetailDialog(
                task = task,
                taskRepo = taskRepo,
                onDismiss = { selectedTask = null },
                onEditClick = {
                    editingTask = task
                    selectedTask = null
                    showCreateDialog = true
                },
                onDeleteClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            taskRepo.deleteTask(task)
                        }
                        when (result) {
                            is EasResult.Success -> {
                                Toast.makeText(context, taskDeletedText, Toast.LENGTH_SHORT).show()
                            }
                            is EasResult.Error -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                        selectedTask = null
                    }
                },
                onToggleComplete = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            taskRepo.toggleTaskComplete(task)
                        }
                        when (result) {
                            is EasResult.Success -> {
                                val msg = if (result.data.complete) taskCompletedText else taskNotCompletedText
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            is EasResult.Error -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                        selectedTask = null
                    }
                }
            )
        }
    }
    
    // Диалог создания/редактирования
    if (showCreateDialog) {
        val taskUpdatedText = Strings.taskUpdated
        val taskCreatedText = Strings.taskCreated
        val isEditing = editingTask != null
        val database = remember { MailDatabase.getInstance(context) }
        CreateTaskDialog(
            task = editingTask,
            isCreating = isCreating,
            accountId = accountId,
            database = database,
            activeAccountEmail = activeAccount?.email ?: "",
            onDismiss = {
                showCreateDialog = false
                editingTask = null
            },
            onSave = { subject, body, startDate, dueDate, importance, reminderSet, reminderTime, assignTo ->
                // Защита от double-tap: если уже создаём — игнорируем
                if (isCreating) return@CreateTaskDialog
                isCreating = true
                scope.launch {
                    // Захватываем editingTask в локальную переменную для безопасного доступа
                    val taskToEdit = editingTask
                    val result = if (taskToEdit != null) {
                        withContext(Dispatchers.IO) {
                            taskRepo.updateTask(
                                task = taskToEdit,
                                subject = subject,
                                body = body,
                                startDate = startDate,
                                dueDate = dueDate,
                                complete = taskToEdit.complete,
                                importance = importance,
                                reminderSet = reminderSet,
                                reminderTime = reminderTime,
                                assignTo = assignTo
                            )
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            taskRepo.createTask(
                                accountId = accountId,
                                subject = subject,
                                body = body,
                                startDate = startDate,
                                dueDate = dueDate,
                                importance = importance,
                                reminderSet = reminderSet,
                                reminderTime = reminderTime,
                                assignTo = assignTo
                            )
                        }
                    }
                    isCreating = false
                    when (result) {
                        is EasResult.Success -> {
                            Toast.makeText(
                                context,
                                if (isEditing) taskUpdatedText else taskCreatedText,
                                Toast.LENGTH_SHORT
                            ).show()
                            showCreateDialog = false
                            editingTask = null
                        }
                        is EasResult.Error -> {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }
    
    // Диалог очистки корзины задач
    if (showEmptyTrashDialog) {
        val deletingMessage = Strings.deletingTasks(deletedTasks.size)
        val tasksTrashEmptiedText = Strings.tasksTrashEmptied
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(Strings.emptyTasksTrash) },
            text = { Text(Strings.emptyTasksTrashConfirm(deletedTasks.size)) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        showEmptyTrashDialog = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        
                        val taskIds = deletedTasks.map { it.id }
                        if (taskIds.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = taskIds,
                                message = deletingMessage,
                                scope = scope,
                                isRestore = false
                            ) { ids, onProgress ->
                                val result = withContext(Dispatchers.IO) {
                                    taskRepo.emptyTasksTrash(accountId)
                                }
                                onProgress(ids.size, ids.size)
                                when (result) {
                                    is EasResult.Success -> {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                "$tasksTrashEmptiedText: ${result.data}",
                                                Toast.LENGTH_SHORT
                                            ).show()
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
    
    // Диалог подтверждения удаления задач
    if (showDeleteConfirmDialog) {
        val taskDeletedText = Strings.taskDeleted
        val tasksDeletedPermanentlyText = Strings.tasksDeletedPermanently
        val deletingTasksText = Strings.deletingTasks(deleteConfirmCount)
        // Вычисляем список задач для удаления заранее
        val tasksToDeleteList = if (deleteConfirmIsPermanent) {
            deletedTasks.filter { it.id in selectedDeletedIds }
        } else {
            emptyList()
        }
        val deletingTasksMessage = if (tasksToDeleteList.isNotEmpty()) {
            Strings.deletingTasks(tasksToDeleteList.size)
        } else {
            deletingTasksText
        }
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(if (deleteConfirmIsPermanent) AppIcons.DeleteForever else AppIcons.Delete, null) },
            title = { 
                Text(
                    if (deleteConfirmIsPermanent) 
                        Strings.deleteTasksPermanently 
                    else 
                        Strings.deleteTasks
                ) 
            },
            text = { 
                Text(
                    if (deleteConfirmCount == 1)
                        if (deleteConfirmIsPermanent) Strings.deleteTaskPermanentlyConfirm else Strings.deleteTaskConfirm
                    else
                        if (deleteConfirmIsPermanent) Strings.deleteTasksPermanentlyConfirm(deleteConfirmCount) else Strings.deleteTasksConfirm(deleteConfirmCount)
                ) 
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        if (deleteConfirmIsPermanent) {
                            // Permanent delete - используем deletionController
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                            val taskIds = tasksToDeleteList.map { it.id }
                            selectedDeletedIds = emptySet()
                            
                            if (taskIds.isNotEmpty()) {
                                deletionController.startDeletion(
                                    emailIds = taskIds,
                                    message = deletingTasksMessage,
                                    scope = scope,
                                    isRestore = false
                                ) { ids, onProgress ->
                                    var deleted = 0
                                    for (task in tasksToDeleteList) {
                                        val result = withContext(Dispatchers.IO) {
                                            taskRepo.deleteTaskPermanently(task)
                                        }
                                        if (result is EasResult.Success) deleted++
                                        onProgress(deleted, tasksToDeleteList.size)
                                    }
                                }
                            }
                        } else {
                            // Regular delete to trash - без прогресса
                            scope.launch {
                                var deleted = 0
                                for (id in selectedTaskIds) {
                                    val task = allTasks.find { it.id == id }
                                    if (task != null) {
                                        val result = withContext(Dispatchers.IO) {
                                            taskRepo.deleteTask(task)
                                        }
                                        if (result is EasResult.Success) deleted++
                                    }
                                }
                                selectedTaskIds = emptySet()
                                Toast.makeText(context, "$taskDeletedText: $deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Строки локализации для множественного удаления
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                val tasksRestoredText = Strings.tasksRestored
                val selectedCount = selectedTaskIds.size + selectedDeletedIds.size
                TasksSelectionTopBar(
                    selectedCount = selectedCount,
                    isDeletedTab = isDeletedSelectionMode,
                    onClearSelection = {
                        selectedTaskIds = emptySet()
                        selectedDeletedIds = emptySet()
                    },
                    onRestore = {
                        if (isDeletedSelectionMode) {
                            // Восстановление задач
                            scope.launch {
                                var restored = 0
                                for (id in selectedDeletedIds) {
                                    val task = deletedTasks.find { it.id == id }
                                    if (task != null) {
                                        val result = withContext(Dispatchers.IO) {
                                            taskRepo.restoreTask(task)
                                        }
                                        if (result is EasResult.Success) restored++
                                    }
                                }
                                selectedDeletedIds = emptySet()
                                Toast.makeText(context, "$tasksRestoredText: $restored", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDelete = {
                        deleteConfirmCount = selectedCount
                        // КРИТИЧНО: Если выбраны ТОЛЬКО удалённые задачи - удаляем окончательно
                        deleteConfirmIsPermanent = selectedDeletedIds.isNotEmpty() && selectedTaskIds.isEmpty()
                        showDeleteConfirmDialog = true
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(Strings.tasks, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                        }
                    },
                    actions = {
                        // Кнопка синхронизации
                        val tasksSyncedText = Strings.tasksSynced
                        IconButton(
                            onClick = {
                                syncScope.launch {
                                    isSyncing = true
                                    val result = withContext(Dispatchers.IO) {
                                        taskRepo.syncTasks(accountId)
                                    }
                                    isSyncing = false
                                    when (result) {
                                        is EasResult.Success -> {
                                            Toast.makeText(
                                                context,
                                                "$tasksSyncedText: ${result.data}",
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
                                Icon(AppIcons.Sync, Strings.syncTasks, tint = Color.White)
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
            com.dedovmosol.iwomail.ui.theme.AnimatedFab(
                onClick = {
                    editingTask = null
                    showCreateDialog = true
                },
                containerColor = LocalColorTheme.current.gradientStart
            ) {
                Icon(AppIcons.Add, Strings.newTask, tint = Color.White)
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
                placeholder = { Text(Strings.searchTasks) },
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
            
            // Фильтры
            TaskFilterChips(
                currentFilter = currentFilter,
                onFilterChange = { currentFilter = it },
                deletedCount = deletedTasks.size,
                onEmptyTrash = { showEmptyTrashDialog = true }
            )
            
            // Счётчик
            Text(
                text = "${Strings.total}: ${filteredTasks.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            
            if (filteredTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!dataLoaded || isSyncing) {
                        // Данные ещё загружаются — показываем индикатор
                        CircularProgressIndicator()
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                AppIcons.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = Strings.noTasks,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val taskCompletedTextList = Strings.taskCompleted
                val taskNotCompletedTextList = Strings.taskNotCompleted
                val isDeletedFilter = currentFilter == TaskFilter.DELETED
                
                Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        // Проверяем task.isDeleted, а не фильтр!
                        // Это позволяет показывать удалённые задачи в фильтре ALL с правильной карточкой
                        if (task.isDeleted) {
                            // Для удалённых — с чекбоксом выбора
                            DeletedTaskCard(
                                task = task,
                                isSelected = task.id in selectedDeletedIds,
                                onSelectionChange = { selected ->
                                    selectedDeletedIds = if (selected) {
                                        selectedDeletedIds + task.id
                                    } else {
                                        selectedDeletedIds - task.id
                                    }
                                },
                                onClick = {
                                    if (isDeletedSelectionMode) {
                                        selectedDeletedIds = if (task.id in selectedDeletedIds) {
                                            selectedDeletedIds - task.id
                                        } else {
                                            selectedDeletedIds + task.id
                                        }
                                    } else {
                                        selectedTask = task
                                    }
                                },
                                onLongClick = {
                                    if (!isDeletedSelectionMode) {
                                        selectedDeletedIds = selectedDeletedIds + task.id
                                    }
                                }
                            )
                        } else {
                            // Обычная карточка
                            TaskCard(
                                task = task,
                                isSelected = task.id in selectedTaskIds,
                                isSelectionMode = isActiveSelectionMode,
                                onClick = {
                                    if (isActiveSelectionMode) {
                                        selectedTaskIds = if (task.id in selectedTaskIds) {
                                            selectedTaskIds - task.id
                                        } else {
                                            selectedTaskIds + task.id
                                        }
                                    } else {
                                        selectedTask = task
                                    }
                                },
                                onLongClick = {
                                    if (!isActiveSelectionMode) {
                                        selectedTaskIds = selectedTaskIds + task.id
                                    }
                                },
                                onToggleComplete = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            taskRepo.toggleTaskComplete(task)
                                        }
                                        when (result) {
                                            is EasResult.Success -> {
                                                val msg = if (result.data.complete) taskCompletedTextList else taskNotCompletedTextList
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                            is EasResult.Error -> {
                                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                    LazyColumnScrollbar(listState)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksSelectionTopBar(
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

/**
 * Карточка удалённой задачи с чекбоксом выбора
 */
@Composable
private fun DeletedTaskCard(
    task: TaskEntity,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Чекбокс выбора
            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChange
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Иконка корзины для удалённых задач
                    Icon(
                        AppIcons.Delete,
                        contentDescription = Strings.deleted,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    if (task.importance == TaskImportance.HIGH.value) {
                        Icon(
                            AppIcons.Star,
                            contentDescription = Strings.priorityHigh,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFF44336)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    Text(
                        text = task.subject.ifBlank { Strings.noTitle },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                if (task.dueDate > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = dateFormat.format(Date(task.dueDate)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


@Composable
private fun TaskFilterChips(
    currentFilter: TaskFilter,
    onFilterChange: (TaskFilter) -> Unit,
    deletedCount: Int = 0,
    onEmptyTrash: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentFilter == TaskFilter.ALL,
            onClick = { onFilterChange(TaskFilter.ALL) },
            label = { Text(Strings.allTasks) }
        )
        FilterChip(
            selected = currentFilter == TaskFilter.TODAY,
            onClick = { onFilterChange(TaskFilter.TODAY) },
            label = { Text(Strings.today) },
            leadingIcon = if (currentFilter == TaskFilter.TODAY) {
                { Icon(AppIcons.Schedule, null, Modifier.size(16.dp)) }
            } else null
        )
        FilterChip(
            selected = currentFilter == TaskFilter.ACTIVE,
            onClick = { onFilterChange(TaskFilter.ACTIVE) },
            label = { Text(Strings.activeTasks) }
        )
        FilterChip(
            selected = currentFilter == TaskFilter.COMPLETED,
            onClick = { onFilterChange(TaskFilter.COMPLETED) },
            label = { Text(Strings.completedTasks) }
        )
        FilterChip(
            selected = currentFilter == TaskFilter.HIGH_PRIORITY,
            onClick = { onFilterChange(TaskFilter.HIGH_PRIORITY) },
            label = { Text(Strings.highPriorityTasks) },
            leadingIcon = if (currentFilter == TaskFilter.HIGH_PRIORITY) {
                { Icon(AppIcons.Star, null, Modifier.size(16.dp)) }
            } else null
        )
        FilterChip(
            selected = currentFilter == TaskFilter.OVERDUE,
            onClick = { onFilterChange(TaskFilter.OVERDUE) },
            label = { Text(Strings.overdueTasks) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFFF44336).copy(alpha = 0.2f)
            )
        )
        FilterChip(
            selected = currentFilter == TaskFilter.DELETED,
            onClick = { onFilterChange(TaskFilter.DELETED) },
            label = { 
                Text(
                    if (deletedCount > 0) "${Strings.deletedTasks} ($deletedCount)" 
                    else Strings.deletedTasks
                ) 
            },
            leadingIcon = if (currentFilter == TaskFilter.DELETED) {
                { Icon(AppIcons.Delete, null, Modifier.size(16.dp)) }
            } else null
        )
        
        // Кнопка очистки корзины (показываем только когда выбран фильтр DELETED и есть задачи)
        if (currentFilter == TaskFilter.DELETED && deletedCount > 0 && onEmptyTrash != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onEmptyTrash,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336)
                )
            ) {
                Icon(AppIcons.DeleteForever, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text(Strings.emptyTrash, color = Color.White)
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleComplete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val isOverdue = task.dueDate > 0 && task.dueDate < System.currentTimeMillis() && !task.complete
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "taskBg"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.complete) {
                backgroundColor.copy(alpha = 0.7f)
            } else {
                backgroundColor
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            } else {
                // Чекбокс выполнения
                Checkbox(
                    checked = task.complete,
                    onCheckedChange = { onToggleComplete() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF4CAF50)
                    )
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Индикатор приоритета
                    if (task.importance == TaskImportance.HIGH.value) {
                        Icon(
                            AppIcons.Star,
                            contentDescription = Strings.priorityHigh,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFF44336)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    Text(
                        text = task.subject.ifBlank { Strings.noTitle },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (task.complete) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (task.complete) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                if (task.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.body.take(100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                if (task.dueDate > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            AppIcons.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (isOverdue) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = dateFormat.format(Date(task.dueDate)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOverdue) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDetailDialog(
    task: TaskEntity,
    taskRepo: TaskRepository,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleComplete: () -> Unit
) {
    val dateTimeFormat = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()) }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.importance == TaskImportance.HIGH.value) {
                    Icon(
                        AppIcons.Star,
                        contentDescription = Strings.priorityHigh,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFF44336)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = task.subject.ifBlank { Strings.noTitle },
                    textDecoration = if (task.complete) TextDecoration.LineThrough else TextDecoration.None
                )
            }
        },
        text = {
            Column {
                // Статус — стилизованный чип вместо голого Checkbox
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleComplete() }
                        .background(
                            if (task.complete) Color(0xFF4CAF50).copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (task.complete) AppIcons.CheckCircle else AppIcons.CheckCircle,
                        contentDescription = null,
                        tint = if (task.complete) Color(0xFF4CAF50)
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = if (task.complete) Strings.taskCompleted else Strings.taskInProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (task.complete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Приоритет
                Text(
                    text = "${Strings.priority}: ${
                        when (task.importance) {
                            TaskImportance.LOW.value -> Strings.priorityLow
                            TaskImportance.HIGH.value -> Strings.priorityHigh
                            else -> Strings.priorityNormal
                        }
                    }",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Даты
                if (task.startDate > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${Strings.startDate}: ${dateTimeFormat.format(Date(task.startDate))}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                if (task.dueDate > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val isOverdue = task.dueDate < System.currentTimeMillis() && !task.complete
                    Text(
                        text = "${Strings.dueDate}: ${dateTimeFormat.format(Date(task.dueDate))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOverdue) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // Описание
                             val cleanBody = remember(task.body) {
                    // Сначала заменяем HTML теги на переносы строк
                    val normalized = task.body
                        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("</?div[^>]*>", RegexOption.IGNORE_CASE), "\n")
                    
                    val seen = mutableSetOf<String>()
                    normalized.lines()
                        .filter { line ->
                            val trimmed = line.trim().replace("\\s+".toRegex(), " ")
                            if (trimmed.isBlank()) true
                            else seen.add(trimmed.lowercase())
                        }
                        .joinToString("\n").trim()
                }
                if (cleanBody.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = Strings.taskDescription,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        com.dedovmosol.iwomail.ui.components.RichTextWithImages(
                            htmlContent = cleanBody,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Справа: Удалить (корзина с обводкой)
            OutlinedButton(
                onClick = onDeleteClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFF44336)
                ),
                border = BorderStroke(1.dp, Color(0xFFF44336))
            ) {
                Icon(
                    imageVector = AppIcons.Delete,
                    contentDescription = Strings.delete,
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFFF44336)
                )
            }
        },
        dismissButton = {
            // Слева: Редактировать (карандаш с обводкой, цвет из темы)
            OutlinedButton(
                onClick = onEditClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = LocalColorTheme.current.gradientStart
                )
            ) {
                Icon(
                    imageVector = AppIcons.Edit,
                    contentDescription = Strings.edit,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    )
}

/**
 * Диалог для удалённой задачи — восстановить или удалить навсегда
 */
@Composable
private fun DeletedTaskDetailDialog(
    task: TaskEntity,
    onDismiss: () -> Unit,
    onRestoreClick: () -> Unit,
    onDeletePermanentlyClick: () -> Unit
) {
    val dateTimeFormat = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()) }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.subject.ifBlank { Strings.noTitle },
                    textDecoration = TextDecoration.LineThrough
                )
            }
        },
        text = {
            Column {
                // Статус — удалена
                Text(
                    text = Strings.taskInTrash,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Приоритет
                Text(
                    text = "${Strings.priority}: ${
                        when (task.importance) {
                            TaskImportance.LOW.value -> Strings.priorityLow
                            TaskImportance.HIGH.value -> Strings.priorityHigh
                            else -> Strings.priorityNormal
                        }
                    }",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Даты
                if (task.dueDate > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${Strings.dueDate}: ${dateTimeFormat.format(Date(task.dueDate))}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Описание задачи
                if (task.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = task.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // КРИТИЧНО: Кнопки разнесены по сторонам
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Слева: Восстановить (только иконка, цвет из темы)
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
                    
                    // Справа: Удалить окончательно (только красная корзина)
                    OutlinedButton(
                        onClick = onDeletePermanentlyClick,
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
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTaskDialog(
    task: TaskEntity?,
    isCreating: Boolean,
    accountId: Long,
    database: MailDatabase,
    activeAccountEmail: String,
    onDismiss: () -> Unit,
    onSave: (subject: String, body: String, startDate: Long, dueDate: Long, importance: Int, reminderSet: Boolean, reminderTime: Long, assignTo: String?) -> Unit
) {
    var subject by rememberSaveable { mutableStateOf(task?.subject ?: "") }
    var body by rememberSaveable { mutableStateOf(task?.body ?: "") }
    var startDate by rememberSaveable { mutableStateOf(task?.startDate ?: 0L) }
    var dueDate by rememberSaveable { mutableStateOf(task?.dueDate ?: 0L) }
    var importance by rememberSaveable { mutableStateOf(task?.importance ?: TaskImportance.NORMAL.value) }
    var reminderSet by rememberSaveable { mutableStateOf(task?.reminderSet ?: false) }
    var reminderTime by rememberSaveable { mutableStateOf(task?.reminderTime ?: 0L) }
    var assignTo by rememberSaveable { mutableStateOf("") }
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    
    // Текстовые поля для дат и времени
    var startDateText by rememberSaveable { mutableStateOf("") }
    var startTimeText by rememberSaveable { mutableStateOf("") }
    var dueDateText by rememberSaveable { mutableStateOf("") }
    var dueTimeText by rememberSaveable { mutableStateOf("") }
    var reminderDateText by rememberSaveable { mutableStateOf("") }
    var reminderTimeText by rememberSaveable { mutableStateOf("") }
    
    // Флаг: даты уже инициализированы (защита от перезаписи при повороте экрана)
    var datesInitialized by rememberSaveable { mutableStateOf(false) }
    
    // Состояния для DatePicker / TimePicker диалогов
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showStartTimePicker by rememberSaveable { mutableStateOf(false) }
    var showDueDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDueTimePicker by rememberSaveable { mutableStateOf(false) }
    var showReminderDatePicker by rememberSaveable { mutableStateOf(false) }
    var showReminderTimePicker by rememberSaveable { mutableStateOf(false) }
    
    // Инициализация текстовых полей из существующих дат или умолчания для новых задач
    // datesInitialized защищает от перезаписи при повороте экрана
    LaunchedEffect(task) {
        if (datesInitialized) return@LaunchedEffect
        datesInitialized = true
        
        if (task != null) {
            if (task.startDate > 0) {
                startDateText = TASK_PARSE_DATE_FORMAT.format(Date(task.startDate))
                startTimeText = TASK_PARSE_TIME_FORMAT.format(Date(task.startDate))
            }
            if (task.dueDate > 0) {
                dueDateText = TASK_PARSE_DATE_FORMAT.format(Date(task.dueDate))
                dueTimeText = TASK_PARSE_TIME_FORMAT.format(Date(task.dueDate))
            }
            if (task.reminderTime > 0) {
                reminderDateText = TASK_PARSE_DATE_FORMAT.format(Date(task.reminderTime))
                reminderTimeText = TASK_PARSE_TIME_FORMAT.format(Date(task.reminderTime))
            }
        } else {
            // Умолчания для новой задачи:
            // Начало — сегодня, текущее время
            val now = System.currentTimeMillis()
            val nowDate = Date(now)
            startDate = now
            startDateText = TASK_PARSE_DATE_FORMAT.format(nowDate)
            startTimeText = TASK_PARSE_TIME_FORMAT.format(nowDate)
            // Срок — через сутки
            val dueDateMs = now + 24 * 60 * 60 * 1000L
            val dueDateObj = Date(dueDateMs)
            dueDate = dueDateMs
            dueDateText = TASK_PARSE_DATE_FORMAT.format(dueDateObj)
            dueTimeText = TASK_PARSE_TIME_FORMAT.format(dueDateObj)
            // Напоминание — через 12 часов
            val reminderMs = now + 12 * 60 * 60 * 1000L
            val reminderObj = Date(reminderMs)
            reminderTime = reminderMs
            reminderSet = true
            reminderDateText = TASK_PARSE_DATE_FORMAT.format(reminderObj)
            reminderTimeText = TASK_PARSE_TIME_FORMAT.format(reminderObj)
        }
    }
    
    val dateTimeFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    
    val lazyListState = rememberLazyListState()
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        scrollable = false, // Отключаем автоскролл диалога
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .widthIn(max = 560.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (task != null) Strings.editTask else Strings.newTask,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
            }
        },
        text = {
            // Контент с прокруткой + видимый скроллбар
            Box(modifier = Modifier.heightIn(min = 200.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 8.dp, bottom = 16.dp)
                ) {
                item {
                    // Название
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text(Strings.taskTitle) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                item {
                    // Описание
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text(Strings.taskDescription) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
                
                // Назначить задачу (доступно при создании и редактировании)
                item {
                    OutlinedTextField(
                        value = assignTo,
                        onValueChange = { assignTo = it },
                        label = { Text(Strings.assignTo) },
                        placeholder = { Text(Strings.assignToHint) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(AppIcons.Person, null, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            IconButton(onClick = { showContactPicker = true }) {
                                Icon(AppIcons.Contacts, Strings.selectContact, modifier = Modifier.size(20.dp))
                            }
                        }
                    )
                }
                
                item {
                    // Приоритет
                    Text(
                        text = Strings.priority,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = importance == TaskImportance.LOW.value,
                            onClick = { importance = TaskImportance.LOW.value },
                            label = { Text(Strings.priorityLow) }
                        )
                        FilterChip(
                            selected = importance == TaskImportance.NORMAL.value,
                            onClick = { importance = TaskImportance.NORMAL.value },
                            label = { Text(Strings.priorityNormal) }
                        )
                        FilterChip(
                            selected = importance == TaskImportance.HIGH.value,
                            onClick = { importance = TaskImportance.HIGH.value },
                            label = { Text(Strings.priorityHigh) },
                            leadingIcon = {
                                if (importance == TaskImportance.HIGH.value) {
                                    Icon(
                                        AppIcons.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
                
                item {
                    // Дата начала
                    Text(
                        text = Strings.startDate,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = startDateText,
                            onValueChange = { 
                                val filtered = it.filter { c -> c.isDigit() || c == '.' }
                                if (filtered.length <= 10) {
                                    startDateText = filtered
                                }
                            },
                            placeholder = { Text(Strings.datePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showStartDatePicker = true }) {
                                    Icon(AppIcons.Calendar, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = startTimeText,
                            onValueChange = { newValue ->
                                val digits = newValue.filter { it.isDigit() }.take(4)
                                startTimeText = when {
                                    digits.length <= 2 -> digits
                                    else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
                                }
                            },
                            placeholder = { Text(Strings.timePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showStartTimePicker = true }) {
                                    Icon(AppIcons.Schedule, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        if (startDateText.isNotEmpty() || startTimeText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    startDateText = ""
                                    startTimeText = ""
                                    startDate = 0L
                                }
                            ) {
                                Icon(AppIcons.Clear, Strings.clear)
                            }
                        }
                    }
                }
                
                item {
                    // Срок выполнения
                    Text(
                        text = Strings.dueDate,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = dueDateText,
                            onValueChange = { 
                                val filtered = it.filter { c -> c.isDigit() || c == '.' }
                                if (filtered.length <= 10) {
                                    dueDateText = filtered
                                }
                            },
                            placeholder = { Text(Strings.datePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showDueDatePicker = true }) {
                                    Icon(AppIcons.Calendar, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = dueTimeText,
                            onValueChange = { newValue ->
                                val digits = newValue.filter { it.isDigit() }.take(4)
                                dueTimeText = when {
                                    digits.length <= 2 -> digits
                                    else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
                                }
                            },
                            placeholder = { Text(Strings.timePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showDueTimePicker = true }) {
                                    Icon(AppIcons.Schedule, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        if (dueDateText.isNotEmpty() || dueTimeText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    dueDateText = ""
                                    dueTimeText = ""
                                    dueDate = 0L
                                }
                            ) {
                                Icon(AppIcons.Clear, Strings.clear)
                            }
                        }
                    }
                }
                
                item {
                    // Напоминание
                    Text(
                        text = Strings.reminder,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = reminderDateText,
                            onValueChange = { 
                                val filtered = it.filter { c -> c.isDigit() || c == '.' }
                                if (filtered.length <= 10) {
                                    reminderDateText = filtered
                                }
                            },
                            placeholder = { Text(Strings.datePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showReminderDatePicker = true }) {
                                    Icon(AppIcons.Calendar, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = reminderTimeText,
                            onValueChange = { newValue ->
                                val digits = newValue.filter { it.isDigit() }.take(4)
                                reminderTimeText = when {
                                    digits.length <= 2 -> digits
                                    else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
                                }
                            },
                            placeholder = { Text(Strings.timePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showReminderTimePicker = true }) {
                                    Icon(AppIcons.Schedule, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        if (reminderDateText.isNotEmpty() || reminderTimeText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    reminderDateText = ""
                                    reminderTimeText = ""
                                    reminderTime = 0L
                                    reminderSet = false
                                }
                            ) {
                                Icon(AppIcons.Clear, Strings.clear)
                            }
                        }
                    }
                }
            }
            
            LazyColumnScrollbar(lazyListState)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (subject.isNotBlank()) {
                        // Парсинг даты начала
                        val parsedStartDate = parseDateTime(startDateText, startTimeText)
                        if (parsedStartDate > 0) startDate = parsedStartDate
                        
                        // Парсинг срока выполнения
                        val parsedDueDate = parseDateTime(dueDateText, dueTimeText)
                        if (parsedDueDate > 0) dueDate = parsedDueDate
                        
                        // Парсинг напоминания
                        val parsedReminderTime = parseDateTime(reminderDateText, reminderTimeText)
                        if (parsedReminderTime > 0) {
                            reminderTime = parsedReminderTime
                            reminderSet = true
                        }
                        
                        onSave(subject, body, startDate, dueDate, importance, reminderSet, reminderTime, assignTo.trim().ifBlank { null })
                    }
                },
                enabled = subject.isNotBlank() && !isCreating
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(Strings.save)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        }
    )
    
    // Диалог выбора контакта (СНАРУЖИ основного диалога)
    if (showContactPicker) {
        ContactPickerDialog(
            accountId = accountId,
            database = database,
            ownEmail = activeAccountEmail,
            onDismiss = { showContactPicker = false },
            onContactsSelected = { emails ->
                if (emails.isNotEmpty()) {
                    assignTo = emails.first()
                }
                showContactPicker = false
            }
        )
    }
    
    // DatePicker и TimePicker диалоги
    val pickerDateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try { pickerDateFormat.parse(startDateText)?.time } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { startDateText = pickerDateFormat.format(Date(it)) }
                    showStartDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = { TextButton(onClick = { showStartDatePicker = false }) { Text(Strings.cancel) } }
        ) { DatePicker(state = datePickerState) }
    }
    
    if (showDueDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try { pickerDateFormat.parse(dueDateText)?.time } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showDueDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { dueDateText = pickerDateFormat.format(Date(it)) }
                    showDueDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = { TextButton(onClick = { showDueDatePicker = false }) { Text(Strings.cancel) } }
        ) { DatePicker(state = datePickerState) }
    }
    
    if (showReminderDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try { pickerDateFormat.parse(reminderDateText)?.time } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showReminderDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { reminderDateText = pickerDateFormat.format(Date(it)) }
                    showReminderDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = { TextButton(onClick = { showReminderDatePicker = false }) { Text(Strings.cancel) } }
        ) { DatePicker(state = datePickerState) }
    }
    
    if (showStartTimePicker) {
        val h = try { startTimeText.split(":")[0].toInt() } catch (_: Exception) { 9 }
        val m = try { startTimeText.split(":")[1].toInt() } catch (_: Exception) { 0 }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(onDismissRequest = { showStartTimePicker = false }) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    TimePicker(state = state)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showStartTimePicker = false }) { Text(Strings.cancel) }
                        TextButton(onClick = {
                            startTimeText = String.format("%02d:%02d", state.hour, state.minute)
                            showStartTimePicker = false
                        }) { Text(Strings.ok) }
                    }
                }
            }
        }
    }
    
    if (showDueTimePicker) {
        val h = try { dueTimeText.split(":")[0].toInt() } catch (_: Exception) { 18 }
        val m = try { dueTimeText.split(":")[1].toInt() } catch (_: Exception) { 0 }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(onDismissRequest = { showDueTimePicker = false }) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    TimePicker(state = state)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showDueTimePicker = false }) { Text(Strings.cancel) }
                        TextButton(onClick = {
                            dueTimeText = String.format("%02d:%02d", state.hour, state.minute)
                            showDueTimePicker = false
                        }) { Text(Strings.ok) }
                    }
                }
            }
        }
    }
    
    if (showReminderTimePicker) {
        val h = try { reminderTimeText.split(":")[0].toInt() } catch (_: Exception) { 9 }
        val m = try { reminderTimeText.split(":")[1].toInt() } catch (_: Exception) { 0 }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(onDismissRequest = { showReminderTimePicker = false }) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    TimePicker(state = state)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showReminderTimePicker = false }) { Text(Strings.cancel) }
                        TextButton(onClick = {
                            reminderTimeText = String.format("%02d:%02d", state.hour, state.minute)
                            showReminderTimePicker = false
                        }) { Text(Strings.ok) }
                    }
                }
            }
        }
    }
}

/**
 * Парсит дату и время из текстовых полей в миллисекунды
 * Формат даты: дд.мм.гггг
 * Формат времени: чч:мм
 */
private fun parseDateTime(dateText: String, timeText: String): Long {
    if (dateText.isBlank()) return 0L
    
    try {
        val parsedDate = TASK_PARSE_DATE_FORMAT.parse(dateText) ?: return 0L
        
        val calendar = Calendar.getInstance().apply {
            time = parsedDate
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // Парсинг времени если указано
        if (timeText.isNotBlank()) {
            val timeParts = timeText.split(":")
            if (timeParts.size == 2) {
                val hour = timeParts[0].toIntOrNull() ?: 0
                val minute = timeParts[1].toIntOrNull() ?: 0
                
                // Валидация времени (00-23:00-59)
                if (hour in 0..23 && minute in 0..59) {
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                }
            }
        } else {
            // Если время не указано, ставим 00:00
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
        }
        
        return calendar.timeInMillis
    } catch (e: Exception) {
        return 0L
    }
}

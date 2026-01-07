package com.iwo.mailclient.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.database.TaskEntity
import com.iwo.mailclient.data.database.TaskImportance
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.RepositoryProvider
import com.iwo.mailclient.data.repository.TaskRepository
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.components.ContactPickerDialog
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

enum class TaskFilter {
    ALL, ACTIVE, COMPLETED, HIGH_PRIORITY, OVERDUE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val taskRepo = remember { RepositoryProvider.getTaskRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    
    // Отдельный scope для синхронизации, чтобы не отменялась при навигации
    val syncScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) {
        onDispose { syncScope.cancel() }
    }
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L
    
    val allTasks by remember(accountId) { taskRepo.getTasks(accountId) }.collectAsState(initial = emptyList())
    
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    
    // Debounce поиска для оптимизации
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedSearchQuery = searchQuery
    }
    var isSyncing by rememberSaveable { mutableStateOf(false) }
    
    // Автоматическая синхронизация при первом открытии если нет данных
    LaunchedEffect(accountId, allTasks.isEmpty()) {
        if (accountId > 0 && allTasks.isEmpty() && !isSyncing) {
            isSyncing = true
            syncScope.launch {
                withContext(Dispatchers.IO) {
                    taskRepo.syncTasks(accountId)
                }
                isSyncing = false
            }
        }
    }
    var selectedTask by remember { mutableStateOf<TaskEntity?>(null) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var currentFilter by rememberSaveable { mutableStateOf(TaskFilter.ALL) }
    
    val listState = rememberLazyListState()
    
    // Состояние сортировки (true = новые сверху, false = старые сверху)
    var sortDescending by rememberSaveable { mutableStateOf(true) }

    // Фильтрация задач
    val filteredTasks = remember(allTasks, debouncedSearchQuery, currentFilter, sortDescending) {
        var tasks = if (debouncedSearchQuery.isBlank()) {
            allTasks
        } else {
            allTasks.filter { task ->
                task.subject.contains(debouncedSearchQuery, ignoreCase = true) ||
                task.body.contains(debouncedSearchQuery, ignoreCase = true)
            }
        }
        
        val filtered = when (currentFilter) {
            TaskFilter.ALL -> tasks
            TaskFilter.ACTIVE -> tasks.filter { !it.complete }
            TaskFilter.COMPLETED -> tasks.filter { it.complete }
            TaskFilter.HIGH_PRIORITY -> tasks.filter { it.importance == TaskImportance.HIGH.value && !it.complete }
            TaskFilter.OVERDUE -> tasks.filter { 
                it.dueDate > 0 && it.dueDate < System.currentTimeMillis() && !it.complete 
            }
        }
        
        // Сортировка по дате (dueDate если есть, иначе по id)
        if (sortDescending) {
            filtered.sortedByDescending { if (it.dueDate > 0) it.dueDate else it.startDate }
        } else {
            filtered.sortedBy { if (it.dueDate > 0) it.dueDate else it.startDate }
        }
    }
    
    // Диалог просмотра/редактирования задачи
    selectedTask?.let { task ->
        val taskDeletedText = Strings.taskDeleted
        val taskCompletedText = Strings.taskCompleted
        val taskNotCompletedText = Strings.taskNotCompleted
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
            onDismiss = {
                showCreateDialog = false
                editingTask = null
            },
            onSave = { subject, body, startDate, dueDate, importance, reminderSet, reminderTime, assignTo ->
                scope.launch {
                    isCreating = true
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
                                reminderTime = reminderTime
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
    
    Scaffold(
        topBar = {
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
        },
        floatingActionButton = {
            com.iwo.mailclient.ui.theme.AnimatedFab(
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
            
            // Фильтры
            TaskFilterChips(
                currentFilter = currentFilter,
                onFilterChange = { currentFilter = it }
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
            } else {
                val taskCompletedTextList = Strings.taskCompleted
                val taskNotCompletedTextList = Strings.taskNotCompleted
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onClick = { selectedTask = task },
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
        }
    }
}


@Composable
private fun TaskFilterChips(
    currentFilter: TaskFilter,
    onFilterChange: (TaskFilter) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Первый ряд: Все, Активные, Выполненные
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = currentFilter == TaskFilter.ALL,
                onClick = { onFilterChange(TaskFilter.ALL) },
                label = { Text(Strings.allTasks) }
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
        }
        // Второй ряд: Важные, Просроченные
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onClick: () -> Unit,
    onToggleComplete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val isOverdue = task.dueDate > 0 && task.dueDate < System.currentTimeMillis() && !task.complete
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.complete) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Чекбокс выполнения
            Checkbox(
                checked = task.complete,
                onCheckedChange = { onToggleComplete() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF4CAF50)
                )
            )
            
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
    
    com.iwo.mailclient.ui.theme.ScaledAlertDialog(
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
                // Статус
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = task.complete,
                        onCheckedChange = { onToggleComplete() }
                    )
                    Text(
                        text = if (task.complete) Strings.taskCompleted else Strings.activeTasks,
                        style = MaterialTheme.typography.bodyMedium
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
                if (task.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = Strings.taskDescription,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        com.iwo.mailclient.ui.components.RichTextWithImages(
                            htmlContent = task.body,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEditClick) {
                Text(Strings.edit)
            }
        },
        dismissButton = {
            TextButton(onClick = onDeleteClick) {
                Text(Strings.delete, color = Color(0xFFF44336))
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreateTaskDialog(
    task: TaskEntity?,
    isCreating: Boolean,
    accountId: Long,
    database: MailDatabase,
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
    
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showStartTimePicker by rememberSaveable { mutableStateOf(false) }
    var tempStartDate by rememberSaveable { mutableStateOf(0L) }
    var showDueDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDueTimePicker by rememberSaveable { mutableStateOf(false) }
    var tempDueDate by rememberSaveable { mutableStateOf(0L) }
    var showReminderPicker by rememberSaveable { mutableStateOf(false) }
    var showReminderTimePicker by rememberSaveable { mutableStateOf(false) }
    var tempReminderDate by rememberSaveable { mutableStateOf(0L) }
    
    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val dateTimeFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    
    // Date pickers
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (startDate > 0) startDate else System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    tempStartDate = datePickerState.selectedDateMillis ?: 0L
                    if (tempStartDate > 0) {
                        showStartDatePicker = false
                        showStartTimePicker = true
                    }
                }) {
                    Text(Strings.next)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) {
                    Text(Strings.cancel)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    if (showStartTimePicker) {
        val calendar = remember { Calendar.getInstance().apply { 
            timeInMillis = if (startDate > 0) startDate else System.currentTimeMillis()
        }}
        val timePickerState = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE)
        )
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showStartTimePicker = false },
            title = { Text(Strings.selectTime) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = tempStartDate
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    startDate = cal.timeInMillis
                    showStartTimePicker = false
                }) {
                    Text(Strings.ok)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartTimePicker = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    if (showDueDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (dueDate > 0) dueDate else System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDueDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    tempDueDate = datePickerState.selectedDateMillis ?: 0L
                    if (tempDueDate > 0) {
                        showDueDatePicker = false
                        showDueTimePicker = true
                    }
                }) {
                    Text(Strings.next)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDueDatePicker = false }) {
                    Text(Strings.cancel)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    if (showDueTimePicker) {
        val calendar = remember { Calendar.getInstance().apply { 
            timeInMillis = if (dueDate > 0) dueDate else System.currentTimeMillis()
        }}
        val timePickerState = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE)
        )
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showDueTimePicker = false },
            title = { Text(Strings.selectTime) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = tempDueDate
                        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(Calendar.MINUTE, timePickerState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    dueDate = cal.timeInMillis
                    showDueTimePicker = false
                }) {
                    Text(Strings.ok)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDueTimePicker = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    if (showReminderPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = if (reminderTime > 0) reminderTime else System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showReminderPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    tempReminderDate = datePickerState.selectedDateMillis ?: 0L
                    if (tempReminderDate > 0) {
                        showReminderPicker = false
                        showReminderTimePicker = true
                    }
                }) {
                    Text(Strings.next)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReminderPicker = false }) {
                    Text(Strings.cancel)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    // TimePicker для напоминания
    if (showReminderTimePicker) {
        val calendar = remember { 
            java.util.Calendar.getInstance().apply {
                if (reminderTime > 0) {
                    timeInMillis = reminderTime
                } else {
                    set(java.util.Calendar.HOUR_OF_DAY, 9)
                    set(java.util.Calendar.MINUTE, 0)
                }
            }
        }
        val timePickerState = rememberTimePickerState(
            initialHour = calendar.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(java.util.Calendar.MINUTE),
            is24Hour = true
        )
        
        com.iwo.mailclient.ui.theme.ScaledAlertDialog(
            onDismissRequest = { showReminderTimePicker = false },
            title = { Text(Strings.selectTime) },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cal = java.util.Calendar.getInstance().apply {
                        timeInMillis = tempReminderDate
                        set(java.util.Calendar.HOUR_OF_DAY, timePickerState.hour)
                        set(java.util.Calendar.MINUTE, timePickerState.minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    reminderTime = cal.timeInMillis
                    reminderSet = true
                    showReminderTimePicker = false
                }) {
                    Text(Strings.ok)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showReminderTimePicker = false
                    showReminderPicker = true // Вернуться к выбору даты
                }) {
                    Text(Strings.back)
                }
            }
        )
    }
    
    com.iwo.mailclient.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task != null) Strings.editTask else Strings.newTask) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Название
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text(Strings.taskTitle) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // Описание
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(Strings.taskDescription) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                
                // Назначить задачу (только для новых задач)
                if (task == null) {
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
                
                // Диалог выбора контакта
                if (showContactPicker) {
                    ContactPickerDialog(
                        accountId = accountId,
                        database = database,
                        onDismiss = { showContactPicker = false },
                        onContactsSelected = { emails ->
                            if (emails.isNotEmpty()) {
                                assignTo = emails.first()
                            }
                            showContactPicker = false
                        }
                    )
                }
                
                // Приоритет
                Text(
                    text = Strings.priority,
                    style = MaterialTheme.typography.labelMedium
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                
                // Дата начала
                OutlinedButton(
                    onClick = { showStartDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(AppIcons.Calendar, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (startDate > 0) {
                            "${Strings.startDate}: ${dateTimeFormat.format(Date(startDate))}"
                        } else {
                            Strings.startDate
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Срок выполнения
                OutlinedButton(
                    onClick = { showDueDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(AppIcons.Schedule, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (dueDate > 0) {
                            "${Strings.dueDate}: ${dateTimeFormat.format(Date(dueDate))}"
                        } else {
                            Strings.dueDate
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Напоминание
                OutlinedButton(
                    onClick = { showReminderPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(AppIcons.Notifications, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (reminderSet && reminderTime > 0) {
                            "${Strings.reminder}: ${dateTimeFormat.format(Date(reminderTime))}"
                        } else {
                            Strings.setReminder
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (subject.isNotBlank()) {
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
}

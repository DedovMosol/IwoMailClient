package com.dedovmosol.iwomail.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.repository.CalendarRepository
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

// Предкомпилированные regex для производительности
private val HTML_TAG_REGEX = Regex("<[^>]*>")
private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

// Кэшированные SimpleDateFormat для утилитных функций (не thread-safe, но используются в suspend/Composable)
private val PARSE_DATE_TIME_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
private val PARSE_DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
private val PARSE_TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

enum class CalendarDateFilter {
    ALL, TODAY, WEEK, MONTH
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBackClick: () -> Unit,
    onComposeClick: (String) -> Unit = {},
    initialDateFilter: CalendarDateFilter = CalendarDateFilter.ALL
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calendarRepo = remember { RepositoryProvider.getCalendarRepository(context) }
    val accountRepo = remember { RepositoryProvider.getAccountRepository(context) }
    val deletionController = com.dedovmosol.iwomail.ui.components.LocalDeletionController.current
    
    // Отдельный scope для синхронизации, чтобы не отменялась при навигации
    val syncScope = com.dedovmosol.iwomail.ui.components.rememberSyncScope()
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L

    val events by remember(accountId) { calendarRepo.getEvents(accountId) }.collectAsState(initial = emptyList())
    
    // Флаг: данные из Room загружены (Flow эмитнул хотя бы раз)
    // КРИТИЧНО: rememberSaveable чтобы при повороте экрана НЕ запускалась повторная синхронизация.
    // С remember флаг сбрасывается при configuration change → events ещё emptyList (initial)
    // → LaunchedEffect видит events.isEmpty() и стартует sync заново.
    var dataLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(events) {
        if (events.isNotEmpty()) dataLoaded = true
    }
    
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    
    // Debounce поиска для оптимизации
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedSearchQuery = searchQuery
    }
    var isSyncing by rememberSaveable { mutableStateOf(false) }
    
    // Автоматическая синхронизация при первом открытии если нет данных
    // Ждём загрузку из Room перед тем как решить что данных нет
    // КРИТИЧНО: проверяем !dataLoaded чтобы при повороте экрана НЕ запускать повторную синхронизацию
    LaunchedEffect(accountId) {
        if (accountId > 0 && !dataLoaded) {
            delay(500)
            if (events.isEmpty() && !isSyncing) {
                dataLoaded = true
                isSyncing = true
                syncScope.launch {
                    withContext(Dispatchers.IO) {
                        calendarRepo.syncCalendar(accountId)
                    }
                    isSyncing = false
                }
            }
        }
    }
    var selectedEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(CalendarViewMode.AGENDA) }
    var selectedDate by remember { mutableStateOf(Date()) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    
    // Множественный выбор
    var selectedEventIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedEventIds.isNotEmpty()
    
    // Диалог подтверждения удаления
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmCount by rememberSaveable { mutableStateOf(0) }
    
    // Состояние списка для автоскролла
    val listState = rememberLazyListState()
    
    // Фильтр по дате (аналогично задачам)
    var dateFilter by rememberSaveable { mutableStateOf(initialDateFilter) }
    
    // Обработка кнопки Back в режиме выбора
    androidx.activity.compose.BackHandler(enabled = isSelectionMode) {
        selectedEventIds = emptySet()
    }
    
    // Фильтрация по поиску и по дате
    val filteredEvents = remember(events, debouncedSearchQuery, dateFilter) {
        // Сначала фильтруем по дате
        val dateFiltered = when (dateFilter) {
            CalendarDateFilter.ALL -> events
            CalendarDateFilter.TODAY -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val tomorrowStart = cal.timeInMillis
                events.filter { event ->
                    // Событие попадает в сегодня: начинается сегодня, или
                    // длится через сегодня (startTime < tomorrowStart && endTime > todayStart)
                    (event.startTime in todayStart until tomorrowStart) ||
                    (event.startTime < tomorrowStart && event.endTime > todayStart)
                }
            }
            CalendarDateFilter.WEEK -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 7)
                val weekEnd = cal.timeInMillis
                events.filter { event ->
                    (event.startTime in todayStart until weekEnd) ||
                    (event.startTime < weekEnd && event.endTime > todayStart)
                }
            }
            CalendarDateFilter.MONTH -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                val monthEnd = cal.timeInMillis
                events.filter { event ->
                    (event.startTime in todayStart until monthEnd) ||
                    (event.startTime < monthEnd && event.endTime > todayStart)
                }
            }
        }
        // Затем фильтруем по поиску
        if (debouncedSearchQuery.isBlank()) {
            dateFiltered
        } else {
            dateFiltered.filter { event ->
                event.subject.contains(debouncedSearchQuery, ignoreCase = true) ||
                event.location.contains(debouncedSearchQuery, ignoreCase = true) ||
                event.body.contains(debouncedSearchQuery, ignoreCase = true)
            }
        }
    }
    
    // Диалог просмотра события
    selectedEvent?.let { event ->
        val eventDeletedText = Strings.eventDeleted
        val deletingOneEventText = Strings.deletingEvents(1)
        val undoText = Strings.undo
        val eventsRestoredText = Strings.eventsRestored
        EventDetailDialog(
            event = event,
            calendarRepo = calendarRepo,
            currentUserEmail = activeAccount?.email ?: "",
            onDismiss = { selectedEvent = null },
            onComposeClick = onComposeClick,
            onEditClick = { 
                editingEvent = event
                selectedEvent = null
                showCreateDialog = true
            },
            onDeleteClick = {
                selectedEvent = null  // Закрываем диалог СРАЗУ
                com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                
                deletionController.startDeletion(
                    emailIds = listOf(event.id),
                    message = deletingOneEventText,
                    scope = scope,
                    isRestore = false
                ) { _, onProgress ->
                    val result = withContext(Dispatchers.IO) {
                        calendarRepo.deleteEvent(event)
                    }
                    onProgress(1, 1)
                    when (result) {
                        is EasResult.Success -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, eventDeletedText, Toast.LENGTH_SHORT).show()
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
        )
    }
    
    // Диалог подтверждения удаления событий
    if (showDeleteConfirmDialog) {
        val eventsDeletedText = Strings.eventDeleted
        val undoText = Strings.undo
        val eventsRestoredText = Strings.eventsRestored
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { 
                Text(Strings.deleteEvents) 
            },
            text = { 
                Text(
                    if (deleteConfirmCount == 1)
                        Strings.deleteEventConfirm
                    else
                        Strings.deleteEventsConfirm(deleteConfirmCount)
                ) 
            },
            confirmButton = {
                val deletingEventsMessage = Strings.deletingEvents(deleteConfirmCount)
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        
                        // Получаем события для удаления из всех событий (не только отфильтрованных)
                        val eventsToDelete = events.filter { it.id in selectedEventIds }
                        val eventIds = eventsToDelete.map { it.id }
                        selectedEventIds = emptySet()
                        
                        if (eventIds.isNotEmpty()) {
                            deletionController.startDeletion(
                                emailIds = eventIds,
                                message = deletingEventsMessage,
                                scope = scope,
                                isRestore = false
                            ) { _, onProgress ->
                                var deleted = 0
                                for (event in eventsToDelete) {
                                    val result = withContext(Dispatchers.IO) {
                                        calendarRepo.deleteEvent(event)
                                    }
                                    if (result is EasResult.Success) deleted++
                                    onProgress(deleted, eventsToDelete.size)
                                }
                                if (deleted > 0) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "$eventsDeletedText: $deleted",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
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
    
    // Диалог создания/редактирования события
    if (showCreateDialog) {
        val eventUpdatedText = Strings.eventUpdated
        val eventCreatedText = Strings.eventCreated
        val invitationSentText = Strings.invitationSent
        val isEditing = editingEvent != null
        CreateEventDialog(
            event = editingEvent,
            initialDate = selectedDate,
            isCreating = isCreating,
            accountId = accountId,
            ownEmail = activeAccount?.email ?: "",
            onDismiss = { 
                showCreateDialog = false
                editingEvent = null
            },
            onSave = { subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, attendees ->
                // Защита от double-tap: если уже создаём — игнорируем
                if (isCreating) return@CreateEventDialog
                isCreating = true
                scope.launch {
                    // Парсим список участников
                    val attendeeList = attendees.split(",", ";")
                        .map { it.trim() }
                        .filter { it.contains("@") }
                    
                    // Захватываем editingEvent в локальную переменную для безопасного доступа
                    val eventToEdit = editingEvent
                    val result = if (eventToEdit != null) {
                        withContext(Dispatchers.IO) {
                            calendarRepo.updateEvent(
                                event = eventToEdit,
                                subject = subject,
                                startTime = startTime,
                                endTime = endTime,
                                location = location,
                                body = body,
                                allDayEvent = allDayEvent,
                                reminder = reminder,
                                busyStatus = busyStatus
                            )
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            // Создаём событие с участниками - Exchange сам отправит приглашения
                            calendarRepo.createEvent(
                                accountId = accountId,
                                subject = subject,
                                startTime = startTime,
                                endTime = endTime,
                                location = location,
                                body = body,
                                allDayEvent = allDayEvent,
                                reminder = reminder,
                                busyStatus = busyStatus,
                                attendees = attendeeList
                            )
                        }
                    }
                    
                    isCreating = false
                    when (result) {
                        is EasResult.Success -> {
                            if (!isEditing) {
                                // Принудительная синхронизация после создания
                                withContext(Dispatchers.IO) {
                                    calendarRepo.syncCalendar(accountId)
                                }
                            }
                            val message = if (attendeeList.isNotEmpty()) {
                                "${if (isEditing) eventUpdatedText else eventCreatedText}. $invitationSentText"
                            } else {
                                if (isEditing) eventUpdatedText else eventCreatedText
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            showCreateDialog = false
                            editingEvent = null
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
                val eventsDeletedText = Strings.eventDeleted
                CalendarSelectionTopBar(
                    selectedCount = selectedEventIds.size,
                    onClearSelection = { selectedEventIds = emptySet() },
                    onDelete = {
                        deleteConfirmCount = selectedEventIds.size
                        showDeleteConfirmDialog = true
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(Strings.calendar, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(AppIcons.ArrowBack, Strings.back, tint = Color.White)
                        }
                    },
                    actions = {
                        // Переключение режима просмотра
                        IconButton(
                            onClick = {
                                selectedEventIds = emptySet() // Сбрасываем выбор при смене режима
                                viewMode = if (viewMode == CalendarViewMode.AGENDA) {
                                    CalendarViewMode.MONTH
                                } else {
                                    CalendarViewMode.AGENDA
                                }
                            }
                        ) {
                            Icon(
                                if (viewMode == CalendarViewMode.AGENDA) AppIcons.CalendarMonth else AppIcons.Menu,
                                if (viewMode == CalendarViewMode.AGENDA) Strings.month else Strings.agenda,
                                tint = Color.White
                            )
                        }
                        
                        // Кнопка синхронизации
                        val calendarSyncedText = Strings.calendarSynced
                        IconButton(
                            onClick = {
                                syncScope.launch {
                                    isSyncing = true
                                    val result = withContext(Dispatchers.IO) {
                                        calendarRepo.syncCalendar(accountId)
                                    }
                                    isSyncing = false
                                    when (result) {
                                        is EasResult.Success -> {
                                            Toast.makeText(
                                                context,
                                                "$calendarSyncedText: ${result.data}",
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
                                Icon(AppIcons.Sync, Strings.syncCalendar, tint = Color.White)
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
                    editingEvent = null
                    showCreateDialog = true 
                },
                containerColor = LocalColorTheme.current.gradientStart
            ) {
                Icon(AppIcons.Add, Strings.newEvent, tint = Color.White)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (viewMode) {
                CalendarViewMode.AGENDA -> {
                    AgendaView(
                        events = filteredEvents,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        onEventClick = { event ->
                            if (!isSelectionMode) {
                                selectedEvent = event
                            }
                        },
                        listState = listState,
                        selectedEventIds = selectedEventIds,
                        onEventSelectionChange = { eventId, selected ->
                            selectedEventIds = if (selected) {
                                selectedEventIds + eventId
                            } else {
                                selectedEventIds - eventId
                            }
                        },
                        onEventLongClick = { eventId ->
                            selectedEventIds = selectedEventIds + eventId
                        },
                        isLoading = !dataLoaded || isSyncing,
                        dateFilter = dateFilter,
                        onDateFilterChange = { dateFilter = it }
                    )
                }
                CalendarViewMode.MONTH -> {
                    // Фильтры по дате для MonthView
                    CalendarFilterChips(
                        currentFilter = dateFilter,
                        onFilterChange = { dateFilter = it }
                    )
                    MonthView(
                        events = filteredEvents,
                        selectedDate = selectedDate,
                        onDateSelected = { selectedDate = it },
                        onEventClick = { event ->
                            if (!isSelectionMode) {
                                selectedEvent = event
                            }
                        },
                        calendarRepo = calendarRepo,
                        accountId = accountId,
                        selectedEventIds = selectedEventIds,
                        onEventSelectionChange = { eventId, selected ->
                            selectedEventIds = if (selected) {
                                selectedEventIds + eventId
                            } else {
                                selectedEventIds - eventId
                            }
                        },
                        onEventLongClick = { eventId ->
                            selectedEventIds = selectedEventIds + eventId
                        }
                    )
                }
            }
        }
    }
}

/**
 * Чипы фильтров по дате для календаря
 */
@Composable
private fun CalendarFilterChips(
    currentFilter: CalendarDateFilter,
    onFilterChange: (CalendarDateFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = currentFilter == CalendarDateFilter.ALL,
            onClick = { onFilterChange(CalendarDateFilter.ALL) },
            label = { Text(Strings.allDates) }
        )
        FilterChip(
            selected = currentFilter == CalendarDateFilter.TODAY,
            onClick = { onFilterChange(CalendarDateFilter.TODAY) },
            label = { Text(Strings.today) },
            leadingIcon = if (currentFilter == CalendarDateFilter.TODAY) {
                { Icon(AppIcons.Schedule, null, Modifier.size(16.dp)) }
            } else null
        )
        FilterChip(
            selected = currentFilter == CalendarDateFilter.WEEK,
            onClick = { onFilterChange(CalendarDateFilter.WEEK) },
            label = { Text(Strings.week) }
        )
        FilterChip(
            selected = currentFilter == CalendarDateFilter.MONTH,
            onClick = { onFilterChange(CalendarDateFilter.MONTH) },
            label = { Text(Strings.month) }
        )
    }
}

enum class CalendarViewMode {
    AGENDA, MONTH
}


@Composable
private fun AgendaView(
    events: List<CalendarEventEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onEventClick: (CalendarEventEntity) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedEventIds: Set<String> = emptySet(),
    onEventSelectionChange: (String, Boolean) -> Unit = { _, _ -> },
    onEventLongClick: (String) -> Unit = {},
    isLoading: Boolean = false,
    dateFilter: CalendarDateFilter = CalendarDateFilter.ALL,
    onDateFilterChange: (CalendarDateFilter) -> Unit = {}
) {
    val isSelectionMode = selectedEventIds.isNotEmpty()
    Column {
        // Поле поиска (на всю ширину)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(Strings.searchEvents) },
            leadingIcon = { Icon(AppIcons.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(AppIcons.Clear, Strings.clear)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        
        // Фильтры по дате (под полем поиска)
        CalendarFilterChips(
            currentFilter = dateFilter,
            onFilterChange = onDateFilterChange
        )
        
        // Счётчик
        Text(
            text = "${Strings.total}: ${events.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            AppIcons.Calendar,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = Strings.noEvents,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Группировка по датам (новые сверху)
            val groupedEvents = remember(events) {
                events.groupBy { event ->
                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = event.startTime
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    calendar.time
                }.toSortedMap(compareByDescending { it })
            }
            
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedEvents.forEach { (date, dayEvents) ->
                    item(key = "header_${date.time}") {
                        DateHeader(date = date)
                    }
                    
                    items(dayEvents, key = { it.id }) { event ->
                        EventCard(
                            event = event,
                            isSelected = event.id in selectedEventIds,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    onEventSelectionChange(event.id, event.id !in selectedEventIds)
                                } else {
                                    onEventClick(event)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    onEventLongClick(event.id)
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

@Composable
private fun MonthView(
    events: List<CalendarEventEntity>,
    selectedDate: Date,
    onDateSelected: (Date) -> Unit,
    onEventClick: (CalendarEventEntity) -> Unit,
    calendarRepo: CalendarRepository,
    accountId: Long,
    selectedEventIds: Set<String> = emptySet(),
    onEventSelectionChange: (String, Boolean) -> Unit = { _, _ -> },
    onEventLongClick: (String) -> Unit = {}
) {
    val isSelectionMode = selectedEventIds.isNotEmpty()
    val calendar = Calendar.getInstance()
    calendar.time = selectedDate
    
    var currentMonth by remember(selectedDate) { mutableStateOf(calendar.get(Calendar.MONTH)) }
    var currentYear by remember(selectedDate) { mutableStateOf(calendar.get(Calendar.YEAR)) }
    var showYearView by remember { mutableStateOf(false) }
    
    // События для выбранного дня
    val dayEvents by calendarRepo.getEventsForDay(accountId, selectedDate).collectAsState(initial = emptyList())
    
    if (showYearView) {
        YearView(
            year = currentYear,
            events = events,
            selectedDate = selectedDate,
            onMonthSelected = { month, year ->
                currentMonth = month
                currentYear = year
                showYearView = false
            },
            onYearChange = { newYear -> currentYear = newYear },
            onBack = { showYearView = false }
        )
    } else {
        Column {
            // Заголовок месяца (кликабельный)
            MonthHeader(
                month = currentMonth,
                year = currentYear,
                onPreviousMonth = {
                    calendar.set(Calendar.MONTH, currentMonth - 1)
                    currentMonth = calendar.get(Calendar.MONTH)
                    currentYear = calendar.get(Calendar.YEAR)
                },
                onNextMonth = {
                    calendar.set(Calendar.MONTH, currentMonth + 1)
                    currentMonth = calendar.get(Calendar.MONTH)
                    currentYear = calendar.get(Calendar.YEAR)
                },
                onTitleClick = { showYearView = true }
            )
            
            // Сетка календаря
            CalendarGrid(
                month = currentMonth,
                year = currentYear,
                selectedDate = selectedDate,
                events = events,
                onDateSelected = onDateSelected
            )
            
            // События выбранного дня
            if (dayEvents.isNotEmpty()) {
                Text(
                    text = remember(selectedDate) { SimpleDateFormat("d MMMM", Locale.getDefault()).format(selectedDate) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                )
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dayEvents, key = { it.id }) { event ->
                        EventCard(
                            event = event,
                            isSelected = event.id in selectedEventIds,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    onEventSelectionChange(event.id, event.id !in selectedEventIds)
                                } else {
                                    onEventClick(event)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    onEventLongClick(event.id)
                                }
                            }
                        )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = Strings.noEvents,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        }
    }
}


@Composable
private fun DateHeader(date: Date) {
    val dateFormat = remember { SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()) }
    val today = remember { Date() }
    val isToday = remember(date) {
        val cal1 = Calendar.getInstance().apply { time = date }
        val cal2 = Calendar.getInstance().apply { time = today }
        cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
        cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
    
    Text(
        text = if (isToday) Strings.today else dateFormat.format(date),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun EventCard(
    event: CalendarEventEntity,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isPastEvent = remember(event.endTime) { event.endTime < System.currentTimeMillis() }
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else if (isPastEvent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "eventBg"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Чекбокс выбора в режиме множественного выбора
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            // Цветная полоска статуса
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(
                        color = if (isPastEvent) {
                            Color.Gray
                        } else {
                            when (event.busyStatus) {
                                0 -> Color(0xFF4CAF50) // Free - Green
                                1 -> Color(0xFFFF9800) // Tentative - Orange
                                2 -> Color(0xFFF44336) // Busy - Red
                                3 -> Color(0xFF9C27B0) // Out of Office - Purple
                                else -> Color(0xFF2196F3) // Default - Blue
                            }
                        },
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.subject.ifBlank { Strings.noTitle },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isPastEvent) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPastEvent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            AppIcons.CheckCircle,
                            contentDescription = Strings.completed,
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFF4CAF50)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = if (event.allDayEvent) {
                        Strings.allDay
                    } else {
                        "${timeFormat.format(Date(event.startTime))} - ${timeFormat.format(Date(event.endTime))}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isPastEvent) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary
                )
                
                if (event.location.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            AppIcons.Business,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = event.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            
            if (event.isRecurring) {
                Icon(
                    AppIcons.Refresh,
                    contentDescription = Strings.recurringEvent,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: Int,
    year: Int,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTitleClick: () -> Unit = {}
) {
    val monthNames = Strings.monthNames
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(AppIcons.ChevronLeft, Strings.previousMonth)
        }
        
        Text(
            text = "${monthNames[month]} $year",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onTitleClick() }
        )
        
        IconButton(onClick = onNextMonth) {
            Icon(AppIcons.ChevronRight, Strings.nextMonth)
        }
    }
}


@Composable
private fun CalendarGrid(
    month: Int,
    year: Int,
    selectedDate: Date,
    events: List<CalendarEventEntity>,
    onDateSelected: (Date) -> Unit
) {
    val calendar = Calendar.getInstance()
    calendar.set(year, month, 1)
    
    // Первый день месяца (0 = Воскресенье, нужно сдвинуть для Пн-Вс)
    val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val firstDayOffset = if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - 2
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    
    val dayNames = Strings.dayNamesShort
    
    Column {
        // Заголовки дней недели
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            dayNames.forEach { dayName ->
                Text(
                    text = dayName,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Сетка дней
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .height(240.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Пустые ячейки до первого дня месяца
            items(firstDayOffset) {
                Spacer(modifier = Modifier.height(40.dp))
            }
            
            // Дни месяца
            items(daysInMonth) { dayIndex ->
                val day = dayIndex + 1
                calendar.set(year, month, day)
                val date = calendar.time
                
                val hasEvents = events.any { event ->
                    val eventCalendar = Calendar.getInstance()
                    eventCalendar.timeInMillis = event.startTime
                    eventCalendar.get(Calendar.YEAR) == year &&
                    eventCalendar.get(Calendar.MONTH) == month &&
                    eventCalendar.get(Calendar.DAY_OF_MONTH) == day
                }
                
                val isSelected = remember(selectedDate, date) {
                    val selectedCal = Calendar.getInstance().apply { time = selectedDate }
                    val dateCal = Calendar.getInstance().apply { time = date }
                    selectedCal.get(Calendar.YEAR) == dateCal.get(Calendar.YEAR) &&
                    selectedCal.get(Calendar.MONTH) == dateCal.get(Calendar.MONTH) &&
                    selectedCal.get(Calendar.DAY_OF_MONTH) == dateCal.get(Calendar.DAY_OF_MONTH)
                }
                
                val isToday = remember(date) {
                    val today = Calendar.getInstance()
                    val dateCal = Calendar.getInstance().apply { time = date }
                    today.get(Calendar.YEAR) == dateCal.get(Calendar.YEAR) &&
                    today.get(Calendar.DAY_OF_YEAR) == dateCal.get(Calendar.DAY_OF_YEAR)
                }
                
                DayCell(
                    day = day,
                    isSelected = isSelected,
                    isToday = isToday,
                    hasEvents = hasEvents,
                    onClick = { onDateSelected(date) }
                )
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isToday && !isSelected) 1.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
            )
            
            if (hasEvents) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            shape = CircleShape
                        )
                )
            }
        }
    }
}


@Composable
private fun EventDetailDialog(
    event: CalendarEventEntity,
    calendarRepo: CalendarRepository,
    currentUserEmail: String = "",
    onDismiss: () -> Unit,
    onComposeClick: (String) -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val attendees = remember(event.attendees) { calendarRepo.parseAttendeesFromJson(event.attendees) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    
    // Очищаем body от дублированных строк
    val cleanBody = remember(event.body) {
        // Убираем HTML теги и нормализуем
        var textOnly = event.body
            .replace(HTML_TAG_REGEX, "\n")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\r", "")
            .replace("\u00A0", " ") // non-breaking space
            .replace("\t", " ")
        
        // Убираем дублированный контент после разделителя *~*~*
        val separatorIndex = textOnly.indexOf("*~*~*")
        if (separatorIndex > 0) {
            // Берём только часть после разделителя (основной контент)
            val afterSeparator = textOnly.substring(separatorIndex)
            val separatorEnd = afterSeparator.indexOf("\n")
            if (separatorEnd > 0) {
                textOnly = afterSeparator.substring(separatorEnd + 1)
            }
        }
        
        val lines = textOnly.split("\n")
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (line in lines) {
            // Нормализуем: убираем все пробелы и приводим к lowercase для сравнения
            val normalized = line.trim().replace("\\s+".toRegex(), " ")
            if (normalized.isBlank()) continue
            val key = normalized.lowercase().replace(" ", "")
            if (seen.add(key)) {
                result.add(normalized)
            }
        }
        result.joinToString("\n")
    }
    
    // Извлекаем email из строки организатора
    val organizerEmail = remember(event.organizer) {
        EMAIL_REGEX.find(event.organizer)?.value ?: ""
    }
    
    // Проверяем что я не организатор (тогда показываем кнопки ответа)
    val isOrganizer = remember(organizerEmail, currentUserEmail) {
        organizerEmail.isNotBlank() && currentUserEmail.isNotBlank() &&
        organizerEmail.equals(currentUserEmail, ignoreCase = true)
    }
    
    // Проверяем есть ли что показывать в расширенном виде
    val hasMoreContent = cleanBody.isNotBlank() || event.organizer.isNotBlank() || attendees.isNotEmpty()
    
    // Диалог подтверждения удаления
    if (showDeleteConfirm) {
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteEvent) },
            text = { Text(Strings.deleteEventConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.GradientDialogButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    },
                    text = Strings.delete
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = event.subject.ifBlank { Strings.noSubject },
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            // Не добавляем verticalScroll - ScaledAlertDialog уже имеет скролл
            Column {
                // Дата/время
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(
                        AppIcons.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (event.allDayEvent) {
                            "${dateFormat.format(Date(event.startTime))} - ${Strings.allDay}"
                        } else {
                            "${dateFormat.format(Date(event.startTime))} ${timeFormat.format(Date(event.startTime))} - ${timeFormat.format(Date(event.endTime))}"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Место
                if (event.location.isNotBlank()) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    val isUrl = event.location.startsWith("http://") || event.location.startsWith("https://")
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            if (isUrl) AppIcons.OpenInNew else AppIcons.Business,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = event.location,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isUrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = if (isUrl) {
                                Modifier.clickable { uriHandler.openUri(event.location) }
                            } else Modifier
                        )
                    }
                }
                
                // Краткое описание (свёрнутый вид)
                if (!expanded && cleanBody.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = cleanBody.take(200) + if (cleanBody.length > 200) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                // Кнопка "Показать ещё"
                if (!expanded && hasMoreContent) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { expanded = true }) {
                            Text(Strings.showMore)
                            Icon(
                                AppIcons.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                // Расширенный вид
                if (expanded) {
                    // Кнопка "Свернуть" вверху для удобства
                    TextButton(
                        onClick = { expanded = false },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(
                            AppIcons.ExpandLess,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(Strings.showLess)
                    }
                    
                    // Организатор
                    if (event.organizer.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                AppIcons.Person,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = Strings.organizer,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = event.organizer.replace(HTML_TAG_REGEX, ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (organizerEmail.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = if (organizerEmail.isNotBlank()) {
                                        Modifier.clickable { onComposeClick(organizerEmail) }
                                    } else Modifier
                                )
                            }
                        }
                        
                    }
                    
                    // Участники
                    if (attendees.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                AppIcons.People,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = Strings.attendees,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                attendees.forEach { attendee ->
                                    val nameOrEmail = if (attendee.name.isNotBlank()) attendee.name else attendee.email
                                    // Статус участника: 0=Unknown, 2=Tentative, 3=Accepted, 4=Declined, 5=Not responded
                                    val statusText = when (attendee.status) {
                                        2 -> " (${Strings.statusTentative})"
                                        3 -> " (${Strings.statusAccepted})"
                                        4 -> " (${Strings.statusDeclined})"
                                        5 -> " (${Strings.statusNotResponded})"
                                        else -> ""
                                    }
                                    val displayText = "$nameOrEmail$statusText"
                                    Text(
                                        text = displayText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (attendee.email.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = if (attendee.email.isNotBlank()) {
                                            Modifier.clickable { onComposeClick(attendee.email) }
                                        } else Modifier
                                    )
                                }
                            }
                        }
                    }
                    
                    // Полное описание с изображениями и ссылками
                    if (cleanBody.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        SelectionContainer {
                            com.dedovmosol.iwomail.ui.components.RichTextWithImages(
                                htmlContent = cleanBody,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Справа: Удалить (только иконка с обводкой)
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
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
        },
        dismissButton = {
            // Слева: Редактировать (только иконка с обводкой, цвет из темы)
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
    )
}

/**
 * Годовой вид календаря
 */
@Composable
private fun YearView(
    year: Int,
    events: List<CalendarEventEntity>,
    selectedDate: Date,
    onMonthSelected: (Int, Int) -> Unit,
    onYearChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    val monthNames = Strings.monthNamesShort
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Заголовок с годом
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onYearChange(year - 1) }) {
                Icon(AppIcons.ChevronLeft, Strings.previousYear)
            }
            
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }
            )
            
            IconButton(onClick = { onYearChange(year + 1) }) {
                Icon(AppIcons.ChevronRight, Strings.nextYear)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Сетка месяцев 4x3
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(12) { monthIndex ->
                MiniMonthCard(
                    month = monthIndex,
                    year = year,
                    monthName = monthNames[monthIndex],
                    events = events,
                    selectedDate = selectedDate,
                    onClick = { onMonthSelected(monthIndex, year) }
                )
            }
        }
    }
}

@Composable
private fun MiniMonthCard(
    month: Int,
    year: Int,
    monthName: String,
    events: List<CalendarEventEntity>,
    selectedDate: Date,
    onClick: () -> Unit
) {
    val calendar = Calendar.getInstance()
    calendar.set(year, month, 1)
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val firstDayOffset = if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - 2
    
    // Дни с событиями в этом месяце
    val daysWithEvents = remember(events, month, year) {
        events.filter { event ->
            val eventCal = Calendar.getInstance()
            eventCal.timeInMillis = event.startTime
            eventCal.get(Calendar.YEAR) == year && eventCal.get(Calendar.MONTH) == month
        }.map { event ->
            val eventCal = Calendar.getInstance()
            eventCal.timeInMillis = event.startTime
            eventCal.get(Calendar.DAY_OF_MONTH)
        }.toSet()
    }
    
    // Текущий день
    val today = Calendar.getInstance()
    val isCurrentMonth = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month
    val currentDay = today.get(Calendar.DAY_OF_MONTH)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Название месяца
            Text(
                text = monthName.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCurrentMonth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Дни недели
            Row(modifier = Modifier.fillMaxWidth()) {
                Strings.dayNamesMin.forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 8.sp
                    )
                }
            }
            
            // Дни месяца
            var dayCounter = 1
            for (week in 0..5) {
                if (dayCounter > daysInMonth) break
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (dayOfWeek in 0..6) {
                        val cellIndex = week * 7 + dayOfWeek
                        if (cellIndex < firstDayOffset || dayCounter > daysInMonth) {
                            Text(
                                text = "",
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                fontSize = 8.sp
                            )
                        } else {
                            val day = dayCounter
                            val hasEvent = daysWithEvents.contains(day)
                            val isToday = isCurrentMonth && day == currentDay
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(1.dp)
                                    .then(
                                        if (hasEvent) {
                                            Modifier.border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            )
                                        } else Modifier
                                    )
                                    .then(
                                        if (isToday) {
                                            Modifier.background(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                shape = CircleShape
                                            )
                                        } else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = day.toString(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    fontWeight = if (isToday || hasEvent) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isToday -> MaterialTheme.colorScheme.primary
                                        hasEvent -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                            dayCounter++
                        }
                    }
                }
            }
        }
    }
}

// Паттерны для парсинга ссылок (компилируются один раз)
private val markdownImageLinkPattern = Regex("\\[([^\\]]+)\\]<([^>]+)>")
private val hrefPattern = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)
private val urlPattern = Regex("https?://[\\w\\-.]+\\.[a-z]{2,}[\\w\\-._~:/?#\\[\\]@!%&'()*+,;=]*", RegexOption.IGNORE_CASE)
private val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")

/**
 * Текст с кликабельными ссылками (URL и HTML href) и картинками
 */
@Composable
private fun ClickableHtmlText(
    text: String,
    style: androidx.compose.ui.text.TextStyle
) {
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val couldNotOpenLinkText = Strings.couldNotOpenLink
    
    // Типы элементов: 0=text, 1=link, 2=image, 3=clickable image
    data class Part(val content: String, val type: Int, val url: String = "", val linkUrl: String = "")
    val parts = mutableListOf<Part>()
    
    // Собираем все элементы
    data class Element(val start: Int, val end: Int, val imageUrl: String, val linkUrl: String, val display: String, val isImage: Boolean, val isClickableImage: Boolean = false)
    val elements = mutableListOf<Element>()
    
    // Сначала markdown формат [image]<link>
    markdownImageLinkPattern.findAll(text).forEach { match ->
        val imgUrl = match.groupValues[1]
        val lnkUrl = match.groupValues[2]
        val isImg = imageExtensions.any { imgUrl.lowercase().contains(it) }
        elements.add(Element(match.range.first, match.range.last + 1, imgUrl, lnkUrl, imgUrl, isImg, isImg))
    }
    
    // HTML ссылки
    hrefPattern.findAll(text).forEach { match ->
        val overlaps = elements.any { it.start <= match.range.first && it.end >= match.range.last }
        if (!overlaps) {
            elements.add(Element(match.range.first, match.range.last + 1, match.groupValues[1], match.groupValues[1], match.groupValues[2], false))
        }
    }
    
    // Обычные URL
    urlPattern.findAll(text).forEach { match ->
        val overlaps = elements.any { it.start <= match.range.first && it.end >= match.range.last }
        if (!overlaps) {
            val isImg = imageExtensions.any { match.value.lowercase().contains(it) }
            elements.add(Element(match.range.first, match.range.last + 1, match.value, match.value, match.value, isImg))
        }
    }
    
    elements.sortBy { it.start }
    
    // Разбиваем на части
    var lastIndex = 0
    elements.forEach { elem ->
        if (elem.start > lastIndex) {
            parts.add(Part(text.substring(lastIndex, elem.start), 0))
        }
        when {
            elem.isClickableImage -> parts.add(Part(elem.imageUrl, 3, elem.imageUrl, elem.linkUrl))
            elem.isImage -> parts.add(Part(elem.imageUrl, 2, elem.imageUrl))
            else -> parts.add(Part(elem.display, 1, elem.linkUrl))
        }
        lastIndex = elem.end
    }
    if (lastIndex < text.length) {
        parts.add(Part(text.substring(lastIndex), 0))
    }
    
    Column {
        parts.forEach { part ->
            when (part.type) {
                0 -> if (part.content.isNotBlank()) {
                    Text(text = part.content, style = style, color = onSurfaceColor)
                }
                1 -> Text(
                    text = part.content,
                    style = style.copy(color = primaryColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    modifier = Modifier.clickable {
                        try { uriHandler.openUri(part.url) }
                        catch (e: Exception) { Toast.makeText(context, couldNotOpenLinkText, Toast.LENGTH_SHORT).show() }
                    }
                )
                2 -> NetworkImage(url = part.url, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                3 -> NetworkImage(
                    url = part.url,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable {
                            try { uriHandler.openUri(part.linkUrl) }
                            catch (e: Exception) { Toast.makeText(context, couldNotOpenLinkText, Toast.LENGTH_SHORT).show() }
                        }
                )
            }
        }
    }
}

@Composable
private fun NetworkImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf(false) }
    
    // Освобождаем Bitmap при выходе из композиции
    DisposableEffect(url) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }
    
    LaunchedEffect(url) {
        isLoading = true
        error = false
        try {
            bitmap = withContext(Dispatchers.IO) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.connect()
                    android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                } finally {
                    conn?.disconnect()
                }
            }
        } catch (e: Exception) { error = true }
        isLoading = false
    }
    
    when {
        isLoading -> Box(modifier.height(100.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        !error && bitmap != null -> androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
        )
    }
}


/**
 * Диалог создания/редактирования события календаря
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEventDialog(
    event: CalendarEventEntity?,
    initialDate: Date,
    isCreating: Boolean,
    accountId: Long,
    ownEmail: String,
    onDismiss: () -> Unit,
    onSave: (
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        attendees: String
    ) -> Unit
) {
    val context = LocalContext.current
    val isEditing = event != null
    
    // Получаем строки заранее для использования в onClick
    val invalidDateTimeText = Strings.invalidDateTime
    val endBeforeStartText = Strings.endBeforeStart
    
    // Состояния полей
    var subject by rememberSaveable { mutableStateOf(event?.subject ?: "") }
    var location by rememberSaveable { mutableStateOf(event?.location ?: "") }
    var body by rememberSaveable { mutableStateOf(event?.body ?: "") }
    var allDayEvent by rememberSaveable { mutableStateOf(event?.allDayEvent ?: false) }
    var reminder by rememberSaveable { mutableStateOf(event?.reminder ?: 15) }
    var busyStatus by rememberSaveable { mutableStateOf(event?.busyStatus ?: 2) }
    var attendees by rememberSaveable { mutableStateOf("") }
    
    // Диалог выбора контактов
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    
    // Текстовые поля для дат и времени
    var startDateText by rememberSaveable { mutableStateOf("") }
    var startTimeText by rememberSaveable { mutableStateOf("") }
    var endDateText by rememberSaveable { mutableStateOf("") }
    var endTimeText by rememberSaveable { mutableStateOf("") }
    
    var showReminderMenu by rememberSaveable { mutableStateOf(false) }
    var showStatusMenu by rememberSaveable { mutableStateOf(false) }
    
    // Состояния для DatePicker / TimePicker диалогов
    var showStartDatePicker by rememberSaveable { mutableStateOf(false) }
    var showStartTimePicker by rememberSaveable { mutableStateOf(false) }
    var showEndDatePicker by rememberSaveable { mutableStateOf(false) }
    var showEndTimePicker by rememberSaveable { mutableStateOf(false) }
    
    // Инициализация текстовых полей из существующих дат
    LaunchedEffect(event, initialDate) {
        // КРИТИЧНО: НЕ устанавливаем UTC, т.к. пользователь работает в LOCAL timezone
        // БД хранит UTC, но отображаем в LOCAL
        
        if (event != null) {
            startDateText = PARSE_DATE_FORMAT.format(Date(event.startTime))
            startTimeText = PARSE_TIME_FORMAT.format(Date(event.startTime))
            endDateText = PARSE_DATE_FORMAT.format(Date(event.endTime))
            endTimeText = PARSE_TIME_FORMAT.format(Date(event.endTime))
        } else {
            // Используем текущее время, округлённое до следующего часа
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            
            startDateText = PARSE_DATE_FORMAT.format(calendar.time)
            startTimeText = PARSE_TIME_FORMAT.format(calendar.time)
            
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            endDateText = PARSE_DATE_FORMAT.format(calendar.time)
            endTimeText = PARSE_TIME_FORMAT.format(calendar.time)
        }
    }
    
    // Валидация
    val isValid = subject.isNotBlank()
    
    val lazyListState = rememberLazyListState()
    
    com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        scrollable = false, // Отключаем автоскролл диалога
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .widthIn(max = 560.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isEditing) Strings.editEvent else Strings.newEvent,
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
                        label = { Text(Strings.eventTitle) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = subject.isBlank()
                    )
                }
                
                item {
                    
                    // Весь день
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(Strings.allDay)
                        Switch(
                            checked = allDayEvent,
                            onCheckedChange = { allDayEvent = it }
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
                        if (!allDayEvent) {
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
                        }
                        if (startDateText.isNotEmpty() || startTimeText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    startDateText = ""
                                    startTimeText = ""
                                }
                            ) {
                                Icon(AppIcons.Clear, Strings.clear)
                            }
                        }
                    }
                }
                
                item {
                    // Дата окончания
                    Text(
                        text = Strings.endDate,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = endDateText,
                            onValueChange = { 
                                val filtered = it.filter { c -> c.isDigit() || c == '.' }
                                if (filtered.length <= 10) {
                                    endDateText = filtered
                                }
                            },
                            placeholder = { Text(Strings.datePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showEndDatePicker = true }) {
                                    Icon(AppIcons.Calendar, null, modifier = Modifier.size(18.dp))
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        if (!allDayEvent) {
                            OutlinedTextField(
                                value = endTimeText,
                                onValueChange = { newValue ->
                                    val digits = newValue.filter { it.isDigit() }.take(4)
                                    endTimeText = when {
                                        digits.length <= 2 -> digits
                                        else -> "${digits.substring(0, 2)}:${digits.substring(2)}"
                                    }
                                },
                            placeholder = { Text(Strings.timePlaceholder, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f).height(48.dp),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { showEndTimePicker = true }) {
                                        Icon(AppIcons.Schedule, null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                        }
                        if (endDateText.isNotEmpty() || endTimeText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    endDateText = ""
                                    endTimeText = ""
                                }
                            ) {
                                Icon(AppIcons.Clear, Strings.clear)
                            }
                        }
                    }
                }
                
                item {
                    // Место
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text(Strings.eventLocation) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                item {
                    // Пригласить участников
                    OutlinedTextField(
                        value = attendees,
                        onValueChange = { attendees = it },
                        label = { Text(Strings.inviteAttendees) },
                        placeholder = { Text(Strings.attendeesHint) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 2,
                        trailingIcon = {
                            IconButton(onClick = { showContactPicker = true }) {
                                Icon(AppIcons.PersonAdd, contentDescription = null)
                            }
                        }
                    )
                }
                
                item {
                    // Напоминание
                    Box {
                        OutlinedTextField(
                            value = when (reminder) {
                                0 -> Strings.noReminder
                                5 -> Strings.minutes5
                                15 -> Strings.minutes15
                                30 -> Strings.minutes30
                                60 -> Strings.hour1
                                120 -> Strings.hours2
                                1440 -> Strings.day1
                                else -> "$reminder мин"
                            },
                            onValueChange = {},
                            label = { Text(Strings.reminder) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showReminderMenu = true },
                            readOnly = true,
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        
                        DropdownMenu(
                            expanded = showReminderMenu,
                            onDismissRequest = { showReminderMenu = false }
                        ) {
                            listOf(
                                0 to Strings.noReminder,
                                5 to Strings.minutes5,
                                15 to Strings.minutes15,
                                30 to Strings.minutes30,
                                60 to Strings.hour1,
                                120 to Strings.hours2,
                                1440 to Strings.day1
                            ).forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        reminder = value
                                        showReminderMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                item {
                    // Статус занятости
                    Box {
                        OutlinedTextField(
                            value = when (busyStatus) {
                                0 -> Strings.statusFree
                                1 -> Strings.statusTentative
                                2 -> Strings.statusBusy
                                3 -> Strings.statusOof
                                else -> Strings.statusBusy
                            },
                            onValueChange = {},
                            label = { Text(Strings.busyStatus) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showStatusMenu = true },
                            readOnly = true,
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        
                        DropdownMenu(
                            expanded = showStatusMenu,
                            onDismissRequest = { showStatusMenu = false }
                        ) {
                            listOf(
                                0 to Strings.statusFree,
                                1 to Strings.statusTentative,
                                2 to Strings.statusBusy,
                                3 to Strings.statusOof
                            ).forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        busyStatus = value
                                        showStatusMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                item {
                    // Описание
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text(Strings.eventDescription) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        maxLines = 5
                    )
                }
            }
            
            LazyColumnScrollbar(lazyListState)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        // Парсинг дат и времени
                        val startTime = if (allDayEvent) {
                            parseDateTime(startDateText, "00:00")
                        } else {
                            parseDateTime(startDateText, startTimeText)
                        }
                        
                        val endTime = if (allDayEvent) {
                            parseDateTime(endDateText, "23:59")
                        } else {
                            parseDateTime(endDateText, endTimeText)
                        }
                        
                        if (startTime <= 0 || endTime <= 0) {
                            Toast.makeText(context, invalidDateTimeText, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        
                        if (endTime <= startTime) {
                            Toast.makeText(context, endBeforeStartText, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        
                        onSave(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, attendees)
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
    
    // Диалог выбора контактов
    if (showContactPicker) {
        val database = remember { com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context) }
        com.dedovmosol.iwomail.ui.components.ContactPickerDialog(
            accountId = accountId,
            database = database,
            ownEmail = ownEmail,
            onDismiss = { showContactPicker = false },
            onContactsSelected = { selectedEmails ->
                if (selectedEmails.isNotEmpty()) {
                    attendees = if (attendees.isBlank()) {
                        selectedEmails.joinToString(", ")
                    } else {
                        attendees + ", " + selectedEmails.joinToString(", ")
                    }
                }
                showContactPicker = false
            }
        )
    }
    
    // DatePicker и TimePicker диалоги
    val pickerDateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try {
                pickerDateFormat.parse(startDateText)?.time
            } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        startDateText = pickerDateFormat.format(Date(millis))
                    }
                    showStartDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text(Strings.cancel) }
            }
        ) { DatePicker(state = datePickerState) }
    }
    
    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = try {
                pickerDateFormat.parse(endDateText)?.time
            } catch (_: Exception) { null }
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        endDateText = pickerDateFormat.format(Date(millis))
                    }
                    showEndDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text(Strings.cancel) }
            }
        ) { DatePicker(state = datePickerState) }
    }
    
    if (showStartTimePicker) {
        val initHour = try { startTimeText.split(":")[0].toInt() } catch (_: Exception) { 9 }
        val initMinute = try { startTimeText.split(":")[1].toInt() } catch (_: Exception) { 0 }
        val timePickerState = rememberTimePickerState(initialHour = initHour, initialMinute = initMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showStartTimePicker = false }
        ) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    TimePicker(state = timePickerState)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showStartTimePicker = false }) { Text(Strings.cancel) }
                        TextButton(onClick = {
                            startTimeText = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                            showStartTimePicker = false
                        }) { Text(Strings.ok) }
                    }
                }
            }
        }
    }
    
    if (showEndTimePicker) {
        val initHour = try { endTimeText.split(":")[0].toInt() } catch (_: Exception) { 10 }
        val initMinute = try { endTimeText.split(":")[1].toInt() } catch (_: Exception) { 0 }
        val timePickerState = rememberTimePickerState(initialHour = initHour, initialMinute = initMinute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showEndTimePicker = false }
        ) {
            Surface(shape = RoundedCornerShape(28.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    TimePicker(state = timePickerState)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showEndTimePicker = false }) { Text(Strings.cancel) }
                        TextButton(onClick = {
                            endTimeText = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                            showEndTimePicker = false
                        }) { Text(Strings.ok) }
                    }
                }
            }
        }
    }
}

/**
 * Парсит дату и время из текстовых полей
 * @param dateText дата в формате "дд.мм.гггг"
 * @param timeText время в формате "чч:мм"
 * @return timestamp в миллисекундах или 0 при ошибке
 */
private fun parseDateTime(dateText: String, timeText: String): Long {
    return try {
        // НЕ устанавливаем UTC - SimpleDateFormat.parse() автоматически возвращает UTC timestamp
        val dateTimeString = "$dateText $timeText"
        PARSE_DATE_TIME_FORMAT.parse(dateTimeString)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
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
            IconButton(onClick = onDelete) {
                Icon(AppIcons.Delete, Strings.delete, tint = Color.White)
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

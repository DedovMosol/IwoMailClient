package com.iwo.mailclient.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import com.iwo.mailclient.data.database.CalendarEventEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.CalendarRepository
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.ui.theme.AppIcons
import com.iwo.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// Предкомпилированные regex для производительности
private val HTML_TAG_REGEX = Regex("<[^>]*>")
private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBackClick: () -> Unit,
    onComposeClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calendarRepo = remember { CalendarRepository(context) }
    val accountRepo = remember { AccountRepository(context) }
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L

    val events by remember(accountId) { calendarRepo.getEvents(accountId) }.collectAsState(initial = emptyList())
    
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(CalendarViewMode.AGENDA) }
    var selectedDate by remember { mutableStateOf(Date()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    
    // Состояние списка для автоскролла
    val listState = rememberLazyListState()
    
    // Фильтрация по поиску
    val filteredEvents = remember(events, searchQuery) {
        if (searchQuery.isBlank()) {
            events
        } else {
            events.filter { event ->
                event.subject.contains(searchQuery, ignoreCase = true) ||
                event.location.contains(searchQuery, ignoreCase = true) ||
                event.body.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    // Диалог просмотра события
    selectedEvent?.let { event ->
        val eventDeletedText = Strings.eventDeleted
        EventDetailDialog(
            event = event,
            calendarRepo = calendarRepo,
            onDismiss = { selectedEvent = null },
            onComposeClick = onComposeClick,
            onEditClick = { 
                editingEvent = event
                selectedEvent = null
                showCreateDialog = true
            },
            onDeleteClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        calendarRepo.deleteEvent(event)
                    }
                    when (result) {
                        is EasResult.Success -> {
                            Toast.makeText(context, eventDeletedText, Toast.LENGTH_SHORT).show()
                        }
                        is EasResult.Error -> {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                    selectedEvent = null
                }
            }
        )
    }
    
    // Диалог создания/редактирования события
    if (showCreateDialog) {
        val eventUpdatedText = Strings.eventUpdated
        val eventCreatedText = Strings.eventCreated
        val isEditing = editingEvent != null
        CreateEventDialog(
            event = editingEvent,
            initialDate = selectedDate,
            isCreating = isCreating,
            onDismiss = { 
                showCreateDialog = false
                editingEvent = null
            },
            onSave = { subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus ->
                scope.launch {
                    isCreating = true
                    val result = if (editingEvent != null) {
                        withContext(Dispatchers.IO) {
                            calendarRepo.updateEvent(
                                event = editingEvent!!,
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
                            calendarRepo.createEvent(
                                accountId = accountId,
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
                    }
                    isCreating = false
                    when (result) {
                        is EasResult.Success -> {
                            Toast.makeText(
                                context,
                                if (isEditing) eventUpdatedText else eventCreatedText,
                                Toast.LENGTH_SHORT
                            ).show()
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
        topBar = {
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
                            scope.launch {
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
        },
        floatingActionButton = {
            FloatingActionButton(
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
                        onEventClick = { selectedEvent = it },
                        listState = listState
                    )
                }
                CalendarViewMode.MONTH -> {
                    MonthView(
                        events = events,
                        selectedDate = selectedDate,
                        onDateSelected = { selectedDate = it },
                        onEventClick = { selectedEvent = it },
                        calendarRepo = calendarRepo,
                        accountId = accountId
                    )
                }
            }
        }
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
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    Column {
        // Поле поиска
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
        } else {
            // Группировка по датам
            val groupedEvents = events.groupBy { event ->
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = event.startTime
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.time
            }.toSortedMap()
            
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
                            onClick = { onEventClick(event) }
                        )
                    }
                }
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
    accountId: Long
) {
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
                    text = SimpleDateFormat("d MMMM", Locale.getDefault()).format(selectedDate),
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
                            onClick = { onEventClick(event) }
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
    onClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isPastEvent = remember(event.endTime) { event.endTime < System.currentTimeMillis() }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPastEvent) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
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
    onDismiss: () -> Unit,
    onComposeClick: (String) -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val attendees = remember(event.attendees) { calendarRepo.parseAttendeesFromJson(event.attendees) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    // Извлекаем email из строки организатора
    val organizerEmail = remember(event.organizer) {
        EMAIL_REGEX.find(event.organizer)?.value ?: ""
    }
    
    // Диалог подтверждения удаления
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(Strings.deleteEvent) },
            text = { Text(Strings.deleteEventConfirm) },
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = event.subject.ifBlank { Strings.noSubject },
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
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
                
                // Описание
                if (event.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.body.replace(HTML_TAG_REGEX, "").take(200) + if (event.body.length > 200) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
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
    onDismiss: () -> Unit,
    onSave: (
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int
    ) -> Unit
) {
    val isEditing = event != null
    
    // Состояния полей
    var subject by rememberSaveable { mutableStateOf(event?.subject ?: "") }
    var location by rememberSaveable { mutableStateOf(event?.location ?: "") }
    var body by rememberSaveable { mutableStateOf(event?.body ?: "") }
    var allDayEvent by rememberSaveable { mutableStateOf(event?.allDayEvent ?: false) }
    var reminder by rememberSaveable { mutableStateOf(event?.reminder ?: 15) }
    var busyStatus by rememberSaveable { mutableStateOf(event?.busyStatus ?: 2) }
    
    // Даты и время
    val calendar = Calendar.getInstance()
    if (event != null) {
        calendar.timeInMillis = event.startTime
    } else {
        calendar.time = initialDate
        // Округляем до следующего часа
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.add(Calendar.HOUR_OF_DAY, 1)
    }
    
    var startDate by remember { mutableStateOf(calendar.time) }
    var startHour by rememberSaveable { mutableStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by rememberSaveable { mutableStateOf(calendar.get(Calendar.MINUTE)) }
    
    if (event != null) {
        calendar.timeInMillis = event.endTime
    } else {
        calendar.add(Calendar.HOUR_OF_DAY, 1)
    }
    var endDate by remember { mutableStateOf(calendar.time) }
    var endHour by rememberSaveable { mutableStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var endMinute by rememberSaveable { mutableStateOf(calendar.get(Calendar.MINUTE)) }
    
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showReminderMenu by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    
    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    
    // Валидация
    val isValid = subject.isNotBlank()
    
    // Date Pickers
    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = startDate.time)
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        startDate = Date(millis)
                        // Если дата окончания раньше — сдвигаем
                        if (endDate.before(startDate)) {
                            endDate = startDate
                        }
                    }
                    showStartDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text(Strings.cancel) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = endDate.time)
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        endDate = Date(millis)
                    }
                    showEndDatePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text(Strings.cancel) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    // Time Pickers
    if (showStartTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = startHour, initialMinute = startMinute)
        AlertDialog(
            onDismissRequest = { showStartTimePicker = false },
            title = { Text(Strings.startTime) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    startHour = timePickerState.hour
                    startMinute = timePickerState.minute
                    showStartTimePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showStartTimePicker = false }) { Text(Strings.cancel) }
            }
        )
    }
    
    if (showEndTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = endHour, initialMinute = endMinute)
        AlertDialog(
            onDismissRequest = { showEndTimePicker = false },
            title = { Text(Strings.endTime) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    endHour = timePickerState.hour
                    endMinute = timePickerState.minute
                    showEndTimePicker = false
                }) { Text(Strings.ok) }
            },
            dismissButton = {
                TextButton(onClick = { showEndTimePicker = false }) { Text(Strings.cancel) }
            }
        )
    }
    
    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text(if (isEditing) Strings.editEvent else Strings.newEvent) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Название
                item {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text(Strings.eventTitle) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = subject.isBlank()
                    )
                }
                
                // Весь день
                item {
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
                
                // Дата начала
                item {
                    OutlinedTextField(
                        value = dateFormat.format(startDate),
                        onValueChange = {},
                        label = { Text(Strings.startDate) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showStartDatePicker = true },
                        readOnly = true,
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                
                // Время начала (если не весь день)
                if (!allDayEvent) {
                    item {
                        val startCal = Calendar.getInstance().apply {
                            time = startDate
                            set(Calendar.HOUR_OF_DAY, startHour)
                            set(Calendar.MINUTE, startMinute)
                        }
                        OutlinedTextField(
                            value = timeFormat.format(startCal.time),
                            onValueChange = {},
                            label = { Text(Strings.startTime) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showStartTimePicker = true },
                            readOnly = true,
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
                
                // Дата окончания
                item {
                    OutlinedTextField(
                        value = dateFormat.format(endDate),
                        onValueChange = {},
                        label = { Text(Strings.endDate) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showEndDatePicker = true },
                        readOnly = true,
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                
                // Время окончания (если не весь день)
                if (!allDayEvent) {
                    item {
                        val endCal = Calendar.getInstance().apply {
                            time = endDate
                            set(Calendar.HOUR_OF_DAY, endHour)
                            set(Calendar.MINUTE, endMinute)
                        }
                        OutlinedTextField(
                            value = timeFormat.format(endCal.time),
                            onValueChange = {},
                            label = { Text(Strings.endTime) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showEndTimePicker = true },
                            readOnly = true,
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
                
                // Место
                item {
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text(Strings.eventLocation) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                // Напоминание
                item {
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
                
                // Статус занятости
                item {
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
                
                // Описание
                item {
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isValid) {
                        // Собираем время
                        val startCal = Calendar.getInstance().apply {
                            time = startDate
                            if (allDayEvent) {
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                            } else {
                                set(Calendar.HOUR_OF_DAY, startHour)
                                set(Calendar.MINUTE, startMinute)
                            }
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        
                        val endCal = Calendar.getInstance().apply {
                            time = endDate
                            if (allDayEvent) {
                                set(Calendar.HOUR_OF_DAY, 23)
                                set(Calendar.MINUTE, 59)
                            } else {
                                set(Calendar.HOUR_OF_DAY, endHour)
                                set(Calendar.MINUTE, endMinute)
                            }
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        
                        onSave(
                            subject,
                            startCal.timeInMillis,
                            endCal.timeInMillis,
                            location,
                            body,
                            allDayEvent,
                            reminder,
                            busyStatus
                        )
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

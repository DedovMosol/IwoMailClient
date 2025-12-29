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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBackClick: () -> Unit
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
        EventDetailDialog(
            event = event,
            calendarRepo = calendarRepo,
            onDismiss = { selectedEvent = null }
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
                        onEventClick = { selectedEvent = it }
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
    onEventClick: (CalendarEventEntity) -> Unit
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
                        text = event.subject.ifBlank { "(Без заголовка)" },
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
                            contentDescription = "Завершено",
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
                    contentDescription = "Повторяющееся событие",
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
    val monthNames = remember {
        listOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
        )
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(AppIcons.ChevronLeft, "Предыдущий месяц")
        }
        
        Text(
            text = "${monthNames[month]} $year",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { onTitleClick() }
        )
        
        IconButton(onClick = onNextMonth) {
            Icon(AppIcons.ChevronRight, "Следующий месяц")
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
    
    val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    
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
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("d MMMM yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val attendees = remember(event.attendees) { calendarRepo.parseAttendeesFromJson(event.attendees) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = event.subject.ifBlank { "(Без заголовка)" },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            LazyColumn {
                item {
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
                }
                
                if (event.location.isNotBlank()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                AppIcons.Business,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = event.location,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                if (event.organizer.isNotBlank()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
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
                                    text = event.organizer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                
                if (attendees.isNotEmpty()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(bottom = 8.dp)
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
                                    Text(
                                        text = if (attendee.name.isNotBlank()) {
                                            "${attendee.name} (${attendee.email})"
                                        } else {
                                            attendee.email
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (event.categories.isNotBlank()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                AppIcons.Task,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = event.categories,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                
                if (event.body.isNotBlank()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        ClickableHtmlText(
                            text = event.body,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.close)
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
    val monthNames = listOf("янв", "фев", "мар", "апр", "май", "июнь", "июль", "авг", "сен", "окт", "ноя", "дек")
    
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
                Icon(AppIcons.ChevronLeft, "Предыдущий год")
            }
            
            Text(
                text = year.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }
            )
            
            IconButton(onClick = { onYearChange(year + 1) }) {
                Icon(AppIcons.ChevronRight, "Следующий год")
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
                listOf("П", "В", "С", "Ч", "П", "С", "В").forEach { day ->
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
                            
                            Text(
                                text = day.toString(),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.primary
                                    hasEvent -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            dayCounter++
                        }
                    }
                }
            }
        }
    }
}

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
    
    // Расширения картинок
    val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")
    
    // Паттерны для разных форматов ссылок
    // Markdown формат [imageUrl]<linkUrl> - картинка-ссылка
    val markdownImageLinkPattern = Regex("\\[([^\\]]+)\\]<([^>]+)>")
    // HTML ссылки <a href="url">text</a>
    val hrefPattern = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)
    // Обычные URL
    val urlPattern = Regex("https?://[\\w\\-.]+\\.[a-z]{2,}[\\w\\-._~:/?#\\[\\]@!%&'()*+,;=]*", RegexOption.IGNORE_CASE)
    
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
                        catch (e: Exception) { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
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
                            catch (e: Exception) { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
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
    
    LaunchedEffect(url) {
        isLoading = true
        error = false
        try {
            bitmap = withContext(Dispatchers.IO) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.connect()
                android.graphics.BitmapFactory.decodeStream(conn.inputStream)
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

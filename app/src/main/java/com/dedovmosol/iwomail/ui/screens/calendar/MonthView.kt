package com.dedovmosol.iwomail.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.screens.expandRecurringForRange
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import java.text.SimpleDateFormat
import java.util.*

@Composable
internal fun MonthView(
    events: List<CalendarEventEntity>,
    selectedDate: Date,
    onDateSelected: (Date) -> Unit,
    onEventClick: (CalendarEventEntity) -> Unit,
    selectedEventIds: Set<String> = emptySet(),
    onEventSelectionChange: (String, Boolean) -> Unit = { _, _ -> },
    onEventLongClick: (String) -> Unit = {},
    onDragSelect: (Set<String>) -> Unit = {}
) {
    val isSelectionMode = selectedEventIds.isNotEmpty()
    val calendar = Calendar.getInstance()
    calendar.time = selectedDate
    
    var currentMonth by remember(selectedDate) { mutableStateOf(calendar.get(Calendar.MONTH)) }
    var currentYear by remember(selectedDate) { mutableStateOf(calendar.get(Calendar.YEAR)) }
    var showYearView by rememberSaveable { mutableStateOf(false) }
    
    val expandedEvents = remember(events, currentMonth, currentYear) {
        val cal = Calendar.getInstance()
        cal.set(currentYear, currentMonth, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val monthEnd = cal.timeInMillis
        expandRecurringForRange(events, monthStart, monthEnd)
    }
    
    val dayEvents = remember(expandedEvents, selectedDate) {
        val cal = Calendar.getInstance()
        cal.time = selectedDate
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val dayEnd = cal.timeInMillis
        expandedEvents.filter { event ->
            (event.startTime in dayStart until dayEnd) ||
            (event.startTime < dayEnd && event.endTime > dayStart)
        }.sortedBy { it.startTime }
    }
    
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
                events = expandedEvents,
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
                
                val dayListState = rememberLazyListState()
                val dayEventKeys = remember(dayEvents) { dayEvents.map { it.id } }
                val dayDragModifier = com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier(
                    listState = dayListState,
                    itemKeys = dayEventKeys,
                    selectedIds = selectedEventIds,
                    onSelectionChange = onDragSelect
                )
                
                Box(modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isSelectionMode) Modifier.clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { onDragSelect(emptySet()) }
                        else Modifier
                    )
                ) {
                LazyColumn(
                    state = dayListState,
                    modifier = dayDragModifier.fillMaxSize(),
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
                }
        } else {
            val emptyMonthScrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(emptyMonthScrollState)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = Strings.noEvents,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ScrollColumnScrollbar(emptyMonthScrollState)
            }
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
        
        val eventDays = remember(events, year, month) {
            val reuseCal = Calendar.getInstance()
            reuseCal.set(year, month, 1, 0, 0, 0)
            reuseCal.set(Calendar.MILLISECOND, 0)
            val monthStart = reuseCal.timeInMillis
            reuseCal.add(Calendar.MONTH, 1)
            val monthEnd = reuseCal.timeInMillis
            val days = mutableSetOf<Int>()
            for (event in events) {
                if (event.endTime <= monthStart || event.startTime >= monthEnd) continue
                reuseCal.timeInMillis = maxOf(event.startTime, monthStart)
                val from = reuseCal.get(Calendar.DAY_OF_MONTH)
                reuseCal.timeInMillis = minOf(maxOf(event.endTime - 1, event.startTime), monthEnd - 1)
                val to = reuseCal.get(Calendar.DAY_OF_MONTH)
                for (d in from..to) days.add(d)
            }
            days
        }
        val selectedDay = remember(selectedDate) {
            val cal = Calendar.getInstance().apply { time = selectedDate }
            Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        }
        val todayTriple = remember {
            val cal = Calendar.getInstance()
            Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        }
        
        // Сетка дней
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .height(240.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(firstDayOffset) {
                Spacer(modifier = Modifier.height(40.dp))
            }
            
            items(daysInMonth) { dayIndex ->
                val day = dayIndex + 1
                calendar.set(year, month, day)
                val date = calendar.time
                
                val hasEvents = day in eventDays
                val isSelected = selectedDay.first == year && selectedDay.second == month && selectedDay.third == day
                val isToday = todayTriple.first == year && todayTriple.second == month && todayTriple.third == day
                
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
        
        val eventsByMonth = remember(events, year) {
            val cal = Calendar.getInstance()
            val buckets = Array(12) { mutableListOf<CalendarEventEntity>() }
            for (event in events) {
                cal.timeInMillis = event.startTime
                if (cal.get(Calendar.YEAR) == year) {
                    buckets[cal.get(Calendar.MONTH)].add(event)
                }
            }
            Array<List<CalendarEventEntity>>(12) { buckets[it] }
        }
        
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
                    monthEvents = eventsByMonth[monthIndex],
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
    monthEvents: List<CalendarEventEntity>,
    selectedDate: Date,
    onClick: () -> Unit
) {
    val calendar = Calendar.getInstance()
    calendar.set(year, month, 1)
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
    val firstDayOffset = if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - 2
    
    val daysWithEvents = remember(monthEvents) {
        val cal = Calendar.getInstance()
        monthEvents.mapNotNullTo(mutableSetOf()) { event ->
            cal.timeInMillis = event.startTime
            cal.get(Calendar.DAY_OF_MONTH)
        }
    }
    
    val today = remember { Calendar.getInstance() }
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


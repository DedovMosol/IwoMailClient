@file:OptIn(ExperimentalFoundationApi::class)

package com.dedovmosol.iwomail.ui.screens.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.screens.CalendarDateFilter
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import java.text.SimpleDateFormat
import java.util.*

enum class CalendarViewMode {
    AGENDA, MONTH
}

@Composable
internal fun CalendarFilterChips(
    currentFilter: CalendarDateFilter,
    onFilterChange: (CalendarDateFilter) -> Unit,
    deletedCount: Int = 0,
    onEmptyTrash: () -> Unit = {}
) {
    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
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
        FilterChip(
            selected = currentFilter == CalendarDateFilter.DELETED,
            onClick = { onFilterChange(CalendarDateFilter.DELETED) },
            label = { 
                val label = if (isRussian) "Корзина" else "Trash"
                Text(if (deletedCount > 0) "$label ($deletedCount)" else label) 
            },
            leadingIcon = if (currentFilter == CalendarDateFilter.DELETED) {
                { Icon(AppIcons.Delete, null, Modifier.size(16.dp)) }
            } else null
        )
        // Кнопка очистки корзины (видна только в режиме корзины)
        if (currentFilter == CalendarDateFilter.DELETED && deletedCount > 0) {
            FilterChip(
                selected = false,
                onClick = onEmptyTrash,
                label = { Text(if (isRussian) "Очистить" else "Empty") },
                leadingIcon = { Icon(AppIcons.DeleteForever, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) }
            )
        }
    }
}

@Composable
internal fun AgendaView(
    events: List<CalendarEventEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchFocusRequester: FocusRequester = FocusRequester(),
    onSearchFocusChanged: (Boolean) -> Unit = {},
    onEventClick: (CalendarEventEntity) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedEventIds: Set<String> = emptySet(),
    onEventSelectionChange: (String, Boolean) -> Unit = { _, _ -> },
    onEventLongClick: (String) -> Unit = {},
    onDragSelect: (Set<String>) -> Unit = {},
    isLoading: Boolean = false,
    dateFilter: CalendarDateFilter = CalendarDateFilter.ALL,
    onDateFilterChange: (CalendarDateFilter) -> Unit = {},
    deletedCount: Int = 0,
    onEmptyTrash: () -> Unit = {}
) {
    val isSelectionMode = selectedEventIds.isNotEmpty()
    val haptic = LocalHapticFeedback.current
    Column {
        // Поле поиска (на всю ширину)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(searchFocusRequester)
                .onFocusChanged { onSearchFocusChanged(it.isFocused) },
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
            onFilterChange = onDateFilterChange,
            deletedCount = deletedCount,
            onEmptyTrash = onEmptyTrash
        )
        
        // Счётчик
        Text(
            text = "${Strings.total}: ${events.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        
        if (events.isEmpty()) {
            val emptyScrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(emptyScrollState)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else {
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
                ScrollColumnScrollbar(emptyScrollState)
            }
        } else {
            var expandedSeriesIds by rememberSaveable(
                stateSaver = listSaver<Set<String>, String>(
                    save = { it.toList() },
                    restore = { it.toSet() }
                )
            ) { mutableStateOf(emptySet()) }

            val seriesMap = remember(events) {
                val tempMap = mutableMapOf<String, MutableList<CalendarEventEntity>>()
                events.forEach { event ->
                    if (event.isDeleted) return@forEach
                    if (event.id.contains("_occ_")) {
                        val masterId = event.id.substringBefore("_occ_")
                        tempMap.getOrPut(masterId) { mutableListOf() }.add(event)
                    } else if (event.isRecurring) {
                        tempMap.getOrPut(event.id) { mutableListOf() }.add(event)
                    }
                }
                val result = mutableMapOf<String, MutableList<CalendarEventEntity>>()
                for ((masterId, occs) in tempMap) {
                    val masterSubject = occs.firstOrNull { !it.id.contains("_occ_") }?.subject
                        ?: occs.groupingBy { it.subject }.eachCount().maxByOrNull { it.value }?.key
                        ?: ""
                    val matching = mutableListOf<CalendarEventEntity>()
                    for (occ in occs) {
                        if (occ.subject != masterSubject && occ.id.contains("_occ_")) {
                            result.getOrPut("_renamed_${occ.id}") { mutableListOf() }.add(occ)
                        } else {
                            matching.add(occ)
                        }
                    }
                    if (matching.isNotEmpty()) {
                        result[masterId] = matching
                    }
                }
                result.values.forEach { list -> list.sortBy { it.startTime } }
                result.toMap()
            }

            val standaloneEvents = remember(events, seriesMap) {
                val recurringIds = seriesMap.keys
                val renamedStandalone = seriesMap.entries
                    .filter { it.key.startsWith("_renamed_") }
                    .flatMap { it.value }
                events.filter { e ->
                    e.isDeleted || (!e.id.contains("_occ_") && !e.isRecurring && e.id !in recurringIds)
                } + renamedStandalone
            }

            val groupedStandalone = remember(standaloneEvents) {
                standaloneEvents.groupBy { event ->
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = event.startTime
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.time
                }.toSortedMap(compareByDescending { it })
            }

            val sortedSeries = remember(seriesMap) {
                seriesMap.entries
                    .filter { !it.key.startsWith("_renamed_") }
                    .sortedByDescending { it.value.maxOfOrNull { e -> e.startTime } ?: 0L }
            }

            val eventKeys = remember(sortedSeries, expandedSeriesIds, groupedStandalone) {
                val keys = mutableListOf<String>()
                sortedSeries.forEach { (masterId, occurrences) ->
                    if (masterId in expandedSeriesIds) {
                        keys.addAll(occurrences.map { it.id })
                    }
                }
                groupedStandalone.values.forEach { dayEvents -> keys.addAll(dayEvents.map { it.id }) }
                keys
            }
            val dragModifier = com.dedovmosol.iwomail.ui.components.rememberDragSelectModifier(
                listState = listState,
                itemKeys = eventKeys,
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
                state = listState,
                modifier = dragModifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sortedSeries.isNotEmpty()) {
                    sortedSeries.forEach { (masterId, occurrences) ->
                        val representative = occurrences.first()
                        val isExpanded = masterId in expandedSeriesIds
                        val allOccurrenceIds = occurrences.map { it.id }.toSet()
                        val selectedInSeries = allOccurrenceIds.count { it in selectedEventIds }
                        val seriesSelectionState = when {
                            selectedInSeries == allOccurrenceIds.size -> ToggleableState.On
                            selectedInSeries > 0 -> ToggleableState.Indeterminate
                            else -> ToggleableState.Off
                        }

                        item(key = "series_$masterId") {
                            RecurringSeriesFolder(
                                event = representative,
                                count = occurrences.size,
                                isExpanded = isExpanded,
                                isSelectionMode = isSelectionMode,
                                selectionState = seriesSelectionState,
                                onClick = {
                                    expandedSeriesIds = if (isExpanded) expandedSeriesIds - masterId
                                    else expandedSeriesIds + masterId
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDragSelect(selectedEventIds + allOccurrenceIds)
                                },
                                onSelectionToggle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (seriesSelectionState == ToggleableState.On) {
                                        onDragSelect(selectedEventIds - allOccurrenceIds)
                                    } else {
                                        onDragSelect(selectedEventIds + allOccurrenceIds)
                                    }
                                }
                            )
                        }

                        if (isExpanded) {
                            items(occurrences, key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    isSelected = event.id in selectedEventIds,
                                    isSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) onEventSelectionChange(event.id, event.id !in selectedEventIds)
                                        else onEventClick(event)
                                    },
                                    onLongClick = { if (!isSelectionMode) onEventLongClick(event.id) },
                                    showDate = true
                                )
                            }
                        }
                    }
                }

                groupedStandalone.forEach { (date, dayEvents) ->
                    item(key = "header_${date.time}") {
                        DateHeader(date = date)
                    }
                    
                    items(dayEvents, key = { it.id }) { event ->
                        EventCard(
                            event = event,
                            isSelected = event.id in selectedEventIds,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) onEventSelectionChange(event.id, event.id !in selectedEventIds)
                                else onEventClick(event)
                            },
                            onLongClick = { if (!isSelectionMode) onEventLongClick(event.id) }
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
private fun RecurringSeriesFolder(
    event: CalendarEventEntity,
    count: Int,
    isExpanded: Boolean,
    isSelectionMode: Boolean = false,
    selectionState: ToggleableState = ToggleableState.Off,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onSelectionToggle: () -> Unit = {}
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selectionState != ToggleableState.Off)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        label = "seriesBg"
    )
    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
    val ruleDescription = remember(event.recurrenceRule, isRussian) {
        RecurrenceHelper.describeRule(event.recurrenceRule, isRussian)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (isSelectionMode) onSelectionToggle() else onClick() },
                onLongClick = { if (!isSelectionMode) onLongClick() }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                TriStateCheckbox(
                    state = selectionState,
                    onClick = onSelectionToggle,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                AppIcons.Refresh,
                contentDescription = Strings.recurringEvent,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.subject.ifBlank { Strings.noTitle },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$count ${Strings.pluralEvents(count)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ruleDescription.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = ruleDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = count.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                if (isExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
internal fun EventCard(
    event: CalendarEventEntity,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showDate: Boolean = false
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val shortDateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
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
            .then(
                if (isSelectionMode) Modifier.clickable(onClick = onClick)
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            ),
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
                        color = if (event.isDeleted) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                               else if (isPastEvent) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) 
                               else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (event.isDeleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPastEvent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            AppIcons.CheckCircle,
                            contentDescription = Strings.completed,
                            modifier = Modifier.size(18.dp),
                            tint = com.dedovmosol.iwomail.ui.theme.AppColors.calendar
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = when {
                        event.allDayEvent && showDate -> "${shortDateFormat.format(Date(event.startTime))} — ${Strings.allDay}"
                        event.allDayEvent -> Strings.allDay
                        showDate -> "${shortDateFormat.format(Date(event.startTime))}  ${timeFormat.format(Date(event.startTime))} - ${timeFormat.format(Date(event.endTime))}"
                        else -> "${timeFormat.format(Date(event.startTime))} - ${timeFormat.format(Date(event.endTime))}"
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

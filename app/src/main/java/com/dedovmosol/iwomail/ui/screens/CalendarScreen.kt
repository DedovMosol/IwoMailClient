package com.dedovmosol.iwomail.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import com.dedovmosol.iwomail.ui.components.rememberDebouncedState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
import com.dedovmosol.iwomail.ui.screens.calendar.CalendarViewMode
import com.dedovmosol.iwomail.ui.screens.calendar.CalendarFilterChips
import com.dedovmosol.iwomail.ui.screens.calendar.AgendaView
import com.dedovmosol.iwomail.ui.screens.calendar.MonthView
import com.dedovmosol.iwomail.ui.screens.calendar.EventDetailDialog
import com.dedovmosol.iwomail.ui.screens.calendar.DeletedEventDetailDialog
import com.dedovmosol.iwomail.ui.screens.calendar.CreateEventDialog
import com.dedovmosol.iwomail.ui.screens.calendar.CalendarSelectionTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlinx.coroutines.delay



enum class CalendarDateFilter {
    ALL, TODAY, WEEK, MONTH, DELETED
}

/**
 * Разворачивает повторяющиеся события в виртуальные экземпляры (occurrences)
 * для заданного диапазона дат. Не-повторяющиеся события фильтруются по диапазону.
 */
internal fun expandRecurringForRange(
    events: List<CalendarEventEntity>,
    rangeStart: Long,
    rangeEnd: Long
): List<CalendarEventEntity> {
    val result = mutableListOf<CalendarEventEntity>()
    
    for (event in events) {
        if (event.isRecurring && event.recurrenceRule.isNotBlank()) {
            // Генерируем экземпляры серии в диапазоне
            val occurrences = RecurrenceHelper.generateOccurrences(event, rangeStart, rangeEnd)
            if (occurrences.isNotEmpty()) {
                for (occ in occurrences) {
                    result.add(event.copy(
                        id = "${event.id}_occ_${occ.originalStartTime}",
                        startTime = occ.startTime,
                        endTime = occ.endTime,
                        subject = occ.subject,
                        location = occ.location,
                        body = occ.body,
                        hasAttachments = occ.attachments.isNotBlank(),
                        attachments = occ.attachments
                    ))
                }
            } else {
                // Fallback: если не удалось сгенерировать — показываем оригинал (если в диапазоне)
                if ((event.startTime in rangeStart until rangeEnd) ||
                    (event.startTime < rangeEnd && event.endTime > rangeStart)) {
                    result.add(event)
                }
            }
        } else {
            // Не-повторяющееся: стандартная проверка попадания в диапазон
            if ((event.startTime in rangeStart until rangeEnd) ||
                (event.startTime < rangeEnd && event.endTime > rangeStart)) {
                result.add(event)
            }
        }
    }
    
    return result.sortedBy { it.startTime }
}

private fun buildRecurringOccurrenceSelectionId(
    event: CalendarEventEntity,
    masterId: String = event.id
): String? {
    return when {
        event.id.contains("_occ_") -> event.id
        event.startTime > 0L -> "${masterId}_occ_${event.startTime}"
        else -> null
    }
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
    val deletedEvents by remember(accountId) { calendarRepo.getDeletedEvents(accountId) }.collectAsState(initial = emptyList())
    
    // ID аккаунта, для которого уже был запущен автосинк.
    // rememberSaveable: сохраняется при повороте → не запускает повторный синк для того же аккаунта.
    // При смене accountId значение не совпадёт → синк запустится для нового аккаунта.
    var syncedForAccountId by rememberSaveable { mutableStateOf(0L) }

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val debouncedSearchQuery by rememberDebouncedState(searchQuery)
    
    // Сохранение фокуса поиска при повороте экрана
    val searchFocusRequester = remember { FocusRequester() }
    var isSearchFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isSearchFocused) {
        if (isSearchFocused) {
            kotlinx.coroutines.delay(100)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
    var isSyncing by remember { mutableStateOf(false) }
    
    // Автоматическая синхронизация при первом открытии экрана или смене аккаунта.
    // syncedForAccountId != accountId → синк не запускался для этого аккаунта → запускаем.
    // При повороте экрана accountId не меняется → syncedForAccountId совпадает → синк не повторяется.
    LaunchedEffect(accountId) {
        if (accountId > 0 && syncedForAccountId != accountId && !isSyncing) {
            syncedForAccountId = accountId
            isSyncing = true
            try {
                withContext(Dispatchers.IO) {
                    calendarRepo.syncCalendar(accountId)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                isSyncing = false
            }
        }
    }
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewMode by rememberSaveable { mutableStateOf(CalendarViewMode.AGENDA) }
    var selectedDateMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    val selectedDate = remember(selectedDateMillis) { Date(selectedDateMillis) }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editingEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingEvent = remember(editingEventId, events) {
        editingEventId?.let { id -> events.find { it.id == id } }
    }
    var isCreating by remember { mutableStateOf(false) }

    // Редактирование одного вхождения повторяющегося события
    var editingOccurrenceStartTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var cachedOccurrenceEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var showEditChoiceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingEditOccurrenceId by rememberSaveable { mutableStateOf<String?>(null) }
    
    // Множественный выбор
    val haptic = LocalHapticFeedback.current
    var selectedEventIds by rememberSaveable(
        saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })
    ) { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedEventIds.isNotEmpty()
    
    // Определяем, есть ли среди выделенных удалённые события (для корректного TopBar/диалога)
    val deletedEventIdSet = remember(deletedEvents) { deletedEvents.map { it.id }.toSet() }
    val resolvedSelectedIds = remember(selectedEventIds, deletedEventIdSet) {
        selectedEventIds.map { id ->
            if (id.contains("_occ_") && id !in deletedEventIdSet) id.substringBefore("_occ_") else id
        }.toSet()
    }
    val selectedDeletedResolvedIds = remember(resolvedSelectedIds, deletedEventIdSet) {
        resolvedSelectedIds.filter { it in deletedEventIdSet }.toSet()
    }
    val selectedActiveResolvedIds = remember(resolvedSelectedIds, deletedEventIdSet) {
        resolvedSelectedIds.filter { it !in deletedEventIdSet }.toSet()
    }
    val hasDeletedSelected = selectedDeletedResolvedIds.isNotEmpty()
    val hasActiveSelected = selectedActiveResolvedIds.isNotEmpty()
    
    // Диалог подтверждения удаления
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmCount by rememberSaveable { mutableStateOf(0) }
    var deleteConfirmIsPermanent by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmTargetIds by rememberSaveable(
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) }
        )
    ) { mutableStateOf(setOf<String>()) }
    var showEmptyTrashDialog by rememberSaveable { mutableStateOf(false) }
    // Диалог выбора: удалить вхождение или серию
    var showOccurrenceDeleteChoice by rememberSaveable { mutableStateOf(false) }
    var pendingOccurrenceIds by rememberSaveable(
        saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })
    ) { mutableStateOf(setOf<String>()) }
    
    // Состояние списка для автоскролла
    val listState = rememberLazyListState()
    
    // Фильтр по дате (аналогично задачам)
    var dateFilter by rememberSaveable { mutableStateOf(initialDateFilter) }
    
    // Обработка кнопки Back в режиме выбора
    androidx.activity.compose.BackHandler(enabled = isSelectionMode) {
        selectedEventIds = emptySet()
    }
    
    // Фильтрация по поиску и по дате (с разворачиванием повторяющихся событий)
    val filteredEvents = remember(events, deletedEvents, debouncedSearchQuery, dateFilter) {
        // Сначала фильтруем по дате
        val dateFiltered = when (dateFilter) {
            CalendarDateFilter.DELETED -> deletedEvents
            CalendarDateFilter.ALL -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.DAY_OF_YEAR, -90)
                val rangeStart = cal.timeInMillis
                cal.timeInMillis = System.currentTimeMillis()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.DAY_OF_YEAR, 365)
                val rangeEnd = cal.timeInMillis
                expandRecurringForRange(events, rangeStart, rangeEnd) + deletedEvents
            }
            CalendarDateFilter.TODAY -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val tomorrowStart = cal.timeInMillis
                expandRecurringForRange(events, todayStart, tomorrowStart)
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
                expandRecurringForRange(events, todayStart, weekEnd)
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
                expandRecurringForRange(events, todayStart, monthEnd)
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
    
    val renamedOccurrenceIds = remember(filteredEvents) {
        val occsByMaster = mutableMapOf<String, MutableList<CalendarEventEntity>>()
        filteredEvents.forEach { evt ->
            if (evt.isDeleted) return@forEach
            if (evt.id.contains("_occ_")) {
                occsByMaster.getOrPut(evt.id.substringBefore("_occ_")) { mutableListOf() }.add(evt)
            }
        }
        val masters = filteredEvents.filter { it.isRecurring && !it.id.contains("_occ_") && !it.isDeleted }
            .associateBy { it.id }
        val renamed = mutableSetOf<String>()
        for ((masterId, occs) in occsByMaster) {
            val majoritySubject = masters[masterId]?.subject
                ?: occs.groupingBy { it.subject }.eachCount().maxByOrNull { it.value }?.key ?: ""
            for (occ in occs) {
                if (occ.subject != majoritySubject) renamed.add(occ.id)
            }
        }
        renamed.toSet()
    }

    val monthViewBaseEvents = remember(events, deletedEvents, debouncedSearchQuery, dateFilter) {
        val base = when (dateFilter) {
            CalendarDateFilter.DELETED -> deletedEvents
            CalendarDateFilter.ALL -> events
            else -> events
        }
        if (debouncedSearchQuery.isBlank()) base
        else base.filter { event ->
            event.subject.contains(debouncedSearchQuery, ignoreCase = true) ||
            event.location.contains(debouncedSearchQuery, ignoreCase = true) ||
            event.body.contains(debouncedSearchQuery, ignoreCase = true)
        }
    }
    
    // Виртуальные occurrence (_occ_) ищутся в filteredEvents (содержит развёрнутые повторения),
    // чтобы показать корректное время occurrence, а не базового события.
    val selectedEvent = remember(selectedEventId, filteredEvents, events, deletedEvents) {
        selectedEventId?.let { id ->
            filteredEvents.find { it.id == id }
                ?: deletedEvents.find { it.id == id }
                ?: run {
                    val lookupId = if (id.contains("_occ_")) id.substringBefore("_occ_") else id
                    events.find { it.id == lookupId } ?: deletedEvents.find { it.id == lookupId }
                }
        }
    }
    val singleSelectedEditableId = remember(selectedEventIds, hasDeletedSelected) {
        selectedEventIds.singleOrNull()?.takeIf { !hasDeletedSelected }
    }
    val openSelectedEventForEditing: () -> Unit = {
        singleSelectedEditableId?.let { selectedId ->
            val selectedEventForEdit = filteredEvents.find { it.id == selectedId }
                ?: deletedEvents.find { it.id == selectedId }
                ?: events.find { it.id == selectedId }
                ?: if (selectedId.contains("_occ_")) {
                    events.find { it.id == selectedId.substringBefore("_occ_") }
                } else {
                    null
                }
                ?: return@let

            val originalEvent = if (selectedId.contains("_occ_")) {
                events.find { it.id == selectedId.substringBefore("_occ_") } ?: selectedEventForEdit
            } else {
                selectedEventForEdit
            }

            selectedEventIds = emptySet()
            selectedEventId = null
            if (selectedId.contains("_occ_") && originalEvent.isRecurring) {
                pendingEditOccurrenceId = selectedId
                showEditChoiceDialog = true
            } else {
                pendingEditOccurrenceId = null
                editingEventId = originalEvent.id
                editingOccurrenceStartTime = null
                cachedOccurrenceEvent = null
                showCreateDialog = true
            }
        }
    }

    // Диалог просмотра события
    selectedEvent?.let { event ->
        val eventDeletedText = Strings.eventDeleted
        val deletingOneEventText = Strings.deletingEvents(1)
        val restoringOneEventText = Strings.restoringEvents(1)
        val undoText = Strings.undo
        val eventsRestoredText = Strings.eventsRestored
        val eventRestoredText = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) "Событие восстановлено" else "Event restored"
        val eventDeletedPermanentlyText = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) "Событие удалено навсегда" else "Event permanently deleted"
        
        if (event.isDeleted) {
            // Диалог для удалённого события — восстановить или удалить навсегда
            DeletedEventDetailDialog(
                event = event,
                onDismiss = { selectedEventId = null },
                onRestoreClick = {
                    selectedEventId = null  // Закрываем диалог СРАЗУ
                    
                    deletionController.startDeletion(
                        emailIds = listOf(event.id),
                        message = restoringOneEventText,
                        scope = scope,
                        isRestore = true
                    ) { _, onProgress ->
                        val result = withContext(Dispatchers.IO) {
                            calendarRepo.restoreEvent(event)
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
                },
                onDeletePermanentlyClick = {
                    selectedEventId = null
                    com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                    
                    deletionController.startDeletion(
                        emailIds = listOf(event.id),
                        message = deletingOneEventText,
                        scope = scope,
                        isRestore = false
                    ) { _, onProgress ->
                        val result = withContext(Dispatchers.IO) {
                            calendarRepo.deleteEventPermanently(event)
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
            // Для виртуальных occurrence — находим оригинальное событие в БД
            val originalEvent = if (event.id.contains("_occ_")) {
                val originalId = event.id.substringBefore("_occ_")
                events.find { it.id == originalId } ?: event
            } else {
                event
            }
            
            // Обычный диалог для активного события (показываем данные occurrence, но операции — над оригиналом)
            EventDetailDialog(
                event = event,
                calendarRepo = calendarRepo,
                currentUserEmail = activeAccount?.email ?: "",
                onDismiss = { selectedEventId = null },
                onComposeClick = onComposeClick,
                onEditClick = {
                    val sid = selectedEventId ?: ""
                    val isOccurrence = event.id.contains("_occ_") || sid.contains("_occ_")
                    if (isOccurrence && originalEvent.isRecurring) {
                        pendingEditOccurrenceId = if (event.id.contains("_occ_")) event.id else sid
                        selectedEventId = null
                        showEditChoiceDialog = true
                    } else {
                        editingEventId = originalEvent.id
                        editingOccurrenceStartTime = null
                        cachedOccurrenceEvent = null
                        selectedEventId = null
                        showCreateDialog = true
                    }
                },
                onDeleteClick = {
                    val occurrenceDeleteId = buildRecurringOccurrenceSelectionId(event, originalEvent.id)
                    if (originalEvent.isRecurring && occurrenceDeleteId != null) {
                        pendingOccurrenceIds = setOf(occurrenceDeleteId)
                        deleteConfirmTargetIds = setOf(originalEvent.id)
                        selectedEventId = null
                        showOccurrenceDeleteChoice = true
                    } else {
                        selectedEventId = null
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                calendarRepo.deleteEvent(originalEvent)
                            }
                            when (result) {
                                is EasResult.Success -> {
                                    Toast.makeText(context, eventDeletedText, Toast.LENGTH_SHORT).show()
                                }
                                is EasResult.Error -> {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            )
        }
    }
    
    // Диалог подтверждения удаления событий
    if (showDeleteConfirmDialog) {
        val eventsDeletedText = Strings.eventDeleted
        val eventsDeletedPermanentlyText = Strings.eventsDeletedPermanently
        val undoText = Strings.undo
        val eventsRestoredText = Strings.eventsRestored
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                deleteConfirmTargetIds = emptySet()
            },
            icon = { Icon(if (deleteConfirmIsPermanent) AppIcons.DeleteForever else AppIcons.Delete, null) },
            title = { 
                Text(
                    if (deleteConfirmIsPermanent) 
                        Strings.deleteEventsPermanently 
                    else 
                        Strings.deleteEvents
                ) 
            },
            text = { 
                Text(
                    if (deleteConfirmCount == 1)
                        if (deleteConfirmIsPermanent) Strings.deleteEventPermanentlyConfirm else Strings.deleteEventConfirm
                    else
                        if (deleteConfirmIsPermanent) Strings.deleteEventsPermanentlyConfirm(deleteConfirmCount) else Strings.deleteEventsConfirm(deleteConfirmCount)
                ) 
            },
            confirmButton = {
                val deletingEventsMessage = Strings.deletingEvents(deleteConfirmCount)
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        
                        // Ищем выбранные события в обоих списках
                        // Для виртуальных occurrence ID (содержат _occ_) берём оригинальный ID серии
                        val eventsToDelete = (events + deletedEvents).filter { it.id in deleteConfirmTargetIds }
                        val eventIds = eventsToDelete.map { it.id }
                        selectedEventIds = selectedEventIds.filterNot { id ->
                            id in deleteConfirmTargetIds ||
                                (id.contains("_occ_") && id.substringBefore("_occ_") in deleteConfirmTargetIds)
                        }.toSet()
                        
                        if (eventIds.isNotEmpty()) {
                            if (deleteConfirmIsPermanent) {
                                // Окончательное удаление из корзины — с прогрессбаром
                                deletionController.startDeletion(
                                    emailIds = eventIds,
                                    message = deletingEventsMessage,
                                    scope = scope,
                                    isRestore = false
                                ) { _, onProgress ->
                                    var deleted = 0
                                    for (event in eventsToDelete) {
                                        val result = withContext(Dispatchers.IO) {
                                            calendarRepo.deleteEventPermanently(event)
                                        }
                                        if (result is EasResult.Success) deleted++
                                        onProgress(deleted, eventsToDelete.size)
                                    }
                                    if (deleted > 0) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "$eventsDeletedPermanentlyText: $deleted", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else {
                                // Soft-delete (в корзину) через единый EasClient (deleteEvents).
                                // Раньше для каждого события создавался отдельный EasClient →
                                // дублирование NTLM-хэндшейков и конфликт SyncKey (EAS).
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        calendarRepo.deleteEvents(eventsToDelete)
                                    }
                                    val deleted = when (result) {
                                        is EasResult.Success -> result.data
                                        is EasResult.Error -> 0
                                    }
                                    if (deleted > 0) {
                                        Toast.makeText(context, "$eventsDeletedText: $deleted", Toast.LENGTH_SHORT).show()
                                    }
                                    if (result is EasResult.Error) {
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeleteConfirmDialog = false },
                    text = Strings.no
                )
            }
        )
    }
    
    // Диалог выбора: удалить конкретное вхождение или всю серию
    if (showOccurrenceDeleteChoice) {
        val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = {
                showOccurrenceDeleteChoice = false
                pendingOccurrenceIds = emptySet()
                deleteConfirmTargetIds = emptySet()
            },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(if (isRussian) "Удаление повторяющегося\nсобытия" else "Delete Recurring\nEvent", textAlign = TextAlign.Center) },
            text = {
                Text(
                    if (isRussian) "Удалить только выбранные вхождения\nили всю серию целиком?"
                    else "Delete only selected occurrences\nor the entire series?",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showOccurrenceDeleteChoice = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        val idsToProcess = pendingOccurrenceIds.toList()
                        val nonOccurrenceResolvedIds = deleteConfirmTargetIds -
                            pendingOccurrenceIds.map { it.substringBefore("_occ_") }.toSet()
                        val eventsSnapshot = events.toList()
                        val filteredSnapshot = filteredEvents.toList()
                        selectedEventIds = emptySet()
                        pendingOccurrenceIds = emptySet()
                        deleteConfirmTargetIds = emptySet()
                        scope.launch {
                            var successCount = 0
                            var errorMsg: String? = null
                            for (occId in idsToProcess) {
                                val masterId = occId.substringBefore("_occ_")
                                val occStartStr = occId.substringAfter("_occ_")
                                val occStart = occStartStr.toLongOrNull() ?: continue
                                val master = eventsSnapshot.find { it.id == masterId } ?: continue
                                val occEntity = filteredSnapshot.find { it.id == occId }
                                    ?: eventsSnapshot.find { it.id == occId }
                                val result = withContext(Dispatchers.IO) {
                                    calendarRepo.deleteOccurrence(master, occStart, occEntity)
                                }
                                if (result is EasResult.Success) successCount++
                                else if (result is EasResult.Error) errorMsg = result.message
                            }
                            if (nonOccurrenceResolvedIds.isNotEmpty()) {
                                val regularToDelete = eventsSnapshot.filter { it.id in nonOccurrenceResolvedIds }
                                if (regularToDelete.isNotEmpty()) {
                                    withContext(Dispatchers.IO) {
                                        calendarRepo.deleteEvents(regularToDelete)
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                if (successCount > 0 || nonOccurrenceResolvedIds.isNotEmpty()) {
                                    Toast.makeText(context, if (isRussian) "Удалено" else "Deleted", Toast.LENGTH_SHORT).show()
                                } else if (errorMsg != null) {
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    text = if (isRussian) "Только\nвхождения" else "Only\noccurrences"
                )
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showOccurrenceDeleteChoice = false
                        pendingOccurrenceIds = emptySet()
                        deleteConfirmCount = deleteConfirmTargetIds.size
                        deleteConfirmIsPermanent = false
                        showDeleteConfirmDialog = true
                    },
                    text = if (isRussian) "Всю\nсерию" else "Entire\nseries"
                )
            }
        )
    }

    // Диалог очистки корзины календаря
    if (showEmptyTrashDialog) {
        val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
        val trashEmptiedText = if (isRussian) "Корзина очищена" else "Trash emptied"
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(if (isRussian) "Очистить корзину?" else "Empty trash?") },
            text = { 
                Text(
                    if (isRussian) "Удалить навсегда ${deletedEvents.size} событий из корзины?"
                    else "Permanently delete ${deletedEvents.size} events from trash?"
                ) 
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showEmptyTrashDialog = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                calendarRepo.emptyCalendarTrash(accountId)
                            }
                            when (result) {
                                is EasResult.Success -> {
                                    Toast.makeText(context, trashEmptiedText, Toast.LENGTH_SHORT).show()
                                }
                                is EasResult.Error -> {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showEmptyTrashDialog = false },
                    text = Strings.no
                )
            }
        )
    }
    
    // Диалог выбора: «Изменить это вхождение» или «Изменить всю серию»
    if (showEditChoiceDialog) {
        val occId = pendingEditOccurrenceId
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = {
                showEditChoiceDialog = false
                pendingEditOccurrenceId = null
            },
            icon = { Icon(AppIcons.EditCalendar, null) },
            title = { Text(Strings.editRecurringEventTitle) },
            text = { Text(Strings.editRecurringEventMessage) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showEditChoiceDialog = false
                        if (occId != null) {
                            val masterId = occId.substringBefore("_occ_")
                            val occStart = occId.substringAfter("_occ_").toLongOrNull()
                            editingEventId = masterId
                            editingOccurrenceStartTime = if (occStart != null && occStart > 0) occStart else null
                            cachedOccurrenceEvent = filteredEvents.find { it.id == occId }
                            selectedEventId = null
                            showCreateDialog = true
                        }
                        pendingEditOccurrenceId = null
                    },
                    text = Strings.editThisOccurrence
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = {
                        showEditChoiceDialog = false
                        if (occId != null) {
                            val masterId = occId.substringBefore("_occ_")
                            editingEventId = masterId
                            editingOccurrenceStartTime = null
                            cachedOccurrenceEvent = null
                            selectedEventId = null
                            showCreateDialog = true
                        }
                        pendingEditOccurrenceId = null
                    },
                    text = Strings.editEntireSeries
                )
            }
        )
    }

    // Диалог создания/редактирования события
    if (showCreateDialog) {
        val eventUpdatedText = Strings.eventUpdated
        val eventCreatedText = Strings.eventCreated
        val invitationSentText = Strings.invitationSent
        val eventDeletedText = Strings.error
        val eventAttachmentsMayNotUploadText = Strings.eventAttachmentsMayNotUpload
        val recurringConversionHintText = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) {
            "Событие стало повторяющимся. Используйте фильтр \"${Strings.allDates}\", чтобы увидеть все вхождения."
        } else {
            "Event is now recurring. Use the \"${Strings.allDates}\" filter to view all occurrences."
        }
        val isEditing = editingEvent != null
        val dialogEvent = if (editingOccurrenceStartTime != null && editingEventId != null) {
            cachedOccurrenceEvent ?: editingEvent?.let { master ->
                val exceptions = RecurrenceHelper.parseExceptions(master.exceptions)
                val exc = RecurrenceHelper.fuzzyMatchException(exceptions.filter { !it.deleted }, editingOccurrenceStartTime!!)
                val duration = master.endTime - master.startTime
                master.copy(
                    id = "${master.id}_occ_${editingOccurrenceStartTime}",
                    subject = exc?.subject?.ifBlank { master.subject } ?: master.subject,
                    startTime = exc?.startTime?.takeIf { it > 0 } ?: editingOccurrenceStartTime!!,
                    endTime = exc?.endTime?.takeIf { it > 0 } ?: (editingOccurrenceStartTime!! + duration),
                    location = exc?.location?.ifBlank { master.location } ?: master.location,
                    body = exc?.body?.ifBlank { master.body } ?: master.body,
                    hasAttachments = (exc?.attachmentsOverridden == true && !exc.attachments.isBlank()) ||
                        (exc?.attachmentsOverridden != true && master.hasAttachments),
                    attachments = if (exc?.attachmentsOverridden == true) exc.attachments else master.attachments
                )
            }
        } else editingEvent
        CreateEventDialog(
            event = dialogEvent,
            initialDate = selectedDate,
            isCreating = isCreating,
            accountId = accountId,
            ownEmail = activeAccount?.email ?: "",
            onDismiss = { 
                showCreateDialog = false
                editingEventId = null
                editingOccurrenceStartTime = null
                cachedOccurrenceEvent = null
            },
            onSave = { subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, attendees, recurrenceType, attachments, removedAttachmentIds ->
                // Защита от double-tap: если уже создаём — игнорируем
                if (isCreating) return@CreateEventDialog
                isCreating = true
                scope.launch {
                    try {
                        // Парсим список участников
                        val attendeeList = attendees.split(",", ";")
                            .map { it.trim() }
                            .filter { it.contains("@") }
                        
                        val eventToEdit = editingEvent
                        val occStartTime = editingOccurrenceStartTime
                        val result = if (eventToEdit != null && occStartTime != null) {
                            withContext(Dispatchers.IO) {
                                calendarRepo.updateSingleOccurrence(
                                    masterEvent = eventToEdit,
                                    occurrenceOriginalStart = occStartTime,
                                    subject = subject,
                                    startTime = startTime,
                                    endTime = endTime,
                                    location = location,
                                    body = body,
                                    allDayEvent = allDayEvent,
                                    reminder = reminder,
                                    busyStatus = busyStatus,
                                    sensitivity = eventToEdit.sensitivity,
                                    attachments = attachments,
                                    removedAttachmentIds = removedAttachmentIds
                                )
                            }
                        } else if (eventToEdit != null) {
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
                                    busyStatus = busyStatus,
                                    attendees = attendeeList,
                                    recurrenceType = recurrenceType,
                                    attachments = attachments,
                                    removedAttachmentIds = removedAttachmentIds
                                )
                            }
                        } else if (editingEventId != null) {
                            EasResult.Error(eventDeletedText)
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
                                    busyStatus = busyStatus,
                                    attendees = attendeeList,
                                    recurrenceType = recurrenceType,
                                    attachments = attachments
                                )
                            }
                        }
                        
                        when (result) {
                            is EasResult.Success -> {
                                // НЕ вызываем syncCalendar() здесь:
                                // createEvent() и updateEvent() уже синхронизируют внутри себя.
                                // Повторный sync опасен: Exchange может не успеть проиндексировать
                                // новое событие → sync не увидит его → удалит локально.
                                val messageBase = if (attendeeList.isNotEmpty()) {
                                    "${if (isEditing) eventUpdatedText else eventCreatedText}. $invitationSentText"
                                } else {
                                    if (isEditing) eventUpdatedText else eventCreatedText
                                }

                                // Индикатор частичного успеха: событие отправлено, но вложения могли не загрузиться.
                                // Используем мягкую эвристику по изменению локального attachments JSON.
                                val attachmentWarning = if (attachments.isEmpty()) {
                                    false
                                } else if (eventToEdit != null) {
                                    result.data.attachments == eventToEdit.attachments
                                } else {
                                    result.data.attachments.isBlank()
                                }

                                val message = if (attachmentWarning) {
                                    "$messageBase. $eventAttachmentsMayNotUploadText"
                                } else {
                                    messageBase
                                }
                                val showRecurringConversionHint = eventToEdit != null &&
                                    occStartTime == null &&
                                    !eventToEdit.isRecurring &&
                                    recurrenceType >= 0

                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                if (showRecurringConversionHint) {
                                    Toast.makeText(context, recurringConversionHintText, Toast.LENGTH_LONG).show()
                                }
                                showCreateDialog = false
                                editingEventId = null
                                editingOccurrenceStartTime = null
                                cachedOccurrenceEvent = null
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
                    } finally {
                        isCreating = false
                    }
                }
            }
        )
    }
    
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                val eventsRestoredText = Strings.eventsRestored
                val restoringEventsMessage = Strings.restoringEvents(selectedDeletedResolvedIds.size)
                val renamedDeletedText = Strings.eventDeleted
                val deletePermanently = hasDeletedSelected && !hasActiveSelected
                CalendarSelectionTopBar(
                    selectedCount = resolvedSelectedIds.size,
                    showRestore = hasDeletedSelected,
                    showEdit = singleSelectedEditableId != null,
                    deleteIsPermanent = deletePermanently,
                    onClearSelection = { selectedEventIds = emptySet() },
                    onEdit = openSelectedEventForEditing,
                    onRestore = {
                        if (hasDeletedSelected) {
                            val eventsToRestore = deletedEvents.filter { it.id in selectedDeletedResolvedIds }
                            val eventIds = eventsToRestore.map { it.id }
                            selectedEventIds = selectedEventIds.filterNot { id ->
                                id in selectedDeletedResolvedIds ||
                                    (id.contains("_occ_") && id.substringBefore("_occ_") in selectedDeletedResolvedIds)
                            }.toSet()
                            
                            if (eventIds.isNotEmpty()) {
                                deletionController.startDeletion(
                                    emailIds = eventIds,
                                    message = restoringEventsMessage,
                                    scope = scope,
                                    isRestore = true
                                ) { _, onProgress ->
                                    var restored = 0
                                    var lastError: String? = null
                                    for (event in eventsToRestore) {
                                        val result = withContext(Dispatchers.IO) {
                                            calendarRepo.restoreEvent(event)
                                        }
                                        if (result is EasResult.Success) restored++
                                        else if (result is EasResult.Error) lastError = result.message
                                        onProgress(restored, eventsToRestore.size)
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (restored > 0) {
                                            Toast.makeText(context, "$eventsRestoredText: $restored", Toast.LENGTH_SHORT).show()
                                        }
                                        if (lastError != null) {
                                            Toast.makeText(context, lastError, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onDelete = {
                        val deleteTargetIds = if (deletePermanently) selectedDeletedResolvedIds else selectedActiveResolvedIds
                        if (deleteTargetIds.isEmpty()) return@CalendarSelectionTopBar

                        val eventsSnapshot = events + deletedEvents
                        val allOccIds = selectedEventIds.filter { it.contains("_occ_") }.toSet()
                        val renamedSelected = allOccIds.filter { it in renamedOccurrenceIds }.toSet()
                        val seriesOccurrences = allOccIds - renamedSelected
                        val directRecurringOccurrenceIds = if (deletePermanently) {
                            emptySet()
                        } else {
                            selectedEventIds
                                .filter { it in deleteTargetIds && !it.contains("_occ_") }
                                .mapNotNull { selectedId ->
                                    val recurringMaster = eventsSnapshot.find { it.id == selectedId }
                                    if (recurringMaster?.isRecurring == true) {
                                        buildRecurringOccurrenceSelectionId(recurringMaster)
                                    } else {
                                        null
                                    }
                                }
                                .toSet()
                        }

                        val renamedOnlyMasterIds = renamedSelected.map { it.substringBefore("_occ_") }.toSet() -
                            seriesOccurrences.map { it.substringBefore("_occ_") }.toSet()

                        if (renamedSelected.isNotEmpty()) {
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                            val renamedToProcess = renamedSelected.toList()
                            val filteredSnapshot = filteredEvents.toList()
                            val deletedText = renamedDeletedText
                            selectedEventIds = selectedEventIds - renamedSelected
                            scope.launch {
                                var successCount = 0
                                var lastError: String? = null
                                for (occId in renamedToProcess) {
                                    val masterId = occId.substringBefore("_occ_")
                                    val occStart = occId.substringAfter("_occ_").toLongOrNull() ?: continue
                                    val master = eventsSnapshot.find { it.id == masterId } ?: continue
                                    val occEntity = filteredSnapshot.find { it.id == occId }
                                        ?: eventsSnapshot.find { it.id == occId }
                                    val result = withContext(Dispatchers.IO) {
                                        calendarRepo.deleteOccurrence(master, occStart, occEntity)
                                    }
                                    if (result is com.dedovmosol.iwomail.eas.EasResult.Success) successCount++
                                    else if (result is com.dedovmosol.iwomail.eas.EasResult.Error) lastError = result.message
                                }
                                withContext(Dispatchers.Main) {
                                    if (successCount > 0) {
                                        Toast.makeText(context, deletedText, Toast.LENGTH_SHORT).show()
                                    } else if (lastError != null) {
                                        Toast.makeText(context, lastError, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            val remainingTargetIds = deleteTargetIds - renamedOnlyMasterIds
                            if (seriesOccurrences.isEmpty() && remainingTargetIds.isEmpty()) {
                                return@CalendarSelectionTopBar
                            }
                        }

                        val occurrenceDeleteIds = seriesOccurrences + directRecurringOccurrenceIds
                        if (!deletePermanently && occurrenceDeleteIds.isNotEmpty()) {
                            pendingOccurrenceIds = occurrenceDeleteIds
                            deleteConfirmTargetIds = deleteTargetIds - renamedOnlyMasterIds
                            showOccurrenceDeleteChoice = true
                        } else {
                            val finalTargets = deleteTargetIds - renamedOnlyMasterIds
                            if (finalTargets.isNotEmpty()) {
                                deleteConfirmCount = finalTargets.size
                                deleteConfirmIsPermanent = deletePermanently
                                deleteConfirmTargetIds = finalTargets
                                showDeleteConfirmDialog = true
                            }
                        }
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
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            calendarRepo.syncCalendar(accountId)
                                        }
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
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                    } finally {
                                        isSyncing = false
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
            if (dateFilter != CalendarDateFilter.DELETED) {
                com.dedovmosol.iwomail.ui.theme.AnimatedFab(
                    onClick = {
                        editingEventId = null
                        editingOccurrenceStartTime = null
                        cachedOccurrenceEvent = null
                        showCreateDialog = true
                    },
                    containerColor = LocalColorTheme.current.gradientStart
                ) {
                    Icon(AppIcons.Add, Strings.newEvent, tint = Color.White)
                }
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
                        searchFocusRequester = searchFocusRequester,
                        onSearchFocusChanged = { isSearchFocused = it },
                        onEventClick = { event ->
                            if (!isSelectionMode) {
                                selectedEventId = event.id
                            }
                        },
                        listState = listState,
                        selectedEventIds = selectedEventIds,
                        onEventSelectionChange = { eventId, selected ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedEventIds = if (selected) {
                                selectedEventIds + eventId
                            } else {
                                selectedEventIds - eventId
                            }
                        },
                        onEventLongClick = { eventId ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedEventIds = selectedEventIds + eventId
                        },
                        onDragSelect = { newIds -> selectedEventIds = newIds },
                        isLoading = syncedForAccountId != accountId || isSyncing,
                        dateFilter = dateFilter,
                        onDateFilterChange = { selectedEventIds = emptySet(); dateFilter = it },
                        deletedCount = deletedEvents.size,
                        onEmptyTrash = { showEmptyTrashDialog = true }
                    )
                }
                CalendarViewMode.MONTH -> {
                    // Фильтры по дате для MonthView
                    CalendarFilterChips(
                        currentFilter = dateFilter,
                        onFilterChange = { selectedEventIds = emptySet(); dateFilter = it },
                        deletedCount = deletedEvents.size,
                        onEmptyTrash = { showEmptyTrashDialog = true }
                    )
                    MonthView(
                        events = monthViewBaseEvents,
                        selectedDate = selectedDate,
                        onDateSelected = { selectedDateMillis = it.time },
                        onEventClick = { event ->
                            if (!isSelectionMode) {
                                selectedEventId = event.id
                            }
                        },
                        selectedEventIds = selectedEventIds,
                        onEventSelectionChange = { eventId, selected ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedEventIds = if (selected) {
                                selectedEventIds + eventId
                            } else {
                                selectedEventIds - eventId
                            }
                        },
                        onEventLongClick = { eventId ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedEventIds = selectedEventIds + eventId
                        },
                        onDragSelect = { newIds -> selectedEventIds = newIds }
                    )
                }
            }
        }
    }
}

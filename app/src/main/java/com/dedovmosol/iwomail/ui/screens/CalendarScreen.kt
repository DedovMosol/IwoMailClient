package com.dedovmosol.iwomail.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import com.dedovmosol.iwomail.ui.components.LazyColumnScrollbar
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.components.rememberDebouncedState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.repository.CalendarRepository
import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.DraftAttachmentData
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
private val WHITESPACE_REGEX = "\\s+".toRegex()
private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

// Модель вложения события календаря (вне composable для стабильности типа)
private data class CalendarAttachmentInfo(val name: String, val fileReference: String, val size: Long, val isInline: Boolean)

// ThreadLocal гарантирует thread-safety для SimpleDateFormat (каждый поток — свой экземпляр)
private val PARSE_DATE_TIME_FORMAT = java.lang.ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
private val PARSE_DATE_FORMAT = java.lang.ThreadLocal.withInitial { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
private val PARSE_TIME_FORMAT = java.lang.ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

enum class CalendarDateFilter {
    ALL, TODAY, WEEK, MONTH, DELETED
}

/**
 * Разворачивает повторяющиеся события в виртуальные экземпляры (occurrences)
 * для заданного диапазона дат. Не-повторяющиеся события фильтруются по диапазону.
 */
private fun expandRecurringForRange(
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
                        body = occ.body
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
    var isCreating by rememberSaveable { mutableStateOf(false) }

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
    val resolvedSelectedIds = remember(selectedEventIds) {
        selectedEventIds.map { id -> if (id.contains("_occ_")) id.substringBefore("_occ_") else id }.toSet()
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
                ?: run {
                    val lookupId = if (id.contains("_occ_")) id.substringBefore("_occ_") else id
                    events.find { it.id == lookupId } ?: deletedEvents.find { it.id == lookupId }
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
                    selectedEventId = null  // Закрываем диалог СРАЗУ
                    com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                    
                    // Soft-delete (в корзину) — без прогрессбара, просто удаляем
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
            onDismissRequest = { showDeleteConfirmDialog = false },
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
                            val resolvedId = if (id.contains("_occ_")) id.substringBefore("_occ_") else id
                            resolvedId in deleteConfirmTargetIds
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
            onDismissRequest = { showOccurrenceDeleteChoice = false },
            title = { Text(if (isRussian) "Удаление повторяющегося события" else "Delete Recurring Event") },
            text = {
                Text(
                    if (isRussian) "Удалить только выбранные вхождения или всю серию целиком?"
                    else "Delete only selected occurrences or the entire series?",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showOccurrenceDeleteChoice = false
                        pendingOccurrenceIds = emptySet()
                        deleteConfirmCount = deleteConfirmTargetIds.size
                        deleteConfirmIsPermanent = false
                        showDeleteConfirmDialog = true
                    },
                ) { Text(if (isRussian) "Всю серию" else "Entire series", fontSize = 13.sp, maxLines = 1) }
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeButton(
                    onClick = {
                        showOccurrenceDeleteChoice = false
                        com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                        val idsToProcess = pendingOccurrenceIds.toList()
                        val nonOccurrenceResolvedIds = deleteConfirmTargetIds -
                            pendingOccurrenceIds.map { it.substringBefore("_occ_") }.toSet()
                        selectedEventIds = emptySet()
                        pendingOccurrenceIds = emptySet()
                        scope.launch {
                            var successCount = 0
                            var errorMsg: String? = null
                            for (occId in idsToProcess) {
                                val masterId = occId.substringBefore("_occ_")
                                val occStartStr = occId.substringAfter("_occ_")
                                val occStart = occStartStr.toLongOrNull() ?: continue
                                val master = events.find { it.id == masterId } ?: continue
                                val result = withContext(Dispatchers.IO) {
                                    calendarRepo.deleteOccurrence(master, occStart)
                                }
                                if (result is EasResult.Success) successCount++
                                else if (result is EasResult.Error) errorMsg = result.message
                            }
                            if (nonOccurrenceResolvedIds.isNotEmpty()) {
                                val regularToDelete = events.filter { it.id in nonOccurrenceResolvedIds }
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
                ) { Text(if (isRussian) "Только вхождения" else "Only occurrences", fontSize = 13.sp, maxLines = 1) }
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
        val isEditing = editingEvent != null
        val dialogEvent = if (editingOccurrenceStartTime != null && editingEventId != null) {
            cachedOccurrenceEvent ?: editingEvent?.let { master ->
                val exceptions = RecurrenceHelper.parseExceptions(master.exceptions)
                val exc = exceptions.find { it.exceptionStartTime == editingOccurrenceStartTime && !it.deleted }
                val duration = master.endTime - master.startTime
                master.copy(
                    id = "${master.id}_occ_${editingOccurrenceStartTime}",
                    subject = exc?.subject?.ifBlank { master.subject } ?: master.subject,
                    startTime = exc?.startTime?.takeIf { it > 0 } ?: editingOccurrenceStartTime!!,
                    endTime = exc?.endTime?.takeIf { it > 0 } ?: (editingOccurrenceStartTime!! + duration),
                    location = exc?.location?.ifBlank { master.location } ?: master.location,
                    body = exc?.body?.ifBlank { master.body } ?: master.body
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
                                    sensitivity = eventToEdit.sensitivity
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

                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                val deletePermanently = hasDeletedSelected && !hasActiveSelected
                CalendarSelectionTopBar(
                    selectedCount = resolvedSelectedIds.size,
                    showRestore = hasDeletedSelected,
                    deleteIsPermanent = deletePermanently,
                    onClearSelection = { selectedEventIds = emptySet() },
                    onRestore = {
                        if (hasDeletedSelected) {
                            val eventsToRestore = deletedEvents.filter { it.id in selectedDeletedResolvedIds }
                            val eventIds = eventsToRestore.map { it.id }
                            selectedEventIds = selectedEventIds.filterNot { id ->
                                val resolvedId = if (id.contains("_occ_")) id.substringBefore("_occ_") else id
                                resolvedId in selectedDeletedResolvedIds
                            }.toSet()
                            
                            if (eventIds.isNotEmpty()) {
                                deletionController.startDeletion(
                                    emailIds = eventIds,
                                    message = restoringEventsMessage,
                                    scope = scope,
                                    isRestore = true
                                ) { _, onProgress ->
                                    var restored = 0
                                    for (event in eventsToRestore) {
                                        val result = withContext(Dispatchers.IO) {
                                            calendarRepo.restoreEvent(event)
                                        }
                                        if (result is EasResult.Success) restored++
                                        onProgress(restored, eventsToRestore.size)
                                    }
                                    if (restored > 0) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "$eventsRestoredText: $restored", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onDelete = {
                        val deleteTargetIds = if (deletePermanently) selectedDeletedResolvedIds else selectedActiveResolvedIds
                        if (deleteTargetIds.isEmpty()) return@CalendarSelectionTopBar

                        val allOccIds = selectedEventIds.filter { it.contains("_occ_") }.toSet()
                        val renamedSelected = allOccIds.filter { it in renamedOccurrenceIds }.toSet()
                        val seriesOccurrences = allOccIds - renamedSelected

                        if (renamedSelected.isNotEmpty()) {
                            com.dedovmosol.iwomail.util.SoundPlayer.playDeleteSound(context)
                            val renamedToProcess = renamedSelected.toList()
                            val remainingTargetIds = deleteTargetIds -
                                renamedSelected.map { it.substringBefore("_occ_") }.toSet()
                            selectedEventIds = selectedEventIds - renamedSelected
                            scope.launch {
                                for (occId in renamedToProcess) {
                                    val masterId = occId.substringBefore("_occ_")
                                    val occStart = occId.substringAfter("_occ_").toLongOrNull() ?: continue
                                    val master = filteredEvents.find { it.id == masterId } ?: continue
                                    withContext(Dispatchers.IO) {
                                        calendarRepo.deleteOccurrence(master, occStart)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context,
                                        if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) "Удалено" else "Deleted",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (seriesOccurrences.isEmpty() && remainingTargetIds.isEmpty()) {
                                return@CalendarSelectionTopBar
                            }
                        }

                        if (!deletePermanently && seriesOccurrences.isNotEmpty()) {
                            pendingOccurrenceIds = seriesOccurrences
                            deleteConfirmTargetIds = deleteTargetIds -
                                renamedSelected.map { it.substringBefore("_occ_") }.toSet()
                            showOccurrenceDeleteChoice = true
                        } else {
                            val finalTargets = deleteTargetIds -
                                renamedSelected.map { it.substringBefore("_occ_") }.toSet()
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

/**
 * Чипы фильтров по дате для календаря
 */
@Composable
private fun CalendarFilterChips(
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

enum class CalendarViewMode {
    AGENDA, MONTH
}


@Composable
private fun AgendaView(
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
private fun MonthView(
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
private fun EventCard(
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
            val days = mutableSetOf<Int>()
            for (event in events) {
                reuseCal.timeInMillis = event.startTime
                if (reuseCal.get(Calendar.YEAR) == year && reuseCal.get(Calendar.MONTH) == month) {
                    days.add(reuseCal.get(Calendar.DAY_OF_MONTH))
                }
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
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    
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
            val normalized = line.trim().replace(WHITESPACE_REGEX, " ")
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
    val hasMoreContent = cleanBody.isNotBlank() || event.organizer.isNotBlank() || event.organizerName.isNotBlank() || attendees.isNotEmpty() || event.hasAttachments || event.onlineMeetingLink.isNotBlank()
    
    // Диалог подтверждения удаления
    if (showDeleteConfirm) {
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(AppIcons.Delete, null) },
            title = { Text(Strings.deleteEvent) },
            text = { Text(Strings.deleteEventConfirm) },
            confirmButton = {
                com.dedovmosol.iwomail.ui.theme.DeleteButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteClick()
                    },
                    text = Strings.yes
                )
            },
            dismissButton = {
                com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                    onClick = { showDeleteConfirm = false },
                    text = Strings.no
                )
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
                
                // Правило повторения
                if (event.isRecurring && event.recurrenceRule.isNotBlank()) {
                    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    val ruleDescription = RecurrenceHelper.describeRule(event.recurrenceRule, isRussian)
                    if (ruleDescription.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                AppIcons.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ruleDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                    if (event.organizer.isNotBlank() || event.organizerName.isNotBlank()) {
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
                                val displayOrganizer = if (event.organizerName.isNotBlank()) {
                                    event.organizerName
                                } else {
                                    event.organizer.replace(HTML_TAG_REGEX, "")
                                }
                                Text(
                                    text = displayOrganizer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (organizerEmail.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = if (organizerEmail.isNotBlank()) {
                                        Modifier.clickable { onComposeClick(organizerEmail) }
                                    } else Modifier
                                )
                            }
                        }
                    }
                    
                    // Ссылка на онлайн-встречу
                    if (event.onlineMeetingLink.isNotBlank()) {
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .clickable { uriHandler.openUri(event.onlineMeetingLink) }
                        ) {
                            Icon(
                                AppIcons.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) "Онлайн-встреча" else "Online meeting",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    // Вложения
                    if (event.hasAttachments) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (event.attachments.isNotBlank()) {
                            CalendarAttachmentsList(
                                attachmentsJson = event.attachments,
                                accountId = event.accountId,
                                calendarRepo = calendarRepo
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    AppIcons.Attachment,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN)
                                        "Есть вложения (синхронизируйте для загрузки)"
                                    else
                                        "Has attachments (sync to load details)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    contentColor = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                ),
                border = BorderStroke(1.dp, com.dedovmosol.iwomail.ui.theme.AppColors.delete)
            ) {
                Icon(
                    AppIcons.Delete,
                    contentDescription = Strings.delete,
                    modifier = Modifier.size(20.dp),
                    tint = com.dedovmosol.iwomail.ui.theme.AppColors.delete
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
        val cal = Calendar.getInstance()
        events.mapNotNullTo(mutableSetOf()) { event ->
            cal.timeInMillis = event.startTime
            if (cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month)
                cal.get(Calendar.DAY_OF_MONTH) else null
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
    
    data class Part(val content: String, val type: Int, val url: String = "", val linkUrl: String = "")
    
    val parts = remember(text) {
        data class Element(val start: Int, val end: Int, val imageUrl: String, val linkUrl: String, val display: String, val isImage: Boolean, val isClickableImage: Boolean = false)
        val elements = mutableListOf<Element>()
        
        markdownImageLinkPattern.findAll(text).forEach { match ->
            val imgUrl = match.groupValues[1]
            val lnkUrl = match.groupValues[2]
            val isImg = imageExtensions.any { imgUrl.lowercase().contains(it) }
            elements.add(Element(match.range.first, match.range.last + 1, imgUrl, lnkUrl, imgUrl, isImg, isImg))
        }
        
        hrefPattern.findAll(text).forEach { match ->
            val overlaps = elements.any { it.start <= match.range.first && it.end >= match.range.last }
            if (!overlaps) {
                elements.add(Element(match.range.first, match.range.last + 1, match.groupValues[1], match.groupValues[1], match.groupValues[2], false))
            }
        }
        
        urlPattern.findAll(text).forEach { match ->
            val overlaps = elements.any { it.start <= match.range.first && it.end >= match.range.last }
            if (!overlaps) {
                val isImg = imageExtensions.any { match.value.lowercase().contains(it) }
                elements.add(Element(match.range.first, match.range.last + 1, match.value, match.value, match.value, isImg))
            }
        }
        
        elements.sortBy { it.start }
        
        val result = mutableListOf<Part>()
        var lastIndex = 0
        elements.forEach { elem ->
            if (elem.start > lastIndex) {
                result.add(Part(text.substring(lastIndex, elem.start), 0))
            }
            when {
                elem.isClickableImage -> result.add(Part(elem.imageUrl, 3, elem.imageUrl, elem.linkUrl))
                elem.isImage -> result.add(Part(elem.imageUrl, 2, elem.imageUrl))
                else -> result.add(Part(elem.display, 1, elem.linkUrl))
            }
            lastIndex = elem.end
        }
        if (lastIndex < text.length) {
            result.add(Part(text.substring(lastIndex), 0))
        }
        result.toList()
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
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    val maxDim = 2048
                    var sampleSize = 1
                    while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
                        sampleSize *= 2
                    }
                    val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                } finally {
                    conn?.disconnect()
                }
            }
        } catch (e: Exception) { error = true }
        isLoading = false
    }
    
    val safeBitmap = bitmap
    when {
        isLoading -> Box(modifier.height(100.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        !error && safeBitmap != null -> androidx.compose.foundation.Image(
            bitmap = safeBitmap.asImageBitmap(),
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
        attendees: String,
        recurrenceType: Int,
        attachments: List<DraftAttachmentData>,
        removedAttachmentIds: List<String>
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
    // Тип повторения: -1=Нет, 0=Daily, 1=Weekly, 2=Monthly, 5=Yearly
    var recurrenceType by rememberSaveable {
        mutableStateOf(
            if (event?.isRecurring == true && event.recurrenceRule.isNotBlank()) {
                RecurrenceHelper.parseRule(event.recurrenceRule)?.type ?: -1
            } else -1
        )
    }
    // Диалог выбора контактов
    var showContactPicker by rememberSaveable { mutableStateOf(false) }
    
    // Вложения
    var pickedAttachments by remember { mutableStateOf(listOf<DraftAttachmentData>()) }
    var removedExistingAttachmentRefs by remember { mutableStateOf(setOf<String>()) }
    val isRussianPicker = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val maxSingleFile = 7L * 1024 * 1024 // 7 MB — лимит сервера
        val maxTotal = 10L * 1024 * 1024 // 10 MB суммарно
        var currentTotal = pickedAttachments.sumOf { it.data.size.toLong() }
        uris.forEach { uri ->
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "file"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    if (size > maxSingleFile) {
                        val sizeMB = size / 1024 / 1024
                        Toast.makeText(context,
                            if (isRussianPicker) "Файл '$name' слишком большой (${sizeMB} МБ, макс 7 МБ)"
                            else "File '$name' too large (${sizeMB} MB, max 7 MB)",
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    if (currentTotal + size > maxTotal) {
                        Toast.makeText(context,
                            if (isRussianPicker) "Превышен общий лимит вложений (10 МБ)"
                            else "Total attachment limit exceeded (10 MB)",
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    var streamExceededSingleLimit = false
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        var totalRead = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            totalRead += read
                            if (totalRead > maxSingleFile) {
                                streamExceededSingleLimit = true
                                return@use null
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                    if (bytes == null && streamExceededSingleLimit) {
                        val sizeMB = maxSingleFile / 1024 / 1024
                        Toast.makeText(context,
                            if (isRussianPicker) "Файл '$name' слишком большой (${sizeMB} МБ, макс 7 МБ)"
                            else "File '$name' too large (${sizeMB} MB, max 7 MB)",
                            Toast.LENGTH_LONG).show()
                        return@use
                    }
                    if (bytes != null) {
                        if (bytes.size.toLong() > maxSingleFile) {
                            val sizeMB = bytes.size / 1024 / 1024
                            Toast.makeText(context,
                                if (isRussianPicker) "Файл '$name' слишком большой (${sizeMB} МБ, макс 7 МБ)"
                                else "File '$name' too large (${sizeMB} MB, max 7 MB)",
                                Toast.LENGTH_LONG).show()
                            return@use
                        }
                        if (currentTotal + bytes.size > maxTotal) {
                            Toast.makeText(context,
                                if (isRussianPicker) "Превышен общий лимит вложений (10 МБ)"
                                else "Total attachment limit exceeded (10 MB)",
                                Toast.LENGTH_LONG).show()
                            return@use
                        }
                        currentTotal += bytes.size.toLong()
                        pickedAttachments = pickedAttachments + DraftAttachmentData(
                            name = name,
                            mimeType = mimeType,
                            data = bytes
                        )
                    }
                }
            }
        }
    }
    
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
            startDateText = PARSE_DATE_FORMAT.get().format(Date(event.startTime))
            startTimeText = PARSE_TIME_FORMAT.get().format(Date(event.startTime))
            endDateText = PARSE_DATE_FORMAT.get().format(Date(event.endTime))
            endTimeText = PARSE_TIME_FORMAT.get().format(Date(event.endTime))
        } else {
            // Используем текущее время, округлённое до следующего часа
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            
            startDateText = PARSE_DATE_FORMAT.get().format(calendar.time)
            startTimeText = PARSE_TIME_FORMAT.get().format(calendar.time)
            
            calendar.add(Calendar.HOUR_OF_DAY, 1)
            endDateText = PARSE_DATE_FORMAT.get().format(calendar.time)
            endTimeText = PARSE_TIME_FORMAT.get().format(calendar.time)
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
                    // Дата окончания (при повторении — уточняем, что это конец каждого экземпляра)
                    val isRussianLang = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    val endLabel = if (recurrenceType != -1) {
                        if (isRussianLang) "Окончание каждого события" else "End of each event"
                    } else {
                        Strings.endDate
                    }
                    Text(
                        text = endLabel,
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (recurrenceType != -1) {
                        Text(
                            text = if (isRussianLang) "Продолжительность каждого повторения" else "Sets the duration of each occurrence",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    // Повторение — радиокнопки
                    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (isRussian) "Повторение" else "Repeat",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        listOf(
                            -1 to (if (isRussian) "Не повторять" else "No repeat"),
                            0 to (if (isRussian) "Каждый день" else "Daily"),
                            1 to (if (isRussian) "Каждую неделю" else "Weekly"),
                            2 to (if (isRussian) "Каждый месяц" else "Monthly"),
                            5 to (if (isRussian) "Каждый год" else "Yearly")
                        ).forEach { (value, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { recurrenceType = value }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = recurrenceType == value,
                                    onClick = { recurrenceType = value }
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium
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
                
                item {
                    // Вложения
                    val isRussianAtt = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Существующие вложения с сервера (при редактировании)
                        if (isEditing && event != null && event.hasAttachments && event.attachments.isNotBlank()) {
                            val existingAttachments = remember(event.attachments) {
                                try {
                                    val jsonArray = org.json.JSONArray(event.attachments)
                                    (0 until jsonArray.length()).map { i ->
                                        val obj = jsonArray.getJSONObject(i)
                                        CalendarAttachmentInfo(
                                            name = obj.optString("name", "attachment"),
                                            fileReference = obj.optString("fileReference", ""),
                                            size = obj.optLong("size", 0),
                                            isInline = obj.optBoolean("isInline", false)
                                        )
                                    }.filter { !it.isInline }
                                } catch (_: Exception) { emptyList() }
                            }
                            val visibleAttachments = existingAttachments.filter { 
                                it.fileReference !in removedExistingAttachmentRefs 
                            }
                            if (visibleAttachments.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        AppIcons.Attachment,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isRussianAtt) "Текущие вложения (${visibleAttachments.size})" else "Current attachments (${visibleAttachments.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                visibleAttachments.forEach { att ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.fileIconFor(att.name),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color.Unspecified
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = att.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (att.size > 0) {
                                            Text(
                                                text = Strings.formatFileSize(att.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                removedExistingAttachmentRefs = removedExistingAttachmentRefs + att.fileReference
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = AppIcons.Close,
                                                contentDescription = if (isRussianAtt) "Открепить" else "Detach",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = AppIcons.Attachment,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRussianAtt) "Прикрепить файл" else "Attach file")
                        }
                        
                        if (pickedAttachments.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            pickedAttachments.forEachIndexed { index, att ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = AppIcons.fileIconFor(att.name),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.Unspecified
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = att.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = Strings.formatFileSize(att.data.size.toLong()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = {
                                            pickedAttachments = pickedAttachments.toMutableList().also { it.removeAt(index) }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.Close,
                                            contentDescription = if (isRussianAtt) "Удалить" else "Remove",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            LazyColumnScrollbar(lazyListState)
            }
        },
        confirmButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
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
                            return@ThemeOutlinedButton
                        }
                        
                        if (endTime <= startTime) {
                            Toast.makeText(context, endBeforeStartText, Toast.LENGTH_SHORT).show()
                            return@ThemeOutlinedButton
                        }
                        
                        onSave(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, attendees, recurrenceType, pickedAttachments, removedExistingAttachmentRefs.toList())
                    }
                },
                text = Strings.save,
                enabled = isValid,
                isLoading = isCreating
            )
        },
        dismissButton = {
            com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.cancel,
                enabled = !isCreating
            )
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
        PARSE_DATE_TIME_FORMAT.get().parse(dateTimeString)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSelectionTopBar(
    selectedCount: Int,
    showRestore: Boolean,
    deleteIsPermanent: Boolean,
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
            if (showRestore) {
                IconButton(onClick = onRestore) {
                    Icon(AppIcons.Restore, Strings.restore, tint = Color.White)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    if (deleteIsPermanent) AppIcons.DeleteForever else AppIcons.Delete,
                    Strings.delete,
                    tint = Color.White
                )
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
 * Диалог для удалённого события — восстановить или удалить навсегда
 */
@Composable
private fun DeletedEventDetailDialog(
    event: CalendarEventEntity,
    onDismiss: () -> Unit,
    onRestoreClick: () -> Unit,
    onDeletePermanentlyClick: () -> Unit
) {
    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
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
                    text = event.subject.ifBlank { if (isRussian) "Без названия" else "No title" },
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                )
            }
        },
        text = {
            Column {
                Text(
                    text = if (isRussian) "Событие в корзине" else "Event in trash",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Время начала — окончания
                Text(
                    text = "${dateTimeFormat.format(Date(event.startTime))} — ${dateTimeFormat.format(Date(event.endTime))}",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Место
                if (event.location.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Описание
                if (event.body.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = event.body.replace(HTML_TAG_REGEX, "").trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Кнопки: Восстановить / Удалить навсегда
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRestoreClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = LocalColorTheme.current.gradientStart
                        )
                    ) {
                        Icon(
                            AppIcons.Restore,
                            contentDescription = if (isRussian) "Восстановить" else "Restore",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    OutlinedButton(
                        onClick = onDeletePermanentlyClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                        ),
                        border = BorderStroke(1.dp, com.dedovmosol.iwomail.ui.theme.AppColors.delete)
                    ) {
                        Icon(
                            AppIcons.DeleteForever,
                            contentDescription = if (isRussian) "Удалить навсегда" else "Delete permanently",
                            modifier = Modifier.size(20.dp),
                            tint = com.dedovmosol.iwomail.ui.theme.AppColors.delete
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/**
 * Список вложений события календаря с возможностью скачивания.
 * Поведение аналогично вложениям писем (EmailDetailScreen):
 * - Tap: предпросмотр через временный файл (cache)
 * - Меню: "Просмотр" / "Сохранить" / "Сохранить как"
 * - Для "Сохранить": дефолтный путь Downloads/IwoMail/Calendar/
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CalendarAttachmentsList(
    attachmentsJson: String,
    accountId: Long,
    calendarRepo: CalendarRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
    
    val attachments = remember(attachmentsJson) {
        try {
            val jsonArray = org.json.JSONArray(attachmentsJson)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                CalendarAttachmentInfo(
                    name = obj.optString("name", "attachment"),
                    fileReference = obj.optString("fileReference", ""),
                    size = obj.optLong("size", 0),
                    isInline = obj.optBoolean("isInline", false)
                )
            }.filter { it.fileReference.isNotBlank() && !it.isInline }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    if (attachments.isEmpty()) return
    
    // Save As: системный файл-пикер
    var pendingSaveAsAtt by remember { mutableStateOf<CalendarAttachmentInfo?>(null) }
    var pendingPreviewFile by remember { mutableStateOf<java.io.File?>(null) }
    var downloadingRef by remember { mutableStateOf<String?>(null) }
    val previewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Удаляем временный файл после возврата из просмотрщика
        pendingPreviewFile?.delete()
        pendingPreviewFile = null
    }
    val saveAsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val att = pendingSaveAsAtt ?: return@rememberLauncherForActivityResult
        pendingSaveAsAtt = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            downloadingRef = att.fileReference
            try {
                when (val result = calendarRepo.downloadCalendarAttachment(accountId, att.fileReference)) {
                    is EasResult.Success -> {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(result.data) }
                        }
                        Toast.makeText(context, if (isRussian) "Файл сохранён" else "File saved", Toast.LENGTH_SHORT).show()
                    }
                    is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            } finally {
                downloadingRef = null
            }
        }
    }
    
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.Attachment,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isRussian) "Вложения (${attachments.size})" else "Attachments (${attachments.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        
        attachments.forEach { att ->
            val isDownloading = downloadingRef == att.fileReference
            var showSaveMenu by remember { mutableStateOf(false) }
            val openPreview = {
                downloadingRef = att.fileReference
                scope.launch {
                    try {
                        when (val result = calendarRepo.downloadCalendarAttachment(accountId, att.fileReference)) {
                            is EasResult.Success -> {
                                val safeFileName = att.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                val tempFile = withContext(Dispatchers.IO) {
                                    val previewDir = java.io.File(context.cacheDir, "calendar_preview")
                                    if (!previewDir.exists()) previewDir.mkdirs()
                                    java.io.File(previewDir, safeFileName).apply {
                                        writeBytes(result.data)
                                    }
                                }

                                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(java.io.File(att.name).extension.lowercase(Locale.ROOT))
                                    ?: "application/octet-stream"

                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    tempFile
                                )

                                pendingPreviewFile = tempFile
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mimeType)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    previewLauncher.launch(intent)

                                    // Fallback: если внешнее приложение не вернёт result, чистим позже
                                    scope.launch {
                                        kotlinx.coroutines.delay(60 * 60 * 1000L)
                                        if (pendingPreviewFile?.absolutePath == tempFile.absolutePath) {
                                            pendingPreviewFile?.delete()
                                            pendingPreviewFile = null
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    withContext(Dispatchers.IO) { tempFile.delete() }
                                    pendingPreviewFile = null
                                    Toast.makeText(
                                        context,
                                        if (isRussian) "Нет приложения для просмотра файла" else "No app to preview this file",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: (if (isRussian) "Ошибка просмотра" else "Preview error"), Toast.LENGTH_LONG).show()
                    } finally {
                        downloadingRef = null
                    }
                }
                Unit
            }
            
            Box {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .combinedClickable(
                            enabled = !isDownloading,
                            onClick = openPreview,
                            onLongClick = { showSaveMenu = true }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            AppIcons.fileIconFor(att.name),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = att.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (att.size > 0) {
                                val sizeStr = when {
                                    att.size < 1024 -> "${att.size} B"
                                    att.size < 1024 * 1024 -> "${att.size / 1024} KB"
                                    else -> String.format("%.1f MB", att.size / (1024.0 * 1024.0))
                                }
                                Text(
                                    text = sizeStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(
                                onClick = { showSaveMenu = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    AppIcons.MoreVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                DropdownMenu(
                    expanded = showSaveMenu,
                    onDismissRequest = { showSaveMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isRussian) "Просмотр" else "Preview") },
                        onClick = {
                            showSaveMenu = false
                            openPreview()
                        },
                        leadingIcon = { Icon(AppIcons.Visibility, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(com.dedovmosol.iwomail.ui.Strings.save) },
                        onClick = {
                            showSaveMenu = false
                            downloadingRef = att.fileReference
                            scope.launch {
                                try {
                                    when (val result = calendarRepo.downloadCalendarAttachment(accountId, att.fileReference)) {
                                        is EasResult.Success -> {
                                            val safeFileName = att.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                            withContext(Dispatchers.IO) {
                                                val contentValues = android.content.ContentValues().apply {
                                                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeFileName)
                                                    put(android.provider.MediaStore.Downloads.MIME_TYPE,
                                                        android.webkit.MimeTypeMap.getSingleton()
                                                            .getMimeTypeFromExtension(java.io.File(safeFileName).extension) ?: "application/octet-stream")
                                                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/IwoMail/Calendar")
                                                }
                                                val uri = context.contentResolver.insert(
                                                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues
                                                )
                                                uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> out.write(result.data) } }
                                            }
                                            Toast.makeText(context, if (isRussian) "Сохранено в Downloads/IwoMail/Calendar/" else "Saved to Downloads/IwoMail/Calendar/", Toast.LENGTH_SHORT).show()
                                        }
                                        is EasResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                                } finally {
                                    downloadingRef = null
                                }
                            }
                        },
                        leadingIcon = { Icon(AppIcons.Download, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(com.dedovmosol.iwomail.ui.Strings.saveAs) },
                        onClick = {
                            showSaveMenu = false
                            pendingSaveAsAtt = att
                            saveAsLauncher.launch(att.name)
                        },
                        leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) }
                    )
                }
            }
        }
    }
}

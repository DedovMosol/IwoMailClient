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
 * Р Р°Р·РІРѕСЂР°С‡РёРІР°РµС‚ РїРѕРІС‚РѕСЂСЏСЋС‰РёРµСЃСЏ СЃРѕР±С‹С‚РёСЏ РІ РІРёСЂС‚СѓР°Р»СЊРЅС‹Рµ СЌРєР·РµРјРїР»СЏСЂС‹ (occurrences)
 * РґР»СЏ Р·Р°РґР°РЅРЅРѕРіРѕ РґРёР°РїР°Р·РѕРЅР° РґР°С‚. РќРµ-РїРѕРІС‚РѕСЂСЏСЋС‰РёРµСЃСЏ СЃРѕР±С‹С‚РёСЏ С„РёР»СЊС‚СЂСѓСЋС‚СЃСЏ РїРѕ РґРёР°РїР°Р·РѕРЅСѓ.
 */
internal fun expandRecurringForRange(
    events: List<CalendarEventEntity>,
    rangeStart: Long,
    rangeEnd: Long
): List<CalendarEventEntity> {
    val result = mutableListOf<CalendarEventEntity>()
    
    for (event in events) {
        if (event.isRecurring && event.recurrenceRule.isNotBlank()) {
            // Р“РµРЅРµСЂРёСЂСѓРµРј СЌРєР·РµРјРїР»СЏСЂС‹ СЃРµСЂРёРё РІ РґРёР°РїР°Р·РѕРЅРµ
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
                // Fallback: РµСЃР»Рё РЅРµ СѓРґР°Р»РѕСЃСЊ СЃРіРµРЅРµСЂРёСЂРѕРІР°С‚СЊ вЂ” РїРѕРєР°Р·С‹РІР°РµРј РѕСЂРёРіРёРЅР°Р» (РµСЃР»Рё РІ РґРёР°РїР°Р·РѕРЅРµ)
                if ((event.startTime in rangeStart until rangeEnd) ||
                    (event.startTime < rangeEnd && event.endTime > rangeStart)) {
                    result.add(event)
                }
            }
        } else {
            // РќРµ-РїРѕРІС‚РѕСЂСЏСЋС‰РµРµСЃСЏ: СЃС‚Р°РЅРґР°СЂС‚РЅР°СЏ РїСЂРѕРІРµСЂРєР° РїРѕРїР°РґР°РЅРёСЏ РІ РґРёР°РїР°Р·РѕРЅ
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
    
    // РћС‚РґРµР»СЊРЅС‹Р№ scope РґР»СЏ СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёРё, С‡С‚РѕР±С‹ РЅРµ РѕС‚РјРµРЅСЏР»Р°СЃСЊ РїСЂРё РЅР°РІРёРіР°С†РёРё
    val syncScope = com.dedovmosol.iwomail.ui.components.rememberSyncScope()
    
    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L

    val events by remember(accountId) { calendarRepo.getEvents(accountId) }.collectAsState(initial = emptyList())
    val deletedEvents by remember(accountId) { calendarRepo.getDeletedEvents(accountId) }.collectAsState(initial = emptyList())
    
    // ID Р°РєРєР°СѓРЅС‚Р°, РґР»СЏ РєРѕС‚РѕСЂРѕРіРѕ СѓР¶Рµ Р±С‹Р» Р·Р°РїСѓС‰РµРЅ Р°РІС‚РѕСЃРёРЅРє.
    // rememberSaveable: СЃРѕС…СЂР°РЅСЏРµС‚СЃСЏ РїСЂРё РїРѕРІРѕСЂРѕС‚Рµ в†’ РЅРµ Р·Р°РїСѓСЃРєР°РµС‚ РїРѕРІС‚РѕСЂРЅС‹Р№ СЃРёРЅРє РґР»СЏ С‚РѕРіРѕ Р¶Рµ Р°РєРєР°СѓРЅС‚Р°.
    // РџСЂРё СЃРјРµРЅРµ accountId Р·РЅР°С‡РµРЅРёРµ РЅРµ СЃРѕРІРїР°РґС‘С‚ в†’ СЃРёРЅРє Р·Р°РїСѓСЃС‚РёС‚СЃСЏ РґР»СЏ РЅРѕРІРѕРіРѕ Р°РєРєР°СѓРЅС‚Р°.
    var syncedForAccountId by rememberSaveable { mutableStateOf(0L) }

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val debouncedSearchQuery by rememberDebouncedState(searchQuery)
    
    // РЎРѕС…СЂР°РЅРµРЅРёРµ С„РѕРєСѓСЃР° РїРѕРёСЃРєР° РїСЂРё РїРѕРІРѕСЂРѕС‚Рµ СЌРєСЂР°РЅР°
    val searchFocusRequester = remember { FocusRequester() }
    var isSearchFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isSearchFocused) {
        if (isSearchFocused) {
            kotlinx.coroutines.delay(100)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
    var isSyncing by remember { mutableStateOf(false) }
    
    // РђРІС‚РѕРјР°С‚РёС‡РµСЃРєР°СЏ СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёСЏ РїСЂРё РїРµСЂРІРѕРј РѕС‚РєСЂС‹С‚РёРё СЌРєСЂР°РЅР° РёР»Рё СЃРјРµРЅРµ Р°РєРєР°СѓРЅС‚Р°.
    // syncedForAccountId != accountId в†’ СЃРёРЅРє РЅРµ Р·Р°РїСѓСЃРєР°Р»СЃСЏ РґР»СЏ СЌС‚РѕРіРѕ Р°РєРєР°СѓРЅС‚Р° в†’ Р·Р°РїСѓСЃРєР°РµРј.
    // РџСЂРё РїРѕРІРѕСЂРѕС‚Рµ СЌРєСЂР°РЅР° accountId РЅРµ РјРµРЅСЏРµС‚СЃСЏ в†’ syncedForAccountId СЃРѕРІРїР°РґР°РµС‚ в†’ СЃРёРЅРє РЅРµ РїРѕРІС‚РѕСЂСЏРµС‚СЃСЏ.
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

    // Р РµРґР°РєС‚РёСЂРѕРІР°РЅРёРµ РѕРґРЅРѕРіРѕ РІС…РѕР¶РґРµРЅРёСЏ РїРѕРІС‚РѕСЂСЏСЋС‰РµРіРѕСЃСЏ СЃРѕР±С‹С‚РёСЏ
    var editingOccurrenceStartTime by rememberSaveable { mutableStateOf<Long?>(null) }
    var cachedOccurrenceEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    var showEditChoiceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingEditOccurrenceId by rememberSaveable { mutableStateOf<String?>(null) }
    
    // РњРЅРѕР¶РµСЃС‚РІРµРЅРЅС‹Р№ РІС‹Р±РѕСЂ
    val haptic = LocalHapticFeedback.current
    var selectedEventIds by rememberSaveable(
        saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })
    ) { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedEventIds.isNotEmpty()
    
    // РћРїСЂРµРґРµР»СЏРµРј, РµСЃС‚СЊ Р»Рё СЃСЂРµРґРё РІС‹РґРµР»РµРЅРЅС‹С… СѓРґР°Р»С‘РЅРЅС‹Рµ СЃРѕР±С‹С‚РёСЏ (РґР»СЏ РєРѕСЂСЂРµРєС‚РЅРѕРіРѕ TopBar/РґРёР°Р»РѕРіР°)
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
    
    // Р”РёР°Р»РѕРі РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ СѓРґР°Р»РµРЅРёСЏ
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
    // Р”РёР°Р»РѕРі РІС‹Р±РѕСЂР°: СѓРґР°Р»РёС‚СЊ РІС…РѕР¶РґРµРЅРёРµ РёР»Рё СЃРµСЂРёСЋ
    var showOccurrenceDeleteChoice by rememberSaveable { mutableStateOf(false) }
    var pendingOccurrenceIds by rememberSaveable(
        saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })
    ) { mutableStateOf(setOf<String>()) }
    
    // РЎРѕСЃС‚РѕСЏРЅРёРµ СЃРїРёСЃРєР° РґР»СЏ Р°РІС‚РѕСЃРєСЂРѕР»Р»Р°
    val listState = rememberLazyListState()
    
    // Р¤РёР»СЊС‚СЂ РїРѕ РґР°С‚Рµ (Р°РЅР°Р»РѕРіРёС‡РЅРѕ Р·Р°РґР°С‡Р°Рј)
    var dateFilter by rememberSaveable { mutableStateOf(initialDateFilter) }
    
    // РћР±СЂР°Р±РѕС‚РєР° РєРЅРѕРїРєРё Back РІ СЂРµР¶РёРјРµ РІС‹Р±РѕСЂР°
    androidx.activity.compose.BackHandler(enabled = isSelectionMode) {
        selectedEventIds = emptySet()
    }
    
    // Р¤РёР»СЊС‚СЂР°С†РёСЏ РїРѕ РїРѕРёСЃРєСѓ Рё РїРѕ РґР°С‚Рµ (СЃ СЂР°Р·РІРѕСЂР°С‡РёРІР°РЅРёРµРј РїРѕРІС‚РѕСЂСЏСЋС‰РёС…СЃСЏ СЃРѕР±С‹С‚РёР№)
    val filteredEvents = remember(events, deletedEvents, debouncedSearchQuery, dateFilter) {
        // РЎРЅР°С‡Р°Р»Р° С„РёР»СЊС‚СЂСѓРµРј РїРѕ РґР°С‚Рµ
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
        // Р—Р°С‚РµРј С„РёР»СЊС‚СЂСѓРµРј РїРѕ РїРѕРёСЃРєСѓ
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
    
    // Р’РёСЂС‚СѓР°Р»СЊРЅС‹Рµ occurrence (_occ_) РёС‰СѓС‚СЃСЏ РІ filteredEvents (СЃРѕРґРµСЂР¶РёС‚ СЂР°Р·РІС‘СЂРЅСѓС‚С‹Рµ РїРѕРІС‚РѕСЂРµРЅРёСЏ),
    // С‡С‚РѕР±С‹ РїРѕРєР°Р·Р°С‚СЊ РєРѕСЂСЂРµРєС‚РЅРѕРµ РІСЂРµРјСЏ occurrence, Р° РЅРµ Р±Р°Р·РѕРІРѕРіРѕ СЃРѕР±С‹С‚РёСЏ.
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

    // Р”РёР°Р»РѕРі РїСЂРѕСЃРјРѕС‚СЂР° СЃРѕР±С‹С‚РёСЏ
    selectedEvent?.let { event ->
        val eventDeletedText = Strings.eventDeleted
        val deletingOneEventText = Strings.deletingEvents(1)
        val restoringOneEventText = Strings.restoringEvents(1)
        val undoText = Strings.undo
        val eventsRestoredText = Strings.eventsRestored
        val eventRestoredText = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) "РЎРѕР±С‹С‚РёРµ РІРѕСЃСЃС‚Р°РЅРѕРІР»РµРЅРѕ" else "Event restored"
        val eventDeletedPermanentlyText = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) "РЎРѕР±С‹С‚РёРµ СѓРґР°Р»РµРЅРѕ РЅР°РІСЃРµРіРґР°" else "Event permanently deleted"
        
        if (event.isDeleted) {
            // Р”РёР°Р»РѕРі РґР»СЏ СѓРґР°Р»С‘РЅРЅРѕРіРѕ СЃРѕР±С‹С‚РёСЏ вЂ” РІРѕСЃСЃС‚Р°РЅРѕРІРёС‚СЊ РёР»Рё СѓРґР°Р»РёС‚СЊ РЅР°РІСЃРµРіРґР°
            DeletedEventDetailDialog(
                event = event,
                onDismiss = { selectedEventId = null },
                onRestoreClick = {
                    selectedEventId = null  // Р—Р°РєСЂС‹РІР°РµРј РґРёР°Р»РѕРі РЎР РђР—РЈ
                    
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
            // Р”Р»СЏ РІРёСЂС‚СѓР°Р»СЊРЅС‹С… occurrence вЂ” РЅР°С…РѕРґРёРј РѕСЂРёРіРёРЅР°Р»СЊРЅРѕРµ СЃРѕР±С‹С‚РёРµ РІ Р‘Р”
            val originalEvent = if (event.id.contains("_occ_")) {
                val originalId = event.id.substringBefore("_occ_")
                events.find { it.id == originalId } ?: event
            } else {
                event
            }
            
            // РћР±С‹С‡РЅС‹Р№ РґРёР°Р»РѕРі РґР»СЏ Р°РєС‚РёРІРЅРѕРіРѕ СЃРѕР±С‹С‚РёСЏ (РїРѕРєР°Р·С‹РІР°РµРј РґР°РЅРЅС‹Рµ occurrence, РЅРѕ РѕРїРµСЂР°С†РёРё вЂ” РЅР°Рґ РѕСЂРёРіРёРЅР°Р»РѕРј)
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
    
    // Р”РёР°Р»РѕРі РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ СѓРґР°Р»РµРЅРёСЏ СЃРѕР±С‹С‚РёР№
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
                        
                        // РС‰РµРј РІС‹Р±СЂР°РЅРЅС‹Рµ СЃРѕР±С‹С‚РёСЏ РІ РѕР±РѕРёС… СЃРїРёСЃРєР°С…
                        // Р”Р»СЏ РІРёСЂС‚СѓР°Р»СЊРЅС‹С… occurrence ID (СЃРѕРґРµСЂР¶Р°С‚ _occ_) Р±РµСЂС‘Рј РѕСЂРёРіРёРЅР°Р»СЊРЅС‹Р№ ID СЃРµСЂРёРё
                        val eventsToDelete = (events + deletedEvents).filter { it.id in deleteConfirmTargetIds }
                        val eventIds = eventsToDelete.map { it.id }
                        selectedEventIds = selectedEventIds.filterNot { id ->
                            id in deleteConfirmTargetIds ||
                                (id.contains("_occ_") && id.substringBefore("_occ_") in deleteConfirmTargetIds)
                        }.toSet()
                        
                        if (eventIds.isNotEmpty()) {
                            if (deleteConfirmIsPermanent) {
                                // РћРєРѕРЅС‡Р°С‚РµР»СЊРЅРѕРµ СѓРґР°Р»РµРЅРёРµ РёР· РєРѕСЂР·РёРЅС‹ вЂ” СЃ РїСЂРѕРіСЂРµСЃСЃР±Р°СЂРѕРј
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
                                // Soft-delete (РІ РєРѕСЂР·РёРЅСѓ) С‡РµСЂРµР· РµРґРёРЅС‹Р№ EasClient (deleteEvents).
                                // Р Р°РЅСЊС€Рµ РґР»СЏ РєР°Р¶РґРѕРіРѕ СЃРѕР±С‹С‚РёСЏ СЃРѕР·РґР°РІР°Р»СЃСЏ РѕС‚РґРµР»СЊРЅС‹Р№ EasClient в†’
                                // РґСѓР±Р»РёСЂРѕРІР°РЅРёРµ NTLM-С…СЌРЅРґС€РµР№РєРѕРІ Рё РєРѕРЅС„Р»РёРєС‚ SyncKey (EAS).
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
    
    // Р”РёР°Р»РѕРі РІС‹Р±РѕСЂР°: СѓРґР°Р»РёС‚СЊ РєРѕРЅРєСЂРµС‚РЅРѕРµ РІС…РѕР¶РґРµРЅРёРµ РёР»Рё РІСЃСЋ СЃРµСЂРёСЋ
    if (showOccurrenceDeleteChoice) {
        val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = {
                showOccurrenceDeleteChoice = false
                pendingOccurrenceIds = emptySet()
                deleteConfirmTargetIds = emptySet()
            },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(if (isRussian) "РЈРґР°Р»РµРЅРёРµ РїРѕРІС‚РѕСЂСЏСЋС‰РµРіРѕСЃСЏ\nСЃРѕР±С‹С‚РёСЏ" else "Delete Recurring\nEvent", textAlign = TextAlign.Center) },
            text = {
                Text(
                    if (isRussian) "РЈРґР°Р»РёС‚СЊ С‚РѕР»СЊРєРѕ РІС‹Р±СЂР°РЅРЅС‹Рµ РІС…РѕР¶РґРµРЅРёСЏ\nРёР»Рё РІСЃСЋ СЃРµСЂРёСЋ С†РµР»РёРєРѕРј?"
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
                                    Toast.makeText(context, if (isRussian) "РЈРґР°Р»РµРЅРѕ" else "Deleted", Toast.LENGTH_SHORT).show()
                                } else if (errorMsg != null) {
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    text = if (isRussian) "РўРѕР»СЊРєРѕ\nРІС…РѕР¶РґРµРЅРёСЏ" else "Only\noccurrences"
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
                    text = if (isRussian) "Р’СЃСЋ\nСЃРµСЂРёСЋ" else "Entire\nseries"
                )
            }
        )
    }

    // Р”РёР°Р»РѕРі РѕС‡РёСЃС‚РєРё РєРѕСЂР·РёРЅС‹ РєР°Р»РµРЅРґР°СЂСЏ
    if (showEmptyTrashDialog) {
        val isRussian = com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN
        val trashEmptiedText = if (isRussian) "РљРѕСЂР·РёРЅР° РѕС‡РёС‰РµРЅР°" else "Trash emptied"
        
        com.dedovmosol.iwomail.ui.theme.StyledAlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            icon = { Icon(AppIcons.DeleteForever, null) },
            title = { Text(if (isRussian) "РћС‡РёСЃС‚РёС‚СЊ РєРѕСЂР·РёРЅСѓ?" else "Empty trash?") },
            text = { 
                Text(
                    if (isRussian) "РЈРґР°Р»РёС‚СЊ РЅР°РІСЃРµРіРґР° ${deletedEvents.size} СЃРѕР±С‹С‚РёР№ РёР· РєРѕСЂР·РёРЅС‹?"
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
    
    // Р”РёР°Р»РѕРі РІС‹Р±РѕСЂР°: В«РР·РјРµРЅРёС‚СЊ СЌС‚Рѕ РІС…РѕР¶РґРµРЅРёРµВ» РёР»Рё В«РР·РјРµРЅРёС‚СЊ РІСЃСЋ СЃРµСЂРёСЋВ»
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

    // Р”РёР°Р»РѕРі СЃРѕР·РґР°РЅРёСЏ/СЂРµРґР°РєС‚РёСЂРѕРІР°РЅРёСЏ СЃРѕР±С‹С‚РёСЏ
    if (showCreateDialog) {
        val eventUpdatedText = Strings.eventUpdated
        val eventCreatedText = Strings.eventCreated
        val invitationSentText = Strings.invitationSent
        val eventDeletedText = Strings.error
        val eventAttachmentsMayNotUploadText = Strings.eventAttachmentsMayNotUpload
        val recurringConversionHintText = if (com.dedovmosol.iwomail.ui.LocalLanguage.current == com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) {
            "РЎРѕР±С‹С‚РёРµ СЃС‚Р°Р»Рѕ РїРѕРІС‚РѕСЂСЏСЋС‰РёРјСЃСЏ. РСЃРїРѕР»СЊР·СѓР№С‚Рµ С„РёР»СЊС‚СЂ \"${Strings.allDates}\", С‡С‚РѕР±С‹ СѓРІРёРґРµС‚СЊ РІСЃРµ РІС…РѕР¶РґРµРЅРёСЏ."
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
                // Р—Р°С‰РёС‚Р° РѕС‚ double-tap: РµСЃР»Рё СѓР¶Рµ СЃРѕР·РґР°С‘Рј вЂ” РёРіРЅРѕСЂРёСЂСѓРµРј
                if (isCreating) return@CreateEventDialog
                isCreating = true
                scope.launch {
                    try {
                        // РџР°СЂСЃРёРј СЃРїРёСЃРѕРє СѓС‡Р°СЃС‚РЅРёРєРѕРІ
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
                                // РќР• РІС‹Р·С‹РІР°РµРј syncCalendar() Р·РґРµСЃСЊ:
                                // createEvent() Рё updateEvent() СѓР¶Рµ СЃРёРЅС…СЂРѕРЅРёР·РёСЂСѓСЋС‚ РІРЅСѓС‚СЂРё СЃРµР±СЏ.
                                // РџРѕРІС‚РѕСЂРЅС‹Р№ sync РѕРїР°СЃРµРЅ: Exchange РјРѕР¶РµС‚ РЅРµ СѓСЃРїРµС‚СЊ РїСЂРѕРёРЅРґРµРєСЃРёСЂРѕРІР°С‚СЊ
                                // РЅРѕРІРѕРµ СЃРѕР±С‹С‚РёРµ в†’ sync РЅРµ СѓРІРёРґРёС‚ РµРіРѕ в†’ СѓРґР°Р»РёС‚ Р»РѕРєР°Р»СЊРЅРѕ.
                                val messageBase = if (attendeeList.isNotEmpty()) {
                                    "${if (isEditing) eventUpdatedText else eventCreatedText}. $invitationSentText"
                                } else {
                                    if (isEditing) eventUpdatedText else eventCreatedText
                                }

                                // РРЅРґРёРєР°С‚РѕСЂ С‡Р°СЃС‚РёС‡РЅРѕРіРѕ СѓСЃРїРµС…Р°: СЃРѕР±С‹С‚РёРµ РѕС‚РїСЂР°РІР»РµРЅРѕ, РЅРѕ РІР»РѕР¶РµРЅРёСЏ РјРѕРіР»Рё РЅРµ Р·Р°РіСЂСѓР·РёС‚СЊСЃСЏ.
                                // РСЃРїРѕР»СЊР·СѓРµРј РјСЏРіРєСѓСЋ СЌРІСЂРёСЃС‚РёРєСѓ РїРѕ РёР·РјРµРЅРµРЅРёСЋ Р»РѕРєР°Р»СЊРЅРѕРіРѕ attachments JSON.
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
                                // РђРІС‚РѕСЃРєСЂРѕР»Р» РІРІРµСЂС… РїРѕСЃР»Рµ СЃРѕР·РґР°РЅРёСЏ (СЃ Р·Р°РґРµСЂР¶РєРѕР№ РґР»СЏ РѕР±РЅРѕРІР»РµРЅРёСЏ СЃРїРёСЃРєР°)
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
                        // РџРµСЂРµРєР»СЋС‡РµРЅРёРµ СЂРµР¶РёРјР° РїСЂРѕСЃРјРѕС‚СЂР°
                        IconButton(
                            onClick = {
                                selectedEventIds = emptySet() // РЎР±СЂР°СЃС‹РІР°РµРј РІС‹Р±РѕСЂ РїСЂРё СЃРјРµРЅРµ СЂРµР¶РёРјР°
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
                        
                        // РљРЅРѕРїРєР° СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёРё
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
                    // Р¤РёР»СЊС‚СЂС‹ РїРѕ РґР°С‚Рµ РґР»СЏ MonthView
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

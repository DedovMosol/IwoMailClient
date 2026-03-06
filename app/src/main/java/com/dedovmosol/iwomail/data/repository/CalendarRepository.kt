package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.sync.CalendarReminderReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Репозиторий для работы с календарём Exchange
 */
class CalendarRepository(private val context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val calendarEventDao = database.calendarEventDao()
    private val accountRepo = RepositoryProvider.getAccountRepository(context)
    
    companion object {
        private val syncLocks = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()
        private fun getSyncMutex(accountId: Long) = syncLocks.computeIfAbsent(accountId) { kotlinx.coroutines.sync.Mutex() }

        /**
         * Защита от "воскрешения" удалённых событий.
         * Множество serverId событий, удалённых пользователем.
         * КРИТИЧНО: Персистится через SharedPreferences чтобы пережить рестарт приложения!
         * Без персистенции при убийстве процесса набор терялся, и при следующей
         * синхронизации удалённые события "воскресали".
         * Записи удаляются ТОЛЬКО когда syncCalendar() подтверждает,
         * что сервер больше не возвращает эти события.
         */
        private val deletedServerIds = java.util.LinkedHashSet<String>()
        
        private const val PREFS_NAME = "calendar_deleted_ids"
        private const val KEY_DELETED_IDS = "deleted_server_ids"
        private const val MAX_DELETED_IDS = 1000
        @Volatile private var prefsInitialized = false
        
        private fun initFromPrefs(context: Context) {
            if (prefsInitialized) return
            synchronized(deletedServerIds) {
                if (prefsInitialized) return
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val saved = prefs.getStringSet(KEY_DELETED_IDS, emptySet())?.toSet() ?: emptySet()
                    deletedServerIds.addAll(saved)
                } catch (_: Exception) { }
                prefsInitialized = true
            }
        }
        
        private fun saveToPrefs(context: Context) {
            try {
                val snapshot = synchronized(deletedServerIds) { deletedServerIds.toSet() }
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putStringSet(KEY_DELETED_IDS, snapshot).apply()
            } catch (_: Exception) { }
        }
        
        private fun markAsDeleted(serverId: String, context: Context? = null) {
            if (serverId.isNotBlank()) {
                synchronized(deletedServerIds) {
                    if (deletedServerIds.size >= MAX_DELETED_IDS) {
                        val excess = deletedServerIds.size - MAX_DELETED_IDS / 2
                        if (excess > 0) {
                            val iter = deletedServerIds.iterator()
                            var removed = 0
                            while (iter.hasNext() && removed < excess) {
                                iter.next()
                                iter.remove()
                                removed++
                            }
                        }
                    }
                    deletedServerIds.add(serverId)
                }
                context?.let { saveToPrefs(it) }
            }
        }
        
        private fun isMarkedAsDeleted(serverId: String): Boolean {
            return synchronized(deletedServerIds) { deletedServerIds.contains(serverId) }
        }
        
        private fun removeFromDeleted(serverId: String, context: Context? = null) {
            synchronized(deletedServerIds) { deletedServerIds.remove(serverId) }
            context?.let { saveToPrefs(it) }
        }
        
        /**
         * Вызывается из syncCalendar() — убирает из защитного множества
         * те serverId, которых сервер уже НЕ возвращает (реально удалены).
         * КРИТИЧНО: Убираем ТОЛЬКО те ID, которые:
         *   1) были помечены как удалённые пользователем (в deletedServerIds)
         *   2) сервер ПОДТВЕРДИЛ удаление (НЕ вернул в ответе)
         * Если сервер ЕЩЁ возвращает событие — защиту НЕ снимаем!
         */
        private fun confirmServerDeletions(serverReturnedIds: Set<String>, context: Context? = null) {
            val removed = synchronized(deletedServerIds) {
                deletedServerIds.removeIf { it !in serverReturnedIds }
            }
            if (removed) {
                context?.let { saveToPrefs(it) }
            }
        }
    }
    
    private fun normalizeAllDayTimes(startTime: Long, endTime: Long): Pair<Long, Long> {
        val local = java.util.Calendar.getInstance()
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        local.timeInMillis = startTime
        utc.clear()
        utc.set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
        val normalizedStart = utc.timeInMillis
        local.timeInMillis = endTime
        utc.clear()
        utc.set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
        utc.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val normalizedEnd = utc.timeInMillis
        return normalizedStart to normalizedEnd
    }
    
    // === Получение событий ===
    
    fun getEvents(accountId: Long): Flow<List<CalendarEventEntity>> {
        return calendarEventDao.getEventsByAccount(accountId)
    }
    
    suspend fun getEventsList(accountId: Long): List<CalendarEventEntity> {
        return calendarEventDao.getEventsByAccountList(accountId)
    }
    
    fun getEventsForDay(accountId: Long, date: Date): Flow<List<CalendarEventEntity>> {
        val (startOfDay, endOfDay) = com.dedovmosol.iwomail.util.DateUtils.getDayRange(date)
        return calendarEventDao.getEventsForDay(accountId, startOfDay, endOfDay)
    }

    suspend fun getEventsForDayList(accountId: Long, date: Date): List<CalendarEventEntity> {
        val (startOfDay, endOfDay) = com.dedovmosol.iwomail.util.DateUtils.getDayRange(date)
        return calendarEventDao.getEventsForDayList(accountId, startOfDay, endOfDay)
    }
    
    suspend fun getEventsCountForDay(accountId: Long, date: Date): Int {
        val (startOfDay, endOfDay) = com.dedovmosol.iwomail.util.DateUtils.getDayRange(date)
        return calendarEventDao.getEventsCountForDay(accountId, startOfDay, endOfDay)
    }
    
    fun getEventsInRange(accountId: Long, startDate: Date, endDate: Date): Flow<List<CalendarEventEntity>> {
        return calendarEventDao.getEventsInRange(accountId, startDate.time, endDate.time)
    }
    
    suspend fun getEventsInRangeList(accountId: Long, startDate: Date, endDate: Date): List<CalendarEventEntity> {
        return calendarEventDao.getEventsInRangeList(accountId, startDate.time, endDate.time)
    }
    
    fun getEvent(id: String): Flow<CalendarEventEntity?> {
        return calendarEventDao.getEventFlow(id)
    }
    
    suspend fun getEventById(id: String): CalendarEventEntity? {
        return calendarEventDao.getEvent(id)
    }
    
    fun getEventsCount(accountId: Long): Flow<Int> {
        return calendarEventDao.getEventsCount(accountId)
    }
    
    suspend fun getEventsCountSync(accountId: Long): Int {
        return calendarEventDao.getEventsCountSync(accountId)
    }
    
    // === Поиск ===
    
    suspend fun searchEvents(accountId: Long, query: String): List<CalendarEventEntity> {
        if (query.isBlank()) return getEventsList(accountId)
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return calendarEventDao.searchEvents(accountId, escaped)
    }
    
    // === Создание/Редактирование/Удаление ===
    
    /**
     * Создание события календаря на сервере и в локальной БД
     * @param attendees Список email участников (для митингов)
     */
    suspend fun createEvent(
        accountId: Long,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String = "",
        body: String = "",
        allDayEvent: Boolean = false,
        reminder: Int = 15,
        busyStatus: Int = 2,
        sensitivity: Int = 0,
        attendees: List<String> = emptyList(),
        recurrenceType: Int = -1,
        attachments: List<DraftAttachmentData> = emptyList()
    ): EasResult<CalendarEventEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(accountId)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }
                
                // AllDayEvent: нормализуем к midnight UTC той же даты (как это сделает EasCalendarService).
                // Используем эти же значения для temp event → matching после sync не создаст дубль.
                val (effectiveStartTime, effectiveEndTime) = if (allDayEvent) {
                    normalizeAllDayTimes(startTime, endTime)
                } else {
                    startTime to endTime
                }

                val result = easClient.createCalendarEvent(
                    subject = subject,
                    startTime = effectiveStartTime,
                    endTime = effectiveEndTime,
                    location = location,
                    body = body,
                    allDayEvent = allDayEvent,
                    reminder = reminder,
                    busyStatus = busyStatus,
                    sensitivity = sensitivity,
                    attendees = attendees,
                    recurrenceType = recurrenceType,
                    attachments = attachments
                )
                
                val isRecurring = recurrenceType >= 0
                val recurrenceRule = if (isRecurring) {
                    RecurrenceHelper.buildRuleJson(recurrenceType, effectiveStartTime)
                } else ""
                
                when (result) {
                    is EasResult.Success -> {
                        // Формат ответа: "serverId\nattachmentsJson" или просто "serverId"
                        val parts = result.data.split("\n", limit = 2)
                        val serverId = parts[0]
                        val attachmentsJson = if (parts.size > 1) parts[1] else ""
                        
                        // КРИТИЧНО: Определяем нужна ли синхронизация для получения EAS ServerId.
                        // clientId = UUID без дефисов (32 hex) → сервер не вернул реальный ID
                        // EWS ItemId = длинный base64 (>50 символов, без ":") → из EWS CreateItem
                        // pending_sync_ = событие создано но ItemId не извлечён
                        // Во всех этих случаях нужна синхронизация чтобы получить канонический
                        // EAS ServerId — без него update/delete через EAS будут фейлить (Status=6)
                        // и удаление не будет работать → "воскрешение" событий.
                        val isClientId = serverId.length == 32 && !serverId.contains(":")
                        val isEwsItemId = serverId.length > 50 && !serverId.contains(":")
                        val isPendingSync = serverId.startsWith("pending_sync_")
                        val needsSync = isClientId || isEwsItemId || isPendingSync
                        
                        // Сохраняем участников в JSON формате (через JSONArray для корректного экранирования)
                        val attendeesJson = if (attendees.isNotEmpty()) {
                            val arr = JSONArray()
                            attendees.forEach { email ->
                                val obj = JSONObject()
                                obj.put("email", email)
                                obj.put("name", "")
                                arr.put(obj)
                            }
                            arr.toString()
                        } else ""
                        
                        if (needsSync) {
                            val tempEvent = CalendarEventEntity(
                                id = "${accountId}_${serverId}",
                                accountId = accountId,
                                serverId = serverId,
                                subject = subject,
                                location = location,
                                body = body,
                                startTime = effectiveStartTime,
                                endTime = effectiveEndTime,
                                allDayEvent = allDayEvent,
                                reminder = reminder,
                                busyStatus = busyStatus,
                                sensitivity = sensitivity,
                                organizer = "",
                                attendees = attendeesJson,
                                isRecurring = isRecurring,
                                recurrenceRule = recurrenceRule,
                                categories = "",
                                lastModified = System.currentTimeMillis(),
                                hasAttachments = attachmentsJson.isNotBlank(),
                                attachments = attachmentsJson
                            )
                            calendarEventDao.insert(tempEvent)
                            
                            // NonCancellable: UI scope может быть отменён (поворот экрана),
                            // но sync ОБЯЗАН завершиться — иначе temp event останется
                            // с неканоническим serverId и будет удалён следующим sync.
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                // Exchange 2007 SP1 медленно индексирует → 3 секунды.
                                if (isEwsItemId || isPendingSync) {
                                    kotlinx.coroutines.delay(3000)
                                }
                                
                                android.util.Log.d("CalendarRepository", 
                                    "createEvent: syncing to resolve serverId (isClient=$isClientId, isEws=$isEwsItemId, isPending=$isPendingSync)")
                                try {
                                    syncCalendar(accountId)
                                } catch (e: Exception) {
                                    android.util.Log.w("CalendarRepository",
                                        "createEvent: sync failed, temp event preserved: ${e.message}")
                                }
                                
                                val allMatching = calendarEventDao.getEventsByAccountList(accountId)
                                    .filter { it.subject == subject && it.startTime == effectiveStartTime && !it.isDeleted }
                                val createdEvent = allMatching.find { it.id != tempEvent.id }
                                    ?: allMatching.firstOrNull()
                                
                                if (createdEvent != null) {
                                    if (createdEvent.id != tempEvent.id) {
                                        if (isRecurring && !createdEvent.isRecurring) {
                                            calendarEventDao.update(createdEvent.copy(
                                                isRecurring = true,
                                                recurrenceRule = recurrenceRule
                                            ))
                                        }
                                        calendarEventDao.delete(tempEvent.id)
                                        android.util.Log.d("CalendarRepository",
                                            "createEvent: cleaned up temp ${tempEvent.id}, resolved → ${createdEvent.id}")
                                    }
                                    CalendarReminderReceiver.scheduleReminder(context, createdEvent)
                                } else {
                                    // Sync не нашёл каноническую запись — обновляем lastModified
                                    // чтобы продлить защитный период для следующих sync
                                    calendarEventDao.insert(tempEvent.copy(lastModified = System.currentTimeMillis()))
                                    CalendarReminderReceiver.scheduleReminder(context, tempEvent)
                                }
                            }
                            
                            val finalEvent = calendarEventDao.getEventsByAccountList(accountId)
                                .filter { it.subject == subject && it.startTime == effectiveStartTime && !it.isDeleted }
                                .firstOrNull()
                            return@withContext EasResult.Success(finalEvent ?: tempEvent)
                        }
                        
                        val event = CalendarEventEntity(
                            id = "${accountId}_${serverId}",
                            accountId = accountId,
                            serverId = serverId,
                            subject = subject,
                            location = location,
                            body = body,
                            startTime = effectiveStartTime,
                            endTime = effectiveEndTime,
                            allDayEvent = allDayEvent,
                            reminder = reminder,
                            busyStatus = busyStatus,
                            sensitivity = sensitivity,
                            organizer = "",
                            attendees = attendeesJson,
                            isRecurring = isRecurring,
                            recurrenceRule = recurrenceRule,
                            categories = "",
                            lastModified = System.currentTimeMillis(),
                            hasAttachments = attachmentsJson.isNotBlank(),
                            attachments = attachmentsJson
                        )
                        calendarEventDao.insert(event)
                        CalendarReminderReceiver.scheduleReminder(context, event)
                        EasResult.Success(event)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_CREATE_ERROR)
            }
        }
    }
    
    /**
     * Отправка приглашений на событие календаря
     */
    suspend fun sendMeetingInvitation(
        accountId: Long,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        attendees: String
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val account = accountRepo.getAccount(accountId)
                ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
            
            val easClient = accountRepo.createEasClient(accountId)
                ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
            
            easClient.sendMeetingInvitation(
                subject = subject,
                startTime = startTime,
                endTime = endTime,
                location = location,
                body = body,
                attendees = attendees
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error(e.message ?: RepositoryErrors.MEETING_INVITE_ERROR)
        }
    }
    
    /**
     * Обновление события календаря
     */
    suspend fun updateEvent(
        event: CalendarEventEntity,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String = event.location,
        body: String = event.body,
        allDayEvent: Boolean = event.allDayEvent,
        reminder: Int = event.reminder,
        busyStatus: Int = event.busyStatus,
        sensitivity: Int = event.sensitivity,
        attendees: List<String> = emptyList(),
        recurrenceType: Int = -1,
        attachments: List<DraftAttachmentData> = emptyList(),
        removedAttachmentIds: List<String> = emptyList()
    ): EasResult<CalendarEventEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(event.accountId)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }

                val (effectiveStartTime, effectiveEndTime) = if (allDayEvent) {
                    normalizeAllDayTimes(startTime, endTime)
                } else {
                    startTime to endTime
                }

                if (removedAttachmentIds.isNotEmpty()) {
                    val deleteResult = easClient.deleteCalendarAttachments(removedAttachmentIds)
                    if (deleteResult is EasResult.Error) {
                        android.util.Log.w("CalendarRepository", 
                            "updateEvent: Failed to delete some attachments: ${deleteResult.message}")
                    }
                }
                
                val oldAttendeeEmails = parseAttendeesFromJson(event.attendees)
                    .map { it.email.trim().lowercase() }.toSet()
                val newAttendeesToAppend = attendees
                    .filter { it.trim().lowercase() !in oldAttendeeEmails }

                val result = withRetry {
                    easClient.updateCalendarEvent(
                        serverId = event.serverId,
                        subject = subject,
                        startTime = startTime,
                        endTime = endTime,
                        location = location,
                        body = body,
                        allDayEvent = allDayEvent,
                        reminder = reminder,
                        busyStatus = busyStatus,
                        sensitivity = sensitivity,
                        attendees = attendees,
                        oldSubject = event.subject,
                        recurrenceType = recurrenceType,
                        attachments = attachments,
                        newAttendeesToAppend = newAttendeesToAppend
                    )
                }
                
                when (result) {
                    is EasResult.Success -> {
                        // Фильтруем удалённые вложения из существующего JSON
                        val existingAfterRemoval = if (removedAttachmentIds.isNotEmpty() && event.attachments.isNotBlank()) {
                            try {
                                val arr = JSONArray(event.attachments)
                                val filtered = JSONArray()
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    if (obj.optString("fileReference", "") !in removedAttachmentIds) {
                                        filtered.put(obj)
                                    }
                                }
                                if (filtered.length() > 0) filtered.toString() else ""
                            } catch (_: Exception) { event.attachments }
                        } else event.attachments
                        
                        // result.data — attachments JSON новых вложений (пустая строка если нет)
                        val newAttachmentsJson = result.data
                        val finalAttachments = if (newAttachmentsJson.isNotBlank()) {
                            if (existingAfterRemoval.isNotBlank()) {
                                val oldArr = try { JSONArray(existingAfterRemoval) } catch (_: Exception) { JSONArray() }
                                val newArr = try { JSONArray(newAttachmentsJson) } catch (_: Exception) { JSONArray() }
                                for (i in 0 until newArr.length()) oldArr.put(newArr.getJSONObject(i))
                                oldArr.toString()
                            } else newAttachmentsJson
                        } else existingAfterRemoval
                        
                        val newLastModified = System.currentTimeMillis()
                        val isRecurringNew = recurrenceType >= 0
                        val existingRuleType = RecurrenceHelper.parseRule(event.recurrenceRule)?.type ?: -1
                        val recurrenceRuleNew = if (isRecurringNew) {
                            if (recurrenceType == existingRuleType && event.recurrenceRule.isNotBlank()) {
                                event.recurrenceRule
                            } else {
                                RecurrenceHelper.buildRuleJson(recurrenceType, effectiveStartTime)
                            }
                        } else ""
                        val attendeesJson = if (attendees.isNotEmpty()) {
                            val jsonArray = JSONArray()
                            attendees.forEach { email ->
                                val jsonObject = JSONObject()
                                jsonObject.put("email", email.trim())
                                jsonObject.put("name", "")
                                jsonObject.put("status", 0)
                                jsonArray.put(jsonObject)
                            }
                            jsonArray.toString()
                        } else event.attendees

                        val updatedEvent = event.copy(
                            subject = subject,
                            startTime = effectiveStartTime,
                            endTime = effectiveEndTime,
                            location = location,
                            body = body,
                            allDayEvent = allDayEvent,
                            reminder = reminder,
                            busyStatus = busyStatus,
                            sensitivity = sensitivity,
                            isRecurring = isRecurringNew,
                            recurrenceRule = recurrenceRuleNew,
                            lastModified = newLastModified,
                            hasAttachments = finalAttachments.isNotBlank(),
                            attachments = finalAttachments,
                            attendees = attendeesJson
                        )
                        
                        calendarEventDao.update(updatedEvent)
                        
                        // Удаляем дубликаты — те же события (по serverId) с другим локальным ID
                        // Это может произойти если сервер создал новое событие вместо обновления старого
                        val allEvents = calendarEventDao.getEventsByAccountList(event.accountId)
                        val duplicates = allEvents.filter { 
                            it.id != updatedEvent.id && // Не само событие
                            it.serverId.isNotBlank() && // Есть serverId
                            updatedEvent.serverId.isNotBlank() &&
                            it.serverId == updatedEvent.serverId // Тот же объект на сервере
                        }
                        if (duplicates.isNotEmpty()) {
                            duplicates.forEach { dup ->
                                CalendarReminderReceiver.cancelReminder(context, dup.id)
                                calendarEventDao.delete(dup.id)
                            }
                        }
                        
                        // Перепланируем напоминание (отменяем старое, планируем новое)
                        CalendarReminderReceiver.cancelReminder(context, event.id)
                        CalendarReminderReceiver.scheduleReminder(context, updatedEvent)
                        
                        // NonCancellable: sync должен завершиться даже при повороте экрана
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            kotlinx.coroutines.delay(2000)
                            try {
                                syncCalendar(event.accountId)
                            } catch (e: Exception) {
                                android.util.Log.w("CalendarRepository",
                                    "updateEvent: post-update sync failed: ${e.message}")
                            }
                        }
                        
                        EasResult.Success(updatedEvent)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_UPDATE_ERROR)
            }
        }
    }
    
    /**
     * Обновление одного вхождения (occurrence) повторяющегося события.
     * Создаёт exception в MS-ASCAL Exceptions для мастер-события.
     *
     * @param masterEvent Мастер-событие (серия повторений)
     * @param occurrenceOriginalStart Оригинальное время начала вхождения
     */
    suspend fun updateSingleOccurrence(
        masterEvent: CalendarEventEntity,
        occurrenceOriginalStart: Long,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attachments: List<DraftAttachmentData> = emptyList(),
        removedAttachmentIds: List<String> = emptyList()
    ): EasResult<CalendarEventEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(masterEvent.accountId)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }

                val (effectiveStart, effectiveEnd) = if (allDayEvent) {
                    normalizeAllDayTimes(startTime, endTime)
                } else {
                    startTime to endTime
                }

                val result = easClient.updateSingleOccurrence(
                    serverId = masterEvent.serverId,
                    existingExceptionsJson = masterEvent.exceptions,
                    occurrenceOriginalStartTime = occurrenceOriginalStart,
                    masterSubject = masterEvent.subject,
                    subject = subject,
                    startTime = effectiveStart,
                    endTime = effectiveEnd,
                    location = location,
                    body = body,
                    allDayEvent = allDayEvent,
                    reminder = reminder,
                    busyStatus = busyStatus,
                    sensitivity = sensitivity,
                    attachments = attachments,
                    removedAttachmentIds = removedAttachmentIds
                )

                when (result) {
                    is EasResult.Success -> {
                        val changedAttachments = attachments.isNotEmpty() || removedAttachmentIds.isNotEmpty()
                        val existingException = RecurrenceHelper.fuzzyMatchException(
                            RecurrenceHelper.parseExceptions(masterEvent.exceptions).filter { !it.deleted },
                            occurrenceOriginalStart
                        )
                        val occurrenceAttachmentsJson = if (changedAttachments) {
                            result.data
                        } else {
                            existingException?.attachments ?: ""
                        }
                        val attachmentsOverridden = if (changedAttachments) {
                            true
                        } else {
                            existingException?.attachmentsOverridden ?: false
                        }
                        val newException = RecurrenceHelper.RecurrenceException(
                            exceptionStartTime = occurrenceOriginalStart,
                            deleted = false,
                            subject = subject,
                            location = location,
                            startTime = effectiveStart,
                            endTime = effectiveEnd,
                            body = body,
                            attachments = occurrenceAttachmentsJson,
                            attachmentsOverridden = attachmentsOverridden
                        )
                        val updatedExceptionsJson = RecurrenceHelper.mergeException(
                            masterEvent.exceptions, newException
                        )
                        val updatedEvent = masterEvent.copy(
                            exceptions = updatedExceptionsJson,
                            lastModified = System.currentTimeMillis()
                        )
                        calendarEventDao.update(updatedEvent)

                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            kotlinx.coroutines.delay(2000)
                            try {
                                syncCalendar(masterEvent.accountId)
                            } catch (e: Exception) {
                                android.util.Log.w("CalendarRepository",
                                    "updateSingleOccurrence: post-update sync failed: ${e.message}")
                            }
                        }

                        EasResult.Success(updatedEvent)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_UPDATE_ERROR)
            }
        }
    }

    /**
     * Удаление одного вхождения повторяющегося события.
     * Использует EWS FindItem + DeleteItem на конкретный occurrence.
     */
    suspend fun deleteOccurrence(
        masterEvent: CalendarEventEntity,
        occurrenceStartTime: Long,
        occurrenceSnapshot: CalendarEventEntity? = null
    ): EasResult<Boolean> {
        initFromPrefs(context)
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(masterEvent.accountId, RepositoryErrors.CALENDAR_EXCHANGE_ONLY)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }

                val searchSubject = occurrenceSnapshot?.subject ?: masterEvent.subject
                val account = accountRepo.getAccount(masterEvent.accountId)
                val currentEmail = account?.email?.lowercase() ?: ""
                val isOrganizer = masterEvent.organizer.lowercase() == currentEmail
                val serverResult = withRetry {
                    easClient.deleteSingleOccurrence(
                        searchSubject, occurrenceStartTime,
                        isMeeting = masterEvent.isMeeting, isOrganizer = isOrganizer
                    )
                }
                if (serverResult is EasResult.Error) {
                    return@withContext serverResult
                }

                val duration = masterEvent.endTime - masterEvent.startTime
                val occId = "${masterEvent.id}_occ_$occurrenceStartTime"
                val snapshot = occurrenceSnapshot?.copy(
                    isDeleted = true,
                    isRecurring = false,
                    recurrenceRule = "",
                    exceptions = "",
                    serverId = "",
                    lastModified = System.currentTimeMillis()
                ) ?: CalendarEventEntity(
                    id = occId,
                    accountId = masterEvent.accountId,
                    serverId = "",
                    subject = masterEvent.subject,
                    location = masterEvent.location,
                    body = masterEvent.body,
                    startTime = occurrenceStartTime,
                    endTime = occurrenceStartTime + duration,
                    allDayEvent = masterEvent.allDayEvent,
                    reminder = masterEvent.reminder,
                    busyStatus = masterEvent.busyStatus,
                    sensitivity = masterEvent.sensitivity,
                    organizer = masterEvent.organizer,
                    organizerName = masterEvent.organizerName,
                    attendees = masterEvent.attendees,
                    isRecurring = false,
                    categories = masterEvent.categories,
                    isMeeting = masterEvent.isMeeting,
                    isDeleted = true,
                    lastModified = System.currentTimeMillis()
                )
                database.withTransaction {
                    calendarEventDao.insert(snapshot)

                    val freshMaster = calendarEventDao.getEvent(masterEvent.id) ?: masterEvent
                    val deletedException = RecurrenceHelper.RecurrenceException(
                        exceptionStartTime = occurrenceStartTime,
                        deleted = true
                    )
                    val updatedJson = RecurrenceHelper.mergeException(
                        freshMaster.exceptions, deletedException
                    )
                    calendarEventDao.update(freshMaster.copy(
                        exceptions = updatedJson,
                        lastModified = System.currentTimeMillis()
                    ))
                }

                EasResult.Success(true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_DELETE_ERROR)
            }
        }
    }

    /**
     * Удаление события календаря
     */
    suspend fun deleteEvent(event: CalendarEventEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                CalendarReminderReceiver.cancelReminder(context, event.id)
                calendarEventDao.softDelete(event.id)
                EasResult.Success(true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_DELETE_ERROR)
            }
        }
    }
    
    /**
     * Удаление нескольких событий календаря
     */
    suspend fun deleteEvents(events: List<CalendarEventEntity>): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (events.isEmpty()) return@withContext EasResult.Success(0)
            deleteEventsWithProgress(events) { _, _ -> }
        }
    }
    
    /**
     * Мягкое удаление нескольких событий (перемещение в локальную корзину).
     * Серверное удаление происходит только при окончательном удалении (deleteEventPermanently/emptyCalendarTrash).
     */
    suspend fun deleteEventsWithProgress(
        events: List<CalendarEventEntity>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            if (events.isEmpty()) return@withContext EasResult.Success(0)
            try {
                val totalCount = events.size
                var totalDeleted = 0
                database.withTransaction {
                    for (event in events) {
                        CalendarReminderReceiver.cancelReminder(context, event.id)
                        calendarEventDao.softDelete(event.id)
                        totalDeleted++
                    }
                }
                onProgress(totalDeleted, totalCount)
                EasResult.Success(totalDeleted)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_DELETE_ERROR)
            }
        }
    }
    
    // === Корзина событий ===
    
    /**
     * Восстановление события из локальной корзины.
     * Событие остаётся на сервере с тем же serverId — снимаем только флаг isDeleted.
     */
    suspend fun restoreEvent(event: CalendarEventEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                if (event.id.contains("_occ_")) {
                    return@withContext EasResult.Error(
                        "Восстановление вхождения повторяющегося события невозможно. Удалите его навсегда или создайте заново."
                    )
                }

                calendarEventDao.restore(event.id)
                CalendarReminderReceiver.scheduleReminder(context, event)
                android.util.Log.d("CalendarRepository", "Event restored locally: serverId=${event.serverId}")
                EasResult.Success(true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Ошибка восстановления события")
            }
        }
    }
    
    /**
     * Окончательное удаление события из корзины.
     * Удаляет с сервера (best-effort) + из локальной БД + markAsDeleted от воскрешения при sync.
     */
    suspend fun deleteEventPermanently(event: CalendarEventEntity): EasResult<Boolean> {
        initFromPrefs(context)
        return withContext(Dispatchers.IO) {
            try {
                if (event.id.contains("_occ_")) {
                    calendarEventDao.delete(event.id)
                    return@withContext EasResult.Success(true)
                }

                if (event.serverId.isNotBlank()) {
                    try {
                        val easClient = accountRepo.createEasClient(event.accountId)
                        if (easClient != null) {
                            val account = accountRepo.getAccount(event.accountId)
                            val currentEmail = account?.email?.lowercase() ?: ""
                            val isOrganizer = event.organizer.lowercase() == currentEmail
                            val serverResult = easClient.deleteCalendarEvent(
                                serverId = event.serverId,
                                isMeeting = event.isMeeting,
                                isOrganizer = isOrganizer,
                                isRecurringSeries = event.isRecurring
                            )
                            when (serverResult) {
                                is EasResult.Success ->
                                    android.util.Log.d("CalendarRepository",
                                        "deleteEventPermanently: server delete OK for serverId=${event.serverId}")
                                is EasResult.Error ->
                                    android.util.Log.w("CalendarRepository",
                                        "deleteEventPermanently: server delete failed (best-effort): ${serverResult.message}")
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.w("CalendarRepository",
                            "deleteEventPermanently: server delete exception (best-effort): ${e.message}")
                    }
                    markAsDeleted(event.serverId, context)
                }
                calendarEventDao.delete(event.id)
                EasResult.Success(true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Ошибка удаления события")
            }
        }
    }
    
    /**
     * Очистка корзины календаря.
     * Делает best-effort удаление с сервера + markAsDeleted (антивоскрешение),
     * отменяет напоминания, затем очищает локальную БД.
     */
    suspend fun emptyCalendarTrash(accountId: Long): EasResult<Int> {
        initFromPrefs(context)
        return withContext(Dispatchers.IO) {
            try {
                val deletedEvents = calendarEventDao.getDeletedEventsList(accountId)
                if (deletedEvents.isEmpty()) return@withContext EasResult.Success(0)

                val regularEvents = deletedEvents.filter { !it.id.contains("_occ_") }

                val eventsWithServerId = regularEvents.filter { it.serverId.isNotBlank() }
                if (eventsWithServerId.isNotEmpty()) {
                    try {
                        val easClient = accountRepo.createEasClient(accountId)
                        if (easClient != null) {
                            val currentEmail = accountRepo.getAccount(accountId)?.email?.lowercase() ?: ""
                            val batchRequests = eventsWithServerId.map { event ->
                                val isOrganizer = event.organizer.lowercase() == currentEmail
                                com.dedovmosol.iwomail.eas.EasCalendarService.DeleteRequest(
                                    serverId = event.serverId,
                                    isMeeting = event.isMeeting,
                                    isOrganizer = isOrganizer,
                                    isRecurringSeries = event.isRecurring
                                )
                            }
                            val batchResult = easClient.deleteCalendarEventsBatch(batchRequests)
                            android.util.Log.d("CalendarRepository",
                                "emptyCalendarTrash: server batch delete result: $batchResult (${eventsWithServerId.size} events)")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.w("CalendarRepository",
                            "emptyCalendarTrash: server delete failed (best-effort): ${e.message}")
                    }
                }

                for (event in regularEvents) {
                    if (event.serverId.isNotBlank()) {
                        markAsDeleted(event.serverId, context)
                    }
                    CalendarReminderReceiver.cancelReminder(context, event.id)
                }

                calendarEventDao.emptyTrash(accountId)
                EasResult.Success(deletedEvents.size)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Ошибка очистки корзины")
            }
        }
    }
    
    // === Скачивание вложений календаря ===
    
    /**
     * Скачивание вложения события календаря
     * @param accountId ID аккаунта
     * @param fileReference FileReference вложения (из attachments JSON)
     * @return байты файла
     */
    suspend fun downloadCalendarAttachment(accountId: Long, fileReference: String): EasResult<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                // EAS FileReference содержит ":" (формат "22:2:1")
                // EWS AttachmentId — длинная base64 строка без ":"
                // Для EWS (Exchange 2007) нужен EWS GetAttachment, а не EAS ItemOperations
                if (fileReference.contains(":")) {
                    easClient.downloadAttachment(fileReference)
                } else {
                    easClient.downloadDraftAttachment(fileReference)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Ошибка скачивания вложения")
            }
        }
    }
    
    // === Получение удалённых событий ===
    
    fun getDeletedEvents(accountId: Long): Flow<List<CalendarEventEntity>> {
        return calendarEventDao.getDeletedEvents(accountId)
    }
    
    fun getDeletedEventsCount(accountId: Long): Flow<Int> {
        return calendarEventDao.getDeletedEventsCount(accountId)
    }
    
    /**
     * Ответ на приглашение на встречу
     * @param event Событие календаря
     * @param response Тип ответа: "Accept", "Tentative", "Decline"
     * @param sendResponse Отправить ответ организатору
     */
    suspend fun respondToMeeting(
        event: CalendarEventEntity,
        response: String,
        sendResponse: Boolean = true
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(event.accountId, RepositoryErrors.MEETING_RESPONSE_EXCHANGE_ONLY)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }
                
                val result = withRetry {
                    easClient.respondToMeetingRequest(event.serverId, response, sendResponse, subject = event.subject)
                }
                
                when (result) {
                    is EasResult.Success -> {
                        // Обновляем статус ответа локально
                        val newResponseStatus = when (response.lowercase()) {
                            "accept" -> MeetingResponseStatus.ACCEPTED.value
                            "tentative" -> MeetingResponseStatus.TENTATIVE.value
                            "decline" -> MeetingResponseStatus.DECLINED.value
                            else -> MeetingResponseStatus.ACCEPTED.value
                        }
                        val updatedEvent = event.copy(responseStatus = newResponseStatus)
                        calendarEventDao.update(updatedEvent)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.MEETING_RESPONSE_ERROR)
            }
        }
    }

    
    // === Синхронизация ===
    
    /**
     * Синхронизация календаря с Exchange сервера
     */
    suspend fun syncCalendar(accountId: Long): EasResult<Int> {
        val mutex = getSyncMutex(accountId)
        if (!mutex.tryLock()) {
            return EasResult.Error("Calendar sync already in progress")
        }
        try {
            return syncCalendarInternal(accountId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun syncCalendarInternal(accountId: Long): EasResult<Int> {
        initFromPrefs(context)
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(accountId)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }
                
                val result = easClient.syncCalendar()
                
                when (result) {
                    is EasResult.Success -> {
                        val syncResult = result.data
                        val serverEvents = syncResult.events
                        val explicitlyDeletedIds = syncResult.deletedServerIds
                        android.util.Log.d("CalendarRepository", 
                            "syncCalendar: server returned ${serverEvents.size} events, ${explicitlyDeletedIds.size} explicit deletes")
                        
                        // Все DB-записи в одной транзакции для атомарности
                        val syncedCount = database.withTransaction {
                        
                        // Явные удаления из EAS <Delete> команд
                        if (explicitlyDeletedIds.isNotEmpty()) {
                            for (serverId in explicitlyDeletedIds) {
                                val eventId = "${accountId}_${serverId}"
                                CalendarReminderReceiver.cancelReminder(context, eventId)
                                calendarEventDao.delete(eventId)
                                android.util.Log.d("CalendarRepository",
                                    "syncCalendar: Explicitly deleted event from EAS Delete: serverId=$serverId")
                            }
                        }
                        
                        val existingEvents = calendarEventDao.getEventsByAccountList(accountId)
                        val existingServerIds = existingEvents.map { it.serverId }.toSet()
                        val serverReturnedIds = serverEvents.map { it.serverId }.toSet()
                        
                        val existingEventsMap = existingEvents.associateBy { it.serverId }
                        
                        if (serverEvents.isNotEmpty() || existingEvents.isEmpty()) {
                            val serverDeletedIds = existingServerIds - serverReturnedIds
                            
                            val now = System.currentTimeMillis()
                            val pastCutoffMs = now - (90L * 24 * 60 * 60 * 1000)
                            val futureCutoffMs = now + (730L * 24 * 60 * 60 * 1000)
                            val RECENT_CREATE_THRESHOLD = 300_000L
                            
                            for (serverId in serverDeletedIds) {
                                val localEvent = existingEventsMap[serverId] ?: continue
                                if (localEvent.startTime < pastCutoffMs || localEvent.startTime > futureCutoffMs) {
                                    continue
                                }
                                // Событие с неканоническим serverId (EWS ItemId, clientId, pending_sync_)
                                // ещё не прошло resolution через sync → не удаляем,
                                // иначе созданное событие пропадёт до индексации Exchange.
                                val isNonCanonical = serverId.startsWith("pending_sync_") ||
                                    (serverId.length > 50 && !serverId.contains(":")) ||
                                    (serverId.length == 32 && !serverId.contains(":"))
                                if (isNonCanonical) {
                                    android.util.Log.d("CalendarRepository",
                                        "syncCalendar: Skipping non-canonical serverId: ${serverId.take(30)}...")
                                    continue
                                }
                                if ((now - localEvent.lastModified) < RECENT_CREATE_THRESHOLD) {
                                    android.util.Log.d("CalendarRepository", 
                                        "syncCalendar: Skipping recently-modified event: serverId=$serverId, age=${now - localEvent.lastModified}ms")
                                    continue
                                }
                                val eventId = "${accountId}_${serverId}"
                                CalendarReminderReceiver.cancelReminder(context, eventId)
                                calendarEventDao.delete(eventId)
                            }
                        } else {
                            android.util.Log.w("CalendarRepository", 
                                "syncCalendar: Server returned 0 events but ${existingEvents.size} exist locally — skipping deletion to prevent data loss")
                        }
                        
                        // КРИТИЧНО: Очищаем защитное множество — убираем serverId,
                        // которые сервер УЖЕ НЕ возвращает (реально удалены на сервере).
                        // Только если сервер вернул непустой ответ (валидный sync)
                        if (serverEvents.isNotEmpty()) {
                            confirmServerDeletions(serverReturnedIds, context)
                        }
                        
                        // Второй уровень защиты: serverIds событий в корзине (isDeleted=1)
                        // Защищает от воскрешения если SharedPreferences очищены/повреждены
                        val deletedEventsInTrash = calendarEventDao.getDeletedEventsList(accountId)
                        val trashServerIds = deletedEventsInTrash.map { it.serverId }.toSet()
                        
                        // КРИТИЧНО: Фильтруем события, удалённые пользователем.
                        // Два уровня защиты:
                        // 1) deletedServerIds (SharedPreferences) — от race condition сервер/клиент
                        // 2) trashServerIds (БД isDeleted=1) — от потери SharedPreferences
                        val filteredServerEvents = serverEvents.filter { event ->
                            when {
                                isMarkedAsDeleted(event.serverId) -> {
                                    android.util.Log.d("CalendarRepository", 
                                        "syncCalendar: Skipping user-deleted event (deletedServerIds): serverId=${event.serverId}")
                                    false
                                }
                                event.serverId in trashServerIds -> {
                                    android.util.Log.d("CalendarRepository", 
                                        "syncCalendar: Skipping trash event (DB isDeleted=1): serverId=${event.serverId}")
                                    false
                                }
                                else -> true
                            }
                        }
                        
                        // Фильтруем дубликаты по serverId (защита от повторной вставки)
                        val uniqueEvents = filteredServerEvents.distinctBy { it.serverId }
                        
                        android.util.Log.d("CalendarRepository", 
                            "syncCalendar: filtered=${filteredServerEvents.size}, unique=${uniqueEvents.size}, " +
                            "deletedServerIds=${deletedServerIds.size}, trashIds=${trashServerIds.size}")
                        
                        // Защита от race condition: sync может получить устаревшие данные после update
                        val syncTime = System.currentTimeMillis()
                        val RECENT_EDIT_THRESHOLD = 30_000L // 30 секунд — Exchange 2007 SP1 медленно индексирует
                        
                        // Извлекаем deleted exceptions из локальных мастеров ДО insertAll.
                        // EWS CalendarView не возвращает deleted occurrences, поэтому
                        // единственный источник deleted exceptions — локальная БД.
                        val localDeletedExceptionsMap = mutableMapOf<String, List<RecurrenceHelper.RecurrenceException>>()
                        for ((serverId, existing) in existingEventsMap) {
                            if (existing.isRecurring && existing.exceptions.isNotBlank()) {
                                val deletedExc = RecurrenceHelper.parseExceptions(existing.exceptions)
                                    .filter { it.deleted }
                                if (deletedExc.isNotEmpty()) {
                                    localDeletedExceptionsMap[serverId] = deletedExc
                                }
                            }
                        }
                        
                        var skippedCount = 0
                        val eventEntities = uniqueEvents.mapNotNull { event ->
                            val existingEvent = existingEventsMap[event.serverId]
                            
                            if (existingEvent != null) {
                                val hasLocalChanges = existingEvent.subject != event.subject || 
                                                     existingEvent.location != event.location ||
                                                     existingEvent.body != event.body ||
                                                     existingEvent.startTime != event.startTime ||
                                                     existingEvent.endTime != event.endTime ||
                                                     existingEvent.exceptions != event.exceptions
                                
                                if (hasLocalChanges) {
                                    val timeSinceLocalEdit = syncTime - existingEvent.lastModified
                                    if (timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                        skippedCount++
                                        return@mapNotNull null
                                    }
                                }
                            }
                            
                            val finalAttachments = when {
                                event.attachments.isNotBlank() -> {
                                    event.attachments
                                }
                                event.hasAttachments && existingEvent?.attachments?.isNotBlank() == true -> {
                                    // EAS 12.1: HasAttachments=true но нет <Attachment> потомков.
                                    // supplementAttachmentsViaEws уже пытался получить данные.
                                    // Если supplement не нашёл — вложения были удалены на сервере
                                    // (EWS уже отразил удаление, EAS ещё нет). Очищаем кэш.
                                    ""
                                }
                                else -> {
                                    ""
                                }
                            }
                            val finalHasAttachments = event.hasAttachments || finalAttachments.isNotBlank()
                            
                            // MS-ASCAL §2.2.2.22: Exchange 2007 SP1 может не включать
                            // Exceptions в инкрементальный Sync Change → protect from wipe.
                            // Дополнительно: merge deleted exceptions из локальных данных,
                            // т.к. EWS CalendarView не возвращает deleted occurrences.
                            val finalExceptions = run {
                                val baseExceptions = if (event.exceptions.isNotBlank()) {
                                    event.exceptions
                                } else {
                                    existingEvent?.exceptions ?: ""
                                }
                                val localDeleted = localDeletedExceptionsMap[event.serverId]
                                if (localDeleted.isNullOrEmpty()) {
                                    baseExceptions
                                } else {
                                    val baseParsed = RecurrenceHelper.parseExceptions(baseExceptions)
                                    val baseStartTimes = baseParsed.map { it.exceptionStartTime }.toSet()
                                    val missingDeleted = localDeleted.filter { 
                                        it.exceptionStartTime !in baseStartTimes 
                                    }
                                    if (missingDeleted.isNotEmpty()) {
                                        RecurrenceHelper.exceptionsToJson(baseParsed + missingDeleted)
                                    } else baseExceptions
                                }
                            }
                            
                            // Аналогичная защита для recurrenceRule (MS-ASCAL §2.2.2.37)
                            val finalRecurrenceRule = if (event.recurrenceRule.isNotBlank()) {
                                event.recurrenceRule
                            } else {
                                existingEvent?.recurrenceRule ?: ""
                            }
                            val finalIsRecurring = event.isRecurring || finalRecurrenceRule.isNotBlank()
                            
                            CalendarEventEntity(
                                id = "${accountId}_${event.serverId}",
                                accountId = accountId,
                                serverId = event.serverId,
                                subject = event.subject,
                                location = event.location,
                                body = event.body,
                                startTime = event.startTime,
                                endTime = event.endTime,
                                allDayEvent = event.allDayEvent,
                                reminder = event.reminder,
                                busyStatus = event.busyStatus,
                                sensitivity = event.sensitivity,
                                organizer = event.organizer,
                                organizerName = event.organizerName,
                                attendees = attendeesToJson(event.attendees),
                                isRecurring = finalIsRecurring,
                                recurrenceRule = finalRecurrenceRule,
                                uid = event.uid,
                                timezone = event.timezone,
                                exceptions = finalExceptions,
                                categories = event.categories.joinToString(","),
                                lastModified = existingEvent?.lastModified ?: event.lastModified,
                                responseStatus = event.responseStatus,
                                isMeeting = event.isMeeting,
                                meetingStatus = event.meetingStatus,
                                responseRequested = event.responseRequested,
                                appointmentReplyTime = event.appointmentReplyTime,
                                disallowNewTimeProposal = event.disallowNewTimeProposal,
                                onlineMeetingLink = event.onlineMeetingLink,
                                hasAttachments = finalHasAttachments,
                                attachments = finalAttachments
                            )
                        }
                        
                        android.util.Log.d("CalendarRepository", 
                            "syncCalendar: stored=${eventEntities.size}, skipped=$skippedCount")
                        
                        if (eventEntities.isNotEmpty()) {
                            for (chunk in eventEntities.chunked(500)) {
                                calendarEventDao.insertAll(chunk)
                            }
                            
                            // Перепланируем напоминания только для новых/изменённых событий
                            val newServerIds = serverReturnedIds - existingServerIds
                            val newEvents = eventEntities.filter { it.serverId in newServerIds }
                            if (newEvents.isNotEmpty()) {
                                CalendarReminderReceiver.rescheduleAllReminders(context, newEvents)
                            }
                        }
                        
                        // КРИТИЧНО: Дедупликация — удаляем "призрачные" локальные записи.
                        // Два типа призраков:
                        //   1) clientId-like (32 hex без ":") — из EAS create когда сервер не вернул ID
                        //   2) EWS ItemId (длинный base64, содержит "AAM" или длина > 50) — из EWS create,
                        //      после sync появляется реальная EAS-запись (ServerId как "5:23")
                        // При удалении призрака МИГРИРУЕМ вложения на реальную запись!
                        try {
                            val allLocalEvents = calendarEventDao.getEventsByAccountList(accountId)
                            
                            data class EventKey(val subject: String, val startTime: Long, val endTime: Long)
                            
                            val realEasIndex = mutableMapOf<EventKey, MutableList<com.dedovmosol.iwomail.data.database.CalendarEventEntity>>()
                            val ghosts = mutableListOf<com.dedovmosol.iwomail.data.database.CalendarEventEntity>()
                            
                            for (ev in allLocalEvents) {
                                val sid = ev.serverId
                                val isReal = sid.contains(":") && sid.length < 50
                                val isGhost = (sid.length == 32 && !sid.contains(":")) ||
                                              (sid.length > 50 && !sid.contains(":"))
                                if (isReal) {
                                    realEasIndex.getOrPut(EventKey(ev.subject, ev.startTime, ev.endTime)) { mutableListOf() }.add(ev)
                                }
                                if (isGhost) ghosts.add(ev)
                            }
                            
                            for (ghost in ghosts) {
                                val key = EventKey(ghost.subject, ghost.startTime, ghost.endTime)
                                val realMatch = realEasIndex[key]?.firstOrNull { it.id != ghost.id } ?: continue
                                
                                if (ghost.attachments.isNotBlank() && realMatch.attachments.isBlank()) {
                                    val updated = realMatch.copy(hasAttachments = true, attachments = ghost.attachments)
                                    calendarEventDao.insert(updated)
                                    android.util.Log.d("CalendarRepository",
                                        "syncCalendar: Migrated attachments from ghost to real: ${realMatch.serverId}")
                                }
                                android.util.Log.w("CalendarRepository",
                                    "syncCalendar: Removing ghost entity serverId=${ghost.serverId.take(30)}, subject=${ghost.subject}")
                                CalendarReminderReceiver.cancelReminder(context, ghost.id)
                                calendarEventDao.delete(ghost.id)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                        
                        // Exchange 2007 SP1: EAS Sync может не возвращать обновлённые exceptions
                        // для повторяющихся событий при модификации отдельных вхождений на ПК.
                        // Дополняем через EWS FindItem + CalendarView.
                        try {
                            val allLocalAfterSync = calendarEventDao.getEventsByAccountList(accountId)
                            val localRecurring = allLocalAfterSync.filter { it.isRecurring && it.uid.isNotBlank() }
                            if (localRecurring.isNotEmpty()) {
                                val recurringInfoList = localRecurring.map {
                                    com.dedovmosol.iwomail.eas.EasCalendarService.RecurringEventInfo(
                                        uid = it.uid,
                                        serverId = it.serverId,
                                        currentExceptions = it.exceptions
                                    )
                                }
                                val updatedExceptions = easClient.supplementRecurringExceptions(recurringInfoList)
                                if (updatedExceptions.isNotEmpty()) {
                                    android.util.Log.d("CalendarRepository",
                                        "syncCalendar: EWS supplement updated exceptions for ${updatedExceptions.size} recurring events")
                                    for ((uid, newExceptions) in updatedExceptions) {
                                        val event = localRecurring.find { it.uid == uid } ?: continue
                                        calendarEventDao.insert(event.copy(exceptions = newExceptions))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            android.util.Log.w("CalendarRepository",
                                "syncCalendar: supplementRecurringExceptions failed: ${e.message}")
                        }
                        uniqueEvents.size
                        } // end withTransaction
                        
                        EasResult.Success(syncedCount)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.CALENDAR_SYNC_ERROR)
            }
        }
    }
    
    // === Утилиты ===
    
    /**
     * Выполняет операцию с одной повторной попыткой при ошибке.
     * Условие retry: сообщение содержит "Status=", "failed" или "error".
     */
    private suspend fun <T> withRetry(block: suspend () -> EasResult<T>): EasResult<T> {
        var result = block()
        if (result is EasResult.Error && (
            result.message.contains("Status=", ignoreCase = true) ||
            result.message.contains("failed", ignoreCase = true) ||
            result.message.contains("error", ignoreCase = true)
        )) {
            kotlinx.coroutines.delay(1000)
            result = block()
        }
        return result
    }
    
    /**
     * Проверяет аккаунт и создаёт EAS клиент.
     * Единая точка валидации: аккаунт существует, тип Exchange, клиент создан.
     */
    private suspend fun requireExchangeClient(
        accountId: Long,
        exchangeOnlyError: String = RepositoryErrors.CALENDAR_EXCHANGE_ONLY
    ): EasResult<EasClient> {
        val account = accountRepo.getAccount(accountId)
            ?: return EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error(exchangeOnlyError)
        }
        
        val easClient = accountRepo.createEasClient(accountId)
            ?: return EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
        
        return EasResult.Success(easClient)
    }
    
    private fun attendeesToJson(attendees: List<com.dedovmosol.iwomail.eas.EasAttendee>): String {
        if (attendees.isEmpty()) return ""
        val jsonArray = JSONArray()
        attendees.forEach { attendee ->
            val jsonObject = JSONObject()
            jsonObject.put("email", attendee.email)
            jsonObject.put("name", attendee.name)
            jsonObject.put("status", attendee.status)
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }
    
    fun parseAttendeesFromJson(json: String): List<Attendee> {
        if (json.isBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            val attendees = mutableListOf<Attendee>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                attendees.add(
                    Attendee(
                        email = jsonObject.getString("email"),
                        name = jsonObject.optString("name", ""),
                        status = jsonObject.optInt("status", 0)
                    )
                )
            }
            attendees
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Обновление статуса участника в событии календаря
     * @param accountId ID аккаунта
     * @param meetingSubject Название события
     * @param attendeeEmail Email участника
     * @param status Статус: 2=Tentative, 3=Accepted, 4=Declined
     */
    suspend fun updateAttendeeStatus(
        accountId: Long,
        meetingSubject: String,
        attendeeEmail: String,
        status: Int
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val events = calendarEventDao.getEventsByAccountList(accountId)
                val matching = events.filter { it.subject.equals(meetingSubject, ignoreCase = true) }
                val now = System.currentTimeMillis()
                val event = matching.minByOrNull { kotlin.math.abs(it.startTime - now) }
                    ?: return@withContext EasResult.Error(RepositoryErrors.EVENT_NOT_FOUND)
                
                // Парсим текущих участников
                val attendees = parseAttendeesFromJson(event.attendees).toMutableList()
                
                // Ищем участника по email
                val attendeeIndex = attendees.indexOfFirst { 
                    it.email.equals(attendeeEmail, ignoreCase = true) 
                }
                
                if (attendeeIndex >= 0) {
                    // Обновляем статус существующего участника
                    val oldAttendee = attendees[attendeeIndex]
                    attendees[attendeeIndex] = Attendee(oldAttendee.email, oldAttendee.name, status)
                } else {
                    // Добавляем нового участника с email из письма
                    val name = attendeeEmail.substringBefore("@")
                    attendees.add(Attendee(attendeeEmail, name, status))
                }
                
                // Сохраняем обновлённый список участников
                val updatedAttendeesJson = attendeesToJson(attendees.map { 
                    com.dedovmosol.iwomail.eas.EasAttendee(it.email, it.name, it.status)
                })
                calendarEventDao.updateAttendees(event.id, updatedAttendeesJson)
                
                EasResult.Success(true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.ATTENDEE_UPDATE_ERROR)
            }
        }
    }
}

/**
 * Участник события для UI
 */
data class Attendee(
    val email: String,
    val name: String,
    val status: Int
)

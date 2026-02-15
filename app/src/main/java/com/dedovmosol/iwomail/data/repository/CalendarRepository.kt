package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.*
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
        /**
         * Защита от "воскрешения" удалённых событий.
         * Множество serverId событий, удалённых пользователем.
         * КРИТИЧНО: Персистится через SharedPreferences чтобы пережить рестарт приложения!
         * Без персистенции при убийстве процесса набор терялся, и при следующей
         * синхронизации удалённые события "воскресали".
         * Записи удаляются ТОЛЬКО когда syncCalendar() подтверждает,
         * что сервер больше не возвращает эти события.
         */
        private val deletedServerIds = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )
        
        private const val PREFS_NAME = "calendar_deleted_ids"
        private const val KEY_DELETED_IDS = "deleted_server_ids"
        private var prefsInitialized = false
        
        private fun initFromPrefs(context: Context) {
            if (prefsInitialized) return
            prefsInitialized = true
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val saved = prefs.getStringSet(KEY_DELETED_IDS, emptySet()) ?: emptySet()
                deletedServerIds.addAll(saved)
            } catch (_: Exception) { }
        }
        
        private fun saveToPrefs(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putStringSet(KEY_DELETED_IDS, deletedServerIds.toSet()).apply()
            } catch (_: Exception) { }
        }
        
        private fun markAsDeleted(serverId: String, context: Context? = null) {
            if (serverId.isNotBlank()) {
                deletedServerIds.add(serverId)
                context?.let { saveToPrefs(it) }
            }
        }
        
        private fun isMarkedAsDeleted(serverId: String): Boolean {
            return deletedServerIds.contains(serverId)
        }
        
        private fun removeFromDeleted(serverId: String, context: Context? = null) {
            deletedServerIds.remove(serverId)
            context?.let { saveToPrefs(it) }
        }
        
        /**
         * Вызывается из syncCalendar() — убирает из защитного множества
         * те serverId, которых сервер уже НЕ возвращает (реально удалены).
         */
        private fun confirmServerDeletions(serverReturnedIds: Set<String>, context: Context? = null) {
            deletedServerIds.removeAll { it !in serverReturnedIds }
            context?.let { saveToPrefs(it) }
        }
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
        return calendarEventDao.searchEvents(accountId, query)
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
        recurrenceType: Int = -1
    ): EasResult<CalendarEventEntity> {
        return withContext(Dispatchers.IO) {
            try {
                // ЗАЩИТА ОТ ДУБЛИРОВАНИЯ: если идентичное событие уже существует — возвращаем его
                val existingEvents = calendarEventDao.getEventsByAccountList(accountId)
                val duplicate = existingEvents.find { existing ->
                    existing.subject == subject &&
                    existing.startTime == startTime &&
                    existing.endTime == endTime &&
                    existing.location == location
                }
                if (duplicate != null) {
                    android.util.Log.w("CalendarRepository", 
                        "createEvent: Duplicate detected (subject=$subject, startTime=$startTime), returning existing")
                    return@withContext EasResult.Success(duplicate)
                }
                
                val easClient = when (val r = requireExchangeClient(accountId)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }
                
                // КРИТИЧНО: НЕ используем withRetry для создания!
                // Create НЕ идемпотентен — retry создаст ВТОРОЕ событие на сервере.
                val result = easClient.createCalendarEvent(
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
                    recurrenceType = recurrenceType
                )
                
                // Формируем recurrenceRule JSON для локальной записи
                val isRecurring = recurrenceType >= 0
                val recurrenceRule = if (isRecurring) {
                    RecurrenceHelper.buildRuleJson(recurrenceType, startTime)
                } else ""
                
                when (result) {
                    is EasResult.Success -> {
                        val serverId = result.data
                        
                        // Если serverId похож на clientId (UUID без дефисов), 
                        // значит сервер не вернул реальный ID — нужна синхронизация
                        val isClientId = serverId.length == 32 && !serverId.contains(":")
                        
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
                        
                        if (isClientId) {
                            // Сервер не вернул реальный ID — синхронизируем
                            syncCalendar(accountId)
                            
                            // Ищем созданное событие в синхронизированных данных
                            val createdEvent = calendarEventDao.getEventsByAccountList(accountId)
                                .find { it.subject == subject && it.startTime == startTime }
                            
                            if (createdEvent != null) {
                                CalendarReminderReceiver.scheduleReminder(context, createdEvent)
                                return@withContext EasResult.Success(createdEvent)
                            }
                        }
                        
                        // Общий путь: создаём entity локально (и для реального serverId, и для fallback)
                        val event = CalendarEventEntity(
                            id = "${accountId}_${serverId}",
                            accountId = accountId,
                            serverId = serverId,
                            subject = subject,
                            location = location,
                            body = body,
                            startTime = startTime,
                            endTime = endTime,
                            allDayEvent = allDayEvent,
                            reminder = reminder,
                            busyStatus = busyStatus,
                            sensitivity = sensitivity,
                            organizer = "",
                            attendees = attendeesJson,
                            isRecurring = isRecurring,
                            recurrenceRule = recurrenceRule,
                            categories = "",
                            lastModified = System.currentTimeMillis()
                        )
                        calendarEventDao.insert(event)
                        CalendarReminderReceiver.scheduleReminder(context, event)
                        EasResult.Success(event)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
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
        recurrenceType: Int = -1
    ): EasResult<CalendarEventEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(event.accountId)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }
                
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
                        oldSubject = event.subject,
                        recurrenceType = recurrenceType
                    )
                }
                
                when (result) {
                    is EasResult.Success -> {
                        val newLastModified = System.currentTimeMillis()
                        val isRecurringNew = recurrenceType >= 0
                        val recurrenceRuleNew = if (isRecurringNew) {
                            RecurrenceHelper.buildRuleJson(recurrenceType, startTime)
                        } else ""
                        val updatedEvent = event.copy(
                            subject = subject,
                            startTime = startTime,
                            endTime = endTime,
                            location = location,
                            body = body,
                            allDayEvent = allDayEvent,
                            reminder = reminder,
                            busyStatus = busyStatus,
                            sensitivity = sensitivity,
                            isRecurring = isRecurringNew,
                            recurrenceRule = recurrenceRuleNew,
                            lastModified = newLastModified
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
                        
                        // КРИТИЧНО: Синхронизируем календарь для получения актуального serverId с сервера
                        // Это необходимо для последующего удаления (EWS DeleteItem требует ПОЛНЫЙ ItemId)
                        // Exchange 2007 SP1 требует 2 секунды для обработки UpdateItem
                        kotlinx.coroutines.delay(2000)
                        syncCalendar(event.accountId)
                        
                        EasResult.Success(updatedEvent)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_UPDATE_ERROR)
            }
        }
    }
    
    /**
     * Удаление события календаря
     */
    suspend fun deleteEvent(event: CalendarEventEntity): EasResult<Boolean> {
        initFromPrefs(context)
        return withContext(Dispatchers.IO) {
            try {
                val easClient = when (val r = requireExchangeClient(event.accountId)) {
                    is EasResult.Success -> r.data
                    is EasResult.Error -> return@withContext r
                }
                
                val result = easClient.deleteCalendarEvent(event.serverId)
                
                when (result) {
                    is EasResult.Success -> {
                        // Отменяем напоминание
                        CalendarReminderReceiver.cancelReminder(context, event.id)
                        // Мягкое удаление (в корзину) вместо жёсткого
                        calendarEventDao.softDelete(event.id)
                        
                        // КРИТИЧНО: Помечаем serverId как удалённый пользователем.
                        // Защита НЕ имеет TTL — запись удалится только когда syncCalendar()
                        // подтвердит, что сервер больше не возвращает это событие.
                        markAsDeleted(event.serverId, context)
                        android.util.Log.d("CalendarRepository", "Event soft-deleted and marked: serverId=${event.serverId}")
                        
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
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
     * Удаление нескольких событий с прогрессом
     * Используем один EasClient для всего пакета, чтобы избежать проблем с SyncKey
     */
    suspend fun deleteEventsWithProgress(
        events: List<CalendarEventEntity>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        initFromPrefs(context)
        return withContext(Dispatchers.IO) {
            if (events.isEmpty()) return@withContext EasResult.Success(0)
            
            try {
                // Группируем по accountId
                val eventsByAccount = events.groupBy { it.accountId }
                var totalDeleted = 0
                val totalCount = events.size
                
                for ((accountId, accountEvents) in eventsByAccount) {
                    val account = accountRepo.getAccount(accountId)
                    if (account == null) {
                        android.util.Log.w("CalendarRepository", "Account not found: $accountId, skipping ${accountEvents.size} events")
                        continue
                    }
                    
                    if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                        android.util.Log.w("CalendarRepository", "Account is not Exchange: $accountId, skipping ${accountEvents.size} events")
                        continue
                    }
                    
                    val easClient = accountRepo.createEasClient(accountId)
                    if (easClient == null) {
                        android.util.Log.w("CalendarRepository", "Failed to create EAS client for account: $accountId")
                        continue
                    }
                    
                    // Удаляем события по одному
                    for (event in accountEvents) {
                        try {
                            val result = easClient.deleteCalendarEvent(event.serverId)
                            
                            when (result) {
                                is EasResult.Success -> {
                                    // Отменяем напоминание
                                    CalendarReminderReceiver.cancelReminder(context, event.id)
                                    // Мягкое удаление (в корзину)
                                    calendarEventDao.softDelete(event.id)
                                    
                                    // КРИТИЧНО: Помечаем serverId как удалённый пользователем
                                    markAsDeleted(event.serverId, context)
                                    
                                    totalDeleted++
                                    onProgress(totalDeleted, totalCount)
                                    android.util.Log.d("CalendarRepository", "Deleted event: ${event.subject} ($totalDeleted/$totalCount)")
                                }
                                is EasResult.Error -> {
                                    android.util.Log.w("CalendarRepository", "Failed to delete event ${event.subject}: ${result.message}")
                                }
                            }
                            
                            // Небольшая задержка между удалениями
                            kotlinx.coroutines.delay(100)
                            
                        } catch (e: Exception) {
                            android.util.Log.e("CalendarRepository", "Error deleting event ${event.subject}: ${e.message}")
                        }
                    }
                    
                    // НЕ вызываем syncCalendar() после удаления!
                    // Защита от воскрешения: deletedServerIds (без TTL).
                }
                
                EasResult.Success(totalDeleted)
                
            } catch (e: Exception) {
                android.util.Log.e("CalendarRepository", "Error in deleteEventsWithProgress: ${e.message}")
                EasResult.Error(e.message ?: RepositoryErrors.EVENT_DELETE_ERROR)
            }
        }
    }
    
    // === Корзина событий ===
    
    /**
     * Восстановление события из корзины.
     * Пересоздаёт событие на сервере (получает новый serverId) и снимает флаг isDeleted.
     */
    suspend fun restoreEvent(event: CalendarEventEntity): EasResult<Boolean> {
        initFromPrefs(context)
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(event.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                val easClient = accountRepo.createEasClient(event.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                // Парсим участников из JSON для пересоздания на сервере
                val attendeeEmails = parseAttendeesFromJson(event.attendees).map { it.email }
                
                // Восстанавливаем тип повторения из recurrenceRule
                val recurrenceType = if (event.isRecurring && event.recurrenceRule.isNotBlank()) {
                    RecurrenceHelper.parseRule(event.recurrenceRule)?.type ?: -1
                } else -1
                
                // Пересоздаём событие на сервере напрямую (без createEvent, который вызывает syncCalendar)
                val result = easClient.createCalendarEvent(
                    subject = event.subject,
                    startTime = event.startTime,
                    endTime = event.endTime,
                    location = event.location,
                    body = event.body,
                    allDayEvent = event.allDayEvent,
                    reminder = event.reminder,
                    busyStatus = event.busyStatus,
                    sensitivity = event.sensitivity,
                    attendees = attendeeEmails,
                    recurrenceType = recurrenceType
                )
                
                when (result) {
                    is EasResult.Success -> {
                        val newServerId = result.data
                        
                        // Убираем старый serverId из защитного множества
                        removeFromDeleted(event.serverId, context)
                        
                        // Удаляем старую запись
                        calendarEventDao.delete(event.id)
                        
                        // Вставляем обновлённую запись с новым serverId и isDeleted=0
                        val restoredEvent = event.copy(
                            id = "${event.accountId}_${newServerId}",
                            serverId = newServerId,
                            isDeleted = false,
                            lastModified = System.currentTimeMillis()
                        )
                        calendarEventDao.insert(restoredEvent)
                        
                        // Планируем напоминание
                        CalendarReminderReceiver.scheduleReminder(context, restoredEvent)
                        
                        android.util.Log.d("CalendarRepository", "Event restored: old=${event.serverId}, new=$newServerId")
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка восстановления события")
            }
        }
    }
    
    /**
     * Окончательное удаление события из корзины (уже удалено с сервера)
     */
    suspend fun deleteEventPermanently(event: CalendarEventEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                calendarEventDao.delete(event.id)
                EasResult.Success(true)
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка удаления события")
            }
        }
    }
    
    /**
     * Очистка корзины календаря
     */
    suspend fun emptyCalendarTrash(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val deletedEvents = calendarEventDao.getDeletedEventsList(accountId)
                calendarEventDao.emptyTrash(accountId)
                EasResult.Success(deletedEvents.size)
            } catch (e: Exception) {
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
                
                easClient.downloadAttachment(fileReference)
            } catch (e: Exception) {
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
                    easClient.respondToMeetingRequest(event.serverId, response, sendResponse)
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
                EasResult.Error(e.message ?: RepositoryErrors.MEETING_RESPONSE_ERROR)
            }
        }
    }

    
    // === Синхронизация ===
    
    /**
     * Синхронизация календаря с Exchange сервера
     */
    suspend fun syncCalendar(accountId: Long): EasResult<Int> {
        // КРИТИЧНО: Загружаем из SharedPreferences при первом обращении
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
                        val serverEvents = result.data
                        
                        // Получаем существующие события
                        val existingEvents = calendarEventDao.getEventsByAccountList(accountId)
                        val existingServerIds = existingEvents.map { it.serverId }.toSet()
                        
                        // Определяем какие события удалены на сервере
                        val serverReturnedIds = serverEvents.map { it.serverId }.toSet()
                        
                        // КРИТИЧНО: Если сервер вернул пустой список, а локально есть события —
                        // НЕ удаляем их. Пустой ответ может означать ошибку синхронизации
                        // (Status=3, пустое тело, race condition с deleteCalendarEvent),
                        // а НЕ реальное удаление всех событий на сервере.
                        // События, удалённые пользователем, уже soft-deleted и в deletedServerIds.
                        if (serverEvents.isNotEmpty() || existingEvents.isEmpty()) {
                            val serverDeletedIds = existingServerIds - serverReturnedIds
                            
                            // КРИТИЧНО: EAS FilterType=6 возвращает только 3 месяца событий.
                            // События СТАРШЕ 3 месяцев не будут в ответе сервера,
                            // но это НЕ означает что они удалены — просто вне окна фильтра.
                            // Удаляем только те, которых нет на сервере И которые в пределах окна.
                            val filterCutoffMs = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000) // 90 дней
                            val existingEventsMap = existingEvents.associateBy { it.serverId }
                            
                            for (serverId in serverDeletedIds) {
                                val localEvent = existingEventsMap[serverId]
                                // Не удаляем события старше 3 месяцев — они вне окна FilterType=6
                                if (localEvent != null && localEvent.startTime < filterCutoffMs) {
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
                        
                        // КРИТИЧНО: НЕ перезаписываем записи, изменённые локально менее 10 сек назад
                        // Это защита от race condition когда sync получает устаревшие данные после update
                        val syncTime = System.currentTimeMillis()
                        val RECENT_EDIT_THRESHOLD = 10_000L // 10 секунд
                        val existingEventsMap = existingEvents.associateBy { it.serverId }
                        
                        var skippedCount = 0
                        // Добавляем/обновляем события с сервера
                        val eventEntities = uniqueEvents.mapNotNull { event ->
                            val existingEvent = existingEventsMap[event.serverId]
                            
                            // Если локальная версия изменена недавно - НЕ перезаписываем
                            // КРИТИЧНО: Проверяем только если данные ДЕЙСТВИТЕЛЬНО отличаются
                            if (existingEvent != null) {
                                val hasLocalChanges = existingEvent.subject != event.subject || 
                                                     existingEvent.location != event.location ||
                                                     existingEvent.body != event.body ||
                                                     existingEvent.startTime != event.startTime ||
                                                     existingEvent.endTime != event.endTime
                                
                                if (hasLocalChanges) {
                                    val timeSinceLocalEdit = syncTime - existingEvent.lastModified
                                    if (timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                        skippedCount++
                                        return@mapNotNull null // Пропускаем
                                    }
                                }
                            }
                            
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
                                isRecurring = event.isRecurring,
                                recurrenceRule = event.recurrenceRule,
                                uid = event.uid,
                                timezone = event.timezone,
                                exceptions = event.exceptions,
                                categories = event.categories.joinToString(","),
                                lastModified = existingEvent?.lastModified ?: event.lastModified,
                                responseStatus = event.responseStatus,
                                isMeeting = event.isMeeting,
                                meetingStatus = event.meetingStatus,
                                responseRequested = event.responseRequested,
                                appointmentReplyTime = event.appointmentReplyTime,
                                disallowNewTimeProposal = event.disallowNewTimeProposal,
                                onlineMeetingLink = event.onlineMeetingLink,
                                hasAttachments = event.hasAttachments,
                                attachments = event.attachments
                            )
                        }
                        
                        if (eventEntities.isNotEmpty()) {
                            // INSERT OR REPLACE — обновляет существующие
                            calendarEventDao.insertAll(eventEntities)
                            
                            // Перепланируем напоминания только для новых/изменённых событий
                            val newServerIds = serverReturnedIds - existingServerIds
                            val newEvents = eventEntities.filter { it.serverId in newServerIds }
                            if (newEvents.isNotEmpty()) {
                                CalendarReminderReceiver.rescheduleAllReminders(context, newEvents)
                            }
                        }
                        
                        // КРИТИЧНО: Дедупликация — удаляем "призрачные" локальные записи
                        // с clientId-like serverId (32 hex без ":"), если для того же события
                        // уже есть запись с реальным serverId от сервера.
                        try {
                            val allLocalEvents = calendarEventDao.getEventsByAccountList(accountId)
                            val clientIdLike = allLocalEvents.filter { 
                                it.serverId.length == 32 && !it.serverId.contains(":") 
                            }
                            for (ghost in clientIdLike) {
                                // Есть ли реальная запись с тем же контентом?
                                val hasReal = allLocalEvents.any { real ->
                                    real.id != ghost.id &&
                                    real.serverId != ghost.serverId &&
                                    real.serverId.contains(":") &&
                                    real.subject == ghost.subject &&
                                    real.startTime == ghost.startTime &&
                                    real.endTime == ghost.endTime
                                }
                                if (hasReal) {
                                    android.util.Log.w("CalendarRepository",
                                        "syncCalendar: Removing ghost entity with clientId=${ghost.serverId}, subject=${ghost.subject}")
                                    CalendarReminderReceiver.cancelReminder(context, ghost.id)
                                    calendarEventDao.delete(ghost.id)
                                }
                            }
                        } catch (_: Exception) { }
                        
                        EasResult.Success(eventEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
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
                // Ищем событие по названию
                val events = calendarEventDao.getEventsByAccountList(accountId)
                val event = events.find { it.subject.equals(meetingSubject, ignoreCase = true) }
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

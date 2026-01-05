package com.iwo.mailclient.data.repository

import android.content.Context
import com.iwo.mailclient.data.database.*
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.sync.CalendarReminderReceiver
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
    private val accountRepo = AccountRepository(context)
    
    // === Получение событий ===
    
    fun getEvents(accountId: Long): Flow<List<CalendarEventEntity>> {
        return calendarEventDao.getEventsByAccount(accountId)
    }
    
    suspend fun getEventsList(accountId: Long): List<CalendarEventEntity> {
        return calendarEventDao.getEventsByAccountList(accountId)
    }
    
    fun getEventsForDay(accountId: Long, date: Date): Flow<List<CalendarEventEntity>> {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
        return calendarEventDao.getEventsForDay(accountId, startOfDay, endOfDay)
    }

    suspend fun getEventsForDayList(accountId: Long, date: Date): List<CalendarEventEntity> {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
        return calendarEventDao.getEventsForDayList(accountId, startOfDay, endOfDay)
    }
    
    suspend fun getEventsCountForDay(accountId: Long, date: Date): Int {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
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
        attendees: List<String> = emptyList()
    ): EasResult<CalendarEventEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Календарь поддерживается только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
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
                    attendees = attendees
                )
                
                when (result) {
                    is EasResult.Success -> {
                        val serverId = result.data
                        // Сохраняем участников в JSON формате
                        val attendeesJson = if (attendees.isNotEmpty()) {
                            attendees.joinToString(",") { """{"email":"$it","name":""}""" }
                                .let { "[$it]" }
                        } else ""
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
                            isRecurring = false,
                            recurrenceRule = "",
                            categories = "",
                            lastModified = System.currentTimeMillis()
                        )
                        calendarEventDao.insert(event)
                        // Планируем напоминание
                        CalendarReminderReceiver.scheduleReminder(context, event)
                        EasResult.Success(event)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка создания события")
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
                ?: return@withContext EasResult.Error("Аккаунт не найден")
            
            val easClient = accountRepo.createEasClient(accountId)
                ?: return@withContext EasResult.Error("Не удалось создать клиент")
            
            easClient.sendMeetingInvitation(
                subject = subject,
                startTime = startTime,
                endTime = endTime,
                location = location,
                body = body,
                attendees = attendees
            )
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка отправки приглашений")
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
        location: String = "",
        body: String = "",
        allDayEvent: Boolean = false,
        reminder: Int = 15,
        busyStatus: Int = 2,
        sensitivity: Int = 0
    ): EasResult<CalendarEventEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(event.accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Календарь поддерживается только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(event.accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.updateCalendarEvent(
                    serverId = event.serverId,
                    subject = subject,
                    startTime = startTime,
                    endTime = endTime,
                    location = location,
                    body = body,
                    allDayEvent = allDayEvent,
                    reminder = reminder,
                    busyStatus = busyStatus,
                    sensitivity = sensitivity
                )
                
                when (result) {
                    is EasResult.Success -> {
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
                            lastModified = System.currentTimeMillis()
                        )
                        calendarEventDao.update(updatedEvent)
                        // Перепланируем напоминание (отменяем старое, планируем новое)
                        CalendarReminderReceiver.cancelReminder(context, event.id)
                        CalendarReminderReceiver.scheduleReminder(context, updatedEvent)
                        EasResult.Success(updatedEvent)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка обновления события")
            }
        }
    }
    
    /**
     * Удаление события календаря
     */
    suspend fun deleteEvent(event: CalendarEventEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(event.accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Календарь поддерживается только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(event.accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.deleteCalendarEvent(event.serverId)
                
                when (result) {
                    is EasResult.Success -> {
                        // Отменяем напоминание
                        CalendarReminderReceiver.cancelReminder(context, event.id)
                        calendarEventDao.delete(event.id)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка удаления события")
            }
        }
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
                val account = accountRepo.getAccount(event.accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Ответ на приглашения поддерживается только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(event.accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.respondToMeetingRequest(event.serverId, response, sendResponse)
                
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
                EasResult.Error(e.message ?: "Ошибка ответа на приглашение")
            }
        }
    }

    
    // === Синхронизация ===
    
    /**
     * Синхронизация календаря с Exchange сервера
     */
    suspend fun syncCalendar(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                // Только для Exchange аккаунтов
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Календарь поддерживается только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.syncCalendar()
                
                when (result) {
                    is EasResult.Success -> {
                        val serverEvents = result.data
                        
                        // Получаем существующие события
                        val existingEvents = calendarEventDao.getEventsByAccountList(accountId)
                        val existingServerIds = existingEvents.map { it.serverId }.toSet()
                        
                        // Определяем какие события удалены на сервере
                        val serverIds = serverEvents.map { it.serverId }.toSet()
                        val deletedServerIds = existingServerIds - serverIds
                        
                        // Удаляем только те, которых нет на сервере
                        for (serverId in deletedServerIds) {
                            val eventId = "${accountId}_${serverId}"
                            CalendarReminderReceiver.cancelReminder(context, eventId)
                            calendarEventDao.delete(eventId)
                        }
                        
                        // Фильтруем дубликаты по serverId (защита от повторной вставки)
                        val uniqueEvents = serverEvents.distinctBy { it.serverId }
                        
                        // Добавляем/обновляем события с сервера
                        val eventEntities = uniqueEvents.map { event ->
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
                                attendees = attendeesToJson(event.attendees),
                                isRecurring = event.isRecurring,
                                recurrenceRule = event.recurrenceRule,
                                categories = event.categories.joinToString(","),
                                lastModified = event.lastModified,
                                responseStatus = event.responseStatus,
                                isMeeting = event.isMeeting
                            )
                        }
                        
                        if (eventEntities.isNotEmpty()) {
                            // INSERT OR REPLACE — обновляет существующие
                            calendarEventDao.insertAll(eventEntities)
                            // Перепланируем напоминания только для новых/изменённых событий
                            val newServerIds = serverIds - existingServerIds
                            val newEvents = eventEntities.filter { it.serverId in newServerIds }
                            if (newEvents.isNotEmpty()) {
                                CalendarReminderReceiver.rescheduleAllReminders(context, newEvents)
                            }
                        }
                        
                        EasResult.Success(eventEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка синхронизации календаря")
            }
        }
    }
    
    // === Утилиты ===
    
    private fun attendeesToJson(attendees: List<com.iwo.mailclient.eas.EasAttendee>): String {
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
                    ?: return@withContext EasResult.Error("Событие не найдено")
                
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
                    com.iwo.mailclient.eas.EasAttendee(it.email, it.name, it.status) 
                })
                calendarEventDao.updateAttendees(event.id, updatedAttendeesJson)
                
                EasResult.Success(true)
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка обновления статуса")
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

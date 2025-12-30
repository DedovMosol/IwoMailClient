package com.iwo.mailclient.data.repository

import android.content.Context
import com.iwo.mailclient.data.database.*
import com.iwo.mailclient.eas.EasResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Репозиторий для работы с календарём Exchange
 */
class CalendarRepository(context: Context) {
    
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
        sensitivity: Int = 0
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
                    sensitivity = sensitivity
                )
                
                when (result) {
                    is EasResult.Success -> {
                        val serverId = result.data
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
                            attendees = "",
                            isRecurring = false,
                            recurrenceRule = "",
                            categories = "",
                            lastModified = System.currentTimeMillis()
                        )
                        calendarEventDao.insert(event)
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
                        
                        // Удаляем старые события для этого аккаунта
                        calendarEventDao.deleteByAccount(accountId)
                        
                        // Добавляем новые
                        val eventEntities = serverEvents.map { event ->
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
                                lastModified = event.lastModified
                            )
                        }
                        
                        if (eventEntities.isNotEmpty()) {
                            calendarEventDao.insertAll(eventEntities)
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
}

/**
 * Участник события для UI
 */
data class Attendee(
    val email: String,
    val name: String,
    val status: Int
)

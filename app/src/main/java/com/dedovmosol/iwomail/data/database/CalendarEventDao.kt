package com.dedovmosol.iwomail.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarEventDao {
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 ORDER BY startTime ASC")
    fun getEventsByAccount(accountId: Long): Flow<List<CalendarEventEntity>>
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 ORDER BY startTime ASC")
    suspend fun getEventsByAccountList(accountId: Long): List<CalendarEventEntity>
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 AND startTime >= :startOfDay AND startTime < :endOfDay ORDER BY startTime ASC")
    fun getEventsForDay(accountId: Long, startOfDay: Long, endOfDay: Long): Flow<List<CalendarEventEntity>>
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 AND startTime >= :startOfDay AND startTime < :endOfDay ORDER BY startTime ASC")
    suspend fun getEventsForDayList(accountId: Long, startOfDay: Long, endOfDay: Long): List<CalendarEventEntity>
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 AND startTime >= :startTime AND endTime <= :endTime ORDER BY startTime ASC")
    fun getEventsInRange(accountId: Long, startTime: Long, endTime: Long): Flow<List<CalendarEventEntity>>
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 AND startTime >= :startTime AND endTime <= :endTime ORDER BY startTime ASC")
    suspend fun getEventsInRangeList(accountId: Long, startTime: Long, endTime: Long): List<CalendarEventEntity>
    
    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getEvent(id: String): CalendarEventEntity?
    
    @Query("SELECT * FROM calendar_events WHERE id = :id")
    fun getEventFlow(id: String): Flow<CalendarEventEntity?>
    
    @Query("SELECT COUNT(*) FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0")
    fun getEventsCount(accountId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0")
    suspend fun getEventsCountSync(accountId: Long): Int
    
    @Query("SELECT COUNT(*) FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 AND startTime >= :startOfDay AND startTime < :endOfDay")
    suspend fun getEventsCountForDay(accountId: Long, startOfDay: Long, endOfDay: Long): Int
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 0 AND endTime > :now ORDER BY startTime ASC LIMIT 1")
    suspend fun getNextEventSync(accountId: Long, now: Long): CalendarEventEntity?

    @Query("SELECT COUNT(*) FROM calendar_events WHERE isDeleted = 0")
    suspend fun getEventsCountGlobal(): Int

    @Query("SELECT * FROM calendar_events WHERE isDeleted = 0 AND endTime > :now ORDER BY startTime ASC LIMIT 1")
    suspend fun getNextEventGlobalSync(now: Long): CalendarEventEntity?
    
    @Query("""
        SELECT * FROM calendar_events 
        WHERE accountId = :accountId 
        AND isDeleted = 0
        AND (subject LIKE '%' || :query || '%' OR location LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%')
        ORDER BY startTime ASC
    """)
    suspend fun searchEvents(accountId: Long, query: String): List<CalendarEventEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CalendarEventEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalendarEventEntity>)
    
    @Update
    suspend fun update(event: CalendarEventEntity)
    
    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun delete(id: String)
    
    @Query("DELETE FROM calendar_events WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
    
    @Query("SELECT * FROM calendar_events WHERE isDeleted = 0 AND startTime > :now AND reminder > 0")
    suspend fun getAllFutureEventsWithReminders(now: Long): List<CalendarEventEntity>
    
    // Удалённые события (корзина)
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 1 ORDER BY lastModified DESC")
    fun getDeletedEvents(accountId: Long): Flow<List<CalendarEventEntity>>
    
    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND isDeleted = 1")
    suspend fun getDeletedEventsList(accountId: Long): List<CalendarEventEntity>
    
    @Query("SELECT COUNT(*) FROM calendar_events WHERE accountId = :accountId AND isDeleted = 1")
    fun getDeletedEventsCount(accountId: Long): Flow<Int>
    
    // Мягкое удаление (в корзину)
    @Query("UPDATE calendar_events SET isDeleted = 1, lastModified = :timestamp WHERE id = :id")
    suspend fun softDelete(id: String, timestamp: Long = System.currentTimeMillis())
    
    // Восстановление из корзины
    @Query("UPDATE calendar_events SET isDeleted = 0, lastModified = :timestamp WHERE id = :id")
    suspend fun restore(id: String, timestamp: Long = System.currentTimeMillis())
    
    // Очистка корзины
    @Query("DELETE FROM calendar_events WHERE accountId = :accountId AND isDeleted = 1")
    suspend fun emptyTrash(accountId: Long)
    
    @Query("UPDATE calendar_events SET attendees = :attendeesJson WHERE id = :id")
    suspend fun updateAttendees(id: String, attendeesJson: String)
}

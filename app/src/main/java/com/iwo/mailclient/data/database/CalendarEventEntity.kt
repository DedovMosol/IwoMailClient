package com.iwo.mailclient.data.database

import androidx.room.*

/**
 * Статус занятости для события календаря
 */
enum class BusyStatus(val value: Int) {
    FREE(0),
    TENTATIVE(1),
    BUSY(2),
    OUT_OF_OFFICE(3),
    WORKING_ELSEWHERE(4)
}

/**
 * Событие календаря из Exchange
 */
@Entity(
    tableName = "calendar_events",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId"), Index("startTime"), Index("endTime")]
)
data class CalendarEventEntity(
    @PrimaryKey val id: String,  // accountId_serverId
    val accountId: Long,
    val serverId: String,
    val subject: String,
    val location: String = "",
    val body: String = "",
    val startTime: Long,         // Timestamp начала
    val endTime: Long,           // Timestamp окончания
    val allDayEvent: Boolean = false,
    val reminder: Int = 0,       // Минуты до напоминания (0 = нет)
    val busyStatus: Int = BusyStatus.BUSY.value,
    val sensitivity: Int = 0,    // 0=Normal, 1=Personal, 2=Private, 3=Confidential
    val organizer: String = "",  // Email организатора
    val attendees: String = "",  // JSON список участников
    val isRecurring: Boolean = false,
    val recurrenceRule: String = "", // Правило повторения
    val categories: String = "", // Категории через запятую
    val lastModified: Long = System.currentTimeMillis()
)

package com.dedovmosol.iwomail.data.database

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
 * Статус ответа на приглашение
 */
enum class MeetingResponseStatus(val value: Int) {
    NONE(0),           // Не приглашение или не требует ответа
    NOT_RESPONDED(1),  // Ещё не ответил
    ACCEPTED(2),       // Принял
    TENTATIVE(3),      // Под вопросом
    DECLINED(4)        // Отклонил
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
    indices = [Index("accountId"), Index("startTime"), Index("endTime"), Index("isDeleted"), Index("lastModified")]
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
    val organizerName: String = "", // Имя организатора (MS-ASCAL OrganizerName)
    val attendees: String = "",  // JSON список участников
    val isRecurring: Boolean = false,
    val recurrenceRule: String = "", // Правило повторения
    val uid: String = "",        // UID серии повторений (MS-ASCAL UID)
    val timezone: String = "",   // Timezone в base64 (MS-ASCAL Timezone)
    val exceptions: String = "", // JSON исключений из повторений (MS-ASCAL Exceptions)
    val categories: String = "", // Категории через запятую
    val lastModified: Long = System.currentTimeMillis(),
    val responseStatus: Int = MeetingResponseStatus.NONE.value, // Статус ответа на приглашение
    val isMeeting: Boolean = false, // Это встреча с участниками (требует ответа)
    val meetingStatus: Int = 0,  // MeetingStatus (0=Appointment, 1=Meeting, 3=MeetingReceived, 5=Canceled, 7=CanceledReceived)
    val responseRequested: Boolean = false, // Требуется ли ответ на приглашение
    val appointmentReplyTime: Long = 0, // Время ответа на приглашение
    val disallowNewTimeProposal: Boolean = false, // Запрет предложения нового времени
    val onlineMeetingLink: String = "", // Ссылка на онлайн-встречу (Lync/Teams)
    val hasAttachments: Boolean = false, // Есть ли вложения
    val attachments: String = "", // JSON метаданных вложений [{name, fileReference, size, isInline, contentId}]
    val isDeleted: Boolean = false  // Событие в корзине
)

package com.dedovmosol.iwomail.data.database

import androidx.room.*

/**
 * Приоритет задачи
 */
enum class TaskImportance(val value: Int) {
    LOW(0),
    NORMAL(1),
    HIGH(2)
}

/**
 * Задача из Exchange
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId"), Index("dueDate"), Index("complete"), Index("isDeleted")]
)
data class TaskEntity(
    @PrimaryKey val id: String,  // accountId_serverId
    val accountId: Long,
    val serverId: String,
    val subject: String,
    val body: String = "",
    val startDate: Long = 0,     // Timestamp начала (0 = не задано)
    val dueDate: Long = 0,       // Timestamp срока выполнения (0 = не задано)
    val complete: Boolean = false,
    val dateCompleted: Long = 0, // Timestamp выполнения
    val importance: Int = TaskImportance.NORMAL.value,
    val sensitivity: Int = 0,    // 0=Normal, 1=Personal, 2=Private, 3=Confidential
    val reminderSet: Boolean = false,
    val reminderTime: Long = 0,  // Timestamp напоминания
    val categories: String = "", // Категории через запятую
    val lastModified: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false  // Задача в корзине
)

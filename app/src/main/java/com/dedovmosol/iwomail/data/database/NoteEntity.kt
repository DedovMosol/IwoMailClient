package com.dedovmosol.iwomail.data.database

import androidx.room.*

/**
 * Заметка из Exchange Notes
 */
@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId"), Index("lastModified")]
)
data class NoteEntity(
    @PrimaryKey val id: String,  // accountId_serverId
    val accountId: Long,
    val serverId: String,
    val subject: String,         // Заголовок заметки
    val body: String,            // Текст заметки
    val categories: String = "", // Категории через запятую
    val lastModified: Long,      // Дата последнего изменения
    val createdAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false  // В корзине
)

package com.dedovmosol.iwomail.data.database

import androidx.room.*

/**
 * Группа контактов (папка)
 */
@Entity(
    tableName = "contact_groups",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class ContactGroupEntity(
    @PrimaryKey val id: String,  // UUID
    val accountId: Long,
    val name: String,
    val color: Int = 0xFF1976D2.toInt(), // Цвет группы
    val sortOrder: Int = 0,      // Порядок сортировки
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

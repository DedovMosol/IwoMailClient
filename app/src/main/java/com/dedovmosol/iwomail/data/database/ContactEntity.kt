package com.dedovmosol.iwomail.data.database

import androidx.room.*

/**
 * Источник контакта
 */
enum class ContactSource {
    LOCAL,      // Локальный (добавлен вручную или из переписки)
    EXCHANGE    // Синхронизирован с Exchange (папка Contacts)
}

/**
 * Контакт
 */
@Entity(
    tableName = "contacts",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId"), Index("email")]
)
data class ContactEntity(
    @PrimaryKey val id: String,  // accountId_serverId или UUID для локальных
    val accountId: Long,
    val serverId: String? = null, // null для локальных контактов
    val groupId: String? = null,  // ID группы (null = без группы)
    val displayName: String,
    val firstName: String = "",
    val lastName: String = "",
    val email: String,
    val email2: String = "",
    val email3: String = "",
    val phone: String = "",
    val mobilePhone: String = "",
    val workPhone: String = "",
    val company: String = "",
    val department: String = "",
    val jobTitle: String = "",
    val notes: String = "",
    val source: ContactSource = ContactSource.LOCAL,
    val isFavorite: Boolean = false, // Избранный контакт
    val useCount: Int = 0,        // Для сортировки в автодополнении
    val lastUsed: Long = 0,       // Timestamp последнего использования
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

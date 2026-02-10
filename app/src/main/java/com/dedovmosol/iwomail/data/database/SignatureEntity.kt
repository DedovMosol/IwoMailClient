package com.dedovmosol.iwomail.data.database

import androidx.room.*

/**
 * Подпись для писем
 */
@Entity(
    tableName = "signatures",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class SignatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val name: String,           // Название подписи (например: "Рабочая", "Личная")
    val text: String,           // Текст подписи (plain text или HTML)
    val isDefault: Boolean = false,  // Подпись по умолчанию
    val sortOrder: Int = 0,     // Порядок сортировки
    val createdAt: Long = System.currentTimeMillis(),
    val isHtml: Boolean = false // true = HTML с форматированием, false = plain text
)

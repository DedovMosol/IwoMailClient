package com.dedovmosol.iwomail.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Цвет категории Outlook для аккаунта: что сообщил сервер и что выбрал пользователь.
 *
 * Ключ — [nameKey] (имя без учёта регистра, [MS-OXOCFG] 2.2.5.2.2), [name] — как на сервере.
 * Цвета — индексы палитры [MS-OXOCFG] (-1..24). [serverColor] заполняется из Master Category List
 * (Exchange 2010+), [userColor] — ручное назначение (единственный источник на Exchange 2007).
 * Удаление аккаунта каскадно удаляет его цвета.
 */
@Entity(
    tableName = "category_colors",
    primaryKeys = ["accountId", "nameKey"],
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CategoryColorEntity(
    val accountId: Long,
    val nameKey: String,
    val name: String,
    val serverColor: Int? = null,
    val userColor: Int? = null
)

@Dao
interface CategoryColorDao {
    @Query("SELECT * FROM category_colors WHERE accountId = :accountId")
    fun observe(accountId: Long): Flow<List<CategoryColorEntity>>

    @Query("SELECT * FROM category_colors WHERE accountId = :accountId")
    suspend fun getAll(accountId: Long): List<CategoryColorEntity>

    @Query("SELECT * FROM category_colors WHERE accountId = :accountId AND nameKey = :nameKey")
    suspend fun get(accountId: Long, nameKey: String): CategoryColorEntity?

    /** REPLACE безопасен: у таблицы нет дочерних FK, каскадных удалений не бывает. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CategoryColorEntity)

    @Query("DELETE FROM category_colors WHERE accountId = :accountId AND nameKey = :nameKey")
    suspend fun delete(accountId: Long, nameKey: String)
}

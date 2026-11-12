package com.dedovmosol.iwomail.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * Изменение письма, сделанное на устройстве, но ещё не принятое сервером (нет связи): флаг ([flag]) и/или «прочитано»
 * ([read]). Значение не хранится — отправляется текущее из `emails`: несколько правок без связи уходят одним итогом.
 *
 * Пока изменение ждёт отправки, синхронизация не перетирает это поле письма значением сервера
 * ([saveServerFlag], [EmailDao.updateReadStatusFromServer]) — иначе правка пропала бы до отправки.
 *
 * Внешнего ключа на `emails` нет намеренно: письма местами перезаписываются через REPLACE (DELETE + INSERT), и каскад
 * стёр бы ожидающую правку. Записи удалённых писем убирает отправка ([com.dedovmosol.iwomail.data.repository.PendingMailChanges]).
 * Удаление аккаунта — каскадом.
 */
@Entity(
    tableName = "pending_email_changes",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class PendingEmailChangeEntity(
    @PrimaryKey val emailId: String,
    val accountId: Long,
    val flag: Boolean = false,
    val read: Boolean = false
)

/** Очередь изменений писем, ждущих отправки на сервер. Без UPSERT: SQLite Android 8 (3.18) его не знает. */
@Dao
abstract class PendingEmailChangeDao {

    @Query("SELECT * FROM pending_email_changes ORDER BY emailId")
    abstract suspend fun getAll(): List<PendingEmailChangeEntity>

    @Query("SELECT COUNT(*) FROM pending_email_changes")
    abstract suspend fun count(): Int

    @Query("SELECT * FROM pending_email_changes WHERE emailId = :emailId")
    abstract suspend fun get(emailId: String): PendingEmailChangeEntity?

    @Query("""
        INSERT OR REPLACE INTO pending_email_changes (emailId, accountId, flag, read)
        VALUES (:emailId, :accountId, 1, COALESCE((SELECT read FROM pending_email_changes WHERE emailId = :emailId), 0))
    """)
    abstract suspend fun markFlag(emailId: String, accountId: Long)

    @Query("""
        INSERT OR REPLACE INTO pending_email_changes (emailId, accountId, flag, read)
        VALUES (:emailId, :accountId, COALESCE((SELECT flag FROM pending_email_changes WHERE emailId = :emailId), 0), 1)
    """)
    abstract suspend fun markRead(emailId: String, accountId: Long)

    @Query("UPDATE pending_email_changes SET flag = 0 WHERE emailId = :emailId")
    protected abstract suspend fun resetFlag(emailId: String)

    @Query("UPDATE pending_email_changes SET read = 0 WHERE emailId = :emailId")
    protected abstract suspend fun resetRead(emailId: String)

    @Query("DELETE FROM pending_email_changes WHERE emailId = :emailId AND flag = 0 AND read = 0")
    protected abstract suspend fun deleteIfEmpty(emailId: String)

    @Query("DELETE FROM pending_email_changes WHERE emailId = :emailId")
    abstract suspend fun delete(emailId: String)

    /** Письмо получило новый id (ServerId сменился после SyncKey=0 на EAS 12.1): правка переезжает с ним. */
    @Query("UPDATE OR REPLACE pending_email_changes SET emailId = :newId WHERE emailId = :oldId")
    abstract suspend fun rename(oldId: String, newId: String)

    /** Флаг принят (или отклонён) сервером — больше не ждёт отправки. */
    @Transaction
    open suspend fun clearFlag(emailId: String) {
        resetFlag(emailId)
        deleteIfEmpty(emailId)
    }

    /** «Прочитано» принято (или отклонено) сервером — больше не ждёт отправки. */
    @Transaction
    open suspend fun clearRead(emailId: String) {
        resetRead(emailId)
        deleteIfEmpty(emailId)
    }
}

package com.dedovmosol.iwomail.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/** Вид записи с правкой, ждущей отправки. */
enum class PimKind { TASK, NOTE }

/**
 * Правка задачи или заметки, сделанная на устройстве, но ещё не принятая сервером (нет связи): изменение
 * ([pendingUpdate]) и/или перенос в корзину ([pendingTrash]). Значение не хранится — отправляется текущее из
 * `tasks`/`notes`: несколько правок без связи уходят одним итогом.
 *
 * - [serverSubject] — тема на сервере до первой правки без связи: EWS (Exchange 2007) находит задачу по теме, а её
 *   могли уже поменять на устройстве.
 * - [version] растёт с каждой правкой. Отправка снимает отметку, только если версия не изменилась: правка, сделанная во
 *   время отправки, не теряется.
 *
 * Пока правка ждёт отправки, синхронизация не перетирает запись данными сервера и не возвращает её из корзины.
 * Внешнего ключа на `tasks`/`notes` нет намеренно: синхронизация перезаписывает строки через REPLACE (DELETE + INSERT),
 * и каскад стёр бы ожидающую правку. Записи исчезнувших задач и заметок убирает отправка. Удаление аккаунта — каскадом.
 */
@Entity(
    tableName = "pending_pim_changes",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("accountId")]
)
data class PendingPimChangeEntity(
    @PrimaryKey val itemId: String,
    val accountId: Long,
    val kind: PimKind,
    @ColumnInfo(defaultValue = "0") val pendingUpdate: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pendingTrash: Boolean = false,
    @ColumnInfo(defaultValue = "") val serverSubject: String = "",
    @ColumnInfo(defaultValue = "0") val version: Long = 0
)

/** Очередь правок задач и заметок, ждущих отправки. Без UPSERT: SQLite Android 8 (3.18) его не знает. */
@Dao
abstract class PendingPimChangeDao {

    @Query("SELECT * FROM pending_pim_changes ORDER BY itemId")
    abstract suspend fun getAll(): List<PendingPimChangeEntity>

    @Query("SELECT COUNT(*) FROM pending_pim_changes")
    abstract suspend fun count(): Int

    @Query("SELECT * FROM pending_pim_changes WHERE itemId = :itemId")
    abstract suspend fun get(itemId: String): PendingPimChangeEntity?

    @Query("SELECT itemId FROM pending_pim_changes WHERE accountId = :accountId AND kind = :kind")
    abstract suspend fun getItemIds(accountId: Long, kind: PimKind): List<String>

    @Query("""
        INSERT OR IGNORE INTO pending_pim_changes (itemId, accountId, kind, pendingUpdate, pendingTrash, serverSubject, version)
        VALUES (:itemId, :accountId, :kind, 0, 0, :serverSubject, 0)
    """)
    protected abstract suspend fun insertIfAbsent(itemId: String, accountId: Long, kind: PimKind, serverSubject: String)

    @Query("UPDATE pending_pim_changes SET pendingUpdate = 1, version = version + 1 WHERE itemId = :itemId")
    protected abstract suspend fun setUpdate(itemId: String)

    @Query("UPDATE pending_pim_changes SET pendingTrash = 1, version = version + 1 WHERE itemId = :itemId")
    protected abstract suspend fun setTrash(itemId: String)

    @Query("UPDATE pending_pim_changes SET pendingUpdate = 0 WHERE itemId = :itemId AND version = :version")
    protected abstract suspend fun resetUpdate(itemId: String, version: Long)

    @Query("UPDATE pending_pim_changes SET pendingTrash = 0 WHERE itemId = :itemId AND version = :version")
    protected abstract suspend fun resetTrash(itemId: String, version: Long)

    @Query("DELETE FROM pending_pim_changes WHERE itemId = :itemId AND pendingUpdate = 0 AND pendingTrash = 0")
    protected abstract suspend fun deleteIfEmpty(itemId: String)

    @Query("DELETE FROM pending_pim_changes WHERE itemId = :itemId")
    abstract suspend fun delete(itemId: String)

    @Query("DELETE FROM pending_pim_changes WHERE accountId = :accountId AND kind = :kind")
    abstract suspend fun deleteByAccount(accountId: Long, kind: PimKind)

    /** Задача или заметка получила новый id (ServerId сменился): правка переезжает с ней. */
    @Query("UPDATE OR REPLACE pending_pim_changes SET itemId = :newId WHERE itemId = :oldId")
    abstract suspend fun rename(oldId: String, newId: String)

    /** Изменение ждёт отправки. [serverSubject] запоминается только при первой правке. */
    @Transaction
    open suspend fun markUpdate(itemId: String, accountId: Long, kind: PimKind, serverSubject: String) {
        insertIfAbsent(itemId, accountId, kind, serverSubject)
        setUpdate(itemId)
    }

    /** Перенос в корзину ждёт отправки. */
    @Transaction
    open suspend fun markTrash(itemId: String, accountId: Long, kind: PimKind, serverSubject: String) {
        insertIfAbsent(itemId, accountId, kind, serverSubject)
        setTrash(itemId)
    }

    /** Изменение принято сервером — если после отправки его не правили ещё раз ([version]). */
    @Transaction
    open suspend fun clearUpdate(itemId: String, version: Long) {
        resetUpdate(itemId, version)
        deleteIfEmpty(itemId)
    }

    /** Перенос в корзину принят сервером — если после отправки его не правили ещё раз ([version]). */
    @Transaction
    open suspend fun clearTrash(itemId: String, version: Long) {
        resetTrash(itemId, version)
        deleteIfEmpty(itemId)
    }

    /** Восстановление из корзины до отправки: на сервере задача и не уходила в корзину. */
    @Query("UPDATE pending_pim_changes SET pendingTrash = 0 WHERE itemId = :itemId")
    protected abstract suspend fun cancelTrashFlag(itemId: String)

    /** @return true — перенос в корзину ждал отправки и отменён (сервер трогать не нужно). */
    @Transaction
    open suspend fun cancelTrash(itemId: String): Boolean {
        val pending = get(itemId)?.pendingTrash == true
        if (pending) {
            cancelTrashFlag(itemId)
            deleteIfEmpty(itemId)
        }
        return pending
    }
}

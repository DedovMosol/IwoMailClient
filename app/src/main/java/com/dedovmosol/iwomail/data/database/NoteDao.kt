package com.dedovmosol.iwomail.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    
    @Query("SELECT * FROM notes WHERE accountId = :accountId AND isDeleted = 0 ORDER BY lastModified DESC")
    fun getNotesByAccount(accountId: Long): Flow<List<NoteEntity>>
    
    @Query("SELECT * FROM notes WHERE accountId = :accountId AND isDeleted = 0 ORDER BY lastModified DESC")
    suspend fun getNotesByAccountList(accountId: Long): List<NoteEntity>
    
    // Удалённые заметки (корзина)
    @Query("SELECT * FROM notes WHERE accountId = :accountId AND isDeleted = 1 ORDER BY lastModified DESC")
    fun getDeletedNotesByAccount(accountId: Long): Flow<List<NoteEntity>>
    
    @Query("SELECT * FROM notes WHERE accountId = :accountId AND isDeleted = 1 ORDER BY lastModified DESC")
    suspend fun getDeletedNotesByAccountList(accountId: Long): List<NoteEntity>
    
    // Все заметки (для синхронизации)
    @Query("SELECT * FROM notes WHERE accountId = :accountId ORDER BY lastModified DESC")
    suspend fun getAllNotesByAccountList(accountId: Long): List<NoteEntity>
    
    @Query("SELECT COUNT(*) FROM notes WHERE accountId = :accountId AND isDeleted = 1")
    fun getDeletedNotesCount(accountId: Long): Flow<Int>
    
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: String): NoteEntity?
    
    @Query("SELECT * FROM notes WHERE id = :id")
    fun getNoteFlow(id: String): Flow<NoteEntity?>
    
    @Query("SELECT COUNT(*) FROM notes WHERE accountId = :accountId AND isDeleted = 0")
    fun getNotesCount(accountId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM notes WHERE accountId = :accountId AND isDeleted = 0")
    suspend fun getNotesCountSync(accountId: Long): Int
    
    @Query("""
        SELECT * FROM notes 
        WHERE accountId = :accountId 
        AND (subject LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%')
        ORDER BY lastModified DESC
    """)
    suspend fun searchNotes(accountId: Long, query: String): List<NoteEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<NoteEntity>)
    
    @Update
    suspend fun update(note: NoteEntity)
    
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)
    
    @Query("DELETE FROM notes WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
    
    // Пометить как удалённую (переместить в корзину)
    @Query("UPDATE notes SET isDeleted = 1 WHERE id = :id")
    suspend fun markAsDeleted(id: String)
    
    // Восстановить из корзины
    @Query("UPDATE notes SET isDeleted = 0 WHERE id = :id")
    suspend fun restore(id: String)
    
    // Очистить корзину заметок
    @Query("DELETE FROM notes WHERE accountId = :accountId AND isDeleted = 1")
    suspend fun emptyTrash(accountId: Long)
}

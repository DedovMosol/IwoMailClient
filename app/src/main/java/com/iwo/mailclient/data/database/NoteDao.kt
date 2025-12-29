package com.iwo.mailclient.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    
    @Query("SELECT * FROM notes WHERE accountId = :accountId ORDER BY lastModified DESC")
    fun getNotesByAccount(accountId: Long): Flow<List<NoteEntity>>
    
    @Query("SELECT * FROM notes WHERE accountId = :accountId ORDER BY lastModified DESC")
    suspend fun getNotesByAccountList(accountId: Long): List<NoteEntity>
    
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: String): NoteEntity?
    
    @Query("SELECT * FROM notes WHERE id = :id")
    fun getNoteFlow(id: String): Flow<NoteEntity?>
    
    @Query("SELECT COUNT(*) FROM notes WHERE accountId = :accountId")
    fun getNotesCount(accountId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM notes WHERE accountId = :accountId")
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
}

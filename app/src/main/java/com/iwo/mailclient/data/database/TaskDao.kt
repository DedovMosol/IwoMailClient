package com.iwo.mailclient.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    
    @Query("SELECT * FROM tasks WHERE accountId = :accountId ORDER BY complete ASC, dueDate ASC, subject ASC")
    fun getTasksByAccount(accountId: Long): Flow<List<TaskEntity>>
    
    @Query("SELECT * FROM tasks WHERE accountId = :accountId ORDER BY complete ASC, dueDate ASC, subject ASC")
    suspend fun getTasksByAccountList(accountId: Long): List<TaskEntity>
    
    @Query("SELECT * FROM tasks WHERE accountId = :accountId AND complete = 0 ORDER BY dueDate ASC, subject ASC")
    fun getActiveTasks(accountId: Long): Flow<List<TaskEntity>>
    
    @Query("SELECT * FROM tasks WHERE accountId = :accountId AND complete = 1 ORDER BY dateCompleted DESC")
    fun getCompletedTasks(accountId: Long): Flow<List<TaskEntity>>
    
    @Query("SELECT * FROM tasks WHERE accountId = :accountId AND importance = 2 AND complete = 0 ORDER BY dueDate ASC")
    fun getHighPriorityTasks(accountId: Long): Flow<List<TaskEntity>>
    
    @Query("SELECT * FROM tasks WHERE accountId = :accountId AND dueDate > 0 AND dueDate < :deadline AND complete = 0 ORDER BY dueDate ASC")
    fun getOverdueTasks(accountId: Long, deadline: Long): Flow<List<TaskEntity>>
    
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?
    
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun getTaskFlow(id: String): Flow<TaskEntity?>
    
    @Query("SELECT COUNT(*) FROM tasks WHERE accountId = :accountId")
    fun getTasksCount(accountId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM tasks WHERE accountId = :accountId AND complete = 0")
    fun getActiveTasksCount(accountId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM tasks WHERE accountId = :accountId AND complete = 0")
    suspend fun getActiveTasksCountSync(accountId: Long): Int
    
    @Query("""
        SELECT * FROM tasks 
        WHERE accountId = :accountId 
        AND (subject LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%')
        ORDER BY complete ASC, dueDate ASC
    """)
    suspend fun searchTasks(accountId: Long, query: String): List<TaskEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TaskEntity>)
    
    @Update
    suspend fun update(task: TaskEntity)
    
    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
    
    @Query("DELETE FROM tasks WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
    
    @Query("SELECT * FROM tasks WHERE reminderSet = 1 AND reminderTime > :now AND complete = 0")
    suspend fun getTasksWithReminders(now: Long): List<TaskEntity>
}

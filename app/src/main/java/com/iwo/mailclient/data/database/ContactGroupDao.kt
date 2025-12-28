package com.iwo.mailclient.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactGroupDao {
    
    @Query("SELECT * FROM contact_groups WHERE accountId = :accountId ORDER BY sortOrder, name COLLATE NOCASE")
    fun getGroupsByAccount(accountId: Long): Flow<List<ContactGroupEntity>>
    
    @Query("SELECT * FROM contact_groups WHERE accountId = :accountId ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getGroupsByAccountList(accountId: Long): List<ContactGroupEntity>
    
    @Query("SELECT * FROM contact_groups WHERE id = :id")
    suspend fun getGroup(id: String): ContactGroupEntity?
    
    @Query("SELECT COUNT(*) FROM contacts WHERE groupId = :groupId")
    suspend fun getContactCount(groupId: String): Int
    
    @Query("SELECT COUNT(*) FROM contacts WHERE groupId = :groupId")
    fun getContactCountFlow(groupId: String): Flow<Int>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: ContactGroupEntity)
    
    @Update
    suspend fun update(group: ContactGroupEntity)
    
    @Query("UPDATE contact_groups SET name = :name, updatedAt = :timestamp WHERE id = :id")
    suspend fun rename(id: String, name: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE contact_groups SET color = :color, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateColor(id: String, color: Int, timestamp: Long = System.currentTimeMillis())
    
    @Delete
    suspend fun delete(group: ContactGroupEntity)
    
    @Query("DELETE FROM contact_groups WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM contact_groups WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
    
    @Query("SELECT COUNT(*) FROM contact_groups WHERE accountId = :accountId")
    suspend fun getCount(accountId: Long): Int
}

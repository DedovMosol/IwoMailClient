package com.iwo.mailclient.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SignatureDao {
    
    @Query("SELECT * FROM signatures WHERE accountId = :accountId ORDER BY sortOrder ASC, createdAt ASC")
    fun getSignaturesByAccount(accountId: Long): Flow<List<SignatureEntity>>
    
    @Query("SELECT * FROM signatures WHERE accountId = :accountId ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getSignaturesByAccountList(accountId: Long): List<SignatureEntity>
    
    @Query("SELECT * FROM signatures WHERE id = :id")
    suspend fun getSignature(id: Long): SignatureEntity?
    
    @Query("SELECT * FROM signatures WHERE accountId = :accountId AND isDefault = 1 LIMIT 1")
    suspend fun getDefaultSignature(accountId: Long): SignatureEntity?
    
    @Query("SELECT COUNT(*) FROM signatures WHERE accountId = :accountId")
    suspend fun getCount(accountId: Long): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signature: SignatureEntity): Long
    
    @Update
    suspend fun update(signature: SignatureEntity)
    
    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun delete(id: Long)
    
    @Query("DELETE FROM signatures WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
    
    @Query("UPDATE signatures SET isDefault = 0 WHERE accountId = :accountId")
    suspend fun clearDefaultForAccount(accountId: Long)
    
    @Query("UPDATE signatures SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)
    
    @Transaction
    suspend fun setDefaultSignature(accountId: Long, signatureId: Long) {
        clearDefaultForAccount(accountId)
        setDefault(signatureId)
    }
}

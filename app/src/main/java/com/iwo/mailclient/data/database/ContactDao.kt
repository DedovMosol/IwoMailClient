package com.iwo.mailclient.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    
    // === Получение контактов ===
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId ORDER BY displayName COLLATE NOCASE")
    fun getContactsByAccount(accountId: Long): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND source = 'LOCAL' ORDER BY displayName COLLATE NOCASE")
    fun getLocalContacts(accountId: Long): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND source = 'EXCHANGE' ORDER BY displayName COLLATE NOCASE")
    fun getExchangeContacts(accountId: Long): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND source = 'EXCHANGE' ORDER BY displayName COLLATE NOCASE")
    suspend fun getExchangeContactsList(accountId: Long): List<ContactEntity>
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId ORDER BY displayName COLLATE NOCASE")
    suspend fun getContactsByAccountList(accountId: Long): List<ContactEntity>
    
    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getContact(id: String): ContactEntity?
    
    @Query("SELECT * FROM contacts WHERE id = :id")
    fun getContactFlow(id: String): Flow<ContactEntity?>
    
    // === Поиск ===
    
    @Query("""
        SELECT * FROM contacts 
        WHERE accountId = :accountId 
        AND (displayName LIKE '%' || :query || '%' 
             OR email LIKE '%' || :query || '%'
             OR company LIKE '%' || :query || '%')
        ORDER BY useCount DESC, displayName COLLATE NOCASE
        LIMIT :limit
    """)
    suspend fun searchContacts(accountId: Long, query: String, limit: Int = 50): List<ContactEntity>
    
    @Query("""
        SELECT * FROM contacts 
        WHERE accountId = :accountId 
        AND (email LIKE :query || '%' 
             OR displayName LIKE :query || '%')
        AND LOWER(email) != LOWER(:ownEmail)
        GROUP BY LOWER(email)
        ORDER BY useCount DESC, lastUsed DESC
        LIMIT :limit
    """)
    suspend fun searchForAutocomplete(accountId: Long, query: String, ownEmail: String, limit: Int = 10): List<ContactEntity>
    
    // === Проверка существования ===
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND email = :email LIMIT 1")
    suspend fun findByEmail(accountId: Long, email: String): ContactEntity?
    
    @Query("SELECT displayName FROM contacts WHERE LOWER(email) = LOWER(:email) AND displayName IS NOT NULL AND displayName != '' LIMIT 1")
    suspend fun getNameByEmail(email: String): String?
    
    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE id = :id)")
    suspend fun exists(id: String): Boolean
    
    // === Вставка/обновление ===
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<ContactEntity>)
    
    @Update
    suspend fun update(contact: ContactEntity)
    
    @Query("UPDATE contacts SET useCount = useCount + 1, lastUsed = :timestamp WHERE id = :id")
    suspend fun incrementUseCount(id: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE contacts SET useCount = useCount + 1, lastUsed = :timestamp WHERE accountId = :accountId AND email = :email")
    suspend fun incrementUseCountByEmail(accountId: Long, email: String, timestamp: Long = System.currentTimeMillis())
    
    // === Удаление ===
    
    @Delete
    suspend fun delete(contact: ContactEntity)
    
    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteById(id: String): Int  // Возвращает количество удалённых строк
    
    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int  // Массовое удаление по списку ID
    
    @Query("DELETE FROM contacts WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
    
    @Query("DELETE FROM contacts WHERE accountId = :accountId AND source = :source")
    suspend fun deleteByAccountAndSource(accountId: Long, source: ContactSource)
    
    @Query("DELETE FROM contacts WHERE accountId = :accountId AND source = 'EXCHANGE'")
    suspend fun deleteExchangeContacts(accountId: Long)
    
    // === Группы ===
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND groupId = :groupId ORDER BY displayName COLLATE NOCASE")
    fun getContactsByGroup(accountId: Long, groupId: String): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND groupId IS NULL ORDER BY displayName COLLATE NOCASE")
    fun getContactsWithoutGroup(accountId: Long): Flow<List<ContactEntity>>
    
    @Query("UPDATE contacts SET groupId = :groupId, updatedAt = :timestamp WHERE id = :contactId")
    suspend fun moveToGroup(contactId: String, groupId: String?, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE contacts SET groupId = NULL, updatedAt = :timestamp WHERE groupId = :groupId")
    suspend fun removeAllFromGroup(groupId: String, timestamp: Long = System.currentTimeMillis())
    
    // === Избранные ===
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND isFavorite = 1 ORDER BY displayName COLLATE NOCASE")
    fun getFavoriteContacts(accountId: Long): Flow<List<ContactEntity>>
    
    @Query("SELECT * FROM contacts WHERE accountId = :accountId AND isFavorite = 1 ORDER BY displayName COLLATE NOCASE")
    suspend fun getFavoriteContactsList(accountId: Long): List<ContactEntity>
    
    @Query("UPDATE contacts SET isFavorite = :isFavorite, updatedAt = :timestamp WHERE id = :contactId")
    suspend fun setFavorite(contactId: String, isFavorite: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT COUNT(*) FROM contacts WHERE accountId = :accountId AND isFavorite = 1")
    suspend fun getFavoriteCount(accountId: Long): Int
    
    // === Статистика ===
    
    @Query("SELECT COUNT(*) FROM contacts WHERE accountId = :accountId")
    suspend fun getCount(accountId: Long): Int
    
    @Query("SELECT COUNT(*) FROM contacts WHERE accountId = :accountId AND source = :source")
    suspend fun getCountBySource(accountId: Long, source: ContactSource): Int
}

package com.exchange.mailclient.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun getAllAccounts(): Flow<List<AccountEntity>>
    
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAllAccountsList(): List<AccountEntity>
    
    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    fun getActiveAccount(): Flow<AccountEntity?>
    
    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccountSync(): AccountEntity?
    
    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccount(id: Long): AccountEntity?
    
    @Query("UPDATE accounts SET isActive = 0")
    suspend fun deactivateAll()
    
    @Query("UPDATE accounts SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)
    
    @Transaction
    suspend fun setActiveAccount(id: Long) {
        deactivateAll()
        activate(id)
    }
    
    @Query("UPDATE accounts SET folderSyncKey = :syncKey WHERE id = :id")
    suspend fun updateFolderSyncKey(id: Long, syncKey: String)
    
    @Query("UPDATE accounts SET policyKey = :policyKey WHERE id = :id")
    suspend fun updatePolicyKey(id: Long, policyKey: String?)
    
    @Query("UPDATE accounts SET syncMode = :syncMode WHERE id = :id")
    suspend fun updateSyncMode(id: Long, syncMode: String)
    
    @Query("UPDATE accounts SET syncIntervalMinutes = :intervalMinutes WHERE id = :id")
    suspend fun updateSyncInterval(id: Long, intervalMinutes: Int)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long
    
    @Update
    suspend fun update(account: AccountEntity)
    
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)
    
    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getCount(): Int
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY type ASC, displayName ASC")
    fun getFoldersByAccount(accountId: Long): Flow<List<FolderEntity>>
    
    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY type ASC")
    suspend fun getFoldersByAccountList(accountId: Long): List<FolderEntity>
    
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolder(id: String): FolderEntity?
    
    @Query("SELECT * FROM folders WHERE accountId = :accountId AND type = :type LIMIT 1")
    suspend fun getFolderByType(accountId: Long, type: Int): FolderEntity?
    
    @Query("UPDATE folders SET syncKey = :syncKey WHERE id = :id")
    suspend fun updateSyncKey(id: String, syncKey: String)
    
    @Query("UPDATE folders SET syncKey = '0' WHERE accountId = :accountId")
    suspend fun resetAllSyncKeys(accountId: Long)
    
    @Query("UPDATE folders SET unreadCount = :count WHERE id = :id")
    suspend fun updateUnreadCount(id: String, count: Int)
    
    @Query("UPDATE folders SET totalCount = :count WHERE id = :id")
    suspend fun updateTotalCount(id: String, count: Int)
    
    @Query("UPDATE folders SET unreadCount = :unread, totalCount = :total WHERE id = :id")
    suspend fun updateCounts(id: String, unread: Int, total: Int)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(folders: List<FolderEntity>)
    
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
    
    @Query("DELETE FROM folders WHERE accountId = :accountId")
    suspend fun deleteByAccount(accountId: Long)
    
    @Query("UPDATE folders SET displayName = :displayName WHERE id = :id")
    suspend fun updateDisplayName(id: String, displayName: String)
}

@Dao
interface EmailDao {
    @Query("SELECT * FROM emails WHERE folderId = :folderId ORDER BY dateReceived DESC")
    fun getEmailsByFolder(folderId: String): Flow<List<EmailEntity>>
    
    @Query("SELECT * FROM emails WHERE folderId = :folderId ORDER BY dateReceived DESC")
    suspend fun getEmailsByFolderList(folderId: String): List<EmailEntity>
    
    @Query("SELECT * FROM emails WHERE folderId = :folderId ORDER BY dateReceived DESC LIMIT :limit OFFSET :offset")
    suspend fun getEmailsByFolderPaged(folderId: String, limit: Int, offset: Int): List<EmailEntity>
    
    @Query("SELECT * FROM emails WHERE id = :id")
    suspend fun getEmail(id: String): EmailEntity?
    
    @Query("SELECT * FROM emails WHERE id = :id")
    fun getEmailFlow(id: String): Flow<EmailEntity?>
    
    @Query("UPDATE emails SET read = :read WHERE id = :id")
    suspend fun updateReadStatus(id: String, read: Boolean)
    
    @Query("UPDATE emails SET flagged = :flagged WHERE id = :id")
    suspend fun updateFlagStatus(id: String, flagged: Boolean)
    
    @Query("UPDATE emails SET body = :body WHERE id = :id")
    suspend fun updateBody(id: String, body: String)
    
    @Query("UPDATE emails SET originalFolderId = :originalFolderId WHERE id = :id")
    suspend fun updateOriginalFolderId(id: String, originalFolderId: String)
    
    @Query("UPDATE emails SET mdnSent = :mdnSent WHERE id = :id")
    suspend fun updateMdnSent(id: String, mdnSent: Boolean)
    
    @Query("UPDATE emails SET mdnRequestedBy = :mdnRequestedBy WHERE id = :id")
    suspend fun updateMdnRequestedBy(id: String, mdnRequestedBy: String)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(emails: List<EmailEntity>)
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(emails: List<EmailEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(email: EmailEntity)
    
    @Query("DELETE FROM emails WHERE id = :id")
    suspend fun delete(id: String)
    
    @Query("DELETE FROM emails WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: String)
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId AND read = 0")
    suspend fun getUnreadCount(folderId: String): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId")
    suspend fun getTotalCount(folderId: String): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId")
    suspend fun getCountByFolder(folderId: String): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND read = 0")
    suspend fun getUnreadCountByAccount(accountId: Long): Int
    
    @Query("""
        SELECT * FROM emails 
        WHERE accountId = :accountId 
        AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%')
        ORDER BY dateReceived DESC
        LIMIT 50
    """)
    suspend fun search(accountId: Long, query: String): List<EmailEntity>
    
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND flagged = 1 ORDER BY dateReceived DESC")
    fun getFlaggedEmails(accountId: Long): Flow<List<EmailEntity>>
    
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND flagged = 1")
    suspend fun getFlaggedCount(accountId: Long): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND flagged = 1")
    fun getFlaggedCountFlow(accountId: Long): Flow<Int>
    
    @Query("SELECT * FROM emails WHERE id IN (:ids) ORDER BY dateReceived DESC")
    suspend fun getEmailsByIds(ids: List<String>): List<EmailEntity>
    
    /**
     * Получает непрочитанные письма из Inbox (type=2), полученные после указанного времени
     * Используется для определения новых писем для уведомлений
     */
    @Query("""
        SELECT e.* FROM emails e 
        INNER JOIN folders f ON e.folderId = f.id 
        WHERE e.accountId = :accountId 
        AND f.type = 2 
        AND e.read = 0 
        AND e.dateReceived > :afterTime 
        ORDER BY e.dateReceived DESC
    """)
    suspend fun getNewUnreadEmails(accountId: Long, afterTime: Long): List<EmailEntity>
    
    /**
     * Получает все непрочитанные письма из Inbox для аккаунта
     */
    @Query("""
        SELECT e.* FROM emails e 
        INNER JOIN folders f ON e.folderId = f.id 
        WHERE e.accountId = :accountId 
        AND f.type = 2 
        AND e.read = 0 
        ORDER BY e.dateReceived DESC
    """)
    suspend fun getUnreadInboxEmails(accountId: Long): List<EmailEntity>
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE emailId = :emailId")
    fun getAttachments(emailId: String): Flow<List<AttachmentEntity>>
    
    @Query("SELECT * FROM attachments WHERE emailId = :emailId")
    suspend fun getAttachmentsList(emailId: String): List<AttachmentEntity>
    
    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getAttachment(id: Long): AttachmentEntity?
    
    @Query("UPDATE attachments SET localPath = :localPath, downloaded = 1 WHERE id = :id")
    suspend fun updateLocalPath(id: Long, localPath: String)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)
    
    @Query("DELETE FROM attachments WHERE emailId = :emailId")
    suspend fun deleteByEmail(emailId: String)
}


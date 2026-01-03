package com.iwo.mailclient.data.database

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
    
    @Query("UPDATE accounts SET signature = :signature WHERE id = :id")
    suspend fun updateSignature(id: Long, signature: String)
    
    @Query("UPDATE accounts SET certificatePath = :certificatePath WHERE id = :id")
    suspend fun updateCertificatePath(id: Long, certificatePath: String?)
    
    @Query("UPDATE accounts SET autoCleanupTrashDays = :days WHERE id = :id")
    suspend fun updateAutoCleanupTrashDays(id: Long, days: Int)
    
    @Query("UPDATE accounts SET autoCleanupDraftsDays = :days WHERE id = :id")
    suspend fun updateAutoCleanupDraftsDays(id: Long, days: Int)
    
    @Query("UPDATE accounts SET autoCleanupSpamDays = :days WHERE id = :id")
    suspend fun updateAutoCleanupSpamDays(id: Long, days: Int)
    
    @Query("UPDATE accounts SET contactsSyncIntervalDays = :days WHERE id = :id")
    suspend fun updateContactsSyncInterval(id: Long, days: Int)
    
    @Query("UPDATE accounts SET contactsSyncKey = :syncKey WHERE id = :id")
    suspend fun updateContactsSyncKey(id: Long, syncKey: String)
    
    @Query("UPDATE accounts SET notesSyncIntervalDays = :days WHERE id = :id")
    suspend fun updateNotesSyncInterval(id: Long, days: Int)
    
    @Query("UPDATE accounts SET notesSyncKey = :syncKey WHERE id = :id")
    suspend fun updateNotesSyncKey(id: Long, syncKey: String)
    
    @Query("UPDATE accounts SET calendarSyncIntervalDays = :days WHERE id = :id")
    suspend fun updateCalendarSyncInterval(id: Long, days: Int)
    
    @Query("UPDATE accounts SET calendarSyncKey = :syncKey WHERE id = :id")
    suspend fun updateCalendarSyncKey(id: Long, syncKey: String)
    
    @Query("UPDATE accounts SET tasksSyncIntervalDays = :days WHERE id = :id")
    suspend fun updateTasksSyncInterval(id: Long, days: Int)
    
    @Query("UPDATE accounts SET tasksSyncKey = :syncKey WHERE id = :id")
    suspend fun updateTasksSyncKey(id: Long, syncKey: String)
    
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
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(folders: List<FolderEntity>)
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity)
    
    @Query("UPDATE folders SET displayName = :displayName, parentId = :parentId, type = :type WHERE id = :id")
    suspend fun updateFolder(id: String, displayName: String, parentId: String, type: Int)
    
    /**
     * Batch upsert папок в одной транзакции (оптимизация производительности)
     */
    @Transaction
    suspend fun upsertFolders(folders: List<FolderEntity>) {
        insertAll(folders)
        for (folder in folders) {
            updateFolder(folder.id, folder.displayName, folder.parentId, folder.type)
        }
    }
    
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
    
    @Update
    suspend fun update(email: EmailEntity)
    
    @Query("DELETE FROM emails WHERE id = :id")
    suspend fun delete(id: String)
    
    @Query("DELETE FROM emails WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
    
    @Query("DELETE FROM emails WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: String)
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId AND read = 0")
    suspend fun getUnreadCount(folderId: String): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId")
    suspend fun getTotalCount(folderId: String): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId")
    suspend fun getCountByFolder(folderId: String): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND serverId LIKE 'local_draft_%'")
    suspend fun getLocalDraftCount(accountId: Long): Int
    
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
    
    /**
     * Получает все черновики для аккаунта (из папки с type = 3)
     */
    @Query("""
        SELECT e.* FROM emails e 
        INNER JOIN folders f ON e.folderId = f.id 
        WHERE e.accountId = :accountId 
        AND f.type = 3 
        ORDER BY e.dateReceived DESC
    """)
    suspend fun getDraftEmails(accountId: Long): List<EmailEntity>
    
    /**
     * Получает все локальные черновики для аккаунта (по serverId)
     */
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND serverId LIKE 'local_draft_%' ORDER BY dateReceived DESC")
    suspend fun getLocalDraftEmails(accountId: Long): List<EmailEntity>
    
    /**
     * Получает письма из папки, полученные раньше указанного времени
     * Используется для автоочистки корзины
     */
    @Query("SELECT * FROM emails WHERE folderId = :folderId AND dateReceived < :beforeTime")
    suspend fun getEmailsOlderThan(folderId: String, beforeTime: Long): List<EmailEntity>
    
    /**
     * Получает уникальные email адреса из истории переписки для автодополнения
     * Ищет по полям from, to, fromName
     */
    @Query("""
        SELECT DISTINCT `from` as email, fromName as name FROM emails 
        WHERE accountId = :accountId 
        AND (`from` LIKE :query || '%' OR fromName LIKE '%' || :query || '%')
        UNION
        SELECT DISTINCT `to` as email, '' as name FROM emails 
        WHERE accountId = :accountId 
        AND `to` LIKE :query || '%'
        LIMIT :limit
    """)
    suspend fun searchEmailHistory(accountId: Long, query: String, limit: Int = 10): List<EmailHistoryResult>
}

/**
 * Результат поиска по истории писем
 */
data class EmailHistoryResult(
    val email: String,
    val name: String
)

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
    
    @Query("DELETE FROM attachments WHERE emailId IN (:emailIds)")
    suspend fun deleteByEmailIds(emailIds: List<String>)
    
    @Query("""
        SELECT a.localPath FROM attachments a
        INNER JOIN emails e ON a.emailId = e.id
        WHERE e.accountId = :accountId AND a.localPath IS NOT NULL
    """)
    suspend fun getLocalPathsByAccount(accountId: Long): List<String>
}

/**
 * DAO для транзакционных операций синхронизации
 * Объединяет несколько операций в одну транзакцию для консистентности и производительности
 */
@Dao
abstract class SyncDao {
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEmailsIgnore(emails: List<EmailEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAttachments(attachments: List<AttachmentEntity>)
    
    @Query("DELETE FROM attachments WHERE emailId = :emailId")
    abstract suspend fun deleteAttachmentsByEmail(emailId: String)
    
    @Query("DELETE FROM emails WHERE id = :id")
    abstract suspend fun deleteEmail(id: String)
    
    @Query("UPDATE folders SET unreadCount = :unread, totalCount = :total WHERE id = :id")
    abstract suspend fun updateFolderCounts(id: String, unread: Int, total: Int)
    
    @Query("UPDATE folders SET syncKey = :syncKey WHERE id = :id")
    abstract suspend fun updateFolderSyncKey(id: String, syncKey: String)
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId")
    abstract suspend fun getEmailCount(folderId: String): Int
    
    @Query("SELECT COUNT(*) FROM emails WHERE folderId = :folderId AND read = 0")
    abstract suspend fun getUnreadCount(folderId: String): Int
    
    /**
     * Вставка писем и вложений в одной транзакции
     */
    @Transaction
    open suspend fun insertEmailsWithAttachments(
        emails: List<EmailEntity>,
        attachments: List<AttachmentEntity>
    ) {
        if (emails.isNotEmpty()) {
            insertEmailsIgnore(emails)
        }
        if (attachments.isNotEmpty()) {
            insertAttachments(attachments)
        }
    }
    
    /**
     * Удаление писем с вложениями в одной транзакции
     */
    @Transaction
    open suspend fun deleteEmailsWithAttachments(emailIds: List<String>) {
        for (emailId in emailIds) {
            deleteAttachmentsByEmail(emailId)
            deleteEmail(emailId)
        }
    }
    
    /**
     * Полная синхронизация: вставка новых, удаление старых, обновление счётчиков
     */
    @Transaction
    open suspend fun syncEmailsBatch(
        folderId: String,
        newEmails: List<EmailEntity>,
        newAttachments: List<AttachmentEntity>,
        deletedServerIds: List<String>,
        accountId: Long,
        newSyncKey: String
    ) {
        // Вставляем новые письма
        if (newEmails.isNotEmpty()) {
            insertEmailsIgnore(newEmails)
        }
        
        // Вставляем вложения
        if (newAttachments.isNotEmpty()) {
            insertAttachments(newAttachments)
        }
        
        // Удаляем удалённые на сервере
        for (serverId in deletedServerIds) {
            val emailId = "${accountId}_$serverId"
            deleteAttachmentsByEmail(emailId)
            deleteEmail(emailId)
        }
        
        // Обновляем syncKey
        updateFolderSyncKey(folderId, newSyncKey)
        
        // Обновляем счётчики
        val total = getEmailCount(folderId)
        val unread = getUnreadCount(folderId)
        updateFolderCounts(folderId, unread, total)
    }
}


package com.dedovmosol.iwomail.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun getAllAccounts(): Flow<List<AccountEntity>>
    
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAllAccountsList(): List<AccountEntity>
    
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun getAllAccountsSync(): List<AccountEntity>
    
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
    
    @Query("UPDATE accounts SET clientCertificatePath = :path WHERE id = :id")
    suspend fun updateClientCertificatePath(id: Long, path: String?)
    
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
    
    @Query("UPDATE accounts SET nightModeEnabled = :enabled WHERE id = :id")
    suspend fun updateNightModeEnabled(id: Long, enabled: Boolean)
    
    @Query("UPDATE accounts SET ignoreBatterySaver = :ignore WHERE id = :id")
    suspend fun updateIgnoreBatterySaver(id: Long, ignore: Boolean)
    
    @Query("UPDATE accounts SET pinnedCertificateHash = :hash WHERE id = :accountId")
    suspend fun updatePinnedCertHash(accountId: Long, hash: String?)
    
    @Query("""
        UPDATE accounts SET 
            pinnedCertificateHash = :hash,
            pinnedCertificateCN = :cn,
            pinnedCertificateOrg = :org,
            pinnedCertificateValidFrom = :validFrom,
            pinnedCertificateValidTo = :validTo
        WHERE id = :accountId
    """)
    suspend fun updatePinnedCertificate(
        accountId: Long, 
        hash: String?, 
        cn: String?, 
        org: String?, 
        validFrom: Long?, 
        validTo: Long?
    )
    
    @Query("UPDATE accounts SET certificatePinningFailCount = :count WHERE id = :accountId")
    suspend fun updateCertificatePinningFailCount(accountId: Long, count: Int)
    
    @Query("UPDATE accounts SET certificatePinningEnabled = :enabled WHERE id = :accountId")
    suspend fun updateCertificatePinningEnabled(accountId: Long, enabled: Boolean)
    
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
    
    @Query("UPDATE emails SET preview = :preview WHERE id = :id")
    suspend fun updatePreview(id: String, preview: String)
    
    @Query("UPDATE emails SET bodyType = :bodyType WHERE id = :id")
    suspend fun updateBodyType(id: String, bodyType: Int)
    
    @Query("UPDATE emails SET `to` = :to WHERE id = :id")
    suspend fun updateTo(id: String, to: String)
    
    @Query("UPDATE emails SET cc = :cc WHERE id = :id")
    suspend fun updateCc(id: String, cc: String)
    
    // Обновление полей черновика одним запросом БЕЗ REPLACE.
    // КРИТИЧНО: emailDao.insert() с REPLACE = DELETE + INSERT.
    // DELETE триггерит CASCADE → все AttachmentEntity для этого email удаляются.
    // Этот метод делает UPDATE IN PLACE — вложения не затрагиваются.
    // ВКЛЮЧАЕТ from/fromName: Exchange 2007 SP1 (и новее) НЕ гарантирует <From>
    // в EAS Sync для черновиков (черновик не отправлен → нет отправителя).
    // После EAS миграции from="" → аватар "?". Обновление from/fromName
    // при каждом сохранении восстанавливает корректный аватар.
    @Query("UPDATE emails SET `to` = :to, cc = :cc, subject = :subject, body = :body, preview = :preview, dateReceived = :dateReceived, `from` = :fromEmail, fromName = :fromName WHERE id = :id")
    suspend fun updateDraftFields(id: String, to: String, cc: String, subject: String, body: String, preview: String, dateReceived: Long, fromEmail: String, fromName: String)
    
    // Обновление from/fromName для черновиков при миграции.
    // Exchange 2007 SP1+ не гарантирует <From> для черновиков в EAS Sync.
    @Query("UPDATE emails SET `from` = :from, fromName = :fromName WHERE id = :id")
    suspend fun updateFrom(id: String, from: String, fromName: String)

    @Query("UPDATE emails SET originalFolderId = :originalFolderId WHERE id = :id")
    suspend fun updateOriginalFolderId(id: String, originalFolderId: String)
    
    @Query("UPDATE emails SET folderId = :folderId WHERE id = :id")
    suspend fun updateFolderId(id: String, folderId: String)
    
    @Query("UPDATE emails SET mdnSent = :mdnSent WHERE id = :id")
    suspend fun updateMdnSent(id: String, mdnSent: Boolean)
    
    @Query("UPDATE emails SET mdnRequestedBy = :mdnRequestedBy WHERE id = :id")
    suspend fun updateMdnRequestedBy(id: String, mdnRequestedBy: String)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(emails: List<EmailEntity>)
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(emails: List<EmailEntity>)
    
    @Query("SELECT id FROM emails WHERE id IN (:ids)")
    suspend fun getExistingIds(ids: List<String>): List<String>
    
    @Query("SELECT serverId FROM emails WHERE accountId = :accountId AND serverId IN (:serverIds)")
    suspend fun getExistingServerIds(accountId: Long, serverIds: List<String>): List<String>
    
    /**
     * Получает ID существующих писем по содержимому (для дедупликации при изменении serverId)
     * Возвращает список ID писем, которые уже есть в БД с таким же содержимым
     * Используется допуск ±5 секунд на dateReceived для учёта возможных расхождений времени
     */
    @Query("""
        SELECT id FROM emails 
        WHERE accountId = :accountId 
        AND folderId = :folderId
        AND subject = :subject 
        AND `from` = :from 
        AND ABS(dateReceived - :dateReceived) < 5000
        LIMIT 1
    """)
    suspend fun findDuplicateByContent(
        accountId: Long,
        folderId: String,
        subject: String,
        from: String,
        dateReceived: Long
    ): String?
    
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

    @Query("SELECT accountId, COUNT(*) AS unreadCount FROM emails WHERE read = 0 GROUP BY accountId")
    suspend fun getUnreadCountsByAccount(): List<AccountUnreadCount>
    
    @Query("""
        SELECT folderId, 
               COUNT(*) AS totalCount, 
               SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END) AS unreadCount 
        FROM emails 
        WHERE accountId = :accountId 
        GROUP BY folderId
    """)
    suspend fun getCountsByAccount(accountId: Long): List<FolderEmailCounts>
    
    @Query("""
        SELECT * FROM emails 
        WHERE accountId = :accountId 
        AND (subject LIKE '%' || :query || '%' ESCAPE '\' OR `from` LIKE '%' || :query || '%' ESCAPE '\' OR body LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY dateReceived DESC
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
     * @deprecated Используйте getNewEmailsForNotification - не зависит от статуса прочитанности
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
     * Получает НОВЫЕ письма из Inbox (type=2), полученные после указанного времени
     * КРИТИЧНО: НЕ зависит от статуса прочитанности!
     * Если письмо прочитано на другом устройстве (Outlook, смартфон) до синхронизации,
     * уведомление всё равно будет показано.
     * 
     * @param accountId ID аккаунта
     * @param afterTime Время в миллисекундах - показывать письма полученные ПОСЛЕ этого времени
     */
    @Query("""
        SELECT e.* FROM emails e 
        INNER JOIN folders f ON e.folderId = f.id 
        WHERE e.accountId = :accountId 
        AND f.type = 2 
        AND e.dateReceived > :afterTime 
        ORDER BY e.dateReceived DESC
    """)
    suspend fun getNewEmailsForNotification(accountId: Long, afterTime: Long): List<EmailEntity>
    
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
     * Исключает email текущего аккаунта (ownEmail)
     */
    @Query("""
        SELECT email, MAX(name) as name FROM (
            SELECT DISTINCT `from` as email, fromName as name FROM emails 
            WHERE accountId = :accountId 
            AND (
                LOWER(
                    CASE 
                        WHEN INSTR(`from`, '@') > 0 THEN SUBSTR(`from`, 1, INSTR(`from`, '@') - 1)
                        ELSE `from`
                    END
                ) LIKE :query || '%'
                OR LOWER(`from`) LIKE '%' || :query || '%'
                OR LOWER(fromName) LIKE '%' || :query || '%'
            )
            AND LOWER(`from`) != LOWER(:ownEmail)
            UNION
            SELECT DISTINCT `to` as email, '' as name FROM emails 
            WHERE accountId = :accountId 
            AND (
                LOWER(
                    CASE 
                        WHEN INSTR(`to`, '@') > 0 THEN SUBSTR(`to`, 1, INSTR(`to`, '@') - 1)
                        ELSE `to`
                    END
                ) LIKE :query || '%'
                OR LOWER(`to`) LIKE '%' || :query || '%'
            )
            AND LOWER(`to`) != LOWER(:ownEmail)
        )
        GROUP BY LOWER(email)
        LIMIT :limit
    """)
    suspend fun searchEmailHistory(accountId: Long, query: String, ownEmail: String, limit: Int = 10): List<EmailHistoryResult>
    
    /**
     * Получает все уникальные пары email → имя отправителя для кэширования
     * Используется для инициализации кэша имён при старте приложения
     */
    @Query("""
        SELECT DISTINCT `from` as email, fromName as name FROM emails 
        WHERE fromName IS NOT NULL AND fromName != '' AND fromName NOT LIKE '%@%'
    """)
    suspend fun getAllSenderNames(): List<EmailHistoryResult>
    
    /**
     * Подсчитывает ВСЕ полученные письма за указанный период.
     * Используется для статистики "Сегодня" на главном экране.
     * Включает: Inbox (2), пользовательские папки (1, 12).
     * Исключает: Черновики (3), Корзину (4), Отправленные (5), Исходящие (6), Спам (11) —
     * это не "полученные" письма. Перемещённое в папку письмо остаётся в счётчике.
     */
    @Query("""
        SELECT COUNT(*) FROM emails e 
        INNER JOIN folders f ON e.folderId = f.id 
        WHERE e.accountId = :accountId 
        AND f.type IN (2, 1, 12) 
        AND e.dateReceived >= :startTime 
        AND e.dateReceived < :endTime
    """)
    suspend fun getEmailsCountForPeriod(accountId: Long, startTime: Long, endTime: Long): Int
    
    /**
     * Получает ВСЕ полученные письма за указанный период (кросс-папочный список).
     * Используется для экрана "Сегодня" — показывает письма из Inbox (2)
     * и пользовательских папок (1, 12), отсортированные по дате.
     */
    @Query("""
        SELECT e.* FROM emails e 
        INNER JOIN folders f ON e.folderId = f.id 
        WHERE e.accountId = :accountId 
        AND f.type IN (2, 1, 12) 
        AND e.dateReceived >= :startTime 
        AND e.dateReceived < :endTime
        ORDER BY e.dateReceived DESC
    """)
    fun getEmailsForPeriodAcrossFolders(accountId: Long, startTime: Long, endTime: Long): Flow<List<EmailEntity>>
    
    /**
     * Удаляет дубликаты писем (оставляет только одно письмо с уникальной комбинацией subject+from+dateReceived)
     * Сохраняет письмо с наименьшим id (первое добавленное)
     */
    @Query("""
        DELETE FROM emails WHERE id NOT IN (
            SELECT MIN(id) FROM emails 
            GROUP BY folderId, subject, `from`, dateReceived
        )
    """)
    suspend fun deleteDuplicateEmails()
    
    /**
     * Получает последние N писем из папки с пустым body
     * Используется для предзагрузки тел писем для офлайн-доступа
     */
    @Query("""
        SELECT * FROM emails 
        WHERE folderId = :folderId AND (body = '' OR body IS NULL)
        ORDER BY dateReceived DESC 
        LIMIT :limit
    """)
    suspend fun getEmailsWithEmptyBody(folderId: String, limit: Int): List<EmailEntity>
    
    /**
     * Репарация XML-экранированных данных в email-полях.
     * Исправляет &lt; → <, &gt; → >, &quot; → ", &apos; → ', &amp; → &
     * Вызывать ДВАЖДЫ для обработки двойного кодирования (&amp;lt; → &lt; → <).
     * Используется для ручного запуска из настроек или автоматически при обнаружении проблем.
     * @return количество обновлённых строк
     */
    @Query("""
        UPDATE emails SET 
            body = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(body, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
            subject = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(subject, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
            `from` = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(`from`, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
            `to` = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(`to`, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
            cc = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(cc, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
            preview = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(preview, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
            fromName = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(fromName, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&')
        WHERE body LIKE '%&lt;%' OR subject LIKE '%&lt;%' OR `from` LIKE '%&lt;%'
           OR `to` LIKE '%&lt;%' OR cc LIKE '%&lt;%' OR preview LIKE '%&lt;%' OR fromName LIKE '%&lt;%'
    """)
    suspend fun repairEncodedBodies(): Int
}

/**
 * Количество непрочитанных писем по аккаунту
 */
data class AccountUnreadCount(
    val accountId: Long,
    val unreadCount: Int
)

/**
 * Счётчики писем по папке (для batch-обновления)
 */
data class FolderEmailCounts(
    val folderId: String,
    val totalCount: Int,
    val unreadCount: Int
)

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
    
    @Query("SELECT * FROM attachments WHERE emailId IN (:emailIds)")
    suspend fun getAttachmentsForEmails(emailIds: List<String>): List<AttachmentEntity>
    
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
    
    /**
     * Удаляет дубликаты вложений, оставляя для каждой комбинации
     * (emailId, displayName, fileReference) запись с наибольшим id.
     * Если есть скачанная копия (downloaded=1) — сохраняет именно её.
     */
    @Query("""
        DELETE FROM attachments WHERE id NOT IN (
            SELECT MAX(id) FROM attachments 
            GROUP BY emailId, displayName, fileReference
        )
    """)
    suspend fun removeDuplicates()
    
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


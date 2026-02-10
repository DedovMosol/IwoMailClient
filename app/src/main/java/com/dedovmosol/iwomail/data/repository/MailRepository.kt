package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.onSuccessResult
import com.dedovmosol.iwomail.eas.getResultOrElse
import com.dedovmosol.iwomail.util.HtmlRegex
import kotlinx.coroutines.flow.Flow

// Предкомпилированные regex для производительности
private val CN_REGEX = Regex("CN=([^/><]+)", RegexOption.IGNORE_CASE)
private val NAME_BEFORE_BRACKET_REGEX = Regex("^\"?([^\"<]+)\"?\\s*<")
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Репозиторий для работы с почтой
 * Координирует работу сервисов и предоставляет единый API
 * После рефакторинга делегирует к:
 * - FolderSyncService - операции с папками
 * - EmailSyncService - синхронизация писем
 * - EmailOperationsService - операции с письмами
 */
class MailRepository(private val context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val accountRepo = RepositoryProvider.getAccountRepository(context)
    
    private val folderDao = database.folderDao()
    private val emailDao = database.emailDao()
    private val attachmentDao = database.attachmentDao()
    private val accountDao = database.accountDao()
    private val syncDao = database.syncDao()
    
    // Кэш email → displayName (LRU, потокобезопасный)
    // LruCache вместо ConcurrentHashMap: при достижении лимита вытесняет самые старые записи
    // вместо полного сброса кэша (устраняет "cache clear storm")
    private val emailNameCache = android.util.LruCache<String, String>(5000)
    private var cacheInitialized = false
    private var contactsDatabase: MailDatabase? = null
    private val maxEmailNameCacheSize = 5000
    
    private val emailExtractRegex = Regex("<([^>]+)>")
    
    private fun extractEmail(raw: String): String {
        val match = emailExtractRegex.find(raw)
        return (match?.groupValues?.get(1) ?: raw).lowercase().trim()
    }
    
    fun getCachedName(email: String): String? {
        val cleanEmail = extractEmail(email)
        return emailNameCache.get(cleanEmail)
    }
    
    suspend fun getNameFromContacts(email: String): String? {
        val db = contactsDatabase ?: return null
        return try {
            db.contactDao().getNameByEmail(email)
        } catch (_: Exception) { null }
    }
    
    fun cacheName(email: String, name: String) {
        if (email.isNotBlank() && name.isNotBlank() && !name.contains("@")) {
            val key = email.lowercase()
            // LruCache автоматически вытесняет старые записи при достижении лимита
            if (emailNameCache.get(key) == null) {
                emailNameCache.put(key, name)
            }
        }
    }
    
    suspend fun initCacheFromDb() {
        if (cacheInitialized) return
        cacheInitialized = true
        
        try {
            contactsDatabase = database
            
            val accounts = database.accountDao().getAllAccountsList()
            for (account in accounts) {
                val contacts = database.contactDao().getContactsByAccountList(account.id)
                for (contact in contacts) {
                    if (contact.email.isNotBlank() && contact.displayName.isNotBlank() && !contact.displayName.contains("@")) {
                        emailNameCache.put(contact.email.lowercase(), contact.displayName)
                    }
                }
            }
            
            val senderPairs = database.emailDao().getAllSenderNames()
            for ((email, name) in senderPairs) {
                if (email.isNotBlank() && name.isNotBlank() && !name.contains("@")) {
                    val key = email.lowercase()
                    if (emailNameCache.get(key) == null) {
                        emailNameCache.put(key, name)
                    }
                }
            }
        } catch (_: Exception) { }
    }
    
    // Сервисы (lazy для избежания циклических зависимостей)
    private val folderSyncService by lazy {
        FolderSyncService(context, folderDao, emailDao, accountDao, accountRepo)
    }
    
    private val emailSyncService by lazy {
        EmailSyncService(context, folderDao, emailDao, attachmentDao, accountDao, accountRepo)
    }
    
    private val emailOperationsService by lazy {
        EmailOperationsService(context, database, folderDao, emailDao, attachmentDao, accountRepo, emailSyncService)
    }
    
    // === Делегирование к FolderSyncService ===
    
    fun isFolderSyncing(folderId: String): Boolean = emailSyncService.isFolderSyncing(folderId)
    
    suspend fun syncFolders(accountId: Long): EasResult<Unit> = folderSyncService.syncFolders(accountId)
    
    suspend fun createFolder(accountId: Long, folderName: String): EasResult<Unit> = 
        folderSyncService.createFolder(accountId, folderName)
    
    suspend fun deleteFolder(accountId: Long, folderId: String): EasResult<Unit> = 
        folderSyncService.deleteFolder(accountId, folderId)
    
    suspend fun renameFolder(accountId: Long, folderId: String, newName: String): EasResult<Unit> = 
        folderSyncService.renameFolder(accountId, folderId, newName)
    
    // === Делегирование к EmailSyncService ===
    
    suspend fun syncEmails(accountId: Long, folderId: String, forceFullSync: Boolean = false): EasResult<Int> {
        repairXmlEntitiesIfNeeded()
        return emailSyncService.syncEmails(accountId, folderId, forceFullSync)
    }
    
    suspend fun syncSentFull(accountId: Long, folderId: String): EasResult<Int> = 
        emailSyncService.syncSentFull(accountId, folderId)
    
    // === Делегирование к EmailOperationsService ===
    
    suspend fun markAsRead(emailId: String, read: Boolean): EasResult<Boolean> = 
        emailOperationsService.markAsRead(emailId, read)
    
    suspend fun loadEmailBody(emailId: String): EasResult<String> = 
        emailOperationsService.loadEmailBody(emailId)
    
    suspend fun prefetchEmailBodies(accountId: Long, count: Int = 7) = 
        emailOperationsService.prefetchEmailBodies(accountId, count)
    
    suspend fun toggleFlag(emailId: String): EasResult<Boolean> = emailOperationsService.toggleFlag(emailId)
    
    suspend fun deleteEmail(emailId: String) = emailOperationsService.deleteEmail(emailId)
    
    suspend fun moveEmails(emailIds: List<String>, targetFolderId: String, updateOriginalFolder: Boolean = true): EasResult<Int> = 
        emailOperationsService.moveEmails(emailIds, targetFolderId, updateOriginalFolder)
    
    suspend fun moveToSpam(emailIds: List<String>): EasResult<Int> = 
        emailOperationsService.moveToSpam(emailIds)
    
    suspend fun moveToTrash(emailIds: List<String>): EasResult<Int> = 
        emailOperationsService.moveToTrash(emailIds) { accountId, serverId ->
            deleteDraft(accountId, serverId)
        }
    
    suspend fun restoreFromTrash(emailIds: List<String>): EasResult<Int> = 
        emailOperationsService.restoreFromTrash(emailIds)
    
    suspend fun deleteEmailsPermanently(emailIds: List<String>): EasResult<Int> = 
        emailOperationsService.deleteEmailsPermanently(emailIds)
    
    /**
     * Удаление черновиков с сервера (через специализированный deleteDraft).
     * Вызывается из UI списка черновиков.
     */
    suspend fun deleteDrafts(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        var deletedCount = 0
        for (emailId in emailIds) {
            val email = emailDao.getEmail(emailId) ?: continue
            val result = deleteDraft(email.accountId, email.serverId)
            if (result is EasResult.Success) {
                // deleteDraft уже удаляет из БД, чистит вложения и обновляет счётчик
                deletedCount++
            }
        }
        return EasResult.Success(deletedCount)
    }
    
    suspend fun deleteEmailsPermanentlyWithProgress(
        emailIds: List<String>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> = emailOperationsService.deleteEmailsPermanentlyWithProgress(emailIds, onProgress)
    
    suspend fun sendMdn(emailId: String): EasResult<Boolean> = 
        emailOperationsService.sendMdn(emailId)
    
    suspend fun markMdnSent(emailId: String) = emailOperationsService.markMdnSent(emailId)
    
    // === Простые геттеры (остаются в MailRepository) ===
    
    fun getFolders(accountId: Long): Flow<List<FolderEntity>> {
        return folderDao.getFoldersByAccount(accountId)
    }
    
    fun getEmails(folderId: String): Flow<List<EmailEntity>> {
        return emailDao.getEmailsByFolder(folderId)
    }
    
    fun getFlaggedEmails(accountId: Long): Flow<List<EmailEntity>> {
        return emailDao.getFlaggedEmails(accountId)
    }
    
    fun getFlaggedCount(accountId: Long): Flow<Int> {
        return emailDao.getFlaggedCountFlow(accountId)
    }
    
    suspend fun getTodayEmailsCount(accountId: Long): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        
        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
        return emailDao.getEmailsCountForPeriod(accountId, startOfDay, endOfDay)
    }
    
    /**
     * Возвращает Flow со списком писем за сегодня из Inbox + пользовательских папок.
     * Используется для кросс-папочного экрана "Сегодня".
     */
    fun getTodayEmailsAcrossFolders(accountId: Long): Flow<List<EmailEntity>> {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        
        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis
        
        return emailDao.getEmailsForPeriodAcrossFolders(accountId, startOfDay, endOfDay)
    }
    
    fun getEmail(emailId: String): Flow<EmailEntity?> {
        return emailDao.getEmailFlow(emailId)
    }
    
    fun getAttachments(emailId: String): Flow<List<AttachmentEntity>> {
        return attachmentDao.getAttachments(emailId)
    }
    
    suspend fun getEmailSync(emailId: String): EmailEntity? {
        return emailDao.getEmail(emailId)
    }
    
    suspend fun getAttachmentsSync(emailId: String): List<AttachmentEntity> {
        return attachmentDao.getAttachmentsList(emailId)
    }
    
    suspend fun getAttachmentsForEmails(emailIds: List<String>): List<AttachmentEntity> {
        return attachmentDao.getAttachmentsForEmails(emailIds)
    }
    
    suspend fun search(accountId: Long, query: String): List<EmailEntity> {
        // Экранируем SQL wildcards чтобы %, _ в запросе не интерпретировались
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return emailDao.search(accountId, escaped)
    }
    
    suspend fun getEmailsByIds(ids: List<String>): List<EmailEntity> {
        return emailDao.getEmailsByIds(ids)
    }
    
    suspend fun getUnreadCount(accountId: Long): Int {
        return emailDao.getUnreadCountByAccount(accountId)
    }
    
    /**
     * Обновляет счетчики для всех папок аккаунта из БД
     * Используется после syncFolders для отображения актуальных данных
     * Оптимизация: один SQL-запрос вместо 2*N запросов (N = кол-во папок)
     */
    suspend fun refreshFolderCounts(accountId: Long) {
        val countsMap = emailDao.getCountsByAccount(accountId).associateBy { it.folderId }
        val folders = folderDao.getFoldersByAccountList(accountId)
        folders.forEach { folder ->
            val counts = countsMap[folder.id]
            folderDao.updateCounts(folder.id, counts?.unreadCount ?: 0, counts?.totalCount ?: 0)
        }
    }
    
    suspend fun updateAttachmentPath(attachmentId: Long, path: String) {
        attachmentDao.updateLocalPath(attachmentId, path)
    }
    
    // === Draft операции (остаются в MailRepository из-за сложной логики) ===
    
    suspend fun saveDraft(
    accountId: Long,
    to: String,
    cc: String,
    subject: String,
    serverBody: String,
    localBody: String,
    fromEmail: String,
    fromName: String,
    hasAttachments: Boolean,
    attachmentFiles: List<com.dedovmosol.iwomail.eas.DraftAttachmentData> = emptyList()
): String? {
        val account = accountRepo.getAccount(accountId) ?: return null
        val draftsFolder = folderDao.getFolderByType(accountId, FolderType.DRAFTS)
            ?: return null
        
        if (AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            val easClient = accountRepo.createEasClient(accountId)
                ?: return null
            
          // На сервер отправляем serverBody (с cid: ссылками на inline-картинки).
          // Inline-картинки загружаются через CreateAttachment с ContentId.
          // Outlook десктопный рендерит HTML через Word — data: URL не работает,
          // только cid: + ContentId. Это стандарт, работает на Exchange 2007 SP1.
          val result = easClient.createDraft(
    to = to,
    cc = cc,
    bcc = "",
    subject = subject,
    body = serverBody,
    draftsFolderId = draftsFolder.serverId,
    attachments = attachmentFiles
)
if (result is EasResult.Success) {
    val ewsItemId = result.data
    
    if (ewsItemId.startsWith("local_draft_")) {
        val emailId = "${accountId}_${ewsItemId}"
        val email = EmailEntity(
            id = emailId,
            accountId = accountId,
            folderId = draftsFolder.id,
            serverId = ewsItemId,
            from = fromEmail,
            fromName = fromName,
            to = to,
            cc = cc,
            subject = subject,
            preview = stripHtml(localBody).take(150).replace("\n", " ").trim(),
            body = localBody,
            bodyType = 2,
            dateReceived = System.currentTimeMillis(),
            read = true,
            hasAttachments = hasAttachments
        )
        // АТОМАРНАЯ вставка: email + вложения в одной транзакции.
        // Защита от гонки: если PushService запустит sync между insert(email)
        // и insertAll(attachments), миграция EWS→EAS может удалить email
        // (CASCADE удалит вложения) → FK violation при вставке вложений.
        // Room transaction гарантирует: другие операции видят либо ВСЁ, либо НИЧЕГО.
        val attEntities = prepareDraftAttachmentFiles(emailId, attachmentFiles)
        database.withTransaction {
            emailDao.insert(email)
            if (attEntities.isNotEmpty()) {
                attachmentDao.insertAll(attEntities)
            }
        }
        updateDraftsFolderCount(accountId, draftsFolder.id)
        return ewsItemId
    }
    
    // Сразу вставляем полноценную запись в БД.
    // НЕ используем temp + sync, т.к. EWS возвращает длинный ItemId,
    // а EAS sync использует короткий ServerId (5:8) — они не совпадают,
    // и temp-запись "зависает" или удаляется при следующем фоновом sync.
    // Формат emailId = "${accountId}_$ewsItemId" — совпадает с deleteDraft().
    val emailId = "${accountId}_$ewsItemId"
    val email = EmailEntity(
        id = emailId,
        accountId = accountId,
        folderId = draftsFolder.id,
        serverId = ewsItemId,
        from = fromEmail,
        fromName = fromName,
        to = to,
        cc = cc,
        subject = subject,
        preview = stripHtml(localBody).take(150).replace("\n", " ").trim(),
        body = localBody,
        bodyType = 2,
        dateReceived = System.currentTimeMillis(),
        read = true,
        hasAttachments = hasAttachments
    )
    // АТОМАРНАЯ вставка: email + вложения в одной транзакции.
    // См. комментарий выше про гонку PushService/sync.
    val attEntities = prepareDraftAttachmentFiles(emailId, attachmentFiles)
    database.withTransaction {
        emailDao.insert(email)
        if (attEntities.isNotEmpty()) {
            attachmentDao.insertAll(attEntities)
        }
    }
    updateDraftsFolderCount(accountId, draftsFolder.id)
    
    // НЕ запускаем sync для reconciliation:
    // EWS ItemId и EAS ServerId — это РАЗНЫЕ системы идентификаторов (Microsoft docs),
    // конвертировать их невозможно. Reconciliation через subject+dateReceived ненадёжна
    // (зависит от совпадения серверного и локального времени ±60 сек).
    // EWS-запись остаётся в БД как постоянная — черновик виден сразу.
    // При следующем EAS sync — миграция EWS→EAS происходит в syncEmailsEas
    // (body и вложения переносятся автоматически).
    
    return ewsItemId
}
            return null
        }
        
        return null
    }
    
    /**
     * Подготовка вложений черновика: запись файлов на диск + создание AttachmentEntity.
     * НЕ выполняет DB-операций — возвращает список сущностей для вставки в транзакции.
     * Файловый I/O выполняется ДО транзакции, чтобы не держать DB-lock.
     */
    private fun prepareDraftAttachmentFiles(
        emailId: String,
        attachments: List<com.dedovmosol.iwomail.eas.DraftAttachmentData>
    ): List<AttachmentEntity> {
        if (attachments.isEmpty()) return emptyList()
        val attachmentsDir = java.io.File(context.filesDir, "draft_attachments")
        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
        
        return attachments.mapNotNull { att ->
            try {
                val safeFileName = att.name.replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ]"), "_")
                val file = java.io.File(attachmentsDir, "${System.currentTimeMillis()}_$safeFileName")
                file.writeBytes(att.data)
                AttachmentEntity(
                    emailId = emailId,
                    fileReference = "",
                    displayName = att.name,
                    contentType = att.mimeType,
                    estimatedSize = att.data.size.toLong(),
                    isInline = att.isInline,
                    contentId = att.contentId,
                    localPath = file.absolutePath,
                    downloaded = true
                )
            } catch (e: Exception) {
                android.util.Log.w("MailRepository", "Failed to save draft attachment ${att.name}: ${e.message}")
                null
            }
        }
    }

    /**
     * Удаляет локальные файлы вложений черновика из draft_attachments/
     */
    private suspend fun cleanupDraftAttachmentFiles(emailId: String) {
        try {
            val atts = attachmentDao.getAttachmentsList(emailId)
            for (att in atts) {
                val path = att.localPath
                if (path != null) {
                    try { java.io.File(path).delete() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

   suspend fun updateDraft(
    accountId: Long,
    serverId: String,
    to: String,
    cc: String,
    subject: String,
    body: String,
    fromEmail: String,
    fromName: String
): EasResult<String> {
        val account = accountRepo.getAccount(accountId)
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Не поддерживается")
        }
        
        var draftsFolder = folderDao.getFolderByType(accountId, FolderType.DRAFTS)
            ?: return EasResult.Error("Папка черновиков не найдена")
        
        val easClient = accountRepo.getEasClientOrError<String>(accountId) { return it }
        
        easClient.detectEasVersion()
        
        val oldEmailId = "${accountId}_${serverId}"
        
        if (serverId.startsWith("local_draft_")) {
            // Локальный черновик - просто обновляем локально.
            // Используем updateDraftFields() для защиты вложений от CASCADE.
            emailDao.updateDraftFields(
                id = oldEmailId,
                to = to,
                cc = cc,
                subject = subject,
                body = body,
                preview = stripHtml(body).take(150).replace("\n", " ").trim(),
                dateReceived = System.currentTimeMillis(),
                fromEmail = fromEmail,
                fromName = fromName
            )
            updateDraftsFolderCount(accountId, draftsFolder.id)
            
            return EasResult.Success(serverId)
        }
        
        // КРИТИЧНО: ActiveSync возвращает КОРОТКИЙ serverId (22:2), а EWS требует ПОЛНЫЙ ItemId!
        // Если serverId короткий - получаем ПОЛНЫЙ ItemId через поиск по СТАРОМУ Subject из БД
        var actualServerId = serverId
        if (serverId.contains(":") && !serverId.contains("=")) {
            // Короткий serverId (формат "22:2") - получаем полный через поиск по Subject
            // ВАЖНО: используем СТАРЫЙ subject из БД, т.к. новый subject ещё не сохранён на сервере
            val oldEmail = emailDao.getEmail(oldEmailId)
            val searchSubject = oldEmail?.subject ?: subject
            
            val getItemResult = easClient.getItemIdBySubject(searchSubject)
            getItemResult.onSuccessResult { itemId ->
                actualServerId = itemId
            }
            // Если не удалось получить - пробуем с коротким (может сработать)
        }
        
        // Серверный черновик - обновляем через EWS UpdateItem
        val updateResult = easClient.updateDraft(actualServerId, to, cc, "", subject, body)

        if (updateResult is EasResult.Success && updateResult.data) {
            // UpdateItem сработал - обновляем локальную БД.
            // КРИТИЧНО: используем updateDraftFields() вместо insert() (REPLACE).
            // insert() с REPLACE = DELETE + INSERT. DELETE триггерит CASCADE foreign key,
            // что УНИЧТОЖАЕТ все записи AttachmentEntity для этого email.
            // updateDraftFields() делает UPDATE IN PLACE — вложения не затрагиваются.
            emailDao.updateDraftFields(
                id = oldEmailId,
                to = to,
                cc = cc,
                subject = subject,
                body = body,
                preview = stripHtml(body).take(150).replace("\n", " ").trim(),
                dateReceived = System.currentTimeMillis(),
                fromEmail = fromEmail,
                fromName = fromName
            )
            updateDraftsFolderCount(accountId, draftsFolder.id)
            
            // НЕ вызываем syncEmails здесь. Причины:
            // 1. syncEmails может гонять с фоновым PushService/SyncWorker (оба используют
            //    тот же SyncKey) → INVALID_SYNCKEY → полный resync → удаление всех записей
            //    из папки → черновик пропадает из UI.
            // 2. Сервер может отправить Delete+Add вместо Change — старая запись удалена,
            //    новая с другим emailId, но с потерей to/cc (PR_DISPLAY_TO ≠ email).
            // 3. Полный ItemId (для последующего удаления) уже получен через
            //    getItemIdBySubject() выше (строка 524). UpdateItem НЕ меняет ItemId
            //    (только ChangeKey). Следующий фоновый sync обработает изменения безопасно.
            return EasResult.Success(serverId)
        } else {
            // UpdateItem не сработал - обновляем ТОЛЬКО локально.
            // Используем updateDraftFields() для защиты вложений от CASCADE.
            emailDao.updateDraftFields(
                id = oldEmailId,
                to = to,
                cc = cc,
                subject = subject,
                body = body,
                preview = stripHtml(body).take(150).replace("\n", " ").trim(),
                dateReceived = System.currentTimeMillis(),
                fromEmail = fromEmail,
                fromName = fromName
            )
            updateDraftsFolderCount(accountId, draftsFolder.id)
            
            return EasResult.Success(serverId)
        }
}
    /**
     * Удаляет черновик с сервера и из БД.
     * @param excludeEwsItemId — EWS ItemId нового черновика (созданного saveDraft),
     *   который НЕ должен быть удалён. Защита от случайного удаления при поиске по subject.
     */
    suspend fun deleteDraft(accountId: Long, serverId: String, excludeEwsItemId: String? = null): EasResult<Boolean> {
        val account = accountRepo.getAccount(accountId)
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Не поддерживается")
        }
        
        val draftsFolder = folderDao.getFolderByType(accountId, FolderType.DRAFTS)
            ?: return EasResult.Error("Папка черновиков не найдена")
        
        val emailId = "${accountId}_${serverId}"
        
        if (serverId.startsWith("local_draft_")) {
            // Удаляем вложения (записи + файлы)
            cleanupDraftAttachmentFiles(emailId)
            attachmentDao.deleteByEmail(emailId)
            emailDao.delete(emailId)
            updateDraftsFolderCount(accountId, draftsFolder.id)
            return EasResult.Success(true)
        }
        
        val easClient = accountRepo.getEasClientOrError<Boolean>(accountId) { return it }
        
        easClient.detectEasVersion()
        
        // Перечитываем письмо из БД для получения актуального serverId.
        // После фонового sync serverId мог измениться (SHORT → FULL).
        val freshEmail = emailDao.getEmail(emailId)
        val actualServerId = freshEmail?.serverId ?: serverId
        
        // Вспомогательная функция: полная локальная очистка черновика
        // (вложения на диске, записи вложений в БД, письмо, счётчик папки, защита от "воскрешения")
        suspend fun finalizeDraftDeletion() {
            EmailSyncService.registerDeletedEmail(emailId, context)
            cleanupDraftAttachmentFiles(emailId)
            attachmentDao.deleteByEmail(emailId)
            emailDao.delete(emailId)
            updateDraftsFolderCount(accountId, draftsFolder.id)
        }
        
        // КРИТИЧНО: Exchange 2007 игнорирует DeletesAsMoves=0 в EAS Sync Delete.
        // Черновик перемещается в "Удалённые" вместо безвозвратного удаления,
        // вызывая ошибки IMAP4 при просмотре через Outlook.
        // Решение: для Exchange 2007 используем EWS HardDelete как ОСНОВНОЙ метод.
        if (easClient.isExchange2007()) {
            // Шаг 1: Если serverId — полный EWS ItemId (>50 символов без ":"), удаляем напрямую
            if (actualServerId.length >= 50 && !actualServerId.contains(":")) {
                val ewsResult = easClient.deleteEmailPermanentlyViaEWS(actualServerId)
                if (ewsResult is EasResult.Success) {
                    finalizeDraftDeletion()
                    return EasResult.Success(true)
                }
            }
            
            // Шаг 2: Если serverId — EAS формат (5:8), ищем по индексу.
            // КРИТИЧНО: проверяем что найденный ItemId НЕ равен excludeEwsItemId
            // (новый черновик, который был только что создан через saveDraft).
            if (actualServerId.contains(":")) {
                val draftItemId = easClient.findDraftItemIdByIndex(actualServerId)
                if (draftItemId != null && draftItemId != excludeEwsItemId) {
                    val ewsDeleteResult = easClient.deleteEmailPermanentlyViaEWS(draftItemId)
                    if (ewsDeleteResult is EasResult.Success) {
                        finalizeDraftDeletion()
                        return EasResult.Success(true)
                    }
                }
            }
            
            // Шаг 3: Fallback — ищем по теме в папке Drafts.
            // Два режима:
            // A) Пересохранение (excludeEwsItemId != null): на сервере ДВА+ черновика с одной темой.
            //    Получаем ВСЕ ItemId, исключаем новый, удаляем ВСЕ оставшиеся (старые + дубли).
            // B) Обычное удаление (excludeEwsItemId == null): удаляем только ОДИН (первый найденный).
            val searchSubject = freshEmail?.subject ?: ""
            if (searchSubject.isNotBlank()) {
                if (excludeEwsItemId != null) {
                    // Режим A: пересохранение — удаляем ВСЕ кроме нового
                    val allIdsResult = easClient.getAllItemIdsBySubject(searchSubject)
                    if (allIdsResult is EasResult.Success && allIdsResult.data.isNotEmpty()) {
                        val idsToDelete = allIdsResult.data.filter { it != excludeEwsItemId }
                        var anyDeleted = false
                        for (id in idsToDelete) {
                            val ewsDeleteResult = easClient.deleteEmailPermanentlyViaEWS(id)
                            if (ewsDeleteResult is EasResult.Success) {
                                anyDeleted = true
                            }
                        }
                        if (anyDeleted) {
                            finalizeDraftDeletion()
                            return EasResult.Success(true)
                        }
                    }
                } else {
                    // Режим B: обычное удаление — удаляем первый найденный
                    val fullIdResult = easClient.getItemIdBySubject(searchSubject)
                    if (fullIdResult is EasResult.Success) {
                        val ewsDeleteResult = easClient.deleteEmailPermanentlyViaEWS(fullIdResult.data)
                        if (ewsDeleteResult is EasResult.Success) {
                            finalizeDraftDeletion()
                            return EasResult.Success(true)
                        }
                    }
                }
            }
            
            // Шаг 4: Последний fallback — EAS Sync Delete.
            // На Exchange 2007 может переместить в Deleted Items (не HardDelete),
            // но хотя бы уберёт из Drafts.
            // КРИТИЧНО: НЕ запускаем sync-loop для обновления syncKey!
            // Sync-loop потребляет серверные изменения (включая Add нового черновика)
            // без их обработки → syncKey продвигается → новый черновик теряется
            // (его Add никогда не будет повторён сервером).
            // Используем текущий syncKey. Если он устарел — delete может вернуть ошибку,
            // но finalizeDraftDeletion() всё равно очистит локально.
            val syncKey = folderDao.getFolder(draftsFolder.id)?.syncKey ?: draftsFolder.syncKey
            if (syncKey != "0") {
                easClient.deleteEmailPermanently(draftsFolder.serverId, actualServerId, syncKey)
            }
            // Независимо от результата — удаляем локально
            finalizeDraftDeletion()
            return EasResult.Success(true)
        }
        
        // === Exchange 2010+ стандартный путь через EAS Sync Delete ===
        // КРИТИЧНО: НЕ запускаем sync-loop для обновления syncKey!
        // Sync-loop потребляет серверные изменения (Add нового черновика)
        // без их обработки → syncKey продвигается → новый черновик теряется.
        // Используем текущий syncKey. При INVALID_SYNCKEY — сбрасываем на "0"
        // через init-sync (без данных), затем повторяем.
        var syncKey = folderDao.getFolder(draftsFolder.id)?.syncKey ?: draftsFolder.syncKey
        
        var deleteResult = if (syncKey != "0") {
            easClient.deleteEmailPermanently(draftsFolder.serverId, actualServerId, syncKey)
        } else {
            EasResult.Error("SyncKey is 0")
        }
        
        if (deleteResult is EasResult.Error && (deleteResult.message.contains("INVALID_SYNCKEY") || syncKey == "0")) {
            // SyncKey устарел — получаем новый через init-sync.
            // КРИТИЧНО: сохраняем ТОЛЬКО начальный SyncKey (initSyncKey) в БД.
            // Продвинутый SyncKey (от refreshResult) используем ТОЛЬКО для Delete-операции,
            // но НЕ персистим. Причина: refreshResult.sync() потребляет pending Adds
            // (включая Add нового черновика, только что созданного saveDraft()),
            // и если сохранить продвинутый ключ, эти Adds не будут повторно доставлены
            // сервером → новый черновик навсегда потерян для синхронизации.
            // С initSyncKey в БД следующий фоновый syncEmailsEas выполнит полную
            // синхронизацию и корректно обработает все Adds.
            val initResult = easClient.sync(draftsFolder.serverId, "0")
            if (initResult is EasResult.Success && initResult.data.syncKey != "0") {
                val initSyncKey = initResult.data.syncKey
                // Сохраняем ТОЛЬКО начальный ключ — безопасная точка для следующего sync
                folderDao.updateSyncKey(draftsFolder.id, initSyncKey)
                // Один проход для получения актуального ключа для Delete-операции.
                // Данные НЕ обрабатываем, ключ НЕ сохраняем в БД.
                val refreshResult = easClient.sync(draftsFolder.serverId, initSyncKey, windowSize = 50)
                val deleteKey = if (refreshResult is EasResult.Success) {
                    refreshResult.data.syncKey
                } else {
                    initSyncKey
                }
                deleteResult = easClient.deleteEmailPermanently(draftsFolder.serverId, actualServerId, deleteKey)
            }
        }
        
        // EWS fallback для Exchange 2010+ при ошибке EAS
        if (deleteResult is EasResult.Error) {
            if (actualServerId.contains(":") && !actualServerId.contains("=")) {
                val searchSubject = freshEmail?.subject ?: ""
                if (searchSubject.isNotBlank()) {
                    if (excludeEwsItemId != null) {
                        // Пересохранение: удаляем ВСЕ старые дубли кроме нового
                        val allIdsResult = easClient.getAllItemIdsBySubject(searchSubject)
                        if (allIdsResult is EasResult.Success && allIdsResult.data.isNotEmpty()) {
                            val idsToDelete = allIdsResult.data.filter { it != excludeEwsItemId }
                            var anyDeleted = false
                            for (id in idsToDelete) {
                                val ewsDeleteResult = easClient.deleteEmailPermanentlyViaEWS(id)
                                if (ewsDeleteResult is EasResult.Success) {
                                    anyDeleted = true
                                }
                            }
                            if (anyDeleted) {
                                finalizeDraftDeletion()
                                return EasResult.Success(true)
                            }
                        }
                    } else {
                        // Обычное удаление: первый найденный
                        val fullIdResult = easClient.getItemIdBySubject(searchSubject)
                        if (fullIdResult is EasResult.Success) {
                            val ewsDeleteResult = easClient.deleteEmailPermanentlyViaEWS(fullIdResult.data)
                            if (ewsDeleteResult is EasResult.Success) {
                                finalizeDraftDeletion()
                                return EasResult.Success(true)
                            }
                        }
                    }
                }
            }
            
            // Пробуем EWS с текущим serverId (для длинных EWS ItemIds)
            val ewsResult = easClient.deleteEmailPermanentlyViaEWS(actualServerId)
            if (ewsResult is EasResult.Success) {
                finalizeDraftDeletion()
                return EasResult.Success(true)
            }
        }
        
        return when (deleteResult) {
            is EasResult.Success -> {
                finalizeDraftDeletion()
                EasResult.Success(true)
            }
            is EasResult.Error -> deleteResult
        }
    }

    private suspend fun updateDraftsFolderCount(accountId: Long, folderId: String) {
        val totalCount = emailDao.getCountByFolder(folderId)
        val unreadCount = emailDao.getUnreadCount(folderId)
        folderDao.updateCounts(folderId, unreadCount, totalCount)
    }
    
    // === Утилитные методы ===
    
    private fun extractName(emailField: String): String {
        if (emailField.isBlank()) return ""
        
        val cnMatch = CN_REGEX.find(emailField)
        if (cnMatch != null) {
            return cnMatch.groupValues[1].trim()
        }
        
        val nameMatch = NAME_BEFORE_BRACKET_REGEX.find(emailField)
        if (nameMatch != null) {
            return nameMatch.groupValues[1].trim()
        }
        
        if (emailField.contains("@") && !emailField.contains("<")) {
            return ""
        }
        
        return emailField.trim()
    }
    
    // === Репарация XML-экранированных данных ===
    
    /**
     * Проверяет флаг и запускает репарацию один раз.
     * Вызывается автоматически при syncEmails.
     * SharedPreferences-флаг гарантирует однократное выполнение.
     */
    private suspend fun repairXmlEntitiesIfNeeded() {
        val prefs = context.getSharedPreferences("db_repair", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("xml_entities_v1", false)) return
        repairXmlEntities()
        prefs.edit().putBoolean("xml_entities_v1", true).apply()
    }
    
    /**
     * Починка XML-экранированных данных во всех таблицах БД.
     * Можно вызвать вручную из настроек (кнопка "Починить БД").
     *
     * Проблема: parseEmail() и fetchEmailBodyViaEws() сохраняли HTML с XML-экранированием
     * (&lt;div&gt; вместо <div>). Переустановка поверх не помогала — данные уже в Room.
     *
     * Два прохода REPLACE: обработка двойного кодирования (&amp;lt; → &lt; → <).
     * Повторный запуск безопасен: REPLACE на чистых данных — no-op.
     */
    suspend fun repairXmlEntities() {
        try {
            val db = database.openHelper.writableDatabase
            
            val repairEmailsSql = """
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
            """.trimIndent()
            db.execSQL(repairEmailsSql) // Проход 1
            db.execSQL(repairEmailsSql) // Проход 2 (двойное кодирование)
            
            val repairNotesSql = """
                UPDATE notes SET 
                    subject = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(subject, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
                    body = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(body, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&')
                WHERE body LIKE '%&lt;%' OR subject LIKE '%&lt;%'
            """.trimIndent()
            db.execSQL(repairNotesSql)
            db.execSQL(repairNotesSql)
            
            val repairCalendarSql = """
                UPDATE calendar_events SET 
                    subject = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(subject, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
                    body = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(body, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
                    location = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(location, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
                    organizer = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(organizer, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&')
                WHERE body LIKE '%&lt;%' OR subject LIKE '%&lt;%' OR location LIKE '%&lt;%' OR organizer LIKE '%&lt;%'
            """.trimIndent()
            db.execSQL(repairCalendarSql)
            db.execSQL(repairCalendarSql)
            
            val repairTasksSql = """
                UPDATE tasks SET 
                    subject = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(subject, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&'),
                    body = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(body, '&lt;', '<'), '&gt;', '>'), '&quot;', '"'), '&apos;', ''''), '&amp;', '&')
                WHERE body LIKE '%&lt;%' OR subject LIKE '%&lt;%'
            """.trimIndent()
            db.execSQL(repairTasksSql)
            db.execSQL(repairTasksSql)
            
            android.util.Log.i("MailRepository", "XML entity repair completed for emails, notes, calendar, tasks")
        } catch (e: Exception) {
            android.util.Log.e("MailRepository", "XML entity repair failed", e)
        }
    }
    
    private fun extractEmailAddress(field: String): String {
        val match = emailExtractRegex.find(field)
        return match?.groupValues?.get(1) ?: field
    }
    
    private fun stripHtml(html: String): String {
        return html
            .replace(HtmlRegex.STYLE, "")
            .replace(HtmlRegex.SCRIPT, "")
            .replace(HtmlRegex.COMMENT, "")
            .replace(HtmlRegex.BR, " ")
            .replace(HtmlRegex.TAG, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(HtmlRegex.HTML_ENTITY) { match ->
                try {
                    val code = match.value.drop(2).dropLast(1).toInt()
                    code.toChar().toString()
                } catch (e: Exception) {
                    ""
                }
            }
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }
}

package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.onSuccessResult
import com.dedovmosol.iwomail.util.stripHtml
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock

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
    
    companion object {
        private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ]")
    }
    
    private val folderDao = database.folderDao()
    private val emailDao = database.emailDao()
    private val attachmentDao = database.attachmentDao()
    private val accountDao = database.accountDao()
    private val syncDao = database.syncDao()
    
    // Кэш email → displayName (LRU, потокобезопасный)
    // LruCache вместо ConcurrentHashMap: при достижении лимита вытесняет самые старые записи
    // вместо полного сброса кэша (устраняет "cache clear storm")
    private val emailNameCache = android.util.LruCache<String, String>(5000)
    @Volatile private var cacheInitialized = false
    
    private val emailExtractRegex = Regex("<([^>]+)>")
    
    private fun extractEmail(raw: String): String {
        val match = emailExtractRegex.find(raw)
        return (match?.groupValues?.get(1) ?: raw).lowercase().trim()
    }
    
    fun getCachedName(email: String): String? {
        val cleanEmail = extractEmail(email)
        return emailNameCache.get(cleanEmail)
    }
    
    suspend fun getNameFromContacts(accountId: Long, email: String): String? {
        if (!cacheInitialized) return null
        val db = database
        return try {
            db.contactDao().getNameByEmail(accountId, email)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }
    
    fun cacheName(email: String, name: String) {
        if (email.isNotBlank() && name.isNotBlank() && !name.contains("@")) {
            val key = extractEmail(email)
            if (emailNameCache.get(key) == null) {
                emailNameCache.put(key, name)
            }
        }
    }
    
    private val cacheInitMutex = kotlinx.coroutines.sync.Mutex()
    
    suspend fun initCacheFromDb() {
        if (cacheInitialized) return
        cacheInitMutex.withLock {
            if (cacheInitialized) return
            try {
                val accounts = database.accountDao().getAllAccountsList()
                for (account in accounts) {
                    val pairs = database.contactDao().getContactEmailNames(account.id)
                    for ((email, name) in pairs) {
                        emailNameCache.put(extractEmail(email), name)
                    }
                }
                
                for (account in accounts) {
                    val senderPairs = database.emailDao().getAllSenderNames(account.id)
                    for ((rawEmail, name) in senderPairs) {
                        if (rawEmail.isNotBlank() && name.isNotBlank() && !name.contains("@")) {
                            val key = extractEmail(rawEmail)
                            if (emailNameCache.get(key) == null) {
                                emailNameCache.put(key, name)
                            }
                        }
                    }
                }
                cacheInitialized = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }
    
    // Сервисы (lazy для избежания циклических зависимостей)
    private val folderSyncService by lazy {
        FolderSyncService(context, folderDao, emailDao, accountDao, accountRepo, database)
    }
    
    private val emailSyncService by lazy {
        EmailSyncService(
            context, folderDao, emailDao, attachmentDao, accountDao, accountRepo, database,
            // Удаление прежней версии заменённого черновика — тем же путём, что и с экрана письма (DRY)
            deleteServerDraft = { accountId, serverId, excludeServerId ->
                deleteDraft(accountId, serverId, excludeServerId, requireServerDeletion = true) is EasResult.Success
            }
        ) { email, name ->
            cacheName(email, name)
        }
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
    
    suspend fun markAsReadBatch(emailIds: List<String>, read: Boolean): EasResult<Boolean> =
        emailOperationsService.markAsReadBatch(emailIds, read)
    
    suspend fun loadEmailBody(emailId: String, forceReload: Boolean = false): EasResult<String> = 
        emailOperationsService.loadEmailBody(emailId, forceReload)

    /** Серверный черновик перед редактированием: свежее тело и скрытые копии — [EmailOperationsService.refreshServerDraft]. */
    suspend fun refreshServerDraft(emailId: String): EmailOperationsService.DraftRefresh? =
        emailOperationsService.refreshServerDraft(emailId)
    
    suspend fun refreshAttachmentMetadata(emailId: String) = 
        emailOperationsService.refreshAttachmentMetadata(emailId)
    
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
        return emailDao.getEmailSummariesByFolder(folderId)
    }
    
    fun getFlaggedEmails(accountId: Long): Flow<List<EmailEntity>> {
        return emailDao.getFlaggedEmailSummaries(accountId)
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

    /**
     * Записывает тело письма в БД (обёртка над DAO, единая точка записи — инвариант проекта).
     * Используется ComposeViewModel при открытии черновика: после резолвинга
     * `cid:` → `data:` тело самодостаточно и не требует повторного сетевого
     * запроса при каждом открытии (эталонное поведение старого ComposeScreen).
     */
    suspend fun updateEmailBody(emailId: String, body: String) {
        emailDao.updateBody(emailId, body)
    }

    /**
     * История получателей для автодополнения (сортировка по частоте использования).
     * Обёртка над DAO для presentation-слоя (Этап 3, CS-16).
     */
    suspend fun searchEmailHistory(accountId: Long, query: String, ownEmail: String, limit: Int = 10): List<EmailHistoryResult> {
        if (query.isBlank()) return emptyList()
        return emailDao.searchEmailHistory(accountId, query, ownEmail, limit)
    }

    /**
     * Атомарно воссоздаёт запись черновика (email + вложения) в одной транзакции.
     * Используется как защита в [saveDraft] delete+create-потоке: если фоновый
     * sync удалил новую запись между её созданием и верификацией — воссоздаём.
     * Единая точка записи (инвариант проекта): DAO-вызовы только из репозитория.
     */
    suspend fun insertDraftRecord(email: EmailEntity, attachments: List<AttachmentEntity>) {
        database.withTransaction {
            emailDao.insert(email)
            if (attachments.isNotEmpty()) {
                attachmentDao.insertAll(attachments)
            }
        }
    }
    
    /**
     * Синхронное получение папки по локальному ID (паттерн [getEmailSync]).
     * Используется в presentation layer (ComposeViewModel) для определения коллекции вложений.
     */
    suspend fun getFolderSync(folderId: String): FolderEntity? {
        return folderDao.getFolder(folderId)
    }
    
    suspend fun getAttachmentsSync(emailId: String): List<AttachmentEntity> {
        return attachmentDao.getAttachmentsList(emailId)
    }
    
    suspend fun getAttachmentsForEmails(emailIds: List<String>): List<AttachmentEntity> {
        return emailIds.chunked(500).flatMap { attachmentDao.getAttachmentsForEmails(it) }
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
        return ids.chunked(500).flatMap { emailDao.getEmailsByIds(it) }
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
    // null — скрытые копии не известны (только для черновика на устройстве: их возьмут с заменяемой версии)
    bcc: String? = "",
    subject: String,
    serverBody: String,
    localBody: String,
    fromEmail: String,
    fromName: String,
    hasAttachments: Boolean,
    attachmentFiles: List<com.dedovmosol.iwomail.eas.DraftAttachmentData> = emptyList(),
    importance: Int = 1,
    // Только на устройстве, без обращения к серверу (известно, что связи нет)
    forceLocal: Boolean = false
): String? {
        val account = accountRepo.getAccount(accountId) ?: return null
        val draftsFolder = folderDao.getFolderByType(accountId, FolderType.DRAFTS)
            ?: return null
        // Локально — тело с data:-картинками (открывается без сети), но не крупнее окна курсора БД:
        // иначе строку не прочитать. Крупное — с cid:, картинки — файлы вложений (attachmentFiles).
        val storedBody = if (localBody.length <= DraftUpload.MAX_LOCAL_BODY_CHARS) localBody else serverBody
        
        if (AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            // Режим черновиков: LOCAL — сохраняем только в БД, SERVER — на сервер Exchange
            val draftMode = try {
                DraftMode.valueOf(account.draftMode)
            } catch (_: Exception) {
                DraftMode.SERVER
            }
            
            val result: EasResult<String> = if (draftMode == DraftMode.LOCAL || forceLocal) {
                // Локальный режим (или нет связи) — сразу local_draft_ ID без обращения к серверу
                EasResult.Success(com.dedovmosol.iwomail.eas.DraftIds.newDeviceOnly())
            } else {
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return null
                
                // На сервер отправляем serverBody (с cid: ссылками на inline-картинки).
                // Inline-картинки загружаются через CreateAttachment с ContentId.
                // Outlook десктопный рендерит HTML через Word — data: URL не работает,
                // только cid: + ContentId. Это стандарт, работает на Exchange 2007 SP1.
                easClient.createDraft(
                    to = to,
                    cc = cc,
                    bcc = bcc.orEmpty(),
                    subject = subject,
                    body = serverBody,
                    attachments = attachmentFiles,
                    importance = importance
                )
            }
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
            body = storedBody,
            bodyType = 2,
            dateReceived = System.currentTimeMillis(),
            read = true,
            importance = importance,
            hasAttachments = hasAttachments,
            bcc = bcc
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
        updateDraftsFolderCount(draftsFolder.id)
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
        body = storedBody,
        bodyType = 2,
        dateReceived = System.currentTimeMillis(),
        read = true,
        importance = importance,
        hasAttachments = hasAttachments,
        bcc = bcc
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
    updateDraftsFolderCount(draftsFolder.id)
    
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
                val safeFileName = att.name.replace(SAFE_FILENAME_REGEX, "_")
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

    // === Помощники DraftSaver (domain): замена черновика, правка без связи ===

    /** Отметить, что черновик [emailId] заменяет серверный [serverId] (удалить его после загрузки). */
    suspend fun markDraftReplacing(emailId: String, serverId: String?) {
        emailDao.updateReplacesServerId(emailId, serverId)
    }

    /** Перенести записи вложений (например, ещё не скачанных) на черновик [toEmailId]. */
    suspend fun carryDraftAttachments(rows: List<AttachmentEntity>, toEmailId: String) {
        if (rows.isEmpty()) return
        val target = emailDao.getEmail(toEmailId) ?: return
        attachmentDao.insertAll(rows.map { it.copy(id = 0, emailId = target.id) })
        if (!target.hasAttachments) emailDao.updateHasAttachments(target.id, true)
    }

    /** Убрать черновик только на устройстве (сервер не трогается): запись, вложения и их файлы. */
    suspend fun removeDraftLocally(emailId: String) {
        val email = emailDao.getEmail(emailId) ?: return
        cleanupDraftAttachmentFiles(emailId)
        database.withTransaction {
            attachmentDao.deleteByEmail(emailId)
            emailDao.delete(emailId)
        }
        updateDraftsFolderCount(email.folderId)
    }

    /** Убрать записи вложений черновика (сначала записи, потом файлы: без записей на пропавшие файлы). */
    private suspend fun deleteDraftAttachmentRows(emailId: String, rows: List<AttachmentEntity>) {
        if (rows.isEmpty()) return
        attachmentDao.deleteByIds(rows.map { it.id })
        if (attachmentDao.getAttachmentsList(emailId).isEmpty()) emailDao.updateHasAttachments(emailId, false)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (row in rows) {
                val path = row.localPath ?: continue
                try { java.io.File(path).delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Байты вложения черновика: локальный файл, иначе (при [allowNetwork]) — с сервера (EAS ItemOperations или
     * EWS GetAttachment).
     */
    suspend fun draftAttachmentBytes(accountId: Long, attachment: AttachmentEntity, allowNetwork: Boolean = true): ByteArray? {
        attachment.localPath?.let { path ->
            val file = java.io.File(path)
            if (file.exists()) return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.readBytes() }
        }
        if (!allowNetwork || attachment.fileReference.isBlank()) return null
        val client = accountRepo.createEasClient(accountId) ?: return null
        return (client.downloadDraftAttachment(attachment.fileReference) as? EasResult.Success)?.data
    }

    /** Восстановить запись черновика с вложениями (файлы — заново), если её убрала фоновая синхронизация. */
    suspend fun restoreDraftRecord(email: EmailEntity, attachments: List<com.dedovmosol.iwomail.eas.DraftAttachmentData>) {
        val rows = prepareDraftAttachmentFiles(email.id, attachments)
        database.withTransaction {
            emailDao.insert(email)
            if (rows.isNotEmpty()) attachmentDao.insertAll(rows)
        }
        updateDraftsFolderCount(email.folderId)
    }

    /** В папке есть другой черновик с этой темой (например, синхронизация перенесла запись под EAS ServerId). */
    suspend fun hasOtherDraftWithSubject(folderId: String, subject: String, exceptIds: Set<String>): Boolean =
        emailDao.hasEmailWithSubject(folderId, subject, exceptIds.toList())

    /**
     * Скрытые копии серверного черновика по его id (EWS; EAS до 16.0 их не передаёт). Записи на устройстве может
     * уже не быть (заменённая версия). null — не узнать.
     */
    suspend fun serverDraftBcc(accountId: Long, serverId: String): String? {
        if (serverId.isBlank() || com.dedovmosol.iwomail.eas.DraftIds.isLocal(serverId)) return null
        val collectionId = folderDao.getFolderByType(accountId, FolderType.DRAFTS)?.serverId ?: return null
        val client = accountRepo.createEasClient(accountId) ?: return null
        return client.findDraftBcc(collectionId, serverId)
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /**
     * Обновление черновика: на сервере (EWS UpdateItem), затем локально. Сервер не принял — ошибка, и
     * локальная копия НЕ меняется: она зеркало серверной, а «сохранено» без сервера теряло правку при
     * следующей синхронизации. [DraftIds.NOT_RESOLVED] — EWS-id черновика не определить однозначно:
     * вызывающий сохраняет черновик заново (новый + удаление прежнего по его id).
     * [bcc] = null — скрытые копии не известны (черновик из Outlook): на сервере они не меняются.
     */
    suspend fun updateDraft(
        accountId: Long,
        serverId: String,
        to: String,
        cc: String,
        bcc: String? = "",
        subject: String,
        body: String,
        fromEmail: String,
        fromName: String,
        importance: Int = 1,
        // Тело локальной копии: с data:-картинками ([body] — то, что уходит на сервер, с cid:)
        localBody: String = body,
        // Скрытые копии на сервере не известны: [bcc] добавляется к ним, локально они остаются неизвестными
        appendBcc: Boolean = false
    ): EasResult<String> {
        val account = accountRepo.getAccount(accountId)
            ?: return EasResult.Error("Аккаунт не найден")

        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Не поддерживается")
        }

        val draftsFolder = folderDao.getFolderByType(accountId, FolderType.DRAFTS)
            ?: return EasResult.Error("Папка черновиков не найдена")
        val emailId = "${accountId}_${serverId}"
        // Не крупнее окна курсора БД: иначе строку не прочитать (картинки тогда — файлы вложений).
        val storedBody = if (localBody.length <= DraftUpload.MAX_LOCAL_BODY_CHARS) localBody else body

        // UPDATE in place, не insert(): REPLACE = DELETE + INSERT, а DELETE каскадом удаляет вложения.
        suspend fun saveLocally() {
            emailDao.updateDraftFields(
                id = emailId,
                to = to,
                cc = cc,
                subject = subject,
                body = storedBody,
                preview = stripHtml(localBody).take(150).replace("\n", " ").trim(),
                dateReceived = System.currentTimeMillis(),
                fromEmail = fromEmail,
                fromName = fromName,
                bcc = bcc.takeUnless { appendBcc },
                importance = importance
            )
            updateDraftsFolderCount(draftsFolder.id)
        }

        if (com.dedovmosol.iwomail.eas.DraftIds.isLocal(serverId)) {
            saveLocally()
            // Картинки, удалённые из тела (или уже встроенные data: URL), — не вложения черновика.
            deleteDraftAttachmentRows(
                emailId,
                DraftUpload.unreferencedInlineImages(attachmentDao.getAttachmentsList(emailId), storedBody)
            )
            return EasResult.Success(serverId)
        }

        val easClient = accountRepo.getEasClientOrError<String>(accountId) { return it }
        easClient.detectEasVersion()

        // EAS ServerId ("22:2"): EWS нужен полный ItemId — по ПРЕЖНЕЙ теме (новая на сервере ещё не
        // сохранена) и только при однозначном совпадении, иначе правка ушла бы в чужой черновик.
        val ewsItemId = if (com.dedovmosol.iwomail.eas.DraftIds.isEwsItemId(serverId)) {
            serverId
        } else {
            val oldSubject = emailDao.getEmail(emailId)?.subject ?: subject
            easClient.findUniqueDraftItemIdBySubject(oldSubject)
                ?: return EasResult.Error(com.dedovmosol.iwomail.eas.DraftIds.NOT_RESOLVED)
        }

        return when (val result = easClient.updateDraft(ewsItemId, to, cc, bcc, subject, body, importance, appendBcc)) {
            is EasResult.Success -> {
                // Без syncEmails: гонка SyncKey с фоновой синхронизацией (INVALID_SYNCKEY → полный resync).
                // UpdateItem не меняет ItemId; изменения подтянет следующий фоновый sync.
                saveLocally()
                EasResult.Success(serverId)
            }
            is EasResult.Error -> result
        }
    }
    /**
     * Удаляет черновик с сервера и из БД.
     * @param excludeEwsItemId — EWS ItemId нового черновика (созданного saveDraft),
     *   который НЕ должен быть удалён. Защита от случайного удаления при поиске по subject.
     */
    suspend fun deleteDraft(
        accountId: Long,
        serverId: String,
        excludeEwsItemId: String? = null,
        // Успех — только если черновик удалён на сервере: на Exchange 2007 неудачный EAS Delete иначе
        // считается успехом (локальная очистка, «вернётся при синхронизации»). Нужен замене черновика:
        // не удалённый прежний удалит синхронизация позже (EmailEntity.replacesServerId).
        requireServerDeletion: Boolean = false
    ): EasResult<Boolean> {
        val account = accountRepo.getAccount(accountId)
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Не поддерживается")
        }
        
        val draftsFolder = folderDao.getFolderByType(accountId, FolderType.DRAFTS)
            ?: return EasResult.Error("Папка черновиков не найдена")
        
        val emailId = "${accountId}_${serverId}"
        
        if (serverId.startsWith("local_draft_")) {
            // Черновик на устройстве мог заменять серверный (правка без связи): тот удаляется тоже —
            // иначе прежняя версия осталась бы на сервере (в Outlook и OWA).
            val replaced = com.dedovmosol.iwomail.eas.DraftIds.replacedIds(emailDao.getEmail(emailId)?.replacesServerId)
            // Удаляем вложения (записи + файлы)
            cleanupDraftAttachmentFiles(emailId)
            attachmentDao.deleteByEmail(emailId)
            emailDao.delete(emailId)
            updateDraftsFolderCount(draftsFolder.id)
            for (id in replaced) deleteDraft(accountId, id, requireServerDeletion = true)
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
            updateDraftsFolderCount(draftsFolder.id)
            // Прежняя версия, которую этот черновик заменял и которую не удалось удалить сразу, — тоже
            // (иначе с удалением записи пропала бы и отметка, а версия осталась бы на сервере).
            for (id in com.dedovmosol.iwomail.eas.DraftIds.replacedIds(freshEmail?.replacesServerId)) {
                if (id != serverId && id != excludeEwsItemId) deleteDraft(accountId, id, requireServerDeletion = true)
            }
        }
        
        // КРИТИЧНО: Exchange 2007 игнорирует DeletesAsMoves=0 в EAS Sync Delete.
        // Черновик перемещается в "Удалённые" вместо безвозвратного удаления,
        // вызывая ошибки IMAP4 при просмотре через Outlook.
        // Решение: для Exchange 2007 используем EWS HardDelete как ОСНОВНОЙ метод.
        // Черновик, созданный приложением (EWS ItemId, до первой синхронизации), — только через EWS, на любой
        // версии Exchange: EAS Sync Delete с таким id ничего не удаляет, а ответ (Status коллекции = 1) выглядел
        // бы успехом — прежняя версия оставалась на сервере.
        if (com.dedovmosol.iwomail.eas.DraftIds.isEwsItemId(actualServerId)) {
            val ewsResult = easClient.hardDeleteDraft(actualServerId)
            if (ewsResult is EasResult.Error) {
                if (requireServerDeletion) return EasResult.Error(ewsResult.message)
                // Как и для Exchange 2007 ниже: локально удаляется, на сервере остался — вернётся с синхронизацией.
                android.util.Log.w("MailRepository", "EWS draft delete failed (will reappear on next sync): ${ewsResult.message}")
            }
            finalizeDraftDeletion()
            return EasResult.Success(true)
        }

        if (easClient.isExchange2007()) {
            if (actualServerId.contains(":")) {
                val draftSubject = freshEmail?.subject ?: ""
                if (draftSubject.isNotBlank()) {
                    // Только однозначное совпадение по теме: иначе удалился бы чужой черновик с той же темой.
                    val draftItemId = easClient.findUniqueDraftItemIdBySubject(draftSubject, excludeEwsItemId)
                    if (draftItemId != null) {
                        val ewsDeleteResult = easClient.hardDeleteDraft(draftItemId)
                        if (ewsDeleteResult is EasResult.Success) {
                            finalizeDraftDeletion()
                            return EasResult.Success(true)
                        }
                    }
                }
            }
            
            // Шаг 3 (поиск по теме с удалением ВСЕХ совпадений, кроме нового) убран: удалял и чужие
            // черновики с той же темой. Неоднозначно — точное удаление по EAS ServerId ниже.

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
            val delResult = if (syncKey != "0") {
                easClient.deleteEmailPermanently(draftsFolder.serverId, actualServerId, syncKey)
            } else {
                EasResult.Error("SyncKey is 0")
            }
            if (delResult is EasResult.Error) {
                if (requireServerDeletion) return EasResult.Error(delResult.message)
                android.util.Log.w("MailRepository", "EAS draft delete failed (will reappear on next sync): ${delResult.message}")
            }
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
                // Только однозначное совпадение по теме (кроме нового черновика): иначе удалился бы чужой.
                val uniqueItemId = easClient.findUniqueDraftItemIdBySubject(freshEmail?.subject ?: "", excludeEwsItemId)
                if (uniqueItemId != null) {
                    val ewsDeleteResult = easClient.hardDeleteDraft(uniqueItemId)
                    if (ewsDeleteResult is EasResult.Success) {
                        finalizeDraftDeletion()
                        return EasResult.Success(true)
                    }
                }
            }
            // EWS ItemId сюда не доходит (удалён выше через EWS).
        }
        
        return when (deleteResult) {
            is EasResult.Success -> {
                finalizeDraftDeletion()
                EasResult.Success(true)
            }
            is EasResult.Error -> deleteResult
        }
    }

    private suspend fun updateDraftsFolderCount(folderId: String) {
        val totalCount = emailDao.getCountByFolder(folderId)
        val unreadCount = emailDao.getUnreadCount(folderId)
        folderDao.updateCounts(folderId, unreadCount, totalCount)
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
            database.withTransaction {
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
                db.execSQL(repairEmailsSql)
                db.execSQL(repairEmailsSql)

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
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("MailRepository", "XML entity repair failed", e)
        }
    }

}

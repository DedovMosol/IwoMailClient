package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasDraft
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.SyncResponse
import com.dedovmosol.iwomail.util.HtmlRegex

// Предкомпилированные regex для производительности
private val CN_REGEX = Regex("CN=([^/><]+)", RegexOption.IGNORE_CASE)
private val NAME_BEFORE_BRACKET_REGEX = Regex("^\"?([^\"<]+)\"?\\s*<")
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Сервис для синхронизации писем по протоколам (EAS, IMAP, POP3)
 * Single Responsibility: синхронизация писем с серверами
 */
class EmailSyncService(
    private val context: Context,
    private val folderDao: FolderDao,
    private val emailDao: EmailDao,
    private val attachmentDao: AttachmentDao,
    private val accountDao: AccountDao,
    private val accountRepo: AccountRepository
) {
    // Данные сохранённых тел черновиков (для восстановления при full resync)
    private data class SavedDraftBody(
        val subject: String, val body: String, val bodyType: Int,
        val dateReceived: Long, val to: String, val cc: String,
        val from: String, val fromName: String,
        val attachments: List<AttachmentEntity>
    )
    
    // Результат обработки новых писем из одного батча Sync
    private data class NewEmailsBatchResult(
        val allInserted: List<EmailEntity>,
        val draftReplacements: Map<String, String>,
        val insertedCount: Int
    )
    
    companion object {
        // КРИТИЧНО: activeSyncs ОБЯЗАН быть общим (companion object / static) для ВСЕХ экземпляров!
        // SyncWorker и PushService создают РАЗНЫЕ экземпляры MailRepository → EmailSyncService.
        // Без общего activeSyncs они могут одновременно синхронизировать одну папку,
        // вызывая дублирование данных, гонки SyncKey и прыжки счётчиков.
        private val activeSyncs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        
        // КРИТИЧНО: Защита от "воскрешения" удалённых писем.
        // Множество emailId (accountId_serverId) писем, удалённых пользователем.
        // ОБЯЗАН быть в companion object — SyncWorker и PushService создают РАЗНЫЕ экземпляры!
        // Персистится через SharedPreferences чтобы пережить рестарт приложения.
        // Аналог CalendarRepository.deletedServerIds — тот же подход для писем.
        private val deletedEmailIds = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )
        
        private const val PREFS_NAME = "email_deleted_ids"
        private const val KEY_DELETED_IDS = "deleted_email_ids"
        private var prefsInitialized = false
        
        // Кэш данных удалённых черновиков для восстановления при будущем Add.
        // Когда Exchange реорганизует черновик (редактирование в Outlook, внутренняя миграция),
        // он отправляет Delete(старый) + Add(новый). P5 FIX обрабатывает пары в ОДНОМ батче.
        // Но Delete и Add могут прийти в РАЗНЫХ батчах или даже в разных sync-вызовах.
        // Без этого кэша: Delete каскадно уничтожает body (data: URL) и вложения (localPath),
        // а Add создаёт запись с серверными данными (пустой body, нет localPath) → потеря данных.
        // Кэш: перед Delete сохраняем body + вложения, при Add проверяем match по subject.
        // В памяти (не персистится) — покрывает типичный сценарий (разные батчи одной сессии).
        data class PendingDraftRestore(
            val emailId: String, // emailId исходного черновика (для cleanup в reconcile)
            val subject: String, val body: String, val bodyType: Int,
            val to: String, val cc: String,
            val from: String, val fromName: String,
            val attachments: List<AttachmentEntity>,
            val timestamp: Long = System.currentTimeMillis()
        )
        // Key = "${accountId}_${subject}" для матча по subject (serverId меняется).
        private val pendingDraftRestores = java.util.concurrent.ConcurrentHashMap<String, PendingDraftRestore>()
        // Время жизни записи в кэше (1 час). После этого данные считаются устаревшими.
        private const val PENDING_RESTORE_TTL_MS = 3_600_000L
        
        // Максимальный размер защитного множества.
        // Ограничение необходимо потому что:
        // 1. SharedPreferences.putStringSet() на некоторых устройствах ненадёжен при >100 элементов
        // 2. Для клиентских удалений (Sync Delete) сервер НЕ отправляет <Delete> в ответе
        //    (MS-ASCMD: ответ только для failed deletions), поэтому confirmDeletions() 
        //    не вызывается — набор растёт бесконечно без этого лимита
        // 3. Память: каждый emailId ~30-50 символов
        // 500 записей покрывают ~5 минут интенсивного удаления, что достаточно
        // для защиты от race condition с фоновым sync.
        private const val MAX_DELETED_IDS_SIZE = 500
        
        private fun initFromPrefs(context: Context) {
            if (prefsInitialized) return
            prefsInitialized = true
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val saved = prefs.getStringSet(KEY_DELETED_IDS, emptySet()) ?: emptySet()
                deletedEmailIds.addAll(saved)
            } catch (_: Exception) { }
        }
        
        private fun saveToPrefs(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putStringSet(KEY_DELETED_IDS, deletedEmailIds.toSet()).apply()
            } catch (_: Exception) { }
        }
        
        /**
         * Регистрирует удалённое письмо для защиты от восстановления.
         * Вызывается из EmailOperationsService перед локальным удалением.
         * При превышении MAX_DELETED_IDS_SIZE очищает набор полностью —
         * защита актуальна только первые секунды/минуты после удаления.
         */
        fun registerDeletedEmail(emailId: String, context: Context? = null) {
            if (emailId.isNotBlank()) {
                if (deletedEmailIds.size >= MAX_DELETED_IDS_SIZE) {
                    android.util.Log.w("EmailSyncService", 
                        "deletedEmailIds exceeded $MAX_DELETED_IDS_SIZE, clearing old entries")
                    deletedEmailIds.clear()
                }
                deletedEmailIds.add(emailId)
                context?.let { saveToPrefs(it) }
            }
        }
        
        /**
         * Проверяет, было ли письмо удалено пользователем (и ещё не подтверждено сервером).
         * Используется в syncEmailsEas для фильтрации "воскресших" писем.
         */
        fun isDeletedByUser(emailId: String): Boolean {
            return deletedEmailIds.contains(emailId)
        }
        
        /**
         * Подтверждает удаление конкретных писем — убирает их из защитного множества.
         * Вызывается когда сервер явно сообщает об удалении через deletedIds в Sync-ответе.
         * Также вызывается автоматически при превышении MAX_DELETED_IDS_SIZE.
         * @param confirmedDeletedIds — набор emailId, удалённых сервером
         */
        fun confirmDeletions(confirmedDeletedIds: Set<String>, context: Context? = null) {
            val sizeBefore = deletedEmailIds.size
            deletedEmailIds.removeAll(confirmedDeletedIds)
            if (sizeBefore != deletedEmailIds.size) {
                context?.let { saveToPrefs(it) }
            }
        }
    }
    
    init {
        // Инициализируем кэш удалённых писем из SharedPreferences при первом создании
        initFromPrefs(context)
    }
    
    /**
     * Проверка, идёт ли сейчас синхронизация указанной папки
     */
    fun isFolderSyncing(folderId: String): Boolean {
        return activeSyncs[folderId] == true
    }
    
    /**
     * Синхронизация писем в папке
     * @param forceFullSync если true, сбрасывает SyncKey для полной ресинхронизации
     * @param skipRecentEditCheck если true, отключает защиту от race condition (для явных операций delete/update)
     */
    suspend fun syncEmails(accountId: Long, folderId: String, forceFullSync: Boolean = false, skipRecentEditCheck: Boolean = false): EasResult<Int> {
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        val folder = folderDao.getFolder(folderId)
        if (folder?.type == FolderType.DRAFTS && AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            return syncDraftsFull(accountId, folderId, skipRecentEditCheck)
        }
        
        if (forceFullSync && AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            folderDao.updateSyncKey(folderId, "0")
        }
        
        return when (AccountType.valueOf(account.accountType)) {
            AccountType.EXCHANGE -> syncEmailsEas(accountId, folderId)
            AccountType.IMAP -> syncEmailsImap(accountId, folderId)
            AccountType.POP3 -> syncEmailsPop3(accountId, folderId)
        }
    }
    
    /**
     * Полная ресинхронизация черновиков
     * Загружает локальные черновики на сервер при появлении интернета
     */
suspend fun syncDraftsFull(accountId: Long, folderId: String, skipRecentEditCheck: Boolean = false): EasResult<Int> {
    // 1. СНАЧАЛА синхронизируем черновики с сервера.
    // Это КРИТИЧНО для предотвращения дубликатов:
    // Если createDraftMime() создал черновик на сервере, но ItemId не удалось извлечь
    // (→ local_draft_ fallback), синхронизация найдёт серверный черновик
    // и миграция EWS→EAS обработает его. Только ПОСЛЕ этого загружаем
    // оставшиеся local_draft_ записи (действительно оффлайн-черновики).
    val syncResult = syncEmailsEas(accountId, folderId, skipRecentEditCheck = skipRecentEditCheck)
    
    // 2. Найти оставшиеся локальные черновики (созданные без интернета)
    val localDrafts = emailDao.getEmailsByFolderList(folderId)
        .filter { it.serverId.startsWith("local_draft_") }
    
    // 3. Перед загрузкой проверяем: нет ли на сервере черновика с таким же subject?
    // createDraftMime() мог успешно создать черновик, но парсинг ответа не удался.
    // В этом случае sync (шаг 1) уже нашёл серверный черновик.
    // Если local_draft_ имеет совпадение по subject — удаляем как дубликат.
    if (localDrafts.isNotEmpty()) {
        val allDrafts = emailDao.getEmailsByFolderList(folderId)
        val serverDrafts = allDrafts.filter { !it.serverId.startsWith("local_draft_") }
        
        val trulyLocalDrafts = localDrafts.filter { localDraft ->
            val hasServerMatch = serverDrafts.any { serverDraft ->
                serverDraft.subject == localDraft.subject &&
                        kotlin.math.abs(serverDraft.dateReceived - localDraft.dateReceived) < 120_000
            }
            if (hasServerMatch) {
                // Дубликат: черновик уже на сервере (sync нашёл его).
                // Удаляем local_draft_ запись.
                try {
                    attachmentDao.deleteByEmail(localDraft.id)
                    emailDao.delete(localDraft.id)
                } catch (_: Exception) {}
                false // Не загружаем
            } else {
                true // Действительно оффлайн-черновик — загружаем
            }
        }
        
        if (trulyLocalDrafts.isNotEmpty()) {
            val easClient = accountRepo.createEasClient(accountId)
            if (easClient != null) {
                val draftsFolder = folderDao.getFolder(folderId)
                if (draftsFolder != null) {
                    for (draft in trulyLocalDrafts) {
                        val result = easClient.createDraft(
                            to = draft.to,
                            cc = draft.cc,
                            bcc = "",
                            subject = draft.subject,
                            body = draft.body,
                            draftsFolderId = draftsFolder.serverId
                        )
                        
                        when (result) {
                            is EasResult.Success -> {
                                val serverItemId = result.data
                                if (!serverItemId.startsWith("local_draft_")) {
                                    val updatedEmail = draft.copy(
                                        id = "${accountId}_${serverItemId}",
                                        serverId = serverItemId
                                    )
                                    emailDao.delete(draft.id)
                                    emailDao.insert(updatedEmail)
                                }
                            }
                            is EasResult.Error -> {}
                        }
                    }
                }
            }
        }
        
        // Обновляем счётчики после очистки
        val totalCount = emailDao.getCountByFolder(folderId)
        val unreadCount = emailDao.getUnreadCount(folderId)
        folderDao.updateCounts(folderId, unreadCount, totalCount)
    }
    
    return syncResult
}
    /**
     * Обработка результата EWS синхронизации черновиков
     */
    private suspend fun processDraftsEwsResult(
        serverDrafts: List<EasDraft>,
        accountId: Long,
        folderId: String,
        skipRecentEditCheck: Boolean = false
    ): EasResult<Int> {
        val serverDraftIds = serverDrafts.map { it.serverId }.toSet()
        
        val syncTime = System.currentTimeMillis()
        val RECENT_EDIT_THRESHOLD = 10_000L
        val existingEmails = emailDao.getEmailsByFolderList(folderId).associateBy { it.serverId }
        
        if (serverDrafts.isNotEmpty()) {
            val emailEntities = serverDrafts.mapNotNull { draft ->
                val existingEmail = existingEmails[draft.serverId]
                
                if (existingEmail != null) {
                    val timeSinceLocalEdit = syncTime - existingEmail.dateReceived
                    
                    if (!skipRecentEditCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                        return@mapNotNull null
                    }
                    
                    if (skipRecentEditCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                        val dataChanged = existingEmail.subject != draft.subject || 
                                          existingEmail.to != draft.to ||
                                          existingEmail.body != draft.body
                        val serverIdChanged = existingEmail.serverId != draft.serverId
                        
                        if (!dataChanged && !serverIdChanged) {
                            return@mapNotNull null
                        }
                    }
                }
                
                val parsedDate = parseEwsDate(draft.dateCreated)
                val preview = stripHtml(draft.body).take(150).replace("\n", " ").trim()
                EmailEntity(
                    id = "${accountId}_${draft.serverId}",
                    accountId = accountId,
                    folderId = folderId,
                    serverId = draft.serverId,
                    from = "",
                    fromName = "",
                    to = draft.to,
                    cc = draft.cc,
                    subject = draft.subject,
                    preview = preview,
                    body = draft.body,
                    bodyType = 2,
                    dateReceived = parsedDate,
                    read = true,
                    importance = 1,
                    hasAttachments = draft.hasAttachments || draft.attachments.isNotEmpty()
                )
            }
            if (emailEntities.isNotEmpty()) {
                emailDao.insertAll(emailEntities)
            }

            val draftEmailIds = serverDrafts.map { "${accountId}_${it.serverId}" }
            if (draftEmailIds.isNotEmpty()) {
                attachmentDao.deleteByEmailIds(draftEmailIds)
            }

            val allAttachments = serverDrafts.flatMap { draft ->
                val emailId = "${accountId}_${draft.serverId}"
                draft.attachments.map { att ->
                    AttachmentEntity(
                        emailId = emailId,
                        fileReference = att.fileReference,
                        displayName = att.displayName,
                        contentType = att.contentType,
                        estimatedSize = att.estimatedSize,
                        isInline = att.isInline,
                        contentId = att.contentId
                    )
                }
            }
            if (allAttachments.isNotEmpty()) {
                attachmentDao.insertAll(allAttachments)
            }
        }
        
        val localDrafts = emailDao.getEmailsByFolderList(folderId)
        localDrafts.forEach { localDraft ->
            if (localDraft.serverId !in serverDraftIds) {
                attachmentDao.deleteByEmail(localDraft.id)
                emailDao.delete(localDraft.id)
            }
        }
        
        val totalCount = emailDao.getCountByFolder(folderId)
        val unreadCount = emailDao.getUnreadCount(folderId)
        folderDao.updateCounts(folderId, unreadCount, totalCount)
        
        return EasResult.Success(serverDrafts.size)
    }
    
    /**
     * Полная ресинхронизация папки Отправленные
     */
    suspend fun syncSentFull(accountId: Long, folderId: String): EasResult<Int> {
        if (activeSyncs[folderId] == true) {
            var waited = 0
            while (activeSyncs[folderId] == true && waited < 15) {
                kotlinx.coroutines.delay(1000)
                waited++
            }
            if (activeSyncs[folderId] == true) {
                return EasResult.Error("Синхронизация уже выполняется")
            }
        }
        activeSyncs[folderId] = true
        
        try {
        val client = accountRepo.createEasClient(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        val folder = folderDao.getFolder(folderId) 
            ?: return EasResult.Error("Папка не найдена")
        
        val serverEmailIds = mutableSetOf<String>()
        var syncKey = "0"
        val windowSize = 100
        
        when (val result = client.sync(folder.serverId, "0", windowSize)) {
            is EasResult.Success -> {
                syncKey = result.data.syncKey
            }
            is EasResult.Error -> {
                return EasResult.Error(result.message)
            }
        }
        
        var moreAvailable = true
        var iterations = 0
        val maxIterations = 500
        var totalEmails = 0
        var syncCompletedFully = false
        
        while (moreAvailable && iterations < maxIterations) {
            iterations++
            kotlinx.coroutines.yield()
            
            when (val result = client.sync(folder.serverId, syncKey, windowSize)) {
                is EasResult.Success -> {
                    syncKey = result.data.syncKey
                    moreAvailable = result.data.moreAvailable
                    totalEmails += result.data.emails.size
                    
                    result.data.emails.forEach { email ->
                        serverEmailIds.add(email.serverId)
                    }
                    
                    if (result.data.emails.isNotEmpty()) {
                        val emailEntities = result.data.emails.map { email ->
                            val parsedDate = parseDate(email.dateReceived)
                            EmailEntity(
                                id = "${accountId}_${email.serverId}",
                                accountId = accountId,
                                folderId = folderId,
                                serverId = email.serverId,
                                from = email.from,
                                fromName = extractName(email.from),
                                to = email.to,
                                cc = email.cc,
                                subject = email.subject,
                                preview = stripHtml(email.body).take(150).replace("\n", " ").trim(),
                                body = "",
                                bodyType = email.bodyType,
                                dateReceived = parsedDate,
                                read = email.read,
                                flagged = email.flagged,
                                importance = email.importance,
                                hasAttachments = email.attachments.isNotEmpty()
                            )
                        }
                        
                        val emailIdsToInsert = emailEntities.map { it.id }
                        val alreadyExistingIds = emailDao.getExistingIds(emailIdsToInsert).toSet()
                        
                        emailDao.insertAllIgnore(emailEntities)
                        
                        val allAttachments = result.data.emails
                            .filter { email -> "${accountId}_${email.serverId}" !in alreadyExistingIds }
                            .flatMap { email ->
                                email.attachments.map { att ->
                                    AttachmentEntity(
                                        emailId = "${accountId}_${email.serverId}",
                                        fileReference = att.fileReference,
                                        displayName = att.displayName,
                                        contentType = att.contentType,
                                        estimatedSize = att.estimatedSize,
                                        isInline = att.isInline,
                                        contentId = att.contentId
                                    )
                                }
                            }
                        if (allAttachments.isNotEmpty()) {
                            attachmentDao.insertAll(allAttachments)
                        }
                    }
                    
                    if (!moreAvailable) {
                        syncCompletedFully = true
                    }
                }
                is EasResult.Error -> {
                    android.util.Log.w("EmailSyncService", "syncSentFull: Error at batch $iterations: ${result.message}")
                    moreAvailable = false
                }
            }
        }
        
        if (syncCompletedFully) {
            val localEmails = emailDao.getEmailsByFolderList(folderId)
            var removedCount = 0
            localEmails.forEach { localEmail ->
                if (localEmail.serverId !in serverEmailIds) {
                    attachmentDao.deleteByEmail(localEmail.id)
                    emailDao.delete(localEmail.id)
                    removedCount++
                }
            }
            if (removedCount > 0) {
                android.util.Log.d("EmailSyncService", "syncSentFull: Removed $removedCount orphaned emails")
            }
        } else if (serverEmailIds.isNotEmpty()) {
            android.util.Log.w("EmailSyncService", 
                "syncSentFull: Partial sync (${serverEmailIds.size} emails). Skipping orphan deletion.")
        }
        
        folderDao.updateSyncKey(folderId, syncKey)
        
        val totalCount = emailDao.getCountByFolder(folderId)
        val unreadCount = emailDao.getUnreadCount(folderId)
        folderDao.updateCounts(folderId, unreadCount, totalCount)
        
        return EasResult.Success(serverEmailIds.size)
        } finally {
            activeSyncs.remove(folderId)
        }
    }
    
    // ============================================
    // === Утилитные методы для миграции черновиков ===
    // ============================================
    
    /**
     * Мержит вложения: обогащает текущие вложения targetEmailId данными localPath из sourceAttachments.
     * Добавляет orphan-записи (есть в source, нет в target).
     * Заменяет весь набор вложений (delete + insert) чтобы избежать дублей.
     */
    private suspend fun mergeAttachmentsToTarget(
        targetEmailId: String,
        sourceAttachments: List<AttachmentEntity>
    ) {
        val localAtts = sourceAttachments.filter { !it.localPath.isNullOrBlank() }
        if (localAtts.isEmpty()) return
        
        val targetAtts = attachmentDao.getAttachmentsList(targetEmailId)
        val usedSourceIndices = mutableSetOf<Int>()
        val mergedSet = mutableListOf<AttachmentEntity>()
        
        for (tAtt in targetAtts) {
            val matchIdx = localAtts.withIndex().indexOfFirst { (i, sAtt) ->
                i !in usedSourceIndices &&
                ((!sAtt.contentId.isNullOrBlank() && tAtt.contentId == sAtt.contentId) ||
                (sAtt.displayName.isNotBlank() && tAtt.displayName == sAtt.displayName))
            }
            if (matchIdx >= 0) {
                usedSourceIndices.add(matchIdx)
                val match = localAtts[matchIdx]
                mergedSet.add(tAtt.copy(id = 0, localPath = match.localPath, downloaded = match.downloaded))
            } else {
                mergedSet.add(tAtt.copy(id = 0))
            }
        }
        for ((i, sAtt) in localAtts.withIndex()) {
            if (i !in usedSourceIndices) {
                mergedSet.add(sAtt.copy(id = 0, emailId = targetEmailId))
            }
        }
        attachmentDao.deleteByEmail(targetEmailId)
        attachmentDao.insertAll(mergedSet)
    }
    
    /**
     * Мигрирует тело черновика из source на target.
     * Если target body пустой — полностью восстанавливаем из source.
     * Если source содержит data: URL, а target нет — мержим.
     */
    private suspend fun migrateDraftBodyToTarget(
        targetEmailId: String,
        sourceBody: String,
        sourceBodyType: Int,
        sourceAttachments: List<AttachmentEntity> = emptyList()
    ) {
        if (sourceBody.isBlank()) return
        val currentBody = emailDao.getEmail(targetEmailId)?.body ?: ""
        var bodyUpdated = false
        if (currentBody.isBlank()) {
            emailDao.updateBody(targetEmailId, sourceBody)
            if (sourceBodyType != 0) emailDao.updateBodyType(targetEmailId, sourceBodyType)
            bodyUpdated = true
        } else if (sourceBody.contains("data:") && !currentBody.contains("data:")) {
            val merged = mergeDraftBody(currentBody, sourceBody, sourceAttachments)
            emailDao.updateBody(targetEmailId, merged)
            if (sourceBodyType != 0) emailDao.updateBodyType(targetEmailId, sourceBodyType)
            bodyUpdated = true
        }
        // Обновляем preview если body был мигрирован — иначе черновик может выглядеть
        // "пустым" в списке (preview от пустого Exchange body, а body уже мигрирован).
        if (bodyUpdated) {
            val newPreview = stripHtml(sourceBody).take(150).replace("\n", " ").trim()
            if (newPreview.isNotBlank()) {
                emailDao.updatePreview(targetEmailId, newPreview)
            }
        }
    }
    
    /**
     * Мигрирует поля to/cc/from из source на target (если у target они хуже).
     */
    private suspend fun migrateDraftFieldsToTarget(
        targetEmailId: String,
        sourceTo: String,
        sourceCc: String,
        sourceFrom: String,
        sourceFromName: String
    ) {
        val current = emailDao.getEmail(targetEmailId) ?: return
        if (sourceTo.contains("@") && !current.to.contains("@")) {
            emailDao.updateTo(targetEmailId, sourceTo)
        }
        if (sourceCc.contains("@") && !current.cc.contains("@")) {
            emailDao.updateCc(targetEmailId, sourceCc)
        }
        if (sourceFrom.isNotBlank() && current.from.isBlank()) {
            emailDao.updateFrom(targetEmailId, sourceFrom, sourceFromName)
        }
    }
    
    // ============================================
    // === Синхронизация писем через EAS (рефакторинг) ===
    // ============================================
    
    /**
     * Синхронизация писем через EAS
     */
    suspend fun syncEmailsEas(accountId: Long, folderId: String, skipRecentEditCheck: Boolean = false, retryCount: Int = 0): EasResult<Int> {
        if (retryCount > 1) {
            return EasResult.Error("Ошибка синхронизации: слишком много попыток")
        }
        
        initFromPrefs(context)
        
        if (retryCount == 0 && activeSyncs.putIfAbsent(folderId, true) != null) {
            android.util.Log.w("EmailSyncService", "syncEmailsEas: skipping — folder $folderId already syncing")
            return EasResult.Error("Синхронизация уже выполняется")
        }
        
        try {
            val client = accountRepo.createEasClient(accountId) 
                ?: return EasResult.Error("Аккаунт не найден")
            val folder = folderDao.getFolder(folderId) 
                ?: return EasResult.Error("Папка не найдена")
            
            var syncKey = folder.syncKey
            val isFullResync = syncKey == "0"
            
            // Сохраняем тела черновиков перед полной ресинхронизацией.
            // EAS Sync НЕ включает body в ответ — только заголовки.
            // Без этого при full resync тела с data: URL будут утрачены.
            // ТАКЖЕ: сохраняем to/cc — EAS возвращает только PR_DISPLAY_TO (display name),
            // а оригинальные email-адреса из EWS-записи теряются без этого.
            val savedDraftBodies = mutableListOf<SavedDraftBody>()
            
            // RECONCILE MODE для черновиков: НЕ используем deleteByFolder!
            val isDraftReconcile = isFullResync && folder.type == FolderType.DRAFTS
            val serverDraftIdsInFullResync = if (isDraftReconcile) mutableSetOf<String>() else null
            
            // RECONCILE MODE для Отправленных: аналогично черновикам.
            val isSentReconcile = isFullResync && folder.type == FolderType.SENT_ITEMS
            val serverSentIdsInFullResync = if (isSentReconcile) mutableSetOf<String>() else null
            
            if (isFullResync) {
                if (folder.type == FolderType.DRAFTS) {
                    val existingDrafts = emailDao.getEmailsByFolderList(folderId)
                    for (draft in existingDrafts) {
                        if (draft.body.isNotBlank()) {
                            val atts = attachmentDao.getAttachmentsList(draft.id)
                            savedDraftBodies.add(SavedDraftBody(
                                subject = draft.subject,
                                body = draft.body,
                                bodyType = draft.bodyType,
                                dateReceived = draft.dateReceived,
                                to = draft.to,
                                cc = draft.cc,
                                from = draft.from,
                                fromName = draft.fromName,
                                attachments = atts.filter { !it.localPath.isNullOrBlank() }
                            ))
                        }
                    }
                } else if (folder.type == FolderType.SENT_ITEMS) {
                    // НЕ вызываем deleteByFolder — reconcile после sync loop.
                } else {
                    val existingEmailsCount = emailDao.getCountByFolder(folderId)
                    if (existingEmailsCount < 5) {
                        emailDao.deleteByFolder(folderId)
                    }
                }
            }
            var newEmailsCount = 0
            
            val windowSize = 100
            val includeMime = folder.type == FolderType.SENT_ITEMS && !isFullResync
            
            if (syncKey == "0") {
                when (val result = client.sync(folder.serverId, "0", windowSize, false)) {
                    is EasResult.Success -> {
                        if (result.data.status == 12 || result.data.status == 3) {
                            folderDao.resetAllSyncKeys(accountId)
                            accountDao.updateFolderSyncKey(accountId, "0")
                            if (retryCount < 1) {
                                return syncEmailsEas(accountId, folderId, skipRecentEditCheck, retryCount + 1)
                            } else {
                                return EasResult.Error("Ошибка синхронизации: неверный SyncKey после retry")
                            }
                        }
                        if (result.data.status in listOf(4, 5, 6, 7, 8)) {
                            android.util.Log.e("EmailSync", "Initial sync failed with status=${result.data.status} for folder=$folderId")
                            return EasResult.Error("Ошибка синхронизации папки: status=${result.data.status}")
                        }
                        syncKey = result.data.syncKey
                        folderDao.updateSyncKey(folderId, syncKey)
                    }
                    is EasResult.Error -> return EasResult.Error(result.message)
                }
            }
            
            var moreAvailable = true
            var iterations = 0
            val maxIterations = 1000
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 5
            val syncStartTime = System.currentTimeMillis()
            val maxSyncDurationMs = if (isFullResync) 600_000L else 180_000L
            var previousSyncKey = syncKey
            var sameKeyCount = 0
            var emptyDataCount = 0
            var syncLoopCompletedFully = false
            
            while (moreAvailable && iterations < maxIterations && consecutiveErrors < maxConsecutiveErrors) {
                iterations++
                
                if (System.currentTimeMillis() - syncStartTime > maxSyncDurationMs) {
                    android.util.Log.w("EmailSync", "Sync timeout after ${iterations} iterations, breaking")
                    break
                }
                
                kotlinx.coroutines.yield()
                
                when (val result = client.sync(folder.serverId, syncKey, windowSize, includeMime)) {
                    is EasResult.Success -> {
                        consecutiveErrors = 0
                        
                        if (result.data.status == 3 || result.data.status == 12) {
                            folderDao.resetAllSyncKeys(accountId)
                            accountDao.updateFolderSyncKey(accountId, "0")
                            return syncEmailsEas(accountId, folderId, skipRecentEditCheck, retryCount + 1)
                        }
                        
                        if (result.data.status in listOf(4, 5, 6, 7, 8)) {
                            android.util.Log.w("EmailSync", "Sync returned error status=${result.data.status} for folder=$folderId, breaking")
                            break
                        }
                        
                        val newSyncKey = result.data.syncKey
                        
                        if (newSyncKey == previousSyncKey) {
                            sameKeyCount++
                            if (sameKeyCount >= 5) {
                                android.util.Log.w("EmailSync", "SyncKey not changing for 5 iterations, breaking loop")
                                moreAvailable = false
                            }
                        } else {
                            sameKeyCount = 0
                            previousSyncKey = newSyncKey
                        }
                        
                        moreAvailable = result.data.moreAvailable

                        if (moreAvailable && result.data.emails.isEmpty() && result.data.deletedIds.isEmpty() && result.data.changedEmails.isEmpty()) {
                            emptyDataCount++
                            android.util.Log.w("EmailSync", "Server says moreAvailable but no data (empty #$emptyDataCount), syncKey=$newSyncKey")
                            if (emptyDataCount >= 5) {
                                android.util.Log.w("EmailSync", "No data for $emptyDataCount consecutive iterations, breaking loop")
                                moreAvailable = false
                            }
                        } else {
                            emptyDataCount = 0
                        }
                        
                        // === Обработка данных батча через extracted methods ===
                        var draftReplacements = emptyMap<String, String>()
                        var handledDraftDeletionIds = emptySet<String>()
                        
                        if (result.data.emails.isNotEmpty()) {
                            val batchResult = processNewEmailsBatch(
                                result.data, accountId, folderId, folder,
                                skipRecentEditCheck, serverDraftIdsInFullResync, serverSentIdsInFullResync
                            )
                            newEmailsCount += batchResult.insertedCount
                            draftReplacements = batchResult.draftReplacements
                            
                            if (savedDraftBodies.isNotEmpty() && folder.type == FolderType.DRAFTS) {
                                restoreSavedDraftBodies(savedDraftBodies, batchResult.allInserted, folderId)
                            }
                            if (folder.type == FolderType.DRAFTS && pendingDraftRestores.isNotEmpty()) {
                                restoreFromPendingDraftRestores(accountId, batchResult.allInserted)
                            }
                            if (folder.type == FolderType.DRAFTS && result.data.deletedIds.isNotEmpty() && batchResult.allInserted.isNotEmpty()) {
                                handledDraftDeletionIds = migrateDeleteAddDrafts(result.data.deletedIds, batchResult.allInserted, accountId)
                            }
                        }
                        
                        applyDraftReplacements(draftReplacements, accountId)
                        processServerDeletions(result.data.deletedIds, accountId, folderId, folder, handledDraftDeletionIds)
                        processServerChanges(result.data, accountId, folderId, folder, serverDraftIdsInFullResync, serverSentIdsInFullResync)
                        
                        // КРИТИЧНО: Сохраняем SyncKey ПОСЛЕ успешной обработки
                        syncKey = newSyncKey
                        folderDao.updateSyncKey(folderId, syncKey)
                        
                        val totalCount = emailDao.getCountByFolder(folderId)
                        val unreadCount = emailDao.getUnreadCount(folderId)
                        folderDao.updateCounts(folderId, unreadCount, totalCount)
                    }
                    is EasResult.Error -> {
                        if (result.message.contains("INVALID_SYNCKEY") ||
                            result.message.contains("Status=3") ||
                            result.message.contains("Status=12") ||
                            result.message.contains("Status 3") ||
                            result.message.contains("Status 12")) {
                            folderDao.resetAllSyncKeys(accountId)
                            accountDao.updateFolderSyncKey(accountId, "0")
                            return syncEmailsEas(accountId, folderId, skipRecentEditCheck, retryCount + 1)
                        }
                        
                        consecutiveErrors++
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            val totalCount = emailDao.getCountByFolder(folderId)
                            val unreadCount = emailDao.getUnreadCount(folderId)
                            folderDao.updateCounts(folderId, unreadCount, totalCount)
                            
                            return if (newEmailsCount > 0) {
                                EasResult.Success(newEmailsCount)
                            } else {
                                EasResult.Error(result.message)
                            }
                        }
                        kotlinx.coroutines.delay(500L * consecutiveErrors)
                    }
                }
            }
            
            syncLoopCompletedFully = !moreAvailable && consecutiveErrors < maxConsecutiveErrors
            
            if (serverDraftIdsInFullResync != null && serverDraftIdsInFullResync.isNotEmpty() && syncLoopCompletedFully) {
                reconcileDraftsAfterFullResync(serverDraftIdsInFullResync, folderId, accountId)
            }
            
            if (serverSentIdsInFullResync != null && serverSentIdsInFullResync.isNotEmpty() && syncLoopCompletedFully) {
                reconcileSentAfterFullResync(serverSentIdsInFullResync, folderId)
            }
            
            val totalCount = emailDao.getCountByFolder(folderId)
            val unreadCount = emailDao.getUnreadCount(folderId)
            folderDao.updateCounts(folderId, unreadCount, totalCount)
            return EasResult.Success(newEmailsCount)
        } finally {
            activeSyncs.remove(folderId)
        }
    }
    
    // ============================================
    // === Extracted methods из syncEmailsEas ===
    // ============================================
    
    /**
     * Обработка новых писем из одного батча Sync (Add commands).
     * Строит EmailEntity, дедуплицирует, фильтрует, мигрирует EWS→EAS, вставляет в БД.
     */
    private suspend fun processNewEmailsBatch(
        syncData: SyncResponse,
        accountId: Long,
        folderId: String,
        folder: FolderEntity,
        skipRecentEditCheck: Boolean,
        serverDraftIdsInFullResync: MutableSet<String>?,
        serverSentIdsInFullResync: MutableSet<String>?
    ): NewEmailsBatchResult {
        if (syncData.emails.isEmpty()) {
            return NewEmailsBatchResult(emptyList(), emptyMap(), 0)
        }
        
        val existingIds = emailDao.getExistingIds(syncData.emails.map { "${accountId}_${it.serverId}" })
        val existingEmailsMap = if (existingIds.isNotEmpty()) {
            emailDao.getEmailsByIds(existingIds).associateBy { it.id }
        } else {
            emptyMap()
        }
        
        val emailEntities = syncData.emails.map { email ->
            val emailId = "${accountId}_${email.serverId}"
            val existingEmail = existingEmailsMap[emailId]
            val parsedDate = parseDate(email.dateReceived)
            EmailEntity(
                id = emailId,
                accountId = accountId,
                folderId = folderId,
                serverId = email.serverId,
                from = email.from,
                fromName = extractName(email.from),
                to = email.to,
                cc = email.cc,
                subject = email.subject,
                preview = stripHtml(email.body).take(150).replace("\n", " ").trim(),
                body = existingEmail?.body?.takeIf { it.isNotBlank() }
                    ?: if (folder.type == FolderType.DRAFTS && email.body.isNotBlank()) email.body else "",
                bodyType = email.bodyType,
                dateReceived = parsedDate,
                read = email.read,
                flagged = email.flagged,
                importance = email.importance,
                hasAttachments = email.attachments.isNotEmpty()
            )
        }
        
        // Для черновиков: обновляем body/preview уже существующих записей
        if (folder.type == FolderType.DRAFTS && existingIds.isNotEmpty()) {
            val serverEmailMap = syncData.emails.associateBy { "${accountId}_${it.serverId}" }
            for (existingId in existingIds) {
                val serverEmail = serverEmailMap[existingId] ?: continue
                if (serverEmail.body.isBlank()) continue
                val existing = existingEmailsMap[existingId] ?: continue
                val existingBody = existing.body
                if (existingBody.isBlank()) {
                    emailDao.updateBody(existingId, serverEmail.body)
                } else if (existingBody.contains("data:") && !serverEmail.body.contains("data:")) {
                    val atts = attachmentDao.getAttachmentsList(existingId)
                    val merged = mergeDraftBody(serverEmail.body, existingBody, atts)
                    emailDao.updateBody(existingId, merged)
                } else if (!existingBody.contains("data:")) {
                    emailDao.updateBody(existingId, serverEmail.body)
                }
                val newPreview = stripHtml(serverEmail.body).take(150).replace("\n", " ").trim()
                emailDao.updatePreview(existingId, newPreview)
            }
        }
        
        val newEmails = emailEntities.filter { it.id !in existingIds }
        
        // Дедупликация по serverId
        val filteredByServerId = if (newEmails.isNotEmpty()) {
            val serverIds = newEmails.map { it.serverId }
            val existingByServerId = emailDao.getExistingServerIds(accountId, serverIds)
            newEmails.filter { it.serverId !in existingByServerId }
        } else {
            emptyList()
        }
        
        // Дедупликация по содержимому
        var draftReplacements = emptyMap<String, String>()
        val filteredByContent = if (filteredByServerId.isNotEmpty()) {
            val existingEmails = emailDao.getEmailsByFolderList(folderId)
            val isDrafts = folder.type == FolderType.DRAFTS
            val dedupCandidates = if (isDrafts) {
                val deletedInBatch = syncData.deletedIds.toSet()
                existingEmails.filter { e ->
                    !(e.serverId.length > 30 && !e.serverId.contains(":") && !e.serverId.startsWith("local_draft_"))
                    && e.serverId !in deletedInBatch
                }
            } else {
                existingEmails
            }
            
            val contentIndex = dedupCandidates.groupBy { "${it.subject}|${it.from}" }
            val duplicateIds = mutableSetOf<String>()
            val draftReplacementsLocal = mutableMapOf<String, String>()
            
            for (newEmail in filteredByServerId) {
                val key = "${newEmail.subject}|${newEmail.from}"
                var candidates = contentIndex[key]
                
                if (candidates == null && isDrafts) {
                    val subjectPrefix = "${newEmail.subject}|"
                    for ((existingKey, existingCandidates) in contentIndex) {
                        if (existingKey.startsWith(subjectPrefix)) {
                            val filtered = existingCandidates.filter { existing ->
                                kotlin.math.abs(existing.dateReceived - newEmail.dateReceived) < 5000
                            }
                            if (filtered.isNotEmpty()) {
                                candidates = filtered
                                break
                            }
                        }
                    }
                }
                
                if (candidates != null) {
                    val matchingCandidate = candidates.firstOrNull { existing ->
                        kotlin.math.abs(existing.dateReceived - newEmail.dateReceived) < 5000
                    }
                    if (matchingCandidate != null) {
                        if (isDrafts && matchingCandidate.serverId != newEmail.serverId) {
                            draftReplacementsLocal[newEmail.id] = matchingCandidate.id
                        } else {
                            duplicateIds.add(newEmail.id)
                        }
                    }
                }
            }
            
            draftReplacements = draftReplacementsLocal.toMap()
            filteredByServerId.filter { it.id !in duplicateIds }
        } else {
            emptyList()
        }
        
        // Фильтр недавно удалённых
        val filteredByRecentDelete = filteredByContent.filter { email ->
            !isDeletedByUser(email.id)
        }
        
        // Защита от race condition для черновиков
        val finalFiltered = if (folder.type == FolderType.DRAFTS && !skipRecentEditCheck) {
            val syncTime = System.currentTimeMillis()
            val RECENT_EDIT_THRESHOLD = 10_000L
            filteredByRecentDelete.filter { email ->
                val existingEmail = existingEmailsMap[email.id]
                if (existingEmail != null) {
                    val timeSinceLocalEdit = syncTime - existingEmail.dateReceived
                    if (timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                        val dataChanged = existingEmail.subject != email.subject || 
                                          existingEmail.to != email.to ||
                                          existingEmail.body != email.body
                        val serverIdChanged = existingEmail.serverId != email.serverId
                        if (!dataChanged && !serverIdChanged) {
                            return@filter false
                        }
                    }
                }
                true
            }
        } else {
            filteredByRecentDelete
        }
        
        // EWS→EAS миграция для черновиков
        val migratedEasIds = mutableSetOf<String>()
        if (folder.type == FolderType.DRAFTS && finalFiltered.isNotEmpty()) {
            val allDrafts = emailDao.getEmailsByFolderList(folderId)
            val ewsDrafts = allDrafts.filter { draft ->
                draft.serverId.length > 30 && !draft.serverId.contains(":")
                        && !draft.serverId.startsWith("local_draft_")
            }
            if (ewsDrafts.isNotEmpty()) {
                val remainingEws = ewsDrafts.toMutableList()
                for (newEas in finalFiltered) {
                    val matchedEws = remainingEws.firstOrNull { ews ->
                        ews.subject == newEas.subject &&
                                kotlin.math.abs(ews.dateReceived - newEas.dateReceived) < 300_000
                    } ?: remainingEws.firstOrNull { ews ->
                        ews.subject == newEas.subject
                    }
                    if (matchedEws != null && matchedEws.body.isNotBlank()) {
                        remainingEws.remove(matchedEws)
                        val migratedEmail = newEas.copy(
                            body = matchedEws.body,
                            bodyType = matchedEws.bodyType,
                            hasAttachments = matchedEws.hasAttachments || newEas.hasAttachments,
                            to = if (matchedEws.to.contains("@")) matchedEws.to else newEas.to,
                            cc = if (matchedEws.cc.contains("@")) matchedEws.cc else newEas.cc,
                            from = if (matchedEws.from.isNotBlank()) matchedEws.from else newEas.from,
                            fromName = if (matchedEws.fromName.isNotBlank()) matchedEws.fromName else newEas.fromName
                        )
                        emailDao.insert(migratedEmail)
                        migratedEasIds.add(newEas.id)
                        
                        val ewsAttachments = attachmentDao.getAttachmentsList(matchedEws.id)
                        val serverAtts = syncData.emails
                            .firstOrNull { it.serverId == newEas.serverId }
                            ?.attachments ?: emptyList()
                        
                        if (ewsAttachments.isNotEmpty()) {
                            val migratedAttachments = ewsAttachments.map { att ->
                                val serverMatch = serverAtts.firstOrNull { sAtt ->
                                    (!att.contentId.isNullOrBlank() && sAtt.contentId == att.contentId) ||
                                    (att.displayName.isNotBlank() && sAtt.displayName == att.displayName)
                                }
                                att.copy(
                                    id = 0,
                                    emailId = newEas.id,
                                    fileReference = att.fileReference.ifBlank { serverMatch?.fileReference ?: "" }
                                )
                            }
                            attachmentDao.insertAll(migratedAttachments)
                        } else if (serverAtts.isNotEmpty()) {
                            val serverAttEntities = serverAtts.map { sAtt ->
                                AttachmentEntity(
                                    emailId = newEas.id,
                                    fileReference = sAtt.fileReference,
                                    displayName = sAtt.displayName,
                                    contentType = sAtt.contentType,
                                    estimatedSize = sAtt.estimatedSize,
                                    isInline = sAtt.isInline,
                                    contentId = sAtt.contentId
                                )
                            }
                            attachmentDao.insertAll(serverAttEntities)
                        }
                        
                        attachmentDao.deleteByEmail(matchedEws.id)
                        emailDao.delete(matchedEws.id)
                    }
                }
            }
        }
        
        // Вставляем отфильтрованные письма (кроме мигрированных)
        val toInsert = if (migratedEasIds.isNotEmpty()) {
            finalFiltered.filter { it.id !in migratedEasIds }
        } else {
            finalFiltered
        }
        var insertedCount = 0
        if (toInsert.isNotEmpty()) {
            emailDao.insertAllIgnore(toInsert)
            insertedCount += toInsert.size
        }
        insertedCount += migratedEasIds.size
        
        // RECONCILE: собираем serverIds при full resync
        val batchServerIds = syncData.emails.map { "${accountId}_${it.serverId}" }
        serverDraftIdsInFullResync?.addAll(batchServerIds)
        serverSentIdsInFullResync?.addAll(batchServerIds)
        
        // Вставляем вложения для новых писем
        val insertedIds = (toInsert.map { it.id } + migratedEasIds).toSet()
        val allAttachments = syncData.emails
            .filter { "${accountId}_${it.serverId}" in insertedIds && "${accountId}_${it.serverId}" !in migratedEasIds }
            .flatMap { email ->
                email.attachments.map { att ->
                    AttachmentEntity(
                        emailId = "${accountId}_${email.serverId}",
                        fileReference = att.fileReference,
                        displayName = att.displayName,
                        contentType = att.contentType,
                        estimatedSize = att.estimatedSize,
                        isInline = att.isInline,
                        contentId = att.contentId
                    )
                }
            }
        if (allAttachments.isNotEmpty()) {
            attachmentDao.insertAll(allAttachments)
        }
        
        val allInserted = toInsert + finalFiltered.filter { it.id in migratedEasIds }
        
        return NewEmailsBatchResult(
            allInserted = allInserted,
            draftReplacements = draftReplacements,
            insertedCount = insertedCount
        )
    }
    
    /**
     * Восстанавливает сохранённые тела черновиков после full resync.
     */
    private suspend fun restoreSavedDraftBodies(
        savedDraftBodies: List<SavedDraftBody>,
        allInserted: List<EmailEntity>,
        folderId: String
    ) {
        if (savedDraftBodies.isEmpty() || allInserted.isEmpty()) return
        
        val usedSaved = mutableSetOf<Int>()
        for (email in allInserted) {
            val idx = savedDraftBodies.withIndex().indexOfFirst { (i, saved) ->
                saved.subject == email.subject && i !in usedSaved
            }
            if (idx >= 0) {
                val saved = savedDraftBodies[idx]
                usedSaved.add(idx)
                migrateDraftBodyToTarget(email.id, saved.body, saved.bodyType, saved.attachments)
                migrateDraftFieldsToTarget(email.id, saved.to, saved.cc, saved.from, saved.fromName)
                if (saved.attachments.isNotEmpty()) {
                    mergeAttachmentsToTarget(email.id, saved.attachments)
                }
            }
        }
    }
    
    /**
     * P6 FIX: Восстановление из pendingDraftRestores.
     * Покрывает случай когда Delete и Add пришли в РАЗНЫХ батчах или sync-вызовах.
     * Если старый черновик (защищённый от удаления) ещё существует в БД — удаляет его
     * после миграции данных в новый, чтобы избежать дубликатов.
     */
    private suspend fun restoreFromPendingDraftRestores(
        accountId: Long,
        allNewDrafts: List<EmailEntity>
    ) {
        if (pendingDraftRestores.isEmpty()) return
        
        val now = System.currentTimeMillis()
        pendingDraftRestores.entries.removeAll { (_, v) -> now - v.timestamp > PENDING_RESTORE_TTL_MS }
        
        for (newDraft in allNewDrafts) {
            val key = "${accountId}_${newDraft.subject}"
            val pending = pendingDraftRestores[key] ?: continue
            pendingDraftRestores.remove(key)
            
            migrateDraftBodyToTarget(newDraft.id, pending.body, pending.bodyType, pending.attachments)
            migrateDraftFieldsToTarget(newDraft.id, pending.to, pending.cc, pending.from, pending.fromName)
            if (pending.attachments.isNotEmpty()) {
                mergeAttachmentsToTarget(newDraft.id, pending.attachments)
            }
            
            // Удаляем старый «защищённый» черновик из БД (если он ещё существует
            // после того, как processServerDeletions пропустил его удаление).
            // Без этого будет дубликат: старая запись + новая (с восстановленными данными).
            if (pending.emailId != newDraft.id) {
                val oldDraft = emailDao.getEmail(pending.emailId)
                if (oldDraft != null) {
                    attachmentDao.deleteByEmail(pending.emailId)
                    emailDao.delete(pending.emailId)
                    android.util.Log.d("EmailSync", "P6: deleted old protected draft ${pending.emailId} after migrating to ${newDraft.id}")
                }
            }
            
            android.util.Log.d("EmailSync", "P6: restored pending draft data to ${newDraft.id}, subject=${newDraft.subject}")
        }
    }
    
    /**
     * P5 FIX: EAS→EAS миграция для черновиков при Delete+Add в одном батче.
     * Exchange отправляет Delete(старый)+Add(новый) при редактировании в Outlook.
     * @return множество serverIds, для которых P5 успешно мигрировал данные.
     *         processServerDeletions использует это чтобы не сохранять данные в pending повторно.
     */
    private suspend fun migrateDeleteAddDrafts(
        deletedIds: List<String>,
        allInsertedInBatch: List<EmailEntity>,
        accountId: Long
    ): Set<String> {
        if (deletedIds.isEmpty() || allInsertedInBatch.isEmpty()) return emptySet()
        
        val handledServerIds = mutableSetOf<String>()
        val usedMigrationIds = mutableSetOf<String>()
        for (deletedServerId in deletedIds) {
            val oldEmailId = "${accountId}_$deletedServerId"
            val oldDraft = emailDao.getEmail(oldEmailId) ?: continue
            if (oldDraft.body.isBlank() && !oldDraft.hasAttachments) continue
            
            val matchingNew = allInsertedInBatch.firstOrNull { newDraft ->
                newDraft.id !in usedMigrationIds &&
                newDraft.id != oldEmailId &&
                newDraft.subject == oldDraft.subject &&
                kotlin.math.abs(newDraft.dateReceived - oldDraft.dateReceived) < 300_000
            } ?: allInsertedInBatch.firstOrNull { newDraft ->
                newDraft.id !in usedMigrationIds &&
                newDraft.id != oldEmailId &&
                newDraft.subject == oldDraft.subject
            }
            
            if (matchingNew != null) {
                handledServerIds.add(deletedServerId)
                usedMigrationIds.add(matchingNew.id)
                val oldAtts = attachmentDao.getAttachmentsList(oldEmailId)
                migrateDraftBodyToTarget(matchingNew.id, oldDraft.body, oldDraft.bodyType, oldAtts)
                migrateDraftFieldsToTarget(matchingNew.id, oldDraft.to, oldDraft.cc, oldDraft.from, oldDraft.fromName)
                mergeAttachmentsToTarget(matchingNew.id, oldAtts)
            }
        }
        return handledServerIds
    }
    
    /**
     * P7 FIX: Замена старых черновиков при внешнем обновлении (Add без Delete).
     */
    private suspend fun applyDraftReplacements(
        draftReplacements: Map<String, String>,
        accountId: Long
    ) {
        if (draftReplacements.isEmpty()) return
        
        for ((newEmailId, oldEmailId) in draftReplacements) {
            val oldDraft = emailDao.getEmail(oldEmailId) ?: continue
            emailDao.getEmail(newEmailId) ?: continue
            
            val oldAtts = attachmentDao.getAttachmentsList(oldEmailId)
            if (oldDraft.body.isNotBlank()) {
                migrateDraftBodyToTarget(newEmailId, oldDraft.body, oldDraft.bodyType, oldAtts)
            }
            migrateDraftFieldsToTarget(newEmailId, oldDraft.to, oldDraft.cc, oldDraft.from, oldDraft.fromName)
            mergeAttachmentsToTarget(newEmailId, oldAtts)
            
            registerDeletedEmail(oldEmailId, context)
            attachmentDao.deleteByEmail(oldEmailId)
            emailDao.delete(oldEmailId)
        }
    }
    
    /**
     * Обработка удалённых писем с сервера.
     * @param handledDraftDeletionIds serverIds, уже обработанные P5 (migrateDeleteAddDrafts).
     *        Для них данные уже мигрированы → безопасно удалять из БД.
     */
    private suspend fun processServerDeletions(
        deletedIds: List<String>,
        accountId: Long,
        folderId: String,
        folder: FolderEntity,
        handledDraftDeletionIds: Set<String> = emptySet()
    ) {
        if (deletedIds.isEmpty()) return
        
        val now = System.currentTimeMillis()
        val confirmedDeleted = mutableSetOf<String>()
        deletedIds.forEach { serverId ->
            val emailId = "${accountId}_$serverId"
            val existingEmail = emailDao.getEmail(emailId)
            if (existingEmail != null) {
                if (folder.type == FolderType.DRAFTS && existingEmail.body.isNotBlank() && serverId !in handledDraftDeletionIds) {
                    // Orphan Delete: Add может прийти в следующем батче или sync-вызове.
                    // Сохраняем данные в pendingDraftRestores для P6 FIX.
                    val atts = attachmentDao.getAttachmentsList(emailId)
                    val localAtts = atts.filter { !it.localPath.isNullOrBlank() }
                    val key = "${accountId}_${existingEmail.subject}"
                    pendingDraftRestores[key] = PendingDraftRestore(
                        emailId = emailId,
                        subject = existingEmail.subject,
                        body = existingEmail.body,
                        bodyType = existingEmail.bodyType,
                        to = existingEmail.to,
                        cc = existingEmail.cc,
                        from = existingEmail.from,
                        fromName = existingEmail.fromName,
                        attachments = localAtts
                    )
                    
                    // ЗАЩИТА: если черновик был отредактирован пользователем в приложении
                    // в последние 60 секунд (updateDraft обновляет dateReceived на now()),
                    // НЕ удаляем из БД. Причина: EWS UpdateItem может вызвать транзитный
                    // Delete от Exchange (Delete+Add вместо Change), и Add может прийти
                    // в следующем батче. Без защиты черновик мигает в UI.
                    // Данные сохранены в pending выше — P6 восстановит при следующем Add.
                    // Запись удалится при следующем sync, если Delete повторится (dateReceived > 60 сек).
                    val recentlyEdited = now - existingEmail.dateReceived < 60_000
                    if (recentlyEdited) {
                        android.util.Log.d("EmailSync", "Draft recently edited, skipping delete: $emailId")
                        return@forEach
                    }
                }
                attachmentDao.deleteByEmail(emailId)
                emailDao.delete(emailId)
            }
            confirmedDeleted.add(emailId)
        }
        if (confirmedDeleted.isNotEmpty()) {
            confirmDeletions(confirmedDeleted, context)
        }
    }
    
    /**
     * Обработка изменённых писем с сервера (read/flag/body).
     */
    private suspend fun processServerChanges(
        syncData: SyncResponse,
        accountId: Long,
        folderId: String,
        folder: FolderEntity,
        serverDraftIdsInFullResync: MutableSet<String>?,
        serverSentIdsInFullResync: MutableSet<String>?
    ) {
        if (syncData.changedEmails.isEmpty()) return
        
        // RECONCILE: changedEmails тоже содержат serverIds существующих на сервере
        val changedIds = syncData.changedEmails.map { "${accountId}_${it.serverId}" }
        serverDraftIdsInFullResync?.addAll(changedIds)
        serverSentIdsInFullResync?.addAll(changedIds)
        
        syncData.changedEmails.forEach { change ->
            val emailId = "${accountId}_${change.serverId}"
            change.read?.let { emailDao.updateReadStatus(emailId, it) }
            change.flagged?.let { emailDao.updateFlagStatus(emailId, it) }
            
            if (folder.type == FolderType.DRAFTS && !change.body.isNullOrBlank()) {
                val existing = emailDao.getEmail(emailId)
                if (existing != null) {
                    val existingBody = existing.body
                    if (existingBody.isBlank()) {
                        emailDao.updateBody(emailId, change.body)
                    } else if (existingBody.contains("data:") && !change.body.contains("data:")) {
                        val atts = attachmentDao.getAttachmentsList(emailId)
                        val merged = mergeDraftBody(change.body, existingBody, atts)
                        emailDao.updateBody(emailId, merged)
                    } else {
                        emailDao.updateBody(emailId, change.body)
                    }
                    change.bodyType?.let { emailDao.updateBodyType(emailId, it) }
                    val newPreview = stripHtml(change.body).take(150).replace("\n", " ").trim()
                    emailDao.updatePreview(emailId, newPreview)
                }
            }
        }
    }
    
    /**
     * RECONCILE для черновиков после full resync.
     * Удаляет локальные записи, которых нет на сервере. Переносит данные.
     */
    private suspend fun reconcileDraftsAfterFullResync(
        serverDraftIds: Set<String>,
        folderId: String,
        accountId: Long
    ) {
        val now = System.currentTimeMillis()
        val localDrafts = emailDao.getEmailsByFolderList(folderId)
        val orphanDrafts = localDrafts.filter { draft ->
            draft.id !in serverDraftIds &&
            !draft.serverId.startsWith("local_draft_")
        }
        for (orphan in orphanDrafts) {
            // ЗАЩИТА: если черновик отредактирован пользователем < 60 сек назад,
            // пропускаем — он ещё «транзитный» (EWS UpdateItem → EAS Delete→Add).
            // Следующий sync reconcile подберёт его когда Add уже будет в serverDraftIds.
            if (now - orphan.dateReceived < 60_000) {
                android.util.Log.d("EmailSync", "Reconcile: skipping recently edited orphan ${orphan.id}")
                continue
            }
            
            // Чистим pending если orphan был в pendingDraftRestores
            pendingDraftRestores.entries.removeAll { (_, v) -> v.emailId == orphan.id }
            
            if (orphan.body.isNotBlank()) {
                val matchingNew = localDrafts.firstOrNull { newDraft ->
                    newDraft.id in serverDraftIds &&
                    newDraft.subject == orphan.subject
                }
                if (matchingNew != null) {
                    // Body: мерж (без обновления bodyType в else-if — сохраняем оригинальное поведение)
                    val newBody = emailDao.getEmail(matchingNew.id)?.body ?: ""
                    if (newBody.isBlank()) {
                        emailDao.updateBody(matchingNew.id, orphan.body)
                        if (orphan.bodyType != 0) emailDao.updateBodyType(matchingNew.id, orphan.bodyType)
                    } else if (orphan.body.contains("data:") && !newBody.contains("data:")) {
                        val mergeAtts = attachmentDao.getAttachmentsList(orphan.id)
                        val merged = mergeDraftBody(newBody, orphan.body, mergeAtts)
                        emailDao.updateBody(matchingNew.id, merged)
                    }
                    migrateDraftFieldsToTarget(matchingNew.id, orphan.to, orphan.cc, orphan.from, orphan.fromName)
                    val orphanAtts = attachmentDao.getAttachmentsList(orphan.id)
                    mergeAttachmentsToTarget(matchingNew.id, orphanAtts)
                }
            }
            attachmentDao.deleteByEmail(orphan.id)
            emailDao.delete(orphan.id)
        }
    }
    
    /**
     * RECONCILE для Отправленных после full resync.
     */
    private suspend fun reconcileSentAfterFullResync(
        serverSentIds: Set<String>,
        folderId: String
    ) {
        val localSentEmails = emailDao.getEmailsByFolderList(folderId)
        val orphanSent = localSentEmails.filter { it.id !in serverSentIds }
        for (orphan in orphanSent) {
            attachmentDao.deleteByEmail(orphan.id)
            emailDao.delete(orphan.id)
        }
    }
    
    /**
     * Синхронизация писем через IMAP
     */
    suspend fun syncEmailsImap(accountId: Long, folderId: String): EasResult<Int> {
        val client = accountRepo.createImapClient(accountId) 
            ?: return EasResult.Error("Не удалось создать IMAP клиент")
        val folder = folderDao.getFolder(folderId) 
            ?: return EasResult.Error("Папка не найдена")
        
        return try {
            client.connect().getOrThrow()
            val result = client.getEmails(folder.serverId)
            
            result.fold(
                onSuccess = { emails ->
                    emailDao.insertAll(emails)
                    val unreadCount = emailDao.getUnreadCount(folderId)
                    folderDao.updateUnreadCount(folderId, unreadCount)
                    EasResult.Success(emails.size)
                },
                onFailure = { 
                    EasResult.Error(it.message ?: "Ошибка получения писем") 
                }
            )
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка IMAP")
        } finally {
            try { client.disconnect() } catch (_: Exception) { }
        }
    }
    
    /**
     * Синхронизация писем через POP3
     */
    suspend fun syncEmailsPop3(accountId: Long, folderId: String): EasResult<Int> {
        val client = accountRepo.createPop3Client(accountId) 
            ?: return EasResult.Error("Не удалось создать POP3 клиент")
        
        return try {
            client.connect().getOrThrow()
            val result = client.getEmails(folderId)
            
            result.fold(
                onSuccess = { emails ->
                    emailDao.insertAll(emails)
                    val unreadCount = emailDao.getUnreadCount(folderId)
                    folderDao.updateUnreadCount(folderId, unreadCount)
                    EasResult.Success(emails.size)
                },
                onFailure = { EasResult.Error(it.message ?: "Ошибка получения писем") }
            )
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка POP3")
        } finally {
            try { client.disconnect() } catch (_: Exception) { }
        }
    }
    
    // === Утилитные методы ===
    
    private fun parseEwsDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(dateStr.substringBefore("Z"))?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
    
   private fun parseDate(dateStr: String): Long {
    if (dateStr.isEmpty()) {
        return 0L
    }
    
    return try {
        val cleaned = dateStr.replace("Z", "+0000")
        
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = sdf.parse(cleaned)
                if (date != null) {
                    return date.time
                }
            } catch (e: Exception) {
                // Пробуем следующий формат
            }
        }
                0L
    } catch (e: Exception) {
        0L
    }
}
    
    private fun extractName(emailField: String): String {
        if (emailField.isBlank()) return ""
        
        // Формат Exchange: "/O=.../CN=Имя Фамилия"
        val cnMatch = CN_REGEX.find(emailField)
        if (cnMatch != null) {
            return cnMatch.groupValues[1].trim()
        }
        
        // Формат "Имя Фамилия <email@domain.com>"
        val nameMatch = NAME_BEFORE_BRACKET_REGEX.find(emailField)
        if (nameMatch != null) {
            return nameMatch.groupValues[1].trim()
        }
        
        // Если содержит @, но нет угловых скобок - вернуть пустую строку
        if (emailField.contains("@") && !emailField.contains("<")) {
            return ""
        }
        
        return emailField.trim()
    }
    
    /**
     * Мержит серверный body (с текстовыми изменениями из Outlook) и локальные data: URL.
     *
     * Стратегия: берём НОВЫЙ body (текст из Outlook) + заменяем cid: на data: URL:
     * 1. Через contentId → localPath → файл → base64 → data: URL
     * 2. Fallback: извлекаем data: URL из старого body по порядку
     */
    private fun mergeDraftBody(
        newBody: String,
        oldBody: String,
        localAttachments: List<AttachmentEntity>
    ): String {
        if (newBody.isBlank()) return oldBody
        if (!oldBody.contains("data:")) return newBody
        if (!newBody.contains("cid:")) return newBody

        var mergedBody = newBody

        // Стратегия 1: contentId → localPath → file → data: URL
        val inlineAtts = localAttachments.filter {
            it.isInline && !it.contentId.isNullOrBlank() && !it.localPath.isNullOrBlank()
        }
        for (att in inlineAtts) {
            val localFile = java.io.File(att.localPath!!)
            if (!localFile.exists()) continue
            try {
                val bytes = localFile.readBytes()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                val dataUrl = "data:$mimeType;base64,$base64"
                mergedBody = mergedBody.replace("cid:${att.contentId}", dataUrl)
            } catch (e: Exception) {
                android.util.Log.w("EmailSyncService", "mergeDraftBody: failed to read ${att.localPath}: ${e.message}")
            }
        }

        // Стратегия 2 (fallback): позиционный мэтч из data: URL старого body
        if (mergedBody.contains("cid:")) {
            val oldDataUrls = """src\s*=\s*"(data:[^"]+)"""".toRegex()
                .findAll(oldBody).map { it.groupValues[1] }.toList()
            if (oldDataUrls.isNotEmpty()) {
                val cidPattern = """cid:[^"'\s)]+""".toRegex()
                val remainingCids = cidPattern.findAll(mergedBody).map { it.value }.toList()
                for ((i, cid) in remainingCids.withIndex()) {
                    if (i < oldDataUrls.size) {
                        mergedBody = mergedBody.replaceFirst(cid, oldDataUrls[i])
                    }
                }
            }
        }

        return mergedBody
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

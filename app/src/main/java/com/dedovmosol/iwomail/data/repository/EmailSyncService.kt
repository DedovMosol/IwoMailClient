package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasDraft
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.SyncResponse
import com.dedovmosol.iwomail.util.extractName
import com.dedovmosol.iwomail.util.stripHtml

private val DATA_URL_EXTRACT_REGEX = Regex("""data:[^;]+;base64,[A-Za-z0-9+/=]+""")
private val IMG_ORDINAL_REGEX = Regex("""^img(\d+)_""")

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
    private val accountRepo: AccountRepository,
    private val database: MailDatabase,
    private val onNameResolved: ((email: String, name: String) -> Unit)? = null
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
    
    private data class ContentDedupResult(
        val filtered: List<EmailEntity>,
        val draftReplacements: Map<String, String>
    )
    
    companion object {
        // КРИТИЧНО: activeSyncs ОБЯЗАН быть общим (companion object / static) для ВСЕХ экземпляров!
        // SyncWorker и PushService создают РАЗНЫЕ экземпляры MailRepository → EmailSyncService.
        // Без общего activeSyncs они могут одновременно синхронизировать одну папку,
        // вызывая дублирование данных, гонки SyncKey и прыжки счётчиков.
        private val activeSyncs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
        
        // Защита от "воскрешения" удалённых писем (TTL 5 мин).
        // ОБЯЗАН быть в companion object — SyncWorker и PushService создают РАЗНЫЕ экземпляры!
        private val deletedTracker = com.dedovmosol.iwomail.util.DeletedIdsTracker(
            prefsName = "email_deleted_ids",
            prefsKey = "deleted_email_ids",
            maxSize = 500,
            ttlMs = 300_000L
        )
        
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
        
        private val DATE_FORMATS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "dd MMM yyyy HH:mm:ss Z"
        )

        private val threadLocalDateFormats = ThreadLocal.withInitial {
            DATE_FORMATS.map { format ->
                java.text.SimpleDateFormat(format, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
            }
        }

        private val tzColonRegex = Regex("([+-])(\\d{2}):(\\d{2})$")
        
        fun initDeletedTracker(context: Context) = deletedTracker.init(context)

        fun registerDeletedEmail(emailId: String, context: Context? = null) =
            deletedTracker.register(emailId, context)

        fun isDeletedByUser(emailId: String): Boolean =
            deletedTracker.isTracked(emailId)

        fun confirmDeletions(confirmedDeletedIds: Set<String>, context: Context? = null) =
            deletedTracker.confirmDeleted(confirmedDeletedIds, context)
    }
    
    init {
        deletedTracker.init(context)
    }
    
    /**
     * Проверка, идёт ли сейчас синхронизация указанной папки
     */
    fun isFolderSyncing(folderId: String): Boolean {
        return activeSyncs.containsKey(folderId)
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
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
                false // Не загружаем
            } else {
                true // Действительно оффлайн-черновик — загружаем
            }
        }
        
        // Проверяем draftMode: если LOCAL — НЕ загружаем на сервер
        val account = accountRepo.getAccount(accountId)
        val draftMode = try {
            DraftMode.valueOf(account?.draftMode ?: DraftMode.SERVER.name)
        } catch (_: Exception) { DraftMode.SERVER }
        
        if (trulyLocalDrafts.isNotEmpty() && draftMode != DraftMode.LOCAL) {
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
                                    database.withTransaction {
                                        emailDao.delete(draft.id)
                                        emailDao.insert(updatedEmail)
                                    }
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
     * Полная ресинхронизация папки Отправленные
     */
    suspend fun syncSentFull(accountId: Long, folderId: String): EasResult<Int> {
        val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val existing = activeSyncs.putIfAbsent(folderId, signal)
        if (existing != null) {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) { existing.await() }
            val retrySignal = kotlinx.coroutines.CompletableDeferred<Unit>()
            if (activeSyncs.putIfAbsent(folderId, retrySignal) != null) {
                return EasResult.Error("Синхронизация уже выполняется")
            }
        }
        
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
                    
                    if (serverEmailIds.size < 50_000) {
                        result.data.emails.forEach { email ->
                            serverEmailIds.add(email.serverId)
                        }
                    }
                    
                    if (result.data.emails.isNotEmpty()) {
                        val emailEntities = result.data.emails.mapNotNull { email ->
                            val emailId = "${accountId}_${email.serverId}"
                            if (isDeletedByUser(emailId)) return@mapNotNull null
                            val parsedDate = parseDate(email.dateReceived)
                            val effectiveDate = if (parsedDate > 0L) parsedDate else 0L
                            if (effectiveDate <= 0L) return@mapNotNull null
                            val resolvedName = extractName(email.from)
                            if (resolvedName.isNotBlank() && !resolvedName.contains("@")) {
                                onNameResolved?.invoke(email.from, resolvedName)
                            }
                            EmailEntity(
                                id = emailId,
                                accountId = accountId,
                                folderId = folderId,
                                serverId = email.serverId,
                                from = email.from,
                                fromName = resolvedName,
                                to = email.to,
                                cc = email.cc,
                                subject = email.subject,
                                preview = stripHtml(email.body).take(150).replace("\n", " ").trim(),
                                body = "",
                                bodyType = email.bodyType,
                                dateReceived = effectiveDate,
                                read = email.read,
                                flagged = email.flagged,
                                importance = email.importance,
                                hasAttachments = email.attachments.isNotEmpty()
                            )
                        }
                        
                        val emailIdsToInsert = emailEntities.map { it.id }
                        val alreadyExistingIds = emailIdsToInsert.chunked(500).flatMap { emailDao.getExistingIds(it) }.toSet()
                        
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
            if (serverEmailIds.size < 50_000) {
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
            } else {
                android.util.Log.w("EmailSyncService",
                    "syncSentFull: Too many emails (${serverEmailIds.size}). Skipping orphan deletion.")
            }
            folderDao.updateSyncKey(folderId, syncKey)
        } else {
            android.util.Log.w("EmailSyncService",
                "syncSentFull: Incomplete sync (${serverEmailIds.size} emails). Skipping syncKey save.")
        }
        
        emailDao.deleteGhostEmails(folderId)
        
        val totalCount = emailDao.getCountByFolder(folderId)
        val unreadCount = emailDao.getUnreadCount(folderId)
        folderDao.updateCounts(folderId, unreadCount, totalCount)
        
        return EasResult.Success(serverEmailIds.size)
        } finally {
            activeSyncs.remove(folderId)?.complete(Unit)
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
        database.withTransaction {
            attachmentDao.deleteByEmail(targetEmailId)
            attachmentDao.insertAll(mergedSet)
        }
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
            val merged = mergeDraftBody(currentBody, sourceAttachments, sourceBody)
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
        
        deletedTracker.init(context)
        
        if (retryCount == 0) {
            val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
            val existing = activeSyncs.putIfAbsent(folderId, signal)
            if (existing != null) {
                kotlinx.coroutines.withTimeoutOrNull(30_000L) { existing.await() }
                val retrySignal = kotlinx.coroutines.CompletableDeferred<Unit>()
                if (activeSyncs.putIfAbsent(folderId, retrySignal) != null) {
                    return EasResult.Error("Синхронизация уже выполняется")
                }
            }
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
            
            // RECONCILE MODE: при full resync НЕ используем deleteByFolder ни для одной папки.
            // MS-ASCMD: ServerId стабильность гарантирована только для EAS 16.1.
            // На EAS 12.1 (Exchange 2007 SP1) ServerIds МОГУТ измениться после SyncKey=0 reset.
            // Вместо clear+re-populate используем reconcile: собираем серверные ID,
            // после sync loop удаляем orphans с safety guard (30%).
            val isDraftReconcile = isFullResync && folder.type == FolderType.DRAFTS
            val serverDraftIdsInFullResync = if (isDraftReconcile) mutableSetOf<String>() else null
            
            val isSentReconcile = isFullResync && folder.type == FolderType.SENT_ITEMS
            val serverSentIdsInFullResync = if (isSentReconcile) mutableSetOf<String>() else null
            
            val isGenericReconcile = isFullResync && folder.type != FolderType.DRAFTS && folder.type != FolderType.SENT_ITEMS
            val serverGenericIdsInFullResync = if (isGenericReconcile) mutableSetOf<String>() else null
            
            if (isFullResync && folder.type == FolderType.DRAFTS) {
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
            }
            var newEmailsCount = 0
            
            val windowSize = 50  // MS-ASCMD: "values >100 cause larger responses, more susceptible to communication errors"; 50×10KB≈500KB/batch
            val includeMime = folder.type == FolderType.SENT_ITEMS && !isFullResync
            
            if (syncKey == "0") {
                when (val result = client.sync(folder.serverId, "0", windowSize, false)) {
                    is EasResult.Success -> {
                        if (result.data.status == 12 || result.data.status == 3) {
                            folderDao.updateSyncKey(folderId, "0")
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
            val maxSyncDurationMs = if (isFullResync) 280_000L else 55_000L  // Должен быть < внешнего timeout (SyncWorker: 300s/60s) для сохранения SyncKey
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
                            folderDao.updateSyncKey(folderId, "0")
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
                                skipRecentEditCheck, serverDraftIdsInFullResync, serverSentIdsInFullResync,
                                serverGenericIdsInFullResync
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
                        processServerChanges(result.data, accountId, folderId, folder, serverDraftIdsInFullResync, serverSentIdsInFullResync, serverGenericIdsInFullResync)
                        
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
                            folderDao.updateSyncKey(folderId, "0")
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
            
            if (serverGenericIdsInFullResync != null && serverGenericIdsInFullResync.isNotEmpty() && syncLoopCompletedFully) {
                reconcileGenericFolderAfterFullResync(serverGenericIdsInFullResync, folderId)
            }
            
            // Удаляем phantom-записи (dateReceived <= 1L), оставшиеся от предыдущих версий.
            // Такие записи появлялись когда Exchange 2007 SP1 не возвращал DateReceived,
            // и parseDate() ставил fallback 0L → safeDate 1L (01.01.1970).
            // Теперь такие записи фильтруются при вставке; здесь чистим legacy-данные.
            // НЕ трогаем черновики: EWS-мигрированные могут иметь dateReceived=1L как fallback.
            if (folder.type != FolderType.DRAFTS) {
                emailDao.deleteGhostEmails(folderId)
            }
            
            val totalCount = emailDao.getCountByFolder(folderId)
            val unreadCount = emailDao.getUnreadCount(folderId)
            folderDao.updateCounts(folderId, unreadCount, totalCount)
            return EasResult.Success(newEmailsCount)
        } finally {
            activeSyncs.remove(folderId)?.complete(Unit)
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
        serverSentIdsInFullResync: MutableSet<String>?,
        serverGenericIdsInFullResync: MutableSet<String>?
    ): NewEmailsBatchResult {
        if (syncData.emails.isEmpty()) {
            return NewEmailsBatchResult(emptyList(), emptyMap(), 0)
        }
        
        val allIds = syncData.emails.map { "${accountId}_${it.serverId}" }
        val existingIds = allIds.chunked(500).flatMap { emailDao.getExistingIds(it) }
        val existingEmailsMap = if (existingIds.isNotEmpty()) {
            existingIds.chunked(500).flatMap { emailDao.getEmailsByIds(it) }.associateBy { it.id }
        } else {
            emptyMap()
        }
        
        val emailEntities = buildEmailEntities(syncData, accountId, folderId, folder, existingEmailsMap)
        
        if (folder.type == FolderType.DRAFTS && existingIds.isNotEmpty()) {
            updateExistingDraftBodies(syncData, accountId, existingIds, existingEmailsMap)
        }
        
        val newEmails = emailEntities.filter { it.id !in existingIds }
        
        val filteredByServerId = deduplicateByServerId(newEmails, accountId)
        
        val isDrafts = folder.type == FolderType.DRAFTS
        val contentResult = deduplicateByContent(filteredByServerId, folderId, isDrafts, syncData.deletedIds.toSet())
        
        val filteredByRecentDelete = contentResult.filtered.filter { email ->
            val blocked = isDeletedByUser(email.id)
            if (blocked && isDrafts) {
                android.util.Log.w("EmailSync",
                    "Draft blocked by deletedEmailIds: id=${email.id}, subj='${email.subject}'")
            }
            !blocked
        }
        
        val finalFiltered = filterDraftRaceCondition(
            filteredByRecentDelete, existingEmailsMap, isDrafts, skipRecentEditCheck
        )
        
        val migratedEasIds = insertBatchInTransaction(finalFiltered, folder, syncData, accountId, folderId)
        
        val batchServerIds = syncData.emails.map { "${accountId}_${it.serverId}" }
        serverDraftIdsInFullResync?.addAll(batchServerIds)
        serverSentIdsInFullResync?.addAll(batchServerIds)
        serverGenericIdsInFullResync?.addAll(batchServerIds)
        
        val toInsert = if (migratedEasIds.isNotEmpty()) {
            finalFiltered.filter { it.id !in migratedEasIds }
        } else {
            finalFiltered
        }
        val allInserted = toInsert + finalFiltered.filter { it.id in migratedEasIds }
        
        return NewEmailsBatchResult(
            allInserted = allInserted,
            draftReplacements = contentResult.draftReplacements,
            insertedCount = toInsert.size + migratedEasIds.size
        )
    }
    
    private fun buildEmailEntities(
        syncData: SyncResponse,
        accountId: Long,
        folderId: String,
        folder: FolderEntity,
        existingEmailsMap: Map<String, EmailEntity>
    ): List<EmailEntity> = syncData.emails.mapNotNull { email ->
        val emailId = "${accountId}_${email.serverId}"
        val existingEmail = existingEmailsMap[emailId]
        val parsedDate = parseDate(email.dateReceived)
        // MS-ASEMAIL: DateReceived обязателен для валидных писем.
        // Exchange 2007 SP1 может отдавать phantom-записи без даты — пропускаем.
        val effectiveDate = if (parsedDate > 0L) parsedDate
            else existingEmail?.dateReceived?.takeIf { it > 1L }
            ?: 0L
        if (effectiveDate <= 0L) {
            if (email.dateReceived.isNotBlank()) {
                android.util.Log.w("EmailSync",
                    "Skipping email with unparseable date: serverId=${email.serverId}, " +
                    "dateReceived='${email.dateReceived}', from='${email.from}'")
            }
            return@mapNotNull null
        }
        val resolvedName = extractName(email.from)
        if (resolvedName.isNotBlank() && !resolvedName.contains("@")) {
            onNameResolved?.invoke(email.from, resolvedName)
        }
        EmailEntity(
            id = emailId,
            accountId = accountId,
            folderId = folderId,
            serverId = email.serverId,
            from = email.from,
            fromName = resolvedName,
            to = email.to,
            cc = email.cc,
            subject = email.subject,
            preview = stripHtml(email.body).take(150).replace("\n", " ").trim(),
            body = existingEmail?.body?.takeIf { it.isNotBlank() }
                ?: if (folder.type == FolderType.DRAFTS && email.body.isNotBlank()) email.body else "",
            bodyType = email.bodyType,
            dateReceived = effectiveDate,
            read = email.read,
            flagged = email.flagged,
            importance = email.importance,
            hasAttachments = email.attachments.isNotEmpty()
        )
    }
    
    private suspend fun updateExistingDraftBodies(
        syncData: SyncResponse,
        accountId: Long,
        existingIds: List<String>,
        existingEmailsMap: Map<String, EmailEntity>
    ) {
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
                val merged = mergeDraftBody(serverEmail.body, atts, existingBody)
                emailDao.updateBody(existingId, merged)
            } else if (!existingBody.contains("data:")) {
                emailDao.updateBody(existingId, serverEmail.body)
            }
            val newPreview = stripHtml(serverEmail.body).take(150).replace("\n", " ").trim()
            emailDao.updatePreview(existingId, newPreview)
        }
    }
    
    private suspend fun deduplicateByServerId(
        newEmails: List<EmailEntity>,
        accountId: Long
    ): List<EmailEntity> {
        if (newEmails.isEmpty()) return emptyList()
        val serverIds = newEmails.map { it.serverId }
        val existingByServerId = serverIds.chunked(500)
            .flatMap { emailDao.getExistingServerIds(accountId, it) }
        return newEmails.filter { it.serverId !in existingByServerId }
    }
    
    /**
     * Content dedup — защита от дубликатов при full resync (Status=3 recovery)
     * и при serverId-смене (Exchange Delete+Add паттерн).
     * Для черновиков: fuzzy matching по subject-prefix + draftReplacements (EWS→EAS миграция).
     * Для остальных: строгий матч subject|from + dateReceived ±5 сек.
     */
    private suspend fun deduplicateByContent(
        candidates: List<EmailEntity>,
        folderId: String,
        isDrafts: Boolean,
        deletedInBatch: Set<String>
    ): ContentDedupResult {
        if (candidates.isEmpty()) return ContentDedupResult(candidates, emptyMap())
        
        val dedupInfos = emailDao.getDedupInfoByFolder(folderId)
        val dedupCandidates = if (isDrafts) {
            dedupInfos.filter { e ->
                !(e.serverId.length > 30 && !e.serverId.contains(":") && !e.serverId.startsWith("local_draft_"))
                && e.serverId !in deletedInBatch
            }
        } else {
            dedupInfos
        }
        
        val contentIndex = dedupCandidates.groupBy { "${it.subject}|${it.from}" }
        val duplicateIds = mutableSetOf<String>()
        val draftReplacements = mutableMapOf<String, String>()
        
        for (newEmail in candidates) {
            val key = "${newEmail.subject}|${newEmail.from}"
            var matched = contentIndex[key]
            
            if (matched == null && isDrafts) {
                val subjectPrefix = "${newEmail.subject}|"
                for ((existingKey, existingCandidates) in contentIndex) {
                    if (existingKey.startsWith(subjectPrefix)) {
                        val filtered = existingCandidates.filter { existing ->
                            kotlin.math.abs(existing.dateReceived - newEmail.dateReceived) < 5000
                        }
                        if (filtered.isNotEmpty()) {
                            matched = filtered
                            break
                        }
                    }
                }
            }
            
            if (matched != null) {
                val matchingCandidate = matched.firstOrNull { existing ->
                    kotlin.math.abs(existing.dateReceived - newEmail.dateReceived) < 5000
                }
                if (matchingCandidate != null) {
                    if (isDrafts && matchingCandidate.serverId != newEmail.serverId) {
                        draftReplacements[newEmail.id] = matchingCandidate.id
                    } else {
                        duplicateIds.add(newEmail.id)
                    }
                }
            }
        }
        
        return ContentDedupResult(
            filtered = candidates.filter { it.id !in duplicateIds },
            draftReplacements = draftReplacements.toMap()
        )
    }
    
    private fun filterDraftRaceCondition(
        emails: List<EmailEntity>,
        existingEmailsMap: Map<String, EmailEntity>,
        isDrafts: Boolean,
        skipRecentEditCheck: Boolean
    ): List<EmailEntity> {
        if (!isDrafts || skipRecentEditCheck) return emails
        val syncTime = System.currentTimeMillis()
        val recentEditThreshold = 10_000L
        return emails.filter { email ->
            val existingEmail = existingEmailsMap[email.id]
            if (existingEmail != null) {
                val timeSinceLocalEdit = syncTime - existingEmail.dateReceived
                if (timeSinceLocalEdit < recentEditThreshold) {
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
    }
    
    /**
     * EWS→EAS миграция для черновиков + вставка батча — атомарная транзакция.
     * @return set of migrated EAS email IDs (для которых body/attachments взяты из EWS-записи)
     */
    private suspend fun insertBatchInTransaction(
        finalFiltered: List<EmailEntity>,
        folder: FolderEntity,
        syncData: SyncResponse,
        accountId: Long,
        folderId: String
    ): Set<String> {
        val migratedEasIds = mutableSetOf<String>()
        database.withTransaction {
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
            
            val toInsert = if (migratedEasIds.isNotEmpty()) {
                finalFiltered.filter { it.id !in migratedEasIds }
            } else {
                finalFiltered
            }
            if (toInsert.isNotEmpty()) {
                emailDao.insertAllIgnore(toInsert)
            }
            
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
        }
        return migratedEasIds
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
                if (folder.type == FolderType.DRAFTS) {
                    android.util.Log.w("EmailSync",
                        "Deleting draft from Sync: id=$emailId, subj='${existingEmail.subject}', " +
                        "handled=${serverId in handledDraftDeletionIds}")
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
        serverSentIdsInFullResync: MutableSet<String>?,
        serverGenericIdsInFullResync: MutableSet<String>?
    ) {
        if (syncData.changedEmails.isEmpty()) return
        
        val changedIds = syncData.changedEmails.map { "${accountId}_${it.serverId}" }
        serverDraftIdsInFullResync?.addAll(changedIds)
        serverSentIdsInFullResync?.addAll(changedIds)
        serverGenericIdsInFullResync?.addAll(changedIds)
        
        val draftEmailIdsToRefreshAtts = mutableListOf<Pair<String, String>>()
        
        syncData.changedEmails.forEach { change ->
            val emailId = "${accountId}_${change.serverId}"
            change.read?.let { emailDao.updateReadStatus(emailId, it) }
            change.flagged?.let { emailDao.updateFlagStatus(emailId, it) }
            
            if (folder.type == FolderType.DRAFTS) {
                if (!change.body.isNullOrBlank()) {
                    val existing = emailDao.getEmail(emailId)
                    if (existing != null) {
                        val existingBody = existing.body
                        if (existingBody.isBlank()) {
                            emailDao.updateBody(emailId, change.body)
                            change.bodyType?.let { emailDao.updateBodyType(emailId, it) }
                            val newPreview = stripHtml(change.body).take(150).replace("\n", " ").trim()
                            emailDao.updatePreview(emailId, newPreview)
                        } else if (existingBody.contains("data:") && !change.body.contains("data:")) {
                            val atts = attachmentDao.getAttachmentsList(emailId)
                            val merged = mergeDraftBody(change.body, atts, existingBody)
                            emailDao.updateBody(emailId, merged)
                            change.bodyType?.let { emailDao.updateBodyType(emailId, it) }
                            val newPreview = stripHtml(change.body).take(150).replace("\n", " ").trim()
                            emailDao.updatePreview(emailId, newPreview)
                        } else if (!change.body.contains("cid:") || !existingBody.contains("data:")) {
                            emailDao.updateBody(emailId, change.body)
                            change.bodyType?.let { emailDao.updateBodyType(emailId, it) }
                            val newPreview = stripHtml(change.body).take(150).replace("\n", " ").trim()
                            emailDao.updatePreview(emailId, newPreview)
                        }
                    }
                }
                if (change.attachments.isNotEmpty()) {
                    reconcileAttachments(emailId, change.attachments)
                } else {
                    draftEmailIdsToRefreshAtts.add(emailId to change.serverId)
                }
            }
        }
        
        if (draftEmailIdsToRefreshAtts.isNotEmpty()) {
            syncDraftAttachmentsFromServer(accountId, folderId, draftEmailIdsToRefreshAtts)
        }
    }
    
    /**
     * Подтягивает новые вложения с сервера для изменённых черновиков.
     * Exchange 2007 SP1 / EAS 12.1: использует ItemOperations Fetch
     * для получения актуального списка вложений (MS-ASAIRS 2.2.2.7).
     */
    private suspend fun syncDraftAttachmentsFromServer(
        accountId: Long,
        folderId: String,
        drafts: List<Pair<String, String>>
    ) {
        try {
            val client = accountRepo.createEasClient(accountId) ?: return
            val folderServerId = folderId.substringAfter("_")
            
            for ((emailId, serverId) in drafts) {
                if (serverId.contains("=") && !serverId.contains(":")) continue
                val attResult = client.fetchAttachmentMetadata(folderServerId, serverId)
                if (attResult is EasResult.Success) {
                    reconcileAttachments(emailId, attResult.data)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EmailSync", "syncDraftAttachmentsFromServer failed: ${e.message}")
        }
    }
    
    /**
     * Атомарно синхронизирует локальные вложения с серверными данными.
     * Транзакция гарантирует отсутствие гонок с refreshAttachmentMetadata из UI.
     * HTTP-вызов выполняется ДО входа в транзакцию — lock не держится во время сети.
     * File.delete() выполняется ПОСЛЕ commit — необратимая IO-операция не должна
     * выполняться внутри транзакции (при rollback файл не восстановить).
     *
     * Internal: также используется из EmailOperationsService.refreshAttachmentMetadata (DRY).
     */
    internal suspend fun reconcileAttachments(
        emailId: String,
        serverAttachments: List<com.dedovmosol.iwomail.eas.EasAttachment>
    ) {
        val serverNames = serverAttachments.map { it.displayName }.toSet()
        
        val filesToDelete = database.withTransaction {
            val localAtts = attachmentDao.getAttachmentsList(emailId)
            
            val staleAtts = localAtts.filter { it.displayName !in serverNames }
            val pathsToDelete = staleAtts.mapNotNull { it.localPath }
            val staleIds = staleAtts.map { it.id }
            if (staleIds.isNotEmpty()) {
                attachmentDao.deleteByIds(staleIds)
            }
            
            val localByName = localAtts.associateBy { it.displayName }
            val newAtts = mutableListOf<AttachmentEntity>()
            for (serverAtt in serverAttachments) {
                val existing = localByName[serverAtt.displayName]
                if (existing != null) {
                    if (serverAtt.fileReference.isNotEmpty()) {
                        attachmentDao.updateFileReference(emailId, serverAtt.displayName, serverAtt.fileReference)
                    }
                    if (!serverAtt.contentId.isNullOrBlank() && existing.contentId != serverAtt.contentId) {
                        attachmentDao.updateContentId(existing.id, serverAtt.contentId, true)
                    }
                } else if (serverAtt.fileReference.isNotEmpty() || !serverAtt.contentId.isNullOrBlank()) {
                    newAtts.add(AttachmentEntity(
                        emailId = emailId,
                        fileReference = serverAtt.fileReference,
                        displayName = serverAtt.displayName,
                        contentType = serverAtt.contentType,
                        estimatedSize = serverAtt.estimatedSize,
                        isInline = serverAtt.isInline || !serverAtt.contentId.isNullOrBlank(),
                        contentId = serverAtt.contentId
                    ))
                }
            }
            if (newAtts.isNotEmpty()) {
                attachmentDao.insertAll(newAtts)
            }
            
            pathsToDelete
        }
        
        for (path in filesToDelete) {
            try { java.io.File(path).delete() } catch (_: Exception) {}
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
        if (orphanDrafts.isEmpty()) return
        if (localDrafts.isNotEmpty() && orphanDrafts.size.toFloat() / localDrafts.size >= 0.3f) {
            android.util.Log.w("EmailSync",
                "reconcileDrafts: ${orphanDrafts.size}/${localDrafts.size} orphaned (>=30%). " +
                "Server returned incomplete data — skipping cleanup to prevent data loss.")
            return
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
                        val merged = mergeDraftBody(newBody, mergeAtts, orphan.body)
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
        if (orphanSent.isEmpty()) return
        // Safety guard: если orphans > 30% от локальных, сервер мог вернуть
        // неполные данные — НЕ удаляем чтобы не потерять письма.
        if (localSentEmails.isNotEmpty() && orphanSent.size.toFloat() / localSentEmails.size >= 0.3f) {
            android.util.Log.w("EmailSyncService",
                "reconcileSent: ${orphanSent.size}/${localSentEmails.size} orphaned (>30%). Skipping cleanup.")
            return
        }
        for (orphan in orphanSent) {
            attachmentDao.deleteByEmail(orphan.id)
            emailDao.delete(orphan.id)
        }
        android.util.Log.d("EmailSyncService", "reconcileSent: Removed ${orphanSent.size} orphaned sent emails")
    }
    
    /**
     * RECONCILE для INBOX и прочих папок после full resync (Status=3 recovery).
     * MS-ASCMD: ServerId стабильность НЕ гарантирована для item-level на EAS 12.1.
     * Удаляем orphan-записи (локальные, отсутствующие на сервере) с safety guard 30%.
     */
    private suspend fun reconcileGenericFolderAfterFullResync(
        serverIds: Set<String>,
        folderId: String
    ) {
        val localEmails = emailDao.getEmailIdsByFolder(folderId)
        val orphanIds = localEmails.filter { it !in serverIds }
        if (orphanIds.isEmpty()) return
        
        if (localEmails.isNotEmpty() && orphanIds.size.toFloat() / localEmails.size >= 0.3f) {
            android.util.Log.w("EmailSync",
                "reconcileGeneric($folderId): ${orphanIds.size}/${localEmails.size} orphaned (>=30%). " +
                "Skipping cleanup — server likely returned incomplete data.")
            return
        }
        for (id in orphanIds) {
            attachmentDao.deleteByEmail(id)
            emailDao.delete(id)
        }
        android.util.Log.d("EmailSync",
            "reconcileGeneric($folderId): Removed ${orphanIds.size} orphans after full resync")
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
                    emailDao.insertAllIgnore(emails)
                    val unreadCount = emailDao.getUnreadCount(folderId)
                    folderDao.updateUnreadCount(folderId, unreadCount)
                    EasResult.Success(emails.size)
                },
                onFailure = { 
                    EasResult.Error(it.message ?: "Ошибка получения писем") 
                }
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
                    emailDao.insertAllIgnore(emails)
                    val unreadCount = emailDao.getUnreadCount(folderId)
                    folderDao.updateUnreadCount(folderId, unreadCount)
                    EasResult.Success(emails.size)
                },
                onFailure = { EasResult.Error(it.message ?: "Ошибка получения писем") }
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error(e.message ?: "Ошибка POP3")
        } finally {
            try { client.disconnect() } catch (_: Exception) { }
        }
    }
    
    // === Утилитные методы ===
    
    private fun parseEwsDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(dateStr.substringBefore("Z"))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            var cleaned = dateStr.replace("Z", "+0000")
            cleaned = cleaned.replace(tzColonRegex, "$1$2$3")
            val formatters = threadLocalDateFormats.get()!!
            for (sdf in formatters) {
                try {
                    val date = sdf.parse(cleaned)
                    if (date != null) return date.time
                } catch (_: Exception) {}
            }
            0L
        } catch (_: Exception) { 0L }
    }
    
    /**
     * Мержит серверный body (с текстовыми изменениями из Outlook) и локальные data: URL.
     *
     * Стратегия:
     * 1. Берём НОВЫЙ body (текст из Outlook) + заменяем cid: на data: URL
     *    через contentId → localPath → файл → base64 → data: URL.
     * 2. Fallback: если localPath отсутствует, извлекаем data: URL из existingBody.
     *    Маппинг: contentId вида "imgN_timestamp" → N-я data: URL в existingBody.
     * 3. Нерезолвленные cid: (новые картинки из Outlook) остаются —
     *    fetchInlineImages при открытии письма подгрузит через ItemOperations.
     *
     * @param existingBody текущий body в БД (содержит data: URLs из предыдущего merge)
     */
    private fun mergeDraftBody(
        newBody: String,
        localAttachments: List<AttachmentEntity>,
        existingBody: String = ""
    ): String {
        if (newBody.isBlank()) return newBody
        if (!newBody.contains("cid:")) return newBody

        var mergedBody = newBody

        val inlineAtts = localAttachments.filter {
            it.isInline && !it.contentId.isNullOrBlank()
        }

        val dataUrlsFromExisting: List<String> by lazy {
            DATA_URL_EXTRACT_REGEX.findAll(existingBody).map { it.groupValues[0] }.toList()
        }

        for (att in inlineAtts) {
            val cleanCid = att.contentId!!.removeSurrounding("<", ">")
            if (!mergedBody.contains("cid:$cleanCid") &&
                (cleanCid == att.contentId || !mergedBody.contains("cid:${att.contentId}"))) continue

            var dataUrl: String? = null

            if (!att.localPath.isNullOrBlank()) {
                val localFile = java.io.File(att.localPath)
                if (localFile.exists()) {
                    try {
                        val bytes = localFile.readBytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val mimeType = if (att.contentType.isNotBlank()) att.contentType else "image/png"
                        dataUrl = "data:$mimeType;base64,$base64"
                    } catch (e: Exception) {
                        android.util.Log.w("EmailSyncService", "mergeDraftBody: failed to read ${att.localPath}: ${e.message}")
                    }
                }
            }

            if (dataUrl == null && existingBody.isNotBlank()) {
                val ordinalMatch = IMG_ORDINAL_REGEX.find(cleanCid)
                if (ordinalMatch != null) {
                    val idx = ordinalMatch.groupValues[1].toIntOrNull()
                    if (idx != null && idx > 0 && idx <= dataUrlsFromExisting.size) {
                        dataUrl = dataUrlsFromExisting[idx - 1]
                    }
                }
            }

            if (dataUrl != null) {
                mergedBody = mergedBody.replace("cid:$cleanCid", dataUrl)
                if (cleanCid != att.contentId) {
                    mergedBody = mergedBody.replace("cid:${att.contentId}", dataUrl)
                }
            }
        }

        return mergedBody
    }

}

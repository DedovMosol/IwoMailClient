package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.onSuccessResult
import com.dedovmosol.iwomail.eas.mapResult
import com.dedovmosol.iwomail.util.stripHtml
import com.dedovmosol.iwomail.widget.updateMailWidget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.Locale

/**
 * Сервис для операций с письмами (move, delete, markAsRead, MDN)
 * Single Responsibility: операции с письмами на сервере и локально
 */
class EmailOperationsService(
    private val context: Context,
    private val database: MailDatabase,
    private val folderDao: FolderDao,
    private val emailDao: EmailDao,
    private val attachmentDao: AttachmentDao,
    private val accountRepo: AccountRepository,
    private val emailSyncService: EmailSyncService
) {
    
    private val moveItemsStatusRegex = Regex("status=(\\d+)")
    private val previewWhitespaceRegex = Regex("\\s+")
    
    /**
     * Пометить письмо как прочитанное/непрочитанное
     */
    suspend fun markAsRead(emailId: String, read: Boolean): EasResult<Boolean> {
        android.util.Log.d("SYNC_DIAG", "markAsRead START emailId=$emailId read=$read")
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        val account = accountRepo.getAccount(email.accountId) ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            val client = accountRepo.createEasClient(email.accountId) ?: return EasResult.Error("Не удалось создать клиент")
            val folder = folderDao.getFolder(email.folderId) ?: return EasResult.Error("Папка не найдена")
            
            val currentSyncKey = ensureValidSyncKey(client, email.folderId, folder.serverId, folder.syncKey)
            if (currentSyncKey == null) {
                emailDao.updateReadStatus(emailId, read)
                updateFolderCounts(email.folderId)
                updateMailWidget(context)
                return EasResult.Success(true)
            }
            
            // Оптимистичное обновление — UI реагирует сразу
            emailDao.updateReadStatus(emailId, read)
            updateFolderCounts(email.folderId)
            updateMailWidget(context)
            
            // Убираем текущее account-уведомление, чтобы не висело уже прочитанное письмо
            if (read) {
                cancelNotificationForAccount(email.accountId)
            }
            
            val result = client.markAsRead(folder.serverId, email.serverId, currentSyncKey, read, email.subject)
            android.util.Log.d("SYNC_DIAG", "markAsRead result=${if (result is EasResult.Success) "OK newKey=${result.data.take(10)}" else "FAIL: ${(result as EasResult.Error).message}"} syncKey=${currentSyncKey.take(10)}")
            return when (result) {
                is EasResult.Success -> {
                    folderDao.updateSyncKey(email.folderId, result.data)
                    EasResult.Success(true)
                }
                is EasResult.Error -> {
                    android.util.Log.w("EmailOps", "markAsRead failed (syncKey=$currentSyncKey): ${result.message}")
                    
                    // Повторяем только если параллельный sync уже успел сохранить
                    // новый syncKey в БД. Самостоятельно делать GetChanges-sync здесь
                    // нельзя: это может продвинуть SyncKey мимо необработанных дельт.
                    val freshSyncKey = if (isLikelyStaleSyncKeyError(result.message)) {
                        getRetrySyncKeyFromDatabase(email.folderId, currentSyncKey)
                    } else {
                        null
                    }
                    
                    if (freshSyncKey != null) {
                        folderDao.updateSyncKey(email.folderId, freshSyncKey)
                        val retryResult = client.markAsRead(folder.serverId, email.serverId, freshSyncKey, read, email.subject)
                        when (retryResult) {
                            is EasResult.Success -> {
                                folderDao.updateSyncKey(email.folderId, retryResult.data)
                                return EasResult.Success(true)
                            }
                            is EasResult.Error -> {
                                // Повторная ошибка — откатываем
                                android.util.Log.e("EmailOps", "markAsRead retry also failed: ${retryResult.message}")
                                emailDao.updateReadStatus(emailId, !read)
                                updateFolderCounts(email.folderId)
                                updateMailWidget(context)
                                return EasResult.Error(retryResult.message)
                            }
                        }
                    } else {
                        // Не удалось получить syncKey вообще — откатываем
                        android.util.Log.e("EmailOps", "markAsRead: cannot obtain valid syncKey, rolling back")
                        emailDao.updateReadStatus(emailId, !read)
                        updateFolderCounts(email.folderId)
                        updateMailWidget(context)
                        EasResult.Error(result.message)
                    }
                }
            }
        } else {
            emailDao.updateReadStatus(emailId, read)
            updateFolderCounts(email.folderId)
            updateMailWidget(context)
            if (read) {
                cancelNotificationForAccount(email.accountId)
            }
            return EasResult.Success(true)
        }
    }
    
    /**
     * Батч-пометка нескольких писем как прочитанных/непрочитанных.
     * Группирует по папкам и отправляет один Sync-запрос на папку.
     * Exchange 2007 SP1: один Sync с N <Change> элементов.
     */
    suspend fun markAsReadBatch(emailIds: List<String>, read: Boolean): EasResult<Boolean> {
        if (emailIds.isEmpty()) return EasResult.Success(true)
        
        val emails = emailIds.mapNotNull { emailDao.getEmail(it) }
        if (emails.isEmpty()) return EasResult.Error("Письма не найдены")
        
        // Оптимистичное обновление — UI реагирует сразу
        emails.forEach { emailDao.updateReadStatus(it.id, read) }
        val affectedFolders = emails.map { it.folderId }.distinct()
        affectedFolders.forEach { updateFolderCounts(it) }
        updateMailWidget(context)
        
        // Группируем по аккаунту → по папке
        val byAccount = emails.groupBy { it.accountId }
        var lastError: String? = null
        
        for ((accountId, accountEmails) in byAccount) {
            val account = accountRepo.getAccount(accountId) ?: continue
            
            if (account.accountType != AccountType.EXCHANGE.name) {
                // Не Exchange — уже помечено локально
                continue
            }
            
            val client = accountRepo.createEasClient(accountId) ?: continue
            
            val byFolder = accountEmails.groupBy { it.folderId }
            
            for ((folderId, folderEmails) in byFolder) {
                val folder = folderDao.getFolder(folderId) ?: continue
                val currentSyncKey = ensureValidSyncKey(client, folderId, folder.serverId, folder.syncKey)
                    ?: continue
                
                val serverIds = folderEmails.map { it.serverId }
                val result = client.markAsReadBatch(folder.serverId, serverIds, currentSyncKey, read)
                
                when (result) {
                    is EasResult.Success -> {
                        folderDao.updateSyncKey(folderId, result.data)
                    }
                    is EasResult.Error -> {
                        android.util.Log.w("EmailOps", "markAsReadBatch failed for folder $folderId: ${result.message}")
                        val freshSyncKey = if (isLikelyStaleSyncKeyError(result.message)) {
                            getRetrySyncKeyFromDatabase(folderId, currentSyncKey)
                        } else {
                            null
                        }
                        if (freshSyncKey != null) {
                            folderDao.updateSyncKey(folderId, freshSyncKey)
                            val retry = client.markAsReadBatch(folder.serverId, serverIds, freshSyncKey, read)
                            when (retry) {
                                is EasResult.Success -> folderDao.updateSyncKey(folderId, retry.data)
                                is EasResult.Error -> {
                                    lastError = retry.message
                                    // Откатываем только эту папку
                                    folderEmails.forEach { emailDao.updateReadStatus(it.id, !read) }
                                    updateFolderCounts(folderId)
                                }
                            }
                        } else {
                            lastError = result.message
                            folderEmails.forEach { emailDao.updateReadStatus(it.id, !read) }
                            updateFolderCounts(folderId)
                        }
                    }
                }
            } // for byFolder
        } // for byAccount
        
        updateMailWidget(context)
        if (read) {
            byAccount.keys.forEach { cancelNotificationForAccount(it) }
        }
        return if (lastError != null) EasResult.Error(lastError) else EasResult.Success(true)
    }
    
    /**
     * Загружает полное тело письма с сервера
     * @param forceReload - принудительная перезагрузка даже если body уже есть
     */
    suspend fun loadEmailBody(emailId: String, forceReload: Boolean = false): EasResult<String> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        val folder = folderDao.getFolder(email.folderId)
        val suspiciousCachedBody = !forceReload &&
            email.body.isNotEmpty() &&
            folder?.type != FolderType.DRAFTS &&
            !cachedBodyMatchesPreview(email.preview, email.body)

        if (email.body.isNotEmpty() && !forceReload && !suspiciousCachedBody) {
            return EasResult.Success(email.body)
        }
        if (suspiciousCachedBody) {
            android.util.Log.w(
                "BDY",
                "loadEmailBody: cached body looks stale for emailId=$emailId, forcing exact reload"
            )
        }
        
        val account = accountRepo.getAccount(email.accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            val client = accountRepo.createEasClient(email.accountId)
                ?: return EasResult.Error("Не удалось создать клиент")
            
            val folderServerId = email.folderId.substringAfter("_")
            
            return when (val result = client.fetchEmailBodyWithMdn(folderServerId, email.serverId)) {
                is EasResult.Success -> {
                    var bodyContent = result.data.body
                    var resolvedMessageId = result.data.originalMessageId
                    
                    // КРИТИЧНО: Для Exchange 2007 SP1 ItemOperations может вернуть пустое тело
                    // В этом случае используем EWS как fallback
                    if (bodyContent.isEmpty() && client.isExchange2007()) {
                        android.util.Log.d("BDY", "loadEmailBody: Empty body from ItemOperations, trying EWS fallback...")
                        
                        // Получаем тип папки для EWS DistinguishedFolderId
                        val folderTypeStr = when (folder?.type) {
                            FolderType.INBOX -> "inbox"
                            FolderType.SENT_ITEMS -> "sentitems"
                            FolderType.DRAFTS -> "drafts"
                            FolderType.DELETED_ITEMS -> "deleteditems"
                            FolderType.OUTBOX -> "outbox"
                            else -> null
                        }

                        if (folderTypeStr != null) {
                            val ewsResult = client.fetchEmailBodyViaEws(
                                subject = email.subject,
                                folderType = folderTypeStr,
                                dateReceived = email.dateReceived,
                                internetMessageId = email.internetMessageId
                            )
                            if (ewsResult is EasResult.Success && ewsResult.data.body.isNotEmpty()) {
                                android.util.Log.d("BDY", "loadEmailBody: EWS fallback SUCCESS, bodyLength=${ewsResult.data.body.length}")
                                bodyContent = ewsResult.data.body
                                if (resolvedMessageId.isNullOrBlank()) {
                                    resolvedMessageId = ewsResult.data.originalMessageId
                                }
                            }
                        }
                    }
                    
                    // КРИТИЧНО: Не перезаписываем существующее тело пустым ответом!
                    // Сервер может вернуть пустое тело из-за временной ошибки,
                    // race condition с другими EAS командами, или ограничений ItemOperations.
                    if (bodyContent.isNotEmpty() || email.body.isEmpty()) {
                        emailDao.updateBody(emailId, bodyContent)
                    } else if (suspiciousCachedBody) {
                        // Заведомо подозрительное тело хуже пустого: не показываем чужой кэш.
                        emailDao.updateBody(emailId, "")
                        android.util.Log.w("BDY", "loadEmailBody: exact reload failed, dropping suspicious cached body")
                        bodyContent = ""
                    } else {
                        android.util.Log.w("BDY", "loadEmailBody: Server returned empty body, keeping existing (${email.body.length} chars)")
                        bodyContent = email.body
                    }
                    
                    // КРИТИЧНО: Если bodyType=4 (MIME), но мы сохранили уже извлечённый HTML,
                    // обновляем bodyType на 2. Иначе при повторном открытии EmailDetailScreen
                    // будет пытаться парсить HTML как MIME → inline картинки не найдутся.
                    if (email.bodyType == 4 && bodyContent.isNotEmpty() &&
                        !bodyContent.contains("Content-Type:", ignoreCase = true)) {
                        emailDao.updateBodyType(emailId, 2)
                        android.util.Log.d("BDY", "loadEmailBody: Updated bodyType 4→2 (stored HTML, not MIME)")
                    }
                    
                    if (!result.data.mdnRequestedBy.isNullOrBlank() && !email.mdnSent) {
                        emailDao.updateMdnRequestedBy(emailId, result.data.mdnRequestedBy)
                    }

                    resolvedMessageId
                        ?.takeIf { it.isNotBlank() && email.internetMessageId != it }
                        ?.let { emailDao.updateInternetMessageId(emailId, it) }
                    
                    android.util.Log.d("BDY", "loadEmailBody: SUCCESS, bodyLength=${bodyContent.length}")
                    EasResult.Success(bodyContent)
                }
                is EasResult.Error -> {
                    if (result.message == "OBJECT_NOT_FOUND") {
                        // КРИТИЧНО: НЕ удаляем черновики при OBJECT_NOT_FOUND!
                        // Для черновиков, созданных через EWS CreateItem(MimeContent),
                        // EAS ItemOperations может временно не найти элемент
                        // (сервер ещё не проиндексировал). Удаление уничтожит
                        // локальные данные (body с data: URL, вложения).
                        // Для остальных папок — удаление корректно (письмо реально удалено).
                        val folder = folderDao.getFolder(email.folderId)
                        if (folder?.type != FolderType.DRAFTS) {
                            EmailSyncService.registerDeletedEmail(emailId, context)
                            attachmentDao.deleteByEmail(emailId)
                            emailDao.delete(emailId)
                            updateFolderCounts(email.folderId)
                        }
                    }
                    result
                }
            }
        }
        
        return EasResult.Error("Загрузка тела не поддерживается для этого типа аккаунта")
    }

    private fun cachedBodyMatchesPreview(preview: String, body: String): Boolean {
        val normalizedPreview = normalizePreviewText(preview)
        if (normalizedPreview.isBlank()) return true

        val normalizedBody = normalizePreviewText(stripHtml(body))
        if (normalizedBody.isBlank()) return false

        if (normalizedBody.startsWith(normalizedPreview)) return true

        val searchWindow = normalizedBody.take((normalizedPreview.length + 64).coerceAtLeast(160))
        return searchWindow.contains(normalizedPreview)
    }

    private fun normalizePreviewText(text: String): String = text
        .replace(previewWhitespaceRegex, " ")
        .trim()
        .lowercase(Locale.ROOT)
    
    /**
     * Синхронизирует вложения конкретного письма с сервером через ItemOperations.
     * Делегирует в EmailSyncService.reconcileAttachments (DRY) — единая
     * транзакционная логика для sync worker и UI.
     *
     * Exchange 2007 SP1 / EAS 12.1: Attachment metadata поддерживается
     * через ItemOperations Fetch (MS-ASAIRS 2.2.2.7).
     */
    suspend fun refreshAttachmentMetadata(emailId: String) {
        try {
            val email = emailDao.getEmail(emailId) ?: return
            val account = accountRepo.getAccount(email.accountId) ?: return
            if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) return

            val sid = email.serverId
            if (sid.startsWith("local_draft_") || sid.isBlank()) return
            if (sid.contains("=") && !sid.contains(":")) return

            val client = accountRepo.createEasClient(email.accountId) ?: return
            val folderServerId = email.folderId.substringAfter("_")
            
            val attResult = client.fetchAttachmentMetadata(folderServerId, sid)
            if (attResult is EasResult.Success) {
                emailSyncService.reconcileAttachments(emailId, attResult.data)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EmailOps", "refreshAttachmentMetadata failed: ${e.message}")
        }
    }
    
    /**
     * Предзагрузка тел последних N писем из Inbox
     */
    suspend fun prefetchEmailBodies(accountId: Long, count: Int = 7) {
        try {
            val inboxFolder = folderDao.getFolderByType(accountId, FolderType.INBOX) ?: return
            val emails = emailDao.getEmailsWithEmptyBody(inboxFolder.id, count)
            if (emails.isEmpty()) return
            
            val account = accountRepo.getAccount(accountId) ?: return
            if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) return
            
            val client = accountRepo.createEasClient(accountId) ?: return
            val folderServerId = inboxFolder.serverId
            
            kotlinx.coroutines.supervisorScope {
                emails.chunked(3).forEach { chunk ->
                    chunk.map { email ->
                        async {
                            try {
                                // КРИТИЧНО: Сохраняем email.id и email.serverId в локальные переменные
                                // чтобы избежать race condition при параллельной загрузке
                                val emailId = email.id
                                val emailServerId = email.serverId
                                
                                val result = client.fetchEmailBodyWithMdn(folderServerId, emailServerId)
                                result.onSuccessResult { response ->
                                    emailDao.updateBody(emailId, response.body)
                                    if (!response.mdnRequestedBy.isNullOrBlank() && !email.mdnSent) {
                                        emailDao.updateMdnRequestedBy(emailId, response.mdnRequestedBy)
                                    }
                                    response.originalMessageId
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { emailDao.updateInternetMessageId(emailId, it) }
                                }
                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e else Unit }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e else Unit }
    }
    
    /**
     * Переключить флаг письма (избранное)
     * ОПТИМИСТИЧНОЕ ОБНОВЛЕНИЕ: сначала обновляем локально для мгновенного отклика UI,
     * затем синхронизируем с сервером. При ошибке сервера локальное изменение сохраняется
     * и будет скорректировано при следующей полной синхронизации.
     */
    suspend fun toggleFlag(emailId: String): EasResult<Boolean> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        val account = accountRepo.getAccount(email.accountId) ?: return EasResult.Error("Аккаунт не найден")
        
        val newFlagStatus = !email.flagged
        
        // ОПТИМИСТИЧНОЕ ОБНОВЛЕНИЕ: сначала обновляем локально для мгновенного отклика UI
        emailDao.updateFlagStatus(emailId, newFlagStatus)
        
        if (AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            val client = accountRepo.createEasClient(email.accountId)
            if (client == null) {
                // Не удалось создать клиент — локально уже обновили
                return EasResult.Success(true)
            }
            val folder = folderDao.getFolder(email.folderId)
            if (folder == null) {
                return EasResult.Success(true)
            }
            
            val currentSyncKey = ensureValidSyncKey(client, email.folderId, folder.serverId, folder.syncKey)
                ?: return EasResult.Success(true)
            
            val result = client.toggleFlag(folder.serverId, email.serverId, currentSyncKey, newFlagStatus)
            when (result) {
                is EasResult.Success -> {
                    folderDao.updateSyncKey(email.folderId, result.data)
                }
                is EasResult.Error -> {
                    // Серверная синхронизация не удалась, но локально флаг уже установлен.
                    // При следующей полной синхронизации состояние будет скорректировано.
                    android.util.Log.w("ToggleFlag", "Server sync failed: ${result.message}, local update preserved")
                }
            }
        }
        
        return EasResult.Success(true)
    }
    
    /**
     * Удалить письмо локально (с транзакцией для целостности данных)
     */
    suspend fun deleteEmail(emailId: String) {
        EmailSyncService.registerDeletedEmail(emailId, context)
        database.withTransaction {
            attachmentDao.deleteByEmail(emailId)
            emailDao.delete(emailId)
        }
    }
    
    /**
     * Собирает пары (emailServerId, srcFolderServerId) для MoveItems.
     * DRY: используется при первой попытке и при retry.
     */
    private suspend fun buildMoveItems(emailIds: List<String>): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>() // (emailId, emailServerId, srcFolderServerId)
        for (emailId in emailIds) {
            val email = emailDao.getEmail(emailId) ?: continue
            val emailServerId = email.serverId
            val srcFolderServerId = if (emailServerId.contains(":")) {
                emailServerId.substringBefore(":")
            } else {
                val sourceFolder = folderDao.getFolder(email.folderId)
                sourceFolder?.serverId ?: email.folderId.substringAfter("_")
            }
            result.add(Triple(emailId, emailServerId, srcFolderServerId))
        }
        return result
    }
    
    /**
     * Перемещение писем в другую папку
     */
    suspend fun moveEmails(emailIds: List<String>, targetFolderId: String, updateOriginalFolder: Boolean = true): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        val firstEmail = emailDao.getEmail(emailIds.first()) 
            ?: return EasResult.Error("Email not found")
        
        val allEmails = emailIds.mapNotNull { emailDao.getEmail(it) }
        if (!allEmails.all { it.accountId == firstEmail.accountId }) {
            return EasResult.Error("All emails must belong to the same account")
        }
        
        val account = accountRepo.getAccount(firstEmail.accountId)
            ?: return EasResult.Error("Account not found")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Move is only supported for Exchange")
        }
        
        val client = accountRepo.createEasClient(firstEmail.accountId)
            ?: return EasResult.Error("Failed to create client")
        
        val targetFolder = folderDao.getFolder(targetFolderId)
            ?: return EasResult.Error("Target folder not found")
        
        val triples = buildMoveItems(emailIds)
        if (triples.isEmpty()) return EasResult.Success(0)
        
        val items = triples.map { it.second to it.third }
        
        if (items.first().second == targetFolder.serverId) {
            return EasResult.Error("ALREADY_IN_FOLDER")
        }
        
        var result = client.moveItems(items, targetFolder.serverId)
        
        // КРИТИЧНО: Если все MoveItems отклонены (status=1 — устаревшие serverId),
        // пересинхронизируем исходную папку и повторяем с актуальными serverId.
        // Безопасно для Exchange 2007 SP1: syncEmails — инкрементальный sync.
        if (result is EasResult.Error && result.message.contains("MOVEITEMS_ALL_FAILED")) {
            try {
                val sourceFolderId = emailDao.getEmail(emailIds.first())?.folderId
                if (sourceFolderId != null) {
                    emailSyncService.syncEmails(firstEmail.accountId, sourceFolderId, forceFullSync = false)
                    
                    val freshTriples = buildMoveItems(emailIds)
                    if (freshTriples.isNotEmpty()) {
                        val freshItems = freshTriples.map { it.second to it.third }
                        result = client.moveItems(freshItems, targetFolder.serverId)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        
        // FALLBACK: Если batch по-прежнему отклонён, пробуем по одному.
        // MS-ASCMD §2.2.3.177.10:
        //   status=1 (Invalid source) = письмо не существует на сервере → ghost, удаляем локально.
        //   status=2,4,5,7 = письмо СУЩЕСТВУЕТ, но move невозможен → НЕ удаляем.
        var ghostDeletedCount = 0
        if (result is EasResult.Error && result.message.contains("MOVEITEMS_ALL_FAILED")) {
            val allResults = mutableMapOf<String, String>() // srcServerId → dstServerId
            val ghostEmailIds = mutableListOf<String>()     // emailIds для локальной очистки (только status=1)
            val ghostFolderIds = mutableSetOf<String>()     // folderIds для обновления счётчиков
            val freshTriples = buildMoveItems(emailIds)
            
            for ((emailId, serverId, srcFldId) in freshTriples) {
                val singleResult = client.moveItems(listOf(serverId to srcFldId), targetFolder.serverId)
                when (singleResult) {
                    is EasResult.Success -> {
                        if (singleResult.data.isNotEmpty()) {
                            allResults.putAll(singleResult.data)
                        }
                    }
                    is EasResult.Error -> {
                        // executeEasCommand оборачивает throw из parser в EasResult.Error
                        // Парсим status из "...MOVEITEMS_ALL_FAILED:status=N,..."
                        val failStatus = moveItemsStatusRegex.find(singleResult.message)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        
                        if (failStatus == 1) {
                            // Status=1: Invalid source — письмо не существует на сервере.
                            // Удаляем ghost-запись локально.
                            val ghostEmail = emailDao.getEmail(emailId)
                            if (ghostEmail != null) ghostFolderIds.add(ghostEmail.folderId)
                            ghostEmailIds.add(emailId)
                        }
                        // Status=2,4,5,7: письмо существует, но move невозможен.
                        // НЕ удаляем — пользователь увидит ошибку и может повторить.
                    }
                }
            }
            
            // Очищаем ghost-записи локально.
            // НЕ вызываем registerDeletedEmail — status=1 означает лишь устаревший serverId,
            // само письмо может существовать на сервере с другим serverId.
            // Следующий sync вернёт актуальную версию.
            for (ghostId in ghostEmailIds) {
                attachmentDao.deleteByEmail(ghostId)
                emailDao.delete(ghostId)
            }
            ghostDeletedCount = ghostEmailIds.size
            
            result = if (allResults.isNotEmpty() || ghostEmailIds.isNotEmpty()) {
                EasResult.Success(allResults)
            } else {
                result
            }
            
            for (srcId in ghostFolderIds) {
                updateFolderCounts(srcId)
            }
        }
        
        return when (result) {
            is EasResult.Success -> {
                val movedMap = result.data
                val sourceFolderIds = mutableSetOf<String>()
                var movedCount = 0
                
                for (emailId in emailIds) {
                    val email = emailDao.getEmail(emailId) ?: continue
                    sourceFolderIds.add(email.folderId)
                    
                    val newServerId = movedMap[email.serverId]
                    if (newServerId != null) {
                        val newEmailId = "${firstEmail.accountId}_$newServerId"
                        val newOriginalFolderId = if (updateOriginalFolder) targetFolderId else email.originalFolderId
                        val updatedEmail = email.copy(
                            id = newEmailId,
                            serverId = newServerId,
                            folderId = targetFolderId,
                            originalFolderId = newOriginalFolderId
                        )
                        EmailSyncService.registerDeletedEmail(emailId, context)
                        database.withTransaction {
                            val oldAttachments = attachmentDao.getAttachmentsList(emailId)
                            emailDao.delete(emailId)
                            emailDao.insert(updatedEmail)
                            if (oldAttachments.isNotEmpty()) {
                                attachmentDao.insertAll(oldAttachments.map {
                                    it.copy(id = 0, emailId = newEmailId)
                                })
                            }
                        }
                        movedCount++
                    }
                }
                
                for (srcId in sourceFolderIds) {
                    updateFolderCounts(srcId)
                }
                
                updateFolderCounts(targetFolderId)
                EasResult.Success(movedCount + ghostDeletedCount)
            }
            is EasResult.Error -> result
        }
    }
    
    /**
     * Перемещение письма в спам
     */
    suspend fun moveToSpam(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        val firstEmail = emailDao.getEmail(emailIds.first())
            ?: return EasResult.Error("Email not found")
        
        val spamFolder = folderDao.getFolderByType(firstEmail.accountId, FolderType.JUNK_EMAIL)
            ?: return EasResult.Error("Spam folder not found")
        
        return moveEmails(emailIds, spamFolder.id)
    }
    
    /**
     * Перемещение письма в корзину или окончательное удаление
     */
    suspend fun moveToTrash(
        emailIds: List<String>,
        deleteDraft: suspend (Long, String) -> EasResult<Boolean>
    ): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        return try {
            val firstEmail = emailDao.getEmail(emailIds.first())
                ?: return EasResult.Error("Email not found")
            
            val draftsFolder = folderDao.getFolderByType(firstEmail.accountId, FolderType.DRAFTS)
            val trashFolder = folderDao.getFolderByType(firstEmail.accountId, FolderType.DELETED_ITEMS)
                ?: return EasResult.Error("Trash folder not found")
            
            val drafts = mutableListOf<String>()
            val inTrash = mutableListOf<String>()
            val regularEmails = mutableListOf<String>()
            
            for (emailId in emailIds) {
                val email = emailDao.getEmail(emailId) ?: continue
                when {
                    draftsFolder != null && email.folderId == draftsFolder.id -> drafts.add(emailId)
                    email.folderId == trashFolder.id -> inTrash.add(emailId)
                    else -> regularEmails.add(emailId)
                }
            }
            
            var totalDeleted = 0
            
            for (emailId in drafts) {
                val email = emailDao.getEmail(emailId) ?: continue
                val result = deleteDraft(email.accountId, email.serverId)
                if (result is EasResult.Success) {
                    totalDeleted++
                } else {
                    regularEmails.add(emailId)
                    android.util.Log.w("EmailOps", "Draft server delete failed, moving to trash instead: ${email.serverId}")
                }
            }
            
            if (inTrash.isNotEmpty()) {
                val result = deleteEmailsPermanently(inTrash)
                if (result is EasResult.Success) {
                    totalDeleted += result.data
                }
            }
            
            if (regularEmails.isEmpty()) {
                return EasResult.Success(totalDeleted)
            }
            
            for (emailId in regularEmails) {
                val email = emailDao.getEmail(emailId) ?: continue
                emailDao.updateOriginalFolderId(emailId, email.folderId)
            }
            
            val moveResult = moveEmails(regularEmails, trashFolder.id, updateOriginalFolder = false)
            when (moveResult) {
                is EasResult.Success -> EasResult.Success(totalDeleted + moveResult.data)
                is EasResult.Error -> {
                    // Частичный успех: черновики/корзина удалены, но move не удался
                    if (totalDeleted > 0) EasResult.Success(totalDeleted)
                    else moveResult
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Delete error: ${e.message}")
        }
    }
    
    /**
     * Восстановление письма из корзины
     */
    suspend fun restoreFromTrash(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        val firstEmail = emailDao.getEmail(emailIds.first())
            ?: return EasResult.Error("Email not found")
        
        val targetFolderId = firstEmail.originalFolderId?.takeIf { it.isNotEmpty() }
            ?: folderDao.getFolderByType(firstEmail.accountId, FolderType.INBOX)?.id
            ?: return EasResult.Error("Target folder not found")
        
        return moveEmails(emailIds, targetFolderId)
    }
    
    suspend fun deleteEmailsPermanently(emailIds: List<String>): EasResult<Int> {
        return deleteEmailsPermanentlyWithProgress(emailIds) { _, _ -> }
    }
    
    /**
     * Окончательное удаление писем с callback прогресса
     */
    suspend fun deleteEmailsPermanentlyWithProgress(
        emailIds: List<String>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        return try {
            val affectedFolderIds = mutableSetOf<String>()
            val total = emailIds.size
            var deletedCount = 0
            
            // Ищем хотя бы одно существующее письмо для определения аккаунта
            var firstEmail: com.dedovmosol.iwomail.data.database.EmailEntity? = null
            for (id in emailIds) {
                firstEmail = emailDao.getEmail(id)
                if (firstEmail != null) break
            }
            if (firstEmail == null) {
                // Все письма уже удалены из БД (устаревшие ID)
                emailIds.forEach { attachmentDao.deleteByEmail(it) }
                onProgress(total, total)
                return EasResult.Success(total)
            }
            
            val account = accountRepo.getAccount(firstEmail.accountId)
                ?: return EasResult.Error("Account not found")
            
            if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                emailIds.forEach { emailId ->
                    val email = emailDao.getEmail(emailId)
                    if (email != null) {
                        affectedFolderIds.add(email.folderId)
                    }
                    attachmentDao.deleteByEmail(emailId)
                    emailDao.delete(emailId)
                    deletedCount++
                    onProgress(deletedCount, total)
                }
                for (folderId in affectedFolderIds) {
                    updateFolderCounts(folderId)
                }
                return EasResult.Success(deletedCount)
            }
            
            val client = accountRepo.createEasClient(firstEmail.accountId)
                ?: return EasResult.Error("Failed to create client")
            
            // === BATCH DELETE: группируем письма по папкам и удаляем пачкой ===
            
            data class EmailInfo(val emailId: String, val folderId: String, val serverId: String, val subject: String)
            val emailInfos = mutableListOf<EmailInfo>()
            val alreadyDeleted = mutableListOf<String>()
            
            for (emailId in emailIds) {
                val email = emailDao.getEmail(emailId)
                if (email == null) {
                    attachmentDao.deleteByEmail(emailId)
                    alreadyDeleted.add(emailId)
                    continue
                }
                affectedFolderIds.add(email.folderId)
                emailInfos.add(EmailInfo(emailId, email.folderId, email.serverId, email.subject))
            }
            deletedCount += alreadyDeleted.size
            onProgress(deletedCount, total)
            
            // Группируем по folderServerId для batch-операций
            val groupedByFolder = emailInfos.groupBy { info ->
                if (info.serverId.contains(":")) {
                    info.serverId.substringBefore(":")
                } else {
                    info.folderId.substringAfter("_")
                }
            }
            
            for ((folderServerId, emails) in groupedByFolder) {
                val folderId = emails.first().folderId
                var syncKey = folderDao.getFolder(folderId)?.syncKey ?: "0"
                ensureValidSyncKey(client, folderId, folderServerId, syncKey)?.let { syncKey = it }
                
                // Если syncKey всё ещё "0" — EWS fallback по одному
                if (syncKey == "0") {
                    if (client.isExchange2007()) {
                        for (info in emails) {
                            val ewsResult = client.deleteEmailPermanentlyViaEWS(info.serverId, info.subject)
                            if (ewsResult is EasResult.Success) {
                                EmailSyncService.registerDeletedEmail(info.emailId, context)
                                attachmentDao.deleteByEmail(info.emailId)
                                emailDao.delete(info.emailId)
                                deletedCount++
                            }
                            onProgress(deletedCount, total)
                        }
                    }
                    continue
                }
                
                val serverIds = emails.map { it.serverId }
                var batchResult = client.deleteEmailsPermanentlyBatch(folderServerId, serverIds, syncKey)
                
                if (batchResult is EasResult.Error && 
                    (batchResult.message.contains("INVALID_SYNCKEY") || batchResult.message.contains("DELETE_NOT_APPLIED"))) {
                    val initResult = client.sync(folderServerId, "0", windowSize = 0)
                    if (initResult is EasResult.Success && initResult.data.syncKey != "0") {
                        syncKey = initResult.data.syncKey
                        folderDao.updateSyncKey(folderId, syncKey)
                        batchResult = client.deleteEmailsPermanentlyBatch(folderServerId, serverIds, syncKey)
                    }
                }
                
                when {
                    batchResult is EasResult.Success -> {
                        folderDao.updateSyncKey(folderId, batchResult.data)
                        for (info in emails) {
                            EmailSyncService.registerDeletedEmail(info.emailId, context)
                            attachmentDao.deleteByEmail(info.emailId)
                            emailDao.delete(info.emailId)
                            deletedCount++
                            onProgress(deletedCount, total)
                        }
                    }
                    batchResult is EasResult.Error -> {
                        for (info in emails) {
                            val ewsResult = client.deleteEmailPermanentlyViaEWS(info.serverId, info.subject)
                            if (ewsResult is EasResult.Success) {
                                EmailSyncService.registerDeletedEmail(info.emailId, context)
                                attachmentDao.deleteByEmail(info.emailId)
                                emailDao.delete(info.emailId)
                                deletedCount++
                            }
                            onProgress(deletedCount, total)
                        }
                    }
                }
            }
            
            for (folderId in affectedFolderIds) {
                updateFolderCounts(folderId)
            }
            
            if (deletedCount == 0 && emailIds.isNotEmpty()) {
                EasResult.Error("DELETE_FAILED")
            } else {
                EasResult.Success(deletedCount)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Delete error: ${e.message}")
        }
    }
    
    /**
     * Отправка отчёта о прочтении (MDN)
     */
    suspend fun sendMdn(emailId: String): EasResult<Boolean> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        
        if (email.mdnSent) {
            return EasResult.Success(true)
        }
        
        val mdnTo = email.mdnRequestedBy
        if (mdnTo.isNullOrBlank()) {
            return EasResult.Error("Нет адреса для отправки MDN")
        }
        
        val account = accountRepo.getAccount(email.accountId)
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("MDN поддерживается только для Exchange")
        }
        
        val client = accountRepo.createEasClient(email.accountId)
            ?: return EasResult.Error("Не удалось создать клиент")
        
        return client.sendMdn(mdnTo, email.subject, email.internetMessageId)
            .onSuccessResult {
                emailDao.updateMdnSent(emailId, true)
            }
            .mapResult { true }
    }
    
    /**
     * Помечает что MDN отправлен
     */
    suspend fun markMdnSent(emailId: String) {
        emailDao.updateMdnSent(emailId, true)
    }
    
    // === Вспомогательные методы ===
    
    /**
     * Отменяет account-уведомление о почте.
     * Нужен для кейса: пользователь открыл письмо из уведомления и прочитал его,
     * но notification со старым заголовком не должен оставаться в шторке.
     */
    private fun cancelNotificationForAccount(accountId: Long) {
        try {
            val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            val notificationId = 3000 + accountId.toInt()
            notificationManager?.cancel(notificationId)
        } catch (e: Exception) {
            android.util.Log.w("EmailOps", "cancelNotificationForAccount failed", e)
        }
    }
    
    /**
     * Инициализирует syncKey через Sync с "0" если текущий ключ невалиден.
     * MS-ASCMD: SyncKey="0" → сервер возвращает новый ключ без данных (EAS 12.0+).
     * @return валидный syncKey или null если инициализация не удалась
     */
    private suspend fun ensureValidSyncKey(
        client: com.dedovmosol.iwomail.eas.EasClient,
        folderId: String,
        folderServerId: String,
        currentSyncKey: String
    ): String? {
        if (currentSyncKey != "0") return currentSyncKey
        val initResult = client.sync(folderServerId, "0", windowSize = 0)
        if (initResult is EasResult.Success && initResult.data.syncKey != "0") {
            val newKey = initResult.data.syncKey
            folderDao.updateSyncKey(folderId, newKey)
            return newKey
        }
        return null
    }

    private suspend fun getRetrySyncKeyFromDatabase(
        folderId: String,
        failedSyncKey: String
    ): String? {
        val latestSyncKey = folderDao.getFolder(folderId)?.syncKey ?: return null
        return latestSyncKey.takeIf { it.isNotBlank() && it != "0" && it != failedSyncKey }
    }

    private fun isLikelyStaleSyncKeyError(message: String): Boolean {
        return message.contains("INVALID_SYNCKEY", ignoreCase = true) ||
            message.contains("Status=3", ignoreCase = true)
    }

    /**
     * Обновляет счётчики папки с транзакцией для консистентности
     */
    private suspend fun updateFolderCounts(folderId: String) {
        database.withTransaction {
            val totalCount = emailDao.getCountByFolder(folderId)
            val unreadCount = emailDao.getUnreadCount(folderId)
            folderDao.updateCounts(folderId, unreadCount, totalCount)
        }
    }
}

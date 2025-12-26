package com.exchange.mailclient.data.repository

import android.content.Context
import com.exchange.mailclient.data.database.*
import com.exchange.mailclient.eas.EasResult
import com.exchange.mailclient.imap.ImapClient
import com.exchange.mailclient.pop3.Pop3Client
import kotlinx.coroutines.flow.Flow

/**
 * Репозиторий для работы с почтой
 * Single Responsibility: синхронизация и хранение писем/папок
 */
class MailRepository(context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val accountRepo = AccountRepository(context)
    
    private val folderDao = database.folderDao()
    private val emailDao = database.emailDao()
    private val attachmentDao = database.attachmentDao()
    private val accountDao = database.accountDao()
    
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
    
    /**
     * Синхронизация папок для аккаунта
     */
    suspend fun syncFolders(accountId: Long): EasResult<Unit> {
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        return when (AccountType.valueOf(account.accountType)) {
            AccountType.EXCHANGE -> syncFoldersEas(accountId, account)
            AccountType.IMAP -> syncFoldersImap(accountId)
            AccountType.POP3 -> syncFoldersPop3(accountId)
        }
    }
    
    /**
     * Создание новой папки
     */
    suspend fun createFolder(accountId: Long, folderName: String): EasResult<Unit> {
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Создание папок поддерживается только для Exchange")
        }
        
        val client = accountRepo.createEasClient(accountId) 
            ?: return EasResult.Error("Не удалось создать клиент")
        
        val syncKey = account.folderSyncKey ?: "0"
        
        return when (val result = client.createFolder(folderName, "0", 12, syncKey)) {
            is EasResult.Success -> {
                // Сохраняем новый SyncKey
                accountDao.updateFolderSyncKey(accountId, result.data.newSyncKey)
                // Добавляем папку локально сразу (не ждём синхронизации)
                val newFolder = FolderEntity(
                    id = "${accountId}_${result.data.serverId}",
                    accountId = accountId,
                    serverId = result.data.serverId,
                    displayName = folderName,
                    parentId = "0",
                    type = 12 // User-created folder
                )
                folderDao.insertAll(listOf(newFolder))
                EasResult.Success(Unit)
            }
            is EasResult.Error -> result
        }
    }
    
    /**
     * Удаление папки с сервера
     */
    suspend fun deleteFolder(accountId: Long, folderId: String): EasResult<Unit> {
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Удаление папок поддерживается только для Exchange")
        }
        
        val folder = folderDao.getFolder(folderId)
            ?: return EasResult.Error("Папка не найдена")
        
        // Нельзя удалять системные папки
        if (folder.type in listOf(2, 3, 4, 5, 6)) {
            return EasResult.Error("Нельзя удалить системную папку")
        }
        
        val client = accountRepo.createEasClient(accountId) 
            ?: return EasResult.Error("Не удалось создать клиент")
        
        val syncKey = account.folderSyncKey
        
        return when (val result = client.deleteFolder(folder.serverId, syncKey ?: "0")) {
            is EasResult.Success -> {
                // Сохраняем новый SyncKey
                accountDao.updateFolderSyncKey(accountId, result.data)
                // Удаляем локально
                emailDao.deleteByFolder(folderId)
                folderDao.delete(folderId)
                // Синхронизируем список папок
                syncFolders(accountId)
            }
            is EasResult.Error -> {
                result
            }
        }
    }
    
    /**
     * Переименование папки на сервере
     */
    suspend fun renameFolder(accountId: Long, folderId: String, newName: String): EasResult<Unit> {
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Переименование папок поддерживается только для Exchange")
        }
        
        val folder = folderDao.getFolder(folderId)
            ?: return EasResult.Error("Папка не найдена")
        
        // Нельзя переименовывать системные папки
        if (folder.type in listOf(2, 3, 4, 5, 6)) {
            return EasResult.Error("Нельзя переименовать системную папку")
        }
        
        val client = accountRepo.createEasClient(accountId) 
            ?: return EasResult.Error("Не удалось создать клиент")
        
        val syncKey = account.folderSyncKey
        
        return when (val result = client.renameFolder(folder.serverId, newName, syncKey ?: "0")) {
            is EasResult.Success -> {
                // Сохраняем новый SyncKey
                accountDao.updateFolderSyncKey(accountId, result.data)
                // Обновляем локально
                folderDao.updateDisplayName(folderId, newName)
                // Синхронизируем список папок
                syncFolders(accountId)
            }
            is EasResult.Error -> result
        }
    }
    
    private suspend fun syncFoldersEas(accountId: Long, account: AccountEntity, retryCount: Int = 0): EasResult<Unit> {
        // Защита от бесконечной рекурсии
        if (retryCount > 1) {
            return EasResult.Error("Ошибка синхронизации папок: слишком много попыток")
        }
        
        val client = accountRepo.createEasClient(accountId) 
            ?: return EasResult.Error("Не удалось создать клиент")
        
        // Перечитываем аккаунт из базы чтобы получить актуальный SyncKey
        val freshAccount = accountRepo.getAccount(accountId) ?: account
        
        // Используем сохранённый SyncKey для инкрементальной синхронизации
        // "0" только при первом запуске (когда folderSyncKey пустой или "0")
        val savedSyncKey = freshAccount.folderSyncKey
        val syncKey = if (savedSyncKey.isNullOrEmpty() || savedSyncKey == "0") "0" else savedSyncKey
        
        val result = client.folderSync(syncKey)
        
        // Сохраняем PolicyKey сразу после любого запроса (даже при ошибке)
        // Это важно для Exchange 2007 который требует сохранённый PolicyKey
        if (client.policyKey != null && client.policyKey != account.policyKey) {
            accountRepo.savePolicyKey(accountId, client.policyKey)
        }
        
        return when (result) {
            is EasResult.Success -> {
                // Проверяем статус ответа - Status 9 означает Invalid SyncKey
                if (result.data.syncKey == "0" || result.data.syncKey.isEmpty()) {
                    // Сервер вернул невалидный SyncKey - сбрасываем и пробуем заново
                    accountDao.updateFolderSyncKey(accountId, "0")
                    return syncFoldersEas(accountId, account, retryCount + 1)
                }
                
                accountDao.updateFolderSyncKey(accountId, result.data.syncKey)
                
                // Если папки пришли - сохраняем
                if (result.data.folders.isNotEmpty()) {
                    val entities = result.data.folders.map { folder ->
                        FolderEntity(
                            id = "${accountId}_${folder.serverId}",
                            accountId = accountId,
                            serverId = folder.serverId,
                            displayName = folder.displayName,
                            parentId = folder.parentId,
                            type = folder.type
                        )
                    }
                    folderDao.insertAll(entities)
                } else if (syncKey == "0") {
                    // Первая синхронизация но папок нет - странно, но не ошибка
                }
                
                // Проверяем есть ли папки в БД после синхронизации
                val existingFolders = folderDao.getFoldersByAccountList(accountId)
                if (existingFolders.isEmpty() && syncKey != "0") {
                    // Папок нет, но SyncKey не "0" - возможно повреждённый SyncKey
                    // Сбрасываем и пробуем заново
                    accountDao.updateFolderSyncKey(accountId, "0")
                    return syncFoldersEas(accountId, account, retryCount + 1)
                }
                
                EasResult.Success(Unit)
            }
            is EasResult.Error -> {
                // При ошибке синхронизации - сбрасываем SyncKey и пробуем заново
                if (syncKey != "0" && retryCount == 0) {
                    accountDao.updateFolderSyncKey(accountId, "0")
                    return syncFoldersEas(accountId, account, retryCount + 1)
                }
                EasResult.Error(result.message)
            }
        }
    }
    
    private suspend fun syncFoldersEws(accountId: Long): EasResult<Unit> {
        // EWS больше не поддерживается, используем EAS
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        return syncFoldersEas(accountId, account)
    }
    
    private suspend fun syncFoldersImap(accountId: Long): EasResult<Unit> {
        val client = accountRepo.createImapClient(accountId) 
            ?: return EasResult.Error("Не удалось создать IMAP клиент").also {
            }
        
        return try {
            client.connect().getOrThrow()
            val result = client.getFolders()
            client.disconnect()
            
            result.fold(
                onSuccess = { folders ->
                    folderDao.insertAll(folders)
                    EasResult.Success(Unit)
                },
                onFailure = { 
                    EasResult.Error(it.message ?: "Ошибка получения папок") 
                }
            )
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка IMAP")
        }
    }
    
    private suspend fun syncFoldersPop3(accountId: Long): EasResult<Unit> {
        val client = accountRepo.createPop3Client(accountId) 
            ?: return EasResult.Error("Не удалось создать POP3 клиент")
        
        return try {
            val result = client.getFolders()
            result.fold(
                onSuccess = { folders ->
                    folderDao.insertAll(folders)
                    EasResult.Success(Unit)
                },
                onFailure = { EasResult.Error(it.message ?: "Ошибка получения папок") }
            )
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка POP3")
        }
    }
    
    /**
     * Синхронизация писем в папке
     */
    suspend fun syncEmails(accountId: Long, folderId: String): EasResult<Int> {
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        return when (AccountType.valueOf(account.accountType)) {
            AccountType.EXCHANGE -> syncEmailsEas(accountId, folderId)
            AccountType.IMAP -> syncEmailsImap(accountId, folderId)
            AccountType.POP3 -> syncEmailsPop3(accountId, folderId)
        }
    }
    
    @Suppress("unused")
    private fun parseEwsDate(dateStr: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(dateStr.substringBefore("Z"))?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
    
    private suspend fun syncEmailsEas(accountId: Long, folderId: String, retryCount: Int = 0): EasResult<Int> {
        // Защита от бесконечной рекурсии при Status 12
        if (retryCount > 1) {
            return EasResult.Error("Ошибка синхронизации: слишком много попыток")
        }
        
        val client = accountRepo.createEasClient(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        val folder = folderDao.getFolder(folderId) 
            ?: return EasResult.Error("Папка не найдена")
        
        var syncKey = folder.syncKey
        var newEmailsCount = 0  // Только новые письма в этой синхронизации
        
        // Размер окна - сколько писем за раз (увеличен для быстрой загрузки)
        val windowSize = 100
        
        // Первая синхронизация - получаем только SyncKey и сбрасываем счётчики
        if (syncKey == "0") {
            // Сбрасываем счётчики при первой синхронизации
            folderDao.updateCounts(folderId, 0, 0)
            
            when (val result = client.sync(folder.serverId, "0", windowSize)) {
                is EasResult.Success -> {
                    // Проверяем Status 12 (Invalid SyncKey) - хотя для "0" это не должно случиться
                    if (result.data.status == 12) {
                        return EasResult.Error("Ошибка синхронизации: неверный SyncKey")
                    }
                    syncKey = result.data.syncKey
                    folderDao.updateSyncKey(folderId, syncKey)
                }
                is EasResult.Error -> return EasResult.Error(result.message)
            }
        }
        
        // Получаем письма с поддержкой MoreAvailable (с лимитом итераций)
        var moreAvailable = true
        var iterations = 0
        val maxIterations = 100 // Увеличен лимит для больших папок
        var emptyResponseCount = 0
        
        while (moreAvailable && iterations < maxIterations) {
            iterations++
            when (val result = client.sync(folder.serverId, syncKey, windowSize)) {
                is EasResult.Success -> {
                    // Обработка Status 12 (Invalid SyncKey) - сбрасываем и начинаем заново
                    if (result.data.status == 12) {
                        // Сбрасываем SyncKey всех папок этого аккаунта
                        folderDao.resetAllSyncKeys(accountId)
                        // Сбрасываем FolderSyncKey аккаунта
                        accountDao.updateFolderSyncKey(accountId, "0")
                        // Рекурсивно вызываем синхронизацию с нуля (с увеличенным счётчиком)
                        return syncEmailsEas(accountId, folderId, retryCount + 1)
                    }
                    
                    // Если syncKey не изменился и нет писем - выходим (сервер в цикле)
                    if (result.data.syncKey == syncKey && result.data.emails.isEmpty()) {
                        emptyResponseCount++
                        if (emptyResponseCount >= 2) {
                            moreAvailable = false
                            break
                        }
                    } else {
                        emptyResponseCount = 0
                    }
                    
                    val prevSyncKey = syncKey
                    syncKey = result.data.syncKey
                    folderDao.updateSyncKey(folderId, syncKey)
                    moreAvailable = result.data.moreAvailable
                    if (result.data.emails.isNotEmpty()) {
                        // Вставляем письма батчем
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
                                body = "", // Ленивая загрузка - тело грузим при открытии
                                bodyType = email.bodyType,
                                dateReceived = parsedDate,
                                read = email.read,
                                importance = email.importance,
                                hasAttachments = email.attachments.isNotEmpty()
                            )
                        }
                        
                        // INSERT OR IGNORE - не дублируем существующие
                        emailDao.insertAllIgnore(emailEntities)
                        newEmailsCount += result.data.emails.size
                        
                        // Сохраняем вложения батчем
                        val allAttachments = result.data.emails.flatMap { email ->
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
                        
                        // Обновляем счётчики из БД (реальные значения)
                        val totalCount = emailDao.getCountByFolder(folderId)
                        val unreadCount = emailDao.getUnreadCount(folderId)
                        folderDao.updateCounts(folderId, unreadCount, totalCount)
                    }
                }
                is EasResult.Error -> {
                    return if (newEmailsCount > 0) {
                        EasResult.Success(newEmailsCount)
                    } else {
                        EasResult.Error(result.message)
                    }
                }
            }
        }
        
        if (iterations >= maxIterations) {
        }
        
        // Финальное обновление счётчиков из БД
        val totalCount = emailDao.getCountByFolder(folderId)
        val unreadCount = emailDao.getUnreadCount(folderId)
        folderDao.updateCounts(folderId, unreadCount, totalCount)
        return EasResult.Success(newEmailsCount)
    }
    
    private suspend fun syncEmailsImap(accountId: Long, folderId: String): EasResult<Int> {
        val client = accountRepo.createImapClient(accountId) 
            ?: return EasResult.Error("Не удалось создать IMAP клиент")
        val folder = folderDao.getFolder(folderId) 
            ?: return EasResult.Error("Папка не найдена")
        
        return try {
            client.connect().getOrThrow()
            val result = client.getEmails(folder.serverId)
            client.disconnect()
            
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
        }
    }
    
    private suspend fun syncEmailsPop3(accountId: Long, folderId: String): EasResult<Int> {
        val client = accountRepo.createPop3Client(accountId) 
            ?: return EasResult.Error("Не удалось создать POP3 клиент")
        
        return try {
            client.connect().getOrThrow()
            val result = client.getEmails()
            client.disconnect()
            
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
        }
    }
    
    suspend fun markAsRead(emailId: String, read: Boolean) {
        emailDao.updateReadStatus(emailId, read)
    }
    
    /**
     * Загружает полное тело письма с сервера (ленивая загрузка)
     * Также парсит и сохраняет MDN заголовок если есть
     */
    suspend fun loadEmailBody(emailId: String): EasResult<String> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        
        // Если тело уже загружено - возвращаем его
        if (email.body.isNotEmpty()) {
            return EasResult.Success(email.body)
        }
        
        val account = accountRepo.getAccount(email.accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        // Для EAS используем ItemOperations Fetch с парсингом MDN
        if (AccountType.valueOf(account.accountType) == AccountType.EXCHANGE) {
            val client = accountRepo.createEasClient(email.accountId)
                ?: return EasResult.Error("Не удалось создать клиент")
            
            // Получаем folderId из email.folderId (формат: accountId_serverId)
            val folderServerId = email.folderId.substringAfter("_")
            
            return when (val result = client.fetchEmailBodyWithMdn(folderServerId, email.serverId)) {
                is EasResult.Success -> {
                    // Сохраняем тело в БД
                    emailDao.updateBody(emailId, result.data.body)
                    
                    // Сохраняем MDN если есть и ещё не был отправлен
                    if (!result.data.mdnRequestedBy.isNullOrBlank() && !email.mdnSent) {
                        emailDao.updateMdnRequestedBy(emailId, result.data.mdnRequestedBy)
                    }
                    
                    EasResult.Success(result.data.body)
                }
                is EasResult.Error -> result
            }
        }
        
        return EasResult.Error("Загрузка тела не поддерживается для этого типа аккаунта")
    }
    
    suspend fun toggleFlag(emailId: String) {
        val email = emailDao.getEmail(emailId) ?: return
        emailDao.updateFlagStatus(emailId, !email.flagged)
    }
    
    suspend fun deleteEmail(emailId: String) {
        attachmentDao.deleteByEmail(emailId)
        emailDao.delete(emailId)
    }
    
    suspend fun search(accountId: Long, query: String): List<EmailEntity> {
        return emailDao.search(accountId, query)
    }
    
    suspend fun getEmailsByIds(ids: List<String>): List<EmailEntity> {
        return emailDao.getEmailsByIds(ids)
    }
    
    /**
     * Перемещение писем в другую папку
     * @param emailIds список ID писем (формат: accountId_serverId)
     * @param targetFolderId ID целевой папки (формат: accountId_serverId)
     * @param updateOriginalFolder если true - обновляет originalFolderId на целевую папку
     */
    suspend fun moveEmails(emailIds: List<String>, targetFolderId: String, updateOriginalFolder: Boolean = true): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        // Получаем первое письмо чтобы узнать accountId
        val firstEmail = emailDao.getEmail(emailIds.first()) 
            ?: return EasResult.Error("Email not found")
        
        val account = accountRepo.getAccount(firstEmail.accountId)
            ?: return EasResult.Error("Account not found")
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            return EasResult.Error("Move is only supported for Exchange")
        }
        
        val client = accountRepo.createEasClient(firstEmail.accountId)
            ?: return EasResult.Error("Failed to create client")
        
        // Получаем целевую папку
        val targetFolder = folderDao.getFolder(targetFolderId)
            ?: return EasResult.Error("Target folder not found")
        
        // Собираем данные для перемещения: (serverId письма, serverId исходной папки)
        val items = mutableListOf<Pair<String, String>>()
        for (emailId in emailIds) {
            val email = emailDao.getEmail(emailId) ?: continue
            
            // ServerId письма - используем как есть
            val emailServerId = email.serverId
            
            // Определяем исходную папку:
            // 1. Если ServerId в формате "folderId:messageId" (Exchange 2007) - берём folderId из него
            // 2. Иначе берём из email.folderId
            val srcFolderServerId = if (emailServerId.contains(":")) {
                emailServerId.substringBefore(":")
            } else {
                email.folderId.substringAfter("_")
            }
            items.add(emailServerId to srcFolderServerId)
        }
        
        if (items.isEmpty()) return EasResult.Success(0)
        
        // Проверяем что не перемещаем в ту же папку
        val srcFolderId = items.first().second
        if (srcFolderId == targetFolder.serverId) {
            return EasResult.Error("ALREADY_IN_FOLDER")
        }
        
        return when (val result = client.moveItems(items, targetFolder.serverId)) {
            is EasResult.Success -> {
                val movedCount = result.data.size
                val sourceFolderIds = mutableSetOf<String>()
                
                for (emailId in emailIds) {
                    val email = emailDao.getEmail(emailId) ?: continue
                    sourceFolderIds.add(email.folderId)
                    
                    val newServerId = result.data[email.serverId]
                    if (newServerId != null) {
                        val newEmailId = "${firstEmail.accountId}_$newServerId"
                        // Сохраняем оригинальную дату письма при перемещении
                        // Обновляем originalFolderId только если это обычное перемещение (не в корзину)
                        val newOriginalFolderId = if (updateOriginalFolder) targetFolderId else email.originalFolderId
                        val updatedEmail = email.copy(
                            id = newEmailId,
                            serverId = newServerId,
                            folderId = targetFolderId,
                            originalFolderId = newOriginalFolderId
                            // dateReceived остаётся оригинальной!
                        )
                        emailDao.delete(emailId)
                        emailDao.insert(updatedEmail)
                    }
                }
                
                for (srcFolderId in sourceFolderIds) {
                    val totalCount = emailDao.getCountByFolder(srcFolderId)
                    val unreadCount = emailDao.getUnreadCount(srcFolderId)
                    folderDao.updateCounts(srcFolderId, unreadCount, totalCount)
                }
                
                val targetTotalCount = emailDao.getCountByFolder(targetFolderId)
                val targetUnreadCount = emailDao.getUnreadCount(targetFolderId)
                folderDao.updateCounts(targetFolderId, targetUnreadCount, targetTotalCount)
                EasResult.Success(movedCount)
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
        
        // Находим папку Спам (тип 11 = Junk Email)
        val spamFolder = folderDao.getFolderByType(firstEmail.accountId, 11)
            ?: return EasResult.Error("Spam folder not found")
        
        return moveEmails(emailIds, spamFolder.id)
    }
    
    /**
     * Перемещение письма в корзину или окончательное удаление
     * Если письмо уже в корзине - удаляет окончательно
     * Возвращает: положительное число = перемещено в корзину, 0 = удалено окончательно
     */
    suspend fun moveToTrash(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        val firstEmail = emailDao.getEmail(emailIds.first())
            ?: return EasResult.Error("Email not found")
        
        // Находим папку Удалённые (тип 4 = Deleted Items)
        val trashFolder = folderDao.getFolderByType(firstEmail.accountId, 4)
            ?: return EasResult.Error("Trash folder not found")
        
        // Проверяем, находятся ли письма уже в корзине
        // folderId письма в формате "accountId_serverId"
        val emailFolderId = firstEmail.folderId
        val trashFolderId = trashFolder.id
        if (emailFolderId == trashFolderId) {
            // Письма уже в корзине - удаляем окончательно
            return when (val result = deleteEmailsPermanently(emailIds)) {
                is EasResult.Success -> EasResult.Success(0) // 0 = удалено окончательно
                is EasResult.Error -> result
            }
        }
        
        // Сохраняем исходную папку перед перемещением в корзину
        for (emailId in emailIds) {
            val email = emailDao.getEmail(emailId) ?: continue
            // Всегда обновляем originalFolderId на текущую папку перед удалением
            emailDao.updateOriginalFolderId(emailId, email.folderId)
        }
        
        // Перемещаем в корзину БЕЗ обновления originalFolderId (чтобы сохранить исходную папку)
        return moveEmails(emailIds, trashFolder.id, updateOriginalFolder = false)
    }
    
    /**
     * Восстановление письма из корзины в исходную папку
     * Если исходная папка не сохранена - восстанавливает во Входящие
     */
    suspend fun restoreFromTrash(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        val firstEmail = emailDao.getEmail(emailIds.first())
            ?: return EasResult.Error("Email not found")
        
        // Определяем целевую папку: исходная или Входящие
        val targetFolderId = firstEmail.originalFolderId?.takeIf { it.isNotEmpty() }
            ?: folderDao.getFolderByType(firstEmail.accountId, 2)?.id // Входящие (тип 2)
            ?: return EasResult.Error("Target folder not found")
        
        // Перемещаем (originalFolderId обновится на целевую папку автоматически)
        return moveEmails(emailIds, targetFolderId)
    }
    
    /**
     * Окончательное удаление писем (без перемещения в корзину)
     */
    suspend fun deleteEmailsPermanently(emailIds: List<String>): EasResult<Int> {
        if (emailIds.isEmpty()) return EasResult.Success(0)
        
        val firstEmail = emailDao.getEmail(emailIds.first())
            ?: return EasResult.Error("Email not found")
        
        val account = accountRepo.getAccount(firstEmail.accountId)
            ?: return EasResult.Error("Account not found")
        
        // Запоминаем папки для обновления счётчиков
        val affectedFolderIds = mutableSetOf<String>()
        
        if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
            // Для не-Exchange просто удаляем локально
            emailIds.forEach { emailId ->
                val email = emailDao.getEmail(emailId)
                if (email != null) {
                    affectedFolderIds.add(email.folderId)
                }
                attachmentDao.deleteByEmail(emailId)
                emailDao.delete(emailId)
            }
            // Обновляем счётчики
            for (folderId in affectedFolderIds) {
                val totalCount = emailDao.getCountByFolder(folderId)
                val unreadCount = emailDao.getUnreadCount(folderId)
                folderDao.updateCounts(folderId, unreadCount, totalCount)
            }
            return EasResult.Success(emailIds.size)
        }
        
        val client = accountRepo.createEasClient(firstEmail.accountId)
            ?: return EasResult.Error("Failed to create client")
        
        var deletedCount = 0
        
        for (emailId in emailIds) {
            val email = emailDao.getEmail(emailId) ?: continue
            affectedFolderIds.add(email.folderId)
            
            // Определяем папку письма
            val folderServerId = if (email.serverId.contains(":")) {
                email.serverId.substringBefore(":")
            } else {
                email.folderId.substringAfter("_")
            }
            
            // Получаем syncKey папки
            val folder = folderDao.getFolder(email.folderId)
            val syncKey = folder?.syncKey ?: "0"
            
            if (syncKey == "0") {
                continue
            }
            
            // Удаляем через Sync Delete (DeletesAsMoves=0 для окончательного удаления)
            when (val result = client.deleteEmailPermanently(folderServerId, email.serverId, syncKey)) {
                is EasResult.Success -> {
                    // Обновляем syncKey папки
                    folderDao.updateSyncKey(email.folderId, result.data)
                    // Удаляем локально
                    attachmentDao.deleteByEmail(emailId)
                    emailDao.delete(emailId)
                    deletedCount++
                }
                is EasResult.Error -> {
                }
            }
        }
        
        // Обновляем счётчики затронутых папок
        for (folderId in affectedFolderIds) {
            val totalCount = emailDao.getCountByFolder(folderId)
            val unreadCount = emailDao.getUnreadCount(folderId)
            folderDao.updateCounts(folderId, unreadCount, totalCount)
        }
        
        return EasResult.Success(deletedCount)
    }
    
    /**
     * Отправка отчёта о прочтении (MDN)
     */
    suspend fun sendMdn(emailId: String): EasResult<Boolean> {
        val email = emailDao.getEmail(emailId) ?: return EasResult.Error("Письмо не найдено")
        
        // Проверяем что MDN ещё не отправлен
        if (email.mdnSent) {
            return EasResult.Success(true)
        }
        
        // Проверяем что есть кому отправлять
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
        
        return when (val result = client.sendMdn(mdnTo, email.subject)) {
            is EasResult.Success -> {
                // Помечаем что MDN отправлен
                emailDao.updateMdnSent(emailId, true)
                EasResult.Success(true)
            }
            is EasResult.Error -> result
        }
    }
    
    /**
     * Помечает что MDN отправлен (без фактической отправки)
     */
    suspend fun markMdnSent(emailId: String) {
        emailDao.updateMdnSent(emailId, true)
    }

    suspend fun getUnreadCount(accountId: Long): Int {
        return emailDao.getUnreadCountByAccount(accountId)
    }
    
    suspend fun updateAttachmentPath(attachmentId: Long, path: String) {
        attachmentDao.updateLocalPath(attachmentId, path)
    }
    
    private fun extractName(email: String): String {
        // Обработка X.500 Distinguished Name
        // Пример: "username" </O=ORG/OU=EXCHANGE.../CN=USERNAME>
        if (email.contains("/O=") || email.contains("/OU=") || email.contains("/CN=")) {
            // Извлекаем CN (Common Name)
            val cnMatch = Regex("CN=([^/><]+)", RegexOption.IGNORE_CASE).find(email)
            if (cnMatch != null) {
                val cn = cnMatch.groupValues[1].trim()
                // Если CN это RECIPIENTS, ищем следующий CN
                if (cn.equals("RECIPIENTS", ignoreCase = true)) {
                    val allCns = Regex("CN=([^/><]+)", RegexOption.IGNORE_CASE).findAll(email).toList()
                    val lastCn = allCns.lastOrNull()?.groupValues?.get(1)?.trim()
                    if (lastCn != null && !lastCn.equals("RECIPIENTS", ignoreCase = true)) {
                        return lastCn.lowercase().replaceFirstChar { it.uppercase() }
                    }
                }
                return cn.lowercase().replaceFirstChar { it.uppercase() }
            }
            // Fallback - берём имя до </O=
            val nameMatch = Regex("^\"?([^\"<]+)\"?\\s*<").find(email)
            if (nameMatch != null) {
                return nameMatch.groupValues[1].trim()
            }
        }
        
        // Стандартный формат: "John Doe <john@example.com>" -> "John Doe"
        val match = Regex("^\"?([^\"<]+)\"?\\s*<").find(email)
        return match?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") 
            ?: email.substringBefore("@").substringBefore("<").trim()
    }
    
    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) {
            // Пустая дата - ставим 0 чтобы письмо было внизу списка
            return 0L
        }
        
        return try {
            // ISO 8601: 2024-01-15T10:30:00.000Z или 2024-01-15T10:30:00Z
            val cleaned = dateStr.replace("Z", "+0000")
            
            // Пробуем разные форматы
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
            
            // Не удалось распарсить - ставим 0 чтобы письмо было внизу
            0L
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Удаляет HTML теги из текста для preview
     */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "") // Удаляем стили
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "") // Удаляем скрипты
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "") // Удаляем комментарии
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ") // <br> -> пробел
            .replace(Regex("<[^>]+>"), "") // Удаляем все теги
            .replace(Regex("&nbsp;"), " ") // &nbsp; -> пробел
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#\\d+;")) { match ->
                // Декодируем числовые HTML entities
                try {
                    val code = match.value.drop(2).dropLast(1).toInt()
                    code.toChar().toString()
                } catch (e: Exception) {
                    ""
                }
            }
            .replace(Regex("\\s+"), " ") // Множественные пробелы -> один
            .trim()
    }
}


package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.onSuccessResult
import com.dedovmosol.iwomail.eas.mapResult
import com.dedovmosol.iwomail.eas.flatMapResult

/**
 * Сервис для синхронизации и операций с папками
 * Single Responsibility: операции с папками (sync, create, delete, rename)
 */
class FolderSyncService(
    private val context: Context,
    private val folderDao: FolderDao,
    private val emailDao: EmailDao,
    private val accountDao: AccountDao,
    private val accountRepo: AccountRepository
) {
    /**
     * Безопасное обновление папки без удаления связанных писем
     * Использует INSERT IGNORE + UPDATE вместо REPLACE
     */
    suspend fun upsertFolder(folder: FolderEntity) {
        folderDao.insert(folder)
        folderDao.updateCounts(folder.id, folder.unreadCount, folder.totalCount)
    }
    
    /**
     * Безопасное обновление списка папок без удаления связанных писем
     * Использует batch операцию в DAO с @Transaction
     */
    suspend fun upsertFolders(folders: List<FolderEntity>) {
        if (folders.isEmpty()) return
        folderDao.upsertFolders(folders)
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
        
        // Синхронизируем папки ПЕРЕД созданием для получения актуального syncKey
        syncFolders(accountId)
        
        // Получаем обновлённый аккаунт с актуальным syncKey
        val freshAccount = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        
        val client = accountRepo.getEasClientOrError<Unit>(accountId) { return it }
        
        val syncKey = freshAccount.folderSyncKey ?: "0"
        
        val result = client.createFolder(folderName, "0", 12, syncKey)
        return result
            .onSuccessResult { response ->
                accountDao.updateFolderSyncKey(accountId, response.newSyncKey)
                val newFolder = FolderEntity(
                    id = "${accountId}_${response.serverId}",
                    accountId = accountId,
                    serverId = response.serverId,
                    displayName = folderName,
                    parentId = "0",
                    type = FolderType.USER_CREATED
                )
                upsertFolder(newFolder)
            }
            .mapResult { Unit }
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
        
        if (FolderType.isSystemFolder(folder.type)) {
            return EasResult.Error("Нельзя удалить системную папку")
        }
        
        val client = accountRepo.getEasClientOrError<Unit>(accountId) { return it }
        
        val syncKey = account.folderSyncKey
        
        val result = client.deleteFolder(folder.serverId, syncKey ?: "0")
        return result
            .onSuccessResult { newSyncKey ->
                accountDao.updateFolderSyncKey(accountId, newSyncKey)
                emailDao.deleteByFolder(folderId)
                folderDao.delete(folderId)
            }
            .flatMapResult { syncFolders(accountId) }
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
        
        if (FolderType.isSystemFolder(folder.type)) {
            return EasResult.Error("Нельзя переименовать системную папку")
        }
        
        val client = accountRepo.getEasClientOrError<Unit>(accountId) { return it }
        
        val syncKey = account.folderSyncKey
        
        val result = client.renameFolder(folder.serverId, newName, syncKey ?: "0")
        return result
            .onSuccessResult { newSyncKey ->
                accountDao.updateFolderSyncKey(accountId, newSyncKey)
                folderDao.updateDisplayName(folderId, newName)
            }
            .flatMapResult { syncFolders(accountId) }
    }
    
    /**
     * Синхронизация папок через EAS
     */
    suspend fun syncFoldersEas(accountId: Long, account: AccountEntity, retryCount: Int = 0): EasResult<Unit> {
        if (retryCount > 1) {
            return EasResult.Error("Ошибка синхронизации папок: слишком много попыток")
        }
        
        val client = accountRepo.createEasClient(accountId) 
            ?: return EasResult.Error("Не удалось создать клиент")
        
        val freshAccount = accountRepo.getAccount(accountId) ?: account
        
        val savedSyncKey = freshAccount.folderSyncKey
        val syncKey = if (savedSyncKey.isNullOrEmpty() || savedSyncKey == "0") "0" else savedSyncKey
        
        val result = client.folderSync(syncKey)
        
        if (client.policyKey != null && client.policyKey != account.policyKey) {
            accountRepo.savePolicyKey(accountId, client.policyKey)
        }
        
        return when (result) {
            is EasResult.Success -> {
                if (result.data.syncKey == "0" || result.data.syncKey.isEmpty()) {
                    accountDao.updateFolderSyncKey(accountId, "0")
                    return syncFoldersEas(accountId, account, retryCount + 1)
                }
                
                accountDao.updateFolderSyncKey(accountId, result.data.syncKey)
                
                if (result.data.deletedFolderIds.isNotEmpty()) {
                    for (serverId in result.data.deletedFolderIds) {
                        val folderId = "${accountId}_${serverId}"
                        emailDao.deleteByFolder(folderId)
                        folderDao.delete(folderId)
                    }
                }
                
                if (result.data.folders.isNotEmpty()) {
                    val entities = result.data.folders.map { folder ->
                        val folderId = "${accountId}_${folder.serverId}"
                        val existingFolder = folderDao.getFolder(folderId)
                        if (folder.type == FolderType.DRAFTS && existingFolder != null) {
                            existingFolder.copy(
                                displayName = folder.displayName,
                                parentId = folder.parentId
                            )
                        } else {
                            FolderEntity(
                                id = folderId,
                                accountId = accountId,
                                serverId = folder.serverId,
                                displayName = folder.displayName,
                                parentId = folder.parentId,
                                type = folder.type,
                                syncKey = existingFolder?.syncKey ?: "0",
                                totalCount = existingFolder?.totalCount ?: 0,
                                unreadCount = existingFolder?.unreadCount ?: 0
                            )
                        }
                    }
                    upsertFolders(entities)
                }
                
                val existingFolders = folderDao.getFoldersByAccountList(accountId)
                // ИСПРАВЛЕНО: Проверяем наличие ОБЯЗАТЕЛЬНЫХ системных папок.
                // Если папок нет совсем, или отсутствуют ключевые (Inbox/Sent/Drafts/Deleted),
                // значит FolderSync вернул неполные данные — сбрасываем и запрашиваем заново.
                val hasInbox = existingFolders.any { it.type == FolderType.INBOX }
                val hasSent = existingFolders.any { it.type == FolderType.SENT_ITEMS }
                val hasDrafts = existingFolders.any { it.type == FolderType.DRAFTS }
                val hasDeleted = existingFolders.any { it.type == FolderType.DELETED_ITEMS }
                val missingSystemFolders = !hasInbox || !hasSent || !hasDrafts || !hasDeleted
                
                if ((existingFolders.isEmpty() || missingSystemFolders) && result.data.syncKey != "0" && retryCount == 0) {
                    android.util.Log.w("FolderSyncService", 
                        "Missing system folders after sync (inbox=$hasInbox, sent=$hasSent, drafts=$hasDrafts, deleted=$hasDeleted). Resetting folderSyncKey.")
                    accountDao.updateFolderSyncKey(accountId, "0")
                    return syncFoldersEas(accountId, account, retryCount + 1)
                }
                
                // КРИТИЧНО: Обновляем счетчики из БД после синхронизации папок
                // FolderSync не возвращает счетчики - берем из локальной БД
                existingFolders.forEach { folder ->
                    val totalCount = emailDao.getCountByFolder(folder.id)
                    val unreadCount = emailDao.getUnreadCount(folder.id)
                    folderDao.updateCounts(folder.id, unreadCount, totalCount)
                }
                
                EasResult.Success(Unit)
            }
            is EasResult.Error -> {
                if (syncKey != "0" && retryCount == 0) {
                    accountDao.updateFolderSyncKey(accountId, "0")
                    return syncFoldersEas(accountId, account, retryCount + 1)
                }
                EasResult.Error(result.message)
            }
        }
    }
    
    /**
     * Синхронизация папок через EWS (fallback на EAS)
     */
    suspend fun syncFoldersEws(accountId: Long): EasResult<Unit> {
        val account = accountRepo.getAccount(accountId) 
            ?: return EasResult.Error("Аккаунт не найден")
        return syncFoldersEas(accountId, account)
    }
    
    /**
     * Синхронизация папок через IMAP
     */
    suspend fun syncFoldersImap(accountId: Long): EasResult<Unit> {
        val client = accountRepo.createImapClient(accountId) 
            ?: return EasResult.Error("Не удалось создать IMAP клиент")
        
        return try {
            client.connect().getOrThrow()
            val result = client.getFolders()
            client.disconnect()
            
            result.fold(
                onSuccess = { folders ->
                    val updatedFolders = folders.map { folder ->
                        if (folder.type == FolderType.DRAFTS) {
                            val existingFolder = folderDao.getFolder(folder.id)
                            if (existingFolder != null) {
                                folder.copy(totalCount = existingFolder.totalCount)
                            } else folder
                        } else folder
                    }
                    upsertFolders(updatedFolders)
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
    
    /**
     * Синхронизация папок через POP3
     */
    suspend fun syncFoldersPop3(accountId: Long): EasResult<Unit> {
        val client = accountRepo.createPop3Client(accountId) 
            ?: return EasResult.Error("Не удалось создать POP3 клиент")
        
        return try {
            val result = client.getFolders()
            result.fold(
                onSuccess = { folders ->
                    val updatedFolders = folders.map { folder ->
                        if (folder.type == FolderType.DRAFTS) {
                            val existingFolder = folderDao.getFolder(folder.id)
                            if (existingFolder != null) {
                                folder.copy(totalCount = existingFolder.totalCount)
                            } else folder
                        } else folder
                    }
                    upsertFolders(updatedFolders)
                    EasResult.Success(Unit)
                },
                onFailure = { EasResult.Error(it.message ?: "Ошибка получения папок") }
            )
        } catch (e: Exception) {
            EasResult.Error(e.message ?: "Ошибка POP3")
        }
    }
}

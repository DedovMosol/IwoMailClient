package com.dedovmosol.iwomail.imap

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.shared.MailClient
import com.dedovmosol.iwomail.shared.MailSessionProperties
import com.dedovmosol.iwomail.shared.MessageToEntityConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.mail.*


/**
 * Клиент для работы с IMAP серверами
 */
class ImapClient(
    private val account: AccountEntity,
    private val password: String = "",
    private val clientCertificatePassword: String? = null
) : MailClient {
    
    private var store: Store? = null
    
    private fun getSession(): Session = Session.getInstance(
        MailSessionProperties.forAccount(MailSessionProperties.Protocol.IMAP, account, clientCertificatePassword)
    )
    
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = getSession()
            val protocol = MailSessionProperties.storeProtocol(MailSessionProperties.Protocol.IMAP, account.useSSL)
            store = session.getStore(protocol).apply {
                connect(account.serverUrl, account.incomingPort, account.username, password)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // NET-5: structured concurrency — не глушим отмену
            Result.failure(e)
        }
    }
    
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            store?.close()
            store = null
        } catch (_: Exception) {}
    }
    
    override suspend fun getFolders(): Result<List<FolderEntity>> = withContext(Dispatchers.IO) {
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val folders = mutableListOf<FolderEntity>()
            
            store.defaultFolder.list("*").forEach { folder ->
                val type = when {
                    folder.name.equals("INBOX", ignoreCase = true) -> 2 // Inbox
                    folder.name.contains("sent", ignoreCase = true) -> 5 // Sent
                    folder.name.contains("draft", ignoreCase = true) -> 3 // Drafts
                    folder.name.contains("trash", ignoreCase = true) -> 4 // Deleted
                    folder.name.contains("junk", ignoreCase = true) || 
                        folder.name.contains("spam", ignoreCase = true) -> 11 // Junk
                    else -> 1 // User folder
                }
                
                folders.add(FolderEntity(
                    id = "${account.id}_${folder.fullName}",
                    accountId = account.id,
                    serverId = folder.fullName,
                    displayName = folder.name,
                    parentId = "",
                    type = type,
                    syncKey = "0",
                    unreadCount = 0
                ))
            }
            
            Result.success(folders)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // NET-5: structured concurrency — не глушим отмену
            Result.failure(e)
        }
    }
    
    suspend fun createFolder(folderName: String): Result<FolderEntity> = withContext(Dispatchers.IO) {
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val newFolder = store.getFolder(folderName)
            
            if (newFolder.exists()) {
                return@withContext Result.failure(Exception("Папка уже существует"))
            }
            
            val created = newFolder.create(Folder.HOLDS_MESSAGES)
            if (!created) {
                return@withContext Result.failure(Exception("Не удалось создать папку"))
            }
            
            Result.success(FolderEntity(
                id = "${account.id}_$folderName",
                accountId = account.id,
                serverId = folderName,
                displayName = folderName,
                parentId = "",
                type = 1, // User folder
                syncKey = "0",
                unreadCount = 0
            ))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // NET-5: structured concurrency — не глушим отмену
            Result.failure(e)
        }
    }
    
    suspend fun deleteFolder(folderName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val folder = store.getFolder(folderName)
            
            if (!folder.exists()) {
                return@withContext Result.failure(Exception("Папка не найдена"))
            }
            
            if (folder.isOpen) {
                folder.close(false)
            }
            
            val deleted = folder.delete(true)
            if (!deleted) {
                return@withContext Result.failure(Exception("Не удалось удалить папку"))
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // NET-5: structured concurrency — не глушим отмену
            Result.failure(e)
        }
    }
    
    suspend fun renameFolder(oldName: String, newName: String): Result<FolderEntity> = withContext(Dispatchers.IO) {
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val oldFolder = store.getFolder(oldName)
            
            if (!oldFolder.exists()) {
                return@withContext Result.failure(Exception("Папка не найдена"))
            }
            
            val newFolder = store.getFolder(newName)
            if (newFolder.exists()) {
                return@withContext Result.failure(Exception("Папка с таким именем уже существует"))
            }
            
            if (oldFolder.isOpen) {
                oldFolder.close(false)
            }
            
            val renamed = oldFolder.renameTo(newFolder)
            if (!renamed) {
                return@withContext Result.failure(Exception("Не удалось переименовать папку"))
            }
            
            Result.success(FolderEntity(
                id = "${account.id}_$newName",
                accountId = account.id,
                serverId = newName,
                displayName = newName,
                parentId = "",
                type = 1,
                syncKey = "0",
                unreadCount = 0
            ))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // NET-5: structured concurrency — не глушим отмену
            Result.failure(e)
        }
    }

    
    override suspend fun getEmails(folderId: String, limit: Int): Result<List<EmailEntity>> = withContext(Dispatchers.IO) {
        val folderName = folderId // folderId здесь — это имя папки IMAP
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            
            val emails = try {
                val result = mutableListOf<EmailEntity>()
                val messageCount = folder.messageCount
                val start = maxOf(1, messageCount - limit + 1)
                
                if (messageCount > 0) {
                    val messages = folder.getMessages(start, messageCount)
                    FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add(FetchProfile.Item.CONTENT_INFO)
                    }.let { folder.fetch(messages, it) }
                    
                    messages.reversed().forEach { message ->
                        result.add(messageToEntity(message, folderName))
                    }
                }
                result
            } finally {
                try { folder.close(false) } catch (_: Exception) {}
            }
            
            Result.success(emails)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e // NET-5: structured concurrency — не глушим отмену
            Result.failure(e)
        }
    }
    
    private fun messageToEntity(message: Message, folderId: String): EmailEntity {
        return MessageToEntityConverter.convert(
            fallbackIdentity = {
                val mailbox = message.folder as? javax.mail.UIDFolder
                val validity = mailbox?.uidValidity ?: -1L
                val uid = mailbox?.getUID(message) ?: -1L
                if (validity > 0 && uid > 0) "imap:${folderId.length}:$folderId:$validity:$uid" else null
            },
            message = message,
            accountId = account.id,
            folderId = folderId,
            useMessageFlags = true // IMAP поддерживает флаги
        )
    }
}


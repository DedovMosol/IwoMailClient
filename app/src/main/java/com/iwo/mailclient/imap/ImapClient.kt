package com.iwo.mailclient.imap

import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.EmailEntity
import com.iwo.mailclient.data.database.FolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

// Предкомпилированный regex для производительности
private val HTML_TAG_REGEX = Regex("<[^>]*>")

/**
 * Клиент для работы с IMAP серверами
 */
class ImapClient(
    private val account: AccountEntity,
    private val password: String = ""
) {
    
    private var store: Store? = null
    
    private fun getSession(): Session {
        val protocol = if (account.useSSL) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.imap.host", account.serverUrl)
            put("mail.imap.port", account.incomingPort.toString())
            put("mail.imap.ssl.enable", account.useSSL.toString())
            put("mail.imap.starttls.enable", "false")
            if (account.acceptAllCerts) {
                put("mail.imap.ssl.trust", "*")
                put("mail.imaps.ssl.trust", "*")
                put("mail.imap.ssl.checkserveridentity", "false")
            }
            // Уменьшаем таймаут для быстрой диагностики
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "15000")
        }
        return Session.getInstance(props)
    }
    
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = getSession()
            val protocol = if (account.useSSL) "imaps" else "imap"
            store = session.getStore(protocol).apply {
                connect(account.serverUrl, account.incomingPort, account.username, password)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            store?.close()
            store = null
        } catch (_: Exception) {}
    }
    
    suspend fun getFolders(): Result<List<FolderEntity>> = withContext(Dispatchers.IO) {
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
            Result.failure(e)
        }
    }

    
    suspend fun getEmails(folderName: String, limit: Int = 50): Result<List<EmailEntity>> = withContext(Dispatchers.IO) {
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            
            val emails = mutableListOf<EmailEntity>()
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
                    emails.add(messageToEntity(message, folderName))
                }
            }
            
            folder.close(false)
            Result.success(emails)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun messageToEntity(message: Message, folderId: String): EmailEntity {
        val from = (message.from?.firstOrNull() as? InternetAddress)
        val messageId = (message as? MimeMessage)?.messageID ?: UUID.randomUUID().toString()
        
        return EmailEntity(
            id = "${account.id}_$messageId",
            accountId = account.id,
            folderId = "${account.id}_$folderId",
            serverId = messageId,
            from = from?.address ?: "",
            fromName = from?.personal ?: from?.address ?: "",
            to = message.getRecipients(Message.RecipientType.TO)
                ?.joinToString(", ") { (it as? InternetAddress)?.address ?: "" } ?: "",
            cc = message.getRecipients(Message.RecipientType.CC)
                ?.joinToString(", ") { (it as? InternetAddress)?.address ?: "" } ?: "",
            subject = message.subject ?: "(No subject)",
            preview = getPreview(message),
            body = getBody(message),
            bodyType = if (getBody(message).contains("<html", ignoreCase = true)) 2 else 1,
            dateReceived = message.receivedDate?.time ?: message.sentDate?.time ?: System.currentTimeMillis(),
            read = message.flags.contains(Flags.Flag.SEEN),
            flagged = message.flags.contains(Flags.Flag.FLAGGED),
            importance = 1,
            hasAttachments = hasAttachments(message)
        )
    }
    
    private fun getPreview(message: Message): String {
        return try {
            getBody(message).take(200).replace(HTML_TAG_REGEX, "").trim()
        } catch (_: Exception) { "" }
    }
    
    private fun getBody(message: Message): String {
        return try {
            when (val content = message.content) {
                is String -> content
                is MimeMultipart -> extractTextFromMultipart(content)
                else -> ""
            }
        } catch (_: Exception) { "" }
    }
    
    private fun extractTextFromMultipart(multipart: MimeMultipart): String {
        var html = ""
        var text = ""
        
        for (i in 0 until multipart.count) {
            val part = multipart.getBodyPart(i)
            when {
                part.isMimeType("text/plain") -> text = part.content as? String ?: ""
                part.isMimeType("text/html") -> html = part.content as? String ?: ""
                part.content is MimeMultipart -> {
                    val nested = extractTextFromMultipart(part.content as MimeMultipart)
                    if (nested.isNotEmpty()) return nested
                }
            }
        }
        
        return html.ifEmpty { text }
    }
    
    private fun hasAttachments(message: Message): Boolean {
        return try {
            val content = message.content
            if (content is MimeMultipart) {
                for (i in 0 until content.count) {
                    val part = content.getBodyPart(i)
                    if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) { false }
    }
}


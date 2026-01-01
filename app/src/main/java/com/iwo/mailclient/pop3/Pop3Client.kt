package com.iwo.mailclient.pop3

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
 * Клиент для работы с POP3 серверами
 * POP3 поддерживает только папку "Входящие"
 */
class Pop3Client(
    private val account: AccountEntity,
    private val password: String = ""
) {
    
    private var store: Store? = null
    
    private fun getSession(): Session {
        val protocol = if (account.useSSL) "pop3s" else "pop3"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.pop3.host", account.serverUrl)
            put("mail.pop3.port", account.incomingPort.toString())
            put("mail.pop3.ssl.enable", account.useSSL.toString())
            put("mail.pop3.starttls.enable", "false")
            if (account.acceptAllCerts) {
                put("mail.pop3.ssl.trust", "*")
                put("mail.pop3s.ssl.trust", "*")
            }
            // Уменьшаем таймаут
            put("mail.pop3.connectiontimeout", "15000")
            put("mail.pop3.timeout", "15000")
        }
        return Session.getInstance(props)
    }
    
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = getSession()
            val protocol = if (account.useSSL) "pop3s" else "pop3"
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
    
    /**
     * POP3 поддерживает только INBOX
     */
    suspend fun getFolders(): Result<List<FolderEntity>> = withContext(Dispatchers.IO) {
        Result.success(listOf(
            FolderEntity(
                id = "${account.id}_INBOX",
                accountId = account.id,
                serverId = "INBOX",
                displayName = "Входящие",
                parentId = "",
                type = 2, // Inbox
                syncKey = "0",
                unreadCount = 0
            )
        ))
    }

    
    suspend fun getEmails(limit: Int = 50): Result<List<EmailEntity>> = withContext(Dispatchers.IO) {
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            
            val emails = mutableListOf<EmailEntity>()
            val messageCount = folder.messageCount
            val start = maxOf(1, messageCount - limit + 1)
            
            if (messageCount > 0) {
                val messages = folder.getMessages(start, messageCount)
                
                messages.reversed().forEach { message ->
                    emails.add(messageToEntity(message))
                }
            }
            
            folder.close(false)
            Result.success(emails)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun messageToEntity(message: Message): EmailEntity {
        val from = (message.from?.firstOrNull() as? InternetAddress)
        val messageId = (message as? MimeMessage)?.messageID ?: UUID.randomUUID().toString()
        
        return EmailEntity(
            id = "${account.id}_$messageId",
            accountId = account.id,
            folderId = "${account.id}_INBOX",
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
            read = false, // POP3 не хранит статус прочтения на сервере
            flagged = false,
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


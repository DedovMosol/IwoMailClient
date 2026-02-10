package com.dedovmosol.iwomail.pop3

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.shared.MailClient
import com.dedovmosol.iwomail.shared.MessageToEntityConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.mail.*


/**
 * Клиент для работы с POP3 серверами
 * POP3 поддерживает только папку "Входящие"
 */
class Pop3Client(
    private val account: AccountEntity,
    private val password: String = ""
) : MailClient {
    
    private var store: Store? = null
    
    private fun getSession(): Session {
        val protocol = if (account.useSSL) "pop3s" else "pop3"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            // Устанавливаем properties для ОБОИХ протоколов (pop3 и pop3s),
            // т.к. JavaMail использует prefix "mail.pop3s.*" для SSL-протокола
            put("mail.pop3.host", account.serverUrl)
            put("mail.pop3s.host", account.serverUrl)
            put("mail.pop3.port", account.incomingPort.toString())
            put("mail.pop3s.port", account.incomingPort.toString())
            put("mail.pop3.ssl.enable", account.useSSL.toString())
            put("mail.pop3s.ssl.enable", account.useSSL.toString())
            put("mail.pop3.starttls.enable", "false")
            put("mail.pop3s.starttls.enable", "false")
            if (account.acceptAllCerts) {
                put("mail.pop3.ssl.trust", "*")
                put("mail.pop3s.ssl.trust", "*")
            }
            // Таймауты для обоих протоколов
            put("mail.pop3.connectiontimeout", "15000")
            put("mail.pop3.timeout", "15000")
            put("mail.pop3s.connectiontimeout", "15000")
            put("mail.pop3s.timeout", "15000")
        }
        return Session.getInstance(props)
    }
    
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
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
    
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            store?.close()
            store = null
        } catch (_: Exception) {}
    }
    
    /**
     * POP3 поддерживает только INBOX
     */
    override suspend fun getFolders(): Result<List<FolderEntity>> = withContext(Dispatchers.IO) {
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

    
    /**
     * Для POP3 параметр folderId игнорируется — всегда INBOX
     */
    override suspend fun getEmails(folderId: String, limit: Int): Result<List<EmailEntity>> = withContext(Dispatchers.IO) {
        try {
            val store = store ?: return@withContext Result.failure(Exception("Not connected"))
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            
            val emails = try {
                val result = mutableListOf<EmailEntity>()
                val messageCount = folder.messageCount
                val start = maxOf(1, messageCount - limit + 1)
                
                if (messageCount > 0) {
                    val messages = folder.getMessages(start, messageCount)
                    
                    messages.reversed().forEach { message ->
                        result.add(messageToEntity(message))
                    }
                }
                result
            } finally {
                try { folder.close(false) } catch (_: Exception) {}
            }
            
            Result.success(emails)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun messageToEntity(message: Message): EmailEntity {
        return MessageToEntityConverter.convert(
            message = message,
            accountId = account.id,
            folderId = "INBOX", // POP3 поддерживает только INBOX
            useMessageFlags = false // POP3 не хранит статус прочтения на сервере
        )
    }
}


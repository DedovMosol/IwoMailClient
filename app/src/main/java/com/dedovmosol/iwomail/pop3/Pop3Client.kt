package com.dedovmosol.iwomail.pop3

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
 * Клиент для работы с POP3 серверами
 * POP3 поддерживает только папку "Входящие"
 */
class Pop3Client(
    private val account: AccountEntity,
    private val password: String = ""
) : MailClient {
    
    private var store: Store? = null
    
    private fun getSession(): Session = Session.getInstance(
        MailSessionProperties.build(
            MailSessionProperties.Protocol.POP3, account.serverUrl, account.incomingPort,
            useSsl = account.useSSL, acceptAllCerts = account.acceptAllCerts
        )
    )
    
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = getSession()
            val protocol = MailSessionProperties.storeProtocol(MailSessionProperties.Protocol.POP3, account.useSSL)
            store = session.getStore(protocol).apply {
                connect(account.serverUrl, account.incomingPort, account.username, password)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            if (e is kotlinx.coroutines.CancellationException) throw e
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


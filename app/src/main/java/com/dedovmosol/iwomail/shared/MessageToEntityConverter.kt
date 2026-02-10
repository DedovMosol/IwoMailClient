package com.dedovmosol.iwomail.shared

import com.dedovmosol.iwomail.data.database.EmailEntity
import java.util.UUID
import javax.mail.Flags
import javax.mail.Message
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Конвертер JavaMail Message в EmailEntity.
 * Вынесен в shared для соблюдения DRY - используется в ImapClient и Pop3Client.
 */
object MessageToEntityConverter {
    
    /**
     * Конвертирует JavaMail Message в EmailEntity.
     * 
     * @param message JavaMail сообщение
     * @param accountId ID аккаунта
     * @param folderId ID папки (для POP3 обычно "INBOX")
     * @param useMessageFlags если true, читает read/flagged из флагов сообщения (IMAP),
     *                        если false, устанавливает read=false, flagged=false (POP3)
     */
    fun convert(
        message: Message,
        accountId: Long,
        folderId: String,
        useMessageFlags: Boolean = true
    ): EmailEntity {
        val from = (message.from?.firstOrNull() as? InternetAddress)
        val messageId = (message as? MimeMessage)?.messageID ?: UUID.randomUUID().toString()
        
        val read = if (useMessageFlags) {
            message.flags.contains(Flags.Flag.SEEN)
        } else {
            false // POP3 не хранит статус прочтения на сервере
        }
        
        val flagged = if (useMessageFlags) {
            message.flags.contains(Flags.Flag.FLAGGED)
        } else {
            false
        }
        
        return EmailEntity(
            id = "${accountId}_$messageId",
            accountId = accountId,
            folderId = "${accountId}_$folderId",
            serverId = messageId,
            from = from?.address ?: "",
            fromName = from?.personal ?: from?.address ?: "",
            to = message.getRecipients(Message.RecipientType.TO)
                ?.joinToString(", ") { (it as? InternetAddress)?.address ?: "" } ?: "",
            cc = message.getRecipients(Message.RecipientType.CC)
                ?.joinToString(", ") { (it as? InternetAddress)?.address ?: "" } ?: "",
            subject = message.subject ?: "(No subject)",
            preview = MailMessageParser.getPreview(message),
            body = MailMessageParser.getBody(message),
            bodyType = MailMessageParser.getBodyType(message),
            dateReceived = message.receivedDate?.time ?: message.sentDate?.time ?: System.currentTimeMillis(),
            read = read,
            flagged = flagged,
            importance = 1,
            hasAttachments = MailMessageParser.hasAttachments(message)
        )
    }
}

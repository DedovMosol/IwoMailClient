package com.exchange.mailclient.smtp

import com.exchange.mailclient.data.database.AccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Клиент для отправки писем через SMTP
 */
class SmtpClient(private val account: AccountEntity) {
    
    private fun getSession(password: String): Session {
        val props = Properties().apply {
            put("mail.smtp.host", account.outgoingServer.ifEmpty { account.serverUrl })
            put("mail.smtp.port", account.outgoingPort.toString())
            put("mail.smtp.auth", "true")
            
            when (account.outgoingPort) {
                465 -> {
                    put("mail.smtp.ssl.enable", "true")
                    if (account.acceptAllCerts) {
                        put("mail.smtp.ssl.trust", "*")
                    }
                }
                587 -> {
                    put("mail.smtp.starttls.enable", "true")
                    if (account.acceptAllCerts) {
                        put("mail.smtp.ssl.trust", "*")
                    }
                }
            }
            
            put("mail.smtp.connectiontimeout", "30000")
            put("mail.smtp.timeout", "30000")
        }
        
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(account.username, password)
            }
        })
    }
    
    /**
     * Отправляет письмо
     */
    suspend fun sendEmail(
        password: String,
        to: String,
        cc: String = "",
        subject: String,
        body: String,
        isHtml: Boolean = false,
        replyToMessageId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = getSession(password)
            
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(account.email, account.displayName))
                setRecipients(
                    Message.RecipientType.TO,
                    InternetAddress.parse(to)
                )
                if (cc.isNotBlank()) {
                    setRecipients(
                        Message.RecipientType.CC,
                        InternetAddress.parse(cc)
                    )
                }
                setSubject(subject, "UTF-8")
                
                // Для ответов добавляем заголовки
                replyToMessageId?.let {
                    setHeader("In-Reply-To", it)
                    setHeader("References", it)
                }
                
                // Тело письма
                val multipart = MimeMultipart("alternative")
                val bodyPart = MimeBodyPart().apply {
                    if (isHtml) {
                        setContent(body, "text/html; charset=UTF-8")
                    } else {
                        setText(body, "UTF-8")
                    }
                }
                multipart.addBodyPart(bodyPart)
                setContent(multipart)
                
                sentDate = Date()
            }
            
            Transport.send(message)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Проверяет подключение к SMTP серверу
     */
    suspend fun testConnection(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = getSession(password)
            val transport = session.getTransport("smtp")
            transport.connect(
                account.outgoingServer.ifEmpty { account.serverUrl },
                account.outgoingPort,
                account.username,
                password
            )
            transport.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}


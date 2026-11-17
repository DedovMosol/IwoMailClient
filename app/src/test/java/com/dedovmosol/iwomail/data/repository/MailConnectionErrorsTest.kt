package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.cert.CertPathValidatorException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.net.ssl.SSLHandshakeException

/** Сообщения входа IMAP/POP3 — по всей цепочке причин JavaMail (как она выглядит на Exchange 2007). */
class MailConnectionErrorsTest {

    @Test fun `certificate rejected during STARTTLS is a certificate problem, not missing STARTTLS`() {
        val e = MessagingException("STARTTLS failure", SSLHandshakeException("handshake").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found."))
        })
        assertThat(mailConnectionErrorMessage(e, "IMAP")).contains("Сертификат сервера не подтверждён")
    }

    @Test fun `server without STARTTLS is reported as such`() {
        val imap = MessagingException("STARTTLS is required but host does not support STARTTLS")
        val pop = MessagingException("STLS is required but host does not support STLS")
        assertThat(mailConnectionErrorMessage(imap, "IMAP")).contains("STARTTLS")
        assertThat(mailConnectionErrorMessage(pop, "POP3")).contains("STARTTLS")
        assertThat(mailConnectionErrorMessage(imap, "IMAP")).contains("993")
    }

    @Test fun `wrong host name in the certificate`() {
        assertThat(mailConnectionErrorMessage(MessagingException("Can't verify identity of server: 192.0.2.10"), "IMAP"))
            .contains("другое имя")
    }

    @Test fun `bad password, unreachable server and timeouts`() {
        assertThat(mailConnectionErrorMessage(AuthenticationFailedException("AUTHENTICATE failed."), "IMAP")).isEqualTo("Неверный логин или пароль")
        assertThat(mailConnectionErrorMessage(MessagingException("x", java.net.ConnectException("Connection refused")), "POP3"))
            .contains("Сервер недоступен")
        assertThat(mailConnectionErrorMessage(MessagingException("x", java.net.SocketTimeoutException("Read timed out")), "IMAP"))
            .contains("время ожидания")
        assertThat(mailConnectionErrorMessage(MessagingException("x", java.net.UnknownHostException("mail.example")), "IMAP"))
            .contains("не найден")
    }

    @Test fun `TLS handshake failure without certificate details`() {
        assertThat(mailConnectionErrorMessage(MessagingException("connection closed", SSLHandshakeException("Remote host terminated the handshake")), "IMAP"))
            .contains("TLS")
    }

    @Test fun `unknown error keeps the original text`() {
        assertThat(mailConnectionErrorMessage(MessagingException("Folder closed"), "IMAP")).isEqualTo("Ошибка IMAP: Folder closed")
    }
}

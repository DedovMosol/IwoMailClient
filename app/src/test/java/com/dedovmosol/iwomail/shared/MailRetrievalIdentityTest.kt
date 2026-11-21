package com.dedovmosol.iwomail.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

class MailRetrievalIdentityTest {
    private fun message(id: String? = null) = MimeMessage(Session.getInstance(Properties()),
        ("From: Sender <sender@example.test>\r\nTo: recipient@example.test\r\n" +
            (id?.let { "Message-ID: $it\r\n" } ?: "") + "Subject: Report\r\n\r\nbody").byteInputStream())

    @Test fun `missing MIME identity cannot create a random new Inbox row on each poll`() {
        val result = runCatching { MessageToEntityConverter.convert(message(), 1, "INBOX", false) }
        assertThat(result.isFailure).isTrue()
    }

    @Test fun `repeated headerless delivery with same protocol uid maps to one row`() {
        val page = listOf("imap:5:INBOX:7:42", "imap:5:INBOX:7:43")
        fun ids() = page.map { uid -> MessageToEntityConverter.convert(message(), 1, "INBOX", false) { uid }.id }
        assertThat((ids() + ids()).toSet()).hasSize(2)
    }

    @Test fun `ordinary message keeps existing database key`() {
        val email = MessageToEntityConverter.convert(message("<one@example.test>"), 1, "INBOX", false)
        assertThat(email.id).isEqualTo("1_<one@example.test>")
    }
}

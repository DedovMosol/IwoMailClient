package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.eas.EwsMailFolder
import com.dedovmosol.iwomail.eas.FolderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadReceiptsTest {

    private fun email(read: Boolean = false, mdnSent: Boolean = false, requestedBy: String? = "boss@corp.example") =
        EmailEntity(
            id = "1_m", accountId = 1L, folderId = "1_inbox", serverId = "m", from = "boss@corp.example",
            to = "me@corp.example", subject = "S", body = "", dateReceived = 0L, read = read,
            mdnRequestedBy = requestedBy, mdnSent = mdnSent
        )

    @Test
    fun `unread message with a request is asked about`() {
        assertThat(ReadReceipts.pendingRequest(email(), FolderType.INBOX)).isEqualTo("boss@corp.example")
        assertThat(ReadReceipts.pendingRequest(email(), FolderType.USER_CREATED)).isEqualTo("boss@corp.example")
        assertThat(ReadReceipts.pendingRequest(email(), null)).isEqualTo("boss@corp.example")
    }

    @Test
    fun `read message is not asked about - the server already handled the request`() {
        assertThat(ReadReceipts.pendingRequest(email(read = true), FolderType.INBOX)).isNull()
    }

    @Test
    fun `answered request is not asked again`() {
        assertThat(ReadReceipts.pendingRequest(email(mdnSent = true), FolderType.INBOX)).isNull()
    }

    @Test
    fun `no or blank request is not asked about`() {
        assertThat(ReadReceipts.pendingRequest(email(requestedBy = null), FolderType.INBOX)).isNull()
        assertThat(ReadReceipts.pendingRequest(email(requestedBy = "  "), FolderType.INBOX)).isNull()
    }

    @Test
    fun `own folders and junk are not asked about`() {
        for (type in listOf(FolderType.DRAFTS, FolderType.SENT_ITEMS, FolderType.OUTBOX, FolderType.JUNK_EMAIL)) {
            assertThat(ReadReceipts.pendingRequest(email(), type)).isNull()
        }
    }

    @Test
    fun `address is trimmed`() {
        assertThat(ReadReceipts.pendingRequest(email(requestedBy = " a@b.c "), FolderType.INBOX)).isEqualTo("a@b.c")
    }

    @Test
    fun `EWS folder of a message`() {
        assertThat(EwsMailFolder.of(FolderType.INBOX, "Входящие")).isEqualTo(EwsMailFolder.Distinguished("inbox"))
        assertThat(EwsMailFolder.of(FolderType.DELETED_ITEMS, "x")).isEqualTo(EwsMailFolder.Distinguished("deleteditems"))
        assertThat(EwsMailFolder.of(FolderType.JUNK_EMAIL, "x")).isEqualTo(EwsMailFolder.Distinguished("junkemail"))
        assertThat(EwsMailFolder.of(FolderType.USER_CREATED, "Projects")).isEqualTo(EwsMailFolder.Named("Projects"))
        assertThat(EwsMailFolder.of(1, "Archive")).isEqualTo(EwsMailFolder.Named("Archive"))
    }
}

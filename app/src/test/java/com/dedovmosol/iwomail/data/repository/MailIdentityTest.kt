package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.EmailDedupInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MailIdentityTest {

    private fun row(
        id: String,
        serverId: String,
        date: Long,
        to: String = "a@b",
        messageId: String? = null,
        conversationId: String? = null
    ) = EmailDedupInfo(
        id = id,
        serverId = serverId,
        subject = "S",
        from = "f@b",
        to = to,
        dateReceived = date,
        internetMessageId = messageId,
        conversationId = conversationId
    )

    @Test
    fun migrationNeedsExactlyOneHitInTwoSeconds() {
        val incoming = row("n", "sid-new", 1_000L)
        assertThat(
            EmailSyncService.uniqueServerIdMigration(incoming, listOf(row("o", "sid-old", 1_500L)))?.id
        ).isEqualTo("o")
        assertThat(
            EmailSyncService.uniqueServerIdMigration(
                incoming,
                listOf(row("o1", "a", 1_000L), row("o2", "b", 1_200L))
            )
        ).isNull()
        assertThat(
            EmailSyncService.uniqueServerIdMigration(incoming, listOf(row("o", "sid-old", 10_000L)))
        ).isNull()
    }

    @Test
    fun migrationRejectsDifferentMessageOrConversation() {
        val incoming = row("n", "new", 1_000L, messageId = "id@x", conversationId = "c1")
        assertThat(
            EmailSyncService.uniqueServerIdMigration(
                incoming, listOf(row("o", "old", 1_000L, messageId = "other@x"))
            )
        ).isNull()
        assertThat(
            EmailSyncService.uniqueServerIdMigration(
                incoming, listOf(row("o", "old", 1_000L, messageId = "id@x", conversationId = "c2"))
            )
        ).isNull()
        assertThat(
            EmailSyncService.uniqueServerIdMigration(
                incoming, listOf(row("o", "old", 1_000L, messageId = "<id@x>", conversationId = "c1"))
            )?.id
        ).isEqualTo("o")
    }

    @Test
    fun ambiguousHitsMustNotMigrate() {
        val incoming = row("n", "new", 1_000L)
        val group = listOf(row("o1", "a", 1_000L), row("o2", "b", 1_200L))
        assertThat(EmailSyncService.ambiguousServerIdHits(incoming, group)).isTrue()
        assertThat(EmailSyncService.uniqueServerIdMigration(incoming, group)).isNull()
        assertThat(
            EmailSyncService.ambiguousServerIdHits(incoming, listOf(row("o", "old", 1_000L)))
        ).isFalse()
    }
}

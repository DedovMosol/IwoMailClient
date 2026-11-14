package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmailReconcileTest {

    @Test
    fun `orphan in trash with a pending move is kept`() {
        val local = listOf("1_2:1", "1_2:2", "1_2:9")
        val server = setOf("1_2:1", "1_2:2")
        val pendingMove = setOf("1_2:9")
        assertThat(fullResyncOrphanIds(local, server, pendingMove)).isEmpty()
    }

    @Test
    fun `true orphans without a pending move are removed`() {
        val local = listOf("1_2:1", "1_2:gone")
        val server = setOf("1_2:1")
        assertThat(fullResyncOrphanIds(local, server, emptySet())).containsExactly("1_2:gone")
    }

    @Test
    fun `pending move that the server already has is not an orphan`() {
        val local = listOf("1_2:1")
        val server = setOf("1_2:1")
        assertThat(fullResyncOrphanIds(local, server, setOf("1_2:1"))).isEmpty()
    }

    @Test
    fun `EAS 12_1 new ServerId is retargeted onto the pending trash row`() {
        val pending = mail("1_2:old", "2:old", 1_000L)
        val incoming = mail("1_2:new", "2:new", 1_002L)
        val retargets = pendingMoveRetargets(listOf(incoming), listOf(pending))
        assertThat(retargets).containsExactly(PendingMoveRetarget("1_2:old", "1_2:new", "2:new"))
        assertThat(filterIncomingAgainstPendingMoves(listOf(incoming), listOf(pending))).isEmpty()
    }

    @Test
    fun `unrelated inbox add is not treated as the pending move`() {
        val pending = mail("1_2:old", "2:old", 1_000L)
        val other = mail("1_2:x", "2:x", 9_000_000L, subject = "Другое")
        assertThat(pendingMoveRetargets(listOf(other), listOf(pending))).isEmpty()
        assertThat(filterIncomingAgainstPendingMoves(listOf(other), listOf(pending))).containsExactly(other)
    }

    private fun mail(id: String, serverId: String, date: Long, subject: String = "Смета") =
        com.dedovmosol.iwomail.data.database.EmailEntity(
            id = id, accountId = 1L, folderId = "1_4", serverId = serverId,
            from = "boss@corp.example", to = "me@corp.example", subject = subject,
            body = "", dateReceived = date, read = true
        )
}

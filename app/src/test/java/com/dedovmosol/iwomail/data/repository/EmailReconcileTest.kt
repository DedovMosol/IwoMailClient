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
}

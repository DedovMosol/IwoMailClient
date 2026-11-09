package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.data.database.ContactSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalOverlayAndFlagPolicyTest {

    @Test
    fun `GAL replace keeps favorite and group`() {
        val incoming = ContactEntity(
            id = "1_exchange_ab",
            accountId = 1L,
            displayName = "New",
            email = "a@b.c",
            source = ContactSource.EXCHANGE
        )
        val existing = incoming.copy(isFavorite = true, groupId = "g1", displayName = "Old")
        val merged = keepLocalContactOverlay(incoming, existing)
        assertThat(merged.isFavorite).isTrue()
        assertThat(merged.groupId).isEqualTo("g1")
        assertThat(merged.displayName).isEqualTo("New")
    }

    @Test
    fun `new contact stays as incoming`() {
        val incoming = ContactEntity(
            id = "1_x",
            accountId = 1L,
            displayName = "N",
            email = "n@e.c"
        )
        assertThat(keepLocalContactOverlay(incoming, null)).isSameInstanceAs(incoming)
    }

    @Test
    fun `rollback flag only on EAS Status reject`() {
        assertThat(isEasStatusReject("setFlag failed: Change Status=6")).isTrue()
        assertThat(isEasStatusReject("Collection Status=3")).isTrue()
        assertThat(isEasStatusReject("timeout")).isFalse()
        assertThat(isEasStatusReject("Unable to resolve host")).isFalse()
    }
}

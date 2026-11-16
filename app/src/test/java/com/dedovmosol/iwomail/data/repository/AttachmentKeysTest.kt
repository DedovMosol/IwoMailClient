package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.eas.EasAttachment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AttachmentKeysTest {

    private fun local(
        id: Long,
        name: String,
        ref: String = "",
        cid: String? = null
    ) = AttachmentEntity(
        id = id,
        emailId = "e",
        fileReference = ref,
        displayName = name,
        contentType = "application/octet-stream",
        contentId = cid
    )

    @Test
    fun prefersFileReferenceThenContentIdThenUniqueName() {
        val rows = listOf(
            local(1, "a.png", ref = "ref-a"),
            local(2, "img.png", cid = "cid-1"),
            local(3, "dup.png"),
            local(4, "dup.png")
        )
        assertThat(
            AttachmentKeys.indexOf(rows, EasAttachment("ref-a", "other.png", "image/png"))
        ).isEqualTo(0)
        assertThat(
            AttachmentKeys.indexOf(rows, EasAttachment("", "x.png", "image/png", contentId = "cid-1"))
        ).isEqualTo(1)
        assertThat(
            AttachmentKeys.indexOf(rows, EasAttachment("", "dup.png", "image/png"))
        ).isEqualTo(-1)
        assertThat(
            AttachmentKeys.indexOf(listOf(local(9, "solo.bin")), EasAttachment("", "solo.bin", "x"))
        ).isEqualTo(0)
    }

    @Test
    fun skipAlreadyMatchedIndex() {
        val rows = listOf(local(1, "a.bin", ref = "r1"), local(2, "b.bin", ref = "r2"))
        val first = AttachmentKeys.indexOf(rows, EasAttachment("r1", "a.bin", "x"))
        assertThat(first).isEqualTo(0)
        assertThat(
            AttachmentKeys.indexOf(rows, EasAttachment("r1", "a.bin", "x"), skip = setOf(0))
        ).isEqualTo(-1)
    }
}

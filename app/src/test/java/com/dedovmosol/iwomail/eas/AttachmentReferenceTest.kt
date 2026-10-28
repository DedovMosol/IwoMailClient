package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Куда идёт скачивание вложения: EAS FileReference — ItemOperations, EWS AttachmentId — GetAttachment.
 * Exchange отдаёт FileReference в URL-кодировке; раньше такие уходили в EWS и не скачивались вовсе
 * (вложения и картинки черновиков с сервера не открывались).
 */
class AttachmentReferenceTest {

    @Test
    fun `url-encoded EAS file reference is EAS`() {
        assertThat(isEasFileReference("5%3a1%3a0")).isTrue()
        assertThat(isEasFileReference("5%3A1%3A0")).isTrue()
        assertThat(isEasFileReference("RgAAAACMBTXPYpDISK9wWoRwSXEHBwB%3a0")).isTrue()
        assertThat(isEasFileReference("22:2:1")).isTrue()
    }

    @Test
    fun `EWS attachment id (base64) is not EAS`() {
        assertThat(isEasFileReference("AAMkADk0NzA4ZDM3LTAxZGUtNDBkNi04ZGVhLWU2NmY5ZDQ2ZTQ2MgBGAAAAAAA+Fp9L5a6vQ4=")).isFalse()
    }
}

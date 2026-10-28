package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Отметка «заменяет» (EmailEntity.replacesServerId): все прежние версии, которые ещё не удалось удалить. */
class DraftIdsTest {

    @Test
    fun `marker keeps every pending id once and reads the old single-id form`() {
        assertThat(DraftIds.replacedIds(null)).isEmpty()
        assertThat(DraftIds.replacedIds("5:1\n 5:2 \n\n5:1")).containsExactly("5:1", "5:2").inOrder()
        // Отметка прежнего формата — один id.
        assertThat(DraftIds.replacedIds("AAMkOLD=")).containsExactly("AAMkOLD=")
        assertThat(DraftIds.replacedMarker(listOf("5:1", "", "5:1"))).isEqualTo("5:1")
        assertThat(DraftIds.replacedMarker(emptyList())).isNull()
    }

    @Test
    fun `markers are merged without losing ids, the record's own id is not replaced by itself`() {
        assertThat(DraftIds.mergeReplaced("5:1", "5:2\n5:3", except = "5:3")).isEqualTo("5:1\n5:2")
        assertThat(DraftIds.mergeReplaced(null, null)).isNull()
    }
}

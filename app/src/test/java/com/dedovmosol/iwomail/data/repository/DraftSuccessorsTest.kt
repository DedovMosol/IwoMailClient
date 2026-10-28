package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Черновик сменил запись, пока открыт в редакторе: сохранение находит актуальную запись по цепочке. */
class DraftSuccessorsTest {

    @Test
    fun `latest record is found through a chain of moves, unknown ids have none`() {
        // Загружен с устройства на сервер (EWS id), затем синхронизация перенесла под EAS ServerId.
        DraftSuccessors.record("1_local_draft_d_7_a", "1_AAMkUP=")
        DraftSuccessors.record("1_AAMkUP=", "1_5:12")

        assertThat(DraftSuccessors.latest("1_local_draft_d_7_a")).isEqualTo("1_5:12")
        assertThat(DraftSuccessors.latest("1_AAMkUP=")).isEqualTo("1_5:12")
        assertThat(DraftSuccessors.latest("1_never_moved")).isNull()
    }

    @Test
    fun `a move onto itself is ignored and a cycle does not hang`() {
        DraftSuccessors.record("1_same", "1_same")
        assertThat(DraftSuccessors.latest("1_same")).isNull()

        DraftSuccessors.record("1_x", "1_y")
        DraftSuccessors.record("1_y", "1_x")
        assertThat(DraftSuccessors.latest("1_x")).isAnyOf("1_x", "1_y")
    }
}

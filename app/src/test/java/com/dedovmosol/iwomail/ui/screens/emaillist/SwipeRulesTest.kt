package com.dedovmosol.iwomail.ui.screens.emaillist

import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.domain.SwipeAction
import com.dedovmosol.iwomail.eas.FolderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Свайпы по папке письма и порог срабатывания по расстоянию. */
class SwipeRulesTest {

    private fun folder(id: String, type: Int) =
        FolderEntity(id = id, accountId = 1L, serverId = id, displayName = id, parentId = "0", type = type)

    private val folders = listOf(
        folder("1_inbox", FolderType.INBOX),
        folder("1_trash", FolderType.DELETED_ITEMS),
        folder("1_drafts", FolderType.DRAFTS),
        folder("1_junk", FolderType.JUNK_EMAIL)
    )

    @Test
    fun `in a mixed list (Favorites) a trashed email is never deleted by swipe`() {
        // Удаление из «Удалённых» окончательное — только через диалог подтверждения
        val rules = SwipeRules.of(SwipeAction.READ, SwipeAction.DELETE, folders)

        assertThat(rules.end("1_inbox")).isEqualTo(SwipeAction.DELETE)
        assertThat(rules.end("1_trash")).isEqualTo(SwipeAction.NONE)
        assertThat(rules.end("1_drafts")).isEqualTo(SwipeAction.NONE)
        assertThat(rules.end("1_junk")).isEqualTo(SwipeAction.DELETE)
        assertThat(rules.start("1_trash")).isEqualTo(SwipeAction.READ)
    }

    @Test
    fun `flag swipe only where the star is shown`() {
        val rules = SwipeRules.of(SwipeAction.FLAG, SwipeAction.FLAG, folders)

        assertThat(rules.start("1_inbox")).isEqualTo(SwipeAction.FLAG)
        assertThat(rules.start("1_trash")).isEqualTo(SwipeAction.NONE)
        assertThat(rules.start("1_junk")).isEqualTo(SwipeAction.NONE)
        assertThat(rules.end("1_drafts")).isEqualTo(SwipeAction.NONE)
    }

    @Test
    fun `unknown folder keeps the configured actions`() {
        val rules = SwipeRules.of(SwipeAction.READ, SwipeAction.DELETE, emptyList())

        assertThat(rules.start("1_any")).isEqualTo(SwipeAction.READ)
        assertThat(rules.end("1_any")).isEqualTo(SwipeAction.DELETE)
    }

    @Test
    fun `none rules disable both sides`() {
        assertThat(SwipeRules.NONE.start("1_inbox")).isEqualTo(SwipeAction.NONE)
        assertThat(SwipeRules.NONE.end("1_inbox")).isEqualTo(SwipeAction.NONE)
    }

    @Test
    fun `a fast short flick does not commit the swipe`() {
        // Скорость не в счёт: при косой прокрутке строка сдвигается чуть-чуть, но быстро
        assertThat(isSwipeCommitted(offset = 60f, width = 1080)).isFalse()
        assertThat(isSwipeCommitted(offset = -60f, width = 1080)).isFalse()
    }

    @Test
    fun `dragging past the threshold commits in either direction`() {
        assertThat(isSwipeCommitted(offset = 1080 * SWIPE_COMMIT_FRACTION, width = 1080)).isTrue()
        assertThat(isSwipeCommitted(offset = -900f, width = 1080)).isTrue()
    }

    @Test
    fun `unknown geometry never commits`() {
        assertThat(isSwipeCommitted(offset = Float.NaN, width = 1080)).isFalse()
        assertThat(isSwipeCommitted(offset = 500f, width = 0)).isFalse()
    }
}

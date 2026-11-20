package com.dedovmosol.iwomail.ui.screens.emaillist

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.domain.EmailThreads
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MailListUxTest {
    private fun email(id: String, conversation: String?, folder: String = "inbox", read: Boolean = false) = EmailEntity(
        id = id, accountId = 1, folderId = folder, serverId = id, from = "a@corp.example", to = "b@corp.example",
        subject = "Topic $id", body = "", dateReceived = 100L, read = read, conversationId = conversation
    )

    @Test
    fun `threads-only uses distinct known members but only matched local ids are selectable`() {
        val unread = email("in", "C")
        val read = email("read", "C", read = true)
        val sent = email("sent", "C", "sent", read = true)
        val single = email("single", "S")
        val outside = listOf(email("peer-1", "P", "sent"), email("peer-2", "P", "sent"))
        val grouped = EmailThreads.group(listOf(unread, read, sent, single, single) + outside)
        val scope = MailListScope.of(listOf(unread, single), grouped, onlyThreads = true)
        val collapsed = scope.rows(emptySet())
        val header = collapsed.single() as MailRow.Header
        assertThat(header.thread.ids).containsExactly("in", "read", "sent")
        assertThat(scope.selectableIds).containsExactly("in")
        assertThat(header.selectableIds).containsExactly("in")
        assertThat(scope.selectOnly(setOf("in", "read", "sent", "single"))).containsExactly("in")
        val expanded = scope.rows(setOf(header.thread.key))
        val dragMap = MailRow.selectionOf(expanded)
        assertThat(expanded.map { it.key }).containsExactly(header.key, "in", "read", "sent")
        assertThat(scope.dragTargets(header.key, dragMap)).containsExactly("in")
        assertThat(scope.dragTargets("sent", dragMap)).isEmpty()
        assertThat(scope.dragTargets("read", dragMap)).isEmpty()
        assertThat(scope.dragTargets("in", dragMap)).containsExactly("in")
        assertThat(MailRow.toggleThread(emptySet(), header.selectableIds)).isEqualTo(scope.selectableIds)
    }

    @Test
    fun `confirmation rejects changed selection even for same count or subset`() {
        val first = email("1_2:9", null)
        val second = email("1_2:10", null)
        val ids = setOf(first.id, second.id)
        val initial = listOf(first, second)
        val confirmation = MailConfirmation(ids, initial)
        assertThat(confirmation.count).isEqualTo(2)
        assertThat(confirmation.matches(ids, listOf(second, first.copy(read = true)))).isTrue()
        assertThat(confirmation.matches(setOf("1_2:10", "1_2:42"), initial)).isFalse()
        assertThat(confirmation.matches(setOf("1_2:10"), initial)).isFalse()
        assertThat(confirmation.matches(emptySet(), initial)).isFalse()
        val reused = listOf(first.copy(subject = "Different message"), second)
        assertThat(confirmation.matches(ids, reused)).isFalse()
        assertThat(com.dedovmosol.iwomail.domain.retainSameMessages(ids, initial, reused)).containsExactly(second.id)
    }

    @Test
    fun `quick scroll uses current layout count and physical edges`() {
        assertThat(quickScrollEdge(0, false, true)).isNull()
        assertThat(quickScrollEdge(1, false, false)).isNull()
        assertThat(quickScrollEdge(20, false, false)).isNull()
        assertThat(quickScrollEdge(2, false, true)).isEqualTo(ListEdge.END)
        assertThat(quickScrollEdge(2, true, true)).isEqualTo(ListEdge.START)
        assertThat(quickScrollEdge(2, true, false)).isEqualTo(ListEdge.START)
        assertThat(ListEdge.END.targetIndex(7)).isEqualTo(6)
        assertThat(ListEdge.END.targetIndex(2)).isEqualTo(1)
        assertThat(ListEdge.END.targetIndex(0)).isNull()
        assertThat(ListEdge.START.targetIndex(7)).isEqualTo(0)
    }

    @Test
    fun `tall last row scrolls past initial alignment and includes the FAB gutter`() {
        assertThat(remainingEndScroll(0, 900, 88, 600)).isEqualTo(388f)
        assertThat(remainingEndScroll(-388, 900, 88, 600)).isEqualTo(0f)
        assertThat(remainingEndScroll(0, 200, 88, 600)).isEqualTo(0f)
    }
}

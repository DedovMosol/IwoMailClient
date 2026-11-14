package com.dedovmosol.iwomail.ui.screens.emaillist

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.domain.EmailThreads
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Строки списка писем с цепочками: свёрнутые и раскрытые цепочки, ключи строк, выбор всей цепочки. */
class MailRowTest {

    private var time = 1_000_000L

    private fun email(id: String, subject: String, conversationId: String? = null, read: Boolean = true, attachments: Boolean = false) =
        EmailEntity(
            id = id, accountId = 1, folderId = "1_2", serverId = id, from = "a@corp.example", to = "b@corp.example",
            subject = subject, body = "", dateReceived = time--, read = read, hasAttachments = attachments,
            conversationId = conversationId
        )

    private val emails = listOf(
        email("4", "RE: Бюджет", "C1"),
        email("3", "Отпуск", "C2"),
        email("2", "RE: Бюджет", "C1", read = false, attachments = true),
        email("1", "Бюджет", "C1")
    )
    private val threads = EmailThreads.group(emails)
    private val budget = threads.first()

    @Test
    fun `flat list is one row per email`() {
        val rows = MailRow.flat(emails)

        assertThat(rows.map { it.key }).containsExactly("4", "3", "2", "1").inOrder()
        assertThat(rows.all { it is MailRow.Single }).isTrue()
    }

    @Test
    fun `collapsed thread is one row, single email is a plain row`() {
        val rows = MailRow.threaded(threads, expanded = emptySet())

        assertThat(rows).hasSize(2)
        val header = rows[0] as MailRow.Header
        assertThat(header.expanded).isFalse()
        assertThat(header.thread.ids).containsExactly("4", "2", "1").inOrder()
        assertThat(rows[1]).isEqualTo(MailRow.Single(emails[1]))
    }

    @Test
    fun `thread row shows the newest email, unread if any is unread, with attachments of any`() {
        val header = MailRow.threaded(threads, emptySet())[0] as MailRow.Header

        assertThat(header.email.id).isEqualTo("4")
        assertThat(header.email.read).isFalse()
        assertThat(header.email.hasAttachments).isTrue()
        // Сводка — только для строки: само письмо не меняется
        assertThat(emails[0].read).isTrue()
    }

    @Test
    fun `expanded thread lists all its emails after the thread row`() {
        val rows = MailRow.threaded(threads, expanded = setOf(budget.key))

        assertThat(rows.map { it::class.simpleName }).containsExactly("Header", "Child", "Child", "Child", "Single").inOrder()
        // Все письма, включая latest: шапка не открывает письмо
        assertThat(rows.drop(1).take(3).map { it.key }).containsExactly("4", "2", "1").inOrder()
        assertThat((rows[0] as MailRow.Header).expanded).isTrue()
    }

    @Test
    fun `expanded children are newest first even if the folder listed oldest first`() {
        val newest = email("new", "RE: x", "C9").copy(dateReceived = 2_000L)
        val oldest = email("old", "x", "C9").copy(dateReceived = 1_000L)
        val thread = EmailThreads.group(listOf(oldest, newest)).single()

        val rows = MailRow.threaded(listOf(thread), expanded = setOf(thread.key))

        assertThat(rows.drop(1).map { it.key }).containsExactly("new", "old").inOrder()
        assertThat((rows[0] as MailRow.Header).email.id).isEqualTo("new")
    }

    @Test
    fun `EAS 14 conversation index orders root before reply even if the reply is newer`() {
        val rootBlob = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val replyBlob = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val reply = email("r", "RE: x", "C8").copy(dateReceived = 9_000L, conversationIndex = replyBlob)
        val root = email("o", "x", "C8").copy(dateReceived = 1_000L, conversationIndex = rootBlob)
        val thread = EmailThreads.group(listOf(reply, root)).single()
        val rows = MailRow.threaded(listOf(thread), expanded = setOf(thread.key))
        assertThat(rows.filterIsInstance<MailRow.Child>().map { it.key }).containsExactly("o", "r").inOrder()
    }

    @Test
    fun `row keys are unique and the thread key is not an email id`() {
        val rows = MailRow.threaded(threads, expanded = setOf(budget.key))
        val keys = rows.map { it.key }

        assertThat(keys).containsNoDuplicates()
        assertThat(keys.first()).isEqualTo(MailRow.headerKey(budget.key))
        assertThat(emails.map { it.id }).doesNotContain(keys.first())
    }

    @Test
    fun `dragging over a thread row selects all its emails`() {
        val rows = MailRow.threaded(threads, emptySet())

        assertThat(MailRow.selectionOf(rows)).containsExactly(MailRow.headerKey(budget.key), listOf("4", "2", "1"))
    }

    @Test
    fun `tapping a thread in selection mode selects all, then clears all`() {
        val ids = budget.ids

        val partly = MailRow.toggleThread(setOf("2", "3"), ids)
        assertThat(partly).containsExactly("1", "2", "3", "4")

        val cleared = MailRow.toggleThread(partly, ids)
        assertThat(cleared).containsExactly("3")
    }
}

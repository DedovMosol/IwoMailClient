package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Цепочки писем: по ConversationId (Exchange 2010+), по теме с ответами (Exchange 2007) и в переходный период. */
class EmailThreadsTest {

    private var time = 1_000_000L

    /** Письма идут от новых к старым, как в списке. */
    private fun email(
        id: String,
        subject: String,
        conversationId: String? = null,
        accountId: Long = 1,
        read: Boolean = true,
        flagged: Boolean = false,
        attachments: Boolean = false
    ) = EmailEntity(
        id = id, accountId = accountId, folderId = "${accountId}_2", serverId = id, from = "a@corp.example", to = "b@corp.example",
        subject = subject, body = "", dateReceived = time--, read = read, flagged = flagged, hasAttachments = attachments,
        conversationId = conversationId
    )

    private fun List<EmailThread>.ids() = map { it.ids }

    @Test
    fun `subject is normalized without reply and forward prefixes`() {
        assertThat(EmailThreads.normalizeSubject("RE: Fw: AW:  Отчёт   за  май ")).isEqualTo("отчёт за май")
        assertThat(EmailThreads.normalizeSubject("Re[2]: RE (3): WG: Antw: план")).isEqualTo("план")
        assertThat(EmailThreads.normalizeSubject("Ответ: Пересл.: ОТВ: Встреча")).isEqualTo("встреча")
        assertThat(EmailThreads.normalizeSubject("FWD:Счёт")).isEqualTo("счёт")
        // Слова, похожие на префиксы, без двоеточия — часть темы
        assertThat(EmailThreads.normalizeSubject("Report: Q1")).isEqualTo("report: q1")
        assertThat(EmailThreads.normalizeSubject("Ответственный за проект")).isEqualTo("ответственный за проект")
    }

    @Test
    fun `reply is recognized by its prefix`() {
        assertThat(EmailThreads.isReply("RE: x")).isTrue()
        assertThat(EmailThreads.isReply("  fwd: x")).isTrue()
        assertThat(EmailThreads.isReply("Отв: x")).isTrue()
        assertThat(EmailThreads.isReply("Re: ")).isTrue()
        assertThat(EmailThreads.isReply("Regards")).isFalse()
        assertThat(EmailThreads.isReply("x RE: y")).isFalse()
    }

    @Test
    fun `conversation id groups messages whatever their subjects`() {
        val threads = EmailThreads.group(
            listOf(
                email("3", "RE: Бюджет", "C1"),
                email("2", "Другое", "C2"),
                email("1", "Бюджет (новая тема)", "C1")
            )
        )

        assertThat(threads.ids()).containsExactly(listOf("3", "1"), listOf("2")).inOrder()
        assertThat(threads[0].isThread).isTrue()
        assertThat(threads[1].isThread).isFalse()
        assertThat(threads[1].key).isEqualTo("2")
    }

    @Test
    fun `Exchange 2007 groups by subject only with a reply`() {
        val threads = EmailThreads.group(
            listOf(
                email("5", "RE: Отчёт"),
                email("4", "Вопрос"),
                email("3", "Отчёт"),
                email("2", "Вопрос"),
                email("1", "Fwd: Отчёт")
            )
        )

        // Два «Вопроса» без ответа — разные письма с одинаковой темой
        assertThat(threads.ids()).containsExactly(listOf("5", "3", "1"), listOf("4"), listOf("2")).inOrder()
    }

    @Test
    fun `accounts are not mixed and empty subjects stay apart`() {
        val threads = EmailThreads.group(
            listOf(
                email("4", "RE: План", accountId = 1),
                email("3", "План", accountId = 2),
                email("2", "RE:"),
                email("1", "")
            )
        )

        assertThat(threads.ids()).containsExactly(listOf("4"), listOf("3"), listOf("2"), listOf("1")).inOrder()
    }

    @Test
    fun `messages synced before conversations join their conversation by subject`() {
        val threads = EmailThreads.group(
            listOf(
                email("3", "RE: Смета", "C1"),
                email("2", "RE: Смета"), // старое письмо без ConversationId
                email("1", "Смета")
            )
        )

        assertThat(threads.ids()).containsExactly(listOf("3", "2", "1"))
        assertThat(threads.single().key).startsWith("c:")
    }

    @Test
    fun `subject shared by two conversations is not joined`() {
        val threads = EmailThreads.group(
            listOf(
                email("4", "RE: Встреча", "C1"),
                email("3", "RE: Встреча", "C2"),
                email("2", "Встреча", "C1"),
                email("1", "RE: Встреча") // к какой из двух — не известно
            )
        )

        assertThat(threads.ids()).containsExactly(listOf("4", "2"), listOf("3"), listOf("1")).inOrder()
    }

    @Test
    fun `old message without replies around stays apart from the conversation`() {
        val threads = EmailThreads.group(
            listOf(
                email("2", "Отчёт", "C1"),
                email("1", "Отчёт")
            )
        )

        assertThat(threads.ids()).containsExactly(listOf("2"), listOf("1")).inOrder()
    }

    @Test
    fun `thread sums up unread, flags and attachments and shows the newest`() {
        val newest = email("3", "RE: x", "C1", read = true)
        val thread = EmailThreads.group(
            listOf(
                newest,
                email("2", "RE: x", "C1", read = false, attachments = true),
                email("1", "x", "C1", read = false, flagged = true)
            )
        ).single()

        assertThat(thread.latest).isEqualTo(newest)
        assertThat(thread.unreadCount).isEqualTo(2)
        assertThat(thread.anyFlagged).isTrue()
        assertThat(thread.anyAttachments).isTrue()
    }

    @Test
    fun `newest message is chosen by date, not by list order`() {
        val newest = email("2", "RE: x", "C1")
        val oldest = email("1", "x", "C1")

        // Список от старых к новым: строка цепочки — всё равно самое новое письмо
        val thread = EmailThreads.group(listOf(oldest, newest)).single()

        assertThat(thread.latest).isEqualTo(newest)
        assertThat(thread.ids).containsExactly("1", "2").inOrder()
    }
}

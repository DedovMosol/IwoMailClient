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
        attachments: Boolean = false,
        internetMessageId: String? = null,
        inReplyTo: String? = null,
        folderId: String? = null
    ) = EmailEntity(
        id = id, accountId = accountId, folderId = folderId ?: "${accountId}_2", serverId = id, from = "a@corp.example", to = "b@corp.example",
        subject = subject, body = "", dateReceived = time--, read = read, flagged = flagged, hasAttachments = attachments,
        conversationId = conversationId, internetMessageId = internetMessageId, inReplyTo = inReplyTo
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
        assertThat(EmailThreads.isReply("SV: möte")).isTrue()
        assertThat(EmailThreads.isReply("VS: plan")).isTrue()
        assertThat(EmailThreads.isReply("Enc: factura")).isTrue()
        assertThat(EmailThreads.isReply("Od: spotkanie")).isTrue()
        assertThat(EmailThreads.isReply("YNT: toplantı")).isTrue()
        assertThat(EmailThreads.isReply("Doorst: nota")).isTrue()
        assertThat(EmailThreads.isReply("RES: orçamento")).isTrue()
        assertThat(EmailThreads.isReply("REF: ticket")).isTrue()
        // Однобуквенный итальянский R: не считаем ответом
        assertThat(EmailThreads.isReply("R: Q1")).isFalse()
        assertThat(EmailThreads.isReply("Odessa meeting")).isFalse()
    }

    @Test
    fun `Exchange 2007 Swedish reply groups with the original subject`() {
        val threads = EmailThreads.group(
            listOf(
                email("2", "SV: Budget"),
                email("1", "Budget")
            )
        )

        assertThat(threads.ids()).containsExactly(listOf("2", "1"))
        assertThat(EmailThreads.normalizeSubject("SV: Budget")).isEqualTo("budget")
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
    fun `two independent replies with the same subject stay apart without In-Reply-To`() {
        val threads = EmailThreads.group(
            listOf(
                email("2", "RE: Встреча"),
                email("1", "RE: Встреча")
            )
        )
        assertThat(threads.ids()).containsExactly(listOf("2"), listOf("1")).inOrder()
    }

    @Test
    fun `In-Reply-To links a reply to its original on Exchange 2007`() {
        val threads = EmailThreads.group(
            listOf(
                email("2", "RE: Встреча", internetMessageId = "<r@corp>", inReplyTo = "<o@corp>"),
                email("1", "Встреча", internetMessageId = "<o@corp>")
            )
        )
        assertThat(threads.ids()).containsExactly(listOf("2", "1"))
    }

    @Test
    fun `two originals with the same subject are not glued together by a reply prefix`() {
        val threads = EmailThreads.group(
            listOf(
                email("3", "RE: Встреча"),
                email("2", "Встреча"),
                email("1", "Встреча")
            )
        )
        assertThat(threads.ids()).containsExactly(listOf("3"), listOf("2"), listOf("1")).inOrder()
    }

    @Test
    fun `Inbox reply joins the only Sent original with the same subject`() {
        val threads = EmailThreads.group(
            listOf(
                email("2", "RE: Встреча", folderId = "inbox"),
                email("1", "Встреча", folderId = "sent")
            )
        )
        assertThat(threads.ids()).containsExactly(listOf("2", "1"))
    }

    @Test
    fun `Inbox original and Sent original with the same subject do not steal each others replies`() {
        val threads = EmailThreads.group(
            listOf(
                email("3", "RE: Встреча", folderId = "inbox"),
                email("2", "Встреча", folderId = "inbox"),
                email("1", "Встреча", folderId = "sent")
            )
        )
        assertThat(threads.ids()).containsExactly(listOf("3", "2"), listOf("1")).inOrder()
    }

    @Test
    fun `References chain unions with In-Reply-To targets`() {
        val threads = EmailThreads.group(
            listOf(
                email("2", "RE: Встреча", inReplyTo = "<mid1@corp> <mid0@corp>"),
                email("1", "Встреча", internetMessageId = "<mid0@corp>")
            )
        )
        assertThat(threads.ids()).containsExactly(listOf("2", "1"))
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
    fun `unique server id propagates along exact reply links despite changed subjects`() {
        val root = email("one", "Root", "C1", internetMessageId = "<root@corp>")
        val reply = email("two", "Different subject", internetMessageId = "<reply@corp>", inReplyTo = "<root@corp>")
        val leaf = email("three", "Yet another subject", inReplyTo = "<reply@corp>")
        val serverSibling = email("four", "Another server member", "C1")
        val grouped = EmailThreads.group(listOf(leaf, reply, root, serverSibling)).single()
        assertThat(grouped.key).isEqualTo("c:1:C1")
        assertThat(grouped.ids).containsExactly("three", "two", "one", "four").inOrder()
    }

    @Test
    fun `conflicting exact links do not grant unknown messages either server key or a subject fallback`() {
        val root = email("one", "Original", "C1", internetMessageId = "<one@corp>")
        val other = email("two", "Different", "C2", internetMessageId = "<two@corp>")
        val bridge = email("three", "Re: Original", inReplyTo = "<one@corp> <two@corp>")
        val sibling = email("four", "Re: Original", "C1")
        assertThat(EmailThreads.group(listOf(root, other, bridge, sibling)).ids())
            .containsExactly(listOf("one", "four"), listOf("two"), listOf("three")).inOrder()
    }

    @Test
    fun `same RFC id across accounts does not shadow the local parent and key survives renumbering`() {
        val foreign = email("one", "Foreign", "C2", accountId = 2, internetMessageId = "<root@corp>")
        val root = email("two", "Root", internetMessageId = "<root@corp>")
        val child = email("three", "Changed", inReplyTo = "<root@corp>")
        val grouped = EmailThreads.group(listOf(foreign, root, child))
        assertThat(grouped.ids()).containsExactly(listOf("one"), listOf("two", "three")).inOrder()
        val resynced = EmailThreads.group(listOf(child.copy(id = "new-3", serverId = "3"), root.copy(id = "new-2", serverId = "2")))
        assertThat(resynced.single().key).isEqualTo(grouped.last().key)
    }

    @Test
    fun `missing or ambiguous exact parent does not invent a link and duplicate rows do not form a thread`() {
        val root = email("one", "Root", "C1", internetMessageId = "<duplicate@corp>")
        val other = email("two", "Other", "C2", internetMessageId = "<duplicate@corp>")
        val child = email("three", "Changed", inReplyTo = "<duplicate@corp>")
        assertThat(EmailThreads.group(listOf(root, root, other, child)).ids())
            .containsExactly(listOf("one"), listOf("two"), listOf("three")).inOrder()
        assertThat(EmailThreads.group(listOf(child)).single().isThread).isFalse()
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
    fun `conversation index orders the root before a longer reply blob`() {
        val root = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val reply = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 9, 9))
        assertThat(EmailThreads.compareConversationIndex(root, reply)).isLessThan(0)
        assertThat(EmailThreads.compareConversationIndex(null, root)).isGreaterThan(0)
        assertThat(EmailThreads.compareConversationIndex(null, null)).isEqualTo(0)
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

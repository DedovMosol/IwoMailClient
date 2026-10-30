package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.data.database.EmailEntity

/**
 * Письма одной переписки в списке. [emails] — в порядке списка; одно письмо — не цепочка ([key] — id письма).
 */
data class EmailThread(val key: String, val emails: List<EmailEntity>) {
    /** Самое новое письмо — оно и показывается строкой цепочки. */
    val latest: EmailEntity = emails.maxBy { it.dateReceived }
    val ids: List<String> = emails.map { it.id }
    val isThread: Boolean get() = emails.size > 1
    val unreadCount: Int = emails.count { !it.read }
    val anyFlagged: Boolean = emails.any { it.flagged }
    val anyAttachments: Boolean = emails.any { it.hasAttachments }
}

/**
 * Группировка писем в цепочки.
 *
 * - Exchange 2010 и новее (EAS 14.0+) присылают у письма email2:ConversationId — по нему, как в Outlook.
 * - Exchange 2007 его не присылает, а заголовков References/In-Reply-To в синхронизации нет (только в MIME каждого
 *   письма) — по теме без «Re:», «Fwd:» и подобных. Чтобы не склеить несвязанные письма с одинаковой темой
 *   («Отчёт», «Вопрос»), цепочка по теме — только если в ней есть ответ или пересылка.
 * - Письма, полученные до появления цепочек в приложении, ConversationId не знают: они присоединяются по теме к
 *   переписке с ConversationId (если такая тема у одной переписки и есть ответ — в ней или в самом письме).
 *
 * Переписки разных аккаунтов не смешиваются. Цепочка стоит на месте первого своего письма в списке.
 */
object EmailThreads {

    // Повторяющиеся префиксы ответа и пересылки (англ., нем., рус.), со счётчиком «Re[2]:», «RE (3):»
    private val REPLY_PREFIX = Regex(
        """^(?:\s*(?:re|fwd?|aw|wg|antw|ответ|отв|пересл)\.?\s*(?:\[\d+]|\(\d+\))?\s*[:：])+\s*""",
        RegexOption.IGNORE_CASE
    )
    private val SPACES = Regex("""\s+""")

    /** Тема без префиксов ответа и пересылки, без лишних пробелов, в нижнем регистре — для сравнения. */
    fun normalizeSubject(subject: String): String =
        subject.replaceFirst(REPLY_PREFIX, "").replace(SPACES, " ").trim().lowercase()

    /** Тема ответа или пересылки. */
    fun isReply(subject: String): Boolean = REPLY_PREFIX.containsMatchIn(subject)

    fun group(emails: List<EmailEntity>): List<EmailThread> {
        val keys = keysOf(emails)
        val members = LinkedHashMap<String, MutableList<EmailEntity>>()
        emails.forEachIndexed { index, email -> keys[index]?.let { members.getOrPut(it) { ArrayList(2) } += email } }
        val valid = members.filter { (key, group) ->
            group.size > 1 && (key.startsWith(BY_CONVERSATION) || group.any { isReply(it.subject) })
        }
        val result = ArrayList<EmailThread>(emails.size)
        val placed = HashSet<String>()
        emails.forEachIndexed { index, email ->
            val key = keys[index]
            val group = key?.let(valid::get)
            if (key == null || group == null) result += EmailThread(email.id, listOf(email))
            else if (placed.add(key)) result += EmailThread(key, group)
        }
        return result
    }

    /** Ключ цепочки каждого письма; null — тема пустая, письмо само по себе. */
    private fun keysOf(emails: List<EmailEntity>): List<String?> {
        val subjects = emails.map(::subjectKey)
        // Тема → переписка с ConversationId; у темы несколько переписок — присоединять не к чему
        val conversationBySubject = HashMap<String, String>()
        val conversationsWithReply = HashSet<String>()
        emails.forEachIndexed { index, email ->
            val conversation = conversationKey(email) ?: return@forEachIndexed
            if (isReply(email.subject)) conversationsWithReply += conversation
            subjects[index]?.let { subject ->
                conversationBySubject.merge(subject, conversation) { old, new -> if (old == new) old else AMBIGUOUS }
            }
        }
        return emails.mapIndexed { index, email ->
            conversationKey(email) ?: subjects[index]?.let { subject ->
                conversationBySubject[subject]
                    ?.takeIf { it != AMBIGUOUS && (isReply(email.subject) || it in conversationsWithReply) }
                    ?: subject
            }
        }
    }

    private fun conversationKey(email: EmailEntity): String? =
        email.conversationId?.takeIf { it.isNotBlank() }?.let { "$BY_CONVERSATION${email.accountId}:$it" }

    private fun subjectKey(email: EmailEntity): String? =
        normalizeSubject(email.subject).takeIf { it.isNotEmpty() }?.let { "$BY_SUBJECT${email.accountId}:$it" }

    private const val BY_CONVERSATION = "c:"
    private const val BY_SUBJECT = "s:"
    private const val AMBIGUOUS = ""
}

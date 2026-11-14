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
 *   письма) — по теме без «Re:», «Fwd:», «SV:», «VS:», «Enc:», «Od:» и подобных. Чтобы не склеить несвязанные письма с одинаковой темой
 *   («Отчёт», «Вопрос»), цепочка по теме — только если в ней есть ответ или пересылка.
 * - Письма, полученные до появления цепочек в приложении, ConversationId не знают: они присоединяются по теме к
 *   переписке с ConversationId (если такая тема у одной переписки и есть ответ — в ней или в самом письме).
 *
 * Переписки разных аккаунтов не смешиваются. Цепочка стоит на месте первого своего письма в списке.
 */
object EmailThreads {

    // Повторяющиеся префиксы ответа и пересылки, со счётчиком «Re[2]:», «RE (3):».
    // Длинные ветки первыми. Однобуквенные R:/I: не берём: «R: Q1» — не ответ.
    // SV/VS/Enc/Od/YNT/Doorst — типичные клиенты на Exchange 2007 без ConversationId.
    private val REPLY_PREFIX = Regex(
        """^(?:\s*(?:doorst|antw|ответ|пересл|ynt|res|ref|enc|fwd?|sv|vs|aw|wg|od|re|отв)\.?\s*(?:\[\d+]|\(\d+\))?\s*[:：])+\s*""",
        RegexOption.IGNORE_CASE
    )
    private val SPACES = Regex("""\s+""")

    /** Тема без префиксов ответа и пересылки, без лишних пробелов, в нижнем регистре — для сравнения. */
    fun normalizeSubject(subject: String): String =
        subject.replaceFirst(REPLY_PREFIX, "").replace(SPACES, " ").trim().lowercase()

    /** Тема ответа или пересылки. */
    fun isReply(subject: String): Boolean = REPLY_PREFIX.containsMatchIn(subject)

    /**
     * Порядок в цепочке по ConversationIndex ([MS-ASEMAIL] 2.2.2.21): корень короче, ответы наращивают blob.
     * Нет индекса (Exchange 2007) — 0, вызывающий сортирует по дате.
     */
    fun compareConversationIndex(a: String?, b: String?): Int {
        val left = decodeIndex(a)
        val right = decodeIndex(b)
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
        val n = minOf(left.size, right.size)
        for (i in 0 until n) {
            val d = (left[i].toInt() and 0xFF) - (right[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return left.size - right.size
    }

    private fun decodeIndex(value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        return try {
            java.util.Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            value.toByteArray(Charsets.ISO_8859_1)
        }
    }

    fun group(emails: List<EmailEntity>): List<EmailThread> {
        val keys = keysOf(emails)
        val members = LinkedHashMap<String, MutableList<EmailEntity>>()
        emails.forEachIndexed { index, email -> keys[index]?.let { members.getOrPut(it) { ArrayList(2) } += email } }
        val valid = members.filter { (key, group) ->
            group.size > 1 && (
                key.startsWith(BY_CONVERSATION) ||
                    key.startsWith(BY_MESSAGE) ||
                    (key.startsWith(BY_SUBJECT) && group.any { isReply(it.subject) } && group.any { !isReply(it.subject) })
                )
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
        val graph = messageGraphKeys(emails)
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
        // Корни без ConversationId: по всем папкам и по каждой папке отдельно.
        // Один корень в Inbox+Sent — ответ из другой папки к нему. Два корня (Входящие и Отправленные
        // с одной темой) — не склеивать всё; ответ только к корню своей папки.
        val taken = { i: Int -> conversationKey(emails[i]) != null || graph[i] != null }
        val byAccount = uniqueOriginal(emails, taken) { "${it.accountId}\t${normalizeSubject(it.subject)}" }
        val byFolder = uniqueOriginal(emails, taken) { "${it.accountId}\t${it.folderId}\t${normalizeSubject(it.subject)}" }
        return emails.mapIndexed { index, email ->
            conversationKey(email) ?: graph[index] ?: subjects[index]?.let { subject ->
                conversationBySubject[subject]
                    ?.takeIf { it != AMBIGUOUS && (isReply(email.subject) || it in conversationsWithReply) }
                    ?: subjectFallbackKey(email, index, byAccount, byFolder)
            }
        }
    }

    /** Индекс единственного письма без Re: с этим ключом; несколько — −1. */
    private fun uniqueOriginal(
        emails: List<EmailEntity>,
        taken: (Int) -> Boolean,
        key: (EmailEntity) -> String
    ): Map<String, Int> {
        val map = HashMap<String, Int>()
        emails.forEachIndexed { index, email ->
            if (taken(index) || isReply(email.subject) || normalizeSubject(email.subject).isEmpty()) return@forEachIndexed
            map.merge(key(email), index) { _, _ -> -1 }
        }
        return map
    }

    private fun subjectFallbackKey(
        email: EmailEntity,
        index: Int,
        byAccount: Map<String, Int>,
        byFolder: Map<String, Int>
    ): String? {
        val norm = normalizeSubject(email.subject).takeIf { it.isNotEmpty() } ?: return null
        val global = byAccount["${email.accountId}\t$norm"] ?: -1
        val local = byFolder["${email.accountId}\t${email.folderId}\t$norm"] ?: -1
        val root = if (global >= 0) global else local
        if (root < 0 || (index != root && !isReply(email.subject))) return null
        return if (global >= 0) {
            "$BY_SUBJECT${email.accountId}:$norm"
        } else {
            "$BY_SUBJECT${email.accountId}:${email.folderId}:$norm"
        }
    }

    private fun conversationKey(email: EmailEntity): String? =
        email.conversationId?.takeIf { it.isNotBlank() }?.let { "$BY_CONVERSATION${email.accountId}:$it" }

    private fun subjectKey(email: EmailEntity): String? =
        normalizeSubject(email.subject).takeIf { it.isNotEmpty() }?.let { "$BY_SUBJECT${email.accountId}:$it" }

    /**
     * Связь по RFC 5322 In-Reply-To ↔ Message-ID. На Exchange 2007 нет ConversationId;
     * заголовки появляются после загрузки MIME/EWS. Компонента из одного письма — не цепочка.
     */
    private fun messageGraphKeys(emails: List<EmailEntity>): List<String?> {
        val n = emails.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val pa = find(a)
            val pb = find(b)
            if (pa != pb) parent[pa] = pb
        }
        val byId = HashMap<String, Int>(n)
        emails.forEachIndexed { i, email ->
            normalizeMessageId(email.internetMessageId)?.let { byId.putIfAbsent(it, i) }
        }
        emails.forEachIndexed { i, email ->
            for (reply in referencedIds(email.inReplyTo)) {
                val parentIndex = byId[reply] ?: continue
                if (emails[i].accountId == emails[parentIndex].accountId) union(i, parentIndex)
            }
        }
        val members = Array(n) { ArrayList<String>(1) }
        emails.indices.forEach { i -> members[find(i)].add(emails[i].id) }
        return emails.indices.map { i ->
            val root = find(i)
            val ids = members[root]
            if (ids.size > 1) "$BY_MESSAGE${emails[i].accountId}:${ids.minOrNull()}" else null
        }
    }

    private fun normalizeMessageId(value: String?): String? {
        val t = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.startsWith("<") && t.endsWith(">")) t else "<$t>"
    }

    private val ANGLE_ID = Regex("<[^>]+>")

    private fun referencedIds(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val found = ANGLE_ID.findAll(value).mapNotNull { normalizeMessageId(it.value) }.toList()
        if (found.isNotEmpty()) return found
        return listOfNotNull(normalizeMessageId(value))
    }

    private const val BY_CONVERSATION = "c:"
    private const val BY_MESSAGE = "m:"
    private const val BY_SUBJECT = "s:"
    private const val AMBIGUOUS = ""
}

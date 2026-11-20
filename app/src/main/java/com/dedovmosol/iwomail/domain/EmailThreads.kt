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
 * - Exchange 2007 его не присылает. Если уже известны Message-ID и In-Reply-To, точная связь важнее темы:
 *   компонент без конфликта наследует единственный ConversationId. Если заголовков нет, используем тему
 *   без «Re:», «Fwd:», «SV:» и подобных, когда есть ответ/пересылка и нет неоднозначности.
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
        val unique = emails.distinctBy { it.id }
        val keys = keysOf(unique)
        val members = HashMap<String, MutableList<EmailEntity>>()
        unique.forEachIndexed { i, email -> keys[i]?.let { members.getOrPut(it) { ArrayList(2) } += email } }
        val result = ArrayList<EmailThread>(unique.size)
        unique.forEachIndexed { i, email ->
            val key = keys[i]
            val group = key?.let(members::get)
            if (group == null || group.size < 2) result += EmailThread(email.id, listOf(email))
            else if (group.first() === email) result += EmailThread(checkNotNull(key), group)
        }
        return result
    }

    private data class Subject(val account: Long, val text: String)
    private data class FolderSubject(val subject: Subject, val folder: String)
    private data class MessageId(val account: Long, val header: String)

    private fun keysOf(emails: List<EmailEntity>): Array<String?> {
        val conversationKeys = emails.map { email ->
            email.conversationId?.takeIf { it.isNotBlank() }?.let { "$BY_CONVERSATION${email.accountId}:$it" }
        }
        val exact = messageGraphKeys(emails, conversationKeys)
        val reply = BooleanArray(emails.size)
        val subjects = emails.mapIndexed { i, email ->
            val prefix = REPLY_PREFIX.find(email.subject)
            reply[i] = prefix != null
            val name = email.subject.substring((prefix?.range?.last ?: -1) + 1)
                .replace(SPACES, " ").trim().lowercase()
            name.takeIf { it.isNotEmpty() }?.let { Subject(email.accountId, it) }
        }
        val byConversation = HashMap<Subject, String>()
        val conversationsWithReply = HashSet<String>()
        val originals = HashMap<Subject, Int>()
        val folderOriginals = HashMap<FolderSubject, Int>()
        subjects.forEachIndexed { i, subject ->
            subject ?: return@forEachIndexed
            val conversation = conversationKeys[i] ?: exact[i]?.takeIf { it.startsWith(BY_CONVERSATION) }
            if (conversation != null) {
                if (reply[i]) conversationsWithReply += conversation
                byConversation.merge(subject, conversation) { old, new -> if (old == new) old else AMBIGUOUS }
            } else if (exact[i] == null && !reply[i]) {
                originals.merge(subject, i) { _, _ -> -1 }
                folderOriginals.merge(FolderSubject(subject, emails[i].folderId), i) { _, _ -> -1 }
            }
        }
        // Use the same destination for the original and its subject-only replies. If a root was
        // taken by a server conversation, a fallback reply must not form a group without it.
        fun subjectConversation(i: Int, subject: Subject): String? = byConversation[subject]
            ?.takeIf { it != AMBIGUOUS && (reply[i] || it in conversationsWithReply) }
        return Array(emails.size) { i ->
            val strong = conversationKeys[i] ?: exact[i]
            if (strong != null) strong.takeUnless { it == AMBIGUOUS }
            else subjects[i]?.let { subject ->
                subjectConversation(i, subject) ?: run {
                    val global = originals[subject] ?: -1
                    val folder = emails[i].folderId
                    val root = if (global >= 0) global else folderOriginals[FolderSubject(subject, folder)] ?: -1
                    if (root < 0 || (root != i && !reply[i]) || subjectConversation(root, subject) != null) null
                    else if (global >= 0) "$BY_SUBJECT${subject.account}:a:${subject.text}"
                    else "$BY_SUBJECT${subject.account}:f:${folder.length}:$folder:${subject.text}"
                }
            }
        }
    }

    /** Exact links only, in the same account. AMBIGUOUS blocks the weaker subject fallback. */
    private fun messageGraphKeys(emails: List<EmailEntity>, conversations: List<String?>): Array<String?> {
        val headers = emails.map { normalizeMessageId(it.internetMessageId) }
        val byId = HashMap<MessageId, Int>()
        headers.forEachIndexed { i, header ->
            if (header != null) byId.merge(MessageId(emails[i].accountId, header), i) { _, _ -> -1 }
        }
        val parent = IntArray(emails.size) { it }
        val size = IntArray(emails.size) { 1 }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        emails.forEachIndexed { i, email ->
            for (reply in referencedIds(email.inReplyTo)) {
                val target = byId[MessageId(email.accountId, reply)] ?: continue
                if (target < 0) continue // two possible parents in this account
                var a = find(i)
                var b = find(target)
                if (a == b) continue
                if (size[a] < size[b]) { val tmp = a; a = b; b = tmp }
                parent[b] = a
                size[a] += size[b]
            }
        }
        val serverKey = arrayOfNulls<String>(emails.size)
        val messageKey = arrayOfNulls<String>(emails.size)
        emails.indices.forEach { i ->
            val root = find(i)
            if (size[root] < 2) return@forEach
            conversations[i]?.let { key ->
                val old = serverKey[root]
                serverKey[root] = if (old == null || old == key) key else AMBIGUOUS
            }
            // Use a unique RFC ID for the local key, not a ServerId that can be renumbered.
            val header = headers[i]
            if (header != null && byId[MessageId(emails[i].accountId, header)] == i) {
                val old = messageKey[root]
                if (old == null || header < old) messageKey[root] = header
            }
        }
        return Array(emails.size) { i ->
            val root = find(i)
            serverKey[root] ?: messageKey[root]?.let { "$BY_MESSAGE${emails[i].accountId}:$it" }
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

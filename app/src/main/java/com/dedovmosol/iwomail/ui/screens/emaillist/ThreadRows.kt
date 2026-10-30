package com.dedovmosol.iwomail.ui.screens.emaillist

import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.domain.EmailThread

/**
 * Строка списка писем: письмо, строка цепочки или письмо раскрытой цепочки. [key] — ключ строки в LazyColumn,
 * [email] — что показывает строка.
 */
sealed interface MailRow {
    val key: String
    val email: EmailEntity

    data class Single(override val email: EmailEntity) : MailRow {
        override val key: String get() = email.id
    }

    /** Цепочка: самое новое письмо, число писем, сводка непрочитанных, флагов и вложений. */
    data class Header(val thread: EmailThread, val expanded: Boolean) : MailRow {
        override val key: String get() = headerKey(thread.key)

        /** Как самое новое письмо, но прочитано, только если прочитаны все, и со скрепкой, если вложения есть в любом. */
        override val email: EmailEntity =
            thread.latest.copy(read = thread.unreadCount == 0, hasAttachments = thread.anyAttachments)
    }

    /** Письмо раскрытой цепочки — обычная строка с отступом. */
    data class Child(override val email: EmailEntity) : MailRow {
        override val key: String get() = email.id
    }

    companion object {
        private const val HEADER_PREFIX = "thread:"

        /** Ключ строки цепочки; id письма с таким префиксом не начинается. */
        fun headerKey(threadKey: String): String = HEADER_PREFIX + threadKey

        /** Без цепочек — по строке на письмо. */
        fun flat(emails: List<EmailEntity>): List<MailRow> = emails.map(::Single)

        /** Цепочки: одно письмо — обычной строкой; раскрытая цепочка — строка цепочки и все её письма. */
        fun threaded(threads: List<EmailThread>, expanded: Set<String>): List<MailRow> = buildList {
            for (thread in threads) {
                if (!thread.isThread) {
                    add(Single(thread.emails.single()))
                    continue
                }
                val open = thread.key in expanded
                add(Header(thread, open))
                if (open) thread.emails.forEach { add(Child(it)) }
            }
        }

        /** Что выделяет строка (выбор протягиванием): строка цепочки — все её письма. */
        fun selectionOf(rows: List<MailRow>): Map<String, List<String>> =
            rows.filterIsInstance<Header>().associate { it.key to it.thread.ids }

        /** Нажатие на цепочку в режиме выбора: выбраны все её письма — снять все, иначе выбрать все. */
        fun toggleThread(selected: Set<String>, ids: List<String>): Set<String> =
            if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids
    }
}

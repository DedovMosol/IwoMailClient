package com.dedovmosol.iwomail.eas

/**
 * Сопоставление письма EAS ↔ EWS. EAS ServerId в EWS ItemId не переводится ([MS-ASCMD]).
 *
 * Практика: уникален RFC 5322 Message-ID; Subject+дата — нет.
 * Чужое тело хуже пустого: subject-only и окна шире 2 минут запрещены.
 */
object EwsMailMatch {

    const val TIGHT_WINDOW_MS = 15_000L
    const val WIDE_WINDOW_MS = 120_000L

    fun dateWindowsMs(): List<Long> = listOf(TIGHT_WINDOW_MS, WIDE_WINDOW_MS)

    fun normalizeMessageId(id: String?): String? {
        val trimmed = id?.trim()?.trim('<', '>')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed
    }

    fun sameMessageId(a: String?, b: String?): Boolean {
        val left = normalizeMessageId(a) ?: return false
        val right = normalizeMessageId(b) ?: return false
        return left == right
    }

    fun subjectsEqual(local: String, remote: String): Boolean =
        local.trim().equals(remote.trim(), ignoreCase = true)

    /** Известен Message-ID — искать только по нему, не расширять до темы. */
    fun messageIdOnly(internetMessageId: String?): Boolean =
        normalizeMessageId(internetMessageId) != null

    /** Значение для EWS IsEqualTo: в ящике Message-ID обычно в угловых скобках (RFC 5322). */
    fun forEwsRestriction(internetMessageId: String): String {
        val trimmed = internetMessageId.trim()
        return if (trimmed.startsWith("<") && trimmed.endsWith(">")) trimmed else "<${trimmed.trim('<', '>')}>"
    }

    /** 2007 хранит ID то со скобками, то без — FindItem IsEqualTo по обоим. */
    fun messageIdRestrictionValues(internetMessageId: String): List<String> {
        val withBrackets = forEwsRestriction(internetMessageId)
        val bare = normalizeMessageId(internetMessageId) ?: return listOf(withBrackets)
        return listOf(withBrackets, bare).distinct()
    }

    /**
     * Тело GetItem можно писать в эту локальную запись.
     * [lookedUpByMessageId]: FindItem был по InternetMessageId.
     */
    fun acceptBody(
        localSubject: String,
        localDateReceived: Long,
        localMessageId: String?,
        ewsSubject: String?,
        ewsDateMillis: Long?,
        ewsMessageId: String?,
        lookedUpByMessageId: Boolean
    ): Boolean {
        if (lookedUpByMessageId) {
            // FindItem уже сузил по ID. На 2007 GetItem часто без InternetMessageId в XML —
            // отсутствие тега не повод выбросить найденное тело. Чужое — только явный другой ID.
            val remote = normalizeMessageId(ewsMessageId) ?: return true
            return sameMessageId(localMessageId, remote)
        }
        if (normalizeMessageId(localMessageId) != null &&
            normalizeMessageId(ewsMessageId) != null &&
            !sameMessageId(localMessageId, ewsMessageId)
        ) {
            return false
        }
        val remoteSubject = ewsSubject?.trim().orEmpty()
        if (remoteSubject.isEmpty() || !subjectsEqual(localSubject, remoteSubject)) return false
        if (ewsDateMillis != null && localDateReceived > 0L &&
            kotlin.math.abs(ewsDateMillis - localDateReceived) > WIDE_WINDOW_MS
        ) {
            return false
        }
        return true
    }
}

/**
 * [MS-ASCMD] ItemOperations Fetch, item-level Status.
 * 6 — объекта нет / нет доступа. 8 — неверный или слишком большой Range (не «письма нет»).
 * Exchange 2007 на живых Sent с вложениями иногда отдаёт 8: это не удаление.
 */
object ItemOperationsStatus {
    const val SUCCESS = 1
    const val NOT_FOUND = 6
    const val INVALID_RANGE = 8
}

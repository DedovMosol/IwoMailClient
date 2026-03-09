package com.dedovmosol.iwomail.util

val CN_REGEX = Regex("CN=([^/><]+)", RegexOption.IGNORE_CASE)
val NAME_BEFORE_BRACKET_REGEX = Regex("^\"?([^\"<]+)\"?\\s*<")
private val WHITESPACE_REGEX = Regex("\\s+")

val EMAIL_IN_BRACKETS_REGEX = Regex("<([^>]+@[^>]+)>")
val CID_REGEX = Regex("cid:([^\"'\\s>]+)")
val SAFE_FILENAME_REGEX = Regex("[\\\\/:*?\"<>|]")
val TASK_SUBJECT_REGEX = Regex("^Задача:\\s*(.+)$|^Task:\\s*(.+)$", RegexOption.IGNORE_CASE)
val BODY_LINK_REGEX = Regex(
    "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)" +
    "|" +
    "(\\b[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}\\b)" +
    "|" +
    "(\\+?\\d[\\d\\s\\-()]{6,}\\d)"
)
val TASK_DUE_DATE_REGEX = Regex("Срок выполнения:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})|Due date:\\s*(\\d{2}\\.\\d{2}\\.\\d{4}\\s+\\d{2}:\\d{2})", RegexOption.IGNORE_CASE)
val TASK_DESCRIPTION_REGEX = Regex("Описание:\\s*(.+?)(?=\\n\\n|Срок|Due|$)|Description:\\s*(.+?)(?=\\n\\n|Срок|Due|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
val ICAL_SUMMARY_REGEX = Regex("SUMMARY[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
val ICAL_DTSTART_REGEX = Regex("DTSTART(?:;TZID=([^:;]+))?(?:;[^:]+)?:(\\d{8}(?:T\\d{6})?Z?)")
val ICAL_DTEND_REGEX = Regex("DTEND(?:;TZID=([^:;]+))?(?:;[^:]+)?:(\\d{8}(?:T\\d{6})?Z?)")
val ICAL_LOCATION_REGEX = Regex("LOCATION[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
val ICAL_DESCRIPTION_REGEX = Regex("DESCRIPTION[^:]*:(.+?)(?=\\r?\\n[A-Z])", setOf(RegexOption.DOT_MATCHES_ALL))
val ICAL_ORGANIZER_REGEX = Regex("ORGANIZER[^:]*:mailto:([^\\r\\n]+)", RegexOption.IGNORE_CASE)
val HTML_STRIP_REGEX = Regex("<[^>]+>")
val LINE_FOLDING_REGEX = Regex("\\r?\\n[ \\t]")
val EXCHANGE_SEPARATOR_1_REGEX = Regex("\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*?")
val EXCHANGE_SEPARATOR_2_REGEX = Regex("~\\*~\\*~\\*~\\*~\\*~\\*~\\*~\\*?")
val EXCHANGE_SEPARATOR_3_REGEX = Regex("~\\*+")
val EXCHANGE_SEPARATOR_4_REGEX = Regex("\\*~+")
val BASE64_DETECT_REGEX = Regex("^[A-Za-z0-9+/=\\s]+$")

/**
 * Извлекает отображаемое имя из email-поля Exchange.
 * Поддерживает форматы:
 * - Exchange DN: "/O=.../CN=Имя Фамилия"
 * - RFC 5322: "Имя Фамилия <email@domain.com>"
 */
fun extractName(emailField: String): String {
    if (emailField.isBlank()) return ""

    val cnMatch = CN_REGEX.find(emailField)
    if (cnMatch != null) {
        return cnMatch.groupValues[1].trim()
    }

    val nameMatch = NAME_BEFORE_BRACKET_REGEX.find(emailField)
    if (nameMatch != null) {
        return nameMatch.groupValues[1].trim()
    }

    if (emailField.contains("@") && !emailField.contains("<")) {
        return ""
    }

    return emailField.trim()
}

/**
 * Extracts display name from email string.
 * Handles X.500 DN: "/O=ORG/OU=.../CN=Name" and RFC 5322: "Name <email>".
 * Returns capitalized CN for Exchange DN, or name part before angle bracket.
 */
fun extractDisplayName(email: String): String {
    if (email.contains("/O=") || email.contains("/CN=")) {
        val cnMatch = CN_REGEX.findAll(email).toList()
        val lastCn = cnMatch.lastOrNull()?.groupValues?.get(1)?.trim()
        if (lastCn != null && !lastCn.equals("RECIPIENTS", ignoreCase = true)) {
            return lastCn.lowercase().replaceFirstChar { it.uppercase() }
        }
        val nameMatch = NAME_BEFORE_BRACKET_REGEX.find(email)
        if (nameMatch != null) return nameMatch.groupValues[1].trim()
    }
    val match = NAME_BEFORE_BRACKET_REGEX.find(email)
    return match?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
        ?: email.substringBefore("@").substringBefore("<").trim()
}

/**
 * Extracts email address from string. Returns empty for X.500 DN without normal email.
 */
fun extractEmailAddress(email: String): String {
    val emailMatch = EMAIL_IN_BRACKETS_REGEX.find(email)
    if (emailMatch != null) return emailMatch.groupValues[1]
    if (email.contains("/O=") || email.contains("/CN=")) return ""
    if (email.contains("@") && !email.contains("<")) return email.trim()
    return ""
}

/**
 * Parses recipient string into (displayName, email) pairs.
 */
fun parseRecipientPairs(recipients: String): List<Pair<String, String>> {
    if (recipients.isBlank()) return emptyList()
    return recipients.split(",", ";").map { recipient ->
        val trimmed = recipient.trim()
        Pair(extractDisplayName(trimmed), extractEmailAddress(trimmed))
    }
}

/**
 * Formats recipient string for display, avoiding name/email duplication.
 */
fun formatRecipients(recipients: String): String {
    if (recipients.isBlank()) return ""
    return recipients.split(",").joinToString(", ") { recipient ->
        val trimmed = recipient.trim()
        val name = extractDisplayName(trimmed)
        val email = extractEmailAddress(trimmed)
        if (name.isBlank() || name.equals(email, ignoreCase = true) ||
            name.equals(email.substringBefore("@"), ignoreCase = true)
        ) {
            email.ifEmpty { trimmed }
        } else {
            if (email.isNotEmpty()) "$name <$email>" else name
        }
    }
}

private val DETAIL_DATE_FORMAT = ThreadLocal.withInitial {
    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
}

fun formatFullDate(timestamp: Long): String =
    DETAIL_DATE_FORMAT.get()?.format(java.util.Date(timestamp)) ?: ""

fun formatFileSize(bytes: Long): String {
    val isRussian = java.util.Locale.getDefault().language == "ru"
    return when {
        bytes < 1024 -> if (isRussian) "$bytes Б" else "$bytes B"
        bytes < 1024 * 1024 -> if (isRussian) "${bytes / 1024} КБ" else "${bytes / 1024} KB"
        else -> if (isRussian) "${bytes / (1024 * 1024)} МБ" else "${bytes / (1024 * 1024)} MB"
    }
}

/**
 * Убирает HTML-теги для генерации preview-текста письма.
 * Сжимает whitespace в одиночные пробелы (подходит для однострочного preview).
 */
fun stripHtml(html: String): String {
    return html
        .replace(HtmlRegex.STYLE, "")
        .replace(HtmlRegex.SCRIPT, "")
        .replace(HtmlRegex.COMMENT, "")
        .replace(HtmlRegex.BR, " ")
        .replace(HtmlRegex.TAG, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(HtmlRegex.HTML_ENTITY) { HtmlRegex.decodeNumericEntity(it) }
        .replace(WHITESPACE_REGEX, " ")
        .trim()
}

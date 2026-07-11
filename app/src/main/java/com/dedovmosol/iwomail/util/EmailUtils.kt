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
 * Результат обработки тела письма для отображения.
 * @property text очищенное тело (может быть пустым)
 * @property isHtml true, если тело нужно рендерить как HTML (WebView), иначе plain text
 */
data class ProcessedEmailBody(val text: String, val isHtml: Boolean)

/**
 * Конвейер подготовки тела письма к отображению:
 * 1. bodyType=4 (MIME) → извлечение HTML-части из MIME;
 * 2. расэкранирование XML-entities: WBXML-парсер при EAS Sync выводит тело как XML,
 *    где `<div>` становится `&lt;div&gt;` — parseEmail() уже делает unescapeXml для новых
 *    писем, но старые закэшированные письма в БД всё ещё содержат encoded entities
 *    (safety-net для обоих случаев);
 * 3. чистка Exchange-разделителей (`*~*~...` и частичных остатков);
 * 4. детект HTML-фрагмента.
 *
 * Тяжёлые полнострочные проходы по телу (до МБ) — вызывать вне горячих путей
 * (из UI — строго под remember с ключом по письму).
 */
fun processEmailBodyForDisplay(body: String, bodyType: Int): ProcessedEmailBody {
    val rawBody = if (bodyType == 4) MimeHtmlProcessor.extractHtmlFromMime(body) else body

    val unescapedBody = if (rawBody.contains("&lt;") && looksLikeEncodedHtmlEmailContent(rawBody)) {
        rawBody
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    } else {
        rawBody
    }

    val cleaned = unescapedBody
        .replace(EXCHANGE_SEPARATOR_1_REGEX, "")
        .replace(EXCHANGE_SEPARATOR_2_REGEX, "")
        .replace(EXCHANGE_SEPARATOR_3_REGEX, "")
        .replace(EXCHANGE_SEPARATOR_4_REGEX, "")
        .trim()

    return ProcessedEmailBody(cleaned, looksLikeHtmlEmailContent(cleaned))
}

/**
 * Результат детекта письма-приглашения/ответа на приглашение.
 */
data class MeetingDetection(
    val bodyHasVEvent: Boolean,
    val isAcceptedResponse: Boolean,
    val isDeclinedResponse: Boolean,
    val isTentativeResponse: Boolean,
    val isMeetingResponse: Boolean,
    val isMeetingInvitation: Boolean
)

/**
 * Детект приглашения на встречу / ответа на приглашение по телу и теме письма.
 * Сканирует всё тело — вызывать вне горячих путей (из UI — под remember).
 */
fun detectMeetingEmail(body: String, subject: String, hasCalendarAttachment: Boolean): MeetingDetection {
    val bodyHasVEvent = body.contains("BEGIN:VEVENT", ignoreCase = true) ||
        body.contains("BEGIN:VCALENDAR", ignoreCase = true)

    // Признаки приглашения Exchange в теле («Когда: ... Где: ...»)
    val bodyHasMeetingInfo = (body.contains("Когда:", ignoreCase = true) ||
        body.contains("When:", ignoreCase = true)) &&
        (body.contains("Где:", ignoreCase = true) ||
        body.contains("Where:", ignoreCase = true))

    val isAcceptedResponse = subject.startsWith("Принято:", ignoreCase = true) ||
        subject.startsWith("Accepted:", ignoreCase = true)
    val isDeclinedResponse = subject.startsWith("Отклонено:", ignoreCase = true) ||
        subject.startsWith("Declined:", ignoreCase = true)
    val isTentativeResponse = subject.startsWith("Под вопросом:", ignoreCase = true) ||
        subject.startsWith("Tentative:", ignoreCase = true)
    val isMeetingResponse = isAcceptedResponse || isDeclinedResponse || isTentativeResponse

    val subjectHasInvitation = subject.contains("Приглашение:", ignoreCase = true) ||
        subject.contains("Invitation:", ignoreCase = true) ||
        subject.contains("Meeting:", ignoreCase = true) ||
        subject.contains("Встреча:", ignoreCase = true)

    return MeetingDetection(
        bodyHasVEvent = bodyHasVEvent,
        isAcceptedResponse = isAcceptedResponse,
        isDeclinedResponse = isDeclinedResponse,
        isTentativeResponse = isTentativeResponse,
        isMeetingResponse = isMeetingResponse,
        isMeetingInvitation = (hasCalendarAttachment || bodyHasVEvent || subjectHasInvitation || bodyHasMeetingInfo) &&
            !isMeetingResponse
    )
}

/**
 * Данные встречи, извлечённые из iCalendar (RFC 5545).
 */
data class IcalMeetingInfo(
    val organizerEmail: String,
    val summary: String,
    val location: String,
    val description: String,
    val startTime: Long,
    val endTime: Long
)

/**
 * Парсит поля встречи из iCalendar-данных (уже без line folding).
 * @param nowMillis fallback-время начала, если DTSTART не распарсился
 */
fun parseIcalMeetingInfo(
    icalData: String,
    fallbackOrganizer: String,
    fallbackSummary: String,
    nowMillis: Long = System.currentTimeMillis()
): IcalMeetingInfo {
    val organizerEmail = ICAL_ORGANIZER_REGEX.find(icalData)?.groupValues?.get(1)
        ?: fallbackOrganizer
    val summary = ICAL_SUMMARY_REGEX.find(icalData)?.groupValues?.get(1)
        ?.replace("\\n", " ")?.replace("\\,", ",")?.trim()
        ?: fallbackSummary
    val dtStartMatch = ICAL_DTSTART_REGEX.find(icalData)
    val dtStartTzid = dtStartMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    val dtStart = dtStartMatch?.groupValues?.get(2)
    val dtEndMatch = ICAL_DTEND_REGEX.find(icalData)
    val dtEndTzid = dtEndMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    val dtEnd = dtEndMatch?.groupValues?.get(2)
    val location = ICAL_LOCATION_REGEX.find(icalData)?.groupValues?.get(1)
        ?.replace("\\n", " ")?.replace("\\,", ",")?.trim() ?: ""
    val description = ICAL_DESCRIPTION_REGEX.find(icalData)?.groupValues?.get(1)
        ?.replace("\\n", "\n")?.replace("\\,", ",")?.trim() ?: ""
    val startTime = ICalParser.parseICalDate(dtStart, dtStartTzid) ?: nowMillis
    val endTime = ICalParser.parseICalDate(dtEnd, dtEndTzid) ?: (startTime + 60 * 60 * 1000)
    return IcalMeetingInfo(organizerEmail, summary, location, description, startTime, endTime)
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

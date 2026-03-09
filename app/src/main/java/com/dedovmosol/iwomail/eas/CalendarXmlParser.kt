package com.dedovmosol.iwomail.eas

/**
 * XML parsing for EAS/EWS Calendar responses.
 *
 * Extracted from EasCalendarService (Phase 2 of H-12 decomposition).
 *
 * Contains:
 *  - EAS Sync Add/Change event parsing (parseCalendarEvents)
 *  - EWS CalendarItem event parsing (parseEwsCalendarEvents)
 *  - Attendees parsing (EAS + EWS)
 *  - Attachments parsing (EAS + EWS)
 *  - Recurrence parsing (EAS MS-ASCAL + EWS)
 *  - Exception parsing (EAS MS-ASCAL Exceptions)
 *  - XML value extraction helpers
 *
 * Thread-safety: All methods are stateless (no shared mutable state).
 * Regex patterns are compiled once (companion object).
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
class CalendarXmlParser(
    private val parseEasDate: (String?) -> Long?,
    private val extractValue: (String, String) -> String?
) {

    companion object {
        val DELETE_SERVER_ID_PATTERN = "<(?:Delete|SoftDelete)>.*?<ServerId>(.*?)</ServerId>.*?</(?:Delete|SoftDelete)>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val RESPONSES_PATTERN = "<Responses>(.*?)</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val ADD_PATTERN = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val CHANGE_PATTERN = "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val ADD_OR_CHANGE_PATTERN = "<Add>(.*?)</Add>|<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val APP_DATA_PATTERN = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_RESPONSE_CODE = "<(?:m:)?ResponseCode>(.*?)</(?:m:)?ResponseCode>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_ITEM_ID = "<(?:t:)?ItemId[^>]*\\bId=\"([^\"]+)\"[^>]*\\bChangeKey=\"([^\"]+)\"".toRegex()
        val CHANGE_STATUS_PATTERN = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
        val DELETE_STATUS_PATTERN = "<Responses>.*?<Delete>.*?<Status>(\\d+)</Status>.*?</Delete>.*?</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EAS_CATEGORIES_PATTERN = "<calendar:Categories>(.*?)</calendar:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EAS_CATEGORY_PATTERN = "<calendar:Category>(.*?)</calendar:Category>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_CATEGORIES_PATTERN = "<(?:t:)?Categories>(.*?)</(?:t:)?Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_STRING_PATTERN = "<(?:t:)?String>(.*?)</(?:t:)?String>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val ATTENDEE_PATTERN = "<calendar:Attendee>(.*?)</calendar:Attendee>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val ATTACHMENT_PATTERN = "<(?:airsyncbase:)?Attachment>(.*?)</(?:airsyncbase:)?Attachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EAS_RECURRENCE_PATTERN = "<(?:calendar:)?Recurrence>(.*?)</(?:calendar:)?Recurrence>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EAS_EXCEPTION_PATTERN = "<(?:calendar:)?Exception>(.*?)</(?:calendar:)?Exception>".toRegex(RegexOption.DOT_MATCHES_ALL)
    }

    // ========================= EAS Event Parsing =========================

    fun parseCalendarEvents(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()

        for (match in ADD_OR_CHANGE_PATTERN.findAll(xml)) {
            val eventXml = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (eventXml.isEmpty()) continue

            val serverId = extractValue(eventXml, "ServerId") ?: continue
            val dataMatch = APP_DATA_PATTERN.find(eventXml) ?: continue
            val dataXml = dataMatch.groupValues[1]

            val subject = extractCalendarValue(dataXml, "Subject") ?: ""
            val location = extractCalendarValue(dataXml, "Location") ?: ""
            val body = extractCalendarBody(dataXml)

            val startTime = parseEasDate(extractCalendarValue(dataXml, "StartTime")) ?: 0L
            val endTime = parseEasDate(extractCalendarValue(dataXml, "EndTime")) ?: 0L

            val allDayEvent = extractCalendarValue(dataXml, "AllDayEvent") == "1"
            val reminder = extractCalendarValue(dataXml, "Reminder")?.toIntOrNull() ?: 0
            val busyStatus = extractCalendarValue(dataXml, "BusyStatus")?.toIntOrNull() ?: 2
            val sensitivity = extractCalendarValue(dataXml, "Sensitivity")?.toIntOrNull() ?: 0

            val organizerEmail = extractCalendarValue(dataXml, "OrganizerEmail")
                ?: extractCalendarValue(dataXml, "Organizer_Email") ?: ""
            val organizerName = extractCalendarValue(dataXml, "OrganizerName")
                ?: extractCalendarValue(dataXml, "Organizer_Name") ?: ""

            val categories = extractCalendarCategories(dataXml)
            val isRecurring = dataXml.contains("<calendar:Recurrence>") || dataXml.contains("<Recurrence>")
            val lastModified = parseEasDate(extractCalendarValue(dataXml, "DtStamp")) ?: System.currentTimeMillis()

            val uid = extractCalendarValue(dataXml, "UID") ?: ""
            val timezone = extractCalendarValue(dataXml, "Timezone") ?: ""
            val meetingStatusVal = extractCalendarValue(dataXml, "MeetingStatus")?.toIntOrNull() ?: 0
            val isMeeting = meetingStatusVal == 1 || meetingStatusVal == 3 || meetingStatusVal == 5 || meetingStatusVal == 7
            val responseRequested = extractCalendarValue(dataXml, "ResponseRequested") == "1"
            val responseType = extractCalendarValue(dataXml, "ResponseType")?.toIntOrNull() ?: 0
            val appointmentReplyTime = parseEasDate(extractCalendarValue(dataXml, "AppointmentReplyTime")) ?: 0L
            val disallowNewTimeProposal = extractCalendarValue(dataXml, "DisallowNewTimeProposal") == "1"
            val onlineMeetingLink = extractCalendarValue(dataXml, "OnlineMeetingExternalLink")
                ?: extractCalendarValue(dataXml, "OnlineMeetingConfLink") ?: ""

            val attachmentsJson = if (dataXml.contains("<Attachment>") || dataXml.contains("<airsyncbase:Attachment>"))
                parseEasAttachments(dataXml) else ""
            val hasAttachments = attachmentsJson.isNotBlank()

            val recurrenceRuleJson = if (isRecurring) parseEasRecurrence(dataXml) else ""
            val exceptionsJson = if (isRecurring) parseEasExceptions(dataXml) else ""

            events.add(
                EasCalendarEvent(
                    serverId = serverId, subject = subject,
                    startTime = startTime, endTime = endTime,
                    location = location, body = body,
                    allDayEvent = allDayEvent, reminder = reminder,
                    busyStatus = busyStatus, sensitivity = sensitivity,
                    organizer = organizerEmail, organizerName = organizerName,
                    attendees = parseAttendees(dataXml), categories = categories,
                    isRecurring = isRecurring, recurrenceRule = recurrenceRuleJson,
                    lastModified = lastModified, uid = uid,
                    timezone = timezone, exceptions = exceptionsJson,
                    isMeeting = isMeeting, meetingStatus = meetingStatusVal,
                    responseStatus = responseType, responseRequested = responseRequested,
                    appointmentReplyTime = appointmentReplyTime,
                    disallowNewTimeProposal = disallowNewTimeProposal,
                    onlineMeetingLink = onlineMeetingLink,
                    hasAttachments = hasAttachments, attachments = attachmentsJson
                )
            )
        }

        return events
    }

    // ========================= EWS Event Parsing =========================

    fun parseEwsCalendarEvents(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()
        val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (match in itemPattern.findAll(xml)) {
            val itemXml = match.groupValues[1]

            val itemId = "<(?:t:)?ItemId\\b[^>]*\\bId=\"([^\"]+)\""
                .toRegex(RegexOption.DOT_MATCHES_ALL).find(itemXml)?.groupValues?.getOrNull(1) ?: continue
            val subject = ewsTag(itemXml, "Subject") ?: ""
            val rawBody = "<(?:t:)?Body[^>]*>(.*?)</(?:t:)?Body>"
                .toRegex(RegexOption.DOT_MATCHES_ALL).find(itemXml)?.groupValues?.getOrNull(1)?.trim() ?: ""
            val body = CalendarDateUtils.removeDuplicateLines(XmlUtils.unescape(rawBody))

            val startStr = ewsTag(itemXml, "Start")
            val endStr = ewsTag(itemXml, "End")
            val startTime = CalendarDateUtils.parseEwsDateTime(startStr) ?: 0L
            val endTime = CalendarDateUtils.parseEwsDateTime(endStr) ?: 0L

            val location = ewsTag(itemXml, "Location") ?: ""
            val isAllDay = ewsTag(itemXml, "IsAllDayEvent")?.equals("true", ignoreCase = true) ?: false

            val organizerMatch = "<(?:t:)?Organizer>.*?<(?:t:)?Mailbox>(.*?)</(?:t:)?Mailbox>.*?</(?:t:)?Organizer>"
                .toRegex(RegexOption.DOT_MATCHES_ALL).find(itemXml)
            val organizerMailbox = organizerMatch?.groupValues?.get(1) ?: ""
            val organizer = ewsTag(organizerMailbox, "EmailAddress") ?: ""
            val organizerName = ewsTag(organizerMailbox, "Name") ?: ""
            val isRecurring = itemXml.contains("<t:Recurrence>") || itemXml.contains("<Recurrence>")
                || itemXml.contains("<t:IsRecurring>true</t:IsRecurring>")
                || itemXml.contains("<IsRecurring>true</IsRecurring>")

            val lastModified = CalendarDateUtils.parseEwsDateTime(ewsTag(itemXml, "LastModifiedTime")) ?: System.currentTimeMillis()

            val reminder = ewsTag(itemXml, "ReminderMinutesBeforeStart")?.toIntOrNull() ?: 0
            val reminderSet = ewsTag(itemXml, "ReminderIsSet")?.equals("true", ignoreCase = true) ?: false
            val ewsBusyStr = ewsTag(itemXml, "LegacyFreeBusyStatus") ?: "Busy"
            val busyStatus = when (ewsBusyStr) { "Free" -> 0; "Tentative" -> 1; "Busy" -> 2; "OOF" -> 3; "WorkingElsewhere" -> 4; else -> 2 }
            val ewsSensStr = ewsTag(itemXml, "Sensitivity") ?: "Normal"
            val sensitivity = when (ewsSensStr) { "Normal" -> 0; "Personal" -> 1; "Private" -> 2; "Confidential" -> 3; else -> 0 }

            val uid = ewsTag(itemXml, "UID") ?: ""

            val hasAttachments = ewsTag(itemXml, "HasAttachments")?.equals("true", ignoreCase = true) ?: false
            val ewsAttachmentsJson = if (hasAttachments) parseEwsAttachments(itemXml) else ""

            val isMeeting = ewsTag(itemXml, "IsMeeting")?.equals("true", ignoreCase = true) ?: false
            val isCancelled = ewsTag(itemXml, "IsCancelled")?.equals("true", ignoreCase = true) ?: false
            val meetingStatus = if (isCancelled) 5 else if (isMeeting) 1 else 0

            val responseRequested = ewsTag(itemXml, "IsResponseRequested")?.equals("true", ignoreCase = true) ?: false
            val disallowNewTime = ewsTag(itemXml, "AllowNewTimeProposal")?.equals("false", ignoreCase = true) ?: false

            val ewsResponseStr = ewsTag(itemXml, "MyResponseType") ?: "Unknown"
            val responseStatus = when (ewsResponseStr) { "Unknown" -> 0; "NoResponseReceived" -> 1; "Accept" -> 2; "Tentative" -> 3; "Decline" -> 4; else -> 0 }

            val ewsAttendees = parseEwsAttendees(itemXml)

            val categories = mutableListOf<String>()
            val ewsCategoriesMatch = EWS_CATEGORIES_PATTERN.find(itemXml)
            if (ewsCategoriesMatch != null) {
                EWS_STRING_PATTERN.findAll(ewsCategoriesMatch.groupValues[1]).forEach { catMatch ->
                    categories.add(catMatch.groupValues[1].trim())
                }
            }

            val ewsRecurrenceRule = if (isRecurring) parseEwsRecurrence(itemXml) else ""

            events.add(
                EasCalendarEvent(
                    serverId = itemId, subject = subject,
                    startTime = startTime, endTime = endTime,
                    location = location, body = body,
                    allDayEvent = isAllDay,
                    reminder = if (reminderSet) reminder else 0,
                    busyStatus = busyStatus, sensitivity = sensitivity,
                    organizer = organizer, organizerName = organizerName,
                    attendees = ewsAttendees, categories = categories,
                    isRecurring = isRecurring, recurrenceRule = ewsRecurrenceRule,
                    lastModified = lastModified, uid = uid,
                    isMeeting = isMeeting, meetingStatus = meetingStatus,
                    responseStatus = responseStatus, responseRequested = responseRequested,
                    disallowNewTimeProposal = disallowNewTime,
                    hasAttachments = hasAttachments, attachments = ewsAttachmentsJson
                )
            )
        }

        return events
    }

    // ========================= XML Value Extraction =========================

    fun extractCalendarValue(xml: String, tag: String): String? {
        val patterns = listOf(
            "<calendar:$tag>(.*?)</calendar:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val match = pattern.toRegex(RegexOption.DOT_MATCHES_ALL).find(xml)
            if (match != null) return XmlUtils.unescape(match.groupValues[1].trim())
        }
        return null
    }

    fun extractCalendarBody(xml: String): String {
        val bodyPatterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<calendar:Body>.*?<Data>(.*?)</Data>.*?</calendar:Body>",
            "<calendar:Body>(.*?)</calendar:Body>"
        )
        for (pattern in bodyPatterns) {
            val match = pattern.toRegex(RegexOption.DOT_MATCHES_ALL).find(xml)
            if (match != null) {
                val rawBody = XmlUtils.unescape(match.groupValues[1].trim())
                if (rawBody.isNotBlank()) return CalendarDateUtils.removeDuplicateLines(rawBody)
            }
        }
        return ""
    }

    fun extractCalendarCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesMatch = EAS_CATEGORIES_PATTERN.find(xml)
        if (categoriesMatch != null) {
            EAS_CATEGORY_PATTERN.findAll(categoriesMatch.groupValues[1]).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
        }
        return categories
    }

    fun extractAirsyncValue(xml: String, tag: String): String? {
        val patterns = listOf(
            "<airsyncbase:$tag>(.*?)</airsyncbase:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val match = pattern.toRegex(RegexOption.DOT_MATCHES_ALL).find(xml)
            if (match != null) return match.groupValues[1].trim()
        }
        return null
    }

    fun extractExceptionBody(exXml: String): String {
        val patterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<calendar:Body>.*?<Data>(.*?)</Data>.*?</calendar:Body>",
            "<calendar:Body>(.*?)</calendar:Body>"
        )
        for (pattern in patterns) {
            val match = pattern.toRegex(RegexOption.DOT_MATCHES_ALL).find(exXml)
            if (match != null && match.groupValues[1].isNotBlank())
                return CalendarDateUtils.removeDuplicateLines(XmlUtils.unescape(match.groupValues[1].trim()))
        }
        return ""
    }

    // ========================= Attendees Parsing =========================

    fun parseAttendees(xml: String): List<EasAttendee> {
        val attendees = mutableListOf<EasAttendee>()
        for (match in ATTENDEE_PATTERN.findAll(xml)) {
            val attendeeXml = match.groupValues[1]
            val email = extractCalendarValue(attendeeXml.replace("calendar:", ""), "Email")
                ?: """<calendar:Email>(.*?)</calendar:Email>""".toRegex().find(attendeeXml)?.groupValues?.get(1)
                ?: continue
            val name = """<calendar:Name>(.*?)</calendar:Name>""".toRegex().find(attendeeXml)?.groupValues?.get(1) ?: ""
            val type = """<calendar:AttendeeType>(.*?)</calendar:AttendeeType>""".toRegex().find(attendeeXml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val status = """<calendar:AttendeeStatus>(.*?)</calendar:AttendeeStatus>""".toRegex().find(attendeeXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            attendees.add(EasAttendee(email, name, status))
        }
        return attendees
    }

    fun parseEwsAttendees(xml: String): List<EasAttendee> {
        val attendees = mutableListOf<EasAttendee>()
        val listsPattern = "<(?:t:)?(?:RequiredAttendees|OptionalAttendees|Resources)>(.*?)</(?:t:)?(?:RequiredAttendees|OptionalAttendees|Resources)>"
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (listMatch in listsPattern.findAll(xml)) {
            val listXml = listMatch.groupValues[1]
            val attendeePattern = "<(?:t:)?Attendee>(.*?)</(?:t:)?Attendee>".toRegex(RegexOption.DOT_MATCHES_ALL)
            for (attMatch in attendeePattern.findAll(listXml)) {
                val attXml = attMatch.groupValues[1]
                val email = ewsTag(attXml, "EmailAddress") ?: continue
                val name = ewsTag(attXml, "Name") ?: ""
                val responseStr = ewsTag(attXml, "ResponseType") ?: "Unknown"
                val status = when (responseStr) { "Unknown" -> 0; "Tentative" -> 2; "Accept" -> 3; "Decline" -> 4; "NoResponseReceived" -> 5; else -> 0 }
                attendees.add(EasAttendee(email, name, status))
            }
        }
        return attendees
    }

    // ========================= Attachments Parsing =========================

    fun parseEasAttachments(xml: String): String {
        val attachments = mutableListOf<String>()
        for (match in ATTACHMENT_PATTERN.findAll(xml)) {
            val attXml = match.groupValues[1]
            val displayName = extractAirsyncValue(attXml, "DisplayName") ?: "attachment"
            val fileReference = extractAirsyncValue(attXml, "FileReference") ?: ""
            val estimatedSize = extractAirsyncValue(attXml, "EstimatedDataSize")?.toLongOrNull() ?: 0L
            val isInline = extractAirsyncValue(attXml, "IsInline") == "1"
            val contentId = extractAirsyncValue(attXml, "ContentId") ?: ""
            val method = extractAirsyncValue(attXml, "Method")?.toIntOrNull() ?: 1
            val escapedName = CalendarDateUtils.escapeJsonString(displayName)
            val escapedRef = CalendarDateUtils.escapeJsonString(fileReference)
            val escapedCid = CalendarDateUtils.escapeJsonString(contentId)
            attachments.add("""{"name":"$escapedName","fileReference":"$escapedRef","size":$estimatedSize,"isInline":$isInline,"contentId":"$escapedCid","method":$method}""")
        }
        return if (attachments.isEmpty()) "" else "[${attachments.joinToString(",")}]"
    }

    fun parseEwsAttachments(xml: String): String {
        val attachments = mutableListOf<String>()
        val attPattern = "<(?:t:)?(?:FileAttachment|ItemAttachment)>(.*?)</(?:t:)?(?:FileAttachment|ItemAttachment)>"
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in attPattern.findAll(xml)) {
            val attXml = match.groupValues[1]
            val attId = "<(?:t:)?AttachmentId[^>]*\\bId=\"([^\"]+)\"".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) ?: ""
            val name = ewsTag(attXml, "Name") ?: "attachment"
            val size = ewsTag(attXml, "Size")?.toLongOrNull() ?: 0L
            val isInline = ewsTag(attXml, "IsInline") == "true"
            val contentId = ewsTag(attXml, "ContentId") ?: ""
            val escapedName = CalendarDateUtils.escapeJsonString(name)
            val escapedRef = CalendarDateUtils.escapeJsonString(attId)
            val escapedCid = CalendarDateUtils.escapeJsonString(contentId)
            attachments.add("""{"name":"$escapedName","fileReference":"$escapedRef","size":$size,"isInline":$isInline,"contentId":"$escapedCid","method":1}""")
        }
        return if (attachments.isEmpty()) "" else "[${attachments.joinToString(",")}]"
    }

    // ========================= Recurrence Parsing =========================

    /**
     * Parses EAS recurrence rule (MS-ASCAL <calendar:Recurrence>).
     * Returns JSON: {"type","interval","dayOfWeek","dayOfMonth","weekOfMonth","monthOfYear","until","occurrences","firstDayOfWeek"}
     */
    fun parseEasRecurrence(xml: String): String {
        val recMatch = EAS_RECURRENCE_PATTERN.find(xml) ?: return ""
        val recXml = recMatch.groupValues[1]

        val type = extractCalendarValue(recXml, "Type")?.toIntOrNull() ?: 0
        val interval = extractCalendarValue(recXml, "Interval")?.toIntOrNull() ?: 1
        val dayOfWeek = extractCalendarValue(recXml, "DayOfWeek")?.toIntOrNull() ?: 0
        val dayOfMonth = extractCalendarValue(recXml, "DayOfMonth")?.toIntOrNull() ?: 0
        val weekOfMonth = extractCalendarValue(recXml, "WeekOfMonth")?.toIntOrNull() ?: 0
        val monthOfYear = extractCalendarValue(recXml, "MonthOfYear")?.toIntOrNull() ?: 0
        val until = parseEasDate(extractCalendarValue(recXml, "Until")) ?: 0L
        val occurrences = extractCalendarValue(recXml, "Occurrences")?.toIntOrNull() ?: 0
        val firstDayOfWeek = extractCalendarValue(recXml, "FirstDayOfWeek")?.toIntOrNull() ?: 0

        return """{"type":$type,"interval":$interval,"dayOfWeek":$dayOfWeek,"dayOfMonth":$dayOfMonth,"weekOfMonth":$weekOfMonth,"monthOfYear":$monthOfYear,"until":$until,"occurrences":$occurrences,"firstDayOfWeek":$firstDayOfWeek}"""
    }

    /**
     * Parses EWS recurrence rule (<t:Recurrence>).
     * Converts EWS format to unified JSON (compatible with EAS).
     * Namespace-tolerant: matches both <t:TAG> and <TAG>.
     */
    fun parseEwsRecurrence(xml: String): String {
        val recPattern = "<(?:t:)?Recurrence>(.*?)</(?:t:)?Recurrence>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val recMatch = recPattern.find(xml) ?: return ""
        val recXml = recMatch.groupValues[1]

        fun ewsVal(tag: String) = "<(?:t:)?$tag>(.*?)</(?:t:)?$tag>".toRegex(RegexOption.DOT_MATCHES_ALL).find(recXml)?.groupValues?.get(1)

        val type: Int
        var interval = 1
        var dayOfWeek = 0
        var dayOfMonth = 0
        var weekOfMonth = 0
        var monthOfYear = 0
        var firstDayOfWeek = 0

        when {
            recXml.contains("DailyRecurrence>") -> {
                type = 0
                interval = ewsVal("Interval")?.toIntOrNull() ?: 1
            }
            recXml.contains("WeeklyRecurrence>") -> {
                type = 1
                interval = ewsVal("Interval")?.toIntOrNull() ?: 1
                dayOfWeek = CalendarDateUtils.ewsDaysOfWeekToBitmask(ewsVal("DaysOfWeek") ?: "")
                firstDayOfWeek = CalendarDateUtils.ewsDayNameToNumber(ewsVal("FirstDayOfWeek") ?: "")
            }
            recXml.contains("AbsoluteMonthlyRecurrence>") -> {
                type = 2
                interval = ewsVal("Interval")?.toIntOrNull() ?: 1
                dayOfMonth = ewsVal("DayOfMonth")?.toIntOrNull() ?: 0
            }
            recXml.contains("RelativeMonthlyRecurrence>") -> {
                type = 3
                interval = ewsVal("Interval")?.toIntOrNull() ?: 1
                dayOfWeek = CalendarDateUtils.ewsDaysOfWeekToBitmask(ewsVal("DaysOfWeek") ?: "")
                weekOfMonth = CalendarDateUtils.ewsWeekIndexToNumber(ewsVal("DayOfWeekIndex") ?: "")
            }
            recXml.contains("AbsoluteYearlyRecurrence>") -> {
                type = 5
                dayOfMonth = ewsVal("DayOfMonth")?.toIntOrNull() ?: 0
                monthOfYear = CalendarDateUtils.ewsMonthToNumber(ewsVal("Month") ?: "")
            }
            recXml.contains("RelativeYearlyRecurrence>") -> {
                type = 6
                dayOfWeek = CalendarDateUtils.ewsDaysOfWeekToBitmask(ewsVal("DaysOfWeek") ?: "")
                weekOfMonth = CalendarDateUtils.ewsWeekIndexToNumber(ewsVal("DayOfWeekIndex") ?: "")
                monthOfYear = CalendarDateUtils.ewsMonthToNumber(ewsVal("Month") ?: "")
            }
            else -> return ""
        }

        var until = 0L
        var occurrences = 0
        val endDateStr = "<(?:t:)?EndDate>(.*?)</(?:t:)?EndDate>".toRegex(RegexOption.DOT_MATCHES_ALL).find(recXml)?.groupValues?.get(1)
        if (endDateStr != null) {
            until = CalendarDateUtils.parseEwsDateTime("${endDateStr}T23:59:59Z") ?: 0L
        }
        val occStr = "<(?:t:)?NumberOfOccurrences>(.*?)</(?:t:)?NumberOfOccurrences>".toRegex(RegexOption.DOT_MATCHES_ALL).find(recXml)?.groupValues?.get(1)
        if (occStr != null) {
            occurrences = occStr.toIntOrNull() ?: 0
        }

        return """{"type":$type,"interval":$interval,"dayOfWeek":$dayOfWeek,"dayOfMonth":$dayOfMonth,"weekOfMonth":$weekOfMonth,"monthOfYear":$monthOfYear,"until":$until,"occurrences":$occurrences,"firstDayOfWeek":$firstDayOfWeek}"""
    }

    /**
     * Parses EAS exceptions from recurring events (MS-ASCAL Exceptions).
     * Returns JSON array: [{exceptionStartTime, deleted, subject, location, startTime, endTime, body}]
     */
    fun parseEasExceptions(xml: String): String {
        val exceptions = mutableListOf<String>()
        for (match in EAS_EXCEPTION_PATTERN.findAll(xml)) {
            val exXml = match.groupValues[1]
            val exStartTime = parseEasDate(extractCalendarValue(exXml, "ExceptionStartTime")) ?: 0L
            val deleted = extractCalendarValue(exXml, "Deleted") == "1"
            val subject = CalendarDateUtils.escapeJsonString(extractCalendarValue(exXml, "Subject") ?: "")
            val location = CalendarDateUtils.escapeJsonString(extractCalendarValue(exXml, "Location") ?: "")
            val startTime = parseEasDate(extractCalendarValue(exXml, "StartTime")) ?: 0L
            val endTime = parseEasDate(extractCalendarValue(exXml, "EndTime")) ?: 0L
            val body = CalendarDateUtils.escapeJsonString(extractExceptionBody(exXml))
            exceptions.add("""{"exceptionStartTime":$exStartTime,"deleted":$deleted,"subject":"$subject","location":"$location","startTime":$startTime,"endTime":$endTime,"body":"$body"}""")
        }
        return if (exceptions.isEmpty()) "" else "[${exceptions.joinToString(",")}]"
    }

    // ========================= Private Helpers =========================

    private fun ewsTag(xml: String, tag: String): String? {
        return "<(?:t:)?$tag>(.*?)</(?:t:)?$tag>".toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.getOrNull(1)
    }
}

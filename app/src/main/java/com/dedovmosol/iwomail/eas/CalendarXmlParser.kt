package com.dedovmosol.iwomail.eas

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

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
 * EAS parsing: one XmlPullParser per call (local); EWS: regex patterns compiled once (companion object).
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
class CalendarXmlParser(
    private val parseEasDate: (String?) -> Long?
) {

    companion object {
        val DELETE_SERVER_ID_PATTERN = "<(?:Delete|SoftDelete)>.*?<ServerId>(.*?)</ServerId>.*?</(?:Delete|SoftDelete)>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val RESPONSES_PATTERN = "<Responses>(.*?)</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val ADD_PATTERN = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_RESPONSE_CODE = "<(?:m:)?ResponseCode>(.*?)</(?:m:)?ResponseCode>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_ITEM_ID = "<(?:t:)?ItemId[^>]*\\bId=\"([^\"]+)\"[^>]*\\bChangeKey=\"([^\"]+)\"".toRegex()
        val CHANGE_STATUS_PATTERN = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
        val DELETE_STATUS_PATTERN = "<Responses>.*?<Delete>.*?<Status>(\\d+)</Status>.*?</Delete>.*?</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_CATEGORIES_PATTERN = "<(?:t:)?Categories>(.*?)</(?:t:)?Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val EWS_STRING_PATTERN = "<(?:t:)?String>(.*?)</(?:t:)?String>".toRegex(RegexOption.DOT_MATCHES_ALL)
    }

    // ========================= EAS Event Parsing =========================

    /**
     * Разбор EAS Sync календаря (MS-ASCAL) — ОДИН проход потокового XmlPullParser по всему ответу.
     *
     * Вход — вывод [WbxmlParser.parse]: ГОЛЫЕ теги (без namespace-префиксов; проверено round-trip через
     * реальный кодек). Поэтому принадлежность поля определяется ТОЛЬКО позицией в дереве:
     *  - поля серии — ПРЯМЫЕ дети `ApplicationData` (то, что внутри `Exceptions`, `Recurrence`,
     *    `Attendees`, `Attachments`, не «протекает» в серию — прежний regex брал первое совпадение где угодно);
     *  - `Body`/`Type`/`Attachment`/`DisplayName` совпадают по имени в Calendar и AirSyncBase — скоуп решает.
     * Берутся только `Commands/Add|Change` (в `Responses/Add` у ответов на клиентские команды нет ApplicationData).
     *
     * Целостность: если документ оборван или битый, бросаем исключение. KXmlParser на обрыве выдаёт
     * END_DOCUMENT с открытыми тегами (проверено эмпирически) — молча вернуть «часть событий» нельзя:
     * календарь синкается с SyncKey=0 (полный снимок), и частичный список как «авторитетный» привёл бы к
     * удалению реальных локальных событий. Исключение → EasResult.Error → ветка ошибок синка
     * (неавторитетный результат, без удалений).
     *
     * JSON-форматы recurrenceRule/exceptions/attachments — прежние байт-в-байт (их потребляют
     * CalendarRepository/RecurrenceHelper/UI).
     */
    fun parseCalendarEvents(xml: String): List<EasCalendarEvent> {
        val p = EasXmlParser.createParser(xml) // единая усиленная фабрика (DOCTYPE/XXE отключены)
        val events = mutableListOf<EasCalendarEvent>()
        var commandsDepth = -1
        var type = p.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> when {
                    commandsDepth < 0 && p.name == "Commands" -> commandsDepth = p.depth
                    commandsDepth > 0 && p.depth == commandsDepth + 1 && (p.name == "Add" || p.name == "Change") ->
                        readSyncItem(p)?.let { events.add(it) }
                }
                XmlPullParser.END_TAG -> if (p.depth == commandsDepth) commandsDepth = -1
            }
            type = p.next()
        }
        if (p.depth > 0) throw XmlPullParserException("Truncated EAS Sync response: unclosed elements at depth ${p.depth}")
        return events
    }

    // ---------- скоупированное чтение (парсер стоит на START_TAG, выходит на его END_TAG) ----------

    /** Полный текст элемента (вся вложенность), сущности развёрнуты; trim; пустой → null. */
    private fun leafText(p: XmlPullParser): String? {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(p.text)
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** Обходит ПРЯМЫХ детей текущего элемента; [onChild] обязан дочитать ребёнка (или вызвать skip). */
    private inline fun children(p: XmlPullParser, onChild: (String) -> Unit) {
        val parentDepth = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> onChild(p.name)
                XmlPullParser.END_TAG -> if (p.depth == parentDepth) return
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
            }
        }
    }

    /** Карта «имя прямого ребёнка → текст» (первое вхождение); вложенные контейнеры пропускаются. */
    private fun leafMap(p: XmlPullParser): Map<String, String> {
        val map = HashMap<String, String>()
        children(p) { name ->
            val value = leafText(p)
            if (value != null && name !in map) map[name] = value
        }
        return map
    }

    /**
     * `<Body>`: текст `Data` (AirSyncBase: `<Body><Type/><Data>…</Data></Body>`), иначе — собственный
     * текст элемента (EAS 2.5: тело прямо в `<Body>`). Прочие дети (`Type`, `EstimatedDataSize`…)
     * пропускаются. Значение тримится — как прежде (`trim()` перед unescape в старом regex-коде).
     * (Альтернативы `Organizer_Email`/`Organizer_Name` старого кода не перенесены: таких тегов нет
     * ни в одной WBXML code page — декодер их выдать не может.)
     */
    private fun readBody(p: XmlPullParser): String? {
        if (p.isEmptyElementTag) { p.next(); return null }
        val parentDepth = p.depth
        val ownText = StringBuilder()
        var data: String? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG ->
                    if (p.name == "Data" && data == null) data = leafText(p) else EasXmlParser.skip(p)
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> ownText.append(p.text)
                XmlPullParser.END_TAG -> if (p.depth == parentDepth) break
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unexpected end of document")
            }
        }
        return data ?: ownText.toString().trim().ifEmpty { null }
    }

    private fun readSyncItem(p: XmlPullParser): EasCalendarEvent? {
        var serverId: String? = null
        var event: EasCalendarEvent? = null
        children(p) { name ->
            when (name) {
                "ServerId" -> if (serverId == null) serverId = leafText(p) else EasXmlParser.skip(p)
                "ApplicationData" -> event = readApplicationData(p, serverId)
                else -> EasXmlParser.skip(p)
            }
        }
        val id = serverId ?: return null
        return event?.copy(serverId = id)
    }

    private fun readApplicationData(p: XmlPullParser, serverIdHint: String?): EasCalendarEvent? {
        if (p.isEmptyElementTag) {
            p.next() // KXml выдаёт END_TAG и для <ApplicationData/>
            return null
        }
        val leaves = HashMap<String, String>()
        var body = ""
        var attendees: List<EasAttendee> = emptyList()
        var categories: List<String> = emptyList()
        var recurrence: Map<String, String>? = null
        var exceptions: List<String> = emptyList()
        var attachments: List<String> = emptyList()

        children(p) { name ->
            when (name) {
                "Body" -> {
                    val text = readBody(p)
                    if (body.isEmpty() && !text.isNullOrBlank()) body = CalendarDateUtils.removeDuplicateLines(text)
                }
                "Attendees" -> attendees = readAttendees(p)
                "Categories" -> categories = readCategories(p)
                // Пустой <Recurrence/> прежде давал «не повторяется» (regex требовал парный тег) — сохраняем.
                "Recurrence" -> if (p.isEmptyElementTag) p.next() else recurrence = leafMap(p)
                "Exceptions" -> exceptions = readExceptions(p)
                "Attachments" -> attachments = readAttachments(p)
                else -> {
                    val value = leafText(p)
                    if (value != null && name !in leaves) leaves[name] = value
                }
            }
        }

        fun v(tag: String) = leaves[tag]
        val meetingStatus = v("MeetingStatus")?.toIntOrNull() ?: 0
        val isRecurring = recurrence != null
        val attachmentsJson = if (attachments.isEmpty()) "" else "[${attachments.joinToString(",")}]"
        return EasCalendarEvent(
            serverId = serverIdHint ?: "",
            subject = v("Subject") ?: "",
            location = v("Location") ?: "",
            body = body,
            startTime = parseEasDate(v("StartTime")) ?: 0L,
            endTime = parseEasDate(v("EndTime")) ?: 0L,
            allDayEvent = v("AllDayEvent") == "1",
            reminder = v("Reminder")?.toIntOrNull() ?: 0,
            busyStatus = v("BusyStatus")?.toIntOrNull() ?: 2,
            sensitivity = v("Sensitivity")?.toIntOrNull() ?: 0,
            organizer = v("OrganizerEmail") ?: "",
            organizerName = v("OrganizerName") ?: "",
            attendees = attendees,
            isRecurring = isRecurring,
            recurrenceRule = recurrence?.let(::recurrenceJson) ?: "",
            uid = v("UID") ?: "",
            timezone = v("Timezone") ?: "",
            exceptions = if (isRecurring && exceptions.isNotEmpty()) "[${exceptions.joinToString(",")}]" else "",
            categories = categories,
            lastModified = parseEasDate(v("DtStamp")) ?: System.currentTimeMillis(),
            responseStatus = v("ResponseType")?.toIntOrNull() ?: 0,
            isMeeting = meetingStatus == 1 || meetingStatus == 3 || meetingStatus == 5 || meetingStatus == 7,
            meetingStatus = meetingStatus,
            responseRequested = v("ResponseRequested") == "1",
            appointmentReplyTime = parseEasDate(v("AppointmentReplyTime")) ?: 0L,
            disallowNewTimeProposal = v("DisallowNewTimeProposal") == "1",
            onlineMeetingLink = v("OnlineMeetingExternalLink") ?: v("OnlineMeetingConfLink") ?: "",
            hasAttachments = attachmentsJson.isNotEmpty(),
            attachments = attachmentsJson
        )
    }

    private fun readAttendees(p: XmlPullParser): List<EasAttendee> {
        val result = mutableListOf<EasAttendee>()
        children(p) { name ->
            if (name != "Attendee") { EasXmlParser.skip(p); return@children }
            val f = leafMap(p)
            val email = f["Email"] ?: return@children
            result.add(EasAttendee(email, f["Name"] ?: "", f["AttendeeStatus"]?.toIntOrNull() ?: 0))
        }
        return result
    }

    private fun readCategories(p: XmlPullParser): List<String> {
        val result = mutableListOf<String>()
        children(p) { name -> if (name == "Category") leafText(p)?.let { result.add(it) } else EasXmlParser.skip(p) }
        return result
    }

    private fun readExceptions(p: XmlPullParser): List<String> {
        val result = mutableListOf<String>()
        children(p) { name ->
            if (name != "Exception") { EasXmlParser.skip(p); return@children }
            val leaves = HashMap<String, String>()
            var body = ""
            children(p) { field ->
                if (field == "Body") {
                    val text = readBody(p)
                    if (body.isEmpty() && !text.isNullOrBlank()) body = CalendarDateUtils.removeDuplicateLines(text)
                } else {
                    val value = leafText(p)
                    if (value != null && field !in leaves) leaves[field] = value
                }
            }
            val esc = CalendarDateUtils::escapeJsonString
            result.add(
                """{"exceptionStartTime":${parseEasDate(leaves["ExceptionStartTime"]) ?: 0L},"deleted":${leaves["Deleted"] == "1"},""" +
                    """"subject":"${esc(leaves["Subject"] ?: "")}","location":"${esc(leaves["Location"] ?: "")}",""" +
                    """"startTime":${parseEasDate(leaves["StartTime"]) ?: 0L},"endTime":${parseEasDate(leaves["EndTime"]) ?: 0L},""" +
                    """"body":"${esc(body)}"}"""
            )
        }
        return result
    }

    private fun readAttachments(p: XmlPullParser): List<String> {
        val result = mutableListOf<String>()
        children(p) { name ->
            if (name != "Attachment") { EasXmlParser.skip(p); return@children }
            val f = leafMap(p)
            val esc = CalendarDateUtils::escapeJsonString
            result.add(
                """{"name":"${esc(f["DisplayName"] ?: "attachment")}","fileReference":"${esc(f["FileReference"] ?: "")}",""" +
                    """"size":${f["EstimatedDataSize"]?.toLongOrNull() ?: 0L},"isInline":${f["IsInline"] == "1"},""" +
                    """"contentId":"${esc(f["ContentId"] ?: "")}","method":${f["Method"]?.toIntOrNull() ?: 1}}"""
            )
        }
        return result
    }

    /** Прежний формат parseEasRecurrence байт-в-байт. */
    private fun recurrenceJson(r: Map<String, String>): String {
        fun i(tag: String, def: Int) = r[tag]?.toIntOrNull() ?: def
        return """{"type":${i("Type", 0)},"interval":${i("Interval", 1)},"dayOfWeek":${i("DayOfWeek", 0)},""" +
            """"dayOfMonth":${i("DayOfMonth", 0)},"weekOfMonth":${i("WeekOfMonth", 0)},"monthOfYear":${i("MonthOfYear", 0)},""" +
            """"until":${parseEasDate(r["Until"]) ?: 0L},"occurrences":${i("Occurrences", 0)},"firstDayOfWeek":${i("FirstDayOfWeek", 0)}}"""
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

    // ========================= EWS Attendees Parsing =========================

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

    // ========================= EWS Attachments Parsing =========================

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

    // ========================= EWS Recurrence Parsing =========================

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

    // ========================= Private Helpers =========================

    private fun ewsTag(xml: String, tag: String): String? {
        return "<(?:t:)?$tag>(.*?)</(?:t:)?$tag>".toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.getOrNull(1)
    }
}

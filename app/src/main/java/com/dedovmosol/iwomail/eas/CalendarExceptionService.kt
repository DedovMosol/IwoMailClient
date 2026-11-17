package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import kotlinx.coroutines.CancellationException

/**
 * Handles recurring calendar event exceptions (EAS + EWS).
 *
 * Extracted from EasCalendarService (Phase 3 completion of H-12 decomposition).
 *
 * Responsibilities:
 * - Building EAS Exception Sync XML (buildExceptionSyncXml)
 * - Supplementing recurring exceptions via EWS CalendarView
 * - Building exceptions JSON from EWS occurrences
 *
 * EAS: MS-ASCAL 2.2.2.21 Exception — max 256 per Exceptions element.
 *   ExceptionStartTime (2.2.2.23) — REQUIRED, identifies the occurrence.
 *   airsyncbase:Body — only supported for EAS 14.0+ (guard: majorVersion >= 14).
 * EWS: CalendarView expands recurring events, CalendarItemType="Exception" for modified occurrences.
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
class CalendarExceptionService(
    private val escapeXml: (String) -> String,
    private val formatEasDate: (Long) -> String,
    private val ewsRequest: suspend (String, String) -> EasResult<String>
) {

    internal data class EwsExceptionOccurrence(
        val uid: String,
        val subject: String,
        val body: String,
        val startMs: Long,
        val endMs: Long,
        val location: String,
        val originalStartMs: Long
    )

    /**
     * Builds EAS Sync Change XML with updated Exceptions for a recurring master.
     *
     * MS-ASCAL: Exceptions element contains ALL exceptions for the series.
     * Existing exceptions are re-emitted, plus the new/modified one.
     * airsyncbase:Body inside Exception only for majorVersion >= 14.
     */
    fun buildExceptionSyncXml(
        syncKey: String,
        collectionId: String,
        serverId: String,
        majorVersion: Int,
        existingExceptions: List<RecurrenceHelper.RecurrenceException>,
        occurrenceOriginalStart: Long,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int
    ): String {
        val escapedSubject = escapeXml(subject)
        val escapedLocation = escapeXml(location)
        val escapedBody = escapeXml(body)

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            if (majorVersion >= 14) {
                append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">""")
            } else {
                append("""<Sync xmlns="AirSync" xmlns:calendar="Calendar">""")
            }
            append("<Collections><Collection>")
            append("<SyncKey>${escapeXml(syncKey)}</SyncKey>")
            append("<CollectionId>${escapeXml(collectionId)}</CollectionId>")
            append("<Commands><Change>")
            append("<ServerId>${escapeXml(serverId)}</ServerId>")
            append("<ApplicationData>")

            append("<calendar:Exceptions>")

            for (ex in existingExceptions) {
                if (kotlin.math.abs(ex.exceptionStartTime - occurrenceOriginalStart) <= RecurrenceHelper.DST_TOLERANCE_MS) continue
                appendExceptionXml(ex, majorVersion)
            }

            append("<calendar:Exception>")
            append("<calendar:ExceptionStartTime>${formatEasDate(occurrenceOriginalStart)}</calendar:ExceptionStartTime>")
            append("<calendar:Subject>$escapedSubject</calendar:Subject>")
            append("<calendar:StartTime>${formatEasDate(startTime)}</calendar:StartTime>")
            append("<calendar:EndTime>${formatEasDate(endTime)}</calendar:EndTime>")
            append("<calendar:Location>$escapedLocation</calendar:Location>")
            if (majorVersion >= 14) {
                append("<airsyncbase:Body>")
                append("<airsyncbase:Type>${CalendarBodyFormat.easType(body)}</airsyncbase:Type>")
                append("<airsyncbase:Data>$escapedBody</airsyncbase:Data>")
                append("</airsyncbase:Body>")
            }
            append("<calendar:AllDayEvent>${if (allDayEvent) "1" else "0"}</calendar:AllDayEvent>")
            append("<calendar:Reminder>$reminder</calendar:Reminder>")
            append("<calendar:BusyStatus>$busyStatus</calendar:BusyStatus>")
            append("<calendar:Sensitivity>$sensitivity</calendar:Sensitivity>")
            append("</calendar:Exception>")

            append("</calendar:Exceptions>")

            append("</ApplicationData>")
            append("</Change></Commands>")
            append("</Collection></Collections>")
            append("</Sync>")
        }
    }

    private fun StringBuilder.appendExceptionXml(
        ex: RecurrenceHelper.RecurrenceException,
        majorVersion: Int
    ) {
        append("<calendar:Exception>")
        append("<calendar:ExceptionStartTime>${formatEasDate(ex.exceptionStartTime)}</calendar:ExceptionStartTime>")
        if (ex.deleted) {
            append("<calendar:Deleted>1</calendar:Deleted>")
        } else {
            if (ex.subject.isNotBlank()) append("<calendar:Subject>${escapeXml(ex.subject)}</calendar:Subject>")
            if (ex.startTime > 0) append("<calendar:StartTime>${formatEasDate(ex.startTime)}</calendar:StartTime>")
            if (ex.endTime > 0) append("<calendar:EndTime>${formatEasDate(ex.endTime)}</calendar:EndTime>")
            if (ex.location.isNotBlank()) append("<calendar:Location>${escapeXml(ex.location)}</calendar:Location>")
            if (ex.body.isNotBlank() && majorVersion >= 14) {
                append("<airsyncbase:Body>")
                append("<airsyncbase:Type>${CalendarBodyFormat.easType(ex.body)}</airsyncbase:Type>")
                append("<airsyncbase:Data>${escapeXml(ex.body)}</airsyncbase:Data>")
                append("</airsyncbase:Body>")
            }
        }
        append("</calendar:Exception>")
    }

    /**
     * Supplements exceptions for recurring events via EWS FindItem + CalendarView.
     *
     * Exchange 2007 SP1 EAS Sync (GetChanges) may NOT mark recurring master
     * as changed when only a single occurrence is modified in Outlook on PC.
     *
     * Solution: EWS FindItem + CalendarView returns all expanded occurrences,
     * including modified ones (CalendarItemType = "Exception"); GetItem adds Body and
     * OriginalStart, which Exchange 2007 rejects in FindItem (ErrorInvalidPropertyForOperation).
     * Match them to local masters by UID and update exceptions JSON.
     */
    suspend fun supplementRecurringExceptionsViaEws(
        localRecurringEvents: List<EasCalendarService.RecurringEventInfo>
    ): Map<String, String> {
        if (localRecurringEvents.isEmpty()) return emptyMap()

        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val windowStartMs = cal.timeInMillis
        val startStr = String.format(
            java.util.Locale.US,
            "%04d-%02d-%02dT00:00:00Z",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        cal.timeInMillis = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val windowEndMs = cal.timeInMillis
        val endStr = String.format(
            java.util.Locale.US,
            "%04d-%02d-%02dT23:59:59Z",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )

        val findResult = ewsRequest(calendarViewRequest(startStr, endStr), "FindItem")
        if (findResult is EasResult.Error) {
            android.util.Log.w(
                "CalendarExceptionService",
                "supplementRecurringExceptionsViaEws: FindItem failed: ${findResult.message}"
            )
            return emptyMap()
        }
        val findXml = (findResult as EasResult.Success).data

        // Защита от data loss: если ответ содержит embedded ошибку или CalendarView
        // вернул неполный набор (>2000 occurrences), отказываемся от cleanup —
        // существующие local exceptions останутся нетронутыми. Иначе buildExceptionsJsonFromEws
        // удалит non-deleted exceptions внутри окна, не возвращённые EWS.
        if (findXml.contains("ResponseClass=\"Error\"")) {
            val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(findXml)?.groupValues?.get(1)
            android.util.Log.w(
                "CalendarExceptionService",
                "supplementRecurringExceptionsViaEws: FindItem returned ResponseClass=Error: ${messageText ?: "<no message>"} — skipping cleanup"
            )
            return emptyMap()
        }
        if (!findXml.contains("IncludesLastItemInRange=\"true\"")) {
            android.util.Log.w(
                "CalendarExceptionService",
                "supplementRecurringExceptionsViaEws: CalendarView truncated (MaxEntriesReturned=2000 reached) — skipping cleanup to prevent data loss"
            )
            return emptyMap()
        }

        val localUids = localRecurringEvents.map { it.uid }.toSet()
        val candidates = parseCalendarViewExceptions(findXml, localUids)

        // Body и OriginalStart FindItem не отдаёт (Exchange 2007: ErrorInvalidPropertyForOperation,
        // проверено на сервере) — берём их GetItem по ItemId вхождений-исключений.
        // Без OriginalStart исключение не сопоставить с локальным: не получили — ничего не меняем.
        val exceptions = mutableListOf<EwsExceptionOccurrence>()
        for (chunk in candidates.chunked(GET_ITEM_BATCH)) {
            val getResult = ewsRequest(exceptionDetailsRequest(chunk.map { it.itemId }), "GetItem")
            if (getResult is EasResult.Error) {
                android.util.Log.w("CalendarExceptionService", "supplementRecurringExceptionsViaEws: GetItem failed: ${getResult.message}")
                return emptyMap()
            }
            val details = parseExceptionDetails((getResult as EasResult.Success).data, chunk)
            if (details == null) {
                android.util.Log.w("CalendarExceptionService", "supplementRecurringExceptionsViaEws: GetItem response incomplete — skipping")
                return emptyMap()
            }
            exceptions += details
        }

        if (exceptions.isEmpty()) {
            android.util.Log.d(
                "CalendarExceptionService",
                "supplementRecurringExceptionsViaEws: no EWS exceptions found for ${localRecurringEvents.size} recurring events"
            )
            return emptyMap()
        }

        val grouped = exceptions.groupBy { it.uid }

        android.util.Log.d(
            "CalendarExceptionService",
            "supplementRecurringExceptionsViaEws: found ${exceptions.size} exception occurrences for ${grouped.size} recurring series"
        )

        val result = mutableMapOf<String, String>()
        val processedUids = mutableSetOf<String>()
        for ((uid, occurrences) in grouped) {
            val local = localRecurringEvents.find { it.uid == uid } ?: continue
            processedUids.add(uid)
            val newExceptionsJson = buildExceptionsJsonFromEws(
                occurrences, local.currentExceptions, windowStartMs, windowEndMs
            )
            if (newExceptionsJson != local.currentExceptions) {
                result[uid] = newExceptionsJson
            }
        }

        for (local in localRecurringEvents) {
            if (local.uid in processedUids) continue
            if (local.currentExceptions.isBlank()) continue
            val cleaned = buildExceptionsJsonFromEws(
                emptyList(), local.currentExceptions, windowStartMs, windowEndMs
            )
            if (cleaned != local.currentExceptions) {
                result[local.uid] = cleaned
            }
        }

        android.util.Log.d(
            "CalendarExceptionService",
            "supplementRecurringExceptionsViaEws: ${result.size} recurring events have updated exceptions"
        )
        return result
    }

    /**
     * Builds exceptions JSON from EWS occurrences.
     * Preservation logic:
     *  - deleted → always keep (CalendarView does not return deleted occurrences)
     *  - non-deleted, outside CalendarView window → keep (no data to verify)
     *  - non-deleted, inside window, present in EWS → overwrite with fresh data
     *  - non-deleted, inside window, NOT in EWS → exception "undone" on server → drop
     */
    private fun buildExceptionsJsonFromEws(
        ewsOccurrences: List<EwsExceptionOccurrence>,
        currentExceptionsJson: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): String {
        val entries = mutableListOf<String>()
        val currentByOriginalStart = mutableMapOf<Long, org.json.JSONObject>()

        if (currentExceptionsJson.isNotBlank()) {
            try {
                val arr = org.json.JSONArray(currentExceptionsJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val isDeleted = obj.optBoolean("deleted", false)
                    val exStartTime = obj.optLong("exceptionStartTime", 0L)
                    if (exStartTime > 0L) {
                        currentByOriginalStart[exStartTime] = obj
                    }
                    // CalendarView отдаёт вхождение по ФАКТИЧЕСКОМУ времени: исключение, перенесённое за край
                    // окна, сервер не вернёт — проверить его нечем, значит не удаляем.
                    val actualStart = obj.optLong("startTime", 0L)
                    val isOutsideWindow = exStartTime < windowStartMs || exStartTime > windowEndMs ||
                        (actualStart > 0L && (actualStart < windowStartMs || actualStart > windowEndMs))
                    if (isDeleted || isOutsideWindow) {
                        entries.add(obj.toString())
                    }
                }
            } catch (_: Exception) { }
        }

        for (occ in ewsOccurrences) {
            val subject = CalendarDateUtils.escapeJsonString(occ.subject)
            val body = CalendarDateUtils.escapeJsonString(occ.body)
            val location = CalendarDateUtils.escapeJsonString(occ.location)
            val existing = currentByOriginalStart[occ.originalStartMs]
            val attachments = if (existing?.optBoolean("attachmentsOverridden", false) == true) {
                CalendarDateUtils.escapeJsonString(existing.optString("attachments", ""))
            } else ""
            val attachmentsOverridden = existing?.optBoolean("attachmentsOverridden", false) == true

            entries.add(
                """{"exceptionStartTime":${occ.originalStartMs},"deleted":false,"subject":"$subject","location":"$location","startTime":${occ.startMs},"endTime":${occ.endMs},"body":"$body","attachments":"$attachments","attachmentsOverridden":$attachmentsOverridden}"""
            )
        }

        return if (entries.isEmpty()) "" else "[${entries.joinToString(",")}]"
    }

    /** Вхождение-исключение из CalendarView: всё, что FindItem может вернуть на Exchange 2007. */
    internal data class ExceptionCandidate(
        val itemId: String,
        val uid: String,
        val subject: String,
        val startMs: Long,
        val endMs: Long,
        val location: String
    )

    internal companion object {
        const val GET_ITEM_BATCH = 50

        private val CALENDAR_ITEM = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val GET_ITEM_MESSAGE =
            "<(?:m:)?GetItemResponseMessage\\b[^>]*\\bResponseClass=\"(\\w+)\"[^>]*>(.*?)</(?:m:)?GetItemResponseMessage>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
        private val BODY = "<(?:t:)?Body\\b[^>]*?(?:/>|>(.*?)</(?:t:)?Body>)".toRegex(RegexOption.DOT_MATCHES_ALL)

        private fun envelope(body: String) = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header><t:RequestServerVersion Version="Exchange2007_SP1"/></soap:Header>
    <soap:Body>$body</soap:Body>
</soap:Envelope>"""

        /**
         * FindItem + CalendarView. Только свойства, допустимые в FindItem на Exchange 2007:
         * item:Body и calendar:OriginalStart дают ErrorInvalidPropertyForOperation на весь запрос.
         */
        fun calendarViewRequest(startDate: String, endDate: String): String = envelope(
            "<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                "<t:FieldURI FieldURI=\"item:Subject\"/><t:FieldURI FieldURI=\"calendar:Start\"/>" +
                "<t:FieldURI FieldURI=\"calendar:End\"/><t:FieldURI FieldURI=\"calendar:Location\"/>" +
                "<t:FieldURI FieldURI=\"calendar:CalendarItemType\"/><t:FieldURI FieldURI=\"calendar:UID\"/>" +
                "</t:AdditionalProperties></m:ItemShape>" +
                "<m:CalendarView StartDate=\"${XmlUtils.escape(startDate)}\" EndDate=\"${XmlUtils.escape(endDate)}\" MaxEntriesReturned=\"2000\"/>" +
                "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds></m:FindItem>"
        )

        /** GetItem тела и исходного времени вхождений (без BodyType — как раньше, Best). */
        fun exceptionDetailsRequest(itemIds: List<String>): String = envelope(
            "<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                "<t:FieldURI FieldURI=\"item:Body\"/><t:FieldURI FieldURI=\"calendar:OriginalStart\"/>" +
                "<t:FieldURI FieldURI=\"calendar:UID\"/></t:AdditionalProperties></m:ItemShape><m:ItemIds>" +
                itemIds.joinToString("") { "<t:ItemId Id=\"${XmlUtils.escape(it)}\"/>" } +
                "</m:ItemIds></m:GetItem>"
        )

        fun parseCalendarViewExceptions(findXml: String, localUids: Set<String>): List<ExceptionCandidate> =
            CALENDAR_ITEM.findAll(findXml).mapNotNull { m ->
                val xml = m.groupValues[1]
                if (XmlValueExtractor.extractEws(xml, "CalendarItemType") != "Exception") return@mapNotNull null
                val uid = XmlValueExtractor.extractEws(xml, "UID")?.takeIf { it in localUids } ?: return@mapNotNull null
                val itemId = XmlValueExtractor.extractAttribute(xml, "ItemId", "Id") ?: return@mapNotNull null
                ExceptionCandidate(
                    itemId = XmlUtils.unescape(itemId),
                    uid = uid,
                    subject = XmlValueExtractor.extractEws(xml, "Subject") ?: "",
                    startMs = CalendarDateUtils.parseEwsDateTime(XmlValueExtractor.extractEws(xml, "Start")) ?: 0L,
                    endMs = CalendarDateUtils.parseEwsDateTime(XmlValueExtractor.extractEws(xml, "End")) ?: 0L,
                    location = XmlValueExtractor.extractEws(xml, "Location") ?: ""
                )
            }.toList()

        /**
         * Ответы GetItem идут в порядке запрошенных ItemId. null — ответ неполный, с ошибкой,
         * без OriginalStart или с чужим UID: такое исключение не сопоставить, поэтому не трогаем ничего.
         */
        internal fun parseExceptionDetails(getXml: String, chunk: List<ExceptionCandidate>): List<EwsExceptionOccurrence>? {
            val messages = GET_ITEM_MESSAGE.findAll(getXml).toList()
            if (messages.size != chunk.size) return null
            return chunk.zip(messages).map { (candidate, m) ->
                if (m.groupValues[1] != "Success") return null
                val xml = m.groupValues[2]
                if (XmlValueExtractor.extractEws(xml, "UID") != candidate.uid) return null
                val originalStartMs = CalendarDateUtils.parseEwsDateTime(XmlValueExtractor.extractEws(xml, "OriginalStart"))
                    ?: return null
                val rawBody = BODY.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                EwsExceptionOccurrence(
                    uid = candidate.uid,
                    subject = candidate.subject,
                    body = CalendarBodyFormat.normalizeIncoming(XmlUtils.unescape(rawBody)),
                    startMs = candidate.startMs,
                    endMs = candidate.endMs,
                    location = candidate.location,
                    originalStartMs = originalStartMs
                )
            }
        }
    }
}

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
    private val ewsRequest: suspend (String, String, String) -> EasResult<String>,
    private val getEwsUrl: () -> String
) {

    private data class EwsExceptionOccurrence(
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
                append("<airsyncbase:Type>1</airsyncbase:Type>")
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
                append("<airsyncbase:Type>1</airsyncbase:Type>")
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
     * including modified ones (CalendarItemType = "Exception").
     * Match them to local masters by UID and update exceptions JSON.
     */
    suspend fun supplementRecurringExceptionsViaEws(
        localRecurringEvents: List<EasCalendarService.RecurringEventInfo>
    ): Map<String, String> {
        if (localRecurringEvents.isEmpty()) return emptyMap()

        val ewsUrl = try {
            getEwsUrl()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return emptyMap()
        }

        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val windowStartMs = cal.timeInMillis
        val startStr = String.format(
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
            "%04d-%02d-%02dT23:59:59Z",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )

        val findRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:FindItem Traversal="Shallow">
            <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURI FieldURI="item:Body"/>
                    <t:FieldURI FieldURI="calendar:Start"/>
                    <t:FieldURI FieldURI="calendar:End"/>
                    <t:FieldURI FieldURI="calendar:Location"/>
                    <t:FieldURI FieldURI="calendar:CalendarItemType"/>
                    <t:FieldURI FieldURI="calendar:UID"/>
                    <t:FieldURI FieldURI="calendar:OriginalStart"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:CalendarView StartDate="$startStr" EndDate="$endStr" MaxEntriesReturned="2000"/>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        val findResult = ewsRequest(ewsUrl, findRequest, "FindItem")
        if (findResult is EasResult.Error) {
            android.util.Log.w(
                "CalendarExceptionService",
                "supplementRecurringExceptionsViaEws: FindItem failed: ${findResult.message}"
            )
            return emptyMap()
        }
        val findXml = (findResult as EasResult.Success).data

        val localUids = localRecurringEvents.map { it.uid }.toSet()

        val exceptions = mutableListOf<EwsExceptionOccurrence>()
        val ciPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        for (m in ciPattern.findAll(findXml)) {
            val xml = m.groupValues[1]
            val itemType = XmlValueExtractor.extractEws(xml, "CalendarItemType") ?: continue
            if (itemType != "Exception") continue

            val uid = XmlValueExtractor.extractEws(xml, "UID") ?: continue
            if (uid !in localUids) continue

            val subject = XmlValueExtractor.extractEws(xml, "Subject") ?: ""
            val rawBody = "<(?:t:)?Body[^>]*>(.*?)</(?:t:)?Body>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.getOrNull(1)?.trim() ?: ""
            val body = CalendarDateUtils.removeDuplicateLines(XmlUtils.unescape(rawBody))
            val startMs = CalendarDateUtils.parseEwsDateTime(XmlValueExtractor.extractEws(xml, "Start")) ?: 0L
            val endMs = CalendarDateUtils.parseEwsDateTime(XmlValueExtractor.extractEws(xml, "End")) ?: 0L
            val location = XmlValueExtractor.extractEws(xml, "Location") ?: ""
            val originalStartMs = CalendarDateUtils.parseEwsDateTime(
                XmlValueExtractor.extractEws(xml, "OriginalStart")
            ) ?: startMs

            exceptions.add(
                EwsExceptionOccurrence(uid, subject, body, startMs, endMs, location, originalStartMs)
            )
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
                    val isOutsideWindow = exStartTime < windowStartMs || exStartTime > windowEndMs
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
}

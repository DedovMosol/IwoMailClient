package com.dedovmosol.iwomail.eas

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EWS Calendar attachment operations: create, delete, fetch metadata, supplement.
 *
 * Extracted from EasCalendarService (Phase 4 of H-12 decomposition).
 *
 * All attachment operations use EWS (not EAS) because:
 *  - EAS Sync Add/Change does not support attachment upload/delete
 *  - Exchange 2007 SP1 EAS Sync may omit <airsyncbase:Attachments> for calendar events
 *  - EWS provides CreateAttachment/DeleteAttachment/GetItem for full control
 *
 * EWS considerations:
 *  - CreateAttachment: RootItemChangeKey must be chained between sequential uploads
 *  - DeleteAttachment: one call per attachment (no batch)
 *  - FindItem+CalendarView: does NOT return Attachments collection (only HasAttachments flag)
 *  - GetItem with item:Attachments: returns full metadata
 *  - CalendarView + Restriction: INCOMPATIBLE in same FindItem (MS-EWS)
 *
 * Compatibility: Exchange 2007 SP1 / EWS
 */
class CalendarAttachmentService(
    private val ewsRequest: suspend (ewsUrl: String, soapBody: String, operation: String) -> EasResult<String>,
    private val parseEwsAttachments: (String) -> String,
    private val escapeXml: (String) -> String,
    private val parseEasDate: (String?) -> Long?,
    private val getEwsUrl: () -> String
) {

    private data class EwsAttachmentCandidate(
        val itemId: String,
        val subject: String,
        val startMs: Long,
        val uid: String,
        val hasAtt: Boolean
    )

    suspend fun deleteCalendarAttachments(attachmentIds: List<String>): EasResult<Boolean> {
        if (attachmentIds.isEmpty()) return EasResult.Success(true)

        val ewsUrl = getEwsUrl()
        var lastError: String? = null

        for (attId in attachmentIds) {
            if (attId.isBlank()) continue
            try {
                val request = buildDeleteAttachmentRequest(attId)
                val result = ewsRequest(ewsUrl, request, "DeleteAttachment")
                if (result is EasResult.Error) {
                    Log.w(TAG, "DeleteAttachment failed for ${attId.take(30)}: ${result.message}")
                    lastError = result.message
                } else {
                    val response = (result as EasResult.Success).data
                    if (response.contains("ResponseClass=\"Error\"")) {
                        val errMsg = EasPatterns.EWS_MESSAGE_TEXT.find(response)?.groupValues?.get(1) ?: "Unknown"
                        Log.w(TAG, "DeleteAttachment error for ${attId.take(30)}: $errMsg")
                        lastError = errMsg
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "DeleteAttachment exception for ${attId.take(30)}: ${e.message}")
                lastError = e.message
            }
        }

        return if (lastError != null && attachmentIds.size == 1) {
            EasResult.Error(lastError)
        } else {
            EasResult.Success(true)
        }
    }

    internal suspend fun attachFilesEws(
        ewsUrl: String,
        itemId: String,
        changeKey: String?,
        attachments: List<DraftAttachmentData>,
        exchangeVersion: String
    ): EasResult<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "attachFilesEws: ENTRY, itemId=${itemId.take(40)}, changeKey=${changeKey?.take(20)}, attachments=${attachments.size}, version=$exchangeVersion")
        if (attachments.isEmpty()) return@withContext EasResult.Success("")

        var currentChangeKey = changeKey
        val attachmentInfos = mutableListOf<String>()

        for ((index, att) in attachments.withIndex()) {
            val request = buildCreateAttachmentRequest(itemId, currentChangeKey, att, exchangeVersion)

            val responseResult = ewsRequest(ewsUrl, request, "CreateAttachment")
            if (responseResult is EasResult.Error) {
                return@withContext EasResult.Error(
                    "Вложение ${index + 1}/${attachments.size} (${att.name}): ${responseResult.message}"
                )
            }
            val response = (responseResult as EasResult.Success).data

            if (response.contains("ResponseClass=\"Error\"")) {
                val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(response)?.groupValues?.get(1)
                val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)
                val details = messageText ?: responseCode ?: "Unknown error"
                Log.e(TAG, "CreateAttachment FAILED [${index + 1}/${attachments.size}] ${att.name}: $details")
                return@withContext EasResult.Error("Вложение '${att.name}' не загружено: $details")
            }

            if (!response.contains("ResponseClass=\"Success\"")) {
                Log.e(TAG, "CreateAttachment NO SUCCESS [${index + 1}/${attachments.size}] ${att.name}")
                return@withContext EasResult.Error("Нет подтверждения загрузки вложения '${att.name}'")
            }

            val attachmentId = "<(?:t:)?AttachmentId[^>]*\\bId=\"([^\"]+)\"".toRegex()
                .find(response)?.groupValues?.get(1) ?: ""

            val newChangeKey = """RootItemChangeKey="([^"]+)"""".toRegex()
                .find(response)?.groupValues?.get(1)
                ?: "<(?:t:)?AttachmentId[^>]*\\bChangeKey=\"([^\"]+)\"".toRegex().find(response)?.groupValues?.get(1)
            if (newChangeKey != null) {
                currentChangeKey = newChangeKey
            }

            val escapedName = att.name.replace("\"", "\\\"")
            val escapedRef = attachmentId.replace("\"", "\\\"")
            attachmentInfos.add("""{"name":"$escapedName","fileReference":"$escapedRef","size":${att.data.size},"isInline":false}""")

            Log.d(TAG, "CreateAttachment OK [${index + 1}/${attachments.size}] ${att.name}, attId=${attachmentId.take(20)}...")
        }

        EasResult.Success("[${attachmentInfos.joinToString(",")}]")
    }

    suspend fun fetchCalendarAttachmentsEws(
        ewsUrl: String,
        itemIds: List<String>
    ): Map<String, String> {
        if (itemIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()

        for (batch in itemIds.chunked(50)) {
            val itemIdsXml = batch.joinToString("") { """<t:ItemId Id="${escapeXml(it)}"/>""" }
            val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <GetItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
            <ItemShape>
                <t:BaseShape>AllProperties</t:BaseShape>
                <t:AdditionalProperties>
                    <t:FieldURI FieldURI="item:Attachments"/>
                    <t:FieldURI FieldURI="item:HasAttachments"/>
                </t:AdditionalProperties>
            </ItemShape>
            <ItemIds>
                $itemIdsXml
            </ItemIds>
        </GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

            val responseResult = ewsRequest(ewsUrl, getItemRequest, "GetItem")
            if (responseResult is EasResult.Error) {
                Log.w(TAG, "fetchCalendarAttachmentsEws: batch error: ${responseResult.message}")
                continue
            }
            val responseXml = (responseResult as EasResult.Success).data
            Log.d(TAG, "fetchCalendarAttachmentsEws: GetItem response len=${responseXml.length}, first 600: ${responseXml.take(600)}")

            val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
                .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (match in itemPattern.findAll(responseXml)) {
                val itemXml = match.groupValues[1]
                val itemId = "<(?:t:)?ItemId\\b[^>]*\\bId=\"([^\"]+)\""
                    .toRegex(RegexOption.DOT_MATCHES_ALL).find(itemXml)?.groupValues?.getOrNull(1) ?: continue
                val attachmentsJson = parseEwsAttachments(itemXml)
                if (attachmentsJson.isNotBlank()) {
                    result[itemId] = attachmentsJson
                }
            }
        }

        Log.d(TAG, "fetchCalendarAttachmentsEws: fetched attachments for ${result.size}/${itemIds.size} events")
        return result
    }

    suspend fun supplementAttachmentsViaEws(
        events: List<EasCalendarEvent>
    ): List<EasCalendarEvent> {
        val noAttach = events.filter { it.attachments.isBlank() }
        if (noAttach.isEmpty()) return events

        val ewsUrl = try {
            getEwsUrl()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }

        val ewsItems = mutableListOf<EwsAttachmentCandidate>()
        val windows = buildAttachmentCalendarWindows(noAttach)
        if (windows.isEmpty()) {
            throw IllegalStateException("No valid calendar windows for attachment supplement")
        }
        if (windows.size > MAX_ATTACHMENT_SUPPLEMENT_WINDOWS) {
            throw IllegalStateException("Attachment supplement range is too large: ${windows.size} windows")
        }
        for ((startStr, endStr) in windows) {
            ewsItems.addAll(fetchAttachmentCandidatesViaCalendarView(ewsUrl, startStr, endStr))
        }

        var supplemented = 0
        val withAtt = ewsItems.distinctBy { it.itemId }.filter { it.hasAtt }
        val matchKey: (String, Long) -> String = { subj, start -> "${subj.trim().lowercase()}|$start" }
        val uidMatchKey: (String, Long) -> String = { uid, start -> "${uid.trim()}|$start" }
        val intermediateResult = if (withAtt.isEmpty()) {
            Log.d(TAG, "supplementAttachmentsViaEws: no EWS occurrences have attachments")
            events
        } else {
            Log.d(TAG, "supplementAttachmentsViaEws: ${withAtt.size} EWS events have attachments, fetching via GetItem")

            val attMap = fetchCalendarAttachmentsEws(ewsUrl, withAtt.map { it.itemId })
            if (attMap.isEmpty()) {
                throw IllegalStateException("GetItem returned no attachment metadata")
            }
            if (attMap.size < withAtt.size) {
                throw IllegalStateException("GetItem returned partial attachment metadata")
            }

            val keyCandidates = mutableMapOf<String, MutableList<String>>()
            val uidKeyCandidates = mutableMapOf<String, MutableList<String>>()
            for (item in withAtt) {
                val att = attMap[item.itemId] ?: continue
                keyCandidates.getOrPut(matchKey(item.subject, item.startMs)) { mutableListOf() }.add(att)
                if (item.uid.isNotBlank()) {
                    uidKeyCandidates.getOrPut(uidMatchKey(item.uid, item.startMs)) { mutableListOf() }.add(att)
                }
            }
            val keyToAtt = keyCandidates.mapNotNull { (key, values) ->
                values.singleOrNull()?.let { key to it }
            }.toMap()
            val uidKeyToAtt = uidKeyCandidates.mapNotNull { (key, values) ->
                values.singleOrNull()?.let { key to it }
            }.toMap()

            events.map { event ->
                if (event.attachments.isNotBlank()) return@map event
                val key = matchKey(event.subject, event.startTime)
                val uidKey = if (event.uid.isNotBlank()) uidMatchKey(event.uid, event.startTime) else ""
                val att = if (event.uid.isNotBlank()) {
                    uidKeyToAtt[uidKey]
                } else {
                    keyToAtt[key]
                }
                if (att != null) { supplemented++; event.copy(attachments = att) } else event
            }
        }

        val recurringNoAtt = intermediateResult.filter { it.isRecurring && it.attachments.isBlank() }
        if (recurringNoAtt.isNotEmpty()) {
            if (recurringNoAtt.any { it.uid.isBlank() }) {
                throw IllegalStateException("Recurring event UID missing; attachment metadata is not reliable")
            }
            val masterAtt = supplementRecurringMasterAttachments(ewsUrl, recurringNoAtt)
            if (masterAtt.isNotEmpty()) {
                val finalResult = intermediateResult.map { event ->
                    if (event.isRecurring && event.attachments.isBlank()) {
                        val att = masterAtt[event.uid]
                        if (att != null) { supplemented++; event.copy(attachments = att) } else event
                    } else event
                }
                Log.d(TAG, "supplementAttachmentsViaEws: supplemented $supplemented/${noAttach.size} events (incl. recurring masters)")
                return finalResult
            }
        }

        Log.d(TAG, "supplementAttachmentsViaEws: supplemented $supplemented/${noAttach.size} events with attachments")
        return intermediateResult
    }

    private fun buildAttachmentCalendarWindows(events: List<EasCalendarEvent>): List<Pair<String, String>> {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val cal = java.util.Calendar.getInstance(utc)
        val monthKeys = sortedSetOf<Int>()
        for (event in events) {
            if (event.startTime <= 0L) continue
            cal.timeInMillis = event.startTime
            monthKeys.add(cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH))
        }
        return monthKeys.map { key ->
            val year = key / 12
            val month = key % 12
            cal.clear()
            cal.set(year, month, 1, 0, 0, 0)
            val startMs = cal.timeInMillis
            cal.add(java.util.Calendar.MONTH, 1)
            val endMs = cal.timeInMillis
            CalendarDateUtils.formatEwsDate(startMs) to CalendarDateUtils.formatEwsDate(endMs)
        }
    }

    private suspend fun fetchAttachmentCandidatesViaCalendarView(
        ewsUrl: String,
        startStr: String,
        endStr: String
    ): List<EwsAttachmentCandidate> {
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
                    <t:FieldURI FieldURI="item:HasAttachments"/>
                    <t:FieldURI FieldURI="calendar:Start"/>
                    <t:FieldURI FieldURI="calendar:UID"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:CalendarView StartDate="$startStr" EndDate="$endStr" MaxEntriesReturned="1000"/>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        val findResult = ewsRequest(ewsUrl, findRequest, "FindItem")
        if (findResult is EasResult.Error) {
            Log.w(TAG, "supplementAttachmentsViaEws: FindItem failed: ${findResult.message}")
            throw IllegalStateException(findResult.message)
        }
        val findXml = (findResult as EasResult.Success).data
        if (findXml.contains("ResponseClass=\"Error\"")) {
            val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(findXml)?.groupValues?.get(1)
            val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(findXml)?.groupValues?.get(1)
            throw IllegalStateException(messageText ?: responseCode ?: "FindItem returned error")
        }
        if (!findXml.contains("IncludesLastItemInRange=\"true\"")) {
            throw IllegalStateException("FindItem returned partial calendar range")
        }

        val result = mutableListOf<EwsAttachmentCandidate>()
        val ciPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in ciPattern.findAll(findXml)) {
            val xml = m.groupValues[1]
            val id = XmlValueExtractor.extractAttribute(xml, "ItemId", "Id") ?: continue
            val subj = XmlValueExtractor.extractEws(xml, "Subject") ?: ""
            val startVal = XmlValueExtractor.extractEws(xml, "Start") ?: ""
            val startMs = CalendarDateUtils.parseEwsDateTime(startVal) ?: parseEasDate(startVal) ?: continue
            val uid = XmlValueExtractor.extractEws(xml, "UID") ?: ""
            val hasAtt = XmlValueExtractor.extractEws(xml, "HasAttachments")?.equals("true", ignoreCase = true) ?: false
            result.add(EwsAttachmentCandidate(id, subj, startMs, uid, hasAtt))
        }
        return result
    }

    suspend fun supplementRecurringMasterAttachments(
        ewsUrl: String,
        recurringEvents: List<EasCalendarEvent>
    ): Map<String, String> {
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
                    <t:FieldURI FieldURI="item:HasAttachments"/>
                    <t:FieldURI FieldURI="calendar:CalendarItemType"/>
                    <t:FieldURI FieldURI="calendar:UID"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:IndexedPageItemView MaxEntriesReturned="200" Offset="0" BasePoint="Beginning"/>
            <m:Restriction>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:HasAttachments"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="true"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        val findResult = ewsRequest(ewsUrl, findRequest, "FindItem")
        if (findResult is EasResult.Error) {
            throw IllegalStateException(findResult.message)
        }
        val findXml = (findResult as EasResult.Success).data
        if (findXml.contains("ResponseClass=\"Error\"")) {
            val messageText = EasPatterns.EWS_MESSAGE_TEXT.find(findXml)?.groupValues?.get(1)
            val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(findXml)?.groupValues?.get(1)
            throw IllegalStateException(messageText ?: responseCode ?: "Recurring master FindItem returned error")
        }
        if (!findXml.contains("IncludesLastItemInRange=\"true\"")) {
            throw IllegalStateException("Recurring master FindItem returned partial range")
        }

        val ciPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val recurringUids = recurringEvents.map { it.uid }.filter { it.isNotBlank() }.toSet()
        val masterItemIds = mutableListOf<String>()
        val masterIdToUid = mutableMapOf<String, String>()

        for (m in ciPattern.findAll(findXml)) {
            val xml = m.groupValues[1]
            val calType = XmlValueExtractor.extractEws(xml, "CalendarItemType") ?: ""
            if (calType != "RecurringMaster") continue
            val uid = XmlValueExtractor.extractEws(xml, "UID") ?: ""
            if (uid.isBlank() || uid !in recurringUids) continue
            val id = XmlValueExtractor.extractAttribute(xml, "ItemId", "Id") ?: continue
            masterItemIds.add(id)
            masterIdToUid[id] = uid
        }

        if (masterItemIds.isEmpty()) return emptyMap()

        Log.d(TAG, "supplementRecurringMasterAttachments: found ${masterItemIds.size} recurring masters with attachments")

        val attMap = fetchCalendarAttachmentsEws(ewsUrl, masterItemIds)
        if (attMap.isEmpty()) {
            throw IllegalStateException("Recurring master GetItem returned no attachment metadata")
        }
        if (attMap.size < masterItemIds.size) {
            throw IllegalStateException("Recurring master GetItem returned partial attachment metadata")
        }

        val result = mutableMapOf<String, String>()
        for ((itemId, attJson) in attMap) {
            val uid = masterIdToUid[itemId] ?: continue
            result[uid] = attJson
        }
        return result
    }

    private fun buildDeleteAttachmentRequest(attachmentId: String): String {
        val escapedId = escapeXml(attachmentId)
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:DeleteAttachment>
            <m:AttachmentIds>
                <t:AttachmentId Id="$escapedId"/>
            </m:AttachmentIds>
        </m:DeleteAttachment>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }

    private fun buildCreateAttachmentRequest(
        itemId: String,
        changeKey: String?,
        att: DraftAttachmentData,
        exchangeVersion: String
    ): String {
        val escapedItemId = escapeXml(itemId)
        val changeKeyAttr = changeKey?.takeIf { it.isNotBlank() }?.let { " ChangeKey=\"${escapeXml(it)}\"" } ?: ""
        val name = escapeXml(att.name)
        val contentType = escapeXml(att.mimeType)
        val content = Base64.encodeToString(att.data, Base64.NO_WRAP)

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"")
        sb.append(" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\"")
        sb.append(" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">")
        sb.append("<soap:Header>")
        sb.append("<t:RequestServerVersion Version=\"$exchangeVersion\"/>")
        sb.append("</soap:Header>")
        sb.append("<soap:Body>")
        sb.append("<m:CreateAttachment>")
        sb.append("<m:ParentItemId Id=\"$escapedItemId\"$changeKeyAttr/>")
        sb.append("<m:Attachments>")
        sb.append("<t:FileAttachment>")
        sb.append("<t:Name>$name</t:Name>")
        sb.append("<t:ContentType>$contentType</t:ContentType>")
        sb.append("<t:Content>$content</t:Content>")
        sb.append("</t:FileAttachment>")
        sb.append("</m:Attachments>")
        sb.append("</m:CreateAttachment>")
        sb.append("</soap:Body>")
        sb.append("</soap:Envelope>")
        return sb.toString()
    }

    companion object {
        private const val TAG = "CalendarAttachmentSvc"
        private const val MAX_ATTACHMENT_SUPPLEMENT_WINDOWS = 36
    }
}

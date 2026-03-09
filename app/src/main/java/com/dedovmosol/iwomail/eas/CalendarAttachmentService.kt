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
            return events
        }

        val minTime = events.minOf { it.startTime }
        val maxTime = events.maxOf { it.endTime }
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = minTime - 86400000L
        val startStr = String.format("%04d-%02d-%02dT00:00:00Z", cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        cal.timeInMillis = maxTime + 86400000L
        val endStr = String.format("%04d-%02d-%02dT23:59:59Z", cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))

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
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:CalendarView StartDate="$startStr" EndDate="$endStr" MaxEntriesReturned="500"/>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        val findResult = ewsRequest(ewsUrl, findRequest, "FindItem")
        if (findResult is EasResult.Error) {
            Log.w(TAG, "supplementAttachmentsViaEws: FindItem failed: ${findResult.message}")
            return events
        }
        val findXml = (findResult as EasResult.Success).data

        data class EwsItem(val itemId: String, val subject: String, val startMs: Long, val hasAtt: Boolean)
        val ewsItems = mutableListOf<EwsItem>()
        val ciPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in ciPattern.findAll(findXml)) {
            val xml = m.groupValues[1]
            val id = XmlValueExtractor.extractAttribute(xml, "ItemId", "Id") ?: continue
            val subj = XmlValueExtractor.extractEws(xml, "Subject") ?: ""
            val startVal = XmlValueExtractor.extractEws(xml, "Start") ?: ""
            val startMs = parseEasDate(startVal) ?: 0L
            val hasAtt = xml.contains("<t:HasAttachments>true</t:HasAttachments>")
                || xml.contains("<HasAttachments>true</HasAttachments>")
            ewsItems.add(EwsItem(id, subj, startMs, hasAtt))
        }

        val withAtt = ewsItems.filter { it.hasAtt }
        if (withAtt.isEmpty()) {
            Log.d(TAG, "supplementAttachmentsViaEws: no EWS events have attachments")
            return events
        }

        Log.d(TAG, "supplementAttachmentsViaEws: ${withAtt.size} EWS events have attachments, fetching via GetItem")

        val attMap = fetchCalendarAttachmentsEws(ewsUrl, withAtt.map { it.itemId })

        val matchKey: (String, Long) -> String = { subj, start -> "${subj.trim().lowercase()}|$start" }
        val keyToAtt = mutableMapOf<String, String>()
        val subjToAtt = mutableMapOf<String, String>()
        for (item in withAtt) {
            val att = attMap[item.itemId] ?: continue
            keyToAtt[matchKey(item.subject, item.startMs)] = att
            val subjKey = item.subject.trim().lowercase()
            if (subjKey !in subjToAtt) subjToAtt[subjKey] = att
        }

        var supplemented = 0
        val intermediateResult = events.map { event ->
            if (event.attachments.isNotBlank()) return@map event
            val key = matchKey(event.subject, event.startTime)
            val att = keyToAtt[key] ?: subjToAtt[event.subject.trim().lowercase()]
            if (att != null) { supplemented++; event.copy(attachments = att) } else event
        }

        val recurringNoAtt = intermediateResult.filter { it.isRecurring && it.attachments.isBlank() }
        if (recurringNoAtt.isNotEmpty()) {
            val masterAtt = try {
                supplementRecurringMasterAttachments(ewsUrl, recurringNoAtt)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "supplementRecurringMasterAttachments failed: ${e.message}")
                emptyMap()
            }
            if (masterAtt.isNotEmpty()) {
                val finalResult = intermediateResult.map { event ->
                    if (event.isRecurring && event.attachments.isBlank()) {
                        val att = masterAtt[event.subject.trim().lowercase()]
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
        if (findResult is EasResult.Error) return emptyMap()
        val findXml = (findResult as EasResult.Success).data

        val ciPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        val recurringSubjects = recurringEvents.map { it.subject.trim().lowercase() }.toSet()
        val masterItemIds = mutableListOf<String>()
        val masterIdToSubject = mutableMapOf<String, String>()

        for (m in ciPattern.findAll(findXml)) {
            val xml = m.groupValues[1]
            val calType = XmlValueExtractor.extractEws(xml, "CalendarItemType") ?: ""
            if (calType != "RecurringMaster") continue
            val subj = (XmlValueExtractor.extractEws(xml, "Subject") ?: "").trim().lowercase()
            if (subj !in recurringSubjects) continue
            val id = XmlValueExtractor.extractAttribute(xml, "ItemId", "Id") ?: continue
            masterItemIds.add(id)
            masterIdToSubject[id] = subj
        }

        if (masterItemIds.isEmpty()) return emptyMap()

        Log.d(TAG, "supplementRecurringMasterAttachments: found ${masterItemIds.size} recurring masters with attachments")

        val attMap = fetchCalendarAttachmentsEws(ewsUrl, masterItemIds)
        val result = mutableMapOf<String, String>()
        for ((itemId, attJson) in attMap) {
            val subj = masterIdToSubject[itemId] ?: continue
            result[subj] = attJson
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
    }
}

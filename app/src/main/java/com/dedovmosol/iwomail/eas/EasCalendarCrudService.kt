package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.*

/**
 * CRUD-операции календаря Exchange (EAS 12.1+ / EWS / Exchange 2007 SP1).
 *
 * Выделен из EasCalendarService (Phase 6 — H-12 decomposition) для соблюдения SRP.
 * Содержит все операции создания, обновления, удаления календарных событий
 * как через EAS (ActiveSync), так и через EWS (Exchange Web Services).
 *
 * Ключевые протокольные заметки:
 * - majorVersion >= 14 guards: EAS 14+ (Exchange 2010+) vs EAS 12.x (Exchange 2007 SP1)
 * - AppendToItemField для RequiredAttendees: SetItemField не поддерживается Exchange 2007 SP1
 * - RecurringMasterItemId: для удаления/вложений recurring серий нужен master ItemId
 * - DeclineItem для CRA prevention: предотвращает Calendar Repair Assistant resurrection
 */
class EasCalendarCrudService(
    private val deps: EasCalendarService.CalendarServiceDependencies,
    private val syncService: EasCalendarSyncService,
    private val exceptionService: CalendarExceptionService,
    private val attachmentService: CalendarAttachmentService,
    private val xmlParser: CalendarXmlParser,
    private val ewsRequest: suspend (String, String, String) -> EasResult<String>
) {

    // ======================== Public CRUD ========================

    /**
     * Создание события календаря
     */
    suspend fun createCalendarEvent(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String = "",
        body: String = "",
        allDayEvent: Boolean = false,
        reminder: Int = 15,
        busyStatus: Int = 2,
        sensitivity: Int = 0,
        attendees: List<String> = emptyList(),
        recurrenceType: Int = -1,
        attachments: List<DraftAttachmentData> = emptyList()
    ): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        var createdViaEws = false

        // AllDayEvent: Exchange требует Start/End = midnight UTC.
        // formatEwsDate/formatEasDate уже выводят в UTC — если timestamp = midnight UTC,
        // результат будет "...T00:00:00Z". Нон-midnight UTC сервер сдвигает к ближайшей
        // полуночи UTC, ломая длительность и дату.
        // CalendarScreen передаёт local 00:00 / 23:59. Берём дату из local, ставим 00:00 UTC.
        val actualStartTime: Long
        val actualEndTime: Long
        if (allDayEvent) {
            actualStartTime = CalendarDateUtils.normalizeAllDayUtcMidnight(startTime, addDay = false)
            actualEndTime = CalendarDateUtils.normalizeAllDayUtcMidnight(endTime, addDay = true)
        } else {
            actualStartTime = startTime
            actualEndTime = endTime
        }

        val result = if (attachments.isNotEmpty() || majorVersion < 14) {
            val ewsResult = createCalendarEventEws(
                subject,
                actualStartTime,
                actualEndTime,
                location,
                body,
                allDayEvent,
                reminder,
                busyStatus,
                sensitivity,
                attendees,
                recurrenceType
            )
            if (ewsResult is EasResult.Success) {
                createdViaEws = true
            }

            if (ewsResult is EasResult.Error && majorVersion < 14) {
                createdViaEws = false
                android.util.Log.w(
                    "EasCalendarCrudService",
                    "createCalendarEvent: EWS failed (${ewsResult.message}), fallback to EAS"
                )
                createCalendarEventEas(
                    subject,
                    actualStartTime,
                    actualEndTime,
                    location,
                    body,
                    allDayEvent,
                    reminder,
                    busyStatus,
                    sensitivity,
                    attendees,
                    recurrenceType
                )
            } else {
                ewsResult
            }
        } else {
            createCalendarEventEas(subject, actualStartTime, actualEndTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, recurrenceType)
        }
        
        // Загружаем вложения после успешного создания
        if (attachments.isNotEmpty() && result is EasResult.Success) {
            val rawId = result.data
            android.util.Log.d("EasCalendarCrudService", "createCalendarEvent: attachments=${attachments.size}, rawId=${rawId.take(60)}, createdViaEws=$createdViaEws")
            run {
                val ewsUrl = deps.getEwsUrl()
                // КРИТИЧНО: CreateAttachment требует именно EWS ItemId (не EAS ServerId).
                // Единственный случай когда rawId уже EWS ItemId — когда createCalendarEventEws
                // вернул "ItemId|ChangeKey" (rawId содержит '|').
                // Во всех остальных случаях (EAS ServerId, pending_sync_) — ищем через FindItem.
                val alreadyEwsId = !rawId.startsWith("pending_sync_") && 
                    (rawId.contains("|") || (createdViaEws && !rawId.contains(":")))
                val isRecurring = recurrenceType >= 0
                val ewsItemIdResult = if (alreadyEwsId && (!isRecurring || rawId.contains("|"))) {
                    // CreateItem для recurring возвращает master ItemId → безопасно использовать
                    android.util.Log.d("EasCalendarCrudService", "createCalendarEvent: using rawId directly as EWS ItemId")
                    EasResult.Success(rawId)
                } else if (isRecurring) {
                    // Exchange 2007 SP1: для recurring серии вложения хранятся на master.
                    // FindItem + CalendarView вернёт occurrence → ищем master БЕЗ CalendarView.
                    android.util.Log.d("EasCalendarCrudService", "createCalendarEvent: recurring, FindItem for master (rawId=${rawId.take(30)})")
                    kotlinx.coroutines.delay(2500)
                    val masterResult = findRecurringMasterItemId(subject)
                    if (masterResult is EasResult.Error) {
                        kotlinx.coroutines.delay(3500)
                        findRecurringMasterItemId(subject)
                    } else masterResult
                } else {
                    android.util.Log.d("EasCalendarCrudService", "createCalendarEvent: FindItem needed (rawId=${rawId.take(30)})")
                    findItemIdWithRetry(subject, actualStartTime, 2500, 3500, "create")
                }

                if (ewsItemIdResult is EasResult.Success) {
                    val resolvedRawId = ewsItemIdResult.data
                    val cleanItemId = if (resolvedRawId.contains("|")) resolvedRawId.substringBefore("|") else resolvedRawId
                    val changeKey = if (resolvedRawId.contains("|")) resolvedRawId.substringAfter("|") else null
                    android.util.Log.d("EasCalendarCrudService", "createCalendarEvent: calling attachFilesEws, itemId=${cleanItemId.take(40)}, hasChangeKey=${changeKey != null}, attachments=${attachments.size}")
                    val attachResult = attachmentService.attachFilesEws(ewsUrl, cleanItemId, changeKey, attachments, "Exchange2007_SP1")
                    if (attachResult is EasResult.Error) {
                        // Нефатально: само событие уже создано на сервере, не блокируем отправку.
                        android.util.Log.e("EasCalendarCrudService", "Событие создано, но вложения не загружены: ${attachResult.message}")
                        return result
                    }
                    // Возвращаем "cleanItemId\nattachmentsJson" — Repository разберёт
                    val attachJson = (attachResult as EasResult.Success).data
                    return EasResult.Success("$cleanItemId\n$attachJson")
                } else {
                    // Нефатально: событие создано, но ItemId для вложений не найден.
                    android.util.Log.e("EasCalendarCrudService", "Событие создано, но EWS ItemId для вложений не найден: ${(ewsItemIdResult as EasResult.Error).message}")
                    return result
                }
            }
        }
        
        return result
    }
    
    /**
     * Обновление события календаря
     */
    suspend fun updateCalendarEvent(
        serverId: String,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String = "",
        body: String = "",
        allDayEvent: Boolean = false,
        reminder: Int = 15,
        busyStatus: Int = 2,
        sensitivity: Int = 0,
        attendees: List<String> = emptyList(),
        oldSubject: String? = null,
        recurrenceType: Int = -1,
        attachments: List<DraftAttachmentData> = emptyList(),
        newAttendeesToAppend: List<String> = emptyList()
    ): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12

        val actualStartTime: Long
        val actualEndTime: Long
        if (allDayEvent) {
            actualStartTime = CalendarDateUtils.normalizeAllDayUtcMidnight(startTime, addDay = false)
            actualEndTime = CalendarDateUtils.normalizeAllDayUtcMidnight(endTime, addDay = true)
        } else {
            actualStartTime = startTime
            actualEndTime = endTime
        }

        val isEwsItemId = serverId.length > 50 && !serverId.contains(":")
        
        val result = if (majorVersion >= 14 && !isEwsItemId) {
            updateCalendarEventEas(serverId, subject, actualStartTime, actualEndTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, recurrenceType)
        } else {
            val ewsResult = updateCalendarEventEws(
                serverId,
                subject,
                actualStartTime,
                actualEndTime,
                location,
                body,
                allDayEvent,
                reminder,
                busyStatus,
                sensitivity,
                attendees,
                oldSubject,
                recurrenceType,
                newAttendeesToAppend
            )

            // КРИТИЧНО: если sync идёт через EAS legacy (короткий ServerId),
            // а EWS недоступен, пробуем EAS update как fallback.
            val isEasServerId = serverId.contains(":") && serverId.length < 20
            if (ewsResult is EasResult.Error && isEasServerId) {
                android.util.Log.w(
                    "EasCalendarCrudService",
                    "updateCalendarEvent: EWS failed (${ewsResult.message}), fallback to EAS"
                )
                updateCalendarEventEas(
                    serverId,
                    subject,
                    actualStartTime,
                    actualEndTime,
                    location,
                    body,
                    allDayEvent,
                    reminder,
                    busyStatus,
                    sensitivity,
                    attendees,
                    recurrenceType
                )
            } else {
                ewsResult
            }
        }
        
        if (result is EasResult.Error) return EasResult.Error(result.message)
        
        // Загружаем новые вложения после успешного обновления
        if (attachments.isNotEmpty()) {
            android.util.Log.d("EasCalendarCrudService", "updateCalendarEvent: uploading ${attachments.size} attachments, subject=$subject, recurrenceType=$recurrenceType")
            val ewsUrl = deps.getEwsUrl()
            // КРИТИЧНО для Exchange 2007 SP1:
            // Вложения recurring-серии хранятся на recurring master.
            // FindItem + CalendarView возвращает occurrence ItemId → CreateAttachment
            // прикрепляет к вхождению, а не к мастеру → вложение не видно в серии.
            // Для recurring событий ищем master ItemId БЕЗ CalendarView.
            val isRecurring = recurrenceType >= 0
            val ewsItemIdResult = if (isRecurring) {
                kotlinx.coroutines.delay(2000)
                val masterResult = findRecurringMasterItemId(subject)
                if (masterResult is EasResult.Error) {
                    android.util.Log.w("EasCalendarCrudService",
                        "updateCalendarEvent: master not found, retry in 3s: ${masterResult.message}")
                    kotlinx.coroutines.delay(3000)
                    findRecurringMasterItemId(subject)
                } else masterResult
            } else {
                findItemIdWithRetry(subject, actualStartTime, 2000, 3000, "update")
            }
            if (ewsItemIdResult is EasResult.Success) {
                val rawId = ewsItemIdResult.data
                val cleanItemId = if (rawId.contains("|")) rawId.substringBefore("|") else rawId
                val changeKey = if (rawId.contains("|")) rawId.substringAfter("|") else null
                android.util.Log.d("EasCalendarCrudService", "updateCalendarEvent: calling attachFilesEws, itemId=${cleanItemId.take(40)}, hasChangeKey=${changeKey != null}")
                val attachResult = attachmentService.attachFilesEws(ewsUrl, cleanItemId, changeKey, attachments, "Exchange2007_SP1")
                if (attachResult is EasResult.Error) {
                    // Нефатально: само событие уже обновлено на сервере.
                    android.util.Log.e("EasCalendarCrudService", "Событие обновлено, но вложения не загружены: ${attachResult.message}")
                    return EasResult.Success("")
                }
                return EasResult.Success((attachResult as EasResult.Success).data)
            } else {
                val errMsg = (ewsItemIdResult as EasResult.Error).message
                // Нефатально: само событие уже обновлено на сервере.
                android.util.Log.e("EasCalendarCrudService", "Не удалось найти EWS ItemId для загрузки вложений: $errMsg")
                return EasResult.Success("")
            }
        }
        
        return EasResult.Success("")
    }
    
    /**
     * Обновление одного вхождения (occurrence) повторяющегося события.
     *
     * MS-ASCAL §2.2.2.21: Exception — контейнер изменений для конкретного occurrence.
     * ExceptionStartTime (§2.2.2.23) — ОРИГИНАЛЬНОЕ время начала occurrence, идентифицирует его.
     * Compact DateTime формат (yyyyMMdd'T'HHmmss'Z').
     *
     * Отправляем Sync Change для мастер-события, включая ВСЕ существующие exceptions +
     * новое/изменённое. Это безопасно для всех версий EAS (2.5, 12.x, 14.x).
     */
    suspend fun updateSingleOccurrence(
        serverId: String,
        existingExceptionsJson: String,
        occurrenceOriginalStartTime: Long,
        masterSubject: String,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attachments: List<DraftAttachmentData> = emptyList(),
        removedAttachmentIds: List<String> = emptyList()
    ): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12

        // Exchange 2007 SP1 (EAS 12.x): EWS путь — FindItem(CalendarView) → UpdateItem.
        // CalendarView раскрывает повторяющиеся события, каждый occurrence получает свой ItemId.
        // UpdateItem на этот ItemId автоматически создаёт exception на сервере.
        // Body полностью поддерживается в EWS (в отличие от EAS 12.x).
        if (majorVersion < 14) {
            val existingExceptions = RecurrenceHelper.parseExceptions(existingExceptionsJson)
            val existingException = RecurrenceHelper.fuzzyMatchException(
                existingExceptions.filter { !it.deleted }, occurrenceOriginalStartTime
            )
            val searchSubject = existingException?.subject?.ifBlank { masterSubject } ?: masterSubject
            val searchStartTime = if (existingException != null && existingException.startTime > 0) {
                existingException.startTime
            } else {
                occurrenceOriginalStartTime
            }
            return updateSingleOccurrenceEws(
                searchSubject, searchStartTime,
                subject, startTime, endTime, location, body,
                allDayEvent, reminder, busyStatus, sensitivity,
                attachments, removedAttachmentIds
            )
        }

        // Exchange 2010+ (EAS 14+): EAS Exceptions (Body через airsyncbase:Body)
        val calendarFolderId = syncService.getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")

        val syncKeyResult = syncService.getAdvancedSyncKey(calendarFolderId)
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return syncKeyResult
        }

        val existingExceptions = RecurrenceHelper.parseExceptions(existingExceptionsJson)

        val xml = exceptionService.buildExceptionSyncXml(
            syncKey, calendarFolderId, serverId, majorVersion,
            existingExceptions, occurrenceOriginalStartTime,
            subject, startTime, endTime, location, body,
            allDayEvent, reminder, busyStatus, sensitivity
        )

        return deps.executeEasCommand("Sync", xml) { responseXml ->
            val collectionStatus = deps.extractValue(responseXml, "Status")
            if (collectionStatus != "1") {
                throw Exception("Collection Status=$collectionStatus")
            }
            if (responseXml.contains("<Responses>") && responseXml.contains("<Change>")) {
                val changeStatusMatch = CalendarXmlParser.CHANGE_STATUS_PATTERN.find(responseXml)
                if (changeStatusMatch != null) {
                    val changeStatus = changeStatusMatch.groupValues[1]
                    if (changeStatus != "1") {
                        throw Exception("Change Status=$changeStatus")
                    }
                }
            }
            true
        }.let { result ->
            when (result) {
                is EasResult.Success -> EasResult.Success("")
                is EasResult.Error -> EasResult.Error(result.message)
            }
        }
    }

    /**
     * EWS-путь для изменения одного occurrence на Exchange 2007 SP1.
     *
     * 1) FindItem + CalendarView (раскрывает повторения) → находим ItemId+ChangeKey occurrence
     * 2) UpdateItem с SetItemField для каждого поля (Subject, Body, Start, End, Location, ...)
     *
     * CalendarView возвращает каждый occurrence с собственным ItemId.
     * UpdateItem на этот ItemId автоматически создаёт exception.
     */
    private suspend fun updateSingleOccurrenceEws(
        searchSubject: String,
        searchStartTime: Long,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attachments: List<DraftAttachmentData>,
        removedAttachmentIds: List<String>
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")

                val oneDayMs = 24L * 60 * 60 * 1000
                val viewStart = sdf.format(java.util.Date(searchStartTime - oneDayMs))
                val viewEnd = sdf.format(java.util.Date(searchStartTime + oneDayMs))

                val findXml = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header><t:RequestServerVersion Version="Exchange2007_SP1"/></soap:Header>
    <soap:Body>
        <m:FindItem Traversal="Shallow">
            <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURI FieldURI="calendar:Start"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:CalendarView MaxEntriesReturned="100" StartDate="$viewStart" EndDate="$viewEnd"/>
            <m:ParentFolderIds><t:DistinguishedFolderId Id="calendar"/></m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

                val findResult = ewsRequest(ewsUrl, findXml, "FindItem")
                if (findResult is EasResult.Error) return@withContext findResult
                val findResponse = (findResult as EasResult.Success).data

                val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val idPattern = "<(?:t:)?ItemId[^>]*\\bId=\"([^\"]+)\"[^>]*\\bChangeKey=\"([^\"]+)\"".toRegex()
                val subjectPat = "<(?:t:)?Subject>(.*?)</(?:t:)?Subject>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val startPat = "<(?:t:)?Start>(.*?)</(?:t:)?Start>".toRegex(RegexOption.DOT_MATCHES_ALL)

                var bestItemId: String? = null
                var bestChangeKey: String? = null
                var bestTimeDiff = Long.MAX_VALUE

                for (itemMatch in itemPattern.findAll(findResponse)) {
                    val itemXml = itemMatch.groupValues[1]
                    val idMatch = idPattern.find(itemXml) ?: continue
                    val itemSubject = subjectPat.find(itemXml)?.groupValues?.get(1) ?: ""
                    val itemStartStr = startPat.find(itemXml)?.groupValues?.get(1)
                    val itemStartMs = CalendarDateUtils.parseEwsDateTime(itemStartStr) ?: continue

                    val timeDiff = kotlin.math.abs(itemStartMs - searchStartTime)
                    if (timeDiff > oneDayMs) continue

                    if (itemSubject.equals(searchSubject, ignoreCase = true) && timeDiff < bestTimeDiff) {
                        bestItemId = idMatch.groupValues[1]
                        bestChangeKey = idMatch.groupValues[2]
                        bestTimeDiff = timeDiff
                    }
                }

                if (bestItemId == null) {
                    return@withContext EasResult.Error("Occurrence not found via EWS (subject=$searchSubject)")
                }

                val escapedSubject = deps.escapeXml(subject)
                val escapedLocation = deps.escapeXml(location)
                val escapedBody = deps.escapeXml(body)
                val startStr = if (allDayEvent) CalendarDateUtils.formatEwsAllDayDate(startTime) else CalendarDateUtils.formatEwsDate(startTime)
                val endStr = if (allDayEvent) CalendarDateUtils.formatEwsAllDayDate(endTime) else CalendarDateUtils.formatEwsDate(endTime)
                val ewsBusyStatus = CalendarDateUtils.mapBusyStatusToEws(busyStatus)

                val updateXml = buildString {
                    append("""<?xml version="1.0" encoding="utf-8"?>""")
                    append("""<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
                    append("""xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """)
                    append("""xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""")
                    append("""<soap:Header><t:RequestServerVersion Version="Exchange2007_SP1"/></soap:Header>""")
                    append("<soap:Body>")
                    append("""<m:UpdateItem ConflictResolution="AlwaysOverwrite" SendMeetingInvitationsOrCancellations="SendToNone">""")
                    append("<m:ItemChanges><t:ItemChange>")
                    append("""<t:ItemId Id="${deps.escapeXml(bestItemId)}" ChangeKey="${deps.escapeXml(bestChangeKey ?: "")}"/>""")
                    append("<t:Updates>")

                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="item:Subject"/>""")
                    append("<t:CalendarItem><t:Subject>$escapedSubject</t:Subject></t:CalendarItem>")
                    append("</t:SetItemField>")

                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:Start"/>""")
                    append("<t:CalendarItem><t:Start>$startStr</t:Start></t:CalendarItem>")
                    append("</t:SetItemField>")

                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:End"/>""")
                    append("<t:CalendarItem><t:End>$endStr</t:End></t:CalendarItem>")
                    append("</t:SetItemField>")

                    if (escapedLocation.isNotBlank()) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="calendar:Location"/>""")
                        append("<t:CalendarItem><t:Location>$escapedLocation</t:Location></t:CalendarItem>")
                        append("</t:SetItemField>")
                    } else {
                        append("""<t:DeleteItemField><t:FieldURI FieldURI="calendar:Location"/></t:DeleteItemField>""")
                    }

                    if (escapedBody.isNotBlank()) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:Body"/>""")
                        append("""<t:CalendarItem><t:Body BodyType="Text">$escapedBody</t:Body></t:CalendarItem>""")
                        append("</t:SetItemField>")
                    } else {
                        append("""<t:DeleteItemField><t:FieldURI FieldURI="item:Body"/></t:DeleteItemField>""")
                    }

                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:IsAllDayEvent"/>""")
                    append("<t:CalendarItem><t:IsAllDayEvent>$allDayEvent</t:IsAllDayEvent></t:CalendarItem>")
                    append("</t:SetItemField>")

                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:LegacyFreeBusyStatus"/>""")
                    append("<t:CalendarItem><t:LegacyFreeBusyStatus>$ewsBusyStatus</t:LegacyFreeBusyStatus></t:CalendarItem>")
                    append("</t:SetItemField>")

                    if (reminder > 0) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:ReminderIsSet"/>""")
                        append("<t:CalendarItem><t:ReminderIsSet>true</t:ReminderIsSet></t:CalendarItem>")
                        append("</t:SetItemField>")
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:ReminderMinutesBeforeStart"/>""")
                        append("<t:CalendarItem><t:ReminderMinutesBeforeStart>$reminder</t:ReminderMinutesBeforeStart></t:CalendarItem>")
                        append("</t:SetItemField>")
                    } else {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:ReminderIsSet"/>""")
                        append("<t:CalendarItem><t:ReminderIsSet>false</t:ReminderIsSet></t:CalendarItem>")
                        append("</t:SetItemField>")
                    }

                    val ewsSensitivity = when (sensitivity) {
                        1 -> "Personal"; 2 -> "Private"; 3 -> "Confidential"; else -> "Normal"
                    }
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="item:Sensitivity"/>""")
                    append("<t:CalendarItem><t:Sensitivity>$ewsSensitivity</t:Sensitivity></t:CalendarItem>")
                    append("</t:SetItemField>")

                    append("</t:Updates>")
                    append("</t:ItemChange></m:ItemChanges>")
                    append("</m:UpdateItem></soap:Body></soap:Envelope>")
                }

                val updateResult = ewsRequest(ewsUrl, updateXml, "UpdateItem")
                if (updateResult is EasResult.Error) return@withContext updateResult
                val responseXml = (updateResult as EasResult.Success).data

                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val responseCode = CalendarXmlParser.EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()

                if (hasSuccess && (responseCode == "NoError" || responseCode == null)) {
                    // IMPORTANT: attachments on single occurrence must target occurrence ItemId.
                    // Do NOT resolve recurring master here, otherwise changes leak to whole series.
                    val changedAttachments = removedAttachmentIds.isNotEmpty() || attachments.isNotEmpty()
                    val occurrenceAttachmentMap = if (changedAttachments) {
                        attachmentService.fetchCalendarAttachmentsEws(ewsUrl, listOf(bestItemId))
                    } else {
                        emptyMap()
                    }
                    val currentOccurrenceAttachmentsJson = occurrenceAttachmentMap[bestItemId] ?: ""

                    if (removedAttachmentIds.isNotEmpty()) {
                        // Удаляем только те AttachmentId, которые реально принадлежат этому occurrence.
                        // Это защищает серию от случайного удаления master-вложений.
                        val idsOnOccurrence = try {
                            val arr = JSONArray(currentOccurrenceAttachmentsJson)
                            (0 until arr.length()).mapNotNull { idx ->
                                arr.getJSONObject(idx).optString("fileReference", "").takeIf { it.isNotBlank() }
                            }.toSet()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            emptySet()
                        }
                        val safeIdsToDelete = removedAttachmentIds.filter { it in idsOnOccurrence }
                        if (safeIdsToDelete.isNotEmpty()) {
                            val deleteResult = attachmentService.deleteCalendarAttachments(safeIdsToDelete)
                            if (deleteResult is EasResult.Error) {
                                android.util.Log.w(
                                    "EasCalendarCrudService",
                                    "updateSingleOccurrenceEws: failed to delete some occurrence attachments: ${deleteResult.message}"
                                )
                            }
                        }
                    }

                    var currentChangeKey = XmlValueExtractor.extractAttribute(responseXml, "ItemId", "ChangeKey")
                    if (currentChangeKey.isNullOrBlank()) {
                        currentChangeKey = bestChangeKey
                    }

                    if (attachments.isNotEmpty()) {
                        val attachResult = attachmentService.attachFilesEws(
                            ewsUrl = ewsUrl,
                            itemId = bestItemId,
                            changeKey = currentChangeKey,
                            attachments = attachments,
                            exchangeVersion = "Exchange2007_SP1"
                        )
                        if (attachResult is EasResult.Error) {
                            return@withContext EasResult.Error(attachResult.message)
                        }
                    }

                    if (changedAttachments) {
                        val currentAttachments = attachmentService.fetchCalendarAttachmentsEws(ewsUrl, listOf(bestItemId))
                        EasResult.Success(currentAttachments[bestItemId] ?: "")
                    } else {
                        EasResult.Success("")
                    }
                } else {
                    EasResult.Error("EWS UpdateItem occurrence error: $responseCode")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "EWS occurrence update failed")
            }
        }
    }

    /**
     * Удаление события календаря.
     * КРИТИЧНО: Для meetings (встреч с участниками) простой DeleteItem с SendToNone
     * вызывает "воскрешение" — Calendar Repair Assistant (CRA) Exchange
     * обнаруживает пропавшую встречу и воссоздаёт её.
     * Правильный подход по MS docs:
     *   - Участник (attendee): DeclineItem → уведомляет организатора, CRA не воссоздаст
     *   - Организатор: DeleteItem + SendToAllAndSaveCopy → отправляет отмену участникам
     *   - Обычное событие (не meeting): DeleteItem + HardDelete + SendToNone
     */
    suspend fun deleteCalendarEvent(
        serverId: String,
        isMeeting: Boolean = false,
        isOrganizer: Boolean = false,
        isRecurringSeries: Boolean = false
    ): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        // КРИТИЧНО: Определяем формат serverId.
        // EWS ItemId = длинный base64 (>50 символов, НЕ содержит ":")
        // EAS ServerId = короткий, содержит ":" (напр. "5:23")
        // Если serverId — EWS ItemId, ВСЕГДА используем EWS DeleteItem,
        // т.к. EAS Sync Delete НЕ понимает EWS ItemId → удаление фейлит → воскрешение!
        val isEwsItemId = (serverId.length > 50 && !serverId.contains(":")) || serverId.contains("|")
        val isEasServerId = serverId.contains(":") && serverId.length < 20
        
        return if (isEwsItemId) {
            val ewsItemId = if (serverId.contains("|")) serverId.substringBefore("|") else serverId
            android.util.Log.d("EasCalendarCrudService", "deleteCalendarEvent: using EWS path for EWS ItemId (len=${serverId.length}), isRecurringSeries=$isRecurringSeries")
            if (isMeeting && !isOrganizer) {
                declineCalendarEventEws(ewsItemId, isRecurringSeries = isRecurringSeries)
            } else if (isMeeting && isOrganizer) {
                deleteCalendarEventEws(ewsItemId, sendCancellations = "SendToAllAndSaveCopy", isRecurringSeries = isRecurringSeries)
            } else {
                deleteCalendarEventEws(ewsItemId, isRecurringSeries = isRecurringSeries)
            }
        } else if (isEasServerId) {
            val calendarFolderId = syncService.getCalendarFolderId()
                ?: return EasResult.Error("Папка календаря не найдена")
            if (isMeeting && !isOrganizer) {
                meetingResponseEas(serverId, calendarFolderId, userResponse = 3)
            }
            deleteCalendarEventEas(serverId, calendarFolderId)
        } else {
            android.util.Log.w("EasCalendarCrudService", "deleteCalendarEvent: unknown serverId format (len=${serverId.length}), trying EWS")
            deleteCalendarEventEws(serverId, isRecurringSeries = isRecurringSeries)
        }
    }

    /**
     * Batch-удаление событий календаря. Аналог deleteEmailsBatchViaEWS из EasEmailService.
     *
     * Проблема: При последовательном удалении через отдельные DeleteItem+NTLM
     * после удаления первого элемента сессия/индексы могут измениться,
     * и 2-й/3-й запрос проваливается (ErrorItemNotFound / NTLM timeout).
     *
     * Решение: Все EWS ItemIds удаляются ОДНИМ запросом DeleteItem.
     * Decline-события (участник встречи) обрабатываются отдельно.
     * EAS-события (короткие ServerIds) проходят по старому пути.
     *
     * @return количество успешно удалённых
     */
    suspend fun deleteCalendarEventsBatch(
        requests: List<EasCalendarService.DeleteRequest>
    ): EasResult<Int> {
        if (requests.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()

        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()

                data class EwsItem(val itemId: String, val sendCancellations: String, val isRecurringSeries: Boolean)

                val ewsRegular = mutableListOf<EwsItem>()
                val ewsDecline = mutableListOf<String>()
                val easEvents = mutableListOf<EasCalendarService.DeleteRequest>()

                for (req in requests) {
                    val isEwsItemId = (req.serverId.length > 50 && !req.serverId.contains(":")) || req.serverId.contains("|")
                    val isEasServerId = req.serverId.contains(":") && req.serverId.length < 20
                    val ewsItemId = if (req.serverId.contains("|")) req.serverId.substringBefore("|") else req.serverId

                    when {
                        isEwsItemId && req.isMeeting && !req.isOrganizer ->
                            ewsDecline.add(ewsItemId)
                        isEwsItemId && req.isMeeting && req.isOrganizer ->
                            ewsRegular.add(EwsItem(ewsItemId, "SendToAllAndSaveCopy", req.isRecurringSeries))
                        isEwsItemId ->
                            ewsRegular.add(EwsItem(ewsItemId, "SendToNone", req.isRecurringSeries))
                        isEasServerId ->
                            easEvents.add(req)
                        else ->
                            ewsRegular.add(EwsItem(req.serverId, "SendToNone", req.isRecurringSeries))
                    }
                }

                var deleted = 0

                // 1. Batch EWS: группируем по (sendCancellations, isRecurringSeries)
                val grouped = ewsRegular.groupBy({ it.sendCancellations to it.isRecurringSeries }, { it.itemId })
                for ((key, itemIds) in grouped) {
                    val (sendCancel, isRecurring) = key
                    val batchResult = deleteCalendarEventsBatchEws(itemIds, sendCancel, isRecurring)
                    if (batchResult is EasResult.Success) {
                        deleted += batchResult.data
                    } else {
                        android.util.Log.w("EasCalendarCrudService",
                            "deleteCalendarEventsBatch: batch EWS ($sendCancel, recurring=$isRecurring) failed for ${itemIds.size} items: ${(batchResult as? EasResult.Error)?.message}")
                    }
                }

                // 2. Decline: DeclineItem по одному, затем batch HardDelete
                if (ewsDecline.isNotEmpty()) {
                    val declinedItemIds = mutableListOf<String>()
                    for (itemId in ewsDecline) {
                        val declineBody = """
                            <m:CreateItem MessageDisposition="SendAndSaveCopy">
                                <m:Items>
                                    <t:DeclineItem>
                                        <t:ReferenceItemId Id="${deps.escapeXml(itemId)}"/>
                                    </t:DeclineItem>
                                </m:Items>
                            </m:CreateItem>
                        """.trimIndent()
                        val req = EasXmlTemplates.ewsSoapRequest(declineBody)
                        runCatching { ewsRequest(ewsUrl, req, "CreateItem") }
                        declinedItemIds.add(itemId)
                    }
                    if (declinedItemIds.isNotEmpty()) {
                        val hardDeleteResult = deleteCalendarEventsBatchEws(declinedItemIds, "SendToNone")
                        if (hardDeleteResult is EasResult.Success) {
                            deleted += hardDeleteResult.data
                        }
                    }
                }

                // 3. EAS: по одному (EAS Sync Delete не поддерживает batch)
                for (req in easEvents) {
                    val result = deleteCalendarEvent(req.serverId, req.isMeeting, req.isOrganizer, req.isRecurringSeries)
                    if (result is EasResult.Success && result.data) deleted++
                }

                android.util.Log.d("EasCalendarCrudService",
                    "deleteCalendarEventsBatch: deleted $deleted/${requests.size}")
                EasResult.Success(deleted)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("EasCalendarCrudService",
                    "deleteCalendarEventsBatch: exception: ${e.message}")
                EasResult.Error("Batch delete error: ${e.message}")
            }
        }
    }

    /**
     * Удаление одного вхождения повторяющегося события через EWS.
     * Находит occurrence по CalendarView, затем DeleteItem.
     */
    suspend fun deleteSingleOccurrenceEws(
        searchSubject: String,
        occurrenceStartTime: Long,
        isMeeting: Boolean = false,
        isOrganizer: Boolean = false
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val oneDayMs = 24L * 60 * 60 * 1000
                val viewStart = sdf.format(java.util.Date(occurrenceStartTime - oneDayMs))
                val viewEnd = sdf.format(java.util.Date(occurrenceStartTime + oneDayMs))

                val findXml = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header><t:RequestServerVersion Version="Exchange2007_SP1"/></soap:Header>
    <soap:Body>
        <m:FindItem Traversal="Shallow">
            <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURI FieldURI="calendar:Start"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:CalendarView MaxEntriesReturned="100" StartDate="$viewStart" EndDate="$viewEnd"/>
            <m:ParentFolderIds><t:DistinguishedFolderId Id="calendar"/></m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

                val findResult = ewsRequest(ewsUrl, findXml, "FindItem")
                if (findResult is EasResult.Error) return@withContext findResult
                val findResponse = (findResult as EasResult.Success).data

                val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val idPattern = "<(?:t:)?ItemId[^>]*\\bId=\"([^\"]+)\"".toRegex()
                val subjectPat = "<(?:t:)?Subject>(.*?)</(?:t:)?Subject>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val startPat = "<(?:t:)?Start>(.*?)</(?:t:)?Start>".toRegex(RegexOption.DOT_MATCHES_ALL)

                var bestItemId: String? = null
                var bestTimeDiff = Long.MAX_VALUE
                val fallbackCandidates = mutableListOf<Pair<String, Long>>()

                for (itemMatch in itemPattern.findAll(findResponse)) {
                    val itemXml = itemMatch.groupValues[1]
                    val id = idPattern.find(itemXml)?.groupValues?.get(1) ?: continue
                    val subj = subjectPat.find(itemXml)?.groupValues?.get(1) ?: ""
                    val startMs = CalendarDateUtils.parseEwsDateTime(startPat.find(itemXml)?.groupValues?.get(1)) ?: continue
                    val diff = kotlin.math.abs(startMs - occurrenceStartTime)
                    if (diff > oneDayMs) continue
                    if (subj.equals(searchSubject, ignoreCase = true) && diff < bestTimeDiff) {
                        bestItemId = id
                        bestTimeDiff = diff
                    }
                    val fiveMinMs = 5L * 60 * 1000
                    if (diff <= fiveMinMs && subj.equals(searchSubject, ignoreCase = true)) {
                        fallbackCandidates.add(id to diff)
                    }
                }

                val fallbackItemId = if (bestItemId == null && fallbackCandidates.size == 1) {
                    fallbackCandidates[0].first
                } else null
                val targetId = bestItemId ?: fallbackItemId
                if (targetId == null) {
                    return@withContext EasResult.Error("Occurrence not found via EWS CalendarView")
                }

                val cancellations = if (isMeeting && isOrganizer) "SendToAllAndSaveCopy" else "SendToNone"
                deleteCalendarEventEws(targetId, sendCancellations = cancellations, deleteType = "MoveToDeletedItems")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Error deleting occurrence")
            }
        }
    }

    // ======================== EAS CRUD ========================

    private suspend fun createCalendarEventEas(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attendees: List<String>,
        recurrenceType: Int = -1
    ): EasResult<String> {
        val calendarFolderId = syncService.getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")
        
        val syncKeyResult = syncService.getAdvancedSyncKey(calendarFolderId)
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return syncKeyResult
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        val clientId = UUID.randomUUID().toString().replace("-", "").take(32)
        val startTimeStr = CalendarDateUtils.formatEasDate(startTime)
        val endTimeStr = CalendarDateUtils.formatEasDate(endTime)
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedLocation = deps.escapeXml(location)
        val escapedBody = deps.escapeXml(body)
        
        val createXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            if (majorVersion >= 14) {
                append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">""")
            } else {
                append("""<Sync xmlns="AirSync" xmlns:calendar="Calendar">""")
            }
            append("<Collections><Collection>")
            append("<SyncKey>${deps.escapeXml(syncKey)}</SyncKey>")
            append("<CollectionId>${deps.escapeXml(calendarFolderId)}</CollectionId>")
            append("<Commands><Add>")
            append("<ClientId>${deps.escapeXml(clientId)}</ClientId>")
            append("<ApplicationData>")
            append("<calendar:Subject>$escapedSubject</calendar:Subject>")
            append("<calendar:StartTime>$startTimeStr</calendar:StartTime>")
            append("<calendar:EndTime>$endTimeStr</calendar:EndTime>")
            append("<calendar:Location>$escapedLocation</calendar:Location>")
            
            if (majorVersion >= 14) {
                append("<airsyncbase:Body>")
                append("<airsyncbase:Type>1</airsyncbase:Type>")
                append("<airsyncbase:Data>$escapedBody</airsyncbase:Data>")
                append("</airsyncbase:Body>")
            }
            
            val tzBlob = if (allDayEvent) android.util.Base64.encodeToString(ByteArray(172), android.util.Base64.NO_WRAP) else CalendarDateUtils.buildDeviceTimezoneBlob()
            append("<calendar:Timezone>$tzBlob</calendar:Timezone>")
            append("<calendar:AllDayEvent>${if (allDayEvent) "1" else "0"}</calendar:AllDayEvent>")
            append("<calendar:Reminder>$reminder</calendar:Reminder>")
            append("<calendar:BusyStatus>$busyStatus</calendar:BusyStatus>")
            append("<calendar:Sensitivity>$sensitivity</calendar:Sensitivity>")
            
            if (majorVersion >= 14) {
                val meetingStatus = if (attendees.isNotEmpty()) 1 else 0
                append("<calendar:MeetingStatus>$meetingStatus</calendar:MeetingStatus>")
                
                if (attendees.isNotEmpty()) {
                    append("<calendar:Attendees>")
                    for (email in attendees) {
                        val escapedEmail = deps.escapeXml(email.trim())
                        append("<calendar:Attendee>")
                        append("<calendar:Email>$escapedEmail</calendar:Email>")
                        append("<calendar:AttendeeType>1</calendar:AttendeeType>")
                        append("<calendar:AttendeeStatus>0</calendar:AttendeeStatus>")
                        append("</calendar:Attendee>")
                    }
                    append("</calendar:Attendees>")
                }
            }
            
            val recurrenceXml = CalendarRecurrenceBuilder.buildEasRecurrenceXml(recurrenceType, startTime)
            if (recurrenceXml.isNotBlank()) append(recurrenceXml)
            
            append("</ApplicationData>")
            append("</Add></Commands>")
            append("</Collection></Collections>")
            append("</Sync>")
        }
        
        return deps.executeEasCommand("Sync", createXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status == "1") {
                // КРИТИЧНО: Извлекаем ServerId ИМЕННО из секции Responses/Add,
                // а не первый попавшийся (который может быть из Commands других событий).
                // Ищем блок <Responses>...<Add>...<ClientId>OUR_ID</ClientId>...<ServerId>X</ServerId>...
                val responsesBlock = CalendarXmlParser.RESPONSES_PATTERN.find(responseXml)?.groupValues?.get(1)
                val serverIdFromResponses = if (responsesBlock != null) {
                    CalendarXmlParser.ADD_PATTERN.findAll(responsesBlock)
                        .firstOrNull { it.groupValues[1].contains("<ClientId>$clientId</ClientId>") }
                        ?.let { deps.extractValue(it.groupValues[1], "ServerId") }
                        ?: deps.extractValue(responsesBlock, "ServerId") // fallback: первый ServerId из Responses
                } else {
                    deps.extractValue(responseXml, "ServerId") // fallback: старое поведение
                }
                serverIdFromResponses ?: clientId
            } else {
                throw Exception("Ошибка создания события: Status=$status")
            }
        }
    }
    
    private suspend fun updateCalendarEventEas(
        serverId: String,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attendees: List<String>,
        recurrenceType: Int = -1
    ): EasResult<Boolean> {
        val calendarFolderId = syncService.getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")
        
        val syncKeyResult = syncService.getAdvancedSyncKey(calendarFolderId)
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return syncKeyResult
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        val startTimeStr = CalendarDateUtils.formatEasDate(startTime)
        val endTimeStr = CalendarDateUtils.formatEasDate(endTime)
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedLocation = deps.escapeXml(location)
        val escapedBody = deps.escapeXml(body)
        
        // КРИТИЧНО: Exchange 2007 (EAS 12.x) поддерживает в Calendar Change только:
        // Subject, StartTime, EndTime, Location, AllDayEvent, Reminder, BusyStatus, Sensitivity
        // Body, MeetingStatus, Attendees - НЕ поддерживаются (вызывают Status=6)
        
        val updateXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            if (majorVersion >= 14) {
                append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">""")
            } else {
                append("""<Sync xmlns="AirSync" xmlns:calendar="Calendar">""")
            }
            append("<Collections><Collection>")
            append("<SyncKey>${deps.escapeXml(syncKey)}</SyncKey>")
            append("<CollectionId>${deps.escapeXml(calendarFolderId)}</CollectionId>")
            append("<Commands><Change>")
            append("<ServerId>${deps.escapeXml(serverId)}</ServerId>")
            append("<ApplicationData>")
            append("<calendar:Subject>$escapedSubject</calendar:Subject>")
            append("<calendar:StartTime>$startTimeStr</calendar:StartTime>")
            append("<calendar:EndTime>$endTimeStr</calendar:EndTime>")
            append("<calendar:Location>$escapedLocation</calendar:Location>")
            
            // Body только для EAS 14+ (Exchange 2010+)
            if (majorVersion >= 14) {
                append("<airsyncbase:Body>")
                append("<airsyncbase:Type>1</airsyncbase:Type>")
                append("<airsyncbase:Data>$escapedBody</airsyncbase:Data>")
                append("</airsyncbase:Body>")
            }
            
            if (majorVersion >= 14) {
                val tzBlob = if (allDayEvent) android.util.Base64.encodeToString(ByteArray(172), android.util.Base64.NO_WRAP) else CalendarDateUtils.buildDeviceTimezoneBlob()
                append("<calendar:Timezone>$tzBlob</calendar:Timezone>")
            }
            append("<calendar:AllDayEvent>${if (allDayEvent) "1" else "0"}</calendar:AllDayEvent>")
            append("<calendar:Reminder>$reminder</calendar:Reminder>")
            append("<calendar:BusyStatus>$busyStatus</calendar:BusyStatus>")
            append("<calendar:Sensitivity>$sensitivity</calendar:Sensitivity>")
            
            // MeetingStatus и Attendees только для EAS 14+ (Exchange 2010+)
            if (majorVersion >= 14) {
                val meetingStatus = if (attendees.isNotEmpty()) 1 else 0
                append("<calendar:MeetingStatus>$meetingStatus</calendar:MeetingStatus>")
                
                if (attendees.isNotEmpty()) {
                    append("<calendar:Attendees>")
                    for (email in attendees) {
                        val escapedEmail = deps.escapeXml(email.trim())
                        append("<calendar:Attendee>")
                        append("<calendar:Email>$escapedEmail</calendar:Email>")
                        append("<calendar:AttendeeType>1</calendar:AttendeeType>")
                        append("<calendar:AttendeeStatus>0</calendar:AttendeeStatus>")
                        append("</calendar:Attendee>")
                    }
                    append("</calendar:Attendees>")
                }
                
                val recurrenceXml = CalendarRecurrenceBuilder.buildEasRecurrenceXml(recurrenceType, startTime)
                if (recurrenceXml.isNotBlank()) append(recurrenceXml)
            }
            
            append("</ApplicationData>")
            append("</Change></Commands>")
            append("</Collection></Collections>")
            append("</Sync>")
        }
        
        return deps.executeEasCommand("Sync", updateXml) { responseXml ->
            // Проверяем статус коллекции
            val collectionStatus = deps.extractValue(responseXml, "Status")
            if (collectionStatus != "1") {
                throw Exception("Collection Status=$collectionStatus")
            }
            
            // КРИТИЧНО: Проверяем статус конкретной операции Change
            // Согласно MS-ASCMD: "The server is not required to send an individual response
            // for every operation. The client only receives responses for failed changes."
            // Если <Responses><Change><Status> ЕСТЬ - проверяем его
            // Если НЕТ - считаем что SUCCESS
            
            if (responseXml.contains("<Responses>") && responseXml.contains("<Change>")) {
                val changeStatusMatch = CalendarXmlParser.CHANGE_STATUS_PATTERN.find(responseXml)
                
                if (changeStatusMatch != null) {
                    val changeStatus = changeStatusMatch.groupValues[1]
                    when (changeStatus) {
                        "1" -> true // Success
                        "6" -> throw Exception("Change Status=6: Error in client/server conversion (invalid item)")
                        "7" -> throw Exception("Change Status=7: Conflict (server changes take precedence)")
                        "8" -> throw Exception("Change Status=8: Object not found on server")
                        else -> throw Exception("Change Status=$changeStatus")
                    }
                } else {
                    // <Change> есть, но <Status> нет - считаем SUCCESS
                    true
                }
            } else {
                // Нет <Responses><Change> - согласно MS-ASCMD считаем SUCCESS
                true
            }
        }
    }
    
    private suspend fun deleteCalendarEventEas(serverId: String, calendarFolderId: String): EasResult<Boolean> {
        var syncKeyResult = syncService.getAdvancedSyncKey(calendarFolderId)
        var syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return syncKeyResult
        }
        
        // Удаление
        val deleteResult = executeEasDelete(serverId, syncKey, calendarFolderId)
        
        // Retry при INVALID_SYNCKEY (Status=3) или retriable ошибках сервера (Status=5,16)
        // MS-ASCMD §2.2.3.177.17:
        // - Status=3  -> вернуться к SyncKey=0 и повторить
        // - Status=5  -> transient server error, retry
        // - Status=16 -> Retry, resend request
        val needsRetry = deleteResult is EasResult.Error &&
                         (deleteResult.message.contains("INVALID_SYNCKEY") ||
                          deleteResult.message.contains("RETRY_TRANSIENT"))
        
        if (needsRetry) {
            android.util.Log.w("EasCalendarCrudService", "Delete failed, retrying with full SyncKey reset for serverId=$serverId")
            
            syncKeyResult = syncService.getAdvancedSyncKey(calendarFolderId)
            syncKey = when (syncKeyResult) {
                is EasResult.Success -> syncKeyResult.data
                is EasResult.Error -> return deleteResult // Не смогли — возвращаем исходную ошибку
            }
            
            return executeEasDelete(serverId, syncKey, calendarFolderId)
        }
        
        return deleteResult
    }
    
    /**
     * DRY: Выполнение EAS Sync Delete с указанным SyncKey.
     * Используется в deleteCalendarEventEas (основная попытка + retry).
     */
    private suspend fun executeEasDelete(
        serverId: String,
        syncKey: String,
        calendarFolderId: String
    ): EasResult<Boolean> {
        // КРИТИЧНО: <DeletesAsMoves>0</DeletesAsMoves> — перманентное удаление.
        // Без этого элемента (или со значением 1) событие лишь перемещается
        // в Deleted Items (MS-ASCMD §2.2.3.43), что вызывает "воскрешение"
        // при последующей синхронизации.
        val deleteXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(calendarFolderId)}</CollectionId>
            <DeletesAsMoves>0</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>${deps.escapeXml(serverId)}</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", deleteXml) { responseXml ->
            val collectionStatus = deps.extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            when (collectionStatus) {
                1 -> {
                    // Collection-level OK, но проверяем item-level ошибку в Responses/Delete.
                    // MS-ASCMD §2.2.3.154: <Responses><Delete> появляется ТОЛЬКО при failed deletion.
                    // Если нет <Responses> — удаление успешно (клиент MUST assume success).
                    // ServerId в Responses/Delete — optional (MS-ASCMD §2.2.3.166.8)
                    val deleteStatusMatch = CalendarXmlParser.DELETE_STATUS_PATTERN.find(responseXml)
                    val itemStatus = deleteStatusMatch?.groupValues?.get(1)?.toIntOrNull()
                    when (itemStatus) {
                        null -> true // Нет Responses/Delete — успех
                        8 -> true   // Object not found — уже удалено, считаем успехом
                        else -> throw Exception("EAS Sync Delete item-level failed: Status=$itemStatus")
                    }
                }
                8 -> true  // Object not found — уже удалено, считаем успехом
                3 -> throw Exception("INVALID_SYNCKEY") // SyncKey устарел
                5 -> throw Exception("RETRY_TRANSIENT: Status=5") // Transient server error
                16 -> throw Exception("RETRY_TRANSIENT: Status=16") // Server says retry
                else -> throw Exception("EAS Sync Delete failed: Status=$collectionStatus")
            }
        }
    }

    // ======================== EWS CRUD ========================

    private suspend fun createCalendarEventEws(
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attendees: List<String>,
        recurrenceType: Int = -1
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                
                val escapedSubject = deps.escapeXml(subject)
                val escapedLocation = deps.escapeXml(location)
                val escapedBody = deps.escapeXml(body)
                
                val startTimeStr = if (allDayEvent) CalendarDateUtils.formatEwsAllDayDate(startTime) else CalendarDateUtils.formatEwsDate(startTime)
                val endTimeStr = if (allDayEvent) CalendarDateUtils.formatEwsAllDayDate(endTime) else CalendarDateUtils.formatEwsDate(endTime)
                
                val ewsBusyStatus = CalendarDateUtils.mapBusyStatusToEws(busyStatus)
                
                // Если есть участники - это митинг, отправляем приглашения
                val sendInvitations = if (attendees.isNotEmpty()) "SendToAllAndSaveCopy" else "SendToNone"
                
                // Формируем блок участников по официальному примеру Microsoft
                val attendeesXml = if (attendees.isNotEmpty()) {
                    buildString {
                        append("<RequiredAttendees>")
                        for (email in attendees) {
                            val escapedEmail = deps.escapeXml(email.trim())
                            append("<Attendee>")
                            append("<Mailbox>")
                            append("<EmailAddress>$escapedEmail</EmailAddress>")
                            append("</Mailbox>")
                            append("</Attendee>")
                        }
                        append("</RequiredAttendees>")
                    }
                } else ""
                
                // Формируем SOAP запрос ТОЧНО по официальному примеру Microsoft:
                // Внутри CalendarItem элементы БЕЗ префикса t:, используется xmlns на CalendarItem
                val soapRequest = buildString {
                    append("""<?xml version="1.0" encoding="utf-8"?>""")
                    append("""<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
                    append("""xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """)
                    append("""xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""")
                    append("<soap:Header>")
                    append("""<t:RequestServerVersion Version="Exchange2007_SP1"/>""")
                    append("</soap:Header>")
                    append("<soap:Body>")
                    append("""<CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """)
                    append("""SendMeetingInvitations="$sendInvitations">""")
                    append("<SavedItemFolderId>")
                    append("""<t:DistinguishedFolderId Id="calendar"/>""")
                    append("</SavedItemFolderId>")
                    append("<Items>")
                    // CalendarItem с xmlns - внутренние элементы без префикса (как в официальном примере MS)
                    append("""<t:CalendarItem xmlns="http://schemas.microsoft.com/exchange/services/2006/types">""")
                    append("<Subject>$escapedSubject</Subject>")
                    if (escapedBody.isNotBlank()) {
                        append("""<Body BodyType="Text">$escapedBody</Body>""")
                    }
                    if (reminder > 0) {
                        append("<ReminderIsSet>true</ReminderIsSet>")
                        append("<ReminderMinutesBeforeStart>$reminder</ReminderMinutesBeforeStart>")
                    } else {
                        append("<ReminderIsSet>false</ReminderIsSet>")
                    }
                    append("<Start>$startTimeStr</Start>")
                    append("<End>$endTimeStr</End>")
                    append("<IsAllDayEvent>$allDayEvent</IsAllDayEvent>")
                    append("<LegacyFreeBusyStatus>$ewsBusyStatus</LegacyFreeBusyStatus>")
                    if (escapedLocation.isNotBlank()) {
                        append("<Location>$escapedLocation</Location>")
                    }
                    append(attendeesXml)
                    val ewsRecurrenceXml = CalendarRecurrenceBuilder.buildEwsRecurrenceXml(recurrenceType, startTimeStr)
                    if (ewsRecurrenceXml.isNotBlank()) append(ewsRecurrenceXml)
                    if (!allDayEvent) append(CalendarDateUtils.buildMeetingTimeZoneXml())
                    append("</t:CalendarItem>")
                    append("</Items>")
                    append("</CreateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                android.util.Log.d("EasCalendarCrudService", "createCalendarEventEws: Request: $soapRequest")
                
                val createResult = ewsRequest(ewsUrl, soapRequest, "CreateItem")
                if (createResult is EasResult.Error) return@withContext createResult
                val responseXml = (createResult as EasResult.Success).data
                
                android.util.Log.d("EasCalendarCrudService", "createCalendarEventEws: response length=${responseXml.length}, first 300: ${responseXml.take(300)}")
                
                // Проверяем на ошибки
                if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                    return@withContext EasResult.Error("Ошибка схемы EWS")
                }
                
                val idMatch = CalendarXmlParser.EWS_ITEM_ID.find(responseXml)
                val itemId = idMatch?.groupValues?.get(1)
                    ?: EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                val changeKey = idMatch?.groupValues?.get(2)
                android.util.Log.d("EasCalendarCrudService", "createCalendarEventEws: itemId=${itemId?.take(40)}, changeKey=${changeKey?.take(20)}")
                
                // КРИТИЧНО: Проверяем ResponseClass и ResponseCode (namespace-tolerant)
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val responseCode = CalendarXmlParser.EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()
                val hasNoError = responseCode == "NoError" || responseCode == null
                if (itemId != null) {
                    // Возвращаем ItemId|ChangeKey для последующей загрузки вложений
                    val returnId = if (changeKey != null) "$itemId|$changeKey" else itemId
                    EasResult.Success(returnId)
                } else if (hasSuccess && hasNoError) {
                    EasResult.Success("pending_sync_${System.currentTimeMillis()}")
                } else {
                    EasResult.Error("Не удалось создать событие через EWS")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Ошибка создания события через EWS")
            }
        }
    }
    
    private suspend fun updateCalendarEventEws(
        serverId: String,
        subject: String,
        startTime: Long,
        endTime: Long,
        location: String,
        body: String,
        allDayEvent: Boolean,
        reminder: Int,
        busyStatus: Int,
        sensitivity: Int,
        attendees: List<String>,
        oldSubject: String? = null,
        recurrenceType: Int = -1,
        newAttendeesToAppend: List<String> = emptyList()
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                
                // КРИТИЧНО: Получаем ПОЛНЫЙ EWS ItemId + ChangeKey через FindItem.
                // Нужно в двух случаях:
                // 1) serverId короткий EAS формат ("22:2") → нужен EWS ItemId
                // 2) serverId — EWS ItemId без ChangeKey (длинный, без "|") → нужен ChangeKey
                //    Exchange 2007 SP1 требует ChangeKey для UpdateItem!
                var actualServerId = serverId
                
                val needsFindItem = (serverId.contains(":") && !serverId.contains("=")) ||
                    (serverId.length > 50 && !serverId.contains("|"))
                
                if (needsFindItem) {
                    // КРИТИЧНО: Используем СТАРЫЙ subject для поиска (если subject изменился)
                    val searchSubject = oldSubject ?: subject
                    
                    val findResult = findCalendarItemIdBySubject(searchSubject, startTime)
                    
                    if (findResult is EasResult.Success) {
                        actualServerId = findResult.data
                    }
                    // Если не найдёт - попробуем с исходным serverId (может сработать)
                }
                
                val escapedSubject = deps.escapeXml(subject)
                val escapedLocation = deps.escapeXml(location)
                val escapedBody = deps.escapeXml(body)
                
                val startTimeStr = if (allDayEvent) CalendarDateUtils.formatEwsAllDayDate(startTime) else CalendarDateUtils.formatEwsDate(startTime)
                val endTimeStr = if (allDayEvent) CalendarDateUtils.formatEwsAllDayDate(endTime) else CalendarDateUtils.formatEwsDate(endTime)
                
                val ewsBusyStatus = CalendarDateUtils.mapBusyStatusToEws(busyStatus)
                
                // КРИТИЧНО: Разбираем actualServerId на ItemId и ChangeKey
                // Формат: "ItemId|ChangeKey" или просто "ItemId"
                val (itemId, changeKey) = if (actualServerId.contains("|")) {
                    val parts = actualServerId.split("|", limit = 2)
                    parts[0] to parts[1]
                } else {
                    actualServerId to null
                }
                
                // КРИТИЧНО: Используем EWS UpdateItem вместо DELETE+CREATE!
                // DELETE+CREATE вызывает дублирование событий!
                // UpdateItem требует ItemId с ChangeKey для Exchange 2007 SP1
                val soapRequest = buildString {
                    append("""<?xml version="1.0" encoding="utf-8"?>""")
                    append("""<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
                    append("""xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """)
                    append("""xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">""")
                    append("<soap:Header>")
                    append("""<t:RequestServerVersion Version="Exchange2007_SP1"/>""")
                    append("</soap:Header>")
                    append("<soap:Body>")
                    val sendMode = if (attendees.isNotEmpty()) "SendToAllAndSaveCopy" else "SendToNone"
                    append("""<m:UpdateItem ConflictResolution="AlwaysOverwrite" SendMeetingInvitationsOrCancellations="$sendMode">""")
                    append("<m:ItemChanges>")
                    append("<t:ItemChange>")
                    val safeItemId = deps.escapeXml(itemId)
                    if (changeKey != null) {
                        append("""<t:ItemId Id="$safeItemId" ChangeKey="${deps.escapeXml(changeKey)}"/>""")
                    } else {
                        append("""<t:ItemId Id="$safeItemId"/>""")
                    }
                    append("<t:Updates>")
                    
                    // Subject
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="item:Subject"/>""")
                    append("<t:CalendarItem>")
                    append("<t:Subject>$escapedSubject</t:Subject>")
                    append("</t:CalendarItem>")
                    append("</t:SetItemField>")
                    
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:Start"/>""")
                    append("<t:CalendarItem>")
                    append("<t:Start>$startTimeStr</t:Start>")
                    append("</t:CalendarItem>")
                    append("</t:SetItemField>")
                    
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:End"/>""")
                    append("<t:CalendarItem>")
                    append("<t:End>$endTimeStr</t:End>")
                    append("</t:CalendarItem>")
                    append("</t:SetItemField>")
                    
                    // Location — всегда отправляем (SetItemField если есть, DeleteItemField если очищено)
                    if (escapedLocation.isNotBlank()) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="calendar:Location"/>""")
                        append("<t:CalendarItem>")
                        append("<t:Location>$escapedLocation</t:Location>")
                        append("</t:CalendarItem>")
                        append("</t:SetItemField>")
                    } else {
                        append("<t:DeleteItemField>")
                        append("""<t:FieldURI FieldURI="calendar:Location"/>""")
                        append("</t:DeleteItemField>")
                    }
                    
                    // Body — всегда отправляем
                    if (escapedBody.isNotBlank()) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:Body"/>""")
                        append("<t:CalendarItem>")
                        append("""<t:Body BodyType="Text">$escapedBody</t:Body>""")
                        append("</t:CalendarItem>")
                        append("</t:SetItemField>")
                    } else {
                        append("<t:DeleteItemField>")
                        append("""<t:FieldURI FieldURI="item:Body"/>""")
                        append("</t:DeleteItemField>")
                    }
                    
                    // IsAllDayEvent
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:IsAllDayEvent"/>""")
                    append("<t:CalendarItem>")
                    append("<t:IsAllDayEvent>$allDayEvent</t:IsAllDayEvent>")
                    append("</t:CalendarItem>")
                    append("</t:SetItemField>")
                    
                    // LegacyFreeBusyStatus
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:LegacyFreeBusyStatus"/>""")
                    append("<t:CalendarItem>")
                    append("<t:LegacyFreeBusyStatus>$ewsBusyStatus</t:LegacyFreeBusyStatus>")
                    append("</t:CalendarItem>")
                    append("</t:SetItemField>")
                    
                    // Reminder — всегда отправляем для корректного снятия напоминания
                    if (reminder > 0) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:ReminderIsSet"/>""")
                        append("<t:CalendarItem>")
                        append("<t:ReminderIsSet>true</t:ReminderIsSet>")
                        append("</t:CalendarItem>")
                        append("</t:SetItemField>")
                        
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:ReminderMinutesBeforeStart"/>""")
                        append("<t:CalendarItem>")
                        append("<t:ReminderMinutesBeforeStart>$reminder</t:ReminderMinutesBeforeStart>")
                        append("</t:CalendarItem>")
                        append("</t:SetItemField>")
                    } else {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:ReminderIsSet"/>""")
                        append("<t:CalendarItem>")
                        append("<t:ReminderIsSet>false</t:ReminderIsSet>")
                        append("</t:CalendarItem>")
                        append("</t:SetItemField>")
                    }
                    
                    // Recurrence
                    val ewsRecurrenceUpdateXml = CalendarRecurrenceBuilder.buildEwsRecurrenceUpdateXml(recurrenceType, startTimeStr)
                    if (ewsRecurrenceUpdateXml.isNotBlank()) append(ewsRecurrenceUpdateXml)
                    
                    // Exchange 2007 SP1: SetItemField NOT supported for RequiredAttendees.
                    // Only APPEND truly new attendees (diff computed by CalendarRepository).
                    if (newAttendeesToAppend.isNotEmpty()) {
                        for (email in newAttendeesToAppend) {
                            val escapedEmail = deps.escapeXml(email.trim())
                            append("<t:AppendToItemField>")
                            append("""<t:FieldURI FieldURI="calendar:RequiredAttendees"/>""")
                            append("<t:CalendarItem>")
                            append("<t:RequiredAttendees>")
                            append("<t:Attendee><t:Mailbox>")
                            append("<t:EmailAddress>$escapedEmail</t:EmailAddress>")
                            append("</t:Mailbox></t:Attendee>")
                            append("</t:RequiredAttendees>")
                            append("</t:CalendarItem>")
                            append("</t:AppendToItemField>")
                        }
                    }
                    
                    append("</t:Updates>")
                    append("</t:ItemChange>")
                    append("</m:ItemChanges>")
                    append("</m:UpdateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                val updateResult = ewsRequest(ewsUrl, soapRequest, "UpdateItem")
                if (updateResult is EasResult.Error) return@withContext updateResult
                val responseXml = (updateResult as EasResult.Success).data
                
                // Проверяем ResponseClass и ResponseCode (namespace-tolerant: m: или без префикса)
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val responseCode = CalendarXmlParser.EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()
                
                if (hasSuccess && (responseCode == "NoError" || responseCode == null)) {
                    EasResult.Success(true)
                } else {
                    EasResult.Error("Ошибка обновления события: $responseCode")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Ошибка обновления события через EWS")
            }
        }
    }
    
    /**
     * EWS DeleteItem для календарных событий.
     * @param sendCancellations — значение SendMeetingCancellations:
     *   - "SendToNone" — обычное событие (не meeting) или участник
     *   - "SendToAllAndSaveCopy" — организатор отменяет встречу (отправляет отмену участникам)
     */
    private suspend fun deleteCalendarEventEws(
        serverId: String,
        sendCancellations: String = "SendToNone",
        deleteType: String = "HardDelete",
        isRecurringSeries: Boolean = false
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val escapedId = deps.escapeXml(serverId)

                // Exchange 2007 SP1: CalendarView returns expanded occurrences with unique
                // ItemIds. To delete the ENTIRE series, RecurringMasterItemId resolves the
                // master from any occurrence's ItemId (MS Learn: RecurringMasterItemId).
                // If the stored ID happens to be a master ItemId already (e.g. just after
                // CreateItem, before the next sync), RecurringMasterItemId may fail — we
                // fall back to a plain ItemId which directly targets the master.
                val primaryItemIdXml = if (isRecurringSeries) {
                    """<t:RecurringMasterItemId OccurrenceId="$escapedId"/>"""
                } else {
                    """<t:ItemId Id="$escapedId"/>"""
                }

                val result = executeDeleteItem(ewsUrl, primaryItemIdXml, deleteType, sendCancellations)
                if (result is EasResult.Success) return@withContext result

                if (isRecurringSeries) {
                    android.util.Log.w("EasCalendarCrudService",
                        "deleteCalendarEventEws: RecurringMasterItemId failed (${(result as EasResult.Error).message}), " +
                        "retrying with plain ItemId (serverId may already be master)")
                    val fallbackXml = """<t:ItemId Id="$escapedId"/>"""
                    return@withContext executeDeleteItem(ewsUrl, fallbackXml, deleteType, sendCancellations)
                }

                result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Ошибка удаления события: ${e.message}")
            }
        }
    }

    private suspend fun executeDeleteItem(
        ewsUrl: String,
        itemIdXml: String,
        deleteType: String,
        sendCancellations: String
    ): EasResult<Boolean> {
        val deleteBody = """
            <m:DeleteItem DeleteType="${deps.escapeXml(deleteType)}" SendMeetingCancellations="${deps.escapeXml(sendCancellations)}">
                <m:ItemIds>
                    $itemIdXml
                </m:ItemIds>
            </m:DeleteItem>
        """.trimIndent()
        val request = EasXmlTemplates.ewsSoapRequest(deleteBody)
        val deleteResult = ewsRequest(ewsUrl, request, "DeleteItem")
        if (deleteResult is EasResult.Error) return deleteResult
        val response = (deleteResult as EasResult.Success).data

        val responseCode = CalendarXmlParser.EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)?.trim()
        val hasSuccess = response.contains("ResponseClass=\"Success\"")
        return when {
            responseCode == "NoError" -> EasResult.Success(true)
            responseCode == "ErrorItemNotFound" -> {
                android.util.Log.w("EasCalendarCrudService",
                    "executeDeleteItem: ErrorItemNotFound. Event may have been already deleted or ItemId is stale.")
                EasResult.Success(true)
            }
            hasSuccess && responseCode == null -> EasResult.Success(true)
            else -> EasResult.Error("EWS DeleteItem: $responseCode")
        }
    }
    
    /**
     * EWS DeclineItem — участник отклоняет встречу.
     * КРИТИЧНО: Предотвращает "воскрешение" Calendar Repair Assistant (CRA).
     * CRA обнаруживает пропавшую встречу у участника и воссоздаёт её,
     * если участник просто удалил (DeleteItem) без уведомления организатора.
     * DeclineItem отправляет отказ организатору → CRA не вмешивается.
     * После DeclineItem делаем HardDelete для гарантированного удаления из календаря
     * (некоторые конфигурации Exchange оставляют declined meetings в календаре).
     * Per MS docs: CreateItem + DeclineItem, Exchange 2007 SP1+
     */
    private suspend fun declineCalendarEventEws(
        serverId: String,
        isRecurringSeries: Boolean = false
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val escapedId = deps.escapeXml(serverId)
                // Шаг 1: DeclineItem — уведомляем организатора (предотвращает CRA resurrection).
                // ReferenceItemId принимает только Id/ChangeKey; RecurringMasterItemId
                // в ReferenceItemId не поддерживается. Для серии decline отправится
                // по конкретному occurrence, а HardDelete ниже удалит всю серию.
                val declineBody = """
                    <m:CreateItem MessageDisposition="SendAndSaveCopy">
                        <m:Items>
                            <t:DeclineItem>
                                <t:ReferenceItemId Id="$escapedId"/>
                            </t:DeclineItem>
                        </m:Items>
                    </m:CreateItem>
                """.trimIndent()
                val request = EasXmlTemplates.ewsSoapRequest(declineBody)
                runCatching { ewsRequest(ewsUrl, request, "CreateItem") }
                
                // Шаг 2: HardDelete — гарантируем физическое удаление из календаря.
                // Для recurring series используем RecurringMasterItemId → удаляет всю серию.
                val itemIdXml = if (isRecurringSeries) {
                    """<t:RecurringMasterItemId OccurrenceId="$escapedId"/>"""
                } else {
                    """<t:ItemId Id="$escapedId"/>"""
                }
                val result = executeDeleteItem(ewsUrl, itemIdXml, "HardDelete", "SendToNone")
                if (result is EasResult.Success) return@withContext result

                if (isRecurringSeries) {
                    android.util.Log.w("EasCalendarCrudService",
                        "declineCalendarEventEws: RecurringMasterItemId failed, fallback to ItemId")
                    return@withContext executeDeleteItem(ewsUrl, """<t:ItemId Id="$escapedId"/>""", "HardDelete", "SendToNone")
                }
                result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Ошибка отклонения встречи: ${e.message}")
            }
        }
    }
    
    /**
     * EAS MeetingResponse — отклонение встречи через ActiveSync (Exchange 2010+).
     * MS-ASCMD §2.2.1.10: UserResponse=3 → Decline
     * Предотвращает воскрешение CRA аналогично DeclineItem в EWS.
     */
    private suspend fun meetingResponseEas(
        serverId: String,
        calendarFolderId: String,
        userResponse: Int = 3
    ): EasResult<Boolean> {
        val meetingResponseXml = """<?xml version="1.0" encoding="UTF-8"?>
<MeetingResponse xmlns="MeetingResponse">
    <Request>
        <UserResponse>$userResponse</UserResponse>
        <CollectionId>${deps.escapeXml(calendarFolderId)}</CollectionId>
        <RequestId>${deps.escapeXml(serverId)}</RequestId>
    </Request>
</MeetingResponse>""".trimIndent()
        
        return try {
            deps.executeEasCommand("MeetingResponse", meetingResponseXml) { true }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Success(true)
        }
    }

    /**
     * Один запрос DeleteItem с несколькими ItemId — аналог deleteEmailsBatchViaEWS.
     * Exchange обрабатывает все удаления атомарно, без проблем с NTLM-сессиями.
     */
    private suspend fun deleteCalendarEventsBatchEws(
        itemIds: List<String>,
        sendCancellations: String = "SendToNone",
        isRecurringSeries: Boolean = false
    ): EasResult<Int> {
        if (itemIds.isEmpty()) return EasResult.Success(0)
        val ewsUrl = deps.getEwsUrl()
        val itemIdsXml = itemIds.joinToString("\n") { id ->
            if (isRecurringSeries) {
                """        <t:RecurringMasterItemId OccurrenceId="${deps.escapeXml(id)}"/>"""
            } else {
                """        <t:ItemId Id="${deps.escapeXml(id)}"/>"""
            }
        }
        val deleteBody = """
    <m:DeleteItem DeleteType="HardDelete" SendMeetingCancellations="${deps.escapeXml(sendCancellations)}">
        <m:ItemIds>
$itemIdsXml
        </m:ItemIds>
    </m:DeleteItem>""".trimIndent()
        val request = EasXmlTemplates.ewsSoapRequest(deleteBody)

        val deleteResult = ewsRequest(ewsUrl, request, "DeleteItem")
        if (deleteResult is EasResult.Error) return EasResult.Error((deleteResult).message)
        val response = (deleteResult as EasResult.Success).data

        val successCount = "ResponseClass=\"Success\"".toRegex().findAll(response).count()
        val itemNotFoundCount = "ErrorItemNotFound".toRegex().findAll(response).count()
        val totalOk = successCount + itemNotFoundCount

        android.util.Log.d("EasCalendarCrudService",
            "deleteCalendarEventsBatchEws: ${itemIds.size} items, ok=$totalOk (success=$successCount, notFound=$itemNotFoundCount)")

        return if (totalOk > 0) {
            EasResult.Success(totalOk)
        } else {
            val errorCode = CalendarXmlParser.EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)?.trim()
            EasResult.Error("EWS batch DeleteItem: $errorCode")
        }
    }

    // ======================== Helpers ========================

    /**
     * DRY: Поиск EWS ItemId по Subject+StartTime с задержкой и retry.
     * Exchange 2007 SP1 медленно индексирует новые/обновлённые события в EWS.
     * @param initialDelayMs задержка перед первой попыткой
     * @param retryDelayMs задержка перед повторной попыткой
     * @param tag метка для логирования (create/update)
     */
    private suspend fun findItemIdWithRetry(
        subject: String,
        startTime: Long,
        initialDelayMs: Long,
        retryDelayMs: Long,
        tag: String
    ): EasResult<String> {
        kotlinx.coroutines.delay(initialDelayMs)
        val firstTry = findCalendarItemIdBySubject(subject, startTime)
        if (firstTry is EasResult.Error) {
            android.util.Log.w("EasCalendarCrudService",
                "findCalendarItemIdBySubject ($tag) attempt 1 failed, retry in ${retryDelayMs}ms: ${firstTry.message}")
            kotlinx.coroutines.delay(retryDelayMs)
            return findCalendarItemIdBySubject(subject, startTime)
        }
        return firstTry
    }

    /**
     * Поиск ПОЛНОГО EWS ItemId события по Subject и StartTime
     * Используется для конвертации КОРОТКОГО ActiveSync serverId в ПОЛНЫЙ EWS ItemId
     * CalendarView с узким окном ±1 день + Subject-фильтр в коде (CalendarView и Restriction
     * нельзя совмещать в EWS FindItem)
     */
    private suspend fun findCalendarItemIdBySubject(
        subject: String,
        startTime: Long = 0L
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                
                // CalendarView с узким окном ±1 день от startTime (или широким если startTime=0)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val oneDayMs = 24L * 60 * 60 * 1000
                val viewStart: String
                val viewEnd: String
                if (startTime > 0L) {
                    viewStart = sdf.format(java.util.Date(startTime - oneDayMs))
                    viewEnd = sdf.format(java.util.Date(startTime + oneDayMs))
                } else {
                    val now = System.currentTimeMillis()
                    viewStart = sdf.format(java.util.Date(now - 365L * oneDayMs))
                    viewEnd = sdf.format(java.util.Date(now + 730L * oneDayMs))
                }
                
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
                    <t:FieldURI FieldURI="calendar:Start"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:CalendarView MaxEntriesReturned="50" StartDate="$viewStart" EndDate="$viewEnd"/>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
                
                val responseResult = ewsRequest(ewsUrl, findRequest, "FindItem")
                if (responseResult is EasResult.Error) return@withContext responseResult
                val response = (responseResult as EasResult.Success).data
                
                // Извлекаем все ItemId+ChangeKey+Subject, фильтруем по Subject в коде
                // Namespace-tolerant: match both <t:CalendarItem> and <CalendarItem>
                val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val idPattern = "<(?:t:)?ItemId[^>]*\\bId=\"([^\"]+)\"[^>]*\\bChangeKey=\"([^\"]+)\"".toRegex()
                val subjectPattern = "<(?:t:)?Subject>(.*?)</(?:t:)?Subject>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val startPattern = "<(?:t:)?Start>(.*?)</(?:t:)?Start>".toRegex(RegexOption.DOT_MATCHES_ALL)
                
                for (itemMatch in itemPattern.findAll(response)) {
                    val itemXml = itemMatch.groupValues[1]
                    val subjectMatch = subjectPattern.find(itemXml)?.groupValues?.get(1) ?: ""
                    val startMatch = startPattern.find(itemXml)?.groupValues?.get(1)
                    val startMatches = if (startTime > 0L) {
                        val itemStartMs = CalendarDateUtils.parseEwsDateTime(startMatch)
                        itemStartMs == null || kotlin.math.abs(itemStartMs - startTime) <= 5L * 60 * 1000
                    } else {
                        true
                    }
                    if (subjectMatch.equals(subject, ignoreCase = true) && startMatches) {
                        val idMatch = idPattern.find(itemXml)
                        if (idMatch != null) {
                            return@withContext EasResult.Success("${idMatch.groupValues[1]}|${idMatch.groupValues[2]}")
                        }
                    }
                }
                
                // УБРАН опасный fallback: брать первый ItemId из ответа означало бы
                // прикрепить вложение к СЛУЧАЙНОМУ событию в случае промаха.
                EasResult.Error("ItemId not found for subject=$subject")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Failed to find ItemId: ${e.message}")
            }
        }
    }

    /**
     * Находит EWS ItemId recurring master по Subject.
     *
     * КРИТИЧНО для Exchange 2007 SP1:
     * FindItem + CalendarView возвращает развёрнутые вхождения (occurrence ItemId).
     * FindItem БЕЗ CalendarView возвращает recurring masters (master ItemId).
     * Вложения серии хранятся на master → CreateAttachment/GetAttachment
     * должны использовать именно master ItemId.
     */
    private suspend fun findRecurringMasterItemId(
        subject: String
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()

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
                    <t:FieldURI FieldURI="calendar:CalendarItemType"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:Restriction>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="${deps.escapeXml(subject)}"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

                val responseResult = ewsRequest(ewsUrl, findRequest, "FindItem")
                if (responseResult is EasResult.Error) return@withContext responseResult
                val response = (responseResult as EasResult.Success).data

                val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
                    .toRegex(RegexOption.DOT_MATCHES_ALL)
                val idPattern = "<(?:t:)?ItemId[^>]*\\bId=\"([^\"]+)\"[^>]*\\bChangeKey=\"([^\"]+)\""
                    .toRegex()

                for (itemMatch in itemPattern.findAll(response)) {
                    val itemXml = itemMatch.groupValues[1]
                    val calItemType = XmlValueExtractor.extractEws(itemXml, "CalendarItemType") ?: ""
                    val itemSubject = XmlValueExtractor.extractEws(itemXml, "Subject") ?: ""

                    if (calItemType == "RecurringMaster" &&
                        itemSubject.equals(subject, ignoreCase = true)
                    ) {
                        val idMatch = idPattern.find(itemXml)
                        if (idMatch != null) {
                            return@withContext EasResult.Success(
                                "${idMatch.groupValues[1]}|${idMatch.groupValues[2]}"
                            )
                        }
                    }
                }

                EasResult.Error("RecurringMaster ItemId not found for subject=$subject")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error("Failed to find RecurringMaster ItemId: ${e.message}")
            }
        }
    }
}

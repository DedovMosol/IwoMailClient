package com.dedovmosol.iwomail.eas

import android.util.Base64
import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Сервис для работы с календарём Exchange (EAS/EWS)
 * Выделен из EasClient для соблюдения принципа SRP (Single Responsibility)
 * 
 * Отвечает за:
 * - Синхронизацию событий календаря
 * - Создание, обновление, удаление событий
 * - Работу с EWS для Exchange 2007
 */
class EasCalendarService internal constructor(
    private val deps: CalendarServiceDependencies
) {
    
    interface EasCommandExecutor {
        suspend operator fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T>
    }
    
    /**
     * Зависимости для EasCalendarService
     */
    class CalendarServiceDependencies(
        val executeEasCommand: EasCommandExecutor,
        val folderSync: suspend (String) -> EasResult<FolderSyncResponse>,
        val refreshSyncKey: suspend (String, String) -> EasResult<String>,
        val extractValue: (String, String) -> String?,
        val escapeXml: (String) -> String,
        val getEasVersion: () -> String,
        val isVersionDetected: () -> Boolean,
        val detectEasVersion: suspend () -> EasResult<String>,
        val performNtlmHandshake: suspend (String, String, String) -> String?,
        val executeNtlmRequest: suspend (String, String, String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String, String) -> String?,
        val getEwsUrl: () -> String,
        val parseEasDate: (String?) -> Long?
    )
    
    companion object {
        private val DELETE_SERVER_ID_PATTERN = "<(?:Delete|SoftDelete)>\\s*<ServerId>(.*?)</ServerId>\\s*</(?:Delete|SoftDelete)>".toRegex(RegexOption.DOT_MATCHES_ALL)

        // Предкомпилированные regex для hot paths (sync parsing)
        private val RESPONSES_PATTERN = "<Responses>(.*?)</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ADD_PATTERN = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val CHANGE_PATTERN = "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ADD_OR_CHANGE_PATTERN = "<Add>(.*?)</Add>|<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val APP_DATA_PATTERN = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_RESPONSE_CODE = "<(?:m:)?ResponseCode>(.*?)</(?:m:)?ResponseCode>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_ITEM_ID = "<(?:t:)?ItemId[^>]*\\bId=\"([^\"]+)\"[^>]*\\bChangeKey=\"([^\"]+)\"".toRegex()
        private val CHANGE_STATUS_PATTERN = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
        private val DELETE_STATUS_PATTERN = "<Responses>.*?<Delete>.*?<Status>(\\d+)</Status>.*?</Delete>.*?</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EAS_CATEGORIES_PATTERN = "<calendar:Categories>(.*?)</calendar:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EAS_CATEGORY_PATTERN = "<calendar:Category>(.*?)</calendar:Category>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_CATEGORIES_PATTERN = "<(?:t:)?Categories>(.*?)</(?:t:)?Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EWS_STRING_PATTERN = "<(?:t:)?String>(.*?)</(?:t:)?String>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ATTENDEE_PATTERN = "<calendar:Attendee>(.*?)</calendar:Attendee>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val ATTACHMENT_PATTERN = "<(?:airsyncbase:)?Attachment>(.*?)</(?:airsyncbase:)?Attachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EAS_RECURRENCE_PATTERN = "<(?:calendar:)?Recurrence>(.*?)</(?:calendar:)?Recurrence>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val EAS_EXCEPTION_PATTERN = "<(?:calendar:)?Exception>(.*?)</(?:calendar:)?Exception>".toRegex(RegexOption.DOT_MATCHES_ALL)
    }
    
    // Кэш ID папки календаря
    private var cachedCalendarFolderId: String? = null
    
    // Формат даты EAS
    private val easDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    /**
     * Синхронизация календаря
     * EAS 14+ — стандартный Calendar sync
     * EAS 12.x — fallback через EWS
     */
    suspend fun syncCalendar(): EasResult<CalendarSyncResult> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        android.util.Log.d("EasCalendarService", "syncCalendar: EAS version = ${deps.getEasVersion()}")
        
        val foldersResult = deps.folderSync("0")
        val folders = when (foldersResult) {
            is EasResult.Success -> foldersResult.data.folders
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        val easResult = syncCalendarEasFromFolders(folders)
        
        if (easResult is EasResult.Success && easResult.data.events.isNotEmpty()) {
            val withAttFix = try {
                supplementAttachmentsViaEws(easResult.data.events)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("EasCalendarService", "supplementAttachmentsViaEws failed: ${e.message}")
                easResult.data.events
            }
            return EasResult.Success(CalendarSyncResult(withAttFix, easResult.data.deletedServerIds))
        }
        return easResult
    }
    
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
            actualStartTime = normalizeAllDayUtcMidnight(startTime, addDay = false)
            actualEndTime = normalizeAllDayUtcMidnight(endTime, addDay = true)
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
                    "EasCalendarService",
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
            android.util.Log.d("EasCalendarService", "createCalendarEvent: attachments=${attachments.size}, rawId=${rawId.take(60)}, createdViaEws=$createdViaEws")
            run {
                val ewsUrl = deps.getEwsUrl()
                // КРИТИЧНО: CreateAttachment требует именно EWS ItemId (не EAS ServerId).
                // Единственный случай когда rawId уже EWS ItemId — когда createCalendarEventEws
                // вернул "ItemId|ChangeKey" (rawId содержит '|').
                // Во всех остальных случаях (EAS ServerId, pending_sync_) — ищем через FindItem.
                val alreadyEwsId = !rawId.startsWith("pending_sync_") && 
                    (rawId.contains("|") || (createdViaEws && !rawId.contains(":")))
                val ewsItemIdResult = if (alreadyEwsId) {
                    android.util.Log.d("EasCalendarService", "createCalendarEvent: using rawId directly as EWS ItemId")
                    EasResult.Success(rawId)
                } else {
                    // Exchange 2007 SP1 медленно индексирует новое событие в EWS.
                    // КРИТИЧНО: также вызывается когда CreateItem вернул Success но без ItemId
                    // (pending_sync_) — нужен FindItem для получения EWS ItemId для вложений.
                    android.util.Log.d("EasCalendarService", "createCalendarEvent: FindItem needed (rawId=${rawId.take(30)})")
                    findItemIdWithRetry(subject, actualStartTime, 2500, 3500, "create")
                }

                if (ewsItemIdResult is EasResult.Success) {
                    val resolvedRawId = ewsItemIdResult.data
                    val cleanItemId = if (resolvedRawId.contains("|")) resolvedRawId.substringBefore("|") else resolvedRawId
                    val changeKey = if (resolvedRawId.contains("|")) resolvedRawId.substringAfter("|") else null
                    android.util.Log.d("EasCalendarService", "createCalendarEvent: calling attachFilesEws, itemId=${cleanItemId.take(40)}, hasChangeKey=${changeKey != null}, attachments=${attachments.size}")
                    val attachResult = attachFilesEws(ewsUrl, cleanItemId, changeKey, attachments, "Exchange2007_SP1")
                    if (attachResult is EasResult.Error) {
                        // Нефатально: само событие уже создано на сервере, не блокируем отправку.
                        android.util.Log.e("EasCalendarService", "Событие создано, но вложения не загружены: ${attachResult.message}")
                        return result
                    }
                    // Возвращаем "cleanItemId\nattachmentsJson" — Repository разберёт
                    val attachJson = (attachResult as EasResult.Success).data
                    return EasResult.Success("$cleanItemId\n$attachJson")
                } else {
                    // Нефатально: событие создано, но ItemId для вложений не найден.
                    android.util.Log.e("EasCalendarService", "Событие создано, но EWS ItemId для вложений не найден: ${(ewsItemIdResult as EasResult.Error).message}")
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
            actualStartTime = normalizeAllDayUtcMidnight(startTime, addDay = false)
            actualEndTime = normalizeAllDayUtcMidnight(endTime, addDay = true)
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
                    "EasCalendarService",
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
            android.util.Log.d("EasCalendarService", "updateCalendarEvent: uploading ${attachments.size} attachments, subject=$subject")
            val ewsUrl = deps.getEwsUrl()
            // КРИТИЧНО: CreateAttachment требует EWS ItemId, не EAS ServerId ни в каком формате.
            // Всегда ищем через FindItem, даем Exchange 2007 SP1 время индексировать обновление.
            val ewsItemIdResult = findItemIdWithRetry(subject, actualStartTime, 2000, 3000, "update")
            if (ewsItemIdResult is EasResult.Success) {
                val rawId = ewsItemIdResult.data
                val cleanItemId = if (rawId.contains("|")) rawId.substringBefore("|") else rawId
                val changeKey = if (rawId.contains("|")) rawId.substringAfter("|") else null
                android.util.Log.d("EasCalendarService", "updateCalendarEvent: calling attachFilesEws, itemId=${cleanItemId.take(40)}, hasChangeKey=${changeKey != null}")
                val attachResult = attachFilesEws(ewsUrl, cleanItemId, changeKey, attachments, "Exchange2007_SP1")
                if (attachResult is EasResult.Error) {
                    // Нефатально: само событие уже обновлено на сервере.
                    android.util.Log.e("EasCalendarService", "Событие обновлено, но вложения не загружены: ${attachResult.message}")
                    return EasResult.Success("")
                }
                return EasResult.Success((attachResult as EasResult.Success).data)
            } else {
                val errMsg = (ewsItemIdResult as EasResult.Error).message
                // Нефатально: само событие уже обновлено на сервере.
                android.util.Log.e("EasCalendarService", "Не удалось найти EWS ItemId для загрузки вложений: $errMsg")
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
        sensitivity: Int
    ): EasResult<Boolean> {
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
            val existingException = existingExceptions.find {
                it.exceptionStartTime == occurrenceOriginalStartTime && !it.deleted
            }
            val searchSubject = existingException?.subject?.ifBlank { masterSubject } ?: masterSubject
            val searchStartTime = if (existingException != null && existingException.startTime > 0) {
                existingException.startTime
            } else {
                occurrenceOriginalStartTime
            }
            return updateSingleOccurrenceEws(
                searchSubject, searchStartTime,
                subject, startTime, endTime, location, body,
                allDayEvent, reminder, busyStatus, sensitivity
            )
        }

        // Exchange 2010+ (EAS 14+): EAS Exceptions (Body через airsyncbase:Body)
        val calendarFolderId = getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")

        val syncKeyResult = getAdvancedSyncKey(calendarFolderId)
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return syncKeyResult
        }

        val existingExceptions = RecurrenceHelper.parseExceptions(existingExceptionsJson)

        val xml = buildExceptionSyncXml(
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
                val changeStatusMatch = CHANGE_STATUS_PATTERN.find(responseXml)
                if (changeStatusMatch != null) {
                    val changeStatus = changeStatusMatch.groupValues[1]
                    if (changeStatus != "1") {
                        throw Exception("Change Status=$changeStatus")
                    }
                }
            }
            true
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
        sensitivity: Int
    ): EasResult<Boolean> {
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
                    val itemStartMs = parseEwsDateTime(itemStartStr) ?: continue

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
                val startStr = if (allDayEvent) formatEwsAllDayDate(startTime) else formatEwsDate(startTime)
                val endStr = if (allDayEvent) formatEwsAllDayDate(endTime) else formatEwsDate(endTime)
                val ewsBusyStatus = mapBusyStatusToEws(busyStatus)

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
                    append("""<t:ItemId Id="$bestItemId" ChangeKey="$bestChangeKey"/>""")
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
                val responseCode = EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()

                if (hasSuccess && (responseCode == "NoError" || responseCode == null)) {
                    EasResult.Success(true)
                } else {
                    EasResult.Error("EWS UpdateItem occurrence error: $responseCode")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "EWS occurrence update failed")
            }
        }
    }

    private fun buildExceptionSyncXml(
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
        val escapedSubject = deps.escapeXml(subject)
        val escapedLocation = deps.escapeXml(location)
        val escapedBody = deps.escapeXml(body)

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            if (majorVersion >= 14) {
                append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">""")
            } else {
                append("""<Sync xmlns="AirSync" xmlns:calendar="Calendar">""")
            }
            append("<Collections><Collection>")
            append("<SyncKey>$syncKey</SyncKey>")
            append("<CollectionId>$collectionId</CollectionId>")
            append("<Commands><Change>")
            append("<ServerId>$serverId</ServerId>")
            append("<ApplicationData>")

            append("<calendar:Exceptions>")

            for (ex in existingExceptions) {
                if (ex.exceptionStartTime == occurrenceOriginalStart) continue
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
            if (ex.subject.isNotBlank()) append("<calendar:Subject>${deps.escapeXml(ex.subject)}</calendar:Subject>")
            if (ex.startTime > 0) append("<calendar:StartTime>${formatEasDate(ex.startTime)}</calendar:StartTime>")
            if (ex.endTime > 0) append("<calendar:EndTime>${formatEasDate(ex.endTime)}</calendar:EndTime>")
            if (ex.location.isNotBlank()) append("<calendar:Location>${deps.escapeXml(ex.location)}</calendar:Location>")
            if (ex.body.isNotBlank() && majorVersion >= 14) {
                append("<airsyncbase:Body>")
                append("<airsyncbase:Type>1</airsyncbase:Type>")
                append("<airsyncbase:Data>${deps.escapeXml(ex.body)}</airsyncbase:Data>")
                append("</airsyncbase:Body>")
            }
        }
        append("</calendar:Exception>")
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
        isOrganizer: Boolean = false
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
            android.util.Log.d("EasCalendarService", "deleteCalendarEvent: using EWS path for EWS ItemId (len=${serverId.length})")
            if (isMeeting && !isOrganizer) {
                declineCalendarEventEws(ewsItemId)
            } else if (isMeeting && isOrganizer) {
                deleteCalendarEventEws(ewsItemId, sendCancellations = "SendToAllAndSaveCopy")
            } else {
                deleteCalendarEventEws(ewsItemId)
            }
        } else if (isEasServerId) {
            val calendarFolderId = getCalendarFolderId()
                ?: return EasResult.Error("Папка календаря не найдена")
            if (isMeeting && !isOrganizer) {
                meetingResponseEas(serverId, calendarFolderId, userResponse = 3)
            }
            deleteCalendarEventEas(serverId, calendarFolderId)
        } else {
            android.util.Log.w("EasCalendarService", "deleteCalendarEvent: unknown serverId format (len=${serverId.length}), trying EWS")
            deleteCalendarEventEws(serverId)
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
        requests: List<Triple<String, Boolean, Boolean>> // (serverId, isMeeting, isOrganizer)
    ): EasResult<Int> {
        if (requests.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()

        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()

                // Категоризация: EWS vs EAS, decline vs regular/organizer
                val ewsRegular = mutableListOf<Pair<String, String>>()    // (itemId, sendCancellations)
                val ewsDecline = mutableListOf<String>()                   // attendee decline
                val easEvents = mutableListOf<Triple<String, Boolean, Boolean>>()

                for ((serverId, isMeeting, isOrganizer) in requests) {
                    val isEwsItemId = (serverId.length > 50 && !serverId.contains(":")) || serverId.contains("|")
                    val isEasServerId = serverId.contains(":") && serverId.length < 20

                    val ewsItemId = if (serverId.contains("|")) serverId.substringBefore("|") else serverId

                    when {
                        isEwsItemId && isMeeting && !isOrganizer ->
                            ewsDecline.add(ewsItemId)
                        isEwsItemId && isMeeting && isOrganizer ->
                            ewsRegular.add(ewsItemId to "SendToAllAndSaveCopy")
                        isEwsItemId ->
                            ewsRegular.add(ewsItemId to "SendToNone")
                        isEasServerId ->
                            easEvents.add(Triple(serverId, isMeeting, isOrganizer))
                        else ->
                            ewsRegular.add(serverId to "SendToNone")
                    }
                }

                var deleted = 0

                // 1. Batch EWS: группируем по sendCancellations, один DeleteItem на группу
                val grouped = ewsRegular.groupBy({ it.second }, { it.first })
                for ((sendCancel, itemIds) in grouped) {
                    val batchResult = deleteCalendarEventsBatchEws(itemIds, sendCancel)
                    if (batchResult is EasResult.Success) {
                        deleted += batchResult.data
                    } else {
                        android.util.Log.w("EasCalendarService",
                            "deleteCalendarEventsBatch: batch EWS ($sendCancel) failed for ${itemIds.size} items: ${(batchResult as? EasResult.Error)?.message}")
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
                for ((serverId, isMeeting, isOrganizer) in easEvents) {
                    val result = deleteCalendarEvent(serverId, isMeeting, isOrganizer)
                    if (result is EasResult.Success && result.data) deleted++
                }

                android.util.Log.d("EasCalendarService",
                    "deleteCalendarEventsBatch: deleted $deleted/${requests.size}")
                EasResult.Success(deleted)
            } catch (e: Exception) {
                android.util.Log.e("EasCalendarService",
                    "deleteCalendarEventsBatch: exception: ${e.message}")
                EasResult.Error("Batch delete error: ${e.message}")
            }
        }
    }

    /**
     * Один запрос DeleteItem с несколькими ItemId — аналог deleteEmailsBatchViaEWS.
     * Exchange обрабатывает все удаления атомарно, без проблем с NTLM-сессиями.
     */
    private suspend fun deleteCalendarEventsBatchEws(
        itemIds: List<String>,
        sendCancellations: String = "SendToNone"
    ): EasResult<Int> {
        if (itemIds.isEmpty()) return EasResult.Success(0)
        val ewsUrl = deps.getEwsUrl()
        val itemIdsXml = itemIds.joinToString("\n") {
            """        <t:ItemId Id="${deps.escapeXml(it)}"/>"""
        }
        val deleteBody = """
    <m:DeleteItem DeleteType="HardDelete" SendMeetingCancellations="$sendCancellations">
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

        android.util.Log.d("EasCalendarService",
            "deleteCalendarEventsBatchEws: ${itemIds.size} items, ok=$totalOk (success=$successCount, notFound=$itemNotFoundCount)")

        return if (totalOk > 0) {
            EasResult.Success(totalOk)
        } else {
            val errorCode = EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)?.trim()
            EasResult.Error("EWS batch DeleteItem: $errorCode")
        }
    }
    
    // === Вспомогательные методы ===
    
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
            android.util.Log.w("EasCalendarService",
                "findCalendarItemIdBySubject ($tag) attempt 1 failed, retry in ${retryDelayMs}ms: ${firstTry.message}")
            kotlinx.coroutines.delay(retryDelayMs)
            return findCalendarItemIdBySubject(subject, startTime)
        }
        return firstTry
    }
    
    /**
     * DRY: Выполняет EWS SOAP запрос с Basic Auth → NTLM fallback.
     * Устраняет дублирование auth-логики в 7+ методах.
     * @return тело ответа или EasResult.Error
     */
    private suspend fun ewsRequest(
        ewsUrl: String,
        soapBody: String,
        operation: String
    ): EasResult<String> {
        var response = deps.tryBasicAuthEws(ewsUrl, soapBody, operation)
        if (response == null) {
            val authHeader = deps.performNtlmHandshake(ewsUrl, soapBody, operation)
                ?: return EasResult.Error("NTLM handshake failed ($operation)")
            response = deps.executeNtlmRequest(ewsUrl, soapBody, authHeader, operation)
                ?: return EasResult.Error("EWS request failed ($operation)")
        }
        return EasResult.Success(response)
    }
    
    /**
     * DRY: Общий метод получения актуального SyncKey для EAS операций (Create/Update/Delete).
     * Шаг 1: Начальный Sync с SyncKey=0 → получаем первый SyncKey
     * Шаг 2: Полное продвижение через <MoreAvailable> до актуального состояния
     * КРИТИЧНО: без полного продвижения операции могут получить Status=3 (INVALID_SYNCKEY).
     */
    private suspend fun getAdvancedSyncKey(calendarFolderId: String): EasResult<String> {
        val initialXml = calendarSyncInitialXml(calendarFolderId)
        var syncKey = "0"
        
        val initialResult = deps.executeEasCommand("Sync", initialXml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
        }
        
        var moreAvailable = true
        var syncIterations = 0
        val maxSyncIterations = 50
        var prevAdvanceKey = syncKey
        var sameAdvanceKeyCount = 0
        
        while (moreAvailable && syncIterations < maxSyncIterations) {
            syncIterations++
            // КРИТИЧНО: GetChanges ДОЛЖЕН быть 1 для полного продвижения SyncKey.
            // С GetChanges=0 сервер не вернёт <MoreAvailable>, и цикл выйдет
            // после 1 итерации с потенциально устаревшим SyncKey → Status=3 на write-операциях.
            // WindowSize=512 ускоряет продвижение (больше элементов за итерацию).
            val advanceXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <GetChanges>1</GetChanges>
            <WindowSize>512</WindowSize>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
            
            val advanceResult = deps.executeEasCommand("Sync", advanceXml) { responseXml ->
                val newKey = deps.extractValue(responseXml, "SyncKey")
                val hasMore = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                Pair(newKey ?: syncKey, hasMore)
            }
            
            when (advanceResult) {
                is EasResult.Success -> {
                    syncKey = advanceResult.data.first
                    moreAvailable = advanceResult.data.second
                    // Защита от зависания: если SyncKey не меняется — Exchange завис или ошибка
                    if (syncKey == prevAdvanceKey) {
                        sameAdvanceKeyCount++
                        if (sameAdvanceKeyCount >= 3) {
                            android.util.Log.w("EasCalendarService", "getAdvancedSyncKey: SyncKey stuck at $syncKey for 3 iterations, breaking")
                            moreAvailable = false
                        }
                    } else {
                        sameAdvanceKeyCount = 0
                        prevAdvanceKey = syncKey
                    }
                }
                is EasResult.Error -> {
                    moreAvailable = false
                }
            }
        }
        
        android.util.Log.d("EasCalendarService", "SyncKey advanced in $syncIterations iterations, syncKey=$syncKey")
        return EasResult.Success(syncKey)
    }
    
    private suspend fun getCalendarFolderId(): String? {
        if (cachedCalendarFolderId != null) {
            return cachedCalendarFolderId
        }
        
        val foldersResult = deps.folderSync("0")
        return when (foldersResult) {
            is EasResult.Success -> {
                val folderId = foldersResult.data.folders.find { it.type == 8 }?.serverId
                cachedCalendarFolderId = folderId
                folderId
            }
            is EasResult.Error -> null
        }
    }
    
    @Synchronized
    private fun formatEasDate(timestamp: Long): String {
        return easDateFormat.format(Date(timestamp))
    }
    
    private fun calendarSyncInitialXml(calendarFolderId: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()

    // === EAS методы (Exchange 2010+) ===
    
/**
     * DRY: Единый метод EAS-синхронизации календаря из списка папок.
     * Используется и для Standard (v14+), и для Legacy (v12.x) путей.
     */
    private suspend fun syncCalendarEasFromFolders(folders: List<EasFolder>): EasResult<CalendarSyncResult> {
        val calendarFolderId = folders.find { it.type == 8 }?.serverId
            ?: return EasResult.Error("Папка календаря не найдена")
        
        cachedCalendarFolderId = calendarFolderId
        
        val initialXml = calendarSyncInitialXml(calendarFolderId)
        var syncKey = "0"
        
        val initialResult = deps.executeEasCommand("Sync", initialXml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Error(initialResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Success(CalendarSyncResult(emptyList()))
        }
        
        return syncCalendarEasLoop(calendarFolderId, syncKey)
    }

    /**
     * Общий цикл EAS-синхронизации календаря с пагинацией и защитами (DRY).
     * Используется из syncCalendarEasFromFolders.
     * Robustness: timeout, sameKey loop detection, consecutiveErrors, empty data detection.
     */
    data class CalendarSyncResult(
        val events: List<EasCalendarEvent>,
        val deletedServerIds: Set<String> = emptySet()
    )

    private suspend fun syncCalendarEasLoop(
        calendarFolderId: String,
        initialSyncKey: String
    ): EasResult<CalendarSyncResult> {
        var syncKey = initialSyncKey
        val allEvents = mutableListOf<EasCalendarEvent>()
        val allDeletedIds = mutableSetOf<String>()
        var moreAvailable = true
        var iterations = 0
        val maxIterations = 100
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 3
        val syncStartTime = System.currentTimeMillis()
        val maxSyncDurationMs = 300_000L // 5 мин
        var previousSyncKey = syncKey
        var sameKeyCount = 0
        var emptyDataCount = 0
        
        while (moreAvailable && iterations < maxIterations && consecutiveErrors < maxConsecutiveErrors) {
            iterations++
            
            if (System.currentTimeMillis() - syncStartTime > maxSyncDurationMs) {
                android.util.Log.w("EasCalendarService", "Calendar sync timeout after $iterations iterations")
                break
            }
            
            kotlinx.coroutines.yield()
            
            val syncXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <DeletesAsMoves/>
            <GetChanges/>
            <WindowSize>100</WindowSize>
            <Options>
                <FilterType>0</FilterType>
                <airsyncbase:BodyPreference>
                    <airsyncbase:Type>1</airsyncbase:Type>
                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                </airsyncbase:BodyPreference>
            </Options>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
            
            // [0]=syncKey, [1]=hasMore, [2]=events, [3]=hasAnyCommands, [4]=status, [5]=deletedIds
            val result = deps.executeEasCommand("Sync", syncXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")?.toIntOrNull() ?: 1
                val newSyncKey = deps.extractValue(responseXml, "SyncKey")
                val hasMore = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                val events = if (status == 1) parseCalendarEvents(responseXml) else emptyList<EasCalendarEvent>()
                val deletedIds = mutableSetOf<String>()
                if (status == 1) {
                    DELETE_SERVER_ID_PATTERN.findAll(responseXml).forEach { m ->
                        m.groupValues[1].trim().takeIf { it.isNotBlank() }?.let { deletedIds.add(it) }
                    }
                }
                val hasAnyCommands = responseXml.contains("<Commands>")
                arrayOf<Any?>(newSyncKey, hasMore, events, hasAnyCommands, status, deletedIds)
            }
            
            when (result) {
                is EasResult.Success -> {
                    val arr = result.data
                    val status = arr[4] as Int
                    
                    when (status) {
                        1 -> { /* success — обработка ниже */ }
                        3 -> {
                            // MS-ASCMD §2.2.3.177.17: Invalid SyncKey — MUST return to SyncKey=0.
                            // Очищаем allEvents: пре-сбросные данные стейл, будут перевыгружены.
                            android.util.Log.w("EasCalendarService", "Calendar Sync Status=3: Invalid SyncKey, resetting (discarding ${allEvents.size} pre-reset events)")
                            allEvents.clear()
                            emptyDataCount = 0
                            val resetResult = deps.executeEasCommand("Sync", calendarSyncInitialXml(calendarFolderId)) { responseXml ->
                                deps.extractValue(responseXml, "SyncKey") ?: "0"
                            }
                            if (resetResult is EasResult.Success && resetResult.data != "0") {
                                syncKey = resetResult.data
                                previousSyncKey = syncKey
                                sameKeyCount = 0
                            } else {
                                moreAvailable = false
                            }
                            consecutiveErrors++
                            continue
                        }
                        12 -> {
                            // MS-ASCMD: Folder hierarchy changed — выходим, вызовет повторную синхронизацию
                            android.util.Log.w("EasCalendarService", "Calendar Sync Status=12: Folder hierarchy changed")
                            moreAvailable = false
                            continue
                        }
                        else -> {
                            android.util.Log.w("EasCalendarService", "Calendar Sync Status=$status")
                            consecutiveErrors++
                            if (consecutiveErrors >= maxConsecutiveErrors) {
                                return if (allEvents.isNotEmpty()) EasResult.Success(CalendarSyncResult(allEvents, allDeletedIds))
                                else EasResult.Error("Calendar Sync failed: Status=$status")
                            }
                            kotlinx.coroutines.delay(500L * consecutiveErrors)
                            continue
                        }
                    }
                    
                    consecutiveErrors = 0
                    val newKey = arr[0] as? String
                    if (newKey != null) syncKey = newKey
                    moreAvailable = arr[1] as Boolean
                    
                    @Suppress("UNCHECKED_CAST")
                    val events = arr[2] as List<EasCalendarEvent>
                    val hasAnyCommands = arr[3] as Boolean
                    @Suppress("UNCHECKED_CAST")
                    val deletedIds = arr[5] as Set<String>
                    allEvents.addAll(events)
                    allDeletedIds.addAll(deletedIds)
                    
                    android.util.Log.d("EasCalendarService", 
                        "syncCalendarEasLoop: iteration=$iterations, parsed=${events.size}, total=${allEvents.size}, hasMore=$moreAvailable, hasCommands=$hasAnyCommands")
                    
                    if (syncKey == previousSyncKey) {
                        sameKeyCount++
                        if (sameKeyCount >= 5) {
                            android.util.Log.w("EasCalendarService", "SyncKey not changing for 5 iterations, breaking")
                            moreAvailable = false
                        }
                    } else {
                        sameKeyCount = 0
                        emptyDataCount = 0
                        previousSyncKey = syncKey
                    }
                    
                    if (moreAvailable && events.isEmpty() && !hasAnyCommands) {
                        emptyDataCount++
                        if (emptyDataCount >= 5) {
                            android.util.Log.w("EasCalendarService", "No commands for $emptyDataCount iterations, breaking")
                            moreAvailable = false
                        }
                    } else {
                        emptyDataCount = 0
                    }
                }
                is EasResult.Error -> {
                    consecutiveErrors++
                    android.util.Log.w("EasCalendarService", "Calendar sync batch error #$consecutiveErrors: ${result.message}")
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        return if (allEvents.isNotEmpty()) {
                            EasResult.Success(CalendarSyncResult(allEvents, allDeletedIds))
                        } else {
                            result
                        }
                    }
                    kotlinx.coroutines.delay(500L * consecutiveErrors)
                }
            }
        }
        
        return EasResult.Success(CalendarSyncResult(allEvents, allDeletedIds))
    }

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
        val calendarFolderId = getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")
        
        // Получаем актуальный SyncKey (DRY: общий метод для create/update/delete)
        val syncKeyResult = getAdvancedSyncKey(calendarFolderId)
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return syncKeyResult
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        val clientId = UUID.randomUUID().toString().replace("-", "").take(32)
        val startTimeStr = formatEasDate(startTime)
        val endTimeStr = formatEasDate(endTime)
        
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
            append("<SyncKey>$syncKey</SyncKey>")
            append("<CollectionId>$calendarFolderId</CollectionId>")
            append("<Commands><Add>")
            append("<ClientId>$clientId</ClientId>")
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
            
            val tzBlob = if (allDayEvent) android.util.Base64.encodeToString(ByteArray(172), android.util.Base64.NO_WRAP) else buildDeviceTimezoneBlob()
            append("<calendar:TimeZone>$tzBlob</calendar:TimeZone>")
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
            
            val recurrenceXml = buildEasRecurrenceXml(recurrenceType, startTime)
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
                val responsesBlock = RESPONSES_PATTERN.find(responseXml)?.groupValues?.get(1)
                val serverIdFromResponses = if (responsesBlock != null) {
                    ADD_PATTERN.findAll(responsesBlock)
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
        val calendarFolderId = getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")
        
        // Получаем актуальный SyncKey (DRY: общий метод для create/update/delete)
        val syncKeyResult = getAdvancedSyncKey(calendarFolderId)
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return syncKeyResult
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        val startTimeStr = formatEasDate(startTime)
        val endTimeStr = formatEasDate(endTime)
        
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
            append("<SyncKey>$syncKey</SyncKey>")
            append("<CollectionId>$calendarFolderId</CollectionId>")
            append("<Commands><Change>")
            append("<ServerId>$serverId</ServerId>")
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
                val tzBlob = if (allDayEvent) android.util.Base64.encodeToString(ByteArray(172), android.util.Base64.NO_WRAP) else buildDeviceTimezoneBlob()
                append("<calendar:TimeZone>$tzBlob</calendar:TimeZone>")
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
                
                val recurrenceXml = buildEasRecurrenceXml(recurrenceType, startTime)
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
                val changeStatusMatch = CHANGE_STATUS_PATTERN.find(responseXml)
                
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
        // Получаем актуальный SyncKey (DRY: общий метод для create/update/delete)
        var syncKeyResult = getAdvancedSyncKey(calendarFolderId)
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
            android.util.Log.w("EasCalendarService", "Delete failed, retrying with full SyncKey reset for serverId=$serverId")
            
            syncKeyResult = getAdvancedSyncKey(calendarFolderId)
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <DeletesAsMoves>0</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>$serverId</ServerId>
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
                    val deleteStatusMatch = DELETE_STATUS_PATTERN.find(responseXml)
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
    
    // === EWS методы (Exchange 2007) ===
    
    private suspend fun syncCalendarEws(): EasResult<List<EasCalendarEvent>> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                syncCalendarEwsNtlm(ewsUrl)
            } catch (e: Exception) {
                EasResult.Error("Ошибка синхронизации календаря: ${e.message}")
            }
        }
    }
    
    private suspend fun syncCalendarEwsNtlm(ewsUrl: String): EasResult<List<EasCalendarEvent>> {
        val findItemRequest = EasXmlTemplates.ewsFindCalendarItems()
        
        val responseResult = ewsRequest(ewsUrl, findItemRequest, "FindItem")
        if (responseResult is EasResult.Error) return EasResult.Error(responseResult.message)
        val response = (responseResult as EasResult.Success).data

        // КРИТИЧНО: EWS может вернуть SOAP Fault / ErrorResponseCode с HTTP 200.
        // Раньше это превращалось в "успех + 0 событий", что выглядело как сломанный парсинг.
        val faultText = EWS_RESPONSE_CODE.find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (faultText != null && !faultText.equals("NoError", ignoreCase = true)) {
            return EasResult.Error("EWS FindItem error: $faultText")
        }
        
        return try {
            val events = parseEwsCalendarEvents(response)

            val totalItemsInView = "TotalItemsInView=\"(\\d+)\""
                .toRegex()
                .find(response)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            if (totalItemsInView > 0 && events.isEmpty()) {
                return EasResult.Error("EWS parse mismatch: TotalItemsInView=$totalItemsInView, parsed=0")
            }

            // FindItem+CalendarView НЕ возвращает <t:Attachments> — только <t:HasAttachments>.
            // Для событий с вложениями нужен GetItem, чтобы получить метаданные вложений.
            val needAttachments = events.filter { it.hasAttachments && it.attachments.isBlank() }
            if (needAttachments.isNotEmpty()) {
                android.util.Log.d("EasCalendarService",
                    "syncCalendarEwsNtlm: ${needAttachments.size} events need attachment fetch via GetItem")
                val attachmentMap = fetchCalendarAttachmentsEws(ewsUrl, needAttachments.map { it.serverId })
                val updatedEvents = events.map { event ->
                    val att = attachmentMap[event.serverId]
                    if (!att.isNullOrBlank()) event.copy(attachments = att) else event
                }
                EasResult.Success(updatedEvents)
            } else {
                EasResult.Success(events)
            }
        } catch (e: Exception) {
            EasResult.Error("Ошибка парсинга календаря: ${e.message}")
        }
    }
    
    /**
     * GetItem для событий с HasAttachments=true, у которых FindItem не вернул данные вложений.
     * Exchange 2007 SP1: FindItem+CalendarView возвращает только HasAttachments флаг,
     * но НЕ Attachments коллекцию. GetItem с item:Attachments возвращает метаданные.
     */
    private suspend fun fetchCalendarAttachmentsEws(
        ewsUrl: String,
        itemIds: List<String>
    ): Map<String, String> {
        if (itemIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()

        for (batch in itemIds.chunked(50)) {
            val itemIdsXml = batch.joinToString("") {
                """<t:ItemId Id="${deps.escapeXml(it)}"/>"""
            }
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
                android.util.Log.w("EasCalendarService",
                    "fetchCalendarAttachmentsEws: batch error: ${(responseResult).message}")
                continue
            }
            val responseXml = (responseResult as EasResult.Success).data
            android.util.Log.d("EasCalendarService",
                "fetchCalendarAttachmentsEws: GetItem response len=${responseXml.length}, first 600: ${responseXml.take(600)}")

            val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
                .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (match in itemPattern.findAll(responseXml)) {
                val itemXml = match.groupValues[1]
                val itemId = "<(?:t:)?ItemId\\b[^>]*\\bId=\"([^\"]+)\""
                    .toRegex(RegexOption.DOT_MATCHES_ALL)
                    .find(itemXml)?.groupValues?.getOrNull(1) ?: continue
                val attachmentsJson = parseEwsAttachments(itemXml)
                if (attachmentsJson.isNotBlank()) {
                    result[itemId] = attachmentsJson
                }
            }
        }

        android.util.Log.d("EasCalendarService",
            "fetchCalendarAttachmentsEws: fetched attachments for ${result.size}/${itemIds.size} events")
        return result
    }

    /**
     * Дополняет вложения для событий, где EAS Sync не вернул attachment data.
     * Exchange 2007 SP1 EAS Sync может пропускать <airsyncbase:Attachments>.
     * Использует EWS FindItem+CalendarView для обнаружения HasAttachments,
     * затем GetItem для получения метаданных вложений.
     * Матчинг EAS↔EWS: по Subject + StartTime (IDs несовместимы).
     */
    private suspend fun supplementAttachmentsViaEws(
        events: List<EasCalendarEvent>
    ): List<EasCalendarEvent> {
        val noAttach = events.filter { it.attachments.isBlank() }
        if (noAttach.isEmpty()) return events

        val ewsUrl = try {
            deps.getEwsUrl()
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
            android.util.Log.w("EasCalendarService", "supplementAttachmentsViaEws: FindItem failed: ${(findResult).message}")
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
            val startMs = deps.parseEasDate(startVal) ?: 0L
            val hasAtt = xml.contains("<t:HasAttachments>true</t:HasAttachments>")
                    || xml.contains("<HasAttachments>true</HasAttachments>")
            ewsItems.add(EwsItem(id, subj, startMs, hasAtt))
        }

        val withAtt = ewsItems.filter { it.hasAtt }
        if (withAtt.isEmpty()) {
            android.util.Log.d("EasCalendarService", "supplementAttachmentsViaEws: no EWS events have attachments")
            return events
        }

        android.util.Log.d("EasCalendarService",
            "supplementAttachmentsViaEws: ${withAtt.size} EWS events have attachments, fetching via GetItem")

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
        val result = events.map { event ->
            if (event.attachments.isNotBlank()) return@map event
            val key = matchKey(event.subject, event.startTime)
            val att = keyToAtt[key]
                ?: subjToAtt[event.subject.trim().lowercase()]
            if (att != null) {
                supplemented++
                event.copy(attachments = att)
            } else event
        }

        android.util.Log.d("EasCalendarService",
            "supplementAttachmentsViaEws: supplemented $supplemented/${noAttach.size} events with attachments")
        return result
    }

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
                
                val startTimeStr = if (allDayEvent) formatEwsAllDayDate(startTime) else formatEwsDate(startTime)
                val endTimeStr = if (allDayEvent) formatEwsAllDayDate(endTime) else formatEwsDate(endTime)
                
                val ewsBusyStatus = mapBusyStatusToEws(busyStatus)
                
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
                    val ewsRecurrenceXml = buildEwsRecurrenceXml(recurrenceType, startTimeStr)
                    if (ewsRecurrenceXml.isNotBlank()) append(ewsRecurrenceXml)
                    if (!allDayEvent) append(buildMeetingTimeZoneXml())
                    append("</t:CalendarItem>")
                    append("</Items>")
                    append("</CreateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                android.util.Log.d("EasCalendarService", "createCalendarEventEws: Request: $soapRequest")
                
                val createResult = ewsRequest(ewsUrl, soapRequest, "CreateItem")
                if (createResult is EasResult.Error) return@withContext createResult
                val responseXml = (createResult as EasResult.Success).data
                
                android.util.Log.d("EasCalendarService", "createCalendarEventEws: response length=${responseXml.length}, first 300: ${responseXml.take(300)}")
                
                // Проверяем на ошибки
                if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                    return@withContext EasResult.Error("Ошибка схемы EWS")
                }
                
                val idMatch = EWS_ITEM_ID.find(responseXml)
                val itemId = idMatch?.groupValues?.get(1)
                    ?: EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                val changeKey = idMatch?.groupValues?.get(2)
                android.util.Log.d("EasCalendarService", "createCalendarEventEws: itemId=${itemId?.take(40)}, changeKey=${changeKey?.take(20)}")
                
                // КРИТИЧНО: Проверяем ResponseClass и ResponseCode (namespace-tolerant)
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val responseCode = EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()
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
                
                val startTimeStr = if (allDayEvent) formatEwsAllDayDate(startTime) else formatEwsDate(startTime)
                val endTimeStr = if (allDayEvent) formatEwsAllDayDate(endTime) else formatEwsDate(endTime)
                
                val ewsBusyStatus = mapBusyStatusToEws(busyStatus)
                
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
                    // КРИТИЧНО: Добавляем ChangeKey если есть!
                    if (changeKey != null) {
                        append("""<t:ItemId Id="$itemId" ChangeKey="$changeKey"/>""")
                    } else {
                        append("""<t:ItemId Id="$itemId"/>""")
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
                    val ewsRecurrenceUpdateXml = buildEwsRecurrenceUpdateXml(recurrenceType, startTimeStr)
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
                val responseCode = EWS_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()
                
                if (hasSuccess && (responseCode == "NoError" || responseCode == null)) {
                    EasResult.Success(true)
                } else {
                    EasResult.Error("Ошибка обновления события: $responseCode")
                }
            } catch (e: Exception) {
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
        sendCancellations: String = "SendToNone"
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val deleteBody = """
                    <m:DeleteItem DeleteType="HardDelete" SendMeetingCancellations="$sendCancellations">
                        <m:ItemIds>
                            <t:ItemId Id="${deps.escapeXml(serverId)}"/>
                        </m:ItemIds>
                    </m:DeleteItem>
                """.trimIndent()
                val request = EasXmlTemplates.ewsSoapRequest(deleteBody)
                
                val deleteResult = ewsRequest(ewsUrl, request, "DeleteItem")
                if (deleteResult is EasResult.Error) return@withContext deleteResult
                val response = (deleteResult as EasResult.Success).data
                
                val responseCode = EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)?.trim()
                val hasSuccess = response.contains("ResponseClass=\"Success\"")
                when {
                    responseCode == "NoError" -> EasResult.Success(true)
                    responseCode == "ErrorItemNotFound" -> {
                        android.util.Log.w("EasCalendarService",
                            "deleteCalendarEventEws: ErrorItemNotFound for serverId (len=${serverId.length}). " +
                            "Event may have been already deleted or ItemId is stale.")
                        EasResult.Success(true)
                    }
                    hasSuccess && responseCode == null -> EasResult.Success(true)
                    else -> EasResult.Error("EWS DeleteItem: $responseCode")
                }
            } catch (e: Exception) {
                EasResult.Error("Ошибка удаления события: ${e.message}")
            }
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
    private suspend fun declineCalendarEventEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                // Шаг 1: DeclineItem — уведомляем организатора (предотвращает CRA resurrection)
                val declineBody = """
                    <m:CreateItem MessageDisposition="SendAndSaveCopy">
                        <m:Items>
                            <t:DeclineItem>
                                <t:ReferenceItemId Id="${deps.escapeXml(serverId)}"/>
                            </t:DeclineItem>
                        </m:Items>
                    </m:CreateItem>
                """.trimIndent()
                val request = EasXmlTemplates.ewsSoapRequest(declineBody)
                // DeclineItem — best-effort: ошибка не блокирует HardDelete
                runCatching { ewsRequest(ewsUrl, request, "CreateItem") }
                
                // Шаг 2: HardDelete — гарантируем физическое удаление из календаря
                val deleteBody = """
                    <m:DeleteItem DeleteType="HardDelete" SendMeetingCancellations="SendToNone">
                        <m:ItemIds>
                            <t:ItemId Id="${deps.escapeXml(serverId)}"/>
                        </m:ItemIds>
                    </m:DeleteItem>
                """.trimIndent()
                val deleteRequest = EasXmlTemplates.ewsSoapRequest(deleteBody)
                val delResult = ewsRequest(ewsUrl, deleteRequest, "DeleteItem")
                if (delResult is EasResult.Error) return@withContext delResult
                val deleteResponse = (delResult as EasResult.Success).data
                val responseCode = EWS_RESPONSE_CODE.find(deleteResponse)?.groupValues?.get(1)?.trim()
                val delHasSuccess = deleteResponse.contains("ResponseClass=\"Success\"")
                when {
                    responseCode == "NoError" || responseCode == "ErrorItemNotFound" -> EasResult.Success(true)
                    delHasSuccess && responseCode == null -> EasResult.Success(true)
                    else -> EasResult.Error("EWS DeleteItem: $responseCode")
                }
            } catch (e: Exception) {
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
        <CollectionId>$calendarFolderId</CollectionId>
        <RequestId>$serverId</RequestId>
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
                        val itemStartMs = parseEwsDateTime(startMatch)
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
                EasResult.Error("Failed to find ItemId: ${e.message}")
            }
        }
    }
    
    // === Парсинг ===
    
    private fun parseCalendarEvents(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()
        
        for (match in ADD_OR_CHANGE_PATTERN.findAll(xml)) {
            val eventXml = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (eventXml.isEmpty()) continue
            
            val serverId = deps.extractValue(eventXml, "ServerId") ?: continue
            
            val dataMatch = APP_DATA_PATTERN.find(eventXml) ?: continue
            val dataXml = dataMatch.groupValues[1]
            
            val subject = extractCalendarValue(dataXml, "Subject") ?: ""
            val location = extractCalendarValue(dataXml, "Location") ?: ""
            val body = extractCalendarBody(dataXml)
            
            val startTimeStr = extractCalendarValue(dataXml, "StartTime")
            val endTimeStr = extractCalendarValue(dataXml, "EndTime")
            
            val startTime = deps.parseEasDate(startTimeStr) ?: 0L
            val endTime = deps.parseEasDate(endTimeStr) ?: 0L
            
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
            val lastModified = deps.parseEasDate(extractCalendarValue(dataXml, "DtStamp")) ?: System.currentTimeMillis()
            
            val uid = extractCalendarValue(dataXml, "UID") ?: ""
            val timezone = extractCalendarValue(dataXml, "Timezone") ?: ""
            val meetingStatusVal = extractCalendarValue(dataXml, "MeetingStatus")?.toIntOrNull() ?: 0
            val isMeeting = meetingStatusVal == 1 || meetingStatusVal == 3 || meetingStatusVal == 5 || meetingStatusVal == 7
            val responseRequested = extractCalendarValue(dataXml, "ResponseRequested") == "1"
            val responseType = extractCalendarValue(dataXml, "ResponseType")?.toIntOrNull() ?: 0
            val appointmentReplyTime = deps.parseEasDate(extractCalendarValue(dataXml, "AppointmentReplyTime")) ?: 0L
            val disallowNewTimeProposal = extractCalendarValue(dataXml, "DisallowNewTimeProposal") == "1"
            val onlineMeetingLink = extractCalendarValue(dataXml, "OnlineMeetingExternalLink") 
                ?: extractCalendarValue(dataXml, "OnlineMeetingConfLink") ?: ""
            
            // Парсинг вложений (airsyncbase:Attachments)
            val hasAttachments = dataXml.contains("<Attachments>") || dataXml.contains("<airsyncbase:Attachments>")
            val attachmentsJson = if (hasAttachments) parseEasAttachments(dataXml) else ""
            
            // Парсинг правила повторения и исключений
            val recurrenceRuleJson = if (isRecurring) parseEasRecurrence(dataXml) else ""
            val exceptionsJson = if (isRecurring) parseEasExceptions(dataXml) else ""
            
            events.add(
                EasCalendarEvent(
                    serverId = serverId,
                    subject = subject,
                    startTime = startTime,
                    endTime = endTime,
                    location = location,
                    body = body,
                    allDayEvent = allDayEvent,
                    reminder = reminder,
                    busyStatus = busyStatus,
                    sensitivity = sensitivity,
                    organizer = organizerEmail,
                    organizerName = organizerName,
                    attendees = parseAttendees(dataXml),
                    categories = categories,
                    isRecurring = isRecurring,
                    recurrenceRule = recurrenceRuleJson,
                    lastModified = lastModified,
                    uid = uid,
                    timezone = timezone,
                    exceptions = exceptionsJson,
                    isMeeting = isMeeting,
                    meetingStatus = meetingStatusVal,
                    responseStatus = responseType,
                    responseRequested = responseRequested,
                    appointmentReplyTime = appointmentReplyTime,
                    disallowNewTimeProposal = disallowNewTimeProposal,
                    onlineMeetingLink = onlineMeetingLink,
                    hasAttachments = hasAttachments,
                    attachments = attachmentsJson
                )
            )
        }
        
        return events
    }
    
    private fun parseEwsCalendarEvents(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()
        val itemPattern = "<(?:t:)?CalendarItem\\b[^>]*>(.*?)</(?:t:)?CalendarItem>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        
        for (match in itemPattern.findAll(xml)) {
            val itemXml = match.groupValues[1]
            
            val itemId = "<(?:t:)?ItemId\\b[^>]*\\bId=\"([^\"]+)\""
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?: continue
            val subject = "<(?:t:)?Subject>(.*?)</(?:t:)?Subject>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?: ""
            val rawBody = "<(?:t:)?Body[^>]*>(.*?)</(?:t:)?Body>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?: ""
            val body = removeDuplicateLines(unescapeXml(rawBody))
            
            val startStr = "<(?:t:)?Start>(.*?)</(?:t:)?Start>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
            val endStr = "<(?:t:)?End>(.*?)</(?:t:)?End>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
            
            val startTime = parseEwsDateTime(startStr) ?: 0L
            val endTime = parseEwsDateTime(endStr) ?: 0L
            
            val location = "<(?:t:)?Location>(.*?)</(?:t:)?Location>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?: ""
            val isAllDay = "<(?:t:)?IsAllDayEvent>(.*?)</(?:t:)?IsAllDayEvent>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.equals("true", ignoreCase = true)
                ?: false
            val organizerMatch = "<(?:t:)?Organizer>.*?<(?:t:)?Mailbox>(.*?)</(?:t:)?Mailbox>.*?</(?:t:)?Organizer>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
            val organizerMailbox = organizerMatch?.groupValues?.get(1) ?: ""
            val organizer = "<(?:t:)?EmailAddress>(.*?)</(?:t:)?EmailAddress>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(organizerMailbox)
                ?.groupValues
                ?.getOrNull(1)
                ?: ""
            val organizerName = "<(?:t:)?Name>(.*?)</(?:t:)?Name>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(organizerMailbox)
                ?.groupValues
                ?.getOrNull(1)
                ?: ""
            val isRecurring = itemXml.contains("<t:Recurrence>")
                || itemXml.contains("<Recurrence>")
                || itemXml.contains("<t:IsRecurring>true</t:IsRecurring>")
                || itemXml.contains("<IsRecurring>true</IsRecurring>")
            
            // Парсим lastModified из EWS
            val lastModifiedStr = "<(?:t:)?LastModifiedTime>(.*?)</(?:t:)?LastModifiedTime>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
            val lastModified = parseEwsDateTime(lastModifiedStr) ?: System.currentTimeMillis()
            
            // Reminder, BusyStatus, Sensitivity
            val reminder = "<(?:t:)?ReminderMinutesBeforeStart>(.*?)</(?:t:)?ReminderMinutesBeforeStart>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            val reminderSet = "<(?:t:)?ReminderIsSet>(.*?)</(?:t:)?ReminderIsSet>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.equals("true", ignoreCase = true)
                ?: false
            val ewsBusyStr = "<(?:t:)?LegacyFreeBusyStatus>(.*?)</(?:t:)?LegacyFreeBusyStatus>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?: "Busy"
            val busyStatus = when (ewsBusyStr) { "Free" -> 0; "Tentative" -> 1; "Busy" -> 2; "OOF" -> 3; "WorkingElsewhere" -> 4; else -> 2 }
            val ewsSensStr = "<(?:t:)?Sensitivity>(.*?)</(?:t:)?Sensitivity>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?: "Normal"
            val sensitivity = when (ewsSensStr) { "Normal" -> 0; "Personal" -> 1; "Private" -> 2; "Confidential" -> 3; else -> 0 }
            
            // UID
            val uid = "<(?:t:)?UID>(.*?)</(?:t:)?UID>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?: ""
            
            // HasAttachments и парсинг вложений EWS
            val hasAttachments = "<(?:t:)?HasAttachments>(.*?)</(?:t:)?HasAttachments>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.equals("true", ignoreCase = true)
                ?: false
            val ewsAttachmentsJson = if (hasAttachments) parseEwsAttachments(itemXml) else ""
            
            // MeetingStatus: IsMeeting + IsCancelled
            val isMeeting = "<(?:t:)?IsMeeting>(.*?)</(?:t:)?IsMeeting>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.equals("true", ignoreCase = true)
                ?: false
            val isCancelled = "<(?:t:)?IsCancelled>(.*?)</(?:t:)?IsCancelled>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.equals("true", ignoreCase = true)
                ?: false
            val meetingStatus = if (isCancelled) 5 else if (isMeeting) 1 else 0
            
            // ResponseRequested, DisallowNewTimeProposal
            val responseRequested = "<(?:t:)?IsResponseRequested>(.*?)</(?:t:)?IsResponseRequested>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.equals("true", ignoreCase = true)
                ?: false
            val disallowNewTime = "<(?:t:)?AllowNewTimeProposal>(.*?)</(?:t:)?AllowNewTimeProposal>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?.equals("false", ignoreCase = true)
                ?: false
            
            // ResponseType (MyResponseType)
            val ewsResponseStr = "<(?:t:)?MyResponseType>(.*?)</(?:t:)?MyResponseType>"
                .toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(itemXml)
                ?.groupValues
                ?.getOrNull(1)
                ?: "Unknown"
            val responseStatus = when (ewsResponseStr) { "Unknown" -> 0; "NoResponseReceived" -> 1; "Accept" -> 2; "Tentative" -> 3; "Decline" -> 4; else -> 0 }
            
            // Парсим участников EWS
            val ewsAttendees = parseEwsAttendees(itemXml)
            
            // Парсим категории EWS (namespace-tolerant: t:Categories или Categories)
            val categories = mutableListOf<String>()
            val ewsCategoriesMatch = EWS_CATEGORIES_PATTERN.find(itemXml)
            if (ewsCategoriesMatch != null) {
                EWS_STRING_PATTERN.findAll(ewsCategoriesMatch.groupValues[1]).forEach { catMatch ->
                    categories.add(catMatch.groupValues[1].trim())
                }
            }
            
            // Парсинг правила повторения для EWS
            val ewsRecurrenceRule = if (isRecurring) parseEwsRecurrence(itemXml) else ""
            
            events.add(
                EasCalendarEvent(
                    serverId = itemId,
                    subject = subject,
                    startTime = startTime,
                    endTime = endTime,
                    location = location,
                    body = body,
                    allDayEvent = isAllDay,
                    reminder = if (reminderSet) reminder else 0,
                    busyStatus = busyStatus,
                    sensitivity = sensitivity,
                    organizer = organizer,
                    organizerName = organizerName,
                    attendees = ewsAttendees,
                    categories = categories,
                    isRecurring = isRecurring,
                    recurrenceRule = ewsRecurrenceRule,
                    lastModified = lastModified,
                    uid = uid,
                    isMeeting = isMeeting,
                    meetingStatus = meetingStatus,
                    responseStatus = responseStatus,
                    responseRequested = responseRequested,
                    disallowNewTimeProposal = disallowNewTime,
                    hasAttachments = hasAttachments,
                    attachments = ewsAttachmentsJson
                )
            )
        }
        
        return events
    }
    
    private fun extractCalendarValue(xml: String, tag: String): String? {
        // Пробуем оба варианта: с namespace и без (как в старой версии)
        val patterns = listOf(
            "<calendar:$tag>(.*?)</calendar:$tag>",
            "<$tag>(.*?)</$tag>"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }
    
    /**
     * Извлекает тело события из различных форматов XML
     */
    private fun extractCalendarBody(xml: String): String {
        val bodyPatterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<calendar:Body>.*?<Data>(.*?)</Data>.*?</calendar:Body>",
            "<calendar:Body>(.*?)</calendar:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                val rawBody = unescapeXml(match.groupValues[1].trim())
                if (rawBody.isNotBlank()) return removeDuplicateLines(rawBody)
            }
        }
        return ""
    }

    /**
     * Декодирует XML entities (&lt;, &gt;, &quot;, &amp;, &apos;)
     */
    private fun unescapeXml(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
    
    /**
     * Извлекает категории события
     */
    private fun extractCalendarCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesMatch = EAS_CATEGORIES_PATTERN.find(xml)
        if (categoriesMatch != null) {
            EAS_CATEGORY_PATTERN.findAll(categoriesMatch.groupValues[1]).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
        }
        return categories
    }
    
    /**
     * Удаляет дублированные строки из текста (проблема Exchange при синхронизации)
     */
    private fun removeDuplicateLines(text: String): String {
        val normalized = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?div[^>]*>", RegexOption.IGNORE_CASE), "\n")
        
        val lines = normalized.lines()
        val seen = mutableSetOf<String>()
        return lines.filter { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) true else seen.add(trimmed)
        }.joinToString("\n")
    }
    
    private fun parseAttendees(xml: String): List<EasAttendee> {
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
    
    /**
     * Парсит вложения из EAS Sync ответа (airsyncbase:Attachments)
     * Возвращает JSON массив: [{name, fileReference, size, isInline, contentId}]
     */
    private fun parseEasAttachments(xml: String): String {
        val attachments = mutableListOf<String>()
        
        for (match in ATTACHMENT_PATTERN.findAll(xml)) {
            val attXml = match.groupValues[1]
            val displayName = extractAirsyncValue(attXml, "DisplayName") ?: "attachment"
            val fileReference = extractAirsyncValue(attXml, "FileReference") ?: ""
            val estimatedSize = extractAirsyncValue(attXml, "EstimatedDataSize")?.toLongOrNull() ?: 0L
            val isInline = extractAirsyncValue(attXml, "IsInline") == "1"
            val contentId = extractAirsyncValue(attXml, "ContentId") ?: ""
            val method = extractAirsyncValue(attXml, "Method")?.toIntOrNull() ?: 1
            
            // Method: 1=NormalAttachment, 5=EmbeddedMessage, 6=OLE
            val escapedName = escapeJsonString(displayName)
            val escapedRef = escapeJsonString(fileReference)
            val escapedCid = escapeJsonString(contentId)
            attachments.add("""{"name":"$escapedName","fileReference":"$escapedRef","size":$estimatedSize,"isInline":$isInline,"contentId":"$escapedCid","method":$method}""")
        }
        
        return if (attachments.isEmpty()) "" else "[${attachments.joinToString(",")}]"
    }
    
    private fun extractAirsyncValue(xml: String, tag: String): String? {
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
    
    /**
     * Парсит правило повторения из EAS XML (MS-ASCAL Recurrence)
     * Возвращает JSON: {"type","interval","dayOfWeek","dayOfMonth","weekOfMonth","monthOfYear","until","occurrences","firstDayOfWeek"}
     */
    private fun parseEasRecurrence(xml: String): String {
        val recMatch = EAS_RECURRENCE_PATTERN.find(xml) ?: return ""
        val recXml = recMatch.groupValues[1]
        
        val type = extractCalendarValue(recXml, "Type")?.toIntOrNull() ?: 0
        val interval = extractCalendarValue(recXml, "Interval")?.toIntOrNull() ?: 1
        val dayOfWeek = extractCalendarValue(recXml, "DayOfWeek")?.toIntOrNull() ?: 0
        val dayOfMonth = extractCalendarValue(recXml, "DayOfMonth")?.toIntOrNull() ?: 0
        val weekOfMonth = extractCalendarValue(recXml, "WeekOfMonth")?.toIntOrNull() ?: 0
        val monthOfYear = extractCalendarValue(recXml, "MonthOfYear")?.toIntOrNull() ?: 0
        val until = deps.parseEasDate(extractCalendarValue(recXml, "Until")) ?: 0L
        val occurrences = extractCalendarValue(recXml, "Occurrences")?.toIntOrNull() ?: 0
        val firstDayOfWeek = extractCalendarValue(recXml, "FirstDayOfWeek")?.toIntOrNull() ?: 0
        
        return """{"type":$type,"interval":$interval,"dayOfWeek":$dayOfWeek,"dayOfMonth":$dayOfMonth,"weekOfMonth":$weekOfMonth,"monthOfYear":$monthOfYear,"until":$until,"occurrences":$occurrences,"firstDayOfWeek":$firstDayOfWeek}"""
    }
    
    /**
     * Формирует EAS <Recurrence> XML блок для создания/обновления события.
     * @param recurrenceType -1=нет, 0=Daily, 1=Weekly, 2=Monthly, 5=Yearly
     * @param startTime Время начала события (для определения дня недели/месяца)
     * @return XML строка или пустая строка если повторения нет
     */
    private fun buildEasRecurrenceXml(recurrenceType: Int, startTime: Long): String {
        if (recurrenceType < 0) return ""
        
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = startTime
        
        return buildString {
            append("<calendar:Recurrence>")
            append("<calendar:Type>$recurrenceType</calendar:Type>")
            append("<calendar:Interval>1</calendar:Interval>")
            
            when (recurrenceType) {
                0 -> { /* Daily */ }
                1 -> {
                    val dayBitmask = 1 shl (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1)
                    append("<calendar:DayOfWeek>$dayBitmask</calendar:DayOfWeek>")
                }
                2 -> {
                    append("<calendar:DayOfMonth>${cal.get(java.util.Calendar.DAY_OF_MONTH)}</calendar:DayOfMonth>")
                }
                5 -> {
                    append("<calendar:DayOfMonth>${cal.get(java.util.Calendar.DAY_OF_MONTH)}</calendar:DayOfMonth>")
                    append("<calendar:MonthOfYear>${cal.get(java.util.Calendar.MONTH) + 1}</calendar:MonthOfYear>")
                }
            }
            
            append("</calendar:Recurrence>")
        }
    }
    
    /**
     * Формирует EWS <Recurrence> XML блок для CreateItem.
     * Внутри CalendarItem с xmlns, элементы без префикса t:
     */
    private fun buildEwsRecurrenceXml(recurrenceType: Int, startTimeStr: String): String {
        if (recurrenceType < 0) return ""
        
        val localCal = localCalFromIso(startTimeStr)
        val startDate = localDateFromIso(startTimeStr)
        
        return buildString {
            append("<Recurrence>")
            
            when (recurrenceType) {
                0 -> {
                    append("<DailyRecurrence>")
                    append("<Interval>1</Interval>")
                    append("</DailyRecurrence>")
                }
                1 -> {
                    val ewsDayName = ewsDayNameFromIso(startTimeStr)
                    append("<WeeklyRecurrence>")
                    append("<Interval>1</Interval>")
                    append("<DaysOfWeek>$ewsDayName</DaysOfWeek>")
                    append("</WeeklyRecurrence>")
                }
                2 -> {
                    val dayOfMonth = localCal?.get(java.util.Calendar.DAY_OF_MONTH) ?: 1
                    append("<AbsoluteMonthlyRecurrence>")
                    append("<Interval>1</Interval>")
                    append("<DayOfMonth>$dayOfMonth</DayOfMonth>")
                    append("</AbsoluteMonthlyRecurrence>")
                }
                5 -> {
                    val dayOfMonth = localCal?.get(java.util.Calendar.DAY_OF_MONTH) ?: 1
                    val monthNum = localCal?.let { it.get(java.util.Calendar.MONTH) + 1 } ?: 1
                    val monthName = ewsMonthName(monthNum)
                    append("<AbsoluteYearlyRecurrence>")
                    append("<DayOfMonth>$dayOfMonth</DayOfMonth>")
                    append("<Month>$monthName</Month>")
                    append("</AbsoluteYearlyRecurrence>")
                }
            }
            
            append("<NoEndRecurrence>")
            append("<StartDate>$startDate</StartDate>")
            append("</NoEndRecurrence>")
            append("</Recurrence>")
        }
    }
    
    /**
     * Формирует EWS SetItemField для Recurrence в UpdateItem.
     * Генерирует XML напрямую с t: префиксом (без хрупкой цепочки .replace()).
     */
    private fun buildEwsRecurrenceUpdateXml(recurrenceType: Int, startTimeStr: String): String {
        if (recurrenceType < 0) return ""
        
        val localCal = localCalFromIso(startTimeStr)
        val startDate = localDateFromIso(startTimeStr)
        
        return buildString {
            append("<t:SetItemField>")
            append("""<t:FieldURI FieldURI="calendar:Recurrence"/>""")
            append("<t:CalendarItem>")
            append("<t:Recurrence>")
            
            when (recurrenceType) {
                0 -> {
                    append("<t:DailyRecurrence>")
                    append("<t:Interval>1</t:Interval>")
                    append("</t:DailyRecurrence>")
                }
                1 -> {
                    val ewsDayName = ewsDayNameFromIso(startTimeStr)
                    append("<t:WeeklyRecurrence>")
                    append("<t:Interval>1</t:Interval>")
                    append("<t:DaysOfWeek>$ewsDayName</t:DaysOfWeek>")
                    append("</t:WeeklyRecurrence>")
                }
                2 -> {
                    val dayOfMonth = localCal?.get(java.util.Calendar.DAY_OF_MONTH) ?: 1
                    append("<t:AbsoluteMonthlyRecurrence>")
                    append("<t:Interval>1</t:Interval>")
                    append("<t:DayOfMonth>$dayOfMonth</t:DayOfMonth>")
                    append("</t:AbsoluteMonthlyRecurrence>")
                }
                5 -> {
                    val dayOfMonth = localCal?.get(java.util.Calendar.DAY_OF_MONTH) ?: 1
                    val monthNum = localCal?.let { it.get(java.util.Calendar.MONTH) + 1 } ?: 1
                    val monthName = ewsMonthName(monthNum)
                    append("<t:AbsoluteYearlyRecurrence>")
                    append("<t:DayOfMonth>$dayOfMonth</t:DayOfMonth>")
                    append("<t:Month>$monthName</t:Month>")
                    append("</t:AbsoluteYearlyRecurrence>")
                }
            }
            
            append("<t:NoEndRecurrence>")
            append("<t:StartDate>$startDate</t:StartDate>")
            append("</t:NoEndRecurrence>")
            append("</t:Recurrence>")
            append("</t:CalendarItem>")
            append("</t:SetItemField>")
        }
    }
    
    /** Форматирование timestamp → EWS ISO 8601 UTC строку (yyyy-MM-dd'T'HH:mm:ss'Z') */
    private fun formatEwsDate(timestamp: Long): String {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return df.format(java.util.Date(timestamp))
    }

    /**
     * Для all-day событий EWS: извлекает дату из UTC-midnight timestamp
     * и форматирует как "YYYY-MM-DDT00:00:00" БЕЗ суффикса Z.
     * Exchange интерпретирует время без Z в таймзоне почтового ящика,
     * корректно распознавая midnight→midnight как all-day.
     */
    private fun formatEwsAllDayDate(utcMidnightTimestamp: Long): String {
        val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utcCal.timeInMillis = utcMidnightTimestamp
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02dT00:00:00",
            utcCal.get(java.util.Calendar.YEAR),
            utcCal.get(java.util.Calendar.MONTH) + 1,
            utcCal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * Берёт дату (год/месяц/день) из local-timestamp, возвращает midnight UTC этой даты.
     * [addDay] = true → ещё +1 день (для End всех all-day событий).
     */
    private fun normalizeAllDayUtcMidnight(localTimestamp: Long, addDay: Boolean): Long {
        val local = java.util.Calendar.getInstance()
        local.timeInMillis = localTimestamp
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(
            local.get(java.util.Calendar.YEAR),
            local.get(java.util.Calendar.MONTH),
            local.get(java.util.Calendar.DAY_OF_MONTH),
            0, 0, 0
        )
        if (addDay) utc.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return utc.timeInMillis
    }
    
    /** Маппинг EAS busyStatus (Int) → EWS LegacyFreeBusyStatus (String) */
    private fun mapBusyStatusToEws(busyStatus: Int): String = when (busyStatus) {
        0 -> "Free"
        1 -> "Tentative"
        3 -> "OOF"
        else -> "Busy"
    }

    /**
     * EAS <calendar:TimeZone> blob from device timezone.
     * TIME_ZONE_INFORMATION struct (172 bytes): Bias + StandardName + StandardDate +
     * StandardBias + DaylightName + DaylightDate + DaylightBias.
     */
    private fun buildDeviceTimezoneBlob(): String {
        val tz = java.util.TimeZone.getDefault()
        val biasMinutes = -(tz.rawOffset / 60_000)
        val buf = java.nio.ByteBuffer.allocate(172).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(biasMinutes)
        repeat(32) { buf.putShort(0) } // StandardName (64 bytes)
        if (tz.useDaylightTime()) {
            try {
                val rules = java.time.ZoneId.systemDefault().rules
                val trRules = rules.transitionRules
                val toStd = trRules.firstOrNull { it.offsetAfter.totalSeconds < it.offsetBefore.totalSeconds }
                val toDst = trRules.firstOrNull { it.offsetAfter.totalSeconds > it.offsetBefore.totalSeconds }
                fun writeSystemTime(r: java.time.zone.ZoneOffsetTransitionRule?) {
                    if (r != null) {
                        buf.putShort(0)
                        buf.putShort(r.month.value.toShort())
                        buf.putShort((r.dayOfWeek.value % 7).toShort())
                        val week = if (r.dayOfMonthIndicator < 0) 5 else ((r.dayOfMonthIndicator - 1) / 7 + 1)
                        buf.putShort(week.toShort())
                        buf.putShort(r.localTime.hour.toShort())
                        buf.putShort(r.localTime.minute.toShort())
                        buf.putShort(0); buf.putShort(0)
                    } else repeat(8) { buf.putShort(0) }
                }
                writeSystemTime(toStd)
                buf.putInt(0) // StandardBias
                repeat(32) { buf.putShort(0) } // DaylightName
                writeSystemTime(toDst)
                buf.putInt(-(tz.dstSavings / 60_000)) // DaylightBias
            } catch (_: Exception) {
                while (buf.hasRemaining()) buf.put(0)
            }
        } else {
            repeat(8) { buf.putShort(0) } // StandardDate
            buf.putInt(0) // StandardBias
            repeat(32) { buf.putShort(0) } // DaylightName
            repeat(8) { buf.putShort(0) } // DaylightDate
            buf.putInt(0) // DaylightBias
        }
        return android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP)
    }

    /**
     * EWS <MeetingTimeZone> XML for Exchange 2007 SP1.
     * Uses full BaseOffset + Standard/Daylight elements (works on both RTM and SP1).
     */
    private fun buildMeetingTimeZoneXml(): String {
        val tz = java.util.TimeZone.getDefault()
        val rawMinutes = tz.rawOffset / 60_000
        val biasMinutes = -rawMinutes
        val absH = kotlin.math.abs(rawMinutes) / 60
        val absM = kotlin.math.abs(rawMinutes) % 60
        val sign = if (rawMinutes >= 0) "+" else "-"
        val displayName = "(UTC${sign}${String.format(java.util.Locale.US, "%02d:%02d", absH, absM)})"

        fun durationStr(min: Int): String {
            val s = if (min < 0) "-" else ""
            val a = kotlin.math.abs(min)
            return "${s}P0DT${a / 60}H${a % 60}M0.0S"
        }

        val sb = StringBuilder()
        sb.append("<t:MeetingTimeZone TimeZoneName=\"$displayName\">")
        sb.append("<t:BaseOffset>${durationStr(biasMinutes)}</t:BaseOffset>")
        if (tz.useDaylightTime()) {
            try {
                val rules = java.time.ZoneId.systemDefault().rules
                val trRules = rules.transitionRules
                val toStd = trRules.firstOrNull { it.offsetAfter.totalSeconds < it.offsetBefore.totalSeconds }
                val toDst = trRules.firstOrNull { it.offsetAfter.totalSeconds > it.offsetBefore.totalSeconds }
                fun weekIndex(day: Int) = when {
                    day < 0 -> "Last"; day <= 7 -> "First"; day <= 14 -> "Second"
                    day <= 21 -> "Third"; day <= 28 -> "Fourth"; else -> "Last"
                }
                fun dayName(d: java.time.DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }
                fun monthName(m: java.time.Month) = m.name.lowercase().replaceFirstChar { it.uppercase() }
                if (toStd != null) {
                    sb.append("<t:Standard><t:Offset>P0DT0H0M0.0S</t:Offset>")
                    sb.append("<t:RelativeYearlyRecurrence>")
                    sb.append("<t:DaysOfWeek>${dayName(toStd.dayOfWeek)}</t:DaysOfWeek>")
                    sb.append("<t:DayOfWeekIndex>${weekIndex(toStd.dayOfMonthIndicator)}</t:DayOfWeekIndex>")
                    sb.append("<t:Month>${monthName(toStd.month)}</t:Month>")
                    sb.append("</t:RelativeYearlyRecurrence>")
                    sb.append("<t:Time>${toStd.localTime}:00.0000000</t:Time></t:Standard>")
                }
                if (toDst != null) {
                    sb.append("<t:Daylight><t:Offset>${durationStr(-(tz.dstSavings / 60_000))}</t:Offset>")
                    sb.append("<t:RelativeYearlyRecurrence>")
                    sb.append("<t:DaysOfWeek>${dayName(toDst.dayOfWeek)}</t:DaysOfWeek>")
                    sb.append("<t:DayOfWeekIndex>${weekIndex(toDst.dayOfMonthIndicator)}</t:DayOfWeekIndex>")
                    sb.append("<t:Month>${monthName(toDst.month)}</t:Month>")
                    sb.append("</t:RelativeYearlyRecurrence>")
                    sb.append("<t:Time>${toDst.localTime}:00.0000000</t:Time></t:Daylight>")
                }
            } catch (_: Exception) { /* omit Standard/Daylight on error */ }
        }
        sb.append("</t:MeetingTimeZone>")
        return sb.toString()
    }

    /**
     * Удаление одного вхождения повторяющегося события через EWS.
     * Находит occurrence по CalendarView, затем DeleteItem.
     */
    suspend fun deleteSingleOccurrenceEws(
        searchSubject: String,
        occurrenceStartTime: Long
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

                for (itemMatch in itemPattern.findAll(findResponse)) {
                    val itemXml = itemMatch.groupValues[1]
                    val id = idPattern.find(itemXml)?.groupValues?.get(1) ?: continue
                    val subj = subjectPat.find(itemXml)?.groupValues?.get(1) ?: ""
                    val startMs = parseEwsDateTime(startPat.find(itemXml)?.groupValues?.get(1)) ?: continue
                    val diff = kotlin.math.abs(startMs - occurrenceStartTime)
                    if (diff > oneDayMs) continue
                    if (subj.equals(searchSubject, ignoreCase = true) && diff < bestTimeDiff) {
                        bestItemId = id
                        bestTimeDiff = diff
                    }
                }

                if (bestItemId == null) {
                    return@withContext EasResult.Error("Occurrence not found via EWS CalendarView")
                }

                deleteCalendarEventEws(bestItemId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: "Error deleting occurrence")
            }
        }
    }
    
    private fun localCalFromIso(isoDate: String): java.util.Calendar? {
        return try {
            val df: java.text.SimpleDateFormat
            if (isoDate.endsWith("Z")) {
                df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                df.timeZone = java.util.TimeZone.getTimeZone("UTC")
            } else {
                df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            }
            val date = df.parse(isoDate) ?: return null
            java.util.Calendar.getInstance().apply { time = date }
        } catch (e: Exception) { null }
    }
    
    private fun localDateFromIso(isoDate: String): String {
        val cal = localCalFromIso(isoDate) ?: return isoDate.substringBefore("T")
        return String.format("%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
    
    private fun ewsDayNameFromIso(isoDate: String): String {
        val cal = localCalFromIso(isoDate) ?: return "Monday"
        return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SUNDAY -> "Sunday"
            java.util.Calendar.MONDAY -> "Monday"
            java.util.Calendar.TUESDAY -> "Tuesday"
            java.util.Calendar.WEDNESDAY -> "Wednesday"
            java.util.Calendar.THURSDAY -> "Thursday"
            java.util.Calendar.FRIDAY -> "Friday"
            java.util.Calendar.SATURDAY -> "Saturday"
            else -> "Monday"
        }
    }
    
    /** Номер месяца (1-12) → EWS имя месяца */
    private fun ewsMonthName(month: Int): String = when (month) {
        1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
        5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
        9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"
        else -> "January"
    }
    
    /**
     * Парсит правило повторения из EWS XML (t:Recurrence)
     * Конвертирует EWS-формат в единый JSON формат (совместимый с EAS)
     */
    private fun parseEwsRecurrence(xml: String): String {
        // Namespace-tolerant: match both <t:Recurrence> and <Recurrence>
        val recPattern = "<(?:t:)?Recurrence>(.*?)</(?:t:)?Recurrence>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val recMatch = recPattern.find(xml) ?: return ""
        val recXml = recMatch.groupValues[1]
        
        // Namespace-tolerant helper: match both <t:TAG> and <TAG>
        fun ewsVal(tag: String) = "<(?:t:)?$tag>(.*?)</(?:t:)?$tag>".toRegex(RegexOption.DOT_MATCHES_ALL).find(recXml)?.groupValues?.get(1)
        
        // Определяем тип повторения
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
                dayOfWeek = ewsDaysOfWeekToBitmask(ewsVal("DaysOfWeek") ?: "")
                firstDayOfWeek = ewsDayNameToNumber(ewsVal("FirstDayOfWeek") ?: "")
            }
            recXml.contains("AbsoluteMonthlyRecurrence>") -> {
                type = 2
                interval = ewsVal("Interval")?.toIntOrNull() ?: 1
                dayOfMonth = ewsVal("DayOfMonth")?.toIntOrNull() ?: 0
            }
            recXml.contains("RelativeMonthlyRecurrence>") -> {
                type = 3
                interval = ewsVal("Interval")?.toIntOrNull() ?: 1
                dayOfWeek = ewsDaysOfWeekToBitmask(ewsVal("DaysOfWeek") ?: "")
                weekOfMonth = ewsWeekIndexToNumber(ewsVal("DayOfWeekIndex") ?: "")
            }
            recXml.contains("AbsoluteYearlyRecurrence>") -> {
                type = 5
                dayOfMonth = ewsVal("DayOfMonth")?.toIntOrNull() ?: 0
                monthOfYear = ewsMonthToNumber(ewsVal("Month") ?: "")
            }
            recXml.contains("RelativeYearlyRecurrence>") -> {
                type = 6
                dayOfWeek = ewsDaysOfWeekToBitmask(ewsVal("DaysOfWeek") ?: "")
                weekOfMonth = ewsWeekIndexToNumber(ewsVal("DayOfWeekIndex") ?: "")
                monthOfYear = ewsMonthToNumber(ewsVal("Month") ?: "")
            }
            else -> return ""
        }
        
        // End condition — namespace-tolerant
        var until = 0L
        var occurrences = 0
        val endDateStr = "<(?:t:)?EndDate>(.*?)</(?:t:)?EndDate>".toRegex(RegexOption.DOT_MATCHES_ALL).find(recXml)?.groupValues?.get(1)
        if (endDateStr != null) {
            until = parseEwsDateTime("${endDateStr}T23:59:59Z") ?: 0L
        }
        val occStr = "<(?:t:)?NumberOfOccurrences>(.*?)</(?:t:)?NumberOfOccurrences>".toRegex(RegexOption.DOT_MATCHES_ALL).find(recXml)?.groupValues?.get(1)
        if (occStr != null) {
            occurrences = occStr.toIntOrNull() ?: 0
        }
        
        return """{"type":$type,"interval":$interval,"dayOfWeek":$dayOfWeek,"dayOfMonth":$dayOfMonth,"weekOfMonth":$weekOfMonth,"monthOfYear":$monthOfYear,"until":$until,"occurrences":$occurrences,"firstDayOfWeek":$firstDayOfWeek}"""
    }
    
    /** EWS DaysOfWeek строка → bitmask (Sun=1,Mon=2,Tue=4,...,Sat=64) */
    private fun ewsDaysOfWeekToBitmask(days: String): Int {
        var mask = 0
        if (days.contains("Sunday")) mask = mask or 1
        if (days.contains("Monday")) mask = mask or 2
        if (days.contains("Tuesday")) mask = mask or 4
        if (days.contains("Wednesday")) mask = mask or 8
        if (days.contains("Thursday")) mask = mask or 16
        if (days.contains("Friday")) mask = mask or 32
        if (days.contains("Saturday")) mask = mask or 64
        return mask
    }
    
    /** EWS день недели → номер (0=Sun, 1=Mon, ..., 6=Sat) */
    private fun ewsDayNameToNumber(name: String): Int = when (name) {
        "Sunday" -> 0; "Monday" -> 1; "Tuesday" -> 2; "Wednesday" -> 3
        "Thursday" -> 4; "Friday" -> 5; "Saturday" -> 6; else -> 0
    }
    
    /** EWS DayOfWeekIndex → число (First=1, ..., Last=5) */
    private fun ewsWeekIndexToNumber(index: String): Int = when (index) {
        "First" -> 1; "Second" -> 2; "Third" -> 3; "Fourth" -> 4; "Last" -> 5; else -> 0
    }
    
    /** EWS Month → число (January=1, ..., December=12) */
    private fun ewsMonthToNumber(month: String): Int = when (month) {
        "January" -> 1; "February" -> 2; "March" -> 3; "April" -> 4
        "May" -> 5; "June" -> 6; "July" -> 7; "August" -> 8
        "September" -> 9; "October" -> 10; "November" -> 11; "December" -> 12
        else -> 0
    }
    
    /**
     * Парсит исключения из повторяющихся событий (MS-ASCAL Exceptions)
     * Возвращает JSON массив: [{startTime, deleted, subject, location, ...}]
     */
    private fun parseEasExceptions(xml: String): String {
        val exceptions = mutableListOf<String>()
        
        for (match in EAS_EXCEPTION_PATTERN.findAll(xml)) {
            val exXml = match.groupValues[1]
            val exStartTime = deps.parseEasDate(extractCalendarValue(exXml, "ExceptionStartTime")) ?: 0L
            val deleted = extractCalendarValue(exXml, "Deleted") == "1"
            val subject = escapeJsonString(extractCalendarValue(exXml, "Subject") ?: "")
            val location = escapeJsonString(extractCalendarValue(exXml, "Location") ?: "")
            val startTime = deps.parseEasDate(extractCalendarValue(exXml, "StartTime")) ?: 0L
            val endTime = deps.parseEasDate(extractCalendarValue(exXml, "EndTime")) ?: 0L
            val body = escapeJsonString(extractExceptionBody(exXml))
            
            exceptions.add("""{"exceptionStartTime":$exStartTime,"deleted":$deleted,"subject":"$subject","location":"$location","startTime":$startTime,"endTime":$endTime,"body":"$body"}""")
        }
        
        return if (exceptions.isEmpty()) "" else "[${exceptions.joinToString(",")}]"
    }

    private fun extractExceptionBody(exXml: String): String {
        val patterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<calendar:Body>.*?<Data>(.*?)</Data>.*?</calendar:Body>",
            "<calendar:Body>(.*?)</calendar:Body>"
        )
        for (pattern in patterns) {
            val match = pattern.toRegex(RegexOption.DOT_MATCHES_ALL).find(exXml)
            if (match != null && match.groupValues[1].isNotBlank()) return removeDuplicateLines(unescapeXml(match.groupValues[1].trim()))
        }
        return ""
    }
    
    /**
     * Парсит вложения из EWS ответа (t:Attachments)
     * Возвращает JSON массив: [{name, fileReference, size, isInline, contentId}]
     */
    private fun parseEwsAttachments(xml: String): String {
        val attachments = mutableListOf<String>()
        // Namespace-tolerant: match both <t:FileAttachment> and <FileAttachment>
        val attPattern = "<(?:t:)?(?:FileAttachment|ItemAttachment)>(.*?)</(?:t:)?(?:FileAttachment|ItemAttachment)>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (match in attPattern.findAll(xml)) {
            val attXml = match.groupValues[1]
            val attId = "<(?:t:)?AttachmentId[^>]*\\bId=\"([^\"]+)\"".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) ?: ""
            val name = "<(?:t:)?Name>(.*?)</(?:t:)?Name>".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) ?: "attachment"
            val size = "<(?:t:)?Size>(.*?)</(?:t:)?Size>".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val isInline = "<(?:t:)?IsInline>(.*?)</(?:t:)?IsInline>".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) == "true"
            val contentId = "<(?:t:)?ContentId>(.*?)</(?:t:)?ContentId>".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) ?: ""
            
            val escapedName = escapeJsonString(name)
            val escapedRef = escapeJsonString(attId)
            val escapedCid = escapeJsonString(contentId)
            attachments.add("""{"name":"$escapedName","fileReference":"$escapedRef","size":$size,"isInline":$isInline,"contentId":"$escapedCid","method":1}""")
        }
        
        return if (attachments.isEmpty()) "" else "[${attachments.joinToString(",")}]"
    }
    
    /**
     * Парсит участников из EWS CalendarItem
     */
    private fun parseEwsAttendees(xml: String): List<EasAttendee> {
        val attendees = mutableListOf<EasAttendee>()
        
        // Required, Optional, Resources attendees (namespace-tolerant: t: prefix optional)
        val listsPattern = "<(?:t:)?(?:RequiredAttendees|OptionalAttendees|Resources)>(.*?)</(?:t:)?(?:RequiredAttendees|OptionalAttendees|Resources)>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (listMatch in listsPattern.findAll(xml)) {
            val listXml = listMatch.groupValues[1]
            val attendeePattern = "<(?:t:)?Attendee>(.*?)</(?:t:)?Attendee>".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            for (attMatch in attendeePattern.findAll(listXml)) {
                val attXml = attMatch.groupValues[1]
                val email = "<(?:t:)?EmailAddress>(.*?)</(?:t:)?EmailAddress>".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) ?: continue
                val name = "<(?:t:)?Name>(.*?)</(?:t:)?Name>".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) ?: ""
                val responseStr = "<(?:t:)?ResponseType>(.*?)</(?:t:)?ResponseType>".toRegex(RegexOption.DOT_MATCHES_ALL).find(attXml)?.groupValues?.get(1) ?: "Unknown"
                val status = when (responseStr) { "Unknown" -> 0; "Tentative" -> 2; "Accept" -> 3; "Decline" -> 4; "NoResponseReceived" -> 5; else -> 0 }
                
                attendees.add(EasAttendee(email, name, status))
            }
        }
        
        return attendees
    }
    
    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    private fun parseEwsDateTime(dateStr: String?): Long? {
        if (dateStr.isNullOrEmpty()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(dateStr.replace("Z", ""))?.time
        } catch (e: Exception) {
            null
        }
    }

    // === Удаление вложений событий календаря через EWS DeleteAttachment ===

    suspend fun deleteCalendarAttachments(attachmentIds: List<String>): EasResult<Boolean> {
        if (attachmentIds.isEmpty()) return EasResult.Success(true)
        
        val ewsUrl = deps.getEwsUrl()
        var lastError: String? = null
        
        for (attId in attachmentIds) {
            if (attId.isBlank()) continue
            try {
                val request = buildDeleteAttachmentRequest(attId)
                val result = ewsRequest(ewsUrl, request, "DeleteAttachment")
                if (result is EasResult.Error) {
                    android.util.Log.w("EasCalendarService", "DeleteAttachment failed for ${attId.take(30)}: ${result.message}")
                    lastError = result.message
                } else {
                    val response = (result as EasResult.Success).data
                    if (response.contains("ResponseClass=\"Error\"")) {
                        val errMsg = EasPatterns.EWS_MESSAGE_TEXT.find(response)?.groupValues?.get(1) ?: "Unknown"
                        android.util.Log.w("EasCalendarService", "DeleteAttachment error for ${attId.take(30)}: $errMsg")
                        lastError = errMsg
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("EasCalendarService", "DeleteAttachment exception for ${attId.take(30)}: ${e.message}")
                lastError = e.message
            }
        }
        
        return if (lastError != null && attachmentIds.size == 1) {
            EasResult.Error(lastError)
        } else {
            EasResult.Success(true)
        }
    }
    
    private fun buildDeleteAttachmentRequest(attachmentId: String): String {
        val escapedId = deps.escapeXml(attachmentId)
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
    
    // === Загрузка вложений к событиям календаря через EWS CreateAttachment ===

    internal suspend fun attachFilesEws(
        ewsUrl: String,
        itemId: String,
        changeKey: String?,
        attachments: List<DraftAttachmentData>,
        exchangeVersion: String
    ): EasResult<String> = withContext(Dispatchers.IO) {
        android.util.Log.d("EasCalendarService", "attachFilesEws: ENTRY, itemId=${itemId.take(40)}, changeKey=${changeKey?.take(20)}, attachments=${attachments.size}, version=$exchangeVersion")
        if (attachments.isEmpty()) return@withContext EasResult.Success("")

        var currentChangeKey = changeKey
        val attachmentInfos = mutableListOf<String>()

        for ((index, att) in attachments.withIndex()) {
            val request = buildCreateAttachmentRequest(
                itemId, currentChangeKey, att, exchangeVersion
            )

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
                android.util.Log.e("EasCalendarService",
                    "CreateAttachment FAILED [${index + 1}/${attachments.size}] ${att.name}: $details")
                return@withContext EasResult.Error(
                    "Вложение '${att.name}' не загружено: $details"
                )
            }

            if (!response.contains("ResponseClass=\"Success\"")) {
                android.util.Log.e("EasCalendarService",
                    "CreateAttachment NO SUCCESS [${index + 1}/${attachments.size}] ${att.name}")
                return@withContext EasResult.Error(
                    "Нет подтверждения загрузки вложения '${att.name}'"
                )
            }

            // Извлекаем AttachmentId из ответа (namespace-tolerant: t: необязателен)
            val attachmentId = "<(?:t:)?AttachmentId[^>]*\\bId=\"([^\"]+)\"".toRegex()
                .find(response)?.groupValues?.get(1) ?: ""

            // КРИТИЧНО: Exchange возвращает RootItemChangeKey — именно его нужно использовать
            // для следующего CreateAttachment запроса, иначе Exchange 2007 SP1 вернёт
            // ErrorItemSave или ErrorChangeKeyRequired при загрузке второго вложения.
            // Fallback ограничен контекстом AttachmentId, чтобы не захватить ChangeKey из других элементов.
            val newChangeKey = """RootItemChangeKey="([^"]+)"""".toRegex()
                .find(response)?.groupValues?.get(1)
                ?: "<(?:t:)?AttachmentId[^>]*\\bChangeKey=\"([^\"]+)\"".toRegex().find(response)?.groupValues?.get(1)
            if (newChangeKey != null) {
                currentChangeKey = newChangeKey
            }

            val escapedName = att.name.replace("\"", "\\\"")
            val escapedRef = attachmentId.replace("\"", "\\\"")
            attachmentInfos.add("""{"name":"$escapedName","fileReference":"$escapedRef","size":${att.data.size},"isInline":false}""")

            android.util.Log.d("EasCalendarService",
                "CreateAttachment OK [${index + 1}/${attachments.size}] ${att.name}, attId=${attachmentId.take(20)}...")
        }

        EasResult.Success("[${attachmentInfos.joinToString(",")}]")
    }

    private fun buildCreateAttachmentRequest(
        itemId: String,
        changeKey: String?,
        att: DraftAttachmentData,
        exchangeVersion: String
    ): String {
        val escapedItemId = deps.escapeXml(itemId)
        val changeKeyAttr = changeKey?.takeIf { it.isNotBlank() }?.let { " ChangeKey=\"${deps.escapeXml(it)}\"" } ?: ""
        val name = deps.escapeXml(att.name)
        val contentType = deps.escapeXml(att.mimeType)
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
}

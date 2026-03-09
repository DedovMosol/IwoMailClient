package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.data.repository.RecurrenceHelper
import java.text.SimpleDateFormat
import java.util.*

/**
 * Facade for Exchange calendar operations (EAS/EWS).
 *
 * Delegates to extracted services (H-12 decomposition):
 * - [CalendarDateUtils] — date/time/timezone utilities
 * - [CalendarXmlParser] — EAS/EWS XML parsing
 * - [CalendarRecurrenceBuilder] — recurrence XML building
 * - [CalendarExceptionService] — recurring exception handling
 * - [CalendarAttachmentService] — EWS attachment CRUD
 * - [EasCalendarSyncService] — sync orchestration
 * - [EasCalendarCrudService] — create/update/delete operations
 *
 * All public/internal method signatures are preserved for backward compatibility.
 * CalendarRepository, CalendarScreen, etc. continue to call EasCalendarService.
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
class EasCalendarService internal constructor(
    private val deps: CalendarServiceDependencies
) {

    interface EasCommandExecutor {
        suspend operator fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T>
    }

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

    data class CalendarSyncResult(
        val events: List<EasCalendarEvent>,
        val deletedServerIds: Set<String> = emptySet()
    )

    data class DeleteRequest(
        val serverId: String,
        val isMeeting: Boolean,
        val isOrganizer: Boolean,
        val isRecurringSeries: Boolean = false
    )

    data class RecurringEventInfo(
        val uid: String,
        val serverId: String,
        val currentExceptions: String
    )

    // === Service instances ===

    val xmlParser = CalendarXmlParser(
        parseEasDate = deps.parseEasDate,
        extractValue = deps.extractValue
    )

    val attachmentService = CalendarAttachmentService(
        ewsRequest = ::ewsRequest,
        parseEwsAttachments = xmlParser::parseEwsAttachments,
        escapeXml = deps.escapeXml,
        parseEasDate = deps.parseEasDate,
        getEwsUrl = deps.getEwsUrl
    )

    private val exceptionService = CalendarExceptionService(
        escapeXml = deps.escapeXml,
        formatEasDate = CalendarDateUtils::formatEasDate,
        ewsRequest = ::ewsRequest,
        getEwsUrl = deps.getEwsUrl
    )

    private val syncService = EasCalendarSyncService(
        deps = deps,
        xmlParser = xmlParser,
        attachmentService = attachmentService,
        ewsRequest = ::ewsRequest
    )

    private val crudService = EasCalendarCrudService(
        deps = deps,
        syncService = syncService,
        exceptionService = exceptionService,
        attachmentService = attachmentService,
        xmlParser = xmlParser,
        ewsRequest = ::ewsRequest
    )

    // === EWS Auth (shared by all services via lambda) ===

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

    // === Sync API ===

    suspend fun syncCalendar(): EasResult<CalendarSyncResult> =
        syncService.syncCalendar()

    // === CRUD API ===

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
    ): EasResult<String> = crudService.createCalendarEvent(
        subject, startTime, endTime, location, body, allDayEvent,
        reminder, busyStatus, sensitivity, attendees, recurrenceType, attachments
    )

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
    ): EasResult<String> = crudService.updateCalendarEvent(
        serverId, subject, startTime, endTime, location, body, allDayEvent,
        reminder, busyStatus, sensitivity, attendees, oldSubject,
        recurrenceType, attachments, newAttendeesToAppend
    )

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
    ): EasResult<String> = crudService.updateSingleOccurrence(
        serverId, existingExceptionsJson, occurrenceOriginalStartTime, masterSubject,
        subject, startTime, endTime, location, body, allDayEvent,
        reminder, busyStatus, sensitivity, attachments, removedAttachmentIds
    )

    suspend fun deleteCalendarEvent(
        serverId: String,
        isMeeting: Boolean = false,
        isOrganizer: Boolean = false,
        isRecurringSeries: Boolean = false
    ): EasResult<Boolean> = crudService.deleteCalendarEvent(
        serverId, isMeeting, isOrganizer, isRecurringSeries
    )

    suspend fun deleteCalendarEventsBatch(
        requests: List<DeleteRequest>
    ): EasResult<Int> = crudService.deleteCalendarEventsBatch(requests)

    suspend fun deleteSingleOccurrenceEws(
        searchSubject: String,
        occurrenceStartTime: Long,
        isMeeting: Boolean = false,
        isOrganizer: Boolean = false
    ): EasResult<Boolean> = crudService.deleteSingleOccurrenceEws(
        searchSubject, occurrenceStartTime, isMeeting, isOrganizer
    )

    // === Exception supplement ===

    suspend fun supplementRecurringExceptionsViaEws(
        localRecurringEvents: List<RecurringEventInfo>
    ): Map<String, String> = exceptionService.supplementRecurringExceptionsViaEws(localRecurringEvents)

    // === Attachment delegates ===

    suspend fun deleteCalendarAttachments(attachmentIds: List<String>): EasResult<Boolean> =
        attachmentService.deleteCalendarAttachments(attachmentIds)

    internal suspend fun attachFilesEws(
        ewsUrl: String,
        itemId: String,
        changeKey: String?,
        attachments: List<DraftAttachmentData>,
        exchangeVersion: String
    ): EasResult<String> = attachmentService.attachFilesEws(ewsUrl, itemId, changeKey, attachments, exchangeVersion)
}

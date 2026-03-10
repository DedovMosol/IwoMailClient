package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calendar synchronization — EAS Sync loop + EWS FindItem+CalendarView fallback.
 *
 * Extracted from EasCalendarService (Phase 5 of H-12 decomposition).
 *
 * Owns the only shared mutable state: [cachedCalendarFolderId] (@Volatile).
 *
 * EAS Sync: MS-ASCMD 2.2.1.21
 *   - Status=3: Invalid SyncKey → MUST reset to SyncKey=0 (MS-ASCMD 2.2.3.177.17)
 *   - Status=12: Folder hierarchy changed → exit loop, caller retries
 *   - GetChanges=1 with SyncKey=0 → Status=4 (MS-ASCMD 2.2.3.84) — calendarSyncInitialXml omits GetChanges
 *   - WindowSize=100: optimal for mobile (MS-ASCMD 2.2.3.199)
 *
 * EWS: FindItem+CalendarView expands recurring events. Exchange 2007 SP1 compatible.
 *   CalendarView does NOT return t:Attachments — only t:HasAttachments. GetItem required for metadata.
 *
 * Compatibility: Exchange 2007 SP1 / EAS 12.1 / EWS
 */
class EasCalendarSyncService(
    private val deps: EasCalendarService.CalendarServiceDependencies,
    private val xmlParser: CalendarXmlParser,
    private val attachmentService: CalendarAttachmentService,
    private val ewsRequest: suspend (String, String, String) -> EasResult<String>
) {

    @Volatile
    var cachedCalendarFolderId: String? = null

    suspend fun syncCalendar(): EasResult<EasCalendarService.CalendarSyncResult> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }

        android.util.Log.d("EasCalendarSyncService", "syncCalendar: EAS version = ${deps.getEasVersion()}")

        val foldersResult = deps.folderSync("0")
        val folders = when (foldersResult) {
            is EasResult.Success -> foldersResult.data.folders
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }

        val easResult = syncCalendarEasFromFolders(folders)

        if (easResult is EasResult.Success && easResult.data.events.isNotEmpty()) {
            val withAttFix = try {
                attachmentService.supplementAttachmentsViaEws(easResult.data.events)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("EasCalendarSyncService", "supplementAttachmentsViaEws failed: ${e.message}")
                easResult.data.events
            }
            return EasResult.Success(
                EasCalendarService.CalendarSyncResult(
                    events = withAttFix,
                    deletedServerIds = easResult.data.deletedServerIds,
                    isAuthoritativeSnapshot = easResult.data.isAuthoritativeSnapshot
                )
            )
        }
        return easResult
    }

    private suspend fun syncCalendarEasFromFolders(
        folders: List<EasFolder>
    ): EasResult<EasCalendarService.CalendarSyncResult> {
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
            return EasResult.Success(
                EasCalendarService.CalendarSyncResult(
                    events = emptyList(),
                    isAuthoritativeSnapshot = false
                )
            )
        }

        return syncCalendarEasLoop(calendarFolderId, syncKey)
    }

    internal fun calendarSyncInitialXml(calendarFolderId: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>${deps.escapeXml(calendarFolderId)}</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()

    private suspend fun syncCalendarEasLoop(
        calendarFolderId: String,
        initialSyncKey: String
    ): EasResult<EasCalendarService.CalendarSyncResult> {
        var syncKey = initialSyncKey
        val allEvents = mutableListOf<EasCalendarEvent>()
        val allDeletedIds = mutableSetOf<String>()
        var moreAvailable = true
        var iterations = 0
        val maxIterations = 100
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 3
        val syncStartTime = System.currentTimeMillis()
        val maxSyncDurationMs = 300_000L
        var isAuthoritativeSnapshot = true
        var previousSyncKey = syncKey
        var sameKeyCount = 0
        var emptyDataCount = 0

        while (moreAvailable && iterations < maxIterations && consecutiveErrors < maxConsecutiveErrors) {
            iterations++

            if (System.currentTimeMillis() - syncStartTime > maxSyncDurationMs) {
                android.util.Log.w("EasCalendarSyncService", "Calendar sync timeout after $iterations iterations")
                isAuthoritativeSnapshot = false
                break
            }

            kotlinx.coroutines.yield()

            val syncXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(calendarFolderId)}</CollectionId>
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

            val result = deps.executeEasCommand("Sync", syncXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")?.toIntOrNull() ?: 1
                val newSyncKey = deps.extractValue(responseXml, "SyncKey")
                val hasMore = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                val events = if (status == 1) xmlParser.parseCalendarEvents(responseXml) else emptyList<EasCalendarEvent>()
                val deletedIds = mutableSetOf<String>()
                if (status == 1) {
                    CalendarXmlParser.DELETE_SERVER_ID_PATTERN.findAll(responseXml).forEach { m ->
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
                        1 -> { /* success */ }
                        3 -> {
                            android.util.Log.w("EasCalendarSyncService", "Calendar Sync Status=3: Invalid SyncKey, resetting (discarding ${allEvents.size} pre-reset events)")
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
                            android.util.Log.w("EasCalendarSyncService", "Calendar Sync Status=12: Folder hierarchy changed")
                            isAuthoritativeSnapshot = false
                            moreAvailable = false
                            continue
                        }
                        else -> {
                            android.util.Log.w("EasCalendarSyncService", "Calendar Sync Status=$status")
                            consecutiveErrors++
                            if (consecutiveErrors >= maxConsecutiveErrors) {
                                return if (allEvents.isNotEmpty()) {
                                    EasResult.Success(
                                        EasCalendarService.CalendarSyncResult(
                                            events = allEvents,
                                            deletedServerIds = allDeletedIds,
                                            isAuthoritativeSnapshot = false
                                        )
                                    )
                                }
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

                    android.util.Log.d("EasCalendarSyncService",
                        "syncCalendarEasLoop: iteration=$iterations, parsed=${events.size}, total=${allEvents.size}, hasMore=$moreAvailable, hasCommands=$hasAnyCommands")

                    if (syncKey == previousSyncKey) {
                        sameKeyCount++
                        if (sameKeyCount >= 5) {
                            android.util.Log.w("EasCalendarSyncService", "SyncKey not changing for 5 iterations, breaking")
                            isAuthoritativeSnapshot = false
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
                            android.util.Log.w("EasCalendarSyncService", "No commands for $emptyDataCount iterations, breaking")
                            isAuthoritativeSnapshot = false
                            moreAvailable = false
                        }
                    } else {
                        emptyDataCount = 0
                    }
                }
                is EasResult.Error -> {
                    consecutiveErrors++
                    android.util.Log.w("EasCalendarSyncService", "Calendar sync batch error #$consecutiveErrors: ${result.message}")
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        return if (allEvents.isNotEmpty()) {
                            EasResult.Success(
                                EasCalendarService.CalendarSyncResult(
                                    events = allEvents,
                                    deletedServerIds = allDeletedIds,
                                    isAuthoritativeSnapshot = false
                                )
                            )
                        } else {
                            result
                        }
                    }
                    kotlinx.coroutines.delay(500L * consecutiveErrors)
                }
            }
        }

        if (moreAvailable) {
            isAuthoritativeSnapshot = false
        }

        return EasResult.Success(
            EasCalendarService.CalendarSyncResult(
                events = allEvents,
                deletedServerIds = allDeletedIds,
                isAuthoritativeSnapshot = isAuthoritativeSnapshot
            )
        )
    }

    suspend fun getCalendarFolderId(): String? {
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

    suspend fun syncCalendarEws(): EasResult<List<EasCalendarEvent>> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                syncCalendarEwsNtlm(ewsUrl)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                EasResult.Error("Ошибка синхронизации календаря: ${e.message}")
            }
        }
    }

    private suspend fun syncCalendarEwsNtlm(
        ewsUrl: String
    ): EasResult<List<EasCalendarEvent>> {
        val findItemRequest = EasXmlTemplates.ewsFindCalendarItems()

        val responseResult = ewsRequest(ewsUrl, findItemRequest, "FindItem")
        if (responseResult is EasResult.Error) return EasResult.Error(responseResult.message)
        val response = (responseResult as EasResult.Success).data

        val faultText = CalendarXmlParser.EWS_RESPONSE_CODE.find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (faultText != null && !faultText.equals("NoError", ignoreCase = true)) {
            return EasResult.Error("EWS FindItem error: $faultText")
        }

        return try {
            val events = xmlParser.parseEwsCalendarEvents(response)

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

            val needAttachments = events.filter { it.hasAttachments && it.attachments.isBlank() }
            if (needAttachments.isNotEmpty()) {
                android.util.Log.d("EasCalendarSyncService",
                    "syncCalendarEwsNtlm: ${needAttachments.size} events need attachment fetch via GetItem")
                val attachmentMap = attachmentService.fetchCalendarAttachmentsEws(ewsUrl, needAttachments.map { it.serverId })
                val updatedEvents = events.map { event ->
                    val att = attachmentMap[event.serverId]
                    if (!att.isNullOrBlank()) event.copy(attachments = att) else event
                }
                EasResult.Success(updatedEvents)
            } else {
                EasResult.Success(events)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            EasResult.Error("Ошибка парсинга календаря: ${e.message}")
        }
    }

    /**
     * Advances SyncKey to current state for EAS write operations (Create/Update/Delete).
     *
     * GetChanges MUST be 1 for proper SyncKey advancement.
     * WindowSize=512 accelerates advancement (more elements per iteration).
     * Protected against infinite loops via sameAdvanceKeyCount and maxSyncIterations.
     */
    suspend fun getAdvancedSyncKey(calendarFolderId: String): EasResult<String> {
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
            val advanceXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(calendarFolderId)}</CollectionId>
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
                    if (syncKey == prevAdvanceKey) {
                        sameAdvanceKeyCount++
                        if (sameAdvanceKeyCount >= 3) {
                            android.util.Log.w("EasCalendarSyncService", "getAdvancedSyncKey: SyncKey stuck at $syncKey for 3 iterations, breaking")
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

        android.util.Log.d("EasCalendarSyncService", "SyncKey advanced in $syncIterations iterations, syncKey=$syncKey")
        return EasResult.Success(syncKey)
    }
}

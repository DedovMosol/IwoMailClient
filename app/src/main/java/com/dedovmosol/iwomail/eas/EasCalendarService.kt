package com.dedovmosol.iwomail.eas

import android.util.Base64
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
    suspend fun syncCalendar(): EasResult<List<EasCalendarEvent>> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        android.util.Log.d("EasCalendarService", "syncCalendar: EAS version = ${deps.getEasVersion()}")
        
        val foldersResult = deps.folderSync("0")
        val folders = when (foldersResult) {
            is EasResult.Success -> foldersResult.data.folders
            is EasResult.Error -> return EasResult.Error(foldersResult.message)
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        // EAS 14+ (Exchange 2010+) — Standard sync
        // EAS 12.x (Exchange 2007) — EWS primary (обходит ограничение EAS на некоторые IPM.Appointment),
        //                            EAS Legacy как fallback
        return if (majorVersion >= 14) {
            syncCalendarStandard(folders)
        } else {
            val ewsResult = syncCalendarEws()
            if (ewsResult is EasResult.Success) {
                ewsResult
            } else {
                android.util.Log.w("EasCalendarService", "EWS calendar sync failed, falling back to EAS Legacy: ${(ewsResult as EasResult.Error).message}")
                syncCalendarLegacy(folders)
            }
        }
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
        
        // При наличии вложений всегда используем EWS (EAS не поддерживает загрузку вложений в календарь)
        val result = if (attachments.isNotEmpty() || majorVersion < 14) {
            createCalendarEventEws(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, recurrenceType)
        } else {
            createCalendarEventEas(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, recurrenceType)
        }
        
        // Загружаем вложения после успешного создания
        if (attachments.isNotEmpty() && result is EasResult.Success) {
            val rawId = result.data
            if (!rawId.startsWith("pending_sync_")) {
                val ewsUrl = deps.getEwsUrl()
                val cleanItemId = if (rawId.contains("|")) rawId.substringBefore("|") else rawId
                val changeKey = if (rawId.contains("|")) rawId.substringAfter("|") else null
                val attachResult = attachFilesEws(ewsUrl, cleanItemId, changeKey, attachments, "Exchange2007_SP1")
                if (attachResult is EasResult.Error) {
                    android.util.Log.e("EasCalendarService", "Событие создано, но вложения не загружены: ${attachResult.message}")
                    return EasResult.Error("Событие создано, но вложения не загружены: ${attachResult.message}")
                }
                // Возвращаем "cleanItemId\nattachmentsJson" — Repository разберёт
                val attachJson = (attachResult as EasResult.Success).data
                return EasResult.Success("$cleanItemId\n$attachJson")
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
        attachments: List<DraftAttachmentData> = emptyList()
    ): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        val result = if (majorVersion >= 14) {
            updateCalendarEventEas(serverId, subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, recurrenceType)
        } else {
            updateCalendarEventEws(serverId, subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, oldSubject, recurrenceType)
        }
        
        if (result is EasResult.Error) return EasResult.Error(result.message)
        
        // Загружаем новые вложения после успешного обновления
        if (attachments.isNotEmpty()) {
            val ewsUrl = deps.getEwsUrl()
            val isEasServerId = serverId.contains(":") && serverId.length < 20
            val ewsItemIdResult = if (isEasServerId) {
                findCalendarItemIdBySubject(subject)
            } else {
                EasResult.Success(serverId)
            }
            if (ewsItemIdResult is EasResult.Success) {
                val rawId = ewsItemIdResult.data
                val cleanItemId = if (rawId.contains("|")) rawId.substringBefore("|") else rawId
                val changeKey = if (rawId.contains("|")) rawId.substringAfter("|") else null
                val attachResult = attachFilesEws(ewsUrl, cleanItemId, changeKey, attachments, "Exchange2007_SP1")
                if (attachResult is EasResult.Error) {
                    android.util.Log.e("EasCalendarService", "Событие обновлено, но вложения не загружены: ${attachResult.message}")
                    return EasResult.Error("Событие обновлено, но вложения не загружены: ${attachResult.message}")
                }
                return EasResult.Success((attachResult as EasResult.Success).data)
            } else {
                val errMsg = (ewsItemIdResult as EasResult.Error).message
                android.util.Log.e("EasCalendarService", "Не удалось найти EWS ItemId для загрузки вложений: $errMsg")
                return EasResult.Error("Не удалось загрузить вложения: $errMsg")
            }
        }
        
        return EasResult.Success("")
    }
    
    /**
     * Удаление события календаря
     * КРИТИЧНО: Exchange 2007 SP1 синхронизирует календарь через EWS (syncCalendarEws),
     * поэтому serverId — EWS ItemId (длинная base64 строка).
     * EAS Sync Delete ожидает КОРОТКИЙ EAS ServerId (формат "22:2").
     * Маршрутизируем удаление по формату serverId на Exchange 2007.
     */
    suspend fun deleteCalendarEvent(serverId: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            // Exchange 2010+: всегда EAS Sync Delete (serverId всегда EAS формат)
            val calendarFolderId = getCalendarFolderId()
                ?: return EasResult.Error("Папка календаря не найдена")
            deleteCalendarEventEas(serverId, calendarFolderId)
        } else {
            // Exchange 2007 SP1: определяем формат serverId
            // EAS ServerId: короткий, содержит ":" (напр. "22:2")
            // EWS ItemId: длинная base64 строка без ":" (напр. "AAMkAD...")
            val isEasServerId = serverId.contains(":") && serverId.length < 20
            if (isEasServerId) {
                val calendarFolderId = getCalendarFolderId()
                    ?: return EasResult.Error("Папка календаря не найдена")
                deleteCalendarEventEas(serverId, calendarFolderId)
            } else {
                // EWS ItemId — удаляем через EWS DeleteItem
                // Если serverId содержит "|" — это формат "ItemId|ChangeKey", берём только ItemId
                val ewsItemId = if (serverId.contains("|")) serverId.substringBefore("|") else serverId
                deleteCalendarEventEws(ewsItemId)
            }
        }
    }
    
    // === Вспомогательные методы ===
    
    /**
     * DRY: Общий метод получения актуального SyncKey для EAS операций (Create/Update/Delete).
     * Шаг 1: Начальный Sync с SyncKey=0 → получаем первый SyncKey
     * Шаг 2: Полное продвижение через <MoreAvailable> до актуального состояния
     * КРИТИЧНО: без полного продвижения операции могут получить Status=3 (INVALID_SYNCKEY).
     */
    private suspend fun getAdvancedSyncKey(calendarFolderId: String): EasResult<String> {
        val initialXml = EasXmlTemplates.syncInitial(calendarFolderId)
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
        
        while (moreAvailable && syncIterations < maxSyncIterations) {
            syncIterations++
            val advanceXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <GetChanges>1</GetChanges>
            <WindowSize>100</WindowSize>
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
    
    // === EAS методы (Exchange 2010+) ===
    
private suspend fun syncCalendarStandard(folders: List<EasFolder>): EasResult<List<EasCalendarEvent>> {
    val calendarFolderId = folders.find { it.type == 8 }?.serverId
        ?: return EasResult.Error("Папка календаря не найдена")
    
    cachedCalendarFolderId = calendarFolderId
    
    // Получаем начальный SyncKey
    val initialXml = EasXmlTemplates.syncInitial(calendarFolderId)
    var syncKey = "0"
    
    val initialResult = deps.executeEasCommand("Sync", initialXml) { responseXml ->
        deps.extractValue(responseXml, "SyncKey") ?: "0"
    }
    
    when (initialResult) {
        is EasResult.Success -> syncKey = initialResult.data
        is EasResult.Error -> return EasResult.Error(initialResult.message)
    }
    
    if (syncKey == "0") {
        return EasResult.Error("Не удалось получить начальный SyncKey для календаря")
    }
    
    return syncCalendarEasLoop(calendarFolderId, syncKey)
}

    
   private suspend fun syncCalendarLegacy(folders: List<EasFolder>): EasResult<List<EasCalendarEvent>> {
    val calendarFolderId = folders.find { it.type == 8 }?.serverId
        ?: return EasResult.Error("Папка календаря не найдена")
    
    cachedCalendarFolderId = calendarFolderId
    
    // Получаем начальный SyncKey
    val initialXml = EasXmlTemplates.syncInitial(calendarFolderId)
    var syncKey = "0"
    
    val initialResult = deps.executeEasCommand("Sync", initialXml) { responseXml ->
        deps.extractValue(responseXml, "SyncKey") ?: "0"
    }
    
    when (initialResult) {
        is EasResult.Success -> syncKey = initialResult.data
        is EasResult.Error -> return EasResult.Error(initialResult.message)
    }
    
    if (syncKey == "0") {
        return EasResult.Error("Не удалось получить начальный SyncKey для календаря")
    }
    
    return syncCalendarEasLoop(calendarFolderId, syncKey)
}

    /**
     * Общий цикл EAS-синхронизации календаря с пагинацией и защитами (DRY).
     * Используется из syncCalendarStandard и syncCalendarLegacy.
     * Robustness: timeout, sameKey loop detection, consecutiveErrors, empty data detection.
     */
    private suspend fun syncCalendarEasLoop(
        calendarFolderId: String,
        initialSyncKey: String
    ): EasResult<List<EasCalendarEvent>> {
        var syncKey = initialSyncKey
        val allEvents = mutableListOf<EasCalendarEvent>()
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
                <FilterType>6</FilterType>
                <airsyncbase:BodyPreference>
                    <airsyncbase:Type>1</airsyncbase:Type>
                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                </airsyncbase:BodyPreference>
            </Options>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
            
            val result = deps.executeEasCommand("Sync", syncXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")?.toIntOrNull()
                if (status != null && status != 1) {
                    throw Exception("Calendar Sync Status=$status")
                }
                val newSyncKey = deps.extractValue(responseXml, "SyncKey")
                if (newSyncKey != null) {
                    syncKey = newSyncKey
                }
                moreAvailable = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                parseCalendarEvents(responseXml)
            }
            
            when (result) {
                is EasResult.Success -> {
                    consecutiveErrors = 0
                    allEvents.addAll(result.data)
                    
                    // Защита от зацикливания: SyncKey не меняется
                    if (syncKey == previousSyncKey) {
                        sameKeyCount++
                        if (sameKeyCount >= 5) {
                            android.util.Log.w("EasCalendarService", "SyncKey not changing for 5 iterations, breaking")
                            moreAvailable = false
                        }
                    } else {
                        sameKeyCount = 0
                        previousSyncKey = syncKey
                    }
                    
                    // Защита: сервер говорит moreAvailable, но данных нет
                    if (moreAvailable && result.data.isEmpty()) {
                        emptyDataCount++
                        if (emptyDataCount >= 3) {
                            android.util.Log.w("EasCalendarService", "No data for $emptyDataCount iterations, breaking")
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
                            EasResult.Success(allEvents) // Возвращаем что успели получить
                        } else {
                            result
                        }
                    }
                    kotlinx.coroutines.delay(500L * consecutiveErrors)
                }
            }
        }
        
        return EasResult.Success(allEvents)
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
        
        // Создание события
        val clientId = UUID.randomUUID().toString().replace("-", "").take(32)
        val startTimeStr = formatEasDate(startTime)
        val endTimeStr = formatEasDate(endTime)
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedLocation = deps.escapeXml(location)
        val escapedBody = deps.escapeXml(body)
        
        val meetingStatus = if (attendees.isNotEmpty()) 1 else 0
        
        val attendeesXml = if (attendees.isNotEmpty()) {
            buildString {
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
        } else ""
        
        val recurrenceXml = buildEasRecurrenceXml(recurrenceType, startTime)
        
        val createXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:calendar="Calendar">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <Commands>
                <Add>
                    <ClientId>$clientId</ClientId>
                    <ApplicationData>
                        <calendar:Subject>$escapedSubject</calendar:Subject>
                        <calendar:StartTime>$startTimeStr</calendar:StartTime>
                        <calendar:EndTime>$endTimeStr</calendar:EndTime>
                        <calendar:Location>$escapedLocation</calendar:Location>
                        <airsyncbase:Body>
                            <airsyncbase:Type>1</airsyncbase:Type>
                            <airsyncbase:Data>$escapedBody</airsyncbase:Data>
                        </airsyncbase:Body>
                        <calendar:AllDayEvent>${if (allDayEvent) "1" else "0"}</calendar:AllDayEvent>
                        <calendar:Reminder>$reminder</calendar:Reminder>
                        <calendar:BusyStatus>$busyStatus</calendar:BusyStatus>
                        <calendar:Sensitivity>$sensitivity</calendar:Sensitivity>
                        <calendar:MeetingStatus>$meetingStatus</calendar:MeetingStatus>
                        $attendeesXml
                        $recurrenceXml
                    </ApplicationData>
                </Add>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", createXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status == "1") {
                // КРИТИЧНО: Извлекаем ServerId ИМЕННО из секции Responses/Add,
                // а не первый попавшийся (который может быть из Commands других событий).
                // Ищем блок <Responses>...<Add>...<ClientId>OUR_ID</ClientId>...<ServerId>X</ServerId>...
                val responsesPattern = "<Responses>(.*?)</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val responsesBlock = responsesPattern.find(responseXml)?.groupValues?.get(1)
                val serverIdFromResponses = if (responsesBlock != null) {
                    // Ищем Add-блок с нашим ClientId
                    val addPattern = "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL)
                    addPattern.findAll(responsesBlock)
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
                val changeStatusMatch = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
                    .find(responseXml)
                
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
                    val deleteStatusMatch = "<Responses>.*?<Delete>.*?<Status>(\\d+)</Status>.*?</Delete>.*?</Responses>".toRegex(RegexOption.DOT_MATCHES_ALL)
                        .find(responseXml)
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
        
        // Пробуем Basic Auth, затем NTLM
        var response = deps.tryBasicAuthEws(ewsUrl, findItemRequest, "FindItem")
        if (response == null) {
            val authHeader = deps.performNtlmHandshake(ewsUrl, findItemRequest, "FindItem")
                ?: return EasResult.Error("NTLM handshake failed")
            response = deps.executeNtlmRequest(ewsUrl, findItemRequest, authHeader, "FindItem")
                ?: return EasResult.Error("Ошибка выполнения EWS запроса")
        }
        
        return try {
            val events = parseEwsCalendarEvents(response)
            EasResult.Success(events)
        } catch (e: Exception) {
            EasResult.Error("Ошибка парсинга календаря: ${e.message}")
        }
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
                
                val startTimeStr = formatEwsDate(startTime)
                val endTimeStr = formatEwsDate(endTime)
                
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
                    // Добавляем участников если есть
                    append(attendeesXml)
                    val ewsRecurrenceXml = buildEwsRecurrenceXml(recurrenceType, startTimeStr)
                    if (ewsRecurrenceXml.isNotBlank()) append(ewsRecurrenceXml)
                    append("</t:CalendarItem>")
                    append("</Items>")
                    append("</CreateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                android.util.Log.d("EasCalendarService", "createCalendarEventEws: Request: $soapRequest")
                
                // Пробуем Basic Auth, затем NTLM
                var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "CreateItem")
                if (responseXml == null) {
                    val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                        ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                    responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                        ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                // Проверяем на ошибки
                if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                    return@withContext EasResult.Error("Ошибка схемы EWS")
                }
                
                // Извлекаем ItemId и ChangeKey
                val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"\\s+ChangeKey=\"([^\"]+)\"".toRegex()
                val idMatch = itemIdPattern.find(responseXml)
                val itemId = idMatch?.groupValues?.get(1)
                    ?: EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                val changeKey = idMatch?.groupValues?.get(2)
                
                // КРИТИЧНО: Проверяем ОБА условия
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                                responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
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
        recurrenceType: Int = -1
    ): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                
                // КРИТИЧНО: Если serverId короткий (формат "22:2") - получаем ПОЛНЫЙ ItemId
                var actualServerId = serverId
                
                if (serverId.contains(":") && !serverId.contains("=")) {
                    // КРИТИЧНО: Используем СТАРЫЙ subject для поиска (если subject изменился)
                    val searchSubject = oldSubject ?: subject
                    
                    // Ищем только по Subject (время может не совпадать из-за временных зон)
                    val findResult = findCalendarItemIdBySubject(searchSubject)
                    
                    if (findResult is EasResult.Success) {
                        actualServerId = findResult.data
                    }
                    // Если не найдёт - попробуем с коротким (может сработать)
                }
                
                val escapedSubject = deps.escapeXml(subject)
                val escapedLocation = deps.escapeXml(location)
                val escapedBody = deps.escapeXml(body)
                
                val startTimeStr = formatEwsDate(startTime)
                val endTimeStr = formatEwsDate(endTime)
                
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
                    append("""<m:UpdateItem ConflictResolution="AlwaysOverwrite" SendMeetingInvitationsOrCancellations="SendToNone">""")
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
                    
                    // Start - КРИТИЧНО: отправляем в UTC с 'Z'!
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:Start"/>""")
                    append("<t:CalendarItem>")
                    append("<t:Start>$startTimeStr</t:Start>")
                    append("</t:CalendarItem>")
                    append("</t:SetItemField>")
                    
                    // End - КРИТИЧНО: отправляем в UTC с 'Z'!
                    append("<t:SetItemField>")
                    append("""<t:FieldURI FieldURI="calendar:End"/>""")
                    append("<t:CalendarItem>")
                    append("<t:End>$endTimeStr</t:End>")
                    append("</t:CalendarItem>")
                    append("</t:SetItemField>")
                    
                    // Location
                    if (escapedLocation.isNotBlank()) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="calendar:Location"/>""")
                        append("<t:CalendarItem>")
                        append("<t:Location>$escapedLocation</t:Location>")
                        append("</t:CalendarItem>")
                        append("</t:SetItemField>")
                    }
                    
                    // Body
                    if (escapedBody.isNotBlank()) {
                        append("<t:SetItemField>")
                        append("""<t:FieldURI FieldURI="item:Body"/>""")
                        append("<t:CalendarItem>")
                        append("""<t:Body BodyType="Text">$escapedBody</t:Body>""")
                        append("</t:CalendarItem>")
                        append("</t:SetItemField>")
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
                    
                    // Reminder
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
                    }
                    
                    // Recurrence
                    val ewsRecurrenceUpdateXml = buildEwsRecurrenceUpdateXml(recurrenceType, startTimeStr)
                    if (ewsRecurrenceUpdateXml.isNotBlank()) append(ewsRecurrenceUpdateXml)
                    
                    append("</t:Updates>")
                    append("</t:ItemChange>")
                    append("</m:ItemChanges>")
                    append("</m:UpdateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                // Пробуем Basic Auth, затем NTLM
                var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "UpdateItem")
                if (responseXml == null) {
                    val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "UpdateItem")
                        ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                    responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "UpdateItem")
                        ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                // Проверяем ResponseClass и ResponseCode
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val responseCode = """<m:ResponseCode>(.*?)</m:ResponseCode>""".toRegex()
                    .find(responseXml)?.groupValues?.get(1)
                
                if (hasSuccess && responseCode == "NoError") {
                    EasResult.Success(true)
                } else {
                    EasResult.Error("Ошибка обновления события: $responseCode")
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка обновления события через EWS")
            }
        }
    }
    
    private suspend fun deleteCalendarEventEws(serverId: String): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                // КРИТИЧНО: Для календарных событий ОБЯЗАТЕЛЕН атрибут SendMeetingCancellations
                // Без него Exchange 2007 SP1 возвращает ErrorSendMeetingCancellationsRequired
                // Поэтому НЕ используем generic ewsDeleteItem, а строим calendar-specific запрос
                // КРИТИЧНО: HardDelete — перманентное удаление из БД Exchange.
                // MoveToDeletedItems лишь перемещает в корзину, что может вызвать
                // "воскрешение" событий при определённых условиях синхронизации.
                // SendMeetingCancellations="SendToNone" — не отправлять уведомления
                // об отмене (событие удалено пользователем, а не организатором).
                val deleteBody = """
                    <m:DeleteItem DeleteType="HardDelete" SendMeetingCancellations="SendToNone">
                        <m:ItemIds>
                            <t:ItemId Id="${deps.escapeXml(serverId)}"/>
                        </m:ItemIds>
                    </m:DeleteItem>
                """.trimIndent()
                val request = EasXmlTemplates.ewsSoapRequest(deleteBody)
                
                // Пробуем Basic Auth, затем NTLM
                var response = deps.tryBasicAuthEws(ewsUrl, request, "DeleteItem")
                if (response == null) {
                    val authHeader = deps.performNtlmHandshake(ewsUrl, request, "DeleteItem")
                        ?: return@withContext EasResult.Error("NTLM handshake failed")
                    response = deps.executeNtlmRequest(ewsUrl, request, authHeader, "DeleteItem")
                        ?: return@withContext EasResult.Error("Ошибка выполнения EWS запроса")
                }
                
                val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)
                when (responseCode) {
                    "NoError" -> EasResult.Success(true)
                    // ErrorItemNotFound — аналог EAS Status=8, событие уже удалено
                    "ErrorItemNotFound" -> EasResult.Success(true)
                    else -> EasResult.Error("EWS DeleteItem: $responseCode")
                }
            } catch (e: Exception) {
                EasResult.Error("Ошибка удаления события: ${e.message}")
            }
        }
    }
    
    /**
     * Поиск ПОЛНОГО EWS ItemId события по Subject
     * Используется для конвертации КОРОТКОГО ActiveSync serverId в ПОЛНЫЙ EWS ItemId
     * КРИТИЧНО: Ищем только по Subject, т.к. время может не совпадать из-за временных зон
     */
    private suspend fun findCalendarItemIdBySubject(
        subject: String
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                val escapedSubject = deps.escapeXml(subject)
                
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
            </m:ItemShape>
            <m:Restriction>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="$escapedSubject"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="calendar"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
                
                var response = deps.tryBasicAuthEws(ewsUrl, findRequest, "FindItem")
                if (response == null) {
                    val authHeader = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
                        ?: return@withContext EasResult.Error("NTLM handshake failed")
                    response = deps.executeNtlmRequest(ewsUrl, findRequest, authHeader, "FindItem")
                        ?: return@withContext EasResult.Error("Failed to execute FindItem")
                }
                
                // Извлекаем ItemId И ChangeKey из ответа
                // Формат: <t:ItemId Id="AAMk..." ChangeKey="EQAAAB..."/>
                val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"\\s+ChangeKey=\"([^\"]+)\"".toRegex()
                val match = itemIdPattern.find(response)
                
                if (match != null) {
                    val fullItemId = match.groupValues[1]
                    val changeKey = match.groupValues[2]
                    // Возвращаем ItemId с ChangeKey в формате "Id|ChangeKey"
                    val itemIdWithChangeKey = "$fullItemId|$changeKey"
                    EasResult.Success(itemIdWithChangeKey)
                } else {
                    EasResult.Error("ItemId or ChangeKey not found for subject=$subject")
                }
            } catch (e: Exception) {
                EasResult.Error("Failed to find ItemId: ${e.message}")
            }
        }
    }
    
    // === Парсинг ===
    
    private fun parseCalendarEvents(xml: String): List<EasCalendarEvent> {
        val events = mutableListOf<EasCalendarEvent>()
        val eventPattern = "<Add>(.*?)</Add>|<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (match in eventPattern.findAll(xml)) {
            val eventXml = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (eventXml.isEmpty()) continue
            
            val serverId = deps.extractValue(eventXml, "ServerId") ?: continue
            
            // КРИТИЧНО: Извлекаем ApplicationData (как в старой версии)
            val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val dataMatch = dataPattern.find(eventXml)
            if (dataMatch == null) continue
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
        val itemPattern = "<t:CalendarItem>(.*?)</t:CalendarItem>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (match in itemPattern.findAll(xml)) {
            val itemXml = match.groupValues[1]
            
            val itemId = EasPatterns.EWS_ITEM_ID.find(itemXml)?.groupValues?.get(1) ?: continue
            val subject = EasPatterns.EWS_SUBJECT.find(itemXml)?.groupValues?.get(1) ?: ""
            val rawBody = EasPatterns.EWS_BODY.find(itemXml)?.groupValues?.get(1) ?: ""
            val body = removeDuplicateLines(rawBody)
            
            val startStr = """<t:Start>(.*?)</t:Start>""".toRegex().find(itemXml)?.groupValues?.get(1)
            val endStr = """<t:End>(.*?)</t:End>""".toRegex().find(itemXml)?.groupValues?.get(1)
            
            val startTime = parseEwsDateTime(startStr) ?: 0L
            val endTime = parseEwsDateTime(endStr) ?: 0L
            
            val location = """<t:Location>(.*?)</t:Location>""".toRegex().find(itemXml)?.groupValues?.get(1) ?: ""
            val isAllDay = """<t:IsAllDayEvent>(.*?)</t:IsAllDayEvent>""".toRegex().find(itemXml)?.groupValues?.get(1) == "true"
            val organizerMatch = """<t:Organizer>.*?<t:Mailbox>(.*?)</t:Mailbox>.*?</t:Organizer>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(itemXml)
            val organizerMailbox = organizerMatch?.groupValues?.get(1) ?: ""
            val organizer = """<t:EmailAddress>(.*?)</t:EmailAddress>""".toRegex().find(organizerMailbox)?.groupValues?.get(1) ?: ""
            val organizerName = """<t:Name>(.*?)</t:Name>""".toRegex().find(organizerMailbox)?.groupValues?.get(1) ?: ""
            val isRecurring = itemXml.contains("<t:Recurrence>") || itemXml.contains("<t:IsRecurring>true</t:IsRecurring>")
            
            // Парсим lastModified из EWS
            val lastModifiedStr = """<t:LastModifiedTime>(.*?)</t:LastModifiedTime>""".toRegex().find(itemXml)?.groupValues?.get(1)
            val lastModified = parseEwsDateTime(lastModifiedStr) ?: System.currentTimeMillis()
            
            // Reminder, BusyStatus, Sensitivity
            val reminder = """<t:ReminderMinutesBeforeStart>(.*?)</t:ReminderMinutesBeforeStart>""".toRegex().find(itemXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val reminderSet = """<t:ReminderIsSet>(.*?)</t:ReminderIsSet>""".toRegex().find(itemXml)?.groupValues?.get(1) == "true"
            val ewsBusyStr = """<t:LegacyFreeBusyStatus>(.*?)</t:LegacyFreeBusyStatus>""".toRegex().find(itemXml)?.groupValues?.get(1) ?: "Busy"
            val busyStatus = when (ewsBusyStr) { "Free" -> 0; "Tentative" -> 1; "Busy" -> 2; "OOF" -> 3; "WorkingElsewhere" -> 4; else -> 2 }
            val ewsSensStr = """<t:Sensitivity>(.*?)</t:Sensitivity>""".toRegex().find(itemXml)?.groupValues?.get(1) ?: "Normal"
            val sensitivity = when (ewsSensStr) { "Normal" -> 0; "Personal" -> 1; "Private" -> 2; "Confidential" -> 3; else -> 0 }
            
            // UID
            val uid = """<t:UID>(.*?)</t:UID>""".toRegex().find(itemXml)?.groupValues?.get(1) ?: ""
            
            // HasAttachments и парсинг вложений EWS
            val hasAttachments = """<t:HasAttachments>(.*?)</t:HasAttachments>""".toRegex().find(itemXml)?.groupValues?.get(1) == "true"
            val ewsAttachmentsJson = if (hasAttachments) parseEwsAttachments(itemXml) else ""
            
            // MeetingStatus: IsMeeting + IsCancelled
            val isMeeting = """<t:IsMeeting>(.*?)</t:IsMeeting>""".toRegex().find(itemXml)?.groupValues?.get(1) == "true"
            val isCancelled = """<t:IsCancelled>(.*?)</t:IsCancelled>""".toRegex().find(itemXml)?.groupValues?.get(1) == "true"
            val meetingStatus = if (isCancelled) 5 else if (isMeeting) 1 else 0
            
            // ResponseRequested, DisallowNewTimeProposal
            val responseRequested = """<t:IsResponseRequested>(.*?)</t:IsResponseRequested>""".toRegex().find(itemXml)?.groupValues?.get(1) == "true"
            val disallowNewTime = """<t:AllowNewTimeProposal>(.*?)</t:AllowNewTimeProposal>""".toRegex().find(itemXml)?.groupValues?.get(1) == "false"
            
            // ResponseType (MyResponseType)
            val ewsResponseStr = """<t:MyResponseType>(.*?)</t:MyResponseType>""".toRegex().find(itemXml)?.groupValues?.get(1) ?: "Unknown"
            val responseStatus = when (ewsResponseStr) { "Unknown" -> 0; "NoResponseReceived" -> 1; "Accept" -> 2; "Tentative" -> 3; "Decline" -> 4; else -> 0 }
            
            // Парсим участников EWS
            val ewsAttendees = parseEwsAttendees(itemXml)
            
            // Парсим категории EWS
            val categories = mutableListOf<String>()
            val ewsCategoriesPattern = """<t:Categories>(.*?)</t:Categories>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val ewsCategoriesMatch = ewsCategoriesPattern.find(itemXml)
            if (ewsCategoriesMatch != null) {
                val categoriesXml = ewsCategoriesMatch.groupValues[1]
                """<t:String>(.*?)</t:String>""".toRegex().findAll(categoriesXml).forEach { catMatch ->
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
            "<calendar:Body>(.*?)</calendar:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                val rawBody = unescapeXml(match.groupValues[1].trim())
                return removeDuplicateLines(rawBody)
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
        val categoriesPattern = "<calendar:Categories>(.*?)</calendar:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val categoriesMatch = categoriesPattern.find(xml)
        if (categoriesMatch != null) {
            val categoriesXml = categoriesMatch.groupValues[1]
            val categoryPattern = "<calendar:Category>(.*?)</calendar:Category>".toRegex(RegexOption.DOT_MATCHES_ALL)
            categoryPattern.findAll(categoriesXml).forEach { match ->
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
        val attendeePattern = "<calendar:Attendee>(.*?)</calendar:Attendee>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (match in attendeePattern.findAll(xml)) {
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
        val attachmentPattern = "<(?:airsyncbase:)?Attachment>(.*?)</(?:airsyncbase:)?Attachment>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (match in attachmentPattern.findAll(xml)) {
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
        val recPattern = "<(?:calendar:)?Recurrence>(.*?)</(?:calendar:)?Recurrence>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val recMatch = recPattern.find(xml) ?: return ""
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
        
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = startTime
        
        return buildString {
            append("<calendar:Recurrence>")
            append("<calendar:Type>$recurrenceType</calendar:Type>")
            append("<calendar:Interval>1</calendar:Interval>")
            
            when (recurrenceType) {
                0 -> { /* Daily — только Type и Interval */ }
                1 -> {
                    // Weekly — день недели как bitmask (Sun=1,Mon=2,Tue=4,...)
                    val dayBitmask = 1 shl (cal.get(java.util.Calendar.DAY_OF_WEEK) - 1)
                    append("<calendar:DayOfWeek>$dayBitmask</calendar:DayOfWeek>")
                }
                2 -> {
                    // Monthly absolute — день месяца
                    append("<calendar:DayOfMonth>${cal.get(java.util.Calendar.DAY_OF_MONTH)}</calendar:DayOfMonth>")
                }
                5 -> {
                    // Yearly absolute — день месяца + месяц года
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
        
        // Извлекаем дату начала для NoEndRecurrence
        val startDate = startTimeStr.substringBefore("T")
        
        return buildString {
            append("<Recurrence>")
            
            when (recurrenceType) {
                0 -> {
                    append("<DailyRecurrence>")
                    append("<Interval>1</Interval>")
                    append("</DailyRecurrence>")
                }
                1 -> {
                    // Определяем день недели из startTimeStr
                    val ewsDayName = ewsDayNameFromIso(startTimeStr)
                    append("<WeeklyRecurrence>")
                    append("<Interval>1</Interval>")
                    append("<DaysOfWeek>$ewsDayName</DaysOfWeek>")
                    append("</WeeklyRecurrence>")
                }
                2 -> {
                    val dayOfMonth = startTimeStr.substring(8, 10).toIntOrNull() ?: 1
                    append("<AbsoluteMonthlyRecurrence>")
                    append("<Interval>1</Interval>")
                    append("<DayOfMonth>$dayOfMonth</DayOfMonth>")
                    append("</AbsoluteMonthlyRecurrence>")
                }
                5 -> {
                    val dayOfMonth = startTimeStr.substring(8, 10).toIntOrNull() ?: 1
                    val monthNum = startTimeStr.substring(5, 7).toIntOrNull() ?: 1
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
        
        val startDate = startTimeStr.substringBefore("T")
        
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
                    val dayOfMonth = startTimeStr.substring(8, 10).toIntOrNull() ?: 1
                    append("<t:AbsoluteMonthlyRecurrence>")
                    append("<t:Interval>1</t:Interval>")
                    append("<t:DayOfMonth>$dayOfMonth</t:DayOfMonth>")
                    append("</t:AbsoluteMonthlyRecurrence>")
                }
                5 -> {
                    val dayOfMonth = startTimeStr.substring(8, 10).toIntOrNull() ?: 1
                    val monthNum = startTimeStr.substring(5, 7).toIntOrNull() ?: 1
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
    
    /** Маппинг EAS busyStatus (Int) → EWS LegacyFreeBusyStatus (String) */
    private fun mapBusyStatusToEws(busyStatus: Int): String = when (busyStatus) {
        0 -> "Free"
        1 -> "Tentative"
        3 -> "OOF"
        else -> "Busy"
    }
    
    /** Определяет EWS день недели из ISO даты (yyyy-MM-ddTHH:mm:ssZ) */
    private fun ewsDayNameFromIso(isoDate: String): String {
        return try {
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = dateFormat.parse(isoDate) ?: return "Monday"
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.time = date
            when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.SUNDAY -> "Sunday"
                java.util.Calendar.MONDAY -> "Monday"
                java.util.Calendar.TUESDAY -> "Tuesday"
                java.util.Calendar.WEDNESDAY -> "Wednesday"
                java.util.Calendar.THURSDAY -> "Thursday"
                java.util.Calendar.FRIDAY -> "Friday"
                java.util.Calendar.SATURDAY -> "Saturday"
                else -> "Monday"
            }
        } catch (e: Exception) { "Monday" }
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
        val recPattern = """<t:Recurrence>(.*?)</t:Recurrence>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val recMatch = recPattern.find(xml) ?: return ""
        val recXml = recMatch.groupValues[1]
        
        // Определяем тип повторения
        val type: Int
        var interval = 1
        var dayOfWeek = 0
        var dayOfMonth = 0
        var weekOfMonth = 0
        var monthOfYear = 0
        var firstDayOfWeek = 0
        
        when {
            recXml.contains("<t:DailyRecurrence>") -> {
                type = 0
                interval = """<t:Interval>(.*?)</t:Interval>""".toRegex().find(recXml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            }
            recXml.contains("<t:WeeklyRecurrence>") -> {
                type = 1
                interval = """<t:Interval>(.*?)</t:Interval>""".toRegex().find(recXml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val daysStr = """<t:DaysOfWeek>(.*?)</t:DaysOfWeek>""".toRegex().find(recXml)?.groupValues?.get(1) ?: ""
                dayOfWeek = ewsDaysOfWeekToBitmask(daysStr)
                val fdow = """<t:FirstDayOfWeek>(.*?)</t:FirstDayOfWeek>""".toRegex().find(recXml)?.groupValues?.get(1) ?: ""
                firstDayOfWeek = ewsDayNameToNumber(fdow)
            }
            recXml.contains("<t:AbsoluteMonthlyRecurrence>") -> {
                type = 2
                interval = """<t:Interval>(.*?)</t:Interval>""".toRegex().find(recXml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                dayOfMonth = """<t:DayOfMonth>(.*?)</t:DayOfMonth>""".toRegex().find(recXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            recXml.contains("<t:RelativeMonthlyRecurrence>") -> {
                type = 3
                interval = """<t:Interval>(.*?)</t:Interval>""".toRegex().find(recXml)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val daysStr = """<t:DaysOfWeek>(.*?)</t:DaysOfWeek>""".toRegex().find(recXml)?.groupValues?.get(1) ?: ""
                dayOfWeek = ewsDaysOfWeekToBitmask(daysStr)
                weekOfMonth = ewsWeekIndexToNumber("""<t:DayOfWeekIndex>(.*?)</t:DayOfWeekIndex>""".toRegex().find(recXml)?.groupValues?.get(1) ?: "")
            }
            recXml.contains("<t:AbsoluteYearlyRecurrence>") -> {
                type = 5
                dayOfMonth = """<t:DayOfMonth>(.*?)</t:DayOfMonth>""".toRegex().find(recXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                monthOfYear = ewsMonthToNumber("""<t:Month>(.*?)</t:Month>""".toRegex().find(recXml)?.groupValues?.get(1) ?: "")
            }
            recXml.contains("<t:RelativeYearlyRecurrence>") -> {
                type = 6
                val daysStr = """<t:DaysOfWeek>(.*?)</t:DaysOfWeek>""".toRegex().find(recXml)?.groupValues?.get(1) ?: ""
                dayOfWeek = ewsDaysOfWeekToBitmask(daysStr)
                weekOfMonth = ewsWeekIndexToNumber("""<t:DayOfWeekIndex>(.*?)</t:DayOfWeekIndex>""".toRegex().find(recXml)?.groupValues?.get(1) ?: "")
                monthOfYear = ewsMonthToNumber("""<t:Month>(.*?)</t:Month>""".toRegex().find(recXml)?.groupValues?.get(1) ?: "")
            }
            else -> return ""
        }
        
        // End condition
        var until = 0L
        var occurrences = 0
        val endDateStr = """<t:EndDate>(.*?)</t:EndDate>""".toRegex().find(recXml)?.groupValues?.get(1)
        if (endDateStr != null) {
            until = parseEwsDateTime("${endDateStr}T23:59:59Z") ?: 0L
        }
        val occStr = """<t:NumberOfOccurrences>(.*?)</t:NumberOfOccurrences>""".toRegex().find(recXml)?.groupValues?.get(1)
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
        val exPattern = "<(?:calendar:)?Exception>(.*?)</(?:calendar:)?Exception>".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (match in exPattern.findAll(xml)) {
            val exXml = match.groupValues[1]
            val exStartTime = deps.parseEasDate(extractCalendarValue(exXml, "ExceptionStartTime")) ?: 0L
            val deleted = extractCalendarValue(exXml, "Deleted") == "1"
            val subject = escapeJsonString(extractCalendarValue(exXml, "Subject") ?: "")
            val location = escapeJsonString(extractCalendarValue(exXml, "Location") ?: "")
            val startTime = deps.parseEasDate(extractCalendarValue(exXml, "StartTime")) ?: 0L
            val endTime = deps.parseEasDate(extractCalendarValue(exXml, "EndTime")) ?: 0L
            
            exceptions.add("""{"exceptionStartTime":$exStartTime,"deleted":$deleted,"subject":"$subject","location":"$location","startTime":$startTime,"endTime":$endTime}""")
        }
        
        return if (exceptions.isEmpty()) "" else "[${exceptions.joinToString(",")}]"
    }
    
    /**
     * Парсит вложения из EWS ответа (t:Attachments)
     * Возвращает JSON массив: [{name, fileReference, size, isInline, contentId}]
     */
    private fun parseEwsAttachments(xml: String): String {
        val attachments = mutableListOf<String>()
        val attPattern = """<t:(?:FileAttachment|ItemAttachment)>(.*?)</t:(?:FileAttachment|ItemAttachment)>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (match in attPattern.findAll(xml)) {
            val attXml = match.groupValues[1]
            val attId = """<t:AttachmentId Id="(.*?)"""".toRegex().find(attXml)?.groupValues?.get(1) ?: ""
            val name = """<t:Name>(.*?)</t:Name>""".toRegex().find(attXml)?.groupValues?.get(1) ?: "attachment"
            val size = """<t:Size>(.*?)</t:Size>""".toRegex().find(attXml)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val isInline = """<t:IsInline>(.*?)</t:IsInline>""".toRegex().find(attXml)?.groupValues?.get(1) == "true"
            val contentId = """<t:ContentId>(.*?)</t:ContentId>""".toRegex().find(attXml)?.groupValues?.get(1) ?: ""
            
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
        
        // Required, Optional, Resources attendees
        val listsPattern = """<t:(?:RequiredAttendees|OptionalAttendees|Resources)>(.*?)</t:(?:RequiredAttendees|OptionalAttendees|Resources)>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        
        for (listMatch in listsPattern.findAll(xml)) {
            val listXml = listMatch.groupValues[1]
            val attendeePattern = """<t:Attendee>(.*?)</t:Attendee>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            for (attMatch in attendeePattern.findAll(listXml)) {
                val attXml = attMatch.groupValues[1]
                val email = """<t:EmailAddress>(.*?)</t:EmailAddress>""".toRegex().find(attXml)?.groupValues?.get(1) ?: continue
                val name = """<t:Name>(.*?)</t:Name>""".toRegex().find(attXml)?.groupValues?.get(1) ?: ""
                val responseStr = """<t:ResponseType>(.*?)</t:ResponseType>""".toRegex().find(attXml)?.groupValues?.get(1) ?: "Unknown"
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

    // === Загрузка вложений к событиям календаря через EWS CreateAttachment ===

    internal suspend fun attachFilesEws(
        ewsUrl: String,
        itemId: String,
        changeKey: String?,
        attachments: List<DraftAttachmentData>,
        exchangeVersion: String
    ): EasResult<String> = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext EasResult.Success("")

        var currentChangeKey = changeKey
        val attachmentInfos = mutableListOf<String>()

        for ((index, att) in attachments.withIndex()) {
            val request = buildCreateAttachmentRequest(
                itemId, currentChangeKey, att, exchangeVersion
            )

            var response = deps.tryBasicAuthEws(ewsUrl, request, "CreateAttachment")
            if (response == null) {
                val authHeader = deps.performNtlmHandshake(ewsUrl, request, "CreateAttachment")
                    ?: return@withContext EasResult.Error(
                        "NTLM handshake failed для вложения ${index + 1}/${attachments.size} (${att.name})"
                    )
                response = deps.executeNtlmRequest(ewsUrl, request, authHeader, "CreateAttachment")
                    ?: return@withContext EasResult.Error(
                        "Ошибка EWS запроса для вложения ${index + 1}/${attachments.size} (${att.name})"
                    )
            }

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

            // Извлекаем AttachmentId из ответа (для последующего скачивания)
            val attachmentId = """<t:AttachmentId Id="([^"]+)"""".toRegex()
                .find(response)?.groupValues?.get(1) ?: ""

            val newChangeKey = """ChangeKey="([^"]+)"""".toRegex()
                .find(response)?.groupValues?.get(1)
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
        val changeKeyAttr = changeKey?.let { " ChangeKey=\"${deps.escapeXml(it)}\"" } ?: ""
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

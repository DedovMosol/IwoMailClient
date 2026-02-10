package com.dedovmosol.iwomail.eas

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
        // EAS 12.x (Exchange 2007) — Legacy sync (как в старой версии)
        return if (majorVersion >= 14) {
            syncCalendarStandard(folders)
        } else {
            syncCalendarLegacy(folders)
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
        attendees: List<String> = emptyList()
    ): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            createCalendarEventEas(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees)
        } else {
            createCalendarEventEws(subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees)
        }
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
        oldSubject: String? = null
    ): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            updateCalendarEventEas(serverId, subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees)
        } else {
            updateCalendarEventEws(serverId, subject, startTime, endTime, location, body, allDayEvent, reminder, busyStatus, sensitivity, attendees, oldSubject)
        }
    }
    
    /**
     * Удаление события календаря
     */
    suspend fun deleteCalendarEvent(serverId: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val calendarFolderId = getCalendarFolderId()
        if (calendarFolderId == null) {
            return EasResult.Error("Папка календаря не найдена")
        }
        
        // Всегда используем EAS Sync для удаления (как в старой версии)
        return deleteCalendarEventEas(serverId, calendarFolderId)
    }
    
    // === Вспомогательные методы ===
    
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
        return EasResult.Success(emptyList())
    }
    
    // Запрашиваем события с пагинацией
    val allEvents = mutableListOf<EasCalendarEvent>()
    var moreAvailable = true
    var iterations = 0
    val maxIterations = 100
    
    while (moreAvailable && iterations < maxIterations) {
        iterations++
        
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
                <airsyncbase:BodyPreference>
                    <airsyncbase:Type>1</airsyncbase:Type>
                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                </airsyncbase:BodyPreference>
            </Options>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val result = deps.executeEasCommand("Sync", syncXml) { responseXml ->
            val newSyncKey = deps.extractValue(responseXml, "SyncKey")
            if (newSyncKey != null) {
                syncKey = newSyncKey
            }
            moreAvailable = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
            parseCalendarEvents(responseXml)
        }
        
        when (result) {
            is EasResult.Success -> {
                allEvents.addAll(result.data)
                if (result.data.isEmpty()) {
                    moreAvailable = false
                }
            }
            is EasResult.Error -> return result
        }
    }
    
    return EasResult.Success(allEvents)
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
        return EasResult.Success(emptyList())
    }
    
    // Запрашиваем события с пагинацией
    val allEvents = mutableListOf<EasCalendarEvent>()
    var moreAvailable = true
    var iterations = 0
    val maxIterations = 100
    
    while (moreAvailable && iterations < maxIterations) {
        iterations++
        
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
                <airsyncbase:BodyPreference>
                    <airsyncbase:Type>1</airsyncbase:Type>
                    <airsyncbase:TruncationSize>200000</airsyncbase:TruncationSize>
                </airsyncbase:BodyPreference>
            </Options>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val result = deps.executeEasCommand("Sync", syncXml) { responseXml ->
            val newSyncKey = deps.extractValue(responseXml, "SyncKey")
            if (newSyncKey != null) {
                syncKey = newSyncKey
            }
            moreAvailable = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
            parseCalendarEvents(responseXml)
        }
        
        when (result) {
            is EasResult.Success -> {
                allEvents.addAll(result.data)
                if (result.data.isEmpty()) {
                    moreAvailable = false
                }
            }
            is EasResult.Error -> return result
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
        attendees: List<String>
    ): EasResult<String> {
        val calendarFolderId = getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")
        
        // Шаг 1: Получаем начальный SyncKey
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
        
        // Шаг 2: Полное продвижение SyncKey до актуального состояния
        // КРИТИЧНО для Exchange 2007 SP1: сервер может отклонить команду Add
        // с начальным SyncKey (нужно сначала подтвердить все существующие элементы)
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
                    moreAvailable = false // Прерываем, пробуем с текущим syncKey
                }
            }
        }
        
        android.util.Log.d("EasCalendarService", "createCalendarEventEas: SyncKey advanced in $syncIterations iterations, syncKey=$syncKey")
        
        // Шаг 3: Создание события
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
                    </ApplicationData>
                </Add>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", createXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status == "1") {
                deps.extractValue(responseXml, "ServerId") ?: clientId
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
        attendees: List<String>
    ): EasResult<Boolean> {
        val calendarFolderId = getCalendarFolderId()
            ?: return EasResult.Error("Папка календаря не найдена")
        
        // Получаем SyncKey
        val syncKeyResult = deps.refreshSyncKey(calendarFolderId, "0")
        val syncKey = when (syncKeyResult) {
            is EasResult.Success -> syncKeyResult.data
            is EasResult.Error -> return EasResult.Error(syncKeyResult.message)
        }
        
        if (syncKey == "0") {
            return EasResult.Error("Не удалось получить SyncKey")
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
        // Шаг 1: Получаем начальный SyncKey
        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
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
        
        // Шаг 2: Полное продвижение SyncKey до актуального состояния
        // КРИТИЧНО: WindowSize=1 (минимум по спецификации MS-ASCMD).
        // WindowSize=0 невалидно и может быть проигнорировано некоторыми серверами,
        // что приводит к неактуальному SyncKey и последующему отказу удаления.
        // Цикл нужен для обработки <MoreAvailable> — SyncKey продвигается пошагово.
        var moreAvailable = true
        var syncIterations = 0
        val maxSyncIterations = 50
        
        while (moreAvailable && syncIterations < maxSyncIterations) {
            syncIterations++
            val syncXml = """<?xml version="1.0" encoding="UTF-8"?>
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
            
            val syncResult = deps.executeEasCommand("Sync", syncXml) { responseXml ->
                val newKey = deps.extractValue(responseXml, "SyncKey")
                val hasMore = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                Pair(newKey ?: syncKey, hasMore)
            }
            
            when (syncResult) {
                is EasResult.Success -> {
                    syncKey = syncResult.data.first
                    moreAvailable = syncResult.data.second
                }
                is EasResult.Error -> {
                    moreAvailable = false // Прерываем, пробуем удалить с текущим syncKey
                }
            }
        }
        
        android.util.Log.d("EasCalendarService", "deleteCalendarEventEas: SyncKey advanced in $syncIterations iterations, syncKey=$syncKey")
        
        // Шаг 3: Удаление
        val deleteXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <Commands>
                <Delete>
                    <ServerId>$serverId</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val deleteResult = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
            when (status) {
                1 -> true  // Успех
                8 -> true  // Object not found — уже удалено, считаем успехом
                3 -> throw Exception("INVALID_SYNCKEY") // SyncKey устарел
                else -> false
            }
        }
        
        // Retry при INVALID_SYNCKEY или неуспешном удалении: полный сброс SyncKey
        val needsRetry = (deleteResult is EasResult.Success && !deleteResult.data) ||
                         (deleteResult is EasResult.Error && deleteResult.message.contains("INVALID_SYNCKEY"))
        
        if (needsRetry) {
            android.util.Log.w("EasCalendarService", "Delete failed, retrying with full SyncKey reset for serverId=$serverId")
            
            // Полный сброс: SyncKey=0 → начальный → полное продвижение → удаление
            var retryKey = "0"
            val retryInitResult = deps.executeEasCommand("Sync", initialXml) { responseXml ->
                deps.extractValue(responseXml, "SyncKey") ?: "0"
            }
            when (retryInitResult) {
                is EasResult.Success -> retryKey = retryInitResult.data
                is EasResult.Error -> return deleteResult // Не смогли даже инициализировать
            }
            if (retryKey == "0") return deleteResult
            
            // Полное продвижение
            var retryMore = true
            var retryIter = 0
            while (retryMore && retryIter < maxSyncIterations) {
                retryIter++
                val retrySyncXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$retryKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <GetChanges>1</GetChanges>
            <WindowSize>100</WindowSize>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
                
                val retrySyncResult = deps.executeEasCommand("Sync", retrySyncXml) { responseXml ->
                    val newKey = deps.extractValue(responseXml, "SyncKey")
                    val hasMore = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                    Pair(newKey ?: retryKey, hasMore)
                }
                when (retrySyncResult) {
                    is EasResult.Success -> {
                        retryKey = retrySyncResult.data.first
                        retryMore = retrySyncResult.data.second
                    }
                    is EasResult.Error -> retryMore = false
                }
            }
            
            // Повторная попытка удаления с полностью актуальным SyncKey
            val retryDeleteXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$retryKey</SyncKey>
            <CollectionId>$calendarFolderId</CollectionId>
            <Commands>
                <Delete>
                    <ServerId>$serverId</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
            
            return deps.executeEasCommand("Sync", retryDeleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")?.toIntOrNull() ?: 0
                status == 1 || status == 8
            }
        }
        
        return deleteResult
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
        
        val authHeader = deps.performNtlmHandshake(ewsUrl, findItemRequest, "FindItem")
            ?: return EasResult.Error("NTLM handshake failed")
        
        val response = deps.executeNtlmRequest(ewsUrl, findItemRequest, authHeader, "FindItem")
            ?: return EasResult.Error("Ошибка выполнения EWS запроса")
        
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
        attendees: List<String>
    ): EasResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ewsUrl = deps.getEwsUrl()
                
                val escapedSubject = deps.escapeXml(subject)
                val escapedLocation = deps.escapeXml(location)
                val escapedBody = deps.escapeXml(body)
                
                // КРИТИЧНО: Формат даты ДОЛЖЕН быть с 'Z' на конце для UTC!
                // Microsoft: "Time values are stored on the Exchange server in Coordinate Universal Time (UTC)"
                // Формат: yyyy-MM-dd'T'HH:mm:ss'Z' (ISO 8601 UTC)
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val startTimeStr = dateFormat.format(java.util.Date(startTime))
                val endTimeStr = dateFormat.format(java.util.Date(endTime))
                
                // Маппинг LegacyFreeBusyStatus
                val ewsBusyStatus = when (busyStatus) {
                    0 -> "Free"
                    1 -> "Tentative"
                    3 -> "OOF"
                    else -> "Busy"
                }
                
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
                    append("""xmlns:xsd="http://www.w3.org/2001/XMLSchema" """)
                    append("""xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" """)
                    append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">""")
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
                    append("</t:CalendarItem>")
                    append("</Items>")
                    append("</CreateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                android.util.Log.d("EasCalendarService", "createCalendarEventEws: Request: $soapRequest")
                
                // Пробуем NTLM аутентификацию
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                val responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
                
                // Проверяем на ошибки
                if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                    return@withContext EasResult.Error("Ошибка схемы EWS")
                }
                
                // Извлекаем ItemId
                val itemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                
                // КРИТИЧНО: Проверяем ОБА условия
                val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
                val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                                responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
                if (itemId != null) {
                    EasResult.Success(itemId)
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
        oldSubject: String? = null
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
                
                // КРИТИЧНО: Формат даты ДОЛЖЕН быть с 'Z' на конце для UTC!
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val startTimeStr = dateFormat.format(java.util.Date(startTime))
                val endTimeStr = dateFormat.format(java.util.Date(endTime))
                
                // Маппинг LegacyFreeBusyStatus
                val ewsBusyStatus = when (busyStatus) {
                    0 -> "Free"
                    1 -> "Tentative"
                    3 -> "OOF"
                    else -> "Busy"
                }
                
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
                    
                    append("</t:Updates>")
                    append("</t:ItemChange>")
                    append("</m:ItemChanges>")
                    append("</m:UpdateItem>")
                    append("</soap:Body>")
                    append("</soap:Envelope>")
                }
                
                // NTLM аутентификация
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "UpdateItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                
                val responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "UpdateItem")
                    ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
                
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
                val request = EasXmlTemplates.ewsDeleteItem(serverId)
                
                val authHeader = deps.performNtlmHandshake(ewsUrl, request, "DeleteItem")
                    ?: return@withContext EasResult.Error("NTLM handshake failed")
                
                val response = deps.executeNtlmRequest(ewsUrl, request, authHeader, "DeleteItem")
                    ?: return@withContext EasResult.Error("Ошибка выполнения EWS запроса")
                
                val responseCode = EasPatterns.EWS_RESPONSE_CODE.find(response)?.groupValues?.get(1)
                EasResult.Success(responseCode == "NoError")
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
                
                val authHeader = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
                    ?: return@withContext EasResult.Error("NTLM handshake failed")
                
                val response = deps.executeNtlmRequest(ewsUrl, findRequest, authHeader, "FindItem")
                    ?: return@withContext EasResult.Error("Failed to execute FindItem")
                
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
            
            val categories = extractCalendarCategories(dataXml)
            val isRecurring = dataXml.contains("<calendar:Recurrence>") || dataXml.contains("<Recurrence>")
            val lastModified = deps.parseEasDate(extractCalendarValue(dataXml, "DtStamp")) ?: System.currentTimeMillis()
            
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
                    attendees = parseAttendees(dataXml),
                    categories = categories,
                    isRecurring = isRecurring,
                    lastModified = lastModified
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
            val organizer = """<t:Organizer>.*?<t:EmailAddress>(.*?)</t:EmailAddress>.*?</t:Organizer>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(itemXml)?.groupValues?.get(1) ?: ""
            val isRecurring = itemXml.contains("<t:Recurrence>") || itemXml.contains("<t:IsRecurring>true</t:IsRecurring>")
            
            // Парсим lastModified из EWS
            val lastModifiedStr = """<t:LastModifiedTime>(.*?)</t:LastModifiedTime>""".toRegex().find(itemXml)?.groupValues?.get(1)
            val lastModified = parseEwsDateTime(lastModifiedStr) ?: System.currentTimeMillis()
            
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
            
            events.add(
                EasCalendarEvent(
                    serverId = itemId,
                    subject = subject,
                    startTime = startTime,
                    endTime = endTime,
                    location = location,
                    body = body,
                    allDayEvent = isAllDay,
                    reminder = 0,
                    busyStatus = 2,
                    sensitivity = 0,
                    organizer = organizer,
                    attendees = emptyList(),
                    categories = categories,
                    isRecurring = isRecurring,
                    lastModified = lastModified
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
    
    private fun formatEwsDate(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestamp))
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
}

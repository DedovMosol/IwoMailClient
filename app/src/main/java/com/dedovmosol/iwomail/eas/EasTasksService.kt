package com.dedovmosol.iwomail.eas

import com.dedovmosol.iwomail.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Сервис для работы с задачами Exchange (EAS/EWS)
 * Выделен из EasClient для соблюдения принципа SRP (Single Responsibility)
 * 
 * Отвечает за:
 * - Синхронизацию задач
 * - Создание, обновление, удаление задач
 * - Работу с EWS для Exchange 2007
 */
class EasTasksService internal constructor(
    private val deps: TasksServiceDependencies
) {
    
    interface EasCommandExecutor {
        suspend operator fun <T> invoke(command: String, xml: String, parser: (String) -> T): EasResult<T>
    }
    
    /**
     * Зависимости для EasTasksService
     * Передаются из EasClient для избежания циклических зависимостей
     */
    class TasksServiceDependencies(
        val executeEasCommand: EasCommandExecutor,
        val folderSync: suspend (String) -> EasResult<FolderSyncResponse>,
        val extractValue: (String, String) -> String?,
        val escapeXml: (String) -> String,
        val getEasVersion: () -> String,
        val isVersionDetected: () -> Boolean,
        val detectEasVersion: suspend () -> EasResult<String>,
        val getTasksFolderId: suspend () -> String?,
        val getDeletedItemsFolderId: suspend () -> String?,
        val performNtlmHandshake: suspend (String, String, String) -> String?,
        val executeNtlmRequest: suspend (String, String, String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String, String) -> String?,
        val getEwsUrl: () -> String,
        val sendMail: suspend (String, String, String, String, String, Int) -> EasResult<Boolean>
    )
    
    // Кэш ID папки задач
    @Volatile private var cachedTasksFolderId: String? = null
    
    /**
     * Синхронизация задач из папки Tasks на сервере Exchange
     */
    suspend fun syncTasks(): EasResult<List<EasTask>> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        if (majorVersion < 14) {
            return syncTasksEws()
        }
        
        val foldersResult = deps.folderSync("0")
        val folders = when (foldersResult) {
            is EasResult.Success -> foldersResult.data.folders
            is EasResult.Error -> null
        }
        
        var tasksFolderId = folders?.find { it.type == 7 }?.serverId
        
        if (tasksFolderId != null) {
            cachedTasksFolderId = tasksFolderId
        } else {
            tasksFolderId = cachedTasksFolderId ?: deps.getTasksFolderId()
        }
        
        if (tasksFolderId == null) {
            return EasResult.Error("Папка задач не найдена")
        }
        
        return syncTasksEas(tasksFolderId)
    }
    
    /**
     * Создание задачи на сервере Exchange
     */
    suspend fun createTask(
        subject: String,
        body: String = "",
        startDate: Long = 0,
        dueDate: Long = 0,
        importance: Int = 1,
        reminderSet: Boolean = false,
        reminderTime: Long = 0,
        assignTo: String? = null
    ): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (!assignTo.isNullOrBlank()) {
            createTaskEws(subject, body, startDate, dueDate, importance, reminderSet, reminderTime, assignTo)
        } else if (majorVersion < 14) {
            createTaskEws(subject, body, startDate, dueDate, importance, reminderSet, reminderTime, null)
        } else {
            createTaskEas(subject, body, startDate, dueDate, importance, reminderSet, reminderTime)
        }
    }
    
    /**
     * Обновление задачи на сервере Exchange
     */
    suspend fun updateTask(
        serverId: String,
        subject: String,
        body: String = "",
        startDate: Long = 0,
        dueDate: Long = 0,
        complete: Boolean = false,
        importance: Int = 1,
        reminderSet: Boolean = false,
        reminderTime: Long = 0,
        oldSubject: String? = null
    ): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        // КРИТИЧНО: Exchange 2007 (EAS 12.x) НЕ поддерживает полноценный Change в Sync
        // Используем EWS UpdateItem для обновления задач
        if (majorVersion < 14) {
            return updateTaskEws(serverId, subject, body, startDate, dueDate, complete, importance, oldSubject)
        }
        
        val tasksFolderId = cachedTasksFolderId ?: deps.getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)

        for (attempt in 0..1) {
            val syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
            
            val updateXml = buildUpdateXml(
                syncKey, tasksFolderId, serverId, escapedSubject, escapedBody,
                startDate, dueDate, complete, importance, reminderSet, reminderTime,
                majorVersion, dateFormat
            )
            
            val result = deps.executeEasCommand("Sync", updateXml) { responseXml ->
                val collectionStatus = deps.extractValue(responseXml, "Status")
                if (collectionStatus == "3" || collectionStatus == "12") {
                    syncKeyCache.remove(tasksFolderId)
                    throw InvalidSyncKeyException(collectionStatus)
                }
                if (collectionStatus != "1") {
                    throw Exception("Collection Status=$collectionStatus")
                }
                
                if (responseXml.contains("<Responses>") && responseXml.contains("<Change>")) {
                    val changeStatusMatch = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
                        .find(responseXml)
                    
                    if (changeStatusMatch != null) {
                        val changeStatus = changeStatusMatch.groupValues[1]
                        when (changeStatus) {
                            "1" -> true
                            "6" -> throw Exception("Change Status=6: Error in client/server conversion")
                            "7" -> throw Exception("Change Status=7: Conflict")
                            "8" -> throw Exception("Change Status=8: Object not found")
                            else -> throw Exception("Change Status=$changeStatus")
                        }
                    } else {
                        true
                    }
                } else {
                    true
                }
            }
            when (result) {
                is EasResult.Success -> return result
                is EasResult.Error -> {
                    if (attempt == 0 && result.message.contains("InvalidSyncKey")) continue
                    return result
                }
            }
        }
        return EasResult.Error("Не удалось обновить задачу после retry")
    }
    
    /**
     * Удаление задачи (перемещение в корзину)
     */
    suspend fun deleteTask(serverId: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        if (deps.getEasVersion().startsWith("12.")) {
            return deleteTaskEws(serverId)
        }
        
        val tasksFolderId = cachedTasksFolderId ?: deps.getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        for (attempt in 0..1) {
            val syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
            
            val deleteXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(tasksFolderId)}</CollectionId>
            <DeletesAsMoves>1</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>${deps.escapeXml(serverId)}</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
            
            val result = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                if (status == "3" || status == "12") {
                    syncKeyCache.remove(tasksFolderId)
                    throw InvalidSyncKeyException(status)
                }
                status == "1"
            }
            when (result) {
                is EasResult.Success -> return result
                is EasResult.Error -> {
                    if (attempt == 0 && result.message.contains("InvalidSyncKey")) continue
                    return result
                }
            }
        }
        return EasResult.Error("Не удалось удалить задачу после retry")
    }
    
    /**
     * Окончательное удаление задачи
     */
    suspend fun deleteTaskPermanently(serverId: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        if (deps.getEasVersion().startsWith("12.")) {
            return deleteTaskEwsPermanently(serverId)
        }
        
        val tasksFolderId = cachedTasksFolderId ?: deps.getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        for (attempt in 0..1) {
            val syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
            
            val deleteXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>${deps.escapeXml(tasksFolderId)}</CollectionId>
            <DeletesAsMoves>0</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>${deps.escapeXml(serverId)}</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
            
            val result = deps.executeEasCommand("Sync", deleteXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                if (status == "3" || status == "12") {
                    syncKeyCache.remove(tasksFolderId)
                    throw InvalidSyncKeyException(status)
                }
                status == "1"
            }
            when (result) {
                is EasResult.Success -> return result
                is EasResult.Error -> {
                    if (attempt == 0 && result.message.contains("InvalidSyncKey")) continue
                    return result
                }
            }
        }
        return EasResult.Error("Не удалось окончательно удалить задачу после retry")
    }
    
    /**
     * Batch-удаление задач (в корзину). Один EWS DeleteItem на весь пакет.
     */
    suspend fun deleteTasksBatch(serverIds: List<String>): EasResult<Int> {
        if (serverIds.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        return if (deps.getEasVersion().startsWith("12.")) {
            deleteTasksBatchEws(serverIds, "MoveToDeletedItems")
        } else {
            var deleted = 0
            for (sid in serverIds) {
                if (deleteTask(sid) is EasResult.Success) deleted++
            }
            EasResult.Success(deleted)
        }
    }

    /**
     * Batch окончательное удаление задач (HardDelete). Один EWS DeleteItem на весь пакет.
     */
    suspend fun deleteTasksPermanentlyBatch(serverIds: List<String>): EasResult<Int> {
        if (serverIds.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        return if (deps.getEasVersion().startsWith("12.")) {
            deleteTasksBatchEws(serverIds, "HardDelete")
        } else {
            var deleted = 0
            for (sid in serverIds) {
                if (deleteTaskPermanently(sid) is EasResult.Success) deleted++
            }
            EasResult.Success(deleted)
        }
    }

    /**
     * Восстановление задачи из корзины
     * Перемещает задачу из Deleted Items обратно в Tasks
     */
    suspend fun restoreTask(serverId: String, subject: String? = null): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            restoreTaskEas(serverId)
        } else {
            restoreTaskEws(serverId, subject)
        }
    }
    
    // ==================== Private EAS methods ====================
    
    private suspend fun getSyncKey(tasksFolderId: String): String? {
        syncKeyCache[tasksFolderId]?.let { return it }

        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>${deps.escapeXml(tasksFolderId)}</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val result = deps.executeEasCommand("Sync", initialXml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        return when (result) {
            is EasResult.Success -> {
                val key = result.data
                if (key != "0") { syncKeyCache[tasksFolderId] = key; key } else null
            }
            is EasResult.Error -> null
        }
    }
    
    private suspend fun syncTasksEas(tasksFolderId: String): EasResult<List<EasTask>> {
        val safeFolderId = deps.escapeXml(tasksFolderId)
        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>$safeFolderId</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        var syncKey = "0"
        val initialResult = deps.executeEasCommand("Sync", initialXml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        when (initialResult) {
            is EasResult.Success -> syncKey = initialResult.data
            is EasResult.Error -> return EasResult.Success(emptyList())
        }
        
        if (syncKey == "0") {
            return EasResult.Success(emptyList())
        }
        
        val allTasks = mutableListOf<EasTask>()
        var hasMore = true
        var iteration = 0
        val maxIterations = 50 // До 5000 задач (windowSize=100)
        val syncStartTime = System.currentTimeMillis()
        val maxSyncDurationMs = 120_000L // 2 минуты — достаточно для 500+ задач
        var previousSyncKey = syncKey
        var sameKeyCount = 0
        
        while (hasMore && iteration < maxIterations) {
            iteration++
            
            // Проверка timeout
            if (System.currentTimeMillis() - syncStartTime > maxSyncDurationMs) {
                android.util.Log.w("EasTasksService", "Tasks sync timeout after ${iteration} iterations")
                break
            }
            
            kotlinx.coroutines.yield() // Даём возможность отменить корутину
            
            val syncXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase">
    <Collections>
        <Collection>
            <SyncKey>${deps.escapeXml(syncKey)}</SyncKey>
            <CollectionId>$safeFolderId</CollectionId>
            <DeletesAsMoves>1</DeletesAsMoves>
            <GetChanges>1</GetChanges>
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
                val status = deps.extractValue(responseXml, "Status")
                if (status == "12") {
                    return@executeEasCommand Triple("0", emptyList<EasTask>(), false)
                }

                val newSyncKey = deps.extractValue(responseXml, "SyncKey") ?: syncKey
                val moreAvailable = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
                val tasks = parseTaskSyncResponse(responseXml)
                Triple(newSyncKey, tasks, moreAvailable)
            }
            
            when (result) {
                is EasResult.Success -> {
                    val (newSyncKey, tasks, moreAvailable) = result.data
                    
                    // Защита от зацикливания: если сервер возвращает тот же syncKey
                    if (newSyncKey == previousSyncKey) {
                        sameKeyCount++
                        if (sameKeyCount >= 3) {
                            android.util.Log.w("EasTasksService", "SyncKey not changing, breaking loop")
                            hasMore = false
                        }
                    } else {
                        sameKeyCount = 0
                        previousSyncKey = newSyncKey
                    }
                    
                    syncKey = newSyncKey
                    allTasks.addAll(tasks)
                    hasMore = moreAvailable
                    
                    // Если сервер говорит moreAvailable но не отдаёт данные - прерываем
                    if (moreAvailable && tasks.isEmpty()) {
                        android.util.Log.w("EasTasksService", "Server says moreAvailable but no tasks, breaking")
                        hasMore = false
                    }
                }
                is EasResult.Error -> hasMore = false
            }
        }
        
        if (syncKey != "0") {
            syncKeyCache[tasksFolderId] = syncKey
        }

        return EasResult.Success(allTasks)
    }
    
    private suspend fun createTaskEas(
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long
    ): EasResult<String> {
        val tasksFolderId = cachedTasksFolderId ?: deps.getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        var syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
        
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        
        for (attempt in 0..1) {
            val createXml = buildCreateXml(
                syncKey, tasksFolderId, clientId, escapedSubject, escapedBody,
                startDate, dueDate, importance, reminderSet, reminderTime, dateFormat
            )
            
            val result = deps.executeEasCommand("Sync", createXml) { responseXml ->
                val status = deps.extractValue(responseXml, "Status")
                if (status == "1") {
                    val newKey = deps.extractValue(responseXml, "SyncKey")
                    if (newKey != null) syncKeyCache[tasksFolderId] = newKey
                    deps.extractValue(responseXml, "ServerId") ?: clientId
                } else if (status == "3" || status == "12") {
                    throw InvalidSyncKeyException(status ?: "3")
                } else {
                    throw Exception("Ошибка создания задачи: Status=$status")
                }
            }
            
            when (result) {
                is EasResult.Success -> return result
                is EasResult.Error -> {
                    if (attempt == 0 && result.message.contains("InvalidSyncKey")) {
                        syncKeyCache.remove(tasksFolderId)
                        syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
                        continue
                    }
                    return result
                }
            }
        }
        return EasResult.Error("Не удалось создать задачу после retry")
    }
    
    private class InvalidSyncKeyException(status: String) : Exception("InvalidSyncKey: Status=$status")
    
    private fun buildCreateXml(
        syncKey: String,
        tasksFolderId: String,
        clientId: String,
        escapedSubject: String,
        escapedBody: String,
        startDate: Long,
        dueDate: Long,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long,
        dateFormat: java.text.SimpleDateFormat
    ): String = buildString {
        val esc = deps.escapeXml
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks">""")
        append("<Collections><Collection>")
        append("<SyncKey>${esc(syncKey)}</SyncKey>")
        append("<CollectionId>${esc(tasksFolderId)}</CollectionId>")
        append("<Commands><Add>")
        append("<ClientId>${esc(clientId)}</ClientId>")
        append("<ApplicationData>")
        append("<tasks:Subject>$escapedSubject</tasks:Subject>")
        append("<airsyncbase:Body>")
        append("<airsyncbase:Type>1</airsyncbase:Type>")
        append("<airsyncbase:Data>$escapedBody</airsyncbase:Data>")
        append("</airsyncbase:Body>")
        append("<tasks:Importance>$importance</tasks:Importance>")
        append("<tasks:Complete>0</tasks:Complete>")
        if (startDate > 0) {
            append("<tasks:StartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:StartDate>")
            append("<tasks:UtcStartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:UtcStartDate>")
        }
        if (dueDate > 0) {
            append("<tasks:DueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:DueDate>")
            append("<tasks:UtcDueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:UtcDueDate>")
        }
        if (reminderSet && reminderTime > 0) {
            append("<tasks:ReminderSet>1</tasks:ReminderSet>")
            append("<tasks:ReminderTime>${dateFormat.format(java.util.Date(reminderTime))}</tasks:ReminderTime>")
        } else {
            append("<tasks:ReminderSet>0</tasks:ReminderSet>")
        }
        append("</ApplicationData>")
        append("</Add></Commands>")
        append("</Collection></Collections>")
        append("</Sync>")
    }
    
    private fun buildUpdateXml(
        syncKey: String,
        tasksFolderId: String,
        serverId: String,
        escapedSubject: String,
        escapedBody: String,
        startDate: Long,
        dueDate: Long,
        complete: Boolean,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long,
        majorVersion: Int,
        dateFormat: java.text.SimpleDateFormat
    ): String = buildString {
        val esc = deps.escapeXml
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        if (majorVersion >= 14) {
            append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks">""")
        } else {
            append("""<Sync xmlns="AirSync" xmlns:tasks="Tasks">""")
        }
        append("<Collections><Collection>")
        append("<SyncKey>${esc(syncKey)}</SyncKey>")
        append("<CollectionId>${esc(tasksFolderId)}</CollectionId>")
        append("<Commands><Change>")
        append("<ServerId>${esc(serverId)}</ServerId>")
        append("<ApplicationData>")
        append("<tasks:Subject>$escapedSubject</tasks:Subject>")
        
        // КРИТИЧНО: Exchange 2007 (EAS 12.x) НЕ поддерживает Body в Change!
        // Body поддерживается только в EAS 14+ через airsyncbase:Body
        if (majorVersion >= 14) {
            append("<airsyncbase:Body>")
            append("<airsyncbase:Type>1</airsyncbase:Type>")
            append("<airsyncbase:Data>$escapedBody</airsyncbase:Data>")
            append("</airsyncbase:Body>")
        }
        // Для EAS 12.x Body НЕ добавляем - это вызывает Status=6
        
        append("<tasks:Importance>$importance</tasks:Importance>")
        append("<tasks:Complete>${if (complete) "1" else "0"}</tasks:Complete>")
        
        // DateCompleted поддерживается только в EAS 14+
        if (complete && majorVersion >= 14) {
            append("<tasks:DateCompleted>${dateFormat.format(java.util.Date())}</tasks:DateCompleted>")
        }
        
        if (startDate > 0) {
            append("<tasks:StartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:StartDate>")
            // UtcStartDate только для EAS 14+
            if (majorVersion >= 14) {
                append("<tasks:UtcStartDate>${dateFormat.format(java.util.Date(startDate))}</tasks:UtcStartDate>")
            }
        }
        if (dueDate > 0) {
            append("<tasks:DueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:DueDate>")
            // UtcDueDate только для EAS 14+
            if (majorVersion >= 14) {
                append("<tasks:UtcDueDate>${dateFormat.format(java.util.Date(dueDate))}</tasks:UtcDueDate>")
            }
        }
        if (reminderSet && reminderTime > 0) {
            append("<tasks:ReminderSet>1</tasks:ReminderSet>")
            append("<tasks:ReminderTime>${dateFormat.format(java.util.Date(reminderTime))}</tasks:ReminderTime>")
        } else {
            append("<tasks:ReminderSet>0</tasks:ReminderSet>")
        }
        append("</ApplicationData>")
        append("</Change></Commands>")
        append("</Collection></Collections>")
        append("</Sync>")
    }
    
    // ==================== Private EWS methods ====================
    
    private suspend fun syncTasksEws(): EasResult<List<EasTask>> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val allActiveTasks = mutableListOf<EasTask>()

            // 0. Диагностика: GetFolder на tasks → TotalCount + ChildFolderCount
            val folderInfo = getTasksFolderInfo(ewsUrl)
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "syncTasksEws: GetFolder tasks → totalCount=${folderInfo.first}, childFolders=${folderInfo.second}")

            // 1. Синхронизация из корневой папки Tasks (DistinguishedFolderId)
            val rootTasks = syncTasksFromFolderEws(ewsUrl, "tasks", isDeleted = false) { offset, pageSize ->
                buildEwsFindTasksRequest(offset, pageSize)
            }
            allActiveTasks.addAll(rootTasks)
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: root tasks folder: ${rootTasks.size}")
            
            // 2. Обнаруживаем подпапки Tasks и ищем в каждой.
            val subfolderIds = discoverTaskSubfolderIds(ewsUrl)
            if (subfolderIds.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: discovered ${subfolderIds.size} task subfolders")
                for (subfolderId in subfolderIds) {
                    val subTasks = syncTasksFromFolderEws(ewsUrl, "tasks-sub-${subfolderId.take(8)}", isDeleted = false) { offset, pageSize ->
                        buildEwsFindTasksByFolderIdRequest(subfolderId, offset, pageSize)
                    }
                    allActiveTasks.addAll(subTasks)
                }
            }

            // 2b. Подпапки непосредственно внутри "tasks" (Deep от msgfolderroot может пропустить)
            val directSubfolders = discoverDirectTaskSubfolders(ewsUrl)
            if (directSubfolders.isNotEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                    "syncTasksEws: ${directSubfolders.size} direct subfolders under tasks")
                for (subfolderId in directSubfolders) {
                    if (subfolderIds.contains(subfolderId)) continue
                    val subTasks = syncTasksFromFolderEws(ewsUrl, "tasks-direct-${subfolderId.take(8)}", isDeleted = false) { offset, pageSize ->
                        buildEwsFindTasksByFolderIdRequest(subfolderId, offset, pageSize)
                    }
                    allActiveTasks.addAll(subTasks)
                }
            }

            // todosearch (DistinguishedFolderId "todosearch") введён в Exchange 2010 (v14).
            // EWS-путь используется только для Exchange 2007 SP1 (majorVersion < 14),
            // поэтому todosearch гарантированно вернёт ErrorFolderNotFound — пропускаем.
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "syncTasksEws: skipping todosearch (not supported on Exchange 2007 SP1)")

            // 3. Дедупликация по serverId
            val uniqueActive = allActiveTasks.distinctBy { it.serverId }
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: active tasks total: ${uniqueActive.size} (before dedup: ${allActiveTasks.size})")
            
            // 4. Синхронизация удалённых задач
            val deletedTasks = syncTasksFromFolderEws(ewsUrl, "deleteditems", isDeleted = true) { offset, pageSize ->
                buildEwsFindDeletedTasksRequest(offset, pageSize)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: deleted tasks: ${deletedTasks.size}")
            
            val allTasks = uniqueActive + deletedTasks
            EasResult.Success(allTasks)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("EasTasksService", "syncTasksEws: exception: ${e.message}")
            EasResult.Success(emptyList())
        }
    }
    
    private suspend fun syncTasksFromFolderEws(
        ewsUrl: String, 
        folderLabel: String, 
        isDeleted: Boolean,
        buildRequest: (offset: Int, pageSize: Int) -> String
    ): List<EasTask> {
        try {
            // ЭТАП 1: FindItem с IdOnly — получаем ВСЕ ItemId из папки.
            // Exchange 2007 SP1 при AllProperties/IdOnly+task:* может фильтровать items.
            // IdOnly без AdditionalProperties гарантирует ВСЕ элементы.
            val allItemIds = mutableListOf<String>()
            var offset = 0
            val pageSize = 200
            var hasMore = true
            
            while (hasMore) {
                yield()
                val findRequest = buildRequest(offset, pageSize)
                
                var responseXml = deps.tryBasicAuthEws(ewsUrl, findRequest, "FindItem")
                if (responseXml == null) {
                    val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
                        ?: break
                    responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
                        ?: break
                }
                
                val totalItemsMatch = Companion.REGEX_TOTAL_ITEMS_IN_VIEW.find(responseXml)
                val totalItems = totalItemsMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val indexedPagingOffsetMatch = Companion.REGEX_INDEXED_PAGING_OFFSET.find(responseXml)
                val nextOffset = indexedPagingOffsetMatch?.groupValues?.get(1)?.toIntOrNull()
                val includesLast = responseXml.contains("IncludesLastItemInRange=\"true\"")
                val hasError = responseXml.contains("ResponseClass=\"Error\"")
                
                if (hasError) {
                    android.util.Log.w("EasTasksService",
                        "syncTasksFromFolderEws($folderLabel): FindItem returned error at offset=$offset")
                    break
                }
                
                val pageIds = Companion.REGEX_ITEM_ID_ATTR.findAll(responseXml).map { it.groupValues[1] }.toList()
                allItemIds.addAll(pageIds)
                
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", 
                    "syncTasksFromFolderEws($folderLabel): offset=$offset, totalItemsInView=$totalItems, " +
                    "pageItemIds=${pageIds.size}, includesLast=$includesLast")
                
                if (includesLast || pageIds.isEmpty()) {
                    hasMore = false
                } else if (nextOffset != null && nextOffset > offset) {
                    offset = nextOffset
                    if (offset > 5000) {
                        android.util.Log.w("EasTasksService", "syncTasksFromFolderEws($folderLabel): stopping at offset=$offset")
                        hasMore = false
                    }
                } else {
                    hasMore = false
                }
            }
            
            val uniqueIds = allItemIds.distinct()
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", 
                "syncTasksFromFolderEws($folderLabel): FindItem complete, ${uniqueIds.size} unique ItemIds")
            
            if (uniqueIds.isEmpty()) return emptyList()
            
            // ЭТАП 2: GetItem с AllProperties — получаем ВСЕ свойства задач (включая Body).
            val tasks = getTaskDetailsEws(ewsUrl, uniqueIds, folderLabel)
            return tasks.map { it.copy(isDeleted = isDeleted) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("EasTasksService", "syncTasksFromFolderEws($folderLabel): exception: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * GetItem для списка ItemId — возвращает полные EasTask объекты (включая Body).
     * Exchange 2007 SP1: GetItem с AllProperties возвращает ВСЕ свойства задач.
     */
    private suspend fun getTaskDetailsEws(
        ewsUrl: String,
        itemIds: List<String>,
        label: String
    ): List<EasTask> {
        val allTasks = mutableListOf<EasTask>()
        val seenIds = mutableSetOf<String>()
        
        for (batch in itemIds.chunked(50)) {
            kotlinx.coroutines.yield()
            
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
            </ItemShape>
            <ItemIds>
                $itemIdsXml
            </ItemIds>
        </GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, getItemRequest, "GetItem")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, getItemRequest, "GetItem") ?: continue
                responseXml = deps.executeNtlmRequest(ewsUrl, getItemRequest, ntlmAuth, "GetItem") ?: continue
            }
            
            if (responseXml.isBlank()) continue
            
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "getTaskDetailsEws($label): batch ${batch.size} ids, response len=${responseXml.length}")
            
            val pageTasks = parseEwsTasksResponse(responseXml)
            allTasks.addAll(pageTasks)
            seenIds.addAll(pageTasks.map { it.serverId })
            
            if (pageTasks.size < batch.size) {
                val parsedIds = pageTasks.map { it.serverId }.toSet()
                val missingIds = batch.filter { it !in parsedIds && it !in seenIds }
                if (missingIds.isNotEmpty()) {
                    android.util.Log.w("EasTasksService",
                        "getTaskDetailsEws($label): ${missingIds.size} items not parsed from GetItem, trying fragment fallback")
                    val itemsBlockMatch = Companion.REGEX_ITEMS_BLOCK.find(responseXml)
                    val searchArea = itemsBlockMatch?.groupValues?.get(1) ?: responseXml
                    for (missingId in missingIds) {
                        val idIdx = searchArea.indexOf(missingId)
                        if (idIdx < 0) continue
                        val fragStart = maxOf(0, idIdx - 200)
                        val fragEnd = minOf(searchArea.length, idIdx + 3000)
                        val fragment = searchArea.substring(fragStart, fragEnd)
                        parseTaskFromEwsItemXml(fragment, seenIds)?.let { allTasks.add(it) }
                    }
                }
            }
        }
        
        if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
            "getTaskDetailsEws($label): total ${allTasks.size} tasks from ${itemIds.size} ids")
        return allTasks.distinctBy { it.serverId }
    }
    
    private suspend fun createTaskEws(
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long,
        assignTo: String?
    ): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val soapRequest = buildEwsCreateTaskRequest(subject, body, startDate, dueDate, importance)
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "CreateItem")
            
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "CreateItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "CreateItem")
                    ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
            }
            
            if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                android.util.Log.e("EasTasksService", "createTaskEws: Schema error! Response (first 500 chars): ${responseXml.take(500)}")
                return@withContext EasResult.Error("Ошибка схемы EWS")
            }
            
            val itemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
            
            // КРИТИЧНО: Проверяем ОБА условия
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val result = if (itemId != null) {
                EasResult.Success(itemId)
            } else if (hasSuccess && hasNoError) {
                EasResult.Success("pending_sync_${System.currentTimeMillis()}")
            } else {
                EasResult.Error("Не удалось создать задачу через EWS")
            }
            
            // Отправляем уведомление если указан assignTo
            if (!assignTo.isNullOrBlank() && result is EasResult.Success) {
                sendTaskNotification(assignTo, subject, body, dueDate, importance)
            }
            
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error(e.message ?: "Ошибка создания задачи через EWS")
        }
    }
    
    private suspend fun deleteTaskEws(serverId: String): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = resolveEwsTaskItemId(ewsUrl, serverId)
                ?: return@withContext EasResult.Error("Не удалось найти задачу на сервере")
            val escapedItemId = deps.escapeXml(ewsItemId)
            
            val soapRequest = buildEwsDeleteRequest(escapedItemId, "MoveToDeletedItems")
            
            // КРИТИЧНО: Basic Auth first, NTLM fallback
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "DeleteItem")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                    ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
            }
            
            // КРИТИЧНО: Для delete проверяем Success+NoError ИЛИ ErrorItemNotFound (уже удалено)
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")
            val success = (hasSuccess && hasNoError) || hasNotFound
            
            EasResult.Success(success)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка EWS: ${e.message}")
        }
    }
    
    private suspend fun deleteTaskEwsPermanently(serverId: String): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = resolveEwsTaskItemId(ewsUrl, serverId)
                ?: return@withContext EasResult.Success(true)
            val escapedItemId = deps.escapeXml(ewsItemId)
            
            val soapRequest = buildEwsDeleteRequest(escapedItemId, "HardDelete")
            
            // КРИТИЧНО: Basic Auth first, NTLM fallback
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "DeleteItem")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                    ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
            }
            
            // КРИТИЧНО: Для delete проверяем Success+NoError ИЛИ ErrorItemNotFound (уже удалено)
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")
            val success = (hasSuccess && hasNoError) || hasNotFound
            
            EasResult.Success(success)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка EWS: ${e.message}")
        }
    }

    /**
     * Batch EWS DeleteItem для задач — один запрос с несколькими ItemId.
     * AffectedTaskOccurrences="AllOccurrences" обязателен для задач.
     */
    private suspend fun deleteTasksBatchEws(
        serverIds: List<String>,
        deleteType: String
    ): EasResult<Int> = withContext(Dispatchers.IO) {
        try {
            require(deleteType in listOf("HardDelete", "SoftDelete", "MoveToDeletedItems")) {
                "Invalid deleteType: $deleteType"
            }
            val ewsUrl = deps.getEwsUrl()
            val ewsItemIds = mutableListOf<String>()

            for (sid in serverIds) {
                val ewsItemId = resolveEwsTaskItemId(ewsUrl, sid)
                if (ewsItemId != null) {
                    ewsItemIds.add(ewsItemId)
                } else {
                    android.util.Log.w("EasTasksService",
                        "deleteTasksBatchEws: could not resolve EWS ItemId for $sid, skipping")
                }
            }

            if (ewsItemIds.isEmpty()) return@withContext EasResult.Success(0)

            val itemIdsXml = ewsItemIds.joinToString("\n") {
                """        <t:ItemId Id="${deps.escapeXml(it)}"/>"""
            }
            val soapBody = """
    <m:DeleteItem DeleteType="${deps.escapeXml(deleteType)}" AffectedTaskOccurrences="AllOccurrences">
        <m:ItemIds>
$itemIdsXml
        </m:ItemIds>
    </m:DeleteItem>""".trimIndent()
            val soapRequest = EasXmlTemplates.ewsSoapRequest(soapBody)

            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "DeleteItem")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                    ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
            }

            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "deleteTasksBatchEws: ${ewsItemIds.size} items, deleteType=$deleteType, response len=${responseXml.length}")

            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("NoError")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")

            if ((hasSuccess && hasNoError) || hasNotFound) {
                EasResult.Success(ewsItemIds.size)
            } else {
                val errorCode = "<(?:m:)?ResponseCode>(.*?)</(?:m:)?ResponseCode>"
                    .toRegex(RegexOption.DOT_MATCHES_ALL).find(responseXml)?.groupValues?.get(1)?.trim()
                EasResult.Error("EWS batch DeleteItem tasks: $errorCode")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка batch EWS tasks: ${e.message}")
        }
    }
    
    /**
     * Восстановление задачи через EAS MoveItems (для Exchange 2010+)
     */
    private suspend fun restoreTaskEas(serverId: String): EasResult<String> {
        val tasksFolderId = cachedTasksFolderId ?: deps.getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")
        
        val deletedItemsFolderId = deps.getDeletedItemsFolderId()
            ?: return EasResult.Error("Папка Deleted Items не найдена")
        
        // MoveItems для перемещения из Deleted Items в Tasks
        val moveXml = EasXmlTemplates.moveItems(
            listOf(serverId to deletedItemsFolderId),
            tasksFolderId
        )
        
        return deps.executeEasCommand("MoveItems", moveXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status == "3") { // 3 = Success для MoveItems
                deps.extractValue(responseXml, "DstMsgId") ?: serverId
            } else {
                throw Exception("Ошибка восстановления: Status=$status")
            }
        }
    }
    
    /**
     * Восстановление задачи через EWS MoveItem (для Exchange 2007)
     */
    private suspend fun restoreTaskEws(serverId: String, subject: String? = null): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                resolveEwsTaskItemId(ewsUrl, serverId, subject) ?: run {
                    return@withContext EasResult.Error("Задача не найдена")
                }
            }
            
            val soapRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:MoveItem>
            <m:ToFolderId>
                <t:DistinguishedFolderId Id="tasks"/>
            </m:ToFolderId>
            <m:ItemIds>
                <t:ItemId Id="${deps.escapeXml(ewsItemId)}"/>
            </m:ItemIds>
        </m:MoveItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "MoveItem")
            
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "MoveItem")
                if (ntlmAuth == null) {
                    return@withContext EasResult.Error("NTLM аутентификация не удалась")
                }
                
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "MoveItem")
                if (responseXml == null) {
                    return@withContext EasResult.Error("Не удалось выполнить запрос")
                }
            }
            
            val success = responseXml.contains("ResponseClass=\"Success\"") &&
                         (responseXml.contains("NoError") || responseXml.contains("<ResponseCode>NoError</ResponseCode>"))
            
            if (success) {
                val newItemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
                EasResult.Success(newItemId ?: serverId)
            } else {
                val errorMsg = Regex("MessageText>([^<]+)</").find(responseXml)?.groupValues?.get(1)
                    ?: "Не удалось восстановить задачу"
                EasResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка восстановления: ${e.message}")
        }
    }
    
    /**
     * Обновление задачи через EWS (для Exchange 2007)
     */
    private suspend fun updateTaskEws(
        serverId: String,
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        complete: Boolean,
        importance: Int,
        oldSubject: String? = null
    ): EasResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            // КРИТИЧНО: Используем СТАРЫЙ subject для поиска (если subject изменился)
            val searchSubject = oldSubject ?: subject
            
            val ewsItemId = resolveEwsTaskItemId(ewsUrl, serverId, searchSubject)
            if (ewsItemId == null) {
                return@withContext EasResult.Error("Не удалось найти задачу на сервере")
            }
            
            // КРИТИЧНО: Получаем ChangeKey через GetItem перед UpdateItem
            val getItemRequest = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <GetItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
            <ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
            </ItemShape>
            <ItemIds>
                <t:ItemId Id="${deps.escapeXml(ewsItemId)}"/>
            </ItemIds>
        </GetItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            var getItemResponse = deps.tryBasicAuthEws(ewsUrl, getItemRequest, "GetItem")
            if (getItemResponse == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, getItemRequest, "GetItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                getItemResponse = deps.executeNtlmRequest(ewsUrl, getItemRequest, ntlmAuth, "GetItem")
                    ?: return@withContext EasResult.Error("Не удалось получить ChangeKey")
            }
            
            // Извлекаем ChangeKey из ответа
            val changeKeyPattern = """<t:ItemId Id="[^"]+" ChangeKey="([^"]+)"""".toRegex()
            val changeKeyMatch = changeKeyPattern.find(getItemResponse)
            val changeKey = changeKeyMatch?.groupValues?.get(1) ?: ""
            
            val escapedItemId = deps.escapeXml(ewsItemId)
            val escapedChangeKey = deps.escapeXml(changeKey)
            val escapedSubject = deps.escapeXml(subject)
            val escapedBody = deps.escapeXml(body)
            
            val ewsImportance = when (importance) {
                0 -> "Low"
                2 -> "High"
                else -> "Normal"
            }
            
            val ewsStatus = if (complete) "Completed" else "NotStarted"
            
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            
            // Строим SetItemField для каждого поля
            val updates = buildString {
                // Subject
                append("""<t:SetItemField>""")
                append("""<t:FieldURI FieldURI="item:Subject"/>""")
                append("""<t:Task><t:Subject>$escapedSubject</t:Subject></t:Task>""")
                append("""</t:SetItemField>""")
                
                // Body
                if (escapedBody.isNotBlank()) {
                    append("""<t:SetItemField>""")
                    append("""<t:FieldURI FieldURI="item:Body"/>""")
                    append("""<t:Task><t:Body BodyType="Text">$escapedBody</t:Body></t:Task>""")
                    append("""</t:SetItemField>""")
                }
                
                // Importance
                append("""<t:SetItemField>""")
                append("""<t:FieldURI FieldURI="item:Importance"/>""")
                append("""<t:Task><t:Importance>$ewsImportance</t:Importance></t:Task>""")
                append("""</t:SetItemField>""")
                
                // Status (Complete)
                append("""<t:SetItemField>""")
                append("""<t:FieldURI FieldURI="task:Status"/>""")
                append("""<t:Task><t:Status>$ewsStatus</t:Status></t:Task>""")
                append("""</t:SetItemField>""")
                
                // StartDate
                if (startDate > 0) {
                    append("""<t:SetItemField>""")
                    append("""<t:FieldURI FieldURI="task:StartDate"/>""")
                    append("""<t:Task><t:StartDate>${dateFormat.format(java.util.Date(startDate))}</t:StartDate></t:Task>""")
                    append("""</t:SetItemField>""")
                }
                
                // DueDate
                if (dueDate > 0) {
                    append("""<t:SetItemField>""")
                    append("""<t:FieldURI FieldURI="task:DueDate"/>""")
                    append("""<t:Task><t:DueDate>${dateFormat.format(java.util.Date(dueDate))}</t:DueDate></t:Task>""")
                    append("""</t:SetItemField>""")
                }
            }
            
            val soapRequest = buildEwsUpdateTaskRequest(escapedItemId, escapedChangeKey, updates)
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, soapRequest, "UpdateItem")
            
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "UpdateItem")
                    ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
                
                responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "UpdateItem")
                    ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
            }
            
            // КРИТИЧНО: Проверяем ОБА условия
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val success = hasSuccess && hasNoError
            
            if (success) {
                EasResult.Success(true)
            } else {
                val errorMsg = Regex("MessageText>([^<]+)</").find(responseXml)?.groupValues?.get(1)
                    ?: "Не удалось обновить задачу"
                EasResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка EWS: ${e.message}")
        }
    }
    
    /**
     * Поиск ПОЛНОГО EWS ItemId задачи по Subject
     * Используется для получения актуального ItemId в формате Exchange2007_SP1
     * КРИТИЧНО: Возвращает ItemId в правильном формате, избегая ошибки "RTM format"
     */
    private suspend fun findTaskItemIdBySubject(subject: String): String? {
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
                <t:DistinguishedFolderId Id="tasks"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        // КРИТИЧНО: Basic Auth first, NTLM fallback
        var responseXml = deps.tryBasicAuthEws(ewsUrl, findRequest, "FindItem")
        if (responseXml == null) {
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
                ?: return null
            responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
                ?: return null
        }

        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
        return itemIdPattern.find(responseXml)?.groupValues?.get(1)
    }

    private suspend fun resolveEwsTaskItemId(ewsUrl: String, serverId: String, subject: String? = null): String? {
        // КРИТИЧНО: ВСЕГДА ищем актуальный ItemId через FindItem по subject
        // Exchange 2007 SP1 может вернуть ошибку "формат RTM" если использовать старый ItemId
        // который был получен при создании или предыдущей операции
        if (subject != null) {
            val foundId = findTaskItemIdBySubject(subject)
            if (foundId != null) {
                return foundId
            }
        }
        
        if (serverId.length >= 50 && !serverId.contains(":")) {
            return serverId
        }
        android.util.Log.w("EasTasksService", "resolveTaskEwsItemId: no subject available for serverId=$serverId")
        return null
    }

    private fun extractEwsBody(taskXml: String): String? {
        for (regex in Companion.REGEX_EWS_BODY_PATTERNS) {
            val match = regex.find(taskXml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return XmlUtils.unescape(match.groupValues[1].trim())
            }
        }
        return null
    }
    
    /**
     * Отправить email-уведомление о назначении задачи
     * Используется при создании и редактировании задачи с указанием assignTo
     */
    suspend fun sendTaskNotification(
        assignTo: String,
        subject: String,
        body: String,
        dueDate: Long,
        importance: Int
    ) {
        val taskSubject = "Задача: $subject"
        val taskBody = buildString {
            append("Вам назначена задача: $subject\r\n\r\n")
            if (body.isNotBlank()) {
                append("Описание: $body\r\n\r\n")
            }
            if (dueDate > 0) {
                val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                append("Срок выполнения: ${dateFormat.format(java.util.Date(dueDate))}\r\n")
            }
        }
        
        deps.sendMail(assignTo, taskSubject, taskBody, "", "", importance)
    }
    
    // ==================== EWS XML builders ====================
    
    /**
     * FindItem для Deleted Items: ищем ТОЛЬКО задачи (IPM.Task) через Restriction.
     * Без фильтра AllProperties на Deleted Items вернёт тысячи email/contacts → timeout.
     * КРИТИЧНО: Порядок элементов в FindItem по XSD: ItemShape → IndexedPageItemView → Restriction → ParentFolderIds
     */
    /**
     * Обнаруживает подпапки Tasks через EWS FindFolder(Traversal="Deep").
     * Exchange 2007 SP1: FindItem не поддерживает Traversal="Deep", но FindFolder — да.
     * Outlook может создавать подсписки задач (My Tasks, etc.) как подпапки.
     * Без этого шага FindItem с Traversal="Shallow" найдёт только задачи
     * в корне папки Tasks, пропустив задачи в подпапках.
     */
    /**
     * Ищет ВСЕ папки с FolderClass=IPF.Task* во всём почтовом ящике.
     * Поиск от msgfolderroot (а не от "tasks") гарантирует обнаружение
     * задач в нестандартных расположениях (Outlook "My Tasks", пользовательские списки).
     * Возвращает folderIds включая TotalCount каждой папки для диагностики.
     */
    private suspend fun discoverTaskSubfolderIds(ewsUrl: String): List<String> {
        try {
            val request = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:FindFolder Traversal="Deep">
            <m:FolderShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                    <t:FieldURI FieldURI="folder:FolderClass"/>
                    <t:FieldURI FieldURI="folder:DisplayName"/>
                    <t:FieldURI FieldURI="folder:TotalCount"/>
                </t:AdditionalProperties>
            </m:FolderShape>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="msgfolderroot"/>
            </m:ParentFolderIds>
        </m:FindFolder>
    </soap:Body>
</soap:Envelope>""".trimIndent()
            
            var responseXml = deps.tryBasicAuthEws(ewsUrl, request, "FindFolder")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, request, "FindFolder")
                    ?: return emptyList()
                responseXml = deps.executeNtlmRequest(ewsUrl, request, ntlmAuth, "FindFolder")
                    ?: return emptyList()
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "discoverTaskSubfolderIds: response len=${responseXml.length}")
            // Логируем больше ответа для диагностики: первые 2000 символов
            val logChunks = responseXml.take(2000).chunked(800)
            logChunks.forEachIndexed { i, chunk ->
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "discoverTaskSubfolderIds[chunk$i]: $chunk")
            }
            
            val folderIds = mutableListOf<String>()
            
            for (match in Companion.REGEX_FOLDER_BLOCK.findAll(responseXml)) {
                val folderXml = match.groupValues[1]
                val folderClass = Companion.REGEX_FOLDER_CLASS.find(folderXml)?.groupValues?.get(1) ?: ""
                if (!folderClass.startsWith("IPF.Task", ignoreCase = true)) continue
                
                val folderId = Companion.REGEX_FOLDER_ID.find(folderXml)?.groupValues?.get(1) ?: continue
                val displayName = Companion.REGEX_DISPLAY_NAME.find(folderXml)?.groupValues?.get(1) ?: ""
                val totalCount = Companion.REGEX_TOTAL_COUNT.find(folderXml)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                folderIds.add(folderId)
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", 
                    "discoverTaskSubfolderIds: found '$displayName' (class=$folderClass, items=$totalCount, id=${folderId.take(20)}...)")
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "discoverTaskSubfolderIds: total ${folderIds.size} task folders")
            return folderIds
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EasTasksService", "discoverTaskSubfolderIds: ${e.message}")
            return emptyList()
        }
    }
    
    /**
     * GetFolder на "tasks" → TotalCount + ChildFolderCount для диагностики.
     */
    private suspend fun getTasksFolderInfo(ewsUrl: String): Pair<Int, Int> {
        try {
            val request = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <GetFolder xmlns="http://schemas.microsoft.com/exchange/services/2006/messages">
            <FolderShape>
                <t:BaseShape>Default</t:BaseShape>
            </FolderShape>
            <FolderIds>
                <t:DistinguishedFolderId Id="tasks"/>
            </FolderIds>
        </GetFolder>
    </soap:Body>
</soap:Envelope>""".trimIndent()

            var responseXml = deps.tryBasicAuthEws(ewsUrl, request, "GetFolder")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, request, "GetFolder") ?: return Pair(-1, -1)
                responseXml = deps.executeNtlmRequest(ewsUrl, request, ntlmAuth, "GetFolder") ?: return Pair(-1, -1)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "getTasksFolderInfo: response: ${responseXml.take(800)}")

            val totalCount = "<(?:t:)?TotalCount>(\\d+)</(?:t:)?TotalCount>"
                .toRegex(RegexOption.DOT_MATCHES_ALL).find(responseXml)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val childFolderCount = "<(?:t:)?ChildFolderCount>(\\d+)</(?:t:)?ChildFolderCount>"
                .toRegex(RegexOption.DOT_MATCHES_ALL).find(responseXml)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
            return Pair(totalCount, childFolderCount)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EasTasksService", "getTasksFolderInfo: ${e.message}")
            return Pair(-1, -1)
        }
    }

    /**
     * FindFolder Shallow непосредственно под "tasks" — ищет дочерние папки,
     * которые Deep-поиск от msgfolderroot мог пропустить.
     */
    private suspend fun discoverDirectTaskSubfolders(ewsUrl: String): List<String> {
        try {
            val request = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:FindFolder Traversal="Shallow">
            <m:FolderShape>
                <t:BaseShape>IdOnly</t:BaseShape>
                <t:AdditionalProperties>
                    <t:FieldURI FieldURI="folder:FolderClass"/>
                    <t:FieldURI FieldURI="folder:DisplayName"/>
                    <t:FieldURI FieldURI="folder:TotalCount"/>
                </t:AdditionalProperties>
            </m:FolderShape>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="tasks"/>
            </m:ParentFolderIds>
        </m:FindFolder>
    </soap:Body>
</soap:Envelope>""".trimIndent()

            var responseXml = deps.tryBasicAuthEws(ewsUrl, request, "FindFolder")
            if (responseXml == null) {
                val ntlmAuth = deps.performNtlmHandshake(ewsUrl, request, "FindFolder") ?: return emptyList()
                responseXml = deps.executeNtlmRequest(ewsUrl, request, ntlmAuth, "FindFolder") ?: return emptyList()
            }
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "discoverDirectTaskSubfolders: response len=${responseXml.length}, first 600: ${responseXml.take(600)}")

            val folderIds = mutableListOf<String>()

            for (match in Companion.REGEX_FOLDER_BLOCK.findAll(responseXml)) {
                val folderXml = match.groupValues[1]
                val folderId = Companion.REGEX_FOLDER_ID.find(folderXml)?.groupValues?.get(1) ?: continue
                val displayName = Companion.REGEX_DISPLAY_NAME.find(folderXml)?.groupValues?.get(1) ?: ""
                val totalCount = Companion.REGEX_TOTAL_COUNT.find(folderXml)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                folderIds.add(folderId)
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                    "discoverDirectTaskSubfolders: found '$displayName' (items=$totalCount, id=${folderId.take(20)}...)")
            }
            return folderIds
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("EasTasksService", "discoverDirectTaskSubfolders: ${e.message}")
            return emptyList()
        }
    }

    private fun buildEwsFindTasksByFolderIdRequest(folderId: String, offset: Int = 0, pageSize: Int = 200): String {
        val escapedId = deps.escapeXml(folderId)
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:FindItem Traversal="Shallow">${taskItemShapeXml()}
            <m:IndexedPageItemView MaxEntriesReturned="$pageSize" Offset="$offset" BasePoint="Beginning"/>
            <m:ParentFolderIds>
                <t:FolderId Id="$escapedId"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }
    
    private fun buildEwsFindDeletedTasksRequest(offset: Int = 0, pageSize: Int = 200): String = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:FindItem Traversal="Shallow">${taskItemShapeXml()}
            <m:IndexedPageItemView MaxEntriesReturned="$pageSize" Offset="$offset" BasePoint="Beginning"/>
            <m:Restriction>
                <t:Contains ContainmentMode="Prefixed" ContainmentComparison="IgnoreCase">
                    <t:FieldURI FieldURI="item:ItemClass"/>
                    <t:Constant Value="IPM.Task"/>
                </t:Contains>
            </m:Restriction>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="deleteditems"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    
    /**
     * ItemShape для FindItem задач.
     * IdOnly без AdditionalProperties — единственный способ получить ВСЕ элементы
     * из папки Tasks на Exchange 2007 SP1. AllProperties и IdOnly+task:* вызывают
     * неявную серверную фильтрацию — возвращаются только "полные" задачи.
     * Свойства получаем отдельным GetItem (getTaskDetailsEws).
     */
    private fun taskItemShapeXml(): String = """
            <m:ItemShape>
                <t:BaseShape>IdOnly</t:BaseShape>
            </m:ItemShape>"""

    private fun buildEwsFindTasksRequest(offset: Int = 0, pageSize: Int = 200): String = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:FindItem Traversal="Shallow">${taskItemShapeXml()}
            <m:IndexedPageItemView MaxEntriesReturned="$pageSize" Offset="$offset" BasePoint="Beginning"/>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="tasks"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

    private fun buildEwsCreateTaskRequest(
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int
    ): String {
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val ewsImportance = when (importance) {
            0 -> "Low"
            2 -> "High"
            else -> "Normal"
        }
        
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """)
            append("""xmlns:xsd="http://www.w3.org/2001/XMLSchema" """)
            append("""xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">""")
            // КРИТИЧНО: Указываем версию Exchange2007_SP1 чтобы получить ItemId в правильном формате
            // Без этого Exchange возвращает ItemId в формате RTM, который несовместим с SP1 запросами
            append("<soap:Header>")
            append("""<t:RequestServerVersion Version="Exchange2007_SP1"/>""")
            append("</soap:Header>")
            append("<soap:Body>")
            // КРИТИЧНО: НЕ используем MessageDisposition для Task —
            // этот атрибут только для Message-элементов.
            // Exchange 2007 SP1 возвращает ErrorSchemaValidation если он указан для Task.
            append("""<CreateItem xmlns="http://schemas.microsoft.com/exchange/services/2006/messages" """)
            append("""xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">""")
            append("<Items>")
            append("<t:Task>")
            append("<t:Subject>$escapedSubject</t:Subject>")
            if (escapedBody.isNotBlank()) {
                append("""<t:Body BodyType="Text">$escapedBody</t:Body>""")
            }
            append("<t:Importance>$ewsImportance</t:Importance>")
            // КРИТИЧНО: Порядок элементов СТРОГО по EWS-схеме TaskType xs:sequence!
            // Exchange 2007 SP1 валидирует порядок и возвращает ErrorSchemaValidation
            // если элементы идут не по схеме. В TaskType: DueDate (pos 10) → StartDate (pos 19) → Status (pos 20)
            if (dueDate > 0) {
                append("<t:DueDate>${dateFormat.format(java.util.Date(dueDate))}</t:DueDate>")
            }
            if (startDate > 0) {
                append("<t:StartDate>${dateFormat.format(java.util.Date(startDate))}</t:StartDate>")
            }
            append("<t:Status>NotStarted</t:Status>")
            append("</t:Task>")
            append("</Items>")
            append("</CreateItem>")
            append("</soap:Body>")
            append("</soap:Envelope>")
        }
    }
    
    private fun buildEwsDeleteRequest(itemId: String, deleteType: String): String {
        require(deleteType in listOf("HardDelete", "SoftDelete", "MoveToDeletedItems"))
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:DeleteItem DeleteType="${deps.escapeXml(deleteType)}" AffectedTaskOccurrences="AllOccurrences">
            <m:ItemIds>
                <t:ItemId Id="${deps.escapeXml(itemId)}"/>
            </m:ItemIds>
        </m:DeleteItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }
    
    // КРИТИЧНО: НЕ используем MessageDisposition для Task — только для Message-элементов
    private fun buildEwsUpdateTaskRequest(itemId: String, changeKey: String, updates: String): String {
        val changeKeyAttr = if (changeKey.isNotEmpty()) """ ChangeKey="${deps.escapeXml(changeKey)}"""" else ""
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:UpdateItem ConflictResolution="AutoResolve">
            <m:ItemChanges>
                <t:ItemChange>
                    <t:ItemId Id="${deps.escapeXml(itemId)}"$changeKeyAttr/>
                    <t:Updates>
                        $updates
                    </t:Updates>
                </t:ItemChange>
            </m:ItemChanges>
        </m:UpdateItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }
    
    // ==================== Parsing ====================
    
    private fun parseTaskSyncResponse(xml: String): List<EasTask> {
        val tasks = mutableListOf<EasTask>()
        
        for (pattern in Companion.REGEX_TASK_SYNC_PATTERNS) {
            pattern.findAll(xml).forEach { match ->
                val itemXml = match.groupValues[1]
                val task = parseTaskFromXml(itemXml)
                if (task != null) tasks.add(task)
            }
        }
        
        return tasks
    }
    
    private fun parseTaskFromXml(itemXml: String): EasTask? {
        val serverId = deps.extractValue(itemXml, "ServerId") ?: return null
        
        val dataXml = Companion.REGEX_APPLICATION_DATA.find(itemXml)?.groupValues?.get(1) ?: return null
        
        val subject = XmlValueExtractor.extractTask(dataXml, "Subject") ?: ""
        val body = extractTaskBody(dataXml)
        val startDate = parseDate(XmlValueExtractor.extractTask(dataXml, "StartDate") 
            ?: XmlValueExtractor.extractTask(dataXml, "UtcStartDate"))
        val dueDate = parseDate(XmlValueExtractor.extractTask(dataXml, "DueDate")
            ?: XmlValueExtractor.extractTask(dataXml, "UtcDueDate"))
        val complete = XmlValueExtractor.extractTask(dataXml, "Complete") == "1"
        val dateCompleted = parseDate(XmlValueExtractor.extractTask(dataXml, "DateCompleted"))
        val importance = XmlValueExtractor.extractTask(dataXml, "Importance")?.toIntOrNull() ?: 1
        val sensitivity = XmlValueExtractor.extractTask(dataXml, "Sensitivity")?.toIntOrNull() ?: 0
        val reminderSet = XmlValueExtractor.extractTask(dataXml, "ReminderSet") == "1"
        val reminderTime = parseDate(XmlValueExtractor.extractTask(dataXml, "ReminderTime"))
        val categories = extractTaskCategories(dataXml)
        val owner = XmlValueExtractor.extractTask(dataXml, "OwnerId") ?: ""
        
        return EasTask(
            serverId = serverId,
            subject = subject,
            body = body,
            startDate = startDate,
            dueDate = dueDate,
            complete = complete,
            dateCompleted = dateCompleted,
            importance = importance,
            sensitivity = sensitivity,
            reminderSet = reminderSet,
            reminderTime = reminderTime,
            categories = categories,
            owner = owner
        )
    }
    
    private fun parseEwsTasksResponse(xml: String): List<EasTask> {
        val tasks = mutableListOf<EasTask>()
        val seenIds = mutableSetOf<String>()

        val itemsBlocks = Companion.REGEX_ITEMS_BLOCK.findAll(xml).map { it.groupValues[1] }.toList()
        val itemsXml = if (itemsBlocks.isNotEmpty()) itemsBlocks.joinToString("\n") else xml

        var totalRawMatches = 0

        for (regex in Companion.REGEX_ITEM_PATTERNS_EWS) {
            val matches = regex.findAll(itemsXml).toList()
            totalRawMatches += matches.size

            for (match in matches) {
                val itemXml = match.groupValues[1]
                parseTaskFromEwsItemXml(itemXml, seenIds)?.let { tasks.add(it) }
            }
        }

        // Fallback: Exchange 2007 SP1 может вернуть элементы в нестандартной обёртке
        // (не <Task>, <Item>, <Message>). Проверяем есть ли непропарсенные ItemId.
        val allIds = Companion.REGEX_ITEM_ID_EWS.findAll(itemsXml).toList()
        if (allIds.size > tasks.size) {
            android.util.Log.w("EasTasksService",
                "parseEwsTasksResponse: found ${allIds.size} ItemIds but only ${tasks.size} parsed → fallback for remaining")
            for (i in allIds.indices) {
                val id = allIds[i].groupValues[1]
                if (id in seenIds) continue
                val startIdx = maxOf(0, allIds[i].range.first - 200)
                val endIdx = if (i + 1 < allIds.size) allIds[i + 1].range.first else itemsXml.length
                val fragment = itemsXml.substring(startIdx, minOf(itemsXml.length, endIdx))
                parseTaskFromEwsItemXml(fragment, seenIds)?.let { tasks.add(it) }
            }
        }

        if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
            "parseEwsTasksResponse: itemsBlocks=${itemsBlocks.size}, rawMatches=$totalRawMatches, " +
            "totalItemIds=${allIds.size}, parsed=${tasks.size}")

        return tasks
    }

    private fun parseTaskFromEwsItemXml(itemXml: String, seenIds: MutableSet<String>): EasTask? {
        val serverId = XmlValueExtractor.extractAttribute(itemXml, "ItemId", "Id") ?: return null
        if (serverId in seenIds) return null
        seenIds.add(serverId)

        val subject = XmlValueExtractor.extractEws(itemXml, "Subject") ?: ""
        val rawBody = extractEwsBody(itemXml) ?: ""
        val body = removeDuplicateLines(rawBody)
        val startDate = parseDate(XmlValueExtractor.extractEws(itemXml, "StartDate"))
        val dueDate = parseDate(XmlValueExtractor.extractEws(itemXml, "DueDate"))
        val status = XmlValueExtractor.extractEws(itemXml, "Status") ?: ""
        val complete = status == "Completed"
        val dateCompleted = parseDate(XmlValueExtractor.extractEws(itemXml, "CompleteDate"))
        val importance = when (XmlValueExtractor.extractEws(itemXml, "Importance")) {
            "High" -> 2
            "Low" -> 0
            else -> 1
        }
        val sensitivity = when (XmlValueExtractor.extractEws(itemXml, "Sensitivity")) {
            "Confidential" -> 3
            "Private" -> 2
            "Personal" -> 1
            else -> 0
        }
        val categories = extractEwsCategories(itemXml)
        val owner = XmlValueExtractor.extractEws(itemXml, "Owner") ?: ""

        return EasTask(
            serverId = serverId,
            subject = subject,
            body = body,
            startDate = startDate,
            dueDate = dueDate,
            complete = complete,
            dateCompleted = dateCompleted,
            importance = importance,
            sensitivity = sensitivity,
            reminderSet = false,
            reminderTime = 0,
            categories = categories,
            owner = owner
        )
    }
    
    private fun extractTaskBody(xml: String): String {
        for (regex in Companion.REGEX_TASK_BODY_PATTERNS) {
            val match = regex.find(xml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return removeDuplicateLines(XmlUtils.unescape(match.groupValues[1].trim()))
            }
        }
        return ""
    }

    private fun extractTaskCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesMatch = Companion.REGEX_TASKS_CATEGORIES.find(xml)
        if (categoriesMatch != null) {
            val categoriesXml = categoriesMatch.groupValues[1]
            Companion.REGEX_TASKS_CATEGORY.findAll(categoriesXml).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
        }
        return categories
    }

    private fun extractEwsCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val outerMatch = Companion.REGEX_EWS_CATEGORIES_OUTER.find(xml) ?: return categories
        Companion.REGEX_EWS_CATEGORY_STRING.findAll(outerMatch.groupValues[1]).forEach { m ->
            val cat = m.groupValues[1].trim()
            if (cat.isNotEmpty()) categories.add(cat)
        }
        return categories
    }
    
    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return sdf.parse(dateStr)?.time ?: 0L
            } catch (_: Exception) {}
        }
        
        return 0L
    }
    
    private fun removeDuplicateLines(text: String): String {
        val normalized = text
            .replace(Companion.REGEX_BR, "\n")
            .replace(Companion.REGEX_P_TAGS, "\n")
            .replace(Companion.REGEX_P_OPEN_CLOSE, "\n")
            .replace(Companion.REGEX_DIV, "\n")
        
        val lines = normalized.lines()
        val seen = mutableSetOf<String>()
        return lines.filter { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) true else seen.add(trimmed)
        }.joinToString("\n")
    }

    private val syncKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {

        // syncTasksFromFolderEws (while hasMore)
        private val REGEX_TOTAL_ITEMS_IN_VIEW = "TotalItemsInView=\"(\\d+)\"".toRegex()
        private val REGEX_INDEXED_PAGING_OFFSET = "IndexedPagingOffset=\"(\\d+)\"".toRegex()
        private val REGEX_ITEM_ID_ATTR = """<(?:t:)?ItemId\s[^>]*Id="([^"]+)"[^>]*/?>""".toRegex()

        // getTaskDetailsEws, parseEwsTasksResponse
        private val REGEX_ITEMS_BLOCK = "<(?:t:|m:)?Items>(.*?)</(?:t:|m:)?Items>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val REGEX_ITEM_ID_EWS = """<(?:t:)?ItemId\s[^>]*Id="([^"]+)"[^>]*/?>""".toRegex()

        // parseEwsTasksResponse item patterns (precompiled list)
        private val REGEX_ITEM_PATTERNS_EWS = listOf(
            "<(?:t:)?Task\\b[^>]*>(.*?)</(?:t:)?Task>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<(?:t:)?Item\\b[^>]*>(.*?)</(?:t:)?Item>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<(?:t:)?Message\\b[^>]*>(.*?)</(?:t:)?Message>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )

        // parseTaskSyncResponse (precompiled list)
        private val REGEX_TASK_SYNC_PATTERNS = listOf(
            "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )

        // parseTaskFromXml
        private val REGEX_APPLICATION_DATA = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)

        // extractTaskBody (precompiled list)
        private val REGEX_TASK_BODY_PATTERNS = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<tasks:Body>.*?<Data>(.*?)</Data>.*?</tasks:Body>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<tasks:Body>(.*?)</tasks:Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )

        // extractTaskCategories
        private val REGEX_TASKS_CATEGORIES = "<tasks:Categories>(.*?)</tasks:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val REGEX_TASKS_CATEGORY = "<tasks:Category>(.*?)</tasks:Category>".toRegex()

        // extractEwsCategories
        private val REGEX_EWS_CATEGORIES_OUTER = "<(?:t:)?Categories>(.*?)</(?:t:)?Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val REGEX_EWS_CATEGORY_STRING = "<(?:t:)?String>(.*?)</(?:t:)?String>".toRegex()

        // extractEwsBody (precompiled list)
        private val REGEX_EWS_BODY_PATTERNS = listOf(
            "<t:Body[^>]*>(.*?)</t:Body>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<Body[^>]*>(.*?)</Body>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<m:Body[^>]*>(.*?)</m:Body>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )

        // discoverTaskSubfolderIds, discoverDirectTaskSubfolders
        private val REGEX_FOLDER_BLOCK = "<(?:t:)?(?:TasksFolder|Folder|SearchFolder)\\b[^>]*>(.*?)</(?:t:)?(?:TasksFolder|Folder|SearchFolder)>"
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL))
        private val REGEX_FOLDER_ID = "<(?:t:)?FolderId[^>]*\\bId=\"([^\"]+)\"".toRegex()
        private val REGEX_FOLDER_CLASS = "<(?:t:)?FolderClass>(.*?)</(?:t:)?FolderClass>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val REGEX_DISPLAY_NAME = "<(?:t:)?DisplayName>(.*?)</(?:t:)?DisplayName>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val REGEX_TOTAL_COUNT = "<(?:t:)?TotalCount>(\\d+)</(?:t:)?TotalCount>".toRegex(RegexOption.DOT_MATCHES_ALL)

        // removeDuplicateLines
        private val REGEX_BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val REGEX_P_TAGS = Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE)
        private val REGEX_P_OPEN_CLOSE = Regex("</?p[^>]*>", RegexOption.IGNORE_CASE)
        private val REGEX_DIV = Regex("</?div[^>]*>", RegexOption.IGNORE_CASE)
    }
}

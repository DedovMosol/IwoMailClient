package com.dedovmosol.iwomail.eas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private var cachedTasksFolderId: String? = null
    
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
        
        // Получаем SyncKey
        var syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        
        val updateXml = buildUpdateXml(
            syncKey, tasksFolderId, serverId, escapedSubject, escapedBody,
            startDate, dueDate, complete, importance, reminderSet, reminderTime,
            majorVersion, dateFormat
        )
        
        return deps.executeEasCommand("Sync", updateXml) { responseXml ->
            // Проверяем статус коллекции
            val collectionStatus = deps.extractValue(responseXml, "Status")
            if (collectionStatus != "1") {
                throw Exception("Collection Status=$collectionStatus")
            }
            
            // Проверяем статус конкретной операции Change
            if (responseXml.contains("<Responses>") && responseXml.contains("<Change>")) {
                val changeStatusMatch = Regex("<Change>.*?<Status>(\\d+)</Status>", RegexOption.DOT_MATCHES_ALL)
                    .find(responseXml)
                
                if (changeStatusMatch != null) {
                    val changeStatus = changeStatusMatch.groupValues[1]
                    when (changeStatus) {
                        "1" -> true // Success
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
        
        val syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
        
        val deleteXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$tasksFolderId</CollectionId>
            <DeletesAsMoves>1</DeletesAsMoves>
            <Commands>
                <Delete>
                    <ServerId>$serverId</ServerId>
                </Delete>
            </Commands>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        return deps.executeEasCommand("Sync", deleteXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            status == "1"
        }
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
        
        val syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
        
        val deleteXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$tasksFolderId</CollectionId>
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
            val status = deps.extractValue(responseXml, "Status")
            status == "1"
        }
    }
    
    /**
     * Восстановление задачи из корзины
     * Перемещает задачу из Deleted Items обратно в Tasks
     */
    suspend fun restoreTask(serverId: String): EasResult<String> {
        if (!deps.isVersionDetected()) {
            deps.detectEasVersion()
        }
        
        val majorVersion = deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12
        
        return if (majorVersion >= 14) {
            restoreTaskEas(serverId)
        } else {
            restoreTaskEws(serverId)
        }
    }
    
    // ==================== Private EAS methods ====================
    
    private suspend fun getSyncKey(tasksFolderId: String): String? {
        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>$tasksFolderId</CollectionId>
        </Collection>
    </Collections>
</Sync>""".trimIndent()
        
        val result = deps.executeEasCommand("Sync", initialXml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: "0"
        }
        
        return when (result) {
            is EasResult.Success -> if (result.data != "0") result.data else null
            is EasResult.Error -> null
        }
    }
    
    private suspend fun syncTasksEas(tasksFolderId: String): EasResult<List<EasTask>> {
        val initialXml = """<?xml version="1.0" encoding="UTF-8"?>
<Sync xmlns="AirSync">
    <Collections>
        <Collection>
            <SyncKey>0</SyncKey>
            <CollectionId>$tasksFolderId</CollectionId>
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
            <SyncKey>$syncKey</SyncKey>
            <CollectionId>$tasksFolderId</CollectionId>
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
        
        val syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
        
        val clientId = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.000'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        
        val escapedSubject = deps.escapeXml(subject)
        val escapedBody = deps.escapeXml(body)
        
        val createXml = buildCreateXml(
            syncKey, tasksFolderId, clientId, escapedSubject, escapedBody,
            startDate, dueDate, importance, reminderSet, reminderTime, dateFormat
        )
        
        return deps.executeEasCommand("Sync", createXml) { responseXml ->
            val status = deps.extractValue(responseXml, "Status")
            if (status == "1") {
                deps.extractValue(responseXml, "ServerId") ?: clientId
            } else {
                throw Exception("Ошибка создания задачи: Status=$status")
            }
        }
    }
    
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
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks">""")
        append("<Collections><Collection>")
        append("<SyncKey>$syncKey</SyncKey>")
        append("<CollectionId>$tasksFolderId</CollectionId>")
        append("<Commands><Add>")
        append("<ClientId>$clientId</ClientId>")
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
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        if (majorVersion >= 14) {
            append("""<Sync xmlns="AirSync" xmlns:airsyncbase="AirSyncBase" xmlns:tasks="Tasks">""")
        } else {
            append("""<Sync xmlns="AirSync" xmlns:tasks="Tasks">""")
        }
        append("<Collections><Collection>")
        append("<SyncKey>$syncKey</SyncKey>")
        append("<CollectionId>$tasksFolderId</CollectionId>")
        append("<Commands><Change>")
        append("<ServerId>$serverId</ServerId>")
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
            val allTasks = mutableListOf<EasTask>()
            
            // Синхронизация активных задач
            val activeTasks = syncTasksFromFolderEws(ewsUrl, "tasks", isDeleted = false)
            allTasks.addAll(activeTasks)
            
            // Синхронизация удалённых задач
            val deletedTasks = syncTasksFromFolderEws(ewsUrl, "deleteditems", isDeleted = true)
            allTasks.addAll(deletedTasks)
            
            EasResult.Success(allTasks)
        } catch (e: Exception) {
            EasResult.Success(emptyList())
        }
    }
    
    private suspend fun syncTasksFromFolderEws(ewsUrl: String, folderId: String, isDeleted: Boolean): List<EasTask> {
        try {
            val findRequest = if (folderId == "tasks") {
                buildEwsFindTasksRequest()
            } else {
                EasXmlTemplates.ewsSoapRequest(EasXmlTemplates.ewsFindItem(folderId))
            }
            
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
                ?: return emptyList()
            
            val responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
                ?: return emptyList()
            
            val tasks = parseEwsTasksResponse(responseXml).toMutableList()
            
            if (tasks.isNotEmpty()) {
                val bodies = getTaskBodiesEws(ewsUrl, tasks.map { it.serverId })
                return tasks.map { task ->
                    val body = bodies[task.serverId]
                    task.copy(
                        body = if (!body.isNullOrBlank()) body else task.body,
                        isDeleted = isDeleted
                    )
                }
            }
            
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
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
            
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
            
            val responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
            
            // КРИТИЧНО: Для delete проверяем Success+NoError ИЛИ ErrorItemNotFound (уже удалено)
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")
            val success = (hasSuccess && hasNoError) || hasNotFound
            
            EasResult.Success(success)
        } catch (e: Exception) {
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
            
            val ntlmAuth = deps.performNtlmHandshake(ewsUrl, soapRequest, "DeleteItem")
                ?: return@withContext EasResult.Error("NTLM аутентификация не удалась")
            
            val responseXml = deps.executeNtlmRequest(ewsUrl, soapRequest, ntlmAuth, "DeleteItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить запрос")
            
            // КРИТИЧНО: Для delete проверяем Success+NoError ИЛИ ErrorItemNotFound (уже удалено)
            val hasSuccess = responseXml.contains("ResponseClass=\"Success\"")
            val hasNoError = responseXml.contains("<ResponseCode>NoError</ResponseCode>") ||
                            responseXml.contains("<m:ResponseCode>NoError</m:ResponseCode>")
            val hasNotFound = responseXml.contains("ErrorItemNotFound")
            val success = (hasSuccess && hasNoError) || hasNotFound
            
            EasResult.Success(success)
        } catch (e: Exception) {
            EasResult.Error("Ошибка EWS: ${e.message}")
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
    private suspend fun restoreTaskEws(serverId: String): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val ewsUrl = deps.getEwsUrl()
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                findEwsTaskItemId(ewsUrl, serverId) ?: run {
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
            EasResult.Error("Ошибка EWS: ${e.message}")
        }
    }
    
    private suspend fun getTaskBodiesEws(ewsUrl: String, itemIds: List<String>): Map<String, String> {
        if (itemIds.isEmpty()) return emptyMap()
        
        val itemIdsXml = itemIds.joinToString("") { """<t:ItemId Id="${deps.escapeXml(it)}"/>""" }
        
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
        
        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, getItemRequest, "GetItem") ?: return emptyMap()
        val responseXml = deps.executeNtlmRequest(ewsUrl, getItemRequest, ntlmAuth, "GetItem") ?: return emptyMap()
        
        val result = mutableMapOf<String, String>()
        val taskPattern = "<t:Task>(.*?)</t:Task>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val taskMatches = taskPattern.findAll(responseXml).toList()
        
        taskMatches.forEach { match ->
            val taskXml = match.groupValues[1]
            val itemId = XmlValueExtractor.extractAttribute(taskXml, "ItemId", "Id")
            
            val body = extractEwsBody(taskXml)
                ?: deps.extractValue(taskXml, "Body")
                ?: XmlValueExtractor.extractEws(taskXml, "Body")
                ?: ""
            
            if (itemId != null) {
                result[itemId] = removeDuplicateLines(body)
            }
        }
        
        return result
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

        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem")
        if (ntlmAuth == null) {
            return null
        }
        
        val responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem")
        if (responseXml == null) {
            return null
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
        
        // Fallback: если subject не помог, пробуем serverId
        return if (serverId.length < 50 || serverId.contains(":")) {
            // SHORT ID - ищем через FindItem все задачи
            findEwsTaskItemId(ewsUrl, serverId)
        } else {
            // Длинный ID - но он может быть устаревшим, пробуем использовать
            serverId
        }
    }

    private suspend fun findEwsTaskItemId(ewsUrl: String, easServerId: String): String? {
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
            <m:IndexedPageItemView MaxEntriesReturned="500" Offset="0" BasePoint="Beginning"/>
            <m:ParentFolderIds>
                <t:DistinguishedFolderId Id="tasks"/>
            </m:ParentFolderIds>
        </m:FindItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()

        val ntlmAuth = deps.performNtlmHandshake(ewsUrl, findRequest, "FindItem") ?: return null
        val responseXml = deps.executeNtlmRequest(ewsUrl, findRequest, ntlmAuth, "FindItem") ?: return null

        val itemIdPattern = "<t:ItemId Id=\"([^\"]+)\"".toRegex()
        val matches = itemIdPattern.findAll(responseXml).toList()
        if (matches.isEmpty()) return null

        val index = easServerId.substringAfter(":").toIntOrNull()?.minus(1) ?: 0
        return matches.getOrNull(index)?.groupValues?.get(1) ?: matches.first().groupValues[1]
    }

    private fun extractEwsBody(taskXml: String): String? {
        val bodyPatterns = listOf(
            "<t:Body[^>]*>(.*?)</t:Body>",
            "<Body[^>]*>(.*?)</Body>",
            "<m:Body[^>]*>(.*?)</m:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(taskXml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return unescapeXml(match.groupValues[1].trim())
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
    
    private fun buildEwsFindTasksRequest(): String = """<?xml version="1.0" encoding="utf-8"?>
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
                    <t:FieldURI FieldURI="task:StartDate"/>
                    <t:FieldURI FieldURI="task:DueDate"/>
                    <t:FieldURI FieldURI="task:Status"/>
                    <t:FieldURI FieldURI="task:CompleteDate"/>
                    <t:FieldURI FieldURI="item:Importance"/>
                </t:AdditionalProperties>
            </m:ItemShape>
            <m:IndexedPageItemView MaxEntriesReturned="200" Offset="0" BasePoint="Beginning"/>
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
    
    private fun buildEwsDeleteRequest(itemId: String, deleteType: String): String = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types"
               xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">
    <soap:Header>
        <t:RequestServerVersion Version="Exchange2007_SP1"/>
    </soap:Header>
    <soap:Body>
        <m:DeleteItem DeleteType="$deleteType" AffectedTaskOccurrences="AllOccurrences">
            <m:ItemIds>
                <t:ItemId Id="$itemId"/>
            </m:ItemIds>
        </m:DeleteItem>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    
    // КРИТИЧНО: НЕ используем MessageDisposition для Task — только для Message-элементов
    private fun buildEwsUpdateTaskRequest(itemId: String, changeKey: String, updates: String): String {
        val changeKeyAttr = if (changeKey.isNotEmpty()) """ ChangeKey="$changeKey"""" else ""
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
                    <t:ItemId Id="$itemId"$changeKeyAttr/>
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
        
        val patterns = listOf(
            "<Add>(.*?)</Add>".toRegex(RegexOption.DOT_MATCHES_ALL),
            "<Change>(.*?)</Change>".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        for (pattern in patterns) {
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
        
        val dataPattern = "<ApplicationData>(.*?)</ApplicationData>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val dataXml = dataPattern.find(itemXml)?.groupValues?.get(1) ?: return null
        
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
            categories = categories
        )
    }
    
    private fun parseEwsTasksResponse(xml: String): List<EasTask> {
        val tasks = mutableListOf<EasTask>()

        val itemsPattern = "<(?:t:|m:)?Items>(.*?)</(?:t:|m:)?Items>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val itemsXml = itemsPattern.find(xml)?.groupValues?.get(1) ?: xml

        val itemPatterns = listOf(
            "<t:Task[^>]*>(.*?)</t:Task>",
            "<Task[^>]*>(.*?)</Task>",
            "<t:Item[^>]*>(.*?)</t:Item>",
            "<Item[^>]*>(.*?)</Item>",
            "<t:Message[^>]*>(.*?)</t:Message>",
            "<Message[^>]*>(.*?)</Message>"
        )

        for (pattern in itemPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(itemsXml).toList()

            matches.forEach { match ->
                val itemXml = match.groupValues[1]

                val itemClass = XmlValueExtractor.extractEws(itemXml, "ItemClass") ?: ""
                if (itemClass.isNotEmpty() && !itemClass.contains("Task", ignoreCase = true)) {
                    return@forEach
                }

                val serverId = XmlValueExtractor.extractAttribute(itemXml, "ItemId", "Id") ?: return@forEach
                val subject = XmlValueExtractor.extractEws(itemXml, "Subject") ?: ""
                val rawBody = extractEwsBody(itemXml) ?: ""
                val body = removeDuplicateLines(rawBody)
                val startDate = parseDate(XmlValueExtractor.extractEws(itemXml, "StartDate"))
                val dueDate = parseDate(XmlValueExtractor.extractEws(itemXml, "DueDate"))
                val complete = XmlValueExtractor.extractEws(itemXml, "Status") == "Completed"
                val dateCompleted = parseDate(XmlValueExtractor.extractEws(itemXml, "CompleteDate"))
                val importance = when (XmlValueExtractor.extractEws(itemXml, "Importance")) {
                    "High" -> 2
                    "Low" -> 0
                    else -> 1
                }

                tasks.add(
                    EasTask(
                        serverId = serverId,
                        subject = subject,
                        body = body,
                        startDate = startDate,
                        dueDate = dueDate,
                        complete = complete,
                        dateCompleted = dateCompleted,
                        importance = importance,
                        sensitivity = 0,
                        reminderSet = false,
                        reminderTime = 0,
                        categories = emptyList()
                    )
                )
            }

            if (tasks.isNotEmpty()) break
        }

        return tasks
    }
    
    private fun extractTaskBody(xml: String): String {
        val bodyPatterns = listOf(
            "<airsyncbase:Body>.*?<airsyncbase:Data>(.*?)</airsyncbase:Data>.*?</airsyncbase:Body>",
            "<Body>.*?<Data>(.*?)</Data>.*?</Body>",
            "<tasks:Body>(.*?)</tasks:Body>"
        )
        for (pattern in bodyPatterns) {
            val regex = pattern.toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null && match.groupValues[1].isNotBlank()) {
                return removeDuplicateLines(unescapeXml(match.groupValues[1].trim()))
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
    
    private fun extractTaskCategories(xml: String): List<String> {
        val categories = mutableListOf<String>()
        val categoriesPattern = "<tasks:Categories>(.*?)</tasks:Categories>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val categoriesMatch = categoriesPattern.find(xml)
        if (categoriesMatch != null) {
            val categoriesXml = categoriesMatch.groupValues[1]
            val categoryPattern = "<tasks:Category>(.*?)</tasks:Category>".toRegex()
            categoryPattern.findAll(categoriesXml).forEach { match ->
                categories.add(match.groupValues[1].trim())
            }
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
}

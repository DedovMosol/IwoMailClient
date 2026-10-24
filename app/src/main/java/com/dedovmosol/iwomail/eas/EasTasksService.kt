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
        val performNtlmHandshake: suspend () -> String?,
        val executeNtlmRequest: suspend (String, String) -> String?,
        val tryBasicAuthEws: suspend (String, String) -> String?,
        val sendMail: suspend (String, String, String, String, String, Int) -> EasResult<Boolean>
    )
    
    // Кэш ID папки задач
    @Volatile private var cachedTasksFolderId: String? = null
    
    /**
     * Синхронизация задач из папки Tasks на сервере Exchange.
     * Полнота снимка — в [TasksSyncResult.isAuthoritative]: только полный снимок даёт право
     * удалять локальные задачи, которых в нём нет.
     */
    suspend fun syncTasks(): EasResult<TasksSyncResult> {
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
            createTaskEws(subject, body, startDate, dueDate, importance, assignTo)
        } else if (majorVersion < 14) {
            createTaskEws(subject, body, startDate, dueDate, importance, null)
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
    
    /** Перемещение задачи в корзину (Deleted Items). ServerId — из синка, поэтому протокол — его же. */
    suspend fun deleteTask(serverId: String): EasResult<Boolean> {
        if (!deps.isVersionDetected()) deps.detectEasVersion()

        if (tasksSyncedViaEws()) {
            return deleteTaskEwsImpl(serverId, "MoveToDeletedItems", notFoundIsSuccess = false)
        }

        val tasksFolderId = cachedTasksFolderId ?: deps.getTasksFolderId()
            ?: return EasResult.Error("Папка задач не найдена")

        for (attempt in 0..1) {
            val syncKey = getSyncKey(tasksFolderId) ?: return EasResult.Error("Не удалось получить SyncKey")
            val deleteXml = EasXmlTemplates.syncDelete(syncKey, tasksFolderId, serverId, deletesAsMoves = true)

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
     * Batch-удаление задач (в корзину). Один EWS DeleteItem на весь пакет.
     */
    suspend fun deleteTasksBatch(serverIds: List<String>): EasResult<Int> {
        if (serverIds.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        return if (tasksSyncedViaEws()) {
            deleteTasksBatchEws(serverIds, "MoveToDeletedItems")
        } else {
            var deleted = 0
            for (sid in serverIds) {
                if (deleteTask(sid) is EasResult.Success) deleted++
            }
            EasResult.Success(deleted)
        }
    }

    /*
     * Корзина задач. Откуда взялся ServerId корзинной задачи, зависит от протокола синка:
     *  - Exchange 2007 (EWS-синк): синк читает deleteditems, и ServerId — EWS ItemId копии в корзине.
     *  - Exchange 2010/2013 (EAS-синк): корзину EAS-синк не читает, а Sync Delete с DeletesAsMoves
     *    не сообщает новый ServerId в Deleted Items. Локальная запись хранит прежний ServerId из
     *    папки Tasks: по нему копию в корзине не адресовать, а Sync Delete по нему ударил бы по папке
     *    Tasks — после полного пересинка (SyncKey=0) ServerId может достаться другой задаче.
     *    Поэтому копию в корзине ищем через EWS: единственная IPM.Task с той же темой в deleteditems.
     */

    /** Синк задач идёт по EWS (Exchange 2007): ServerId корзины — EWS ItemId копии в deleteditems. */
    private fun tasksSyncedViaEws(): Boolean =
        (deps.getEasVersion().substringBefore(".").toIntOrNull() ?: 12) < 14

    /**
     * Окончательное удаление задачи из корзины.
     * EAS-синк ([tasksSyncedViaEws] = false): копия ищется в deleteditems по [subject]; не нашли
     * однозначно или EWS недоступен — удалять на сервере нечего адресно, копия остаётся в Deleted
     * Items (как до появления локальной корзины), запись убирается из приложения.
     */
    suspend fun deleteTaskPermanently(serverId: String, subject: String? = null): EasResult<Boolean> {
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        if (tasksSyncedViaEws()) return deleteTaskEwsImpl(serverId, "HardDelete", notFoundIsSuccess = true)

        val itemId = findDeletedTaskItemIdEws(subject)
        if (itemId == null) {
            android.util.Log.w("EasTasksService",
                "deleteTaskPermanently: trashed copy not addressable, it stays in Deleted Items on the server")
            return EasResult.Success(true)
        }
        return deleteEwsTaskItem(itemId, "HardDelete")
    }

    /**
     * Окончательное удаление задач корзины: [tasks] — пары (ServerId, тема).
     * Exchange 2007 — один EWS DeleteItem на пакет, EAS-синк — по задаче (см. [deleteTaskPermanently]).
     */
    suspend fun deleteTasksPermanentlyBatch(tasks: List<Pair<String, String>>): EasResult<Int> {
        if (tasks.isEmpty()) return EasResult.Success(0)
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        return if (tasksSyncedViaEws()) {
            deleteTasksBatchEws(tasks.map { it.first }, "HardDelete")
        } else {
            // Частичный успех — ошибка, а не «удалено N»: на успехе пакета репозиторий удаляет
            // локально ВСЮ корзину. На ошибке он проходит по задачам и убирает только удалённые
            // (уже удалённая копия не находится, и повтор для неё — только локальное удаление).
            val deleted = tasks.count { (serverId, subject) -> deleteTaskPermanently(serverId, subject) is EasResult.Success }
            if (deleted == tasks.size) EasResult.Success(deleted)
            else EasResult.Error("Окончательно удалено $deleted из ${tasks.size}")
        }
    }

    /**
     * Восстановление задачи из корзины: EWS MoveItem из deleteditems в tasks. Копию адресует EWS
     * ItemId из синка корзины (Exchange 2007) или поиск по [subject] в deleteditems (EAS-синк).
     * Возвращает ItemId восстановленной задачи; следующий синк заменит его своим ServerId.
     */
    suspend fun restoreTask(serverId: String, subject: String? = null): EasResult<String> {
        if (!deps.isVersionDetected()) deps.detectEasVersion()
        return restoreTaskEws(serverId, subject)
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
    
    /** Страница полного EAS-пересинка задач. */
    private class TaskSyncPage(
        val status: String,
        val syncKey: String?,
        val tasks: List<EasTask>,
        val moreAvailable: Boolean
    )

    private fun parseTaskSyncPage(responseXml: String): TaskSyncPage {
        // MS-ASCMD: Status коллекции обязателен; пустое тело ответа транспорт подменяет на Status=1.
        val status = deps.extractValue(responseXml, "Status") ?: "1"
        if (status != "1") return TaskSyncPage(status, syncKey = null, tasks = emptyList(), moreAvailable = false)
        return TaskSyncPage(
            status = status,
            syncKey = deps.extractValue(responseXml, "SyncKey"),
            tasks = parseTaskSyncResponse(responseXml),
            moreAvailable = responseXml.contains("<MoreAvailable/>") || responseXml.contains("<MoreAvailable>")
        )
    }

    private suspend fun syncTasksEas(tasksFolderId: String): EasResult<TasksSyncResult> {
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
        
        val initialResult = deps.executeEasCommand("Sync", initialXml) { responseXml ->
            deps.extractValue(responseXml, "SyncKey") ?: "0"
        }
        // Сбой первичного Sync раньше выдавался за пустую папку (Success(emptyList())),
        // и репозиторий удалял по нему локальные задачи.
        var syncKey = when (initialResult) {
            is EasResult.Success -> initialResult.data
            is EasResult.Error -> return initialResult
        }
        if (syncKey == "0") {
            return EasResult.Error("Sync задач: сервер не выдал SyncKey")
        }
        
        val allTasks = mutableListOf<EasTask>()
        // Снимок полон, только если сервер сам сообщил конец выдачи: Status=1 на каждой странице
        // и MoreAvailable исчерпан. Любой досрочный выход записывает причину сюда.
        var incompleteReason: String? = null
        var keyInvalidated = false
        var hasMore = true
        var iteration = 0
        val maxIterations = 50 // До 5000 задач (windowSize=100)
        val syncStartTime = System.currentTimeMillis()
        val maxSyncDurationMs = 120_000L // 2 минуты — достаточно для 500+ задач
        var previousSyncKey = syncKey
        var sameKeyCount = 0
        
        while (hasMore) {
            if (iteration >= maxIterations) {
                incompleteReason = "лимит $maxIterations страниц"
                break
            }
            iteration++
            
            if (System.currentTimeMillis() - syncStartTime > maxSyncDurationMs) {
                incompleteReason = "таймаут после ${iteration - 1} страниц"
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
            
            val page = when (val result = deps.executeEasCommand("Sync", syncXml) { parseTaskSyncPage(it) }) {
                is EasResult.Success -> result.data
                is EasResult.Error -> {
                    incompleteReason = result.message
                    break
                }
            }
            if (page.status != "1") {
                // 3 — Invalid SyncKey, 12 — иерархия папок изменилась: ключ недействителен.
                // Следующий синк всё равно начнётся с SyncKey=0; кэш для create/update сбрасываем.
                if (page.status == "3" || page.status == "12") {
                    syncKeyCache.remove(tasksFolderId)
                    keyInvalidated = true
                }
                incompleteReason = "Status=${page.status}"
                break
            }

            syncKey = page.syncKey ?: syncKey
            allTasks.addAll(page.tasks)
            hasMore = page.moreAvailable

            // Защита от зацикливания: сервер 3 раза подряд отдаёт тот же SyncKey и обещает ещё.
            if (syncKey == previousSyncKey) {
                sameKeyCount++
                if (sameKeyCount >= 3 && hasMore) {
                    incompleteReason = "SyncKey не меняется"
                    break
                }
            } else {
                sameKeyCount = 0
                previousSyncKey = syncKey
            }

            // MoreAvailable без данных: сервер обещает продолжение, но не отдаёт его.
            if (hasMore && page.tasks.isEmpty()) {
                incompleteReason = "MoreAvailable без данных"
                break
            }
        }
        
        if (!keyInvalidated && syncKey != "0") {
            syncKeyCache[tasksFolderId] = syncKey
        }

        if (incompleteReason != null) {
            android.util.Log.w("EasTasksService",
                "Tasks sync incomplete ($incompleteReason): ${allTasks.size} tasks, deletions disabled")
            // Сбой до первой задачи — ошибка синка, а не «пустая папка».
            if (allTasks.isEmpty()) return EasResult.Error("Синхронизация задач прервана: $incompleteReason")
        }
        // includesDeletedItems=false: EAS Sync читает только активную папку Tasks, корзину — нет.
        return EasResult.Success(TasksSyncResult(allTasks, isAuthoritative = incompleteReason == null, includesDeletedItems = false))
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
                    throw InvalidSyncKeyException(status)
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

    private suspend fun executeEwsWithAuth(soapRequest: String, operation: String): String? {
        var responseXml = deps.tryBasicAuthEws(soapRequest, operation)
        if (responseXml == null) {
            deps.performNtlmHandshake() ?: return null
            responseXml = deps.executeNtlmRequest(soapRequest, operation)
        }
        return responseXml
    }
    
    /** Итог EWS-выборки одной папки задач. */
    private class EwsTaskFolder(
        val tasks: List<EasTask>,
        /** FindItem дошёл до последней страницы (IncludesLastItemInRange) без ошибок. */
        val isComplete: Boolean,
        /** ItemId, которые подтвердил FindItem, но не вернул GetItem. */
        val unresolvedIds: Set<String> = emptySet()
    )

    private suspend fun syncTasksEws(): EasResult<TasksSyncResult> = withContext(Dispatchers.IO) {
        try {
            // 0. Диагностика: GetFolder на tasks → TotalCount + ChildFolderCount
            val folderInfo = getTasksFolderInfo()
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "syncTasksEws: GetFolder tasks → totalCount=${folderInfo.first}, childFolders=${folderInfo.second}")

            // 1. Синхронизация из корневой папки Tasks (DistinguishedFolderId)
            val root = syncTasksFromFolderEws("tasks", isDeleted = false) { offset, pageSize ->
                buildEwsFindTasksRequest(offset, pageSize)
            }
            val activeFolders = mutableListOf(root)
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: root tasks folder: ${root.tasks.size}")
            
            // 2. Обнаруживаем подпапки Tasks и ищем в каждой.
            // null — обнаружение не удалось: задачи подпапок неизвестны, снимок неполный.
            val subfolderIds = discoverTaskSubfolderIds()
            if (!subfolderIds.isNullOrEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: discovered ${subfolderIds.size} task subfolders")
                for (subfolderId in subfolderIds) {
                    activeFolders += syncTasksFromFolderEws("tasks-sub-${subfolderId.take(8)}", isDeleted = false) { offset, pageSize ->
                        buildEwsFindTasksByFolderIdRequest(subfolderId, offset, pageSize)
                    }
                }
            }

            // 2b. Подпапки непосредственно внутри "tasks" (Deep от msgfolderroot может пропустить)
            val directSubfolders = discoverDirectTaskSubfolders()
            if (!directSubfolders.isNullOrEmpty()) {
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                    "syncTasksEws: ${directSubfolders.size} direct subfolders under tasks")
                for (subfolderId in directSubfolders) {
                    if (subfolderIds?.contains(subfolderId) == true) continue
                    activeFolders += syncTasksFromFolderEws("tasks-direct-${subfolderId.take(8)}", isDeleted = false) { offset, pageSize ->
                        buildEwsFindTasksByFolderIdRequest(subfolderId, offset, pageSize)
                    }
                }
            }

            // todosearch (DistinguishedFolderId "todosearch") введён в Exchange 2010 (v14).
            // EWS-путь используется только для Exchange 2007 SP1 (majorVersion < 14),
            // поэтому todosearch гарантированно вернёт ErrorFolderNotFound — пропускаем.
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "syncTasksEws: skipping todosearch (not supported on Exchange 2007 SP1)")

            // 3. Дедупликация по serverId
            val allActiveTasks = activeFolders.flatMap { it.tasks }
            val uniqueActive = allActiveTasks.distinctBy { it.serverId }
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: active tasks total: ${uniqueActive.size} (before dedup: ${allActiveTasks.size})")
            
            // 4. Синхронизация удалённых задач
            val deleted = syncTasksFromFolderEws("deleteditems", isDeleted = true) { offset, pageSize ->
                buildEwsFindDeletedTasksRequest(offset, pageSize)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", "syncTasksEws: deleted tasks: ${deleted.tasks.size}")
            
            val allTasks = uniqueActive + deleted.tasks
            val isAuthoritative = subfolderIds != null && directSubfolders != null &&
                activeFolders.all { it.isComplete } && deleted.isComplete
            if (!isAuthoritative) {
                android.util.Log.w("EasTasksService",
                    "syncTasksEws: snapshot incomplete (root=${root.isComplete}, subfolders=${subfolderIds != null}, " +
                        "direct=${directSubfolders != null}, deleted=${deleted.isComplete}), deletions disabled")
                // Корневая папка не прочиталась и задач нет вовсе — это ошибка синка, а не «пусто».
                if (!root.isComplete && allTasks.isEmpty()) {
                    return@withContext EasResult.Error("EWS: не удалось получить задачи")
                }
            }
            val parsedIds = allTasks.mapTo(HashSet()) { it.serverId }
            val unresolved = (activeFolders + deleted).flatMap { it.unresolvedIds }.filterTo(HashSet()) { it !in parsedIds }
            // includesDeletedItems=true: папка deleteditems синкается явно (deleted), поэтому
            // отсутствие локально-удалённой задачи в снимке означает её удаление с сервера.
            EasResult.Success(TasksSyncResult(allTasks, isAuthoritative, unresolved, includesDeletedItems = true))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("EasTasksService", "syncTasksEws: exception: ${e.message}")
            // Раньше: Success(emptyList()) — репозиторий принимал сбой за пустой сервер.
            EasResult.Error("EWS: ошибка синхронизации задач: ${e.message}")
        }
    }
    
    private suspend fun syncTasksFromFolderEws(
        folderLabel: String, 
        isDeleted: Boolean,
        buildRequest: (offset: Int, pageSize: Int) -> String
    ): EwsTaskFolder {
        try {
            // ЭТАП 1: FindItem с IdOnly — получаем ВСЕ ItemId из папки.
            // Exchange 2007 SP1 при AllProperties/IdOnly+task:* может фильтровать items.
            // IdOnly без AdditionalProperties гарантирует ВСЕ элементы.
            val allItemIds = mutableListOf<String>()
            var offset = 0
            val pageSize = 200
            // Папка выдана целиком, только если сервер сам подтвердил последнюю страницу.
            var isComplete = false
            
            while (true) {
                yield()
                val findRequest = buildRequest(offset, pageSize)
                
                val responseXml = executeEwsWithAuth(findRequest, "FindItem")
                if (responseXml == null) {
                    android.util.Log.w("EasTasksService", "syncTasksFromFolderEws($folderLabel): no FindItem response at offset=$offset")
                    break
                }
                // Ошибка, не-EWS ответ (SOAP fault, страница прокси) или оборванный ответ. Fault без
                // ResponseClass="Error" раньше принимался за пустую страницу, и папка считалась пустой.
                if (!EwsClient.isEwsSuccess(responseXml) || !EwsClient.isCompleteEnvelope(responseXml)) {
                    android.util.Log.w("EasTasksService",
                        "syncTasksFromFolderEws($folderLabel): FindItem failed or truncated at offset=$offset")
                    break
                }

                val totalItemsMatch = Companion.REGEX_TOTAL_ITEMS_IN_VIEW.find(responseXml)
                val totalItems = totalItemsMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val indexedPagingOffsetMatch = Companion.REGEX_INDEXED_PAGING_OFFSET.find(responseXml)
                val nextOffset = indexedPagingOffsetMatch?.groupValues?.get(1)?.toIntOrNull()
                val includesLast = responseXml.contains("IncludesLastItemInRange=\"true\"")
                
                val pageIds = Companion.REGEX_ITEM_ID_ATTR.findAll(responseXml).map { it.groupValues[1] }.toList()
                allItemIds.addAll(pageIds)
                
                if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", 
                    "syncTasksFromFolderEws($folderLabel): offset=$offset, totalItemsInView=$totalItems, " +
                    "pageItemIds=${pageIds.size}, includesLast=$includesLast")
                
                if (includesLast) {
                    isComplete = true
                    break
                }
                // Не последняя страница, но листать дальше нельзя: пустая страница или нет
                // продвижения смещения — выдача оборвана.
                if (pageIds.isEmpty() || nextOffset == null || nextOffset <= offset) {
                    android.util.Log.w("EasTasksService",
                        "syncTasksFromFolderEws($folderLabel): paging stalled at offset=$offset (next=$nextOffset)")
                    break
                }
                if (nextOffset > 5000) {
                    android.util.Log.w("EasTasksService", "syncTasksFromFolderEws($folderLabel): stopping at offset=$nextOffset")
                    break
                }
                offset = nextOffset
            }
            
            val uniqueIds = allItemIds.distinct()
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService", 
                "syncTasksFromFolderEws($folderLabel): FindItem complete=$isComplete, ${uniqueIds.size} unique ItemIds")
            
            if (uniqueIds.isEmpty()) return EwsTaskFolder(emptyList(), isComplete)
            
            // ЭТАП 2: GetItem с AllProperties — получаем ВСЕ свойства задач (включая Body).
            val tasks = getTaskDetailsEws(uniqueIds, folderLabel).map { it.copy(isDeleted = isDeleted) }
            // Задачи без деталей (GetItem-батч не пришёл или элемент не разобран) существуют на
            // сервере — их нельзя удалять локально, хотя обновить сейчас нечем.
            val resolvedIds = tasks.mapTo(HashSet()) { it.serverId }
            val unresolvedIds = uniqueIds.filterTo(LinkedHashSet()) { it !in resolvedIds }
            if (unresolvedIds.isNotEmpty()) {
                android.util.Log.w("EasTasksService",
                    "syncTasksFromFolderEws($folderLabel): ${unresolvedIds.size} items without GetItem details, kept as-is")
            }
            return EwsTaskFolder(tasks, isComplete, unresolvedIds)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("EasTasksService", "syncTasksFromFolderEws($folderLabel): exception: ${e.message}")
            return EwsTaskFolder(emptyList(), isComplete = false)
        }
    }
    
    /**
     * GetItem для списка ItemId — возвращает полные EasTask объекты (включая Body).
     * Exchange 2007 SP1: GetItem с AllProperties возвращает ВСЕ свойства задач.
     */
    private suspend fun getTaskDetailsEws(
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
            
            val responseXml = executeEwsWithAuth(getItemRequest, "GetItem") ?: continue

            // Оборванный ответ не разбираем: позиционный fallback ниже собрал бы задачу из обрубка
            // (с усечённым телом). Задачи батча останутся неразрешёнными — их не тронут.
            if (responseXml.isBlank() || !EwsClient.isCompleteEnvelope(responseXml)) continue
            
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
        assignTo: String?
    ): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val soapRequest = buildEwsCreateTaskRequest(subject, body, startDate, dueDate, importance)
            
            val responseXml = executeEwsWithAuth(soapRequest, "CreateItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить EWS CreateItem")

            if (responseXml.contains("ErrorSchemaValidation") || responseXml.contains("ErrorInvalidRequest")) {
                android.util.Log.e("EasTasksService", "createTaskEws: Schema error! Response (first 500 chars): ${responseXml.take(500)}")
                return@withContext EasResult.Error("Ошибка схемы EWS")
            }
            
            val itemId = EasPatterns.EWS_ITEM_ID.find(responseXml)?.groupValues?.get(1)
            
            val result = if (itemId != null) {
                EasResult.Success(itemId)
            } else if (EwsClient.isEwsSuccess(responseXml)) {
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
    
    private suspend fun deleteTaskEwsImpl(
        serverId: String,
        deleteType: String,
        notFoundIsSuccess: Boolean
    ): EasResult<Boolean> {
        val ewsItemId = resolveEwsTaskItemId(serverId)
            ?: return if (notFoundIsSuccess) EasResult.Success(true)
                else EasResult.Error("Не удалось найти задачу на сервере")
        return deleteEwsTaskItem(ewsItemId, deleteType)
    }

    /** EWS DeleteItem по известному ItemId (без повторного разрешения id). */
    private suspend fun deleteEwsTaskItem(ewsItemId: String, deleteType: String): EasResult<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                // buildEwsDeleteRequest экранирует id сам: прежде он экранировался дважды.
                val responseXml = executeEwsWithAuth(buildEwsDeleteRequest(ewsItemId, deleteType), "DeleteItem")
                    ?: return@withContext EasResult.Error("Не удалось выполнить EWS DeleteItem")
                // Отказ сервера — ошибка. Прежде он возвращался как Success(false), а репозиторий
                // проверяет только тип результата: задача пропадала локально, оставаясь на сервере.
                if (EwsClient.isEwsSuccessOrNotFound(responseXml)) {
                    EasResult.Success(true)
                } else {
                    val code = REGEX_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()
                    EasResult.Error("Не удалось удалить задачу на сервере: ${code ?: "EWS DeleteItem"}")
                }
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
            val ewsItemIds = mutableListOf<String>()

            for (sid in serverIds) {
                val ewsItemId = resolveEwsTaskItemId(sid)
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

            val responseXml = executeEwsWithAuth(soapRequest, "DeleteItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить EWS batch DeleteItem")

            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "deleteTasksBatchEws: ${ewsItemIds.size} items, deleteType=$deleteType, response len=${responseXml.length}")

            if (EwsClient.isEwsSuccessOrNotFound(responseXml)) {
                EasResult.Success(ewsItemIds.size)
            } else {
                val errorCode = REGEX_RESPONSE_CODE.find(responseXml)?.groupValues?.get(1)?.trim()
                EasResult.Error("EWS batch DeleteItem tasks: $errorCode")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EasResult.Error("Ошибка batch EWS tasks: ${e.message}")
        }
    }
    
    /**
     * EWS MoveItem задачи из корзины в tasks. Прежде EAS-ветка (Exchange 2010+) слала MoveItems с
     * ServerId из папки Tasks — копии в Deleted Items он не адресует, восстановление не работало;
     * а короткий ServerId здесь искался по теме в tasks, где копии нет.
     */
    private suspend fun restoreTaskEws(serverId: String, subject: String?): EasResult<String> = withContext(Dispatchers.IO) {
        try {
            val ewsItemId = if (serverId.length >= 50 && !serverId.contains(":")) {
                serverId
            } else {
                // null — копии нет, их несколько или EWS недоступен: «не удалось найти», а не «нет».
                findDeletedTaskItemIdEws(subject)
                    ?: return@withContext EasResult.Error("Не удалось найти задачу в «Удалённых» на сервере")
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
            
            val responseXml = executeEwsWithAuth(soapRequest, "MoveItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить EWS MoveItem")
            
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
            // КРИТИЧНО: Используем СТАРЫЙ subject для поиска (если subject изменился)
            val searchSubject = oldSubject ?: subject
            
            val ewsItemId = resolveEwsTaskItemId(serverId, searchSubject)
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
            
            val getItemResponse = executeEwsWithAuth(getItemRequest, "GetItem")
                ?: return@withContext EasResult.Error("Не удалось получить ChangeKey")

            // EWS-1: ChangeKey извлекается независимо от порядка атрибутов/префикса.
            val changeKey = EwsIds.parseChangeKey(getItemResponse) ?: ""
            
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
            
            val responseXml = executeEwsWithAuth(soapRequest, "UpdateItem")
                ?: return@withContext EasResult.Error("Не удалось выполнить EWS UpdateItem")

            if (EwsClient.isEwsSuccess(responseXml)) {
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

        val responseXml = executeEwsWithAuth(findRequest, "FindItem") ?: return null

        // EWS-4: при неоднозначности (несколько задач с одинаковой темой) НЕ выбираем первую —
        // раньше это приводило к операции над ЧУЖОЙ задачей. null → resolveEwsTaskItemId
        // откатывается к сохранённому EWS ItemId конкретной задачи (однозначно и корректно).
        return singleTaskItemIdOrNull(responseXml)
    }

    /**
     * EWS ItemId единственной задачи (IPM.Task) с темой [subject] в deleteditems, иначе null: темы
     * нет, совпадений нет или их несколько (EWS-4: чужую задачу не трогаем), EWS недоступен.
     * Класс в фильтре обязателен — в корзине лежат и письма с той же темой.
     */
    private suspend fun findDeletedTaskItemIdEws(subject: String?): String? {
        if (subject.isNullOrBlank()) return null
        val soapBody = """
    <m:FindItem Traversal="Shallow">
        <m:ItemShape>
            <t:BaseShape>IdOnly</t:BaseShape>
        </m:ItemShape>
        <m:Restriction>
            <t:And>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:Subject"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="${deps.escapeXml(subject)}"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
                <t:IsEqualTo>
                    <t:FieldURI FieldURI="item:ItemClass"/>
                    <t:FieldURIOrConstant>
                        <t:Constant Value="IPM.Task"/>
                    </t:FieldURIOrConstant>
                </t:IsEqualTo>
            </t:And>
        </m:Restriction>
        <m:ParentFolderIds>
            <t:DistinguishedFolderId Id="deleteditems"/>
        </m:ParentFolderIds>
    </m:FindItem>"""
        val responseXml = executeEwsWithAuth(EasXmlTemplates.ewsSoapRequest(soapBody), "FindItem") ?: return null
        // Обрубленный ответ мог потерять второе совпадение — тогда «единственное» ложно.
        if (!EwsClient.isCompleteEnvelope(responseXml)) return null
        return singleTaskItemIdOrNull(responseXml)
    }

    private suspend fun resolveEwsTaskItemId(serverId: String, subject: String? = null): String? {
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
     * null — обнаружение не удалось (нет ответа, ошибка EWS, исключение): это не «подпапок нет».
     */
    private suspend fun discoverTaskSubfolderIds(): List<String>? {
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
            
            val responseXml = executeEwsWithAuth(request, "FindFolder") ?: return null
            if (!isCompleteFolderListing(responseXml)) {
                android.util.Log.w("EasTasksService", "discoverTaskSubfolderIds: FindFolder failed or truncated")
                return null
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
            return null
        }
    }

    /**
     * FindFolder без пагинации: список полон, если ответ успешен, цел и сервер не сообщил
     * `IncludesLastItemInRange="false"`. Отсутствие атрибута не считаем обрывом — иначе
     * удаления задач не доходили бы никогда.
     */
    private fun isCompleteFolderListing(responseXml: String): Boolean =
        EwsClient.isEwsSuccess(responseXml) && EwsClient.isCompleteEnvelope(responseXml) &&
            !responseXml.contains("IncludesLastItemInRange=\"false\"")
    
    /**
     * GetFolder на "tasks" → TotalCount + ChildFolderCount для диагностики.
     */
    private suspend fun getTasksFolderInfo(): Pair<Int, Int> {
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

            val responseXml = executeEwsWithAuth(request, "GetFolder") ?: return Pair(-1, -1)
            if (BuildConfig.DEBUG) android.util.Log.d("EasTasksService",
                "getTasksFolderInfo: response: ${responseXml.take(800)}")

            val totalCount = REGEX_TOTAL_COUNT.find(responseXml)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val childFolderCount = REGEX_CHILD_FOLDER_COUNT.find(responseXml)
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
     * null — обнаружение не удалось (нет ответа, ошибка EWS, исключение).
     */
    private suspend fun discoverDirectTaskSubfolders(): List<String>? {
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

            val responseXml = executeEwsWithAuth(request, "FindFolder") ?: return null
            if (!isCompleteFolderListing(responseXml)) {
                android.util.Log.w("EasTasksService", "discoverDirectTaskSubfolders: FindFolder failed or truncated")
                return null
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
            return null
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
    
    /**
     * EAS Sync задач (MS-ASTASK) — один потоковый проход ([EasSyncStream]): поля — прямые дети
     * `ApplicationData`. Порядок как прежде: сначала все Add, затем все Change.
     * Оборванный или битый ответ — исключение (см. [EasSyncStream]).
     */
    internal fun parseTaskSyncResponse(xml: String): List<EasTask> {
        val adds = mutableListOf<EasTask>()
        val changes = mutableListOf<EasTask>()
        EasSyncStream.forEachCommand(xml, EasSyncStream.ADD_CHANGE) { command, p ->
            val item = EasSyncStream.readItem(p, TASK_FIELDS, withBody = true, withCategories = true)
            item?.let(::toTask)?.let { if (command == "Add") adds += it else changes += it }
        }
        return adds + changes
    }
    
    private fun toTask(item: EasSyncStream.SyncItem): EasTask? {
        val serverId = item.serverId ?: return null
        val f = item.fields
        return EasTask(
            serverId = serverId,
            subject = f["Subject"] ?: "",
            // AirSyncBase Body/Data (EAS 12+). Тело прямо в <Body> бывает только в EAS 2.5: прежний
            // код его отбрасывал, общий readBody берёт — вне поддерживаемых версий разницы нет.
            body = item.body?.let(::removeDuplicateLines) ?: "",
            startDate = parseDate(f["StartDate"] ?: f["UtcStartDate"]),
            dueDate = parseDate(f["DueDate"] ?: f["UtcDueDate"]),
            complete = f["Complete"] == "1",
            dateCompleted = parseDate(f["DateCompleted"]),
            importance = f["Importance"]?.toIntOrNull() ?: 1,
            sensitivity = f["Sensitivity"]?.toIntOrNull() ?: 0,
            reminderSet = f["ReminderSet"] == "1",
            reminderTime = parseDate(f["ReminderTime"]),
            categories = item.categories,
            // MS-ASTASK (schema 2.5–16.1): у класса Tasks НЕТ элемента Owner — по EAS владелец
            // задачи не передаётся в принципе. Owner заполняется только EWS-путём (<t:Owner>).
            owner = ""
        )
    }
    
    internal fun parseEwsTasksResponse(xml: String): List<EasTask> {
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

        // EAS Sync разбор (Add/Change/ApplicationData/тело/категории) — один проход EasSyncStream.
        // EWS-путь ниже намеренно оставлен на regex: позиционный fallback для Exchange 2007 SP1
        // режет произвольные подстроки по смещениям ItemId — XmlPullParser их не разберёт.

        /**
         * Листья ApplicationData задачи (MS-ASTASK), которые читает [parseTaskSyncResponse].
         * Owner в класс Tasks протоколом не входит (schema 2.5–16.1), поэтому его здесь нет.
         */
        private val TASK_FIELDS = setOf(
            "Subject", "StartDate", "UtcStartDate", "DueDate", "UtcDueDate", "Complete", "DateCompleted",
            "Importance", "Sensitivity", "ReminderSet", "ReminderTime"
        )

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
        private val REGEX_CHILD_FOLDER_COUNT = "<(?:t:)?ChildFolderCount>(\\d+)</(?:t:)?ChildFolderCount>".toRegex(RegexOption.DOT_MATCHES_ALL)
        private val REGEX_RESPONSE_CODE = "<(?:m:)?ResponseCode>(.*?)</(?:m:)?ResponseCode>".toRegex(RegexOption.DOT_MATCHES_ALL)
        // EWS-1: REGEX_ITEM_CHANGE_KEY удалён — ChangeKey извлекается через EwsIds (order/prefix-устойчиво).
        private val REGEX_ITEM_ID_T = "<t:ItemId Id=\"([^\"]+)\"".toRegex()

        /**
         * EWS-4: возвращает ItemId ТОЛЬКО если в ответе FindItem ровно одно совпадение.
         * Несколько (одинаковая тема у разных задач) или ноль → null, чтобы не выбрать чужую
         * задачу. Чистая функция — покрыта EasTasksItemIdResolutionTest.
         */
        @androidx.annotation.VisibleForTesting
        internal fun singleTaskItemIdOrNull(findItemResponseXml: String): String? =
            REGEX_ITEM_ID_T.findAll(findItemResponseXml).map { it.groupValues[1] }.toList().singleOrNull()

        // removeDuplicateLines
        private val REGEX_BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val REGEX_P_TAGS = Regex("</p>\\s*<p[^>]*>", RegexOption.IGNORE_CASE)
        private val REGEX_P_OPEN_CLOSE = Regex("</?p[^>]*>", RegexOption.IGNORE_CASE)
        private val REGEX_DIV = Regex("</?div[^>]*>", RegexOption.IGNORE_CASE)
    }
}

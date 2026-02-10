package com.dedovmosol.iwomail.data.repository

import android.content.Context
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.sync.TaskReminderReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с задачами Exchange
 */
class TaskRepository(private val context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val taskDao = database.taskDao()
    private val accountRepo = RepositoryProvider.getAccountRepository(context)
    
    // === Получение задач ===
    
    fun getTasks(accountId: Long): Flow<List<TaskEntity>> {
        return taskDao.getTasksByAccount(accountId)
    }
    
    suspend fun getTasksList(accountId: Long): List<TaskEntity> {
        return taskDao.getTasksByAccountList(accountId)
    }
    
    fun getActiveTasks(accountId: Long): Flow<List<TaskEntity>> {
        return taskDao.getActiveTasks(accountId)
    }
    
    fun getCompletedTasks(accountId: Long): Flow<List<TaskEntity>> {
        return taskDao.getCompletedTasks(accountId)
    }
    
    fun getHighPriorityTasks(accountId: Long): Flow<List<TaskEntity>> {
        return taskDao.getHighPriorityTasks(accountId)
    }
    
    fun getOverdueTasks(accountId: Long): Flow<List<TaskEntity>> {
        return taskDao.getOverdueTasks(accountId, System.currentTimeMillis())
    }
    
    fun getTask(id: String): Flow<TaskEntity?> {
        return taskDao.getTaskFlow(id)
    }
    
    suspend fun getTaskById(id: String): TaskEntity? {
        return taskDao.getTask(id)
    }
    
    fun getTasksCount(accountId: Long): Flow<Int> {
        return taskDao.getTasksCount(accountId)
    }
    
    fun getActiveTasksCount(accountId: Long): Flow<Int> {
        return taskDao.getActiveTasksCount(accountId)
    }
    
    /**
     * Подсчитывает задачи на сегодня (с дедлайном сегодня или просроченные)
     */
    suspend fun getTodayTasksCount(accountId: Long): Int {
        val endOfDay = com.dedovmosol.iwomail.util.DateUtils.getEndOfDay()
        return taskDao.getTodayTasksCount(accountId, endOfDay)
    }
    
    // === Поиск ===
    
    suspend fun searchTasks(accountId: Long, query: String): List<TaskEntity> {
        if (query.isBlank()) return getTasksList(accountId)
        return taskDao.searchTasks(accountId, query)
    }
    
    // === Создание/Редактирование/Удаление ===
    
    /**
     * Создание задачи на сервере и в локальной БД
     * @param assignTo — email пользователя для назначения задачи (опционально)
     */
    suspend fun createTask(
        accountId: Long,
        subject: String,
        body: String = "",
        startDate: Long = 0,
        dueDate: Long = 0,
        importance: Int = TaskImportance.NORMAL.value,
        reminderSet: Boolean = false,
        reminderTime: Long = 0,
        assignTo: String? = null
    ): EasResult<TaskEntity> {
        return withContext(Dispatchers.IO) {
            try {
                // ЗАЩИТА ОТ ДУБЛИРОВАНИЯ: если идентичная задача уже существует — возвращаем её
                val existingTasks = taskDao.getTasksByAccountList(accountId)
                val duplicate = existingTasks.find { existing ->
                    existing.subject == subject &&
                    existing.startDate == startDate &&
                    existing.dueDate == dueDate &&
                    existing.body == body &&
                    !existing.complete
                }
                if (duplicate != null) {
                    android.util.Log.w("TaskRepository", 
                        "createTask: Duplicate detected (subject=$subject), returning existing")
                    return@withContext EasResult.Success(duplicate)
                }
                
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.TASKS_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                var result = easClient.createTask(
                    subject = subject,
                    body = body,
                    startDate = startDate,
                    dueDate = dueDate,
                    importance = importance,
                    reminderSet = reminderSet,
                    reminderTime = reminderTime,
                    assignTo = assignTo
                )
                
                // Retry при ошибке
                if (result is EasResult.Error && (
                    result.message.contains("Status=", ignoreCase = true) ||
                    result.message.contains("failed", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
                )) {
                    kotlinx.coroutines.delay(1000)
                    result = easClient.createTask(
                        subject = subject,
                        body = body,
                        startDate = startDate,
                        dueDate = dueDate,
                        importance = importance,
                        reminderSet = reminderSet,
                        reminderTime = reminderTime,
                        assignTo = assignTo
                    )
                }
                
                when (result) {
                    is EasResult.Success -> {
                        val serverId = result.data
                        // ВАЖНО: Exchange автоматически устанавливает dueDate = startDate если dueDate не указан
                        // Делаем это локально сразу, чтобы пользователь видел корректное значение до синхронизации
                        val effectiveDueDate = if (dueDate == 0L && startDate > 0) startDate else dueDate
                        
                        val task = TaskEntity(
                            id = "${accountId}_${serverId}",
                            accountId = accountId,
                            serverId = serverId,
                            subject = subject,
                            body = body,
                            startDate = startDate,
                            dueDate = effectiveDueDate,
                            complete = false,
                            dateCompleted = 0,
                            importance = importance,
                            sensitivity = 0,
                            reminderSet = reminderSet,
                            reminderTime = reminderTime,
                            categories = "",
                            lastModified = System.currentTimeMillis()
                        )
                        taskDao.insert(task)
                        // Планируем напоминание
                        if (reminderSet && reminderTime > 0) {
                            TaskReminderReceiver.scheduleReminder(context, task)
                        }
                        EasResult.Success(task)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.TASK_CREATE_ERROR)
            }
        }
    }
    
    /**
     * Обновление задачи
     * @param assignTo — email пользователя для назначения задачи (опционально, отправит уведомление при изменении)
     */
    suspend fun updateTask(
        task: TaskEntity,
        subject: String,
        body: String = task.body,
        startDate: Long = task.startDate,
        dueDate: Long = task.dueDate,
        complete: Boolean = task.complete,
        importance: Int = task.importance,
        reminderSet: Boolean = task.reminderSet,
        reminderTime: Long = task.reminderTime,
        oldSubject: String? = null,
        assignTo: String? = null
    ): EasResult<TaskEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                
                val account = accountRepo.getAccount(task.accountId)
                if (account == null) {
                    return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                }
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.TASKS_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(task.accountId)
                if (easClient == null) {
                    return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                }
                
                val requestTime = System.currentTimeMillis()
                var result = easClient.updateTask(
                    serverId = task.serverId,
                    subject = subject,
                    body = body,
                    startDate = startDate,
                    dueDate = dueDate,
                    complete = complete,
                    importance = importance,
                    reminderSet = reminderSet,
                    reminderTime = reminderTime,
                    oldSubject = oldSubject ?: task.subject
                )
                val responseTime = System.currentTimeMillis()
                val requestDuration = responseTime - requestTime
                
                // Retry при ошибке
                if (result is EasResult.Error && (
                    result.message.contains("Status=", ignoreCase = true) ||
                    result.message.contains("failed", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
                )) {
                    kotlinx.coroutines.delay(1000)
                    val retryRequestTime = System.currentTimeMillis()
                    result = easClient.updateTask(
                        serverId = task.serverId,
                        subject = subject,
                        body = body,
                        startDate = startDate,
                        dueDate = dueDate,
                        complete = complete,
                        importance = importance,
                        reminderSet = reminderSet,
                        reminderTime = reminderTime,
                        oldSubject = oldSubject ?: task.subject
                    )
                    val retryResponseTime = System.currentTimeMillis()
                    val retryDuration = retryResponseTime - retryRequestTime
                }
                
                when (result) {
                    is EasResult.Success -> {
                        val newLastModified = System.currentTimeMillis()
                        val updatedTask = task.copy(
                            subject = subject,
                            body = body,
                            startDate = startDate,
                            dueDate = dueDate,
                            complete = complete,
                            dateCompleted = if (complete && task.dateCompleted == 0L) System.currentTimeMillis() else task.dateCompleted,
                            importance = importance,
                            reminderSet = reminderSet,
                            reminderTime = reminderTime,
                            lastModified = newLastModified
                        )
                        
                        taskDao.update(updatedTask)
                        
                        // Проверяем что запись действительно обновилась
                        val verifyTask = taskDao.getTask(updatedTask.id)
                        
                        // Перепланируем напоминание
                        TaskReminderReceiver.cancelReminder(context, task.id)
                        if (reminderSet && reminderTime > 0 && !complete) {
                            TaskReminderReceiver.scheduleReminder(context, updatedTask)
                        }
                        
                        // КРИТИЧНО: Синхронизируем задачи для получения актуального serverId с сервера
                        // Это необходимо для последующего удаления (EWS DeleteItem требует ПОЛНЫЙ ItemId)
                        // Exchange 2007 SP1 требует 2 секунды для обработки UpdateItem
                        // skipRecentDeleteCheck = true отключает защиту от race condition для получения актуального serverId
                        kotlinx.coroutines.delay(2000)
                        val syncResult = syncTasks(task.accountId, skipRecentDeleteCheck = true)
                        
                        // Отправляем уведомление если указан assignTo
                        if (!assignTo.isNullOrBlank()) {
                            try {
                                easClient.sendTaskNotification(assignTo, subject, body, dueDate, importance)
                            } catch (e: Exception) {
                            }
                        }
                        
                        val totalDuration = System.currentTimeMillis() - startTime
                        
                        EasResult.Success(updatedTask)
                    }
                    is EasResult.Error -> {
                        result
                    }
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.TASK_UPDATE_ERROR)
            }
        }
    }
    
    /**
     * Отметить задачу как выполненную/невыполненную
     */
    suspend fun toggleTaskComplete(task: TaskEntity): EasResult<TaskEntity> {
        return updateTask(
            task = task,
            subject = task.subject,
            body = task.body,
            startDate = task.startDate,
            dueDate = task.dueDate,
            complete = !task.complete,
            importance = task.importance,
            reminderSet = task.reminderSet,
            reminderTime = task.reminderTime
        )
    }
    
    /**
     * Удаление задачи (перемещение в корзину)
     */
    suspend fun deleteTask(task: TaskEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(task.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.TASKS_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(task.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                // КРИТИЧНО: Синхронизируем задачи ПЕРЕД удалением чтобы получить актуальный serverId
                // Exchange 2007 SP1 меняет serverId после UpdateItem!
                syncTasks(task.accountId, skipRecentDeleteCheck = true)
                
                // Получаем актуальную версию задачи из БД (с обновлённым serverId)
                val actualTask = taskDao.getTask(task.id) ?: taskDao.getTasksByAccountList(task.accountId)
                    .find { it.subject == task.subject && !it.isDeleted }
                val actualServerId = actualTask?.serverId ?: task.serverId
                
                // Удаляем на сервере (перемещает в корзину Exchange)
                var result = easClient.deleteTask(actualServerId)
                
                // Retry при ошибке
                if (result is EasResult.Error && (
                    result.message.contains("Status=", ignoreCase = true) ||
                    result.message.contains("failed", ignoreCase = true) ||
                    result.message.contains("error", ignoreCase = true)
                )) {
                    kotlinx.coroutines.delay(1000)
                    result = easClient.deleteTask(actualServerId)
                }
                
                when (result) {
                    is EasResult.Success -> {
                        // Локально помечаем как удалённую (soft delete)
                        // Используем actualTask.id если доступен (может отличаться от task.id при изменении serverId)
                        val deleteId = actualTask?.id ?: task.id
                        taskDao.softDelete(deleteId)
                        // Отменяем напоминание
                        TaskReminderReceiver.cancelReminder(context, deleteId)
                        
                        // КРИТИЧНО: Синхронизируем СРАЗУ после удаления с skipRecentDeleteCheck=true
                        // Это позволяет сразу восстановить задачу без ошибки EWS
                        // Exchange 2007 SP1 требует 2 секунды для обработки MoveItems
                        kotlinx.coroutines.delay(2000) // Даём серверу 2 секунды обработать удаление
                        syncTasks(task.accountId, skipRecentDeleteCheck = true)
                        
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> {
                        result
                    }
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.TASK_DELETE_ERROR)
            }
        }
    }
    
    /**
     * Восстановление задачи из корзины
     * Перемещает задачу из Deleted Items обратно в Tasks на сервере
     */
    suspend fun restoreTask(task: TaskEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(task.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.TASKS_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(task.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                // КРИТИЧНО: Восстанавливаем на сервере (MoveItem из Deleted Items в Tasks)
                val result = easClient.restoreTask(task.serverId)
                
                when (result) {
                    is EasResult.Success -> {
                        val newServerId = result.data
                        // Если сервер вернул новый ItemId - обновляем локально
                        if (newServerId != task.serverId) {
                            taskDao.delete(task.id)
                            val restoredTask = task.copy(
                                id = "${task.accountId}_$newServerId",
                                serverId = newServerId,
                                isDeleted = false
                            )
                            taskDao.insert(restoredTask)
                            
                            // Восстанавливаем напоминание если было
                            if (restoredTask.reminderSet && restoredTask.reminderTime > System.currentTimeMillis() && !restoredTask.complete) {
                                TaskReminderReceiver.scheduleReminder(context, restoredTask)
                            }
                        } else {
                            // ServerId не изменился - просто снимаем флаг isDeleted
                            taskDao.restore(task.id)
                            
                            // Восстанавливаем напоминание если было
                            if (task.reminderSet && task.reminderTime > System.currentTimeMillis() && !task.complete) {
                                TaskReminderReceiver.scheduleReminder(context, task.copy(isDeleted = false))
                            }
                        }
                        
                        // КРИТИЧНО: Синхронизируем СРАЗУ после восстановления
                        // Exchange 2007 SP1 требует 2 секунды для обработки MoveItem
                        kotlinx.coroutines.delay(2000)
                        syncTasks(task.accountId)
                        
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.TASK_RESTORE_ERROR)
            }
        }
    }
    
    /**
     * Окончательное удаление задачи (из корзины)
     */
    suspend fun deleteTaskPermanently(task: TaskEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(task.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.TASKS_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(task.accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                // Окончательно удаляем на сервере (HardDelete)
                var result = easClient.deleteTaskPermanently(task.serverId)
                
                // Retry при ошибке
                if (result is EasResult.Error && 
                    !result.message.contains("not found", ignoreCase = true) &&
                    !result.message.contains("ErrorItemNotFound", ignoreCase = true) &&
                    (result.message.contains("Status=", ignoreCase = true) ||
                     result.message.contains("failed", ignoreCase = true) ||
                     result.message.contains("error", ignoreCase = true))
                ) {
                    kotlinx.coroutines.delay(1000)
                    result = easClient.deleteTaskPermanently(task.serverId)
                }
                
                when (result) {
                    is EasResult.Success -> {
                        // Удаляем локально
                        taskDao.delete(task.id)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> {
                        // Если на сервере уже нет — удаляем локально
                        if (result.message.contains("not found", ignoreCase = true) ||
                            result.message.contains("ErrorItemNotFound", ignoreCase = true)) {
                            taskDao.delete(task.id)
                            EasResult.Success(true)
                        } else {
                            result
                        }
                    }
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.TASK_PERMANENT_DELETE_ERROR)
            }
        }
    }
    
    /**
     * Очистка корзины задач (окончательное удаление всех удалённых задач)
     */
    suspend fun emptyTasksTrash(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val deletedTasks = taskDao.getDeletedTasksList(accountId)
                if (deletedTasks.isEmpty()) {
                    return@withContext EasResult.Success(0)
                }
                
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.TASKS_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                var deletedCount = 0
                for (task in deletedTasks) {
                    val result = easClient.deleteTaskPermanently(task.serverId)
                    
                    when (result) {
                        is EasResult.Success -> {
                            taskDao.delete(task.id)
                            deletedCount++
                        }
                        is EasResult.Error -> {
                            // Если на сервере уже нет — удаляем локально
                            if (result.message.contains("not found", ignoreCase = true) ||
                                result.message.contains("ErrorItemNotFound", ignoreCase = true)) {
                                taskDao.delete(task.id)
                                deletedCount++
                            }
                            // Иначе пропускаем, попробуем в следующий раз
                        }
                    }
                }
                
                EasResult.Success(deletedCount)
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.TASK_TRASH_EMPTY_ERROR)
            }
        }
    }
    
    // === Получение удалённых задач ===
    
    fun getDeletedTasks(accountId: Long): Flow<List<TaskEntity>> {
        return taskDao.getDeletedTasks(accountId)
    }
    
    fun getDeletedTasksCount(accountId: Long): Flow<Int> {
        return taskDao.getDeletedTasksCount(accountId)
    }
    
    // === Синхронизация ===
    
    /**
     * Синхронизация задач с Exchange сервера
     * @param skipRecentDeleteCheck если true, отключает защиту от race condition (для явных операций delete/update)
     */
    suspend fun syncTasks(accountId: Long, skipRecentDeleteCheck: Boolean = false): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error(RepositoryErrors.TASKS_EXCHANGE_ONLY)
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
                
                // КРИТИЧНО: Удаляем старые pending_sync записи (старше 30 секунд)
                // Это предотвращает дубликаты когда createTask не получил serverId
                // Проверяем ВСЕ задачи (включая удалённые)
                val existingTasks = taskDao.getTasksByAccountList(accountId)
                val deletedTasks = taskDao.getDeletedTasksList(accountId)
                val allExistingTasks = existingTasks + deletedTasks
                
                val now = System.currentTimeMillis()
                val PENDING_TIMEOUT = 30_000L // 30 секунд
                
                for (task in allExistingTasks) {
                    if (task.serverId.startsWith("pending_sync_")) {
                        val timestamp = task.serverId.removePrefix("pending_sync_").toLongOrNull() ?: 0
                        if (now - timestamp > PENDING_TIMEOUT) {
                            taskDao.delete(task.id)
                        }
                    }
                }
                
                val result = easClient.syncTasks()
                
                when (result) {
                    is EasResult.Success -> {
                        val serverTasks = result.data
                        
                        // КРИТИЧНО: НЕ делаем повторный запрос - используем уже полученные данные
                        // Но нужно учесть что pending_sync могли быть удалены выше
                        val currentExistingTasks = allExistingTasks.filter { 
                            !it.serverId.startsWith("pending_sync_") || 
                            (now - (it.serverId.removePrefix("pending_sync_").toLongOrNull() ?: 0) <= PENDING_TIMEOUT)
                        }
                        
                        // Определяем какие задачи удалены на сервере (нет ни в Tasks, ни в Deleted Items)
                        val serverIds = serverTasks.map { it.serverId }.toSet()
                        val allExistingServerIds = currentExistingTasks.map { it.serverId }.toSet()
                        val deletedServerIds = allExistingServerIds - serverIds
                        
                        // Удаляем те, которых нет на сервере ВООБЩЕ (окончательно удалены)
                        for (serverId in deletedServerIds) {
                            val taskId = "${accountId}_${serverId}"
                            TaskReminderReceiver.cancelReminder(context, taskId)
                            taskDao.delete(taskId)
                        }
                        
                        // КРИТИЧНО: Фильтруем дубликаты по serverId (защита от повторной вставки)
                        val uniqueTasks = serverTasks.distinctBy { it.serverId }
                        
                        // Создаём map для сохранения данных о существующих задачах
                        val existingTasksMap = currentExistingTasks.associateBy { it.serverId }
                        
                        // КРИТИЧНО: Дополнительный map по subject для поиска при изменении serverId
                        // Exchange 2007 меняет serverId после UpdateItem!
                        val existingTasksBySubject = currentExistingTasks.associateBy { it.subject.lowercase() }
                        
                        // КРИТИЧНО: НЕ перезаписываем записи, изменённые локально менее 10 сек назад
                        // Это защита от race condition когда sync получает устаревшие данные после update
                        val syncTime = System.currentTimeMillis()
                        val RECENT_EDIT_THRESHOLD = 10_000L // 10 секунд
                        
                        // Список старых id для удаления (когда serverId изменился)
                        val oldTaskIdsToDelete = mutableListOf<String>()
                        
                        // Добавляем/обновляем задачи с сервера
                        val taskEntities = uniqueTasks.mapNotNull { task ->
                            var existingTask = existingTasksMap[task.serverId]
                            
                            // КРИТИЧНО: Если не нашли по serverId и skipRecentDeleteCheck=true,
                            // ищем по subject (serverId мог измениться после UpdateItem)
                            if (existingTask == null && skipRecentDeleteCheck) {
                                val taskBySubject = existingTasksBySubject[task.subject.lowercase()]
                                if (taskBySubject != null) {
                                    val timeSinceEdit = syncTime - taskBySubject.lastModified
                                    if (timeSinceEdit < RECENT_EDIT_THRESHOLD) {
                                        // Помечаем старую запись на удаление
                                        oldTaskIdsToDelete.add(taskBySubject.id)
                                        existingTask = taskBySubject
                                    }
                                }
                            }
                            
                            // Защита от race condition - НЕ перезаписываем свежие локальные изменения
                            if (existingTask != null) {
                                val timeSinceLocalEdit = syncTime - existingTask.lastModified
                                
                                // СЛУЧАЙ 1: Обычная синхронизация (skipRecentDeleteCheck=false)
                                if (!skipRecentDeleteCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                    val hasLocalChanges = existingTask.subject != task.subject || 
                                                         existingTask.body != task.body ||
                                                         existingTask.complete != task.complete
                                    
                                    if (hasLocalChanges) {
                                        return@mapNotNull null // Блок - защита от race condition
                                    }
                                }
                                
                                // СЛУЧАЙ 2: Явная синхронизация после update/delete (skipRecentDeleteCheck=true)
                                // КРИТИЧНО: Разрешаем обновление ТОЛЬКО если сервер вернул ИЗМЕНЁННЫЕ данные
                                if (skipRecentDeleteCheck && timeSinceLocalEdit < RECENT_EDIT_THRESHOLD) {
                                    val dataChanged = existingTask.subject != task.subject || 
                                                      existingTask.body != task.body ||
                                                      existingTask.complete != task.complete
                                    val serverIdChanged = existingTask.serverId != task.serverId
                                    
                                    if (!dataChanged && !serverIdChanged) {
                                        // Данные и serverId идентичны - сервер вернул ТО ЖЕ что у нас локально
                                        return@mapNotNull null
                                    }
                                }
                                
                                // КРИТИЧНО: Если задача удалена локально недавно (< 30 сек)
                                // и сервер вернул её как активную - НЕ восстанавливаем
                                // Даём серверу время обработать удаление
                                if (existingTask.isDeleted && !task.isDeleted) {
                                    val timeSinceDelete = syncTime - existingTask.lastModified
                                    if (timeSinceDelete < 30_000) {
                                        return@mapNotNull null // Пропускаем восстановление
                                    }
                                }
                            }
                            
                            TaskEntity(
                                id = "${accountId}_${task.serverId}",
                                accountId = accountId,
                                serverId = task.serverId,
                                subject = task.subject,
                                body = if (task.body.isNotBlank()) task.body else (existingTask?.body ?: ""),
                                startDate = if (task.startDate > 0) task.startDate else (existingTask?.startDate ?: 0),
                                dueDate = if (task.dueDate > 0) task.dueDate else (existingTask?.dueDate ?: 0),
                                complete = task.complete,
                                dateCompleted = task.dateCompleted,
                                importance = task.importance,
                                sensitivity = task.sensitivity,
                                reminderSet = task.reminderSet,
                                reminderTime = if (task.reminderTime > 0) task.reminderTime else (existingTask?.reminderTime ?: 0),
                                categories = task.categories.joinToString(","),
                                // КРИТИЧНО: Если обновляем существующую задачу - ставим текущее время
                                // Если новая задача - используем время с сервера
                                lastModified = if (existingTask != null) syncTime else task.lastModified,
                                // Используем флаг isDeleted с сервера (из Deleted Items)
                                isDeleted = task.isDeleted
                            )
                        }
                        
                        // КРИТИЧНО: Сначала удаляем старые записи с устаревшими serverId
                        // (когда Exchange 2007 изменил serverId после UpdateItem)
                        if (oldTaskIdsToDelete.isNotEmpty()) {
                            oldTaskIdsToDelete.forEach { oldId ->
                                TaskReminderReceiver.cancelReminder(context, oldId)
                                taskDao.delete(oldId)
                            }
                        }
                        
                        if (taskEntities.isNotEmpty()) {
                            taskDao.insertAll(taskEntities)
                            // Планируем напоминания только для НЕ удалённых задач
                            val activeTaskEntities = taskEntities.filter { !it.isDeleted }
                            TaskReminderReceiver.rescheduleAllReminders(context, activeTaskEntities)
                        }
                        
                        EasResult.Success(taskEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: RepositoryErrors.TASK_SYNC_ERROR)
            }
        }
    }
}

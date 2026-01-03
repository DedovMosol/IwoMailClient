package com.iwo.mailclient.data.repository

import android.content.Context
import com.iwo.mailclient.data.database.*
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.sync.TaskReminderReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с задачами Exchange
 */
class TaskRepository(private val context: Context) {
    
    private val database = MailDatabase.getInstance(context)
    private val taskDao = database.taskDao()
    private val accountRepo = AccountRepository(context)
    
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
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Задачи поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.createTask(
                    subject = subject,
                    body = body,
                    startDate = startDate,
                    dueDate = dueDate,
                    importance = importance,
                    reminderSet = reminderSet,
                    reminderTime = reminderTime,
                    assignTo = assignTo
                )
                
                when (result) {
                    is EasResult.Success -> {
                        val serverId = result.data
                        val task = TaskEntity(
                            id = "${accountId}_${serverId}",
                            accountId = accountId,
                            serverId = serverId,
                            subject = subject,
                            body = body,
                            startDate = startDate,
                            dueDate = dueDate,
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
                        EasResult.Success(task)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка создания задачи")
            }
        }
    }
    
    /**
     * Обновление задачи
     */
    suspend fun updateTask(
        task: TaskEntity,
        subject: String,
        body: String = "",
        startDate: Long = 0,
        dueDate: Long = 0,
        complete: Boolean = false,
        importance: Int = TaskImportance.NORMAL.value,
        reminderSet: Boolean = false,
        reminderTime: Long = 0
    ): EasResult<TaskEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(task.accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Задачи поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(task.accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.updateTask(
                    serverId = task.serverId,
                    subject = subject,
                    body = body,
                    startDate = startDate,
                    dueDate = dueDate,
                    complete = complete,
                    importance = importance,
                    reminderSet = reminderSet,
                    reminderTime = reminderTime
                )
                
                when (result) {
                    is EasResult.Success -> {
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
                            lastModified = System.currentTimeMillis()
                        )
                        taskDao.update(updatedTask)
                        EasResult.Success(updatedTask)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка обновления задачи")
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
     * Удаление задачи
     */
    suspend fun deleteTask(task: TaskEntity): EasResult<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(task.accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Задачи поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(task.accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.deleteTask(task.serverId)
                
                when (result) {
                    is EasResult.Success -> {
                        taskDao.delete(task.id)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка удаления задачи")
            }
        }
    }
    
    // === Синхронизация ===
    
    /**
     * Синхронизация задач с Exchange сервера
     */
    suspend fun syncTasks(accountId: Long): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error("Аккаунт не найден")
                
                if (AccountType.valueOf(account.accountType) != AccountType.EXCHANGE) {
                    return@withContext EasResult.Error("Задачи поддерживаются только для Exchange")
                }
                
                val easClient = accountRepo.createEasClient(accountId)
                    ?: return@withContext EasResult.Error("Не удалось создать клиент")
                
                val result = easClient.syncTasks()
                
                when (result) {
                    is EasResult.Success -> {
                        val serverTasks = result.data
                        
                        // Получаем существующие задачи
                        val existingTasks = taskDao.getTasksByAccountList(accountId)
                        val existingServerIds = existingTasks.map { it.serverId }.toSet()
                        
                        // Определяем какие задачи удалены на сервере
                        val serverIds = serverTasks.map { it.serverId }.toSet()
                        val deletedServerIds = existingServerIds - serverIds
                        
                        // Удаляем те, которых нет на сервере и отменяем их напоминания
                        for (serverId in deletedServerIds) {
                            val taskId = "${accountId}_${serverId}"
                            TaskReminderReceiver.cancelReminder(context, taskId)
                            taskDao.delete(taskId)
                        }
                        
                        // Фильтруем дубликаты по serverId (защита от повторной вставки)
                        val uniqueTasks = serverTasks.distinctBy { it.serverId }
                        
                        // Добавляем/обновляем задачи с сервера
                        val taskEntities = uniqueTasks.map { task ->
                            TaskEntity(
                                id = "${accountId}_${task.serverId}",
                                accountId = accountId,
                                serverId = task.serverId,
                                subject = task.subject,
                                body = task.body,
                                startDate = task.startDate,
                                dueDate = task.dueDate,
                                complete = task.complete,
                                dateCompleted = task.dateCompleted,
                                importance = task.importance,
                                sensitivity = task.sensitivity,
                                reminderSet = task.reminderSet,
                                reminderTime = task.reminderTime,
                                categories = task.categories.joinToString(","),
                                lastModified = task.lastModified
                            )
                        }
                        
                        if (taskEntities.isNotEmpty()) {
                            taskDao.insertAll(taskEntities)
                            // Планируем напоминания для задач
                            TaskReminderReceiver.rescheduleAllReminders(context, taskEntities)
                        }
                        
                        EasResult.Success(taskEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                EasResult.Error(e.message ?: "Ошибка синхронизации задач")
            }
        }
    }
}

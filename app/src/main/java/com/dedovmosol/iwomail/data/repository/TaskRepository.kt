package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.dedovmosol.iwomail.data.database.*
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.withEasRetry
import com.dedovmosol.iwomail.sync.TaskReminderReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Репозиторий для работы с задачами Exchange
 */
class TaskRepository internal constructor(
    private val context: Context,
    private val database: MailDatabase,
    private val accountRepo: AccountRepository,
    private val offline: OfflineWrites = OfflineWrites.ALWAYS_ONLINE
) {

    constructor(context: Context) : this(
        context,
        MailDatabase.getInstance(context),
        RepositoryProvider.getAccountRepository(context),
        OfflineWrites.of(context)
    )
    
    private val taskDao = database.taskDao()

    /** Правки задач без связи, ждущие отправки ([PendingPimChangeEntity]). */
    private val pendingDao = database.pendingPimChangeDao()

    /** Правка задачи ждёт отправки на сервер (сделана без связи). */
    suspend fun hasPendingChange(taskId: String): Boolean = pendingDao.get(taskId) != null
    private val consecutiveEmptySyncs = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    
    companion object {
        private val syncLocks = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()
        private fun getSyncMutex(accountId: Long) = syncLocks.computeIfAbsent(accountId) { kotlinx.coroutines.sync.Mutex() }
    }
    
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
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return taskDao.searchTasks(accountId, escaped)
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
        return accountRepo.withEasClient(accountId, RepositoryErrors.TASKS_EXCHANGE_ONLY) { easClient ->
            try {
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
                    return@withEasClient EasResult.Success(duplicate)
                }
                if (!assignTo.isNullOrBlank() && offline.offline()) {
                    return@withEasClient EasResult.Error(RepositoryErrors.TASK_ASSIGN_NEEDS_NETWORK)
                }
                if (offline.offline()) {
                    return@withEasClient EasResult.Success(
                        keepTaskCreate(accountId, subject, body, startDate, dueDate, importance, reminderSet, reminderTime)
                    )
                }

                val result = withEasRetry {
                    easClient.createTask(
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
                            lastModified = System.currentTimeMillis(),
                            assignTo = assignTo?.trim() ?: ""
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
                if (e is kotlinx.coroutines.CancellationException) throw e
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
        return accountRepo.withEasClient(task.accountId, RepositoryErrors.TASKS_EXCHANGE_ONLY) { easClient ->
            try {
                val now = System.currentTimeMillis()
                val updatedTask = task.copy(
                    subject = subject,
                    body = body,
                    startDate = startDate,
                    dueDate = dueDate,
                    complete = complete,
                    dateCompleted = if (complete && task.dateCompleted == 0L) now else task.dateCompleted,
                    importance = importance,
                    reminderSet = reminderSet,
                    reminderTime = reminderTime,
                    lastModified = now,
                    assignTo = assignTo?.trim() ?: task.assignTo
                )
                // Назначение — письмо исполнителю: без связи его не отправить, такая правка не откладывается.
                val deferrable = assignTo.isNullOrBlank()
                val serverSubject = oldSubject ?: task.subject
                // Правка той же задачи уже ждёт отправки — новая встаёт за ней: прямой запрос мог бы обогнать
                // очередь, и сервер получил бы старую версию последней.
                if (deferrable && (offline.offline() || pendingDao.get(task.id) != null)) {
                    return@withEasClient EasResult.Success(keepTaskUpdate(task, updatedTask, serverSubject))
                }

                val result = withEasRetry {
                    easClient.updateTask(
                        serverId = task.serverId,
                        subject = subject,
                        body = body,
                        startDate = startDate,
                        dueDate = dueDate,
                        complete = complete,
                        importance = importance,
                        reminderSet = reminderSet,
                        reminderTime = reminderTime,
                        oldSubject = serverSubject
                    )
                }
                
                when (result) {
                    is EasResult.Success -> {
                        saveTaskLocally(task, updatedTask)
                        
                        // NonCancellable: sync должен завершиться даже при повороте экрана
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            kotlinx.coroutines.delay(2000)
                            try {
                                syncTasks(task.accountId, skipRecentDeleteCheck = true)
                            } catch (e: Exception) {
                                android.util.Log.w("TaskRepository",
                                    "updateTask: post-update sync failed: ${e.message}")
                            }
                        }
                        
                        // Отправляем уведомление если указан assignTo
                        if (!assignTo.isNullOrBlank()) {
                            try {
                                easClient.sendTaskNotification(assignTo, subject, body, dueDate, importance)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                        
                        
                        EasResult.Success(updatedTask)
                    }
                    is EasResult.Error ->
                        if (deferrable && offline.canDefer(result.message)) {
                            EasResult.Success(keepTaskUpdate(task, updatedTask, serverSubject))
                        } else {
                            result
                        }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.TASK_UPDATE_ERROR)
            }
        }
    }

    /** Правка — в БД, напоминание — по новым данным. */
    private suspend fun saveTaskLocally(task: TaskEntity, updatedTask: TaskEntity) {
        taskDao.update(updatedTask)
        TaskReminderReceiver.cancelReminder(context, task.id)
        if (updatedTask.reminderSet && updatedTask.reminderTime > 0 && !updatedTask.complete) {
            TaskReminderReceiver.scheduleReminder(context, updatedTask)
        }
    }

    /** Правка без связи: на устройстве — сразу, на сервер — когда появится связь. */
    private suspend fun keepTaskUpdate(task: TaskEntity, updatedTask: TaskEntity, serverSubject: String): TaskEntity {
        saveTaskLocally(task, updatedTask)
        pendingDao.markUpdate(task.id, task.accountId, PimKind.TASK, serverSubject)
        offline.schedule()
        return updatedTask
    }

    /** Перенос в корзину без связи: на устройстве — сразу, на сервер — когда появится связь. */
    private suspend fun keepTaskTrash(task: TaskEntity) {
        taskDao.softDelete(task.id)
        TaskReminderReceiver.cancelReminder(context, task.id)
        pendingDao.markTrash(task.id, task.accountId, PimKind.TASK, task.subject)
        offline.schedule()
    }

    private suspend fun keepTaskCreate(
        accountId: Long,
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long
    ): TaskEntity {
        val token = "local_task_${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val effectiveDueDate = if (dueDate == 0L && startDate > 0) startDate else dueDate
        val task = TaskEntity(
            id = "${accountId}_$token",
            accountId = accountId,
            serverId = token,
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
            lastModified = System.currentTimeMillis(),
            assignTo = ""
        )
        taskDao.insert(task)
        if (reminderSet && reminderTime > 0) {
            TaskReminderReceiver.scheduleReminder(context, task)
        }
        pendingDao.markCreate(task.id, accountId, PimKind.TASK, subject)
        offline.schedule()
        return task
    }

    /**
     * Отправка правки из очереди: текущее состояние задачи — тем же путём (EAS или EWS по версии Exchange), что и
     * правка со связью. Сервер отказал — запись снимается, и синхронизация вернёт его версию; связи нет — остаётся.
     */
    internal suspend fun sendPendingTask(change: PendingPimChangeEntity, resync: Boolean = true): PimSendOutcome {
        val task = taskDao.getTask(change.itemId) ?: run {
            pendingDao.delete(change.itemId) // задачи уже нет (удалена на сервере) — отправлять нечего
            return PimSendOutcome.DONE
        }
        if (offline.offline()) return PimSendOutcome.KEPT
        val client = accountRepo.createEasClient(task.accountId) ?: return PimSendOutcome.KEPT
        var working = task
        if (change.pendingCreate) {
            val created = client.createTask(
                subject = task.subject,
                body = task.body,
                startDate = task.startDate,
                dueDate = task.dueDate,
                importance = task.importance,
                reminderSet = task.reminderSet,
                reminderTime = task.reminderTime,
                assignTo = null
            )
            when (created) {
                is EasResult.Error -> {
                    if (offline.canDefer(created.message)) return PimSendOutcome.KEPT
                    android.util.Log.w("TaskRepository", "Pending task create rejected: ${created.message}")
                    pendingDao.delete(task.id)
                    return PimSendOutcome.DONE
                }
                is EasResult.Success -> {
                    val newId = "${task.accountId}_${created.data}"
                    val renamed = task.copy(id = newId, serverId = created.data)
                    taskDao.delete(task.id)
                    taskDao.insert(renamed)
                    pendingDao.rename(task.id, newId)
                    pendingDao.clearCreate(newId, change.version)
                    working = renamed
                    if (renamed.reminderSet && renamed.reminderTime > 0) {
                        TaskReminderReceiver.cancelReminder(context, task.id)
                        TaskReminderReceiver.scheduleReminder(context, renamed)
                    }
                }
            }
        }
        if (change.pendingUpdate) {
            val result = client.updateTask(
                serverId = working.serverId,
                subject = task.subject,
                body = task.body,
                startDate = task.startDate,
                dueDate = task.dueDate,
                complete = task.complete,
                importance = task.importance,
                reminderSet = task.reminderSet,
                reminderTime = task.reminderTime,
                oldSubject = change.serverSubject.ifBlank { null }
            )
            if (result is EasResult.Error) {
                if (offline.canDefer(result.message)) return PimSendOutcome.KEPT
                android.util.Log.w("TaskRepository", "Pending task update rejected: ${result.message}")
            }
            pendingDao.clearUpdate(working.id, change.version)
        }
        if (change.pendingTrash) {
            val first = client.deleteTask(working.serverId)
            val result = if (first is EasResult.Error && !offline.canDefer(first.message)) {
                // Exchange 2007: после UpdateItem у задачи мог смениться ServerId — как и без очереди (deleteTask),
                // синхронизация и поиск по теме и сроку
                syncTasks(task.accountId, skipRecentDeleteCheck = true)
                val fresh = taskDao.getTasksByAccountList(task.accountId)
                    .find { it.id != working.id && it.subject == working.subject && it.dueDate == working.dueDate }
                if (fresh != null) client.deleteTask(fresh.serverId) else first
            } else {
                first
            }
            if (result is EasResult.Error) {
                if (offline.canDefer(result.message)) return PimSendOutcome.KEPT
                android.util.Log.w("TaskRepository", "Pending task trash rejected: ${result.message}")
            }
            pendingDao.clearTrash(working.id, change.version)
        }
        // Exchange 2007 после UpdateItem может выдать задаче новый ServerId: синхронизация находит её по теме в окне
        // свежей правки, поэтому время правки — сейчас. Только это поле: правку, сделанную во время отправки, не трогаем.
        taskDao.touch(working.id, System.currentTimeMillis())
        if (resync) syncTasks(task.accountId, skipRecentDeleteCheck = true)
        return PimSendOutcome.DONE
    }

    /** Синхронизация после отправки очереди: один раз на аккаунт, а не на каждую задачу. */
    internal suspend fun syncAfterPendingSent(accountId: Long) {
        syncTasks(accountId, skipRecentDeleteCheck = true)
    }

    /** Отправить правки задач аккаунта, ждущие отправки. true — не осталось ни одной. */
    private suspend fun flushPending(accountId: Long): Boolean = PendingPimChanges(
        pendingDao,
        send = { sendPendingTask(it, resync = false) },
        afterSent = { account, _ -> syncAfterPendingSent(account) }
    ).flush(accountId, PimKind.TASK)
    
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
        return accountRepo.withEasClient(task.accountId, RepositoryErrors.TASKS_EXCHANGE_ONLY) { easClient ->
            try {
                // Без связи или за правкой, ждущей отправки, — в очередь (порядок правок на сервере сохраняется)
                if (offline.offline() || pendingDao.get(task.id) != null) {
                    keepTaskTrash(task)
                    return@withEasClient EasResult.Success(true)
                }
                var actualTask: TaskEntity? = task
                var actualServerId = task.serverId
                
                var result = withEasRetry { easClient.deleteTask(actualServerId) }
                if (result is EasResult.Error && offline.canDefer(result.message)) {
                    keepTaskTrash(task)
                    return@withEasClient EasResult.Success(true)
                }
                
                if (result is EasResult.Error) {
                    syncTasks(task.accountId, skipRecentDeleteCheck = true)
                    val fresh = taskDao.getTask(task.id) ?: taskDao.getTasksByAccountList(task.accountId)
                        .find { it.subject == task.subject && it.dueDate == task.dueDate && !it.isDeleted }
                    if (fresh != null && fresh.serverId != task.serverId) {
                        actualTask = fresh
                        actualServerId = fresh.serverId
                        result = withEasRetry { easClient.deleteTask(actualServerId) }
                    }
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.TASK_DELETE_ERROR)
            }
        }
    }
    
    /**
     * Восстановление задачи из корзины
     * Перемещает задачу из Deleted Items обратно в Tasks на сервере
     */
    suspend fun restoreTask(task: TaskEntity): EasResult<Boolean> {
        // Перенос в корзину ещё не отправлен — на сервере задача не в корзине: восстанавливаем только на устройстве
        if (pendingDao.cancelTrash(task.id)) {
            taskDao.restore(task.id)
            if (task.reminderSet && task.reminderTime > System.currentTimeMillis() && !task.complete) {
                TaskReminderReceiver.scheduleReminder(context, task.copy(isDeleted = false))
            }
            return EasResult.Success(true)
        }
        return accountRepo.withEasClient(task.accountId, RepositoryErrors.TASKS_EXCHANGE_ONLY) { easClient ->
            try {
                // КРИТИЧНО: Восстанавливаем на сервере (MoveItem из Deleted Items в Tasks)
                val result = easClient.restoreTask(task.serverId, task.subject)
                
                when (result) {
                    is EasResult.Success -> {
                        val newServerId = result.data
                        if (newServerId != task.serverId) {
                            val restoredTask = task.copy(
                                id = "${task.accountId}_$newServerId",
                                serverId = newServerId,
                                isDeleted = false
                            )
                            database.withTransaction {
                                taskDao.delete(task.id)
                                taskDao.insert(restoredTask)
                            }
                            
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.TASK_RESTORE_ERROR)
            }
        }
    }
    
    /**
     * Окончательное удаление задачи (из корзины)
     */
    suspend fun deleteTaskPermanently(task: TaskEntity): EasResult<Boolean> {
        return accountRepo.withEasClient(task.accountId, RepositoryErrors.TASKS_EXCHANGE_ONLY) { easClient ->
            try {
                // Перенос в корзину ещё не на сервере: EAS ищет копию в Deleted Items, а её там нет — задача осталась бы
                // на сервере и вернулась синхронизацией. Сначала — очередь.
                // После переноса синхронизация могла пересоздать запись с новым ServerId — задача находится заново.
                val target = pendingDao.get(task.id)?.let { change ->
                    sendPendingTask(change)
                    if (pendingDao.get(task.id) != null) {
                        return@withEasClient EasResult.Error(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
                    }
                    taskDao.getTask(task.id) ?: taskDao.getDeletedTasksList(task.accountId)
                        .find { it.subject == task.subject && it.dueDate == task.dueDate }
                } ?: task
                // Окончательно удаляем на сервере (HardDelete). Тема нужна EAS-синку: копию в
                // Deleted Items адресует только поиск по теме (см. EasTasksService).
                val result = withEasRetry { easClient.deleteTaskPermanently(target.serverId, target.subject) }
                
                when (result) {
                    is EasResult.Success -> {
                        // Удаляем локально
                        taskDao.delete(target.id)
                        EasResult.Success(true)
                    }
                    is EasResult.Error -> {
                        // Если на сервере уже нет — удаляем локально
                        if (result.message.contains("not found", ignoreCase = true) ||
                            result.message.contains("ErrorItemNotFound", ignoreCase = true)) {
                            taskDao.delete(target.id)
                            EasResult.Success(true)
                        } else {
                            result
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.TASK_PERMANENT_DELETE_ERROR)
            }
        }
    }
    
    /**
     * Очистка корзины задач (окончательное удаление всех удалённых задач)
     */
    suspend fun emptyTasksTrash(accountId: Long): EasResult<Int> {
        return accountRepo.withEasClient(accountId, RepositoryErrors.TASKS_EXCHANGE_ONLY) { easClient ->
            try {
                // Корзина, ещё не отправленная на сервер, — сначала очередь (см. deleteTaskPermanently)
                if (!flushPending(accountId)) {
                    return@withEasClient EasResult.Error(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
                }
                val deletedTasks = taskDao.getDeletedTasksList(accountId)
                if (deletedTasks.isEmpty()) {
                    return@withEasClient EasResult.Success(0)
                }

                val batchResult = easClient.deleteTasksPermanentlyBatch(deletedTasks.map { it.serverId to it.subject })
                val batchOk = batchResult is EasResult.Success && batchResult.data > 0

                if (batchOk) {
                    for (task in deletedTasks) {
                        taskDao.delete(task.id)
                    }
                    EasResult.Success((batchResult as EasResult.Success).data)
                } else {
                    val errMsg = (batchResult as? EasResult.Error)?.message ?: "batch returned 0"
                    android.util.Log.w("TaskRepository",
                        "emptyTasksTrash: batch failed ($errMsg), falling back to sequential")
                    var deletedCount = 0
                    for (task in deletedTasks) {
                        val result = easClient.deleteTaskPermanently(task.serverId, task.subject)
                        when (result) {
                            is EasResult.Success -> {
                                taskDao.delete(task.id)
                                deletedCount++
                            }
                            is EasResult.Error -> {
                                if (result.message.contains("not found", ignoreCase = true) ||
                                    result.message.contains("ErrorItemNotFound", ignoreCase = true)) {
                                    taskDao.delete(task.id)
                                    deletedCount++
                                }
                            }
                        }
                    }
                    EasResult.Success(deletedCount)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
    /**
     * Локальный сброс задач + полный ресинк (ср. CalendarRepository.resetAndResync). Стираем все
     * локальные задачи аккаунта и перезагружаем с сервера. Сервер не трогаем. Кэш EAS-клиента
     * сбрасываем, чтобы ресинк пошёл полным проходом. [syncTasks] сам берёт sync-мьютекс (ожидает),
     * поэтому wipe — в отдельной критической секции, затем ресинк.
     */
    suspend fun resetAndResync(accountId: Long): EasResult<Int> {
        // Сброс стёр бы правки, сделанные без связи и ещё не отправленные: сначала — очередь
        if (!flushPending(accountId)) return EasResult.Error(RepositoryErrors.PENDING_CHANGES_NOT_SENT)
        getSyncMutex(accountId).withLock {
            withContext(Dispatchers.IO) {
                val tasks = taskDao.getTasksByAccountList(accountId)
                for (task in tasks) TaskReminderReceiver.cancelReminder(context, task.id)
                taskDao.deleteByAccount(accountId)
            }
            accountRepo.clearEasClientCache(accountId)
        }
        return syncTasks(accountId, skipRecentDeleteCheck = true)
    }

    suspend fun syncTasks(accountId: Long, skipRecentDeleteCheck: Boolean = false): EasResult<Int> {
        return withContext(Dispatchers.IO) {
            getSyncMutex(accountId).withLock {
            try {
                val account = accountRepo.getAccount(accountId)
                    ?: return@withContext EasResult.Error(RepositoryErrors.ACCOUNT_NOT_FOUND)
                
                if (AccountType.fromStored(account.accountType) != AccountType.EXCHANGE) {
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
                        val snapshot = result.data
                        val serverTasks = snapshot.tasks
                        
                        // КРИТИЧНО: НЕ делаем повторный запрос - используем уже полученные данные
                        // Но нужно учесть что pending_sync могли быть удалены выше
                        val currentExistingTasks = allExistingTasks.filter { 
                            !it.serverId.startsWith("pending_sync_") || 
                            (now - (it.serverId.removePrefix("pending_sync_").toLongOrNull() ?: 0) <= PENDING_TIMEOUT)
                        }
                        
                        // Определяем какие задачи удалены на сервере (нет ни в Tasks, ни в Deleted Items).
                        // Задачи, существование которых сервер подтвердил без деталей (EWS GetItem), сохраняем.
                        val serverIds = serverTasks.map { it.serverId }.toSet() + snapshot.unresolvedServerIds
                        // Диффом можно удалять только то, что снимок реально охватывает. EAS Sync читает
                        // лишь активную папку Tasks (includesDeletedItems=false) — локально-удалённой
                        // задачи в нём нет ПО ПОСТРОЕНИЮ, и её отсутствие ничего не доказывает. Иначе
                        // локальная корзина стиралась бы на каждом EAS-синке. EWS синкает deleteditems.
                        val deletionDomain = if (snapshot.includesDeletedItems) {
                            currentExistingTasks
                        } else {
                            currentExistingTasks.filter { !it.isDeleted }
                        }.filter {
                            !it.serverId.startsWith("local_task_") && !it.serverId.startsWith("pending_sync_")
                        }
                        val deletedServerIds = deletionDomain.map { it.serverId }.toSet() - serverIds
                        
                        val activeLocalTasks = currentExistingTasks.filter { !it.isDeleted && !it.serverId.startsWith("pending_sync_") }
                        val activeServerTasks = serverTasks.filter { !it.isDeleted }
                        val emptyCount = consecutiveEmptySyncs[accountId] ?: 0
                        // Отсутствие задачи в снимке доказывает её удаление только при полной выдаче.
                        // Неполный снимок (обрыв, ошибка, лимит) удаления не разрешает; эвристики ниже —
                        // вторая линия защиты для полных снимков.
                        val shouldDeleteFromServer = if (!snapshot.isAuthoritative) {
                            if (deletedServerIds.isNotEmpty()) {
                                android.util.Log.w("TaskRepository", "syncTasks: snapshot incomplete (${serverTasks.size} tasks), keeping ${deletedServerIds.size} local tasks absent from it")
                            }
                            false
                        } else if (activeLocalTasks.size > 3 && activeServerTasks.isEmpty() && emptyCount < 1) {
                            android.util.Log.w("TaskRepository", "syncTasks: server returned 0 active tasks but local has ${activeLocalTasks.size}, skipping deletion (attempt ${emptyCount + 1})")
                            consecutiveEmptySyncs[accountId] = emptyCount + 1
                            false
                        } else if (activeLocalTasks.size >= 5 && activeServerTasks.size == 1 && deletedServerIds.size >= 3 && emptyCount < 1) {
                            android.util.Log.w("TaskRepository", "syncTasks: server returned only ${activeServerTasks.size} active tasks but local has ${activeLocalTasks.size}, skipping (attempt ${emptyCount + 1})")
                            consecutiveEmptySyncs[accountId] = emptyCount + 1
                            false
                        } else {
                            consecutiveEmptySyncs.remove(accountId)
                            true
                        }
                        
                        if (shouldDeleteFromServer) {
                            for (serverId in deletedServerIds) {
                                val taskId = "${accountId}_${serverId}"
                                TaskReminderReceiver.cancelReminder(context, taskId)
                                taskDao.delete(taskId)
                            }
                        }
                        
                        // КРИТИЧНО: Фильтруем дубликаты по serverId (защита от повторной вставки)
                        val uniqueTasks = serverTasks.distinctBy { it.serverId }
                        
                        // Правки без связи, ещё не отправленные: сервер их не видел, его версия старше — не перетираем
                        // (и не возвращаем из корзины). Удаление на сервере (диф выше) по-прежнему сильнее.
                        val pendingIds = pendingDao.getItemIds(accountId, PimKind.TASK).toHashSet()

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
                                if (taskBySubject != null && taskBySubject.id !in pendingIds) {
                                    val timeSinceEdit = syncTime - taskBySubject.lastModified
                                    if (timeSinceEdit < RECENT_EDIT_THRESHOLD) {
                                        // Помечаем старую запись на удаление
                                        oldTaskIdsToDelete.add(taskBySubject.id)
                                        existingTask = taskBySubject
                                    }
                                }
                            }
                            
                            if (existingTask != null && existingTask.id in pendingIds) return@mapNotNull null

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
                                lastModified = if (existingTask != null) syncTime else task.lastModified,
                                isDeleted = task.isDeleted,
                                owner = task.owner.ifBlank { existingTask?.owner ?: "" },
                                assignTo = existingTask?.assignTo ?: ""
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
                            for (chunk in taskEntities.chunked(500)) {
                                taskDao.insertAll(chunk)
                            }
                            // Планируем напоминания только для НЕ удалённых задач
                            val activeTaskEntities = taskEntities.filter { !it.isDeleted }
                            TaskReminderReceiver.rescheduleAllReminders(context, activeTaskEntities)
                        }
                        
                        EasResult.Success(taskEntities.size)
                    }
                    is EasResult.Error -> result
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                EasResult.Error(e.message ?: RepositoryErrors.TASK_SYNC_ERROR)
            }
            } // end withLock
        }
    }
}

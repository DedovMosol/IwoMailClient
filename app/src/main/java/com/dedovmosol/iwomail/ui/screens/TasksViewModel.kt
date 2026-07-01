package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.TaskRepository
import com.dedovmosol.iwomail.eas.EasResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Неизменяемое состояние экрана задач.
 *
 * Живёт в [TasksViewModel] и переживает поворот экрана. Раньше флаг загрузки хранился в
 * `dataLoaded by rememberSaveable(accountId)` и при повороте/переключении аккаунта мог
 * приводить к лишней авто-синхронизации — теперь единый источник правды в VM.
 *
 * Раздельные наборы выделения ([selectedActiveIds] / [selectedDeletedIds]) повторяют исходную
 * семантику экрана: активные и удалённые задачи выбираются независимо, удаление выбранного
 * набора окончательное только когда выбраны исключительно удалённые задачи.
 */
data class TasksUiState(
    val accountId: Long = 0L,
    val accountEmail: String = "",
    val tasks: List<TaskEntity> = emptyList(),
    val deletedTasks: List<TaskEntity> = emptyList(),
    val filter: TaskFilter = TaskFilter.ALL,
    val selectedActiveIds: Set<String> = emptySet(),
    val selectedDeletedIds: Set<String> = emptySet(),
    val query: String = "",
    val isSyncing: Boolean = false,
    val isCreating: Boolean = false,     // защита от double-tap при создании/редактировании
    val isInitialLoadDone: Boolean = false
) {
    val isSelectionMode: Boolean get() = selectedActiveIds.isNotEmpty() || selectedDeletedIds.isNotEmpty()
}

/**
 * Одноразовые события (side-effects), которые происходят ровно один раз и не являются частью
 * состояния: тосты. Локализация выполняется в UI — ViewModel остаётся независимой от языка/ресурсов
 * и эмитит семантические события с числовыми параметрами.
 *
 * Прим.: операции с прогресс-баром (восстановление, окончательное удаление, очистка корзины)
 * оркеструются [com.dedovmosol.iwomail.ui.components.DeletionController] в UI и получают результат
 * напрямую из suspend-обёрток VM, поэтому их тосты не идут через этот канал.
 */
sealed interface TasksEvent {
    data class Synced(val count: Int) : TasksEvent
    data object TaskCreated : TasksEvent
    data object TaskUpdated : TasksEvent
    data class MovedToTrash(val count: Int) : TasksEvent
    data class CompleteToggled(val completed: Boolean) : TasksEvent
    data class Error(val message: String) : TasksEvent
}

/**
 * ViewModel экрана задач (MVVM-слой).
 *
 * Держит состояние списка/корзины/фильтра/выделения/поиска и инкапсулирует бизнес-логику
 * (синхронизация, CRUD, мягкое/окончательное удаление, восстановление, очистка корзины) в одном
 * месте (DRY/SRP). Зависимости — через конструктор (DIP), что делает VM юнит-тестируемой без
 * Robolectric: [TaskRepository] и [AccountRepository] мокаются, [ioDispatcher] подменяется тестовым.
 *
 * Поскольку [TaskRepository] предоставляет только одиночные операции delete/restore/permanent,
 * пакетные обёртки делают цикл здесь (а не в Composable) — это переносит логику из UI в
 * тестируемый слой и сохраняет поведение прогресс-бара 1:1.
 *
 * Протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются — это слой представления.
 */
class TasksViewModel(
    private val taskRepo: TaskRepository,
    private val accountRepo: AccountRepository,
    initialFilter: TaskFilter = TaskFilter.ALL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState(filter = initialFilter))
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    private val _events = Channel<TasksEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Троттлинг авто-синхронизации корзины при переключении на фильтр "Удалённые" (как в исходном UI).
    private var lastDeletedSyncMs = 0L

    init {
        observeAccountAndTasks()
    }

    /**
     * Реактивно отслеживает активный аккаунт и его задачи. `collectLatest` гарантирует, что при
     * смене аккаунта внутренняя подписка на Room-потоки отменяется и пересоздаётся — без утечек и
     * без наложения данных от разных аккаунтов.
     */
    private fun observeAccountAndTasks() {
        viewModelScope.launch {
            accountRepo.activeAccount
                .map { (it?.id ?: 0L) to (it?.email ?: "") }
                .distinctUntilChanged()
                .collectLatest { (id, email) ->
                    _uiState.update {
                        it.copy(
                            accountId = id,
                            accountEmail = email,
                            selectedActiveIds = emptySet(),
                            selectedDeletedIds = emptySet(),
                            isInitialLoadDone = false,
                            tasks = emptyList(),
                            deletedTasks = emptyList()
                        )
                    }
                    if (id <= 0L) {
                        _uiState.update { it.copy(isInitialLoadDone = true) }
                        return@collectLatest
                    }
                    lastDeletedSyncMs = 0L
                    launch { maybeAutoSync(id) }
                    combine(
                        taskRepo.getTasks(id),
                        taskRepo.getDeletedTasks(id)
                    ) { active, deleted -> active to deleted }
                        .collect { (active, deleted) ->
                            _uiState.update { s ->
                                s.copy(
                                    tasks = active,
                                    deletedTasks = deleted,
                                    isInitialLoadDone = s.isInitialLoadDone || active.isNotEmpty()
                                )
                            }
                        }
                }
        }
    }

    /** Тихая авто-синхронизация при первом открытии, если локальных задач нет. */
    private suspend fun maybeAutoSync(id: Long) {
        delay(500)
        val s = _uiState.value
        if (s.accountId == id && !s.isInitialLoadDone && s.tasks.isEmpty() && !s.isSyncing) {
            _uiState.update { it.copy(isInitialLoadDone = true) }
            runSync(id, announce = false)
        } else {
            _uiState.update { if (it.accountId == id) it.copy(isInitialLoadDone = true) else it }
        }
    }

    private suspend fun runSync(id: Long, announce: Boolean) {
        _uiState.update { it.copy(isSyncing = true) }
        try {
            when (val result = withContext(ioDispatcher) { taskRepo.syncTasks(id) }) {
                is EasResult.Success -> if (announce) sendEvent(TasksEvent.Synced(result.data))
                is EasResult.Error -> if (announce) sendEvent(TasksEvent.Error(result.message))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TasksViewModel", "Sync failed", e)
        } finally {
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    // === Состояние UI ===

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun clearQuery() {
        _uiState.update { it.copy(query = "") }
    }

    /**
     * Меняет фильтр. Всегда сбрасывает выделение (как исходный `LaunchedEffect(currentFilter)`),
     * а при переходе на корзину запускает троттлингованную авто-синхронизацию (не чаще раза в 30с).
     */
    fun selectFilter(filter: TaskFilter) {
        _uiState.update { it.copy(filter = filter, selectedActiveIds = emptySet(), selectedDeletedIds = emptySet()) }
        if (filter == TaskFilter.DELETED) {
            val s = _uiState.value
            val now = System.currentTimeMillis()
            if (s.accountId > 0 && !s.isSyncing && now - lastDeletedSyncMs > 30_000L) {
                lastDeletedSyncMs = now
                viewModelScope.launch { runSync(s.accountId, announce = false) }
            }
        }
    }

    // === Выделение ===

    fun toggleActiveSelection(id: String) {
        _uiState.update {
            val sel = if (id in it.selectedActiveIds) it.selectedActiveIds - id else it.selectedActiveIds + id
            it.copy(selectedActiveIds = sel)
        }
    }

    fun toggleDeletedSelection(id: String) {
        _uiState.update {
            val sel = if (id in it.selectedDeletedIds) it.selectedDeletedIds - id else it.selectedDeletedIds + id
            it.copy(selectedDeletedIds = sel)
        }
    }

    fun addActiveSelection(id: String) {
        _uiState.update { it.copy(selectedActiveIds = it.selectedActiveIds + id) }
    }

    fun addDeletedSelection(id: String) {
        _uiState.update { it.copy(selectedDeletedIds = it.selectedDeletedIds + id) }
    }

    fun setActiveSelection(ids: Set<String>) {
        _uiState.update { it.copy(selectedActiveIds = ids) }
    }

    fun setDeletedSelection(ids: Set<String>) {
        _uiState.update { it.copy(selectedDeletedIds = ids) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedActiveIds = emptySet(), selectedDeletedIds = emptySet()) }
    }

    /** Снимает указанные id с обоих наборов выделения (вызывается перед пакетным удалением). */
    fun removeFromSelection(ids: Set<String>) {
        _uiState.update {
            it.copy(
                selectedActiveIds = it.selectedActiveIds - ids,
                selectedDeletedIds = it.selectedDeletedIds - ids
            )
        }
    }

    // === Синхронизация ===

    fun syncTasks() {
        val id = _uiState.value.accountId
        if (id <= 0L) return
        viewModelScope.launch { runSync(id, announce = true) }
    }

    // === Создание / редактирование ===

    /**
     * Создаёт или обновляет задачу. [existing] == null → создание. Защищено от double-tap флагом
     * [TasksUiState.isCreating]. UI закрывает диалог и показывает тост по событиям из канала.
     */
    fun saveTask(
        existing: TaskEntity?,
        subject: String,
        body: String,
        startDate: Long,
        dueDate: Long,
        importance: Int,
        reminderSet: Boolean,
        reminderTime: Long,
        assignTo: String?
    ) {
        if (_uiState.value.isCreating) return
        val id = _uiState.value.accountId
        if (existing == null && id <= 0L) return
        _uiState.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    if (existing != null) {
                        taskRepo.updateTask(
                            task = existing,
                            subject = subject,
                            body = body,
                            startDate = startDate,
                            dueDate = dueDate,
                            complete = existing.complete,
                            importance = importance,
                            reminderSet = reminderSet,
                            reminderTime = reminderTime,
                            assignTo = assignTo
                        )
                    } else {
                        taskRepo.createTask(
                            accountId = id,
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
                }
                when (result) {
                    is EasResult.Success -> sendEvent(if (existing != null) TasksEvent.TaskUpdated else TasksEvent.TaskCreated)
                    is EasResult.Error -> sendEvent(TasksEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(TasksEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isCreating = false) }
            }
        }
    }

    /** Переключает статус выполнения задачи. */
    fun toggleComplete(task: TaskEntity) {
        viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { taskRepo.toggleTaskComplete(task) }) {
                    is EasResult.Success -> sendEvent(TasksEvent.CompleteToggled(result.data.complete))
                    is EasResult.Error -> sendEvent(TasksEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(TasksEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    // === Мягкое удаление (в корзину) ===

    /** Мягкое удаление одной задачи (из диалога просмотра). */
    fun deleteToTrash(task: TaskEntity) {
        viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { taskRepo.deleteTask(task) }) {
                    is EasResult.Success -> sendEvent(TasksEvent.MovedToTrash(1))
                    is EasResult.Error -> sendEvent(TasksEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(TasksEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Мягкое удаление набора активных задач (из диалога подтверждения). Снимает их с выделения
     * сразу, затем удаляет по одной (как в исходном UI) и сообщает о числе успешно удалённых.
     */
    fun deleteSelectedToTrash(tasks: List<TaskEntity>) {
        if (tasks.isEmpty()) return
        removeFromSelection(tasks.map { it.id }.toSet())
        viewModelScope.launch {
            try {
                val deleted = withContext(ioDispatcher) {
                    var count = 0
                    for (task in tasks) {
                        if (taskRepo.deleteTask(task) is EasResult.Success) count++
                    }
                    count
                }
                if (deleted > 0) sendEvent(TasksEvent.MovedToTrash(deleted))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(TasksEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    // === Операции с прогресс-баром (вызываются из DeletionController в UI) ===
    // Тонкие suspend-обёртки: инкапсулируют репозиторий, IO-диспетчер и цикл по элементам, чтобы
    // Composable не обращался к TaskRepository напрямую. Выполняются на scope контроллера удаления
    // (не в viewModelScope), поэтому переживают выход с экрана — поведение сохранено 1:1.

    suspend fun restoreTask(task: TaskEntity): EasResult<Boolean> =
        withContext(ioDispatcher) { taskRepo.restoreTask(task) }

    suspend fun restoreTasks(
        tasks: List<TaskEntity>,
        onProgress: (restored: Int, total: Int) -> Unit
    ): EasResult<Int> = withContext(ioDispatcher) {
        var restored = 0
        for (task in tasks) {
            if (taskRepo.restoreTask(task) is EasResult.Success) restored++
            onProgress(restored, tasks.size)
        }
        EasResult.Success(restored)
    }

    suspend fun deleteTaskPermanently(task: TaskEntity): EasResult<Boolean> =
        withContext(ioDispatcher) { taskRepo.deleteTaskPermanently(task) }

    suspend fun deleteTasksPermanently(
        tasks: List<TaskEntity>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> = withContext(ioDispatcher) {
        var deleted = 0
        for (task in tasks) {
            if (taskRepo.deleteTaskPermanently(task) is EasResult.Success) deleted++
            onProgress(deleted, tasks.size)
        }
        EasResult.Success(deleted)
    }

    suspend fun emptyTrash(): EasResult<Int> =
        withContext(ioDispatcher) { taskRepo.emptyTasksTrash(_uiState.value.accountId) }

    private fun sendEvent(event: TasksEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        fun provideFactory(
            application: Application,
            initialFilter: TaskFilter
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TasksViewModel(
                        RepositoryProvider.getTaskRepository(application),
                        RepositoryProvider.getAccountRepository(application),
                        initialFilter
                    ) as T
                }
            }
    }
}

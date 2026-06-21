package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Неизменяемое состояние экрана пользовательских папок.
 *
 * Живёт в [UserFoldersViewModel] и переживает поворот экрана. Раньше состояние (список, выбор,
 * флаги синка/создания, прогресс пакетного удаления) хранилось в `remember`/`rememberSaveable`
 * внутри Composable, а долгие операции запускались в `rememberSyncScope`, который отменялся при
 * повороте экрана. Теперь единый источник правды в VM, а корутины живут в `viewModelScope`.
 */
data class UserFoldersUiState(
    val accountId: Long = 0L,
    /** Уже отфильтрованные (только пользовательские) и отсортированные по имени папки. */
    val folders: List<FolderEntity> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val isCreatingFolder: Boolean = false,   // защита от double-tap при создании
    val isInitialLoadDone: Boolean = false,
    /** Прогресс пакетного удаления: null = неактивно, иначе (обработано, всего). */
    val batchDeleteProgress: Pair<Int, Int>? = null
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * Одноразовые события (side-effects), которые происходят ровно один раз и не являются частью
 * состояния: тосты. Локализация выполняется в UI — ViewModel остаётся независимой от языка и
 * ресурсов и эмитит семантические события. Для ошибок передаётся «сырой» текст из репозитория,
 * который UI прогоняет через `NotificationStrings.localizeError`.
 */
sealed interface UserFoldersEvent {
    data object Synced : UserFoldersEvent
    data object FolderCreated : UserFoldersEvent
    data object FolderRenamed : UserFoldersEvent
    data object FolderDeleted : UserFoldersEvent
    data class FoldersDeleted(val count: Int) : UserFoldersEvent
    data class Error(val message: String) : UserFoldersEvent
}

/**
 * ViewModel экрана пользовательских папок (MVVM-слой).
 *
 * Держит состояние списка/выделения и инкапсулирует бизнес-логику (синхронизация, создание,
 * переименование, одиночное и пакетное удаление) в одном месте (DRY/SRP). Зависимости — через
 * конструктор (DIP), что делает VM юнит-тестируемой без Robolectric: [MailRepository] и
 * [AccountRepository] мокаются, [ioDispatcher] подменяется тестовым.
 *
 * Протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются — это слой представления.
 * Пакетное удаление выполняется последовательно: per-account `Mutex` во `FolderSyncService` всё
 * равно сериализовал бы конкурентные вызовы, но явный цикл даёт прогресс и корректный порядок
 * `SyncKey` (MS-ASCMD 2.2.1.4).
 */
class UserFoldersViewModel(
    private val mailRepo: MailRepository,
    private val accountRepo: AccountRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserFoldersUiState())
    val uiState: StateFlow<UserFoldersUiState> = _uiState.asStateFlow()

    private val _events = Channel<UserFoldersEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        observeAccountAndFolders()
    }

    /**
     * Реактивно отслеживает активный аккаунт и его папки. `collectLatest` гарантирует, что при
     * смене аккаунта внутренняя подписка на Room-поток отменяется и пересоздаётся — без утечек и
     * без наложения данных от разных аккаунтов. Фильтрация (пользовательские типы 1 и 12) и
     * сортировка по имени выполняются здесь, чтобы UI получал готовый список.
     */
    private fun observeAccountAndFolders() {
        viewModelScope.launch {
            accountRepo.activeAccount
                .map { it?.id ?: 0L }
                .distinctUntilChanged()
                .collectLatest { id ->
                    _uiState.update {
                        it.copy(
                            accountId = id,
                            selectedIds = emptySet(),
                            isInitialLoadDone = false,
                            folders = emptyList()
                        )
                    }
                    if (id <= 0L) {
                        _uiState.update { it.copy(isInitialLoadDone = true) }
                        return@collectLatest
                    }
                    launch { maybeAutoSync(id) }
                    mailRepo.getFolders(id).collect { all ->
                        val userFolders = all
                            .filter { it.type in USER_FOLDER_TYPES }
                            .sortedBy { it.displayName }
                        _uiState.update { s ->
                            val existingIds = userFolders.mapTo(HashSet()) { it.id }
                            // Сбрасываем устаревшие id из выделения (папка могла исчезнуть после sync).
                            val cleanedSelection =
                                if (s.selectedIds.isEmpty() || s.selectedIds.all { it in existingIds }) s.selectedIds
                                else s.selectedIds.intersect(existingIds)
                            s.copy(
                                folders = userFolders,
                                selectedIds = cleanedSelection,
                                isInitialLoadDone = s.isInitialLoadDone || userFolders.isNotEmpty()
                            )
                        }
                    }
                }
        }
    }

    /** Тихая авто-синхронизация при первом открытии, если локальных папок нет. */
    private suspend fun maybeAutoSync(id: Long) {
        delay(500)
        val s = _uiState.value
        if (s.accountId == id && !s.isInitialLoadDone && s.folders.isEmpty() && !s.isSyncing) {
            _uiState.update { it.copy(isInitialLoadDone = true) }
            runSync(id, announce = false)
        } else {
            _uiState.update { if (it.accountId == id) it.copy(isInitialLoadDone = true) else it }
        }
    }

    private suspend fun runSync(id: Long, announce: Boolean) {
        _uiState.update { it.copy(isSyncing = true) }
        try {
            when (val result = withContext(ioDispatcher) { mailRepo.syncFolders(id) }) {
                is EasResult.Success -> if (announce) sendEvent(UserFoldersEvent.Synced)
                is EasResult.Error -> if (announce) sendEvent(UserFoldersEvent.Error(result.message))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("UserFoldersViewModel", "Folder sync failed", e)
        } finally {
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    // === Синхронизация ===

    fun syncFolders() {
        val id = _uiState.value.accountId
        if (id <= 0L) return
        viewModelScope.launch { runSync(id, announce = true) }
    }

    // === Выделение ===

    fun toggleSelection(id: String) {
        _uiState.update {
            val selected = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            it.copy(selectedIds = selected)
        }
    }

    fun setSelection(ids: Set<String>) {
        _uiState.update { it.copy(selectedIds = ids) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    /** Выделяет все папки или снимает выделение, если уже все выбраны. */
    fun toggleSelectAll() {
        _uiState.update {
            val allIds = it.folders.mapTo(LinkedHashSet()) { f -> f.id }
            val newSelection = if (it.selectedIds.size == allIds.size && allIds.isNotEmpty()) emptySet() else allIds
            it.copy(selectedIds = newSelection)
        }
    }

    // === Создание ===

    /**
     * Создаёт новую папку. Защищено от double-tap флагом [UserFoldersUiState.isCreatingFolder].
     * UI закрывает диалог по завершении попытки (успех или ошибка) и показывает тост по событиям.
     */
    fun createFolder(name: String) {
        if (_uiState.value.isCreatingFolder) return
        val id = _uiState.value.accountId
        val trimmed = name.trim()
        if (id <= 0L || trimmed.isBlank()) return
        _uiState.update { it.copy(isCreatingFolder = true) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { mailRepo.createFolder(id, trimmed) }
            _uiState.update { it.copy(isCreatingFolder = false) }
            when (result) {
                is EasResult.Success -> sendEvent(UserFoldersEvent.FolderCreated)
                is EasResult.Error -> sendEvent(UserFoldersEvent.Error(result.message))
            }
        }
    }

    // === Переименование ===

    fun renameFolder(folderId: String, newName: String) {
        val id = _uiState.value.accountId
        val trimmed = newName.trim()
        if (id <= 0L || trimmed.isBlank()) return
        viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { mailRepo.renameFolder(id, folderId, trimmed) }) {
                is EasResult.Success -> sendEvent(UserFoldersEvent.FolderRenamed)
                is EasResult.Error -> sendEvent(UserFoldersEvent.Error(result.message))
            }
        }
    }

    // === Удаление ===

    /** Удаление одной папки (из контекстного меню). Звук удаления играет UI на месте нажатия. */
    fun deleteFolder(folderId: String) {
        val id = _uiState.value.accountId
        if (id <= 0L) return
        viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { mailRepo.deleteFolder(id, folderId) }) {
                is EasResult.Success -> sendEvent(UserFoldersEvent.FolderDeleted)
                is EasResult.Error -> sendEvent(UserFoldersEvent.Error(result.message))
            }
        }
    }

    /**
     * Пакетное удаление выбранных папок с прогрессом. Снимает выделение сразу, затем удаляет по
     * одной (последовательно — корректный порядок SyncKey) и публикует прогресс в состоянии.
     * По завершении эмитит число успешно удалённых и (при наличии) первую ошибку.
     */
    fun deleteSelectedFolders() {
        val ids = _uiState.value.selectedIds.toList()
        val id = _uiState.value.accountId
        if (ids.isEmpty()) {
            clearSelection()
            return
        }
        if (id <= 0L) return
        _uiState.update { it.copy(selectedIds = emptySet(), batchDeleteProgress = 0 to ids.size) }
        viewModelScope.launch {
            var deleted = 0
            var processed = 0
            var failedMsg: String? = null
            try {
                for (folderId in ids) {
                    when (val res = withContext(ioDispatcher) { mailRepo.deleteFolder(id, folderId) }) {
                        is EasResult.Success -> deleted++
                        is EasResult.Error -> if (failedMsg == null) failedMsg = res.message
                    }
                    processed++
                    _uiState.update { it.copy(batchDeleteProgress = processed to ids.size) }
                }
            } finally {
                _uiState.update { it.copy(batchDeleteProgress = null) }
            }
            if (deleted > 0) sendEvent(UserFoldersEvent.FoldersDeleted(deleted))
            failedMsg?.let { sendEvent(UserFoldersEvent.Error(it)) }
        }
    }

    private fun sendEvent(event: UserFoldersEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        /** Пользовательские папки: EAS type 1 (generic) и 12 (user-created mail). */
        private val USER_FOLDER_TYPES = listOf(1, FolderType.USER_CREATED)

        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return UserFoldersViewModel(
                        RepositoryProvider.getMailRepository(application),
                        RepositoryProvider.getAccountRepository(application)
                    ) as T
                }
            }
    }
}

package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.NoteEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.NoteRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Неизменяемое состояние экрана заметок.
 *
 * Живёт в [NotesViewModel] и переживает поворот экрана. Раньше флаг загрузки хранился в
 * `dataLoaded by rememberSaveable(accountId)` и при повороте/переключении аккаунта мог
 * приводить к лишней авто-синхронизации — теперь единый источник правды в VM.
 */
data class NotesUiState(
    val accountId: Long = 0L,
    val notes: List<NoteEntity> = emptyList(),
    val deletedNotes: List<NoteEntity> = emptyList(),
    val deletedCount: Int = 0,
    val selectedTab: Int = 0,            // 0 = активные, 1 = корзина
    val selectedIds: Set<String> = emptySet(),
    val query: String = "",
    val isSyncing: Boolean = false,
    val isCreating: Boolean = false,     // защита от double-tap при создании/редактировании
    val isInitialLoadDone: Boolean = false
)

/**
 * Одноразовые события (side-effects), которые происходят ровно один раз и не являются частью
 * состояния: тосты и автоскролл. Локализация выполняется в UI — ViewModel остаётся независимой
 * от языка/ресурсов и эмитит семантические события с числовыми параметрами.
 *
 * Прим.: операции с прогресс-баром (окончательное удаление, восстановление, очистка корзины)
 * оркеструются [com.dedovmosol.iwomail.ui.components.DeletionController] в UI и получают результат
 * напрямую из suspend-обёрток VM, поэтому их тосты не идут через этот канал.
 */
sealed interface NotesEvent {
    data class Synced(val count: Int) : NotesEvent
    data class MovedToTrash(val count: Int) : NotesEvent
    data object NoteCreated : NotesEvent
    data object NoteUpdated : NotesEvent
    data object ScrollToTop : NotesEvent
    data class Error(val message: String) : NotesEvent
}

/**
 * ViewModel экрана заметок (MVVM-слой).
 *
 * Держит состояние списка/корзины/выделения/поиска и инкапсулирует бизнес-логику (синхронизация,
 * CRUD, мягкое/окончательное удаление, восстановление) в одном месте (DRY/SRP). Зависимости —
 * через конструктор (DIP), что делает VM юнит-тестируемой без Robolectric: [NoteRepository] и
 * [AccountRepository] мокаются, [ioDispatcher] подменяется тестовым.
 *
 * Протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются — это слой представления.
 */
class NotesViewModel(
    private val noteRepo: NoteRepository,
    private val accountRepo: AccountRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotesUiState())
    val uiState: StateFlow<NotesUiState> = _uiState.asStateFlow()

    private val _events = Channel<NotesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Троттлинг авто-синхронизации корзины при переключении на вкладку "Удалённые" (как в исходном UI).
    private var lastDeletedSyncMs = 0L

    init {
        observeAccountAndNotes()
    }

    /**
     * Реактивно отслеживает активный аккаунт и его заметки. `collectLatest` гарантирует, что при
     * смене аккаунта внутренняя подписка на Room-потоки отменяется и пересоздаётся — без утечек и
     * без наложения данных от разных аккаунтов.
     */
    private fun observeAccountAndNotes() {
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
                            notes = emptyList(),
                            deletedNotes = emptyList(),
                            deletedCount = 0
                        )
                    }
                    if (id <= 0L) {
                        _uiState.update { it.copy(isInitialLoadDone = true) }
                        return@collectLatest
                    }
                    lastDeletedSyncMs = 0L
                    launch { maybeAutoSync(id) }
                    combine(
                        noteRepo.getNotes(id),
                        noteRepo.getDeletedNotes(id),
                        noteRepo.getDeletedNotesCount(id)
                    ) { active, deleted, deletedCount -> Triple(active, deleted, deletedCount) }
                        .collect { (active, deleted, deletedCount) ->
                            _uiState.update { s ->
                                // Если корзина опустела, а мы на её вкладке — возвращаемся на активные
                                // и сбрасываем выделение (как в исходном LaunchedEffect(selectedTab)).
                                val returnToActive = deleted.isEmpty() && s.selectedTab == 1
                                s.copy(
                                    notes = active,
                                    deletedNotes = deleted,
                                    deletedCount = deletedCount,
                                    selectedTab = if (returnToActive) 0 else s.selectedTab,
                                    selectedIds = if (returnToActive) emptySet() else s.selectedIds,
                                    isInitialLoadDone = s.isInitialLoadDone || active.isNotEmpty()
                                )
                            }
                        }
                }
        }
    }

    /** Тихая авто-синхронизация при первом открытии, если локальных заметок нет. */
    private suspend fun maybeAutoSync(id: Long) {
        delay(500)
        val s = _uiState.value
        if (s.accountId == id && !s.isInitialLoadDone && s.notes.isEmpty() && !s.isSyncing) {
            _uiState.update { it.copy(isInitialLoadDone = true) }
            runSync(id, skipRecentDeleteCheck = false, announce = false)
        } else {
            _uiState.update { if (it.accountId == id) it.copy(isInitialLoadDone = true) else it }
        }
    }

    private suspend fun runSync(id: Long, skipRecentDeleteCheck: Boolean, announce: Boolean) {
        _uiState.update { it.copy(isSyncing = true) }
        try {
            when (val result = withContext(ioDispatcher) { noteRepo.syncNotes(id, skipRecentDeleteCheck) }) {
                is EasResult.Success -> if (announce) sendEvent(NotesEvent.Synced(result.data))
                is EasResult.Error -> if (announce) sendEvent(NotesEvent.Error(result.message))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("NotesViewModel", "Sync failed", e)
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

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(selectedTab = tab, selectedIds = emptySet()) }
        if (tab == 1) {
            val s = _uiState.value
            val now = System.currentTimeMillis()
            if (s.accountId > 0 && !s.isSyncing && now - lastDeletedSyncMs > 30_000L) {
                lastDeletedSyncMs = now
                viewModelScope.launch { runSync(s.accountId, skipRecentDeleteCheck = true, announce = false) }
            }
        }
    }

    fun toggleSelection(id: String) {
        _uiState.update {
            val selected = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            it.copy(selectedIds = selected)
        }
    }

    fun addToSelection(id: String) {
        _uiState.update { it.copy(selectedIds = it.selectedIds + id) }
    }

    fun setSelection(ids: Set<String>) {
        _uiState.update { it.copy(selectedIds = ids) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    // === Синхронизация ===

    fun syncNotes() {
        val id = _uiState.value.accountId
        if (id <= 0L) return
        viewModelScope.launch { runSync(id, skipRecentDeleteCheck = false, announce = true) }
    }

    // === Создание / редактирование ===

    /**
     * Создаёт или обновляет заметку. [existing] == null → создание. Защищено от double-tap флагом
     * [NotesUiState.isCreating]. UI закрывает диалог и показывает тост по событиям из канала.
     */
    fun saveNote(existing: NoteEntity?, subject: String, body: String) {
        if (_uiState.value.isCreating) return
        val id = _uiState.value.accountId
        if (existing == null && id <= 0L) return
        _uiState.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                if (existing != null) noteRepo.updateNote(existing, subject, body)
                else noteRepo.createNote(id, subject, body)
            }
            _uiState.update { it.copy(isCreating = false) }
            when (result) {
                is EasResult.Success -> {
                    sendEvent(if (existing != null) NotesEvent.NoteUpdated else NotesEvent.NoteCreated)
                    if (existing == null) sendEvent(NotesEvent.ScrollToTop)
                }
                is EasResult.Error -> sendEvent(NotesEvent.Error(result.message))
            }
        }
    }

    // === Мягкое удаление (в корзину) ===

    fun deleteNoteToTrash(note: NoteEntity) {
        viewModelScope.launch {
            when (val result = withContext(ioDispatcher) { noteRepo.deleteNote(note) }) {
                is EasResult.Success -> sendEvent(NotesEvent.MovedToTrash(1))
                is EasResult.Error -> sendEvent(NotesEvent.Error(result.message))
            }
        }
    }

    /** Мягкое удаление выбранных активных заметок. Звук удаления играет UI на месте нажатия. */
    fun deleteSelectedToTrash() {
        val ids = _uiState.value.selectedIds
        val toDelete = _uiState.value.notes.filter { it.id in ids }
        if (toDelete.isEmpty()) {
            clearSelection()
            return
        }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { noteRepo.deleteNotes(toDelete) }
            _uiState.update { it.copy(selectedIds = emptySet()) }
            when (result) {
                is EasResult.Success -> sendEvent(NotesEvent.MovedToTrash(toDelete.size))
                is EasResult.Error -> sendEvent(NotesEvent.Error(result.message))
            }
        }
    }

    // === Операции с прогресс-баром (вызываются из DeletionController в UI) ===
    // Тонкие suspend-обёртки: инкапсулируют репозиторий и IO-диспетчер, чтобы Composable не
    // обращался к NoteRepository напрямую. Выполняются на scope контроллера удаления (не в
    // viewModelScope), поэтому переживают выход с экрана — поведение сохранено 1:1.

    suspend fun deleteNotePermanently(note: NoteEntity): EasResult<Boolean> =
        withContext(ioDispatcher) { noteRepo.deleteNotePermanently(note) }

    suspend fun deleteNotesPermanently(
        notes: List<NoteEntity>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> =
        withContext(ioDispatcher) { noteRepo.deleteNotesPermanentlyWithProgress(notes, onProgress) }

    suspend fun restoreNote(note: NoteEntity): EasResult<Boolean> =
        withContext(ioDispatcher) { noteRepo.restoreNote(note) }

    suspend fun restoreNotes(
        notes: List<NoteEntity>,
        onProgress: (restored: Int, total: Int) -> Unit
    ): EasResult<Int> =
        withContext(ioDispatcher) { noteRepo.restoreNotesWithProgress(notes, onProgress) }

    suspend fun emptyTrash(
        serverIds: List<String>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> =
        withContext(ioDispatcher) { noteRepo.emptyNotesTrashWithProgress(_uiState.value.accountId, serverIds, onProgress) }

    private fun sendEvent(event: NotesEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return NotesViewModel(
                        RepositoryProvider.getNoteRepository(application),
                        RepositoryProvider.getAccountRepository(application)
                    ) as T
                }
            }
    }
}

package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.EmailEntity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Неизменяемое состояние экрана списка писем.
 *
 * Живёт в [EmailListViewModel] и переживает поворот экрана/смену конфигурации. Раньше состояние
 * (фильтры, выделение, флаг авто-синка черновиков, isRefreshing/errorMessage) хранилось в десятке
 * `remember`/`rememberSaveable` в Composable, а длительные операции запускались в
 * `rememberCoroutineScope`, который отменялся при повороте. Теперь единый источник правды в VM,
 * операции — в [viewModelScope].
 *
 * [emails] — «сырой» список из выбранного источника (обычная папка / избранное / «сегодня»).
 * Фильтрация по типу/дате выполняется в UI (presentation) — VM хранит только фильтры и данные.
 */
data class EmailListUiState(
    val accountId: Long = 0L,
    val folder: FolderEntity? = null,
    val folders: List<FolderEntity> = emptyList(),
    val emails: List<EmailEntity> = emptyList(),
    val mailFilter: MailFilter = MailFilter.ALL,
    val dateFilter: EmailDateFilter = EmailDateFilter.ALL,
    val showFilters: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * Одноразовые события (side-effects), которые происходят ровно один раз и не являются частью
 * состояния: тосты. Локализация выполняется в UI — VM остаётся независимой от языка/ресурсов и
 * эмитит семантические события с числовыми параметрами.
 *
 * Прим.: операции окончательного удаления с прогресс-баром (удаление выбранных в корзине/спаме и
 * очистка корзины) оркеструются [com.dedovmosol.iwomail.ui.components.DeletionController] в UI и
 * получают результат напрямую из suspend-обёртки VM, поэтому их тосты не идут через этот канал.
 */
sealed interface EmailListEvent {
    /** Перемещено в корзину. [count] == 0 → «ничего не удалено». */
    data class MovedToTrash(val count: Int) : EmailListEvent
    /** Удалено окончательно (черновики, без прогресс-бара). [count] == 0 → «ничего не удалено». */
    data class DeletedPermanently(val count: Int) : EmailListEvent
    data class Moved(val count: Int) : EmailListEvent
    data class Restored(val count: Int) : EmailListEvent
    data class MovedToSpam(val count: Int) : EmailListEvent
    /** Сырое сообщение об ошибке репозитория — локализуется в UI. */
    data class Error(val message: String) : EmailListEvent
}

/**
 * ViewModel экрана списка писем (MVVM-слой).
 *
 * Реактивно отслеживает активный аккаунт и его данные (письма + папки), инкапсулирует все
 * операции (синхронизация, мягкое/окончательное удаление, перемещение, спам, восстановление,
 * флаги, прочитано/непрочитано) в одном месте (DRY/SRP). Зависимости — через конструктор (DIP),
 * что делает VM юнит-тестируемой без Robolectric: [MailRepository]/[AccountRepository] мокаются,
 * [ioDispatcher] подменяется тестовым.
 *
 * Протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются — это слой представления.
 */
class EmailListViewModel(
    private val folderId: String,
    private val mailRepo: MailRepository,
    private val accountRepo: AccountRepository,
    initialFilter: MailFilter = MailFilter.ALL,
    initialDateFilter: EmailDateFilter = EmailDateFilter.ALL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    /** Спец-папки: «Избранное» (флаги) и кросс-папочный список «Сегодня». */
    val isFavorites: Boolean = folderId == "favorites"
    val isTodayAll: Boolean = folderId == "TODAY_ALL"

    private val _uiState = MutableStateFlow(
        EmailListUiState(
            mailFilter = initialFilter,
            dateFilter = initialDateFilter,
            showFilters = initialFilter != MailFilter.ALL || initialDateFilter != EmailDateFilter.ALL
        )
    )
    val uiState: StateFlow<EmailListUiState> = _uiState.asStateFlow()

    private val _events = Channel<EmailListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Авто-синхронизация черновиков выполняется один раз на открытие папки (на каждый аккаунт). */
    private var draftsSynced = false

    init {
        observeAccountAndEmails()
    }

    /**
     * Реактивно отслеживает активный аккаунт, его письма и папки. `collectLatest` гарантирует, что
     * при смене аккаунта внутренние подписки на Room-потоки отменяются и пересоздаются — без утечек
     * и без наложения данных от разных аккаунтов. Папка и список папок (для диалога переноса)
     * derive'ятся из единого реактивного источника [MailRepository.getFolders].
     */
    private fun observeAccountAndEmails() {
        viewModelScope.launch(com.dedovmosol.iwomail.util.loggingExceptionHandler("EmailListVM")) {
            accountRepo.activeAccount
                .map { it?.id ?: 0L }
                .distinctUntilChanged()
                .collectLatest { id ->
                    draftsSynced = false
                    _uiState.update {
                        it.copy(accountId = id, selectedIds = emptySet(), folder = null, folders = emptyList())
                    }

                    val emailsFlow = when {
                        isFavorites -> if (id > 0L) mailRepo.getFlaggedEmails(id) else flowOf(emptyList())
                        isTodayAll -> if (id > 0L) mailRepo.getTodayEmailsAcrossFolders(id) else flowOf(emptyList())
                        else -> mailRepo.getEmails(folderId)
                    }
                    val foldersFlow = if (id > 0L) mailRepo.getFolders(id) else flowOf(emptyList())

                    combine(emailsFlow, foldersFlow) { emails, folders -> emails to folders }
                        .collect { (emails, folders) ->
                            val folder = if (isFavorites || isTodayAll) null else folders.find { it.id == folderId }
                            _uiState.update { s ->
                                val existingIds = emails.mapTo(HashSet(emails.size)) { it.id }
                                // Сбрасываем из выделения письма, исчезнувшие из списка (после sync/move).
                                val cleanedSelection =
                                    if (s.selectedIds.isEmpty() || s.selectedIds.all { it in existingIds }) s.selectedIds
                                    else s.selectedIds.intersect(existingIds)
                                s.copy(
                                    emails = emails,
                                    folders = folders,
                                    folder = folder,
                                    selectedIds = cleanedSelection
                                )
                            }
                            maybeAutoSyncDrafts(folder)
                        }
                }
        }
    }

    /**
     * Авто-синхронизация только для папки «Черновики» и только один раз. Исходный экран делал это в
     * `LaunchedEffect` с флагом `draftsSynced by rememberSaveable`, чтобы избежать повторного sync
     * при повороте (лишняя нагрузка на сервер + потенциальная гонка с PushService → INVALID_SYNCKEY).
     */
    private fun maybeAutoSyncDrafts(folder: FolderEntity?) {
        if (draftsSynced) return
        if (folder?.type == FolderType.DRAFTS && folder.accountId > 0L) {
            draftsSynced = true
            viewModelScope.launch { runSync(folder.accountId, setError = false) }
        }
    }

    private suspend fun runSync(accountId: Long, setError: Boolean) {
        _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
        try {
            // Инкрементальный sync (forceFullSync=false): при невалидном SyncKey сервер вернёт
            // status 3/12 и репозиторий сам выполнит полный ресинк — критично для больших ящиков.
            when (val result = withContext(ioDispatcher) { mailRepo.syncEmails(accountId, folderId, forceFullSync = false) }) {
                is EasResult.Success -> {}
                is EasResult.Error -> if (setError) _uiState.update { it.copy(errorMessage = result.message) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("EmailListViewModel", "Sync failed", e)
        } finally {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    // === Синхронизация (pull-to-refresh / кнопка обновления) ===

    /** Проверку сети выполняет UI (нужен Context); сюда попадаем только при наличии сети. */
    fun refresh() {
        if (isFavorites || isTodayAll) return
        val s = _uiState.value
        if (s.isRefreshing) return
        val accountId = s.folder?.accountId ?: s.accountId
        if (accountId <= 0L) return
        viewModelScope.launch { runSync(accountId, setError = true) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // === Фильтры ===

    fun setMailFilter(filter: MailFilter) {
        _uiState.update { it.copy(mailFilter = filter) }
    }

    fun setDateFilter(filter: EmailDateFilter) {
        _uiState.update { it.copy(dateFilter = filter) }
    }

    fun toggleFilters() {
        _uiState.update { it.copy(showFilters = !it.showFilters) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(mailFilter = MailFilter.ALL, dateFilter = EmailDateFilter.ALL) }
    }

    // === Выделение ===

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

    /** Выделить все [filteredIds] или снять выделение, если уже выбраны ровно они. */
    fun selectAll(filteredIds: List<String>) {
        _uiState.update {
            val all = filteredIds.toSet()
            it.copy(selectedIds = if (it.selectedIds.size == all.size && all.isNotEmpty()) emptySet() else all)
        }
    }

    // === Операции над выбранными (fire-and-forget в viewModelScope; тосты через события) ===

    /** Мягкое удаление выбранных (в корзину). Звук удаления играет UI на месте нажатия. */
    fun deleteSelectedToTrash() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { mailRepo.moveToTrash(ids) }
                when (result) {
                    is EasResult.Success -> sendEvent(EmailListEvent.MovedToTrash(result.data))
                    is EasResult.Error -> sendEvent(EmailListEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailListEvent.Error(e.message ?: "Unknown error"))
            } finally {
                clearSelection()
            }
        }
    }

    /** Окончательное удаление выбранных черновиков с сервера (через EWS deleteDraft, без прогресса). */
    fun deleteSelectedDrafts() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) {
            clearSelection()
            return
        }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { mailRepo.deleteDrafts(ids) }
                when (result) {
                    is EasResult.Success -> sendEvent(EmailListEvent.DeletedPermanently(result.data))
                    is EasResult.Error -> sendEvent(EmailListEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailListEvent.Error(e.message ?: "Unknown error"))
            } finally {
                clearSelection()
            }
        }
    }

    fun markSelectedAsRead(read: Boolean) {
        val ids = _uiState.value.selectedIds.toList()
        clearSelection()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                when (val result = withContext(ioDispatcher) { mailRepo.markAsReadBatch(ids, read) }) {
                    is EasResult.Success -> {}
                    is EasResult.Error -> sendEvent(EmailListEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailListEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /** Переключает флаг (звёздочку) у всех выбранных. Ошибки отдельных писем не прерывают остальные. */
    fun starSelected() {
        val ids = _uiState.value.selectedIds.toList()
        clearSelection()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id ->
                try {
                    withContext(ioDispatcher) { mailRepo.toggleFlag(id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("EmailListViewModel", "toggleFlag failed for $id", e)
                }
            }
        }
    }

    /** Переключает флаг одного письма (звёздочка в строке списка). */
    fun toggleFlag(emailId: String) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { mailRepo.toggleFlag(emailId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EmailListViewModel", "toggleFlag failed for $emailId", e)
            }
        }
    }

    fun moveSelectedTo(targetFolderId: String) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { mailRepo.moveEmails(ids, targetFolderId) }
                when (result) {
                    is EasResult.Success -> sendEvent(EmailListEvent.Moved(result.data))
                    is EasResult.Error -> sendEvent(EmailListEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailListEvent.Error(e.message ?: "Unknown error"))
            } finally {
                clearSelection()
            }
        }
    }

    fun restoreSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { mailRepo.restoreFromTrash(ids) }
                when (result) {
                    is EasResult.Success -> sendEvent(EmailListEvent.Restored(result.data))
                    is EasResult.Error -> sendEvent(EmailListEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailListEvent.Error(e.message ?: "Unknown error"))
            } finally {
                clearSelection()
            }
        }
    }

    fun moveSelectedToSpam() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { mailRepo.moveToSpam(ids) }
                when (result) {
                    is EasResult.Success -> sendEvent(EmailListEvent.MovedToSpam(result.data))
                    is EasResult.Error -> sendEvent(EmailListEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailListEvent.Error(e.message ?: "Unknown error"))
            } finally {
                clearSelection()
            }
        }
    }

    // === Окончательное удаление с прогресс-баром (вызывается из DeletionController в UI) ===
    // Тонкая suspend-обёртка: инкапсулирует репозиторий и IO-диспетчер. Выполняется на scope
    // контроллера удаления (не в viewModelScope), поэтому переживает выход с экрана — 1:1 с исходником.
    // Используется и для выбранных (корзина/спам), и для очистки корзины (UI передаёт нужные id).
    suspend fun deleteEmailsPermanently(
        emailIds: List<String>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> =
        withContext(ioDispatcher) { mailRepo.deleteEmailsPermanentlyWithProgress(emailIds, onProgress) }

    private fun sendEvent(event: EmailListEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        fun provideFactory(
            application: Application,
            folderId: String,
            initialFilter: MailFilter,
            initialDateFilter: EmailDateFilter
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return EmailListViewModel(
                        folderId,
                        RepositoryProvider.getMailRepository(application),
                        RepositoryProvider.getAccountRepository(application),
                        initialFilter,
                        initialDateFilter
                    ) as T
                }
            }
    }
}

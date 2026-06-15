package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Неизменяемое состояние экрана поиска.
 *
 * Живёт в [SearchViewModel] и переживает поворот экрана. Раньше `allResults` хранился
 * в обычном `remember` и после поворота восстанавливался хрупкой связкой
 * `rememberSaveable(searchResultIds)` + `LaunchedEffect` с повторным запросом в БД —
 * теперь это не нужно.
 */
data class SearchUiState(
    val query: String = "",
    val dateFilter: DateFilter = DateFilter.ALL,
    val allResults: List<EmailEntity> = emptyList(),
    val isSearching: Boolean = false,
    val selectedIds: Set<String> = emptySet()
)

/**
 * Одноразовые события (side-effects), которые должны произойти ровно один раз и не являются
 * частью состояния: тосты, звук удаления, пасхалка. Локализация выполняется в UI —
 * ViewModel остаётся независимой от языка/ресурсов.
 */
sealed interface SearchEvent {
    data object PlayDeleteSound : SearchEvent
    data object MovedToTrash : SearchEvent
    data object DeletedPermanently : SearchEvent
    data class ShowError(val message: String) : SearchEvent
    data object ShowEasterEgg : SearchEvent
}

/**
 * ViewModel экрана поиска (второй экран MVVM-слоя).
 *
 * Держит состояние поиска/выделения и инкапсулирует бизнес-логику (поиск, удаление,
 * пометка прочитанным, избранное) в одном месте (DRY/SRP). Корутины — в [viewModelScope],
 * поэтому операции не отменяются при повороте.
 *
 * Зависимости приходят через конструктор (DIP) — это делает VM юнит-тестируемой без Android
 * (репозитории мокаются, [ioDispatcher] подменяется тестовым). Фабрика берёт реальные
 * реализации из [RepositoryProvider]. Протокол EAS/EWS и Exchange 2007 SP1/SP2 не затрагиваются.
 */
class SearchViewModel(
    private val mailRepo: MailRepository,
    private val accountRepo: AccountRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _events = Channel<SearchEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Eagerly: коллекция должна идти всё время жизни VM, т.к. подписчиков нет —
    // значение читается синхронно в search().
    private val activeAccount = accountRepo.activeAccount
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun clearQuery() {
        searchJob?.cancel()
        _uiState.update { it.copy(query = "", allResults = emptyList(), selectedIds = emptySet()) }
    }

    fun setDateFilter(filter: DateFilter) {
        _uiState.update { it.copy(dateFilter = filter) }
    }

    fun search() {
        val q = _uiState.value.query
        if (q.length < 2) return
        if (q.trim().equals("I Want Out", ignoreCase = true)) {
            sendEvent(SearchEvent.ShowEasterEgg)
            return
        }
        val account = activeAccount.value ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(isSearching = true, selectedIds = emptySet()) }
                val results = mailRepo.search(account.id, q)
                _uiState.update { it.copy(allResults = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SearchViewModel", "Search failed", e)
                _uiState.update { it.copy(allResults = emptyList()) }
            } finally {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
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

    // === Действия над выделенными ===

    fun deleteSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            sendEvent(SearchEvent.PlayDeleteSound)
            when (val result = withContext(ioDispatcher) { mailRepo.moveToTrash(ids) }) {
                is EasResult.Success -> {
                    sendEvent(if (result.data > 0) SearchEvent.MovedToTrash else SearchEvent.DeletedPermanently)
                    _uiState.update { state ->
                        state.copy(
                            allResults = state.allResults.filter { it.id !in ids },
                            selectedIds = emptySet()
                        )
                    }
                }
                is EasResult.Error -> {
                    sendEvent(SearchEvent.ShowError(result.message))
                    _uiState.update { it.copy(selectedIds = emptySet()) }
                }
            }
        }
    }

    fun markSelectedAsRead(read: Boolean) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _uiState.update { it.copy(selectedIds = emptySet()) }
        viewModelScope.launch {
            when (val result = mailRepo.markAsReadBatch(ids, read)) {
                is EasResult.Success -> {
                    _uiState.update { state ->
                        state.copy(allResults = state.allResults.map { e ->
                            if (e.id in ids) e.copy(read = read) else e
                        })
                    }
                }
                is EasResult.Error -> sendEvent(SearchEvent.ShowError(result.message))
            }
        }
    }

    fun starSelected() {
        val ids = _uiState.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> mailRepo.toggleFlag(id) }
            _uiState.update { state ->
                state.copy(
                    allResults = state.allResults.map { e ->
                        if (e.id in ids) e.copy(flagged = !e.flagged) else e
                    },
                    selectedIds = emptySet()
                )
            }
        }
    }

    private fun sendEvent(event: SearchEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SearchViewModel(
                        RepositoryProvider.getMailRepository(application),
                        RepositoryProvider.getAccountRepository(application)
                    ) as T
                }
            }
    }
}

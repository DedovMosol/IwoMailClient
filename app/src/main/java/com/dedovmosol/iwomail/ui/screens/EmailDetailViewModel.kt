package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.screens.emaildetail.EmailDetailActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Семантическая ошибка загрузки тела письма. VM остаётся независимой от языка/ресурсов —
 * локализация выполняется в UI (см. EmailDetailScreen). Сырые коды ошибок репозитория
 * прокидываются через [Raw] и локализуются через NotificationStrings.localizeError.
 */
sealed interface BodyLoadError {
    data object NotFound : BodyLoadError
    data object NoBodyFromServer : BodyLoadError
    data object LoadFailed : BodyLoadError
    data object Timeout : BodyLoadError
    data class Raw(val message: String) : BodyLoadError
}

/**
 * Неизменяемое состояние экрана просмотра письма (ядро MVVM-миграции).
 *
 * Живёт в [EmailDetailViewModel] и переживает поворот. Раньше письмо/вложения/папки,
 * состояние загрузки тела и inline-картинок, флаги операций хранились в `remember`/`rememberSaveable`
 * в Composable, а операции запускались в `rememberCoroutineScope`, который отменялся при повороте
 * (обрыв delete/move/restore) и приводил к повторной СЕТЕВОЙ загрузке inline-картинок на каждый поворот.
 *
 * UI-only концерны (WebView, launcher'ы, файловый I/O, парсинг iCal/задач для отображения,
 * приглашения на встречи) остаются в Composable.
 */
data class EmailDetailUiState(
    val email: EmailEntity? = null,
    val attachments: List<AttachmentEntity> = emptyList(),
    val folders: List<com.dedovmosol.iwomail.data.database.FolderEntity> = emptyList(),
    val isInTrash: Boolean = false,
    val isInSent: Boolean = false,
    val isInDrafts: Boolean = false,
    val isLoadingBody: Boolean = false,
    val bodyLoadError: BodyLoadError? = null,
    val inlineImages: Map<String, String> = emptyMap(),
    val isLoadingInlineImages: Boolean = false,
    val isMoving: Boolean = false,
    val isRestoring: Boolean = false,
    val isDeleting: Boolean = false,
    val isSendingMdn: Boolean = false
)

/**
 * Одноразовые события (тосты + навигация назад), происходящие ровно один раз и не являющиеся
 * частью состояния. Локализация — в UI, поэтому события семантические.
 */
sealed interface EmailDetailEvent {
    data object NavigateBack : EmailDetailEvent
    data object MovedToTrash : EmailDetailEvent
    data object DeletedPermanently : EmailDetailEvent
    data object Moved : EmailDetailEvent
    data object Restored : EmailDetailEvent
    data object Refreshed : EmailDetailEvent
    data object ReadReceiptSent : EmailDetailEvent
    data object DeletedOnServer : EmailDetailEvent
    data object NoBodyFromServer : EmailDetailEvent
    data class Error(val message: String) : EmailDetailEvent
}

/**
 * ViewModel экрана просмотра письма (ядро MVVM-миграции).
 *
 * Реактивно отслеживает письмо/вложения/папки (производные флаги trash/sent/drafts),
 * один раз при открытии помечает прочитанным и подгружает тело (если локально пусто),
 * реактивно грузит inline-картинки (с кэшированием в состоянии → нет перезагрузки при повороте),
 * и инкапсулирует top-level операции (обновление, мягкое удаление, перенос, восстановление,
 * прочитано/непрочитано, флаг, MDN) в [viewModelScope] — переживают поворот.
 *
 * Бизнес-логика делегируется в [EmailDetailActions] (use-case слой). Зависимости — через
 * конструктор (DIP), что делает VM юнит-тестируемой без Robolectric.
 *
 * Протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются — это слой представления.
 */
class EmailDetailViewModel(
    private val emailId: String,
    private val actions: EmailDetailActions,
    private val mailRepo: MailRepository,
    private val accountRepo: AccountRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailDetailUiState())
    val uiState: StateFlow<EmailDetailUiState> = _uiState.asStateFlow()

    private val _events = Channel<EmailDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Единые горячие источники: один upstream-запрос к Room на письмо/вложения, шарится между
    // наблюдателем состояния и загрузчиком inline-картинок (без двойных SQLite-запросов).
    private val emailFlow: StateFlow<EmailEntity?> =
        mailRepo.getEmail(emailId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val attachmentsFlow: StateFlow<List<AttachmentEntity>> =
        mailRepo.getAttachments(emailId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        observeData()
        observeInlineImages()
        openEmail()
    }

    /**
     * Реактивно собирает письмо + вложения + папки активного аккаунта в состояние.
     * `collectLatest` по id аккаунта отменяет/пересоздаёт подписку на папки при смене аккаунта.
     * Тип текущей папки (корзина/отправленные/черновики) derive'ится из единого источника папок.
     */
    private fun observeData() {
        viewModelScope.launch {
            accountRepo.activeAccount
                .map { it?.id ?: 0L }
                .distinctUntilChanged()
                .collectLatest { id ->
                    val foldersFlow = if (id > 0L) mailRepo.getFolders(id) else flowOf(emptyList())
                    combine(emailFlow, attachmentsFlow, foldersFlow) { email, attachments, folders ->
                        Triple(email, attachments, folders)
                    }.collect { (email, attachments, folders) ->
                        val currentFolder = folders.find { it.id == email?.folderId }
                        _uiState.update {
                            it.copy(
                                email = email,
                                attachments = attachments,
                                folders = folders,
                                isInTrash = currentFolder?.type == FolderType.DELETED_ITEMS,
                                isInSent = currentFolder?.type == FolderType.SENT_ITEMS,
                                isInDrafts = currentFolder?.type == FolderType.DRAFTS
                            )
                        }
                    }
                }
        }
    }

    /** Ключ для inline-картинок: пересчитываем загрузку только при реальном изменении входных данных. */
    private data class InlineKey(
        val body: String,
        val bodyType: Int,
        val serverId: String,
        val folderId: String,
        val accountId: Long,
        val attachments: List<AttachmentEntity>
    )

    /**
     * Реактивно подгружает inline-картинки. Кэш живёт в состоянии VM, поэтому при повороте
     * (Room повторно эмитит ту же запись) [distinctUntilChanged] гасит повторную сетевую загрузку —
     * исправление перф-бага исходного экрана, где `remember` сбрасывался и картинки тянулись заново.
     */
    private fun observeInlineImages() {
        viewModelScope.launch {
            combine(emailFlow, attachmentsFlow) { email, attachments ->
                email?.let { InlineKey(it.body, it.bodyType, it.serverId, it.folderId, it.accountId, attachments) }
            }.distinctUntilChanged().collectLatest { key ->
                if (key == null || key.body.isEmpty()) return@collectLatest
                _uiState.update { it.copy(isLoadingInlineImages = true) }
                try {
                    val loaded = withTimeoutOrNull(20_000L) {
                        actions.loadInlineImages(
                            emailBody = key.body,
                            bodyType = key.bodyType,
                            emailServerId = key.serverId,
                            folderId = key.folderId,
                            accountId = key.accountId,
                            attachments = key.attachments
                        )
                    } ?: emptyMap()
                    _uiState.update { it.copy(inlineImages = loaded) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("EmailDetailViewModel", "Failed to load inline images: ${e.message}")
                    _uiState.update { it.copy(inlineImages = emptyMap()) }
                } finally {
                    _uiState.update { it.copy(isLoadingInlineImages = false) }
                }
            }
        }
    }

    /**
     * Один раз при открытии: помечает прочитанным (фоном) и подгружает тело, если локально пусто.
     * Выполняется один раз на инстанс VM → при повороте повторной сетевой загрузки тела нет
     * (исходный экран полагался на guard `body.isEmpty()`; здесь корректнее — операция в VM).
     */
    private fun openEmail() {
        viewModelScope.launch {
            try {
                val current = withContext(ioDispatcher) { actions.getEmailSync(emailId) }
                if (current == null) {
                    _uiState.update { it.copy(bodyLoadError = BodyLoadError.NotFound) }
                    return@launch
                }
                if (!current.read) {
                    launch {
                        try {
                            when (val result = actions.markAsRead(emailId, true)) {
                                is EasResult.Success -> {}
                                is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("EmailDetailViewModel", "markAsRead failed on open", e)
                        }
                    }
                }
                if (current.body.isEmpty() && !_uiState.value.isLoadingBody) {
                    loadBodyInitial(current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EmailDetailViewModel", "openEmail failed for $emailId", e)
                _uiState.update { it.copy(bodyLoadError = BodyLoadError.LoadFailed) }
            }
        }
    }

    private suspend fun loadBodyInitial(current: EmailEntity) {
        _uiState.update { it.copy(isLoadingBody = true, bodyLoadError = null) }
        try {
            when (val result = withTimeoutOrNull(30_000L) { actions.loadEmailBody(emailId) }) {
                is EasResult.Success -> {
                    if (result.data.isBlank() && current.preview.isNotBlank()) {
                        val fresh = withContext(ioDispatcher) { actions.getEmailSync(emailId) }
                        if (fresh?.body.isNullOrBlank()) {
                            _uiState.update { it.copy(bodyLoadError = BodyLoadError.NoBodyFromServer) }
                        }
                    } else {
                        // Метаданные вложений обновляем фоном, не блокируя показ тела.
                        viewModelScope.launch {
                            try {
                                actions.refreshAttachmentMetadata(emailId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                is EasResult.Error -> {
                    if (result.message == "OBJECT_NOT_FOUND") {
                        // Для Sent/Drafts loadEmailBody НЕ удаляет письмо при OBJECT_NOT_FOUND.
                        val stillExists = withContext(ioDispatcher) { actions.getEmailSync(emailId) } != null
                        if (!stillExists) {
                            sendEvent(EmailDetailEvent.DeletedOnServer)
                            sendEvent(EmailDetailEvent.NavigateBack)
                        } else {
                            _uiState.update { it.copy(bodyLoadError = BodyLoadError.LoadFailed) }
                        }
                    } else {
                        _uiState.update { it.copy(bodyLoadError = BodyLoadError.Raw(result.message)) }
                    }
                }
                null -> _uiState.update { it.copy(bodyLoadError = BodyLoadError.Timeout) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(bodyLoadError = e.message?.let { m -> BodyLoadError.Raw(m) }) }
        } finally {
            _uiState.update { it.copy(isLoadingBody = false) }
        }
    }

    // === Top-level операции (в viewModelScope; тосты/навигация — через события) ===

    /** Ручное обновление: sync папки + перезагрузка тела (forceReload). */
    fun refresh() {
        if (_uiState.value.isLoadingBody) return
        val email = _uiState.value.email ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBody = true, bodyLoadError = null) }
            try {
                when (val result = actions.syncAndReloadBody(emailId, email.accountId, email.folderId)) {
                    is EasResult.Success -> {
                        val fresh = withContext(ioDispatcher) { actions.getEmailSync(emailId) }
                        if (fresh?.body.isNullOrEmpty()) {
                            _uiState.update { it.copy(bodyLoadError = BodyLoadError.NoBodyFromServer) }
                            sendEvent(EmailDetailEvent.NoBodyFromServer)
                        } else {
                            sendEvent(EmailDetailEvent.Refreshed)
                        }
                    }
                    is EasResult.Error -> {
                        _uiState.update { it.copy(bodyLoadError = BodyLoadError.Raw(result.message)) }
                        sendEvent(EmailDetailEvent.Error(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(bodyLoadError = e.message?.let { m -> BodyLoadError.Raw(m) }) }
            } finally {
                _uiState.update { it.copy(isLoadingBody = false) }
            }
        }
    }

    /** Мягкое удаление (в корзину). Звук удаления играет UI на месте нажатия. */
    fun deleteToTrash() {
        if (_uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            try {
                val result = actions.deleteEmails(listOf(emailId), isInTrash = false)
                when (result) {
                    is EasResult.Success -> {
                        val count = (result.data as? Int) ?: 0
                        sendEvent(if (count > 0) EmailDetailEvent.MovedToTrash else EmailDetailEvent.DeletedPermanently)
                        sendEvent(EmailDetailEvent.NavigateBack)
                    }
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isDeleting = false) }
            }
        }
    }

    fun move(targetFolderId: String) {
        if (_uiState.value.isMoving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMoving = true) }
            try {
                val result = actions.moveEmails(listOf(emailId), targetFolderId)
                when (result) {
                    is EasResult.Success -> {
                        sendEvent(EmailDetailEvent.Moved)
                        sendEvent(EmailDetailEvent.NavigateBack)
                    }
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isMoving = false) }
            }
        }
    }

    fun restore() {
        if (_uiState.value.isRestoring) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true) }
            try {
                val result = actions.restoreFromTrash(listOf(emailId))
                when (result) {
                    is EasResult.Success -> {
                        sendEvent(EmailDetailEvent.Restored)
                        sendEvent(EmailDetailEvent.NavigateBack)
                    }
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isRestoring = false) }
            }
        }
    }

    fun markUnread() {
        viewModelScope.launch {
            try {
                when (val result = actions.markAsRead(emailId, false)) {
                    is EasResult.Success -> {}
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun toggleFlag() {
        viewModelScope.launch {
            try {
                actions.toggleFlag(emailId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EmailDetailViewModel", "toggleFlag failed for $emailId", e)
            }
        }
    }

    fun sendMdn() {
        if (_uiState.value.isSendingMdn) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingMdn = true) }
            try {
                val result = actions.sendMdn(emailId)
                when (result) {
                    is EasResult.Success -> sendEvent(EmailDetailEvent.ReadReceiptSent)
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isSendingMdn = false) }
            }
        }
    }

    /** Пользователь отказался отправлять MDN — помечаем, чтобы не показывать диалог снова. */
    fun dismissMdn() {
        viewModelScope.launch {
            try {
                actions.markMdnSent(emailId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EmailDetailViewModel", "markMdnSent failed for $emailId", e)
            }
        }
    }

    // === Окончательное удаление с прогресс-баром (вызывается из DeletionController в UI) ===
    // Тонкая suspend-обёртка: выполняется на scope контроллера удаления (не в viewModelScope),
    // поэтому переживает выход с экрана — 1:1 с исходником и с EmailListViewModel.
    suspend fun deleteEmailPermanently(
        emailIds: List<String>,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> =
        withContext(ioDispatcher) { mailRepo.deleteEmailsPermanentlyWithProgress(emailIds, onProgress) }

    private fun sendEvent(event: EmailDetailEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        fun provideFactory(
            application: Application,
            emailId: String
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val mailRepo = RepositoryProvider.getMailRepository(application)
                    val accountRepo = RepositoryProvider.getAccountRepository(application)
                    val calendarRepo = RepositoryProvider.getCalendarRepository(application)
                    val taskRepo = RepositoryProvider.getTaskRepository(application)
                    val actions = EmailDetailActions(mailRepo, accountRepo, calendarRepo, taskRepo)
                    return EmailDetailViewModel(emailId, actions, mailRepo, accountRepo) as T
                }
            }
    }
}

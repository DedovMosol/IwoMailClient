package com.dedovmosol.iwomail.ui.screens.compose

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderType
import com.dedovmosol.iwomail.data.database.SignatureEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.ContactRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.domain.AttachmentLoader
import com.dedovmosol.iwomail.ui.screens.ComposeUtils
import com.dedovmosol.iwomail.ui.screens.isValidRecipientList
import com.dedovmosol.iwomail.ui.utils.loggingExceptionHandler
import com.dedovmosol.iwomail.util.escapeHtml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * ViewModel для экрана создания письма.
 * Следует MVVM-паттерну проекта (см. ARCHITECTURE.md §2.1, COMPOSESCREEN_MVVM_PLAN.md).
 *
 * Инварианты:
 * - Протокол EAS/EWS не затрагивается (изменения только в presentation layer)
 * - Exchange 2007 SP1/SP2 compatibility сохранена
 * - Crash-free: все mutation-функции с try/catch(CancellationException){throw}/catch(Exception)
 * - Memory-safe: бюджет вложений проверяется ДО readBytes() (CS-1, CS-2)
 *
 * @param mailRepository Репозиторий почты
 * @param accountRepository Репозиторий аккаунтов
 * @param contactRepository Репозиторий контактов
 * @param settingsRepository Репозиторий настроек
 * @param context Application context (не Activity!)
 * @param dispatcher Dispatcher для фоновых операций (тестируемость)
 * @param initialAccountId ID аккаунта для отправки (из навигации)
 * @param replyToEmailId ID письма для ответа (из навигации)
 * @param forwardEmailId ID письма для пересылки (из навигации)
 * @param editDraftId ID черновика для редактирования (из навигации)
 * @param initialTo Начальный адрес получателя (mailto intent)
 * @param initialSubject Начальная тема (mailto intent)
 * @param initialBody Начальное тело (mailto intent)
 */
class ComposeViewModel(
    private val mailRepository: MailRepository,
    private val accountRepository: AccountRepository,
    private val contactRepository: ContactRepository,
    private val settingsRepository: SettingsRepository,
    private val context: Context, // applicationContext
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Nav args
    private val initialAccountId: Long?,
    private val replyToEmailId: Long?,
    private val forwardEmailId: Long?,
    private val editDraftId: Long?,
    private val initialTo: String?,
    private val initialSubject: String?,
    private val initialBody: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private val _events = Channel<ComposeEvent>(Channel.BUFFERED)
    val events: Flow<ComposeEvent> = _events.receiveAsFlow()

    // Для дебаунса подсказок (CS-7)
    private val suggestionSearchJob = AtomicReference<Job?>(null)

    // AttachmentLoader для единой загрузки вложений (CS-15)
    private val attachmentLoader = AttachmentLoader(
        context = context,
        mailRepository = mailRepository,
        dispatcher = dispatcher
    )

    init {
        // Инициализация в init блоке (строго последовательно)
        viewModelScope.launch(loggingExceptionHandler("ComposeViewModel.init")) {
            try {
                loadAccountsAndSignatures()

                // Определяем режим на основе nav-аргументов
                when {
                    replyToEmailId != null -> loadReplyEmail(replyToEmailId)
                    forwardEmailId != null -> loadForwardEmail(forwardEmailId)
                    editDraftId != null -> loadEditDraft(editDraftId)
                    initialTo != null -> applyMailtoIntent()
                    else -> initializeNewEmail()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error(e.message ?: "Initialization failed"))
            }
        }
    }

    // ========== Public API (вызываются из UI) ==========

    /**
     * Установить поле "Кому".
     */
    fun setTo(value: String) {
        _uiState.update { state ->
            state.copy(
                to = value,
                toValid = isValidRecipientList(value),
                hasRecipients = calculateHasRecipients(value, state.cc, state.bcc)
            )
        }
    }

    /**
     * Установить поле "Копия".
     */
    fun setCc(value: String) {
        _uiState.update { state ->
            state.copy(
                cc = value,
                ccValid = isValidRecipientList(value),
                hasRecipients = calculateHasRecipients(state.to, value, state.bcc)
            )
        }
    }

    /**
     * Установить поле "Скрытая копия".
     */
    fun setBcc(value: String) {
        _uiState.update { state ->
            state.copy(
                bcc = value,
                bccValid = isValidRecipientList(value),
                hasRecipients = calculateHasRecipients(state.to, state.cc, value)
            )
        }
    }

    /**
     * Установить тему письма.
     */
    fun setSubject(value: String) {
        _uiState.update { it.copy(subject = value) }
    }

    /**
     * Установить тело письма (HTML).
     */
    fun setBody(value: String) {
        _uiState.update { it.copy(body = value) }
    }

    /**
     * Установить важность письма (0 = normal, 1 = high).
     */
    fun setImportance(value: Int) {
        _uiState.update { it.copy(importance = value) }
    }

    /**
     * Установить флаг "Запросить отчёт о прочтении".
     */
    fun setRequestReadReceipt(value: Boolean) {
        _uiState.update { it.copy(requestReadReceipt = value) }
    }

    /**
     * Установить флаг "Запросить отчёт о доставке".
     */
    fun setRequestDeliveryReceipt(value: Boolean) {
        _uiState.update { it.copy(requestDeliveryReceipt = value) }
    }

    /**
     * Добавить вложение.
     */
    fun addAttachment(uri: Uri, name: String, mimeType: String, size: Long) {
        _uiState.update { state ->
            val newItem = AttachmentItem(uri, name, mimeType, size)
            val newAttachments = state.attachments + newItem
            val newSize = state.attachmentSizeBytes + size

            state.copy(
                attachments = newAttachments,
                attachmentSizeBytes = newSize
            )
        }
    }

    /**
     * Удалить вложение.
     */
    fun removeAttachment(uri: Uri) {
        _uiState.update { state ->
            val removed = state.attachments.find { it.uri == uri }
            if (removed != null) {
                state.copy(
                    attachments = state.attachments - removed,
                    attachmentSizeBytes = state.attachmentSizeBytes - removed.size
                )
            } else {
                state
            }
        }
    }

    /**
     * Поиск подсказок для автодополнения (с дебаунсом CS-7).
     */
    fun searchSuggestions(query: String) {
        // Отменить предыдущий поиск
        suggestionSearchJob.getAndSet(null)?.cancel()

        if (query.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        val job = viewModelScope.launch(loggingExceptionHandler("searchSuggestions")) {
            try {
                kotlinx.coroutines.delay(SUGGESTION_DEBOUNCE_MS)

                _uiState.update { it.copy(isLoadingContacts = true) }

                // TODO: Реализовать поиск через репозитории
                // val local = contactRepository.searchContacts(query)
                // val history = mailRepository.searchEmailHistory(query)
                // val groups = contactRepository.searchGroups(query)

                val suggestions = emptyList<EmailSuggestion>() // placeholder

                _uiState.update {
                    it.copy(
                        suggestions = suggestions,
                        isLoadingContacts = false
                    )
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingContacts = false) }
            }
        }

        suggestionSearchJob.set(job)
    }

    /**
     * Выбрать аккаунт для отправки.
     */
    fun selectAccount(accountId: Long) {
        viewModelScope.launch(loggingExceptionHandler("selectAccount")) {
            try {
                val account = _uiState.value.accounts.find { it.id == accountId }
                if (account != null) {
                    _uiState.update { it.copy(selectedAccountId = accountId, activeAccount = account) }
                    loadSignaturesForAccount(accountId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Логируем, но не показываем пользователю
            }
        }
    }

    /**
     * Выбрать подпись.
     */
    fun selectSignature(signature: SignatureEntity?) {
        _uiState.update { it.copy(selectedSignature = signature) }
    }

    // TODO: Реализовать sendEmail, saveDraft в следующих этапах

    // ========== Private helpers ==========

    private suspend fun loadAccountsAndSignatures() {
        try {
            val accounts = accountRepository.getAllAccountsFlow().first()
            _uiState.update { state ->
                val activeAccount = when {
                    initialAccountId != null -> accounts.find { it.id == initialAccountId }
                    else -> accounts.firstOrNull()
                }
                state.copy(
                    accounts = accounts,
                    activeAccount = activeAccount,
                    selectedAccountId = activeAccount?.id
                )
            }

            // Загрузить подписи для активного аккаунта
            _uiState.value.activeAccount?.let { account ->
                loadSignaturesForAccount(account.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load accounts: ${e.message}")
        }
    }

    private suspend fun loadReplyEmail(emailId: Long) {
        try {
            _uiState.update { it.copy(isLoading = true) }

            val email = withContext(dispatcher) {
                mailRepository.getEmailSync(emailId.toString())
            } ?: run {
                _events.send(ComposeEvent.Error("Email not found"))
                _uiState.update { it.copy(isLoading = false) }
                return
            }

            // Определить папку отправки (SENT_ITEMS → reply to recipient, иначе → reply to sender)
            val folder = withContext(dispatcher) {
                mailRepository.getFolderSync(email.folderId)
            }
            val isSentFolder = folder?.type == FolderType.SENT_ITEMS

            val replyTo = if (isSentFolder) email.to else email.from

            // Загрузить вложения через AttachmentLoader
            val account = _uiState.value.activeAccount
            val easClient = account?.let { accountRepository.createEasClient(it.id) }
            val collectionId = folder?.serverId
            val emailServerId = email.serverId

            val attachmentResult = attachmentLoader.loadAttachments(
                source = AttachmentLoader.AttachmentSource.Reply,
                email = email,
                easClient = easClient,
                collectionId = collectionId,
                emailServerId = emailServerId
            )

            when (attachmentResult) {
                is AttachmentLoader.LoadResult.Success -> {
                    // Построить тело письма с inline-картинками
                    val signatureHtml = formatHtmlSignature(
                        _uiState.value.selectedSignature?.text ?: account?.signature,
                        _uiState.value.selectedSignature?.isHtml ?: false
                    )

                    val originalBody = if (email.body.looksLikeHtml()) {
                        email.body
                    } else {
                        email.body.escapeHtml().replace("\n", "<br>")
                    }

                    val replyBody = formatHtmlQuote(
                        header = if (email.body.contains("____")) "" else "---- Original Message ----",
                        from = email.from,
                        date = ComposeUtils.formatEmailDate(email.dateReceived),
                        subject = email.subject,
                        toField = email.to,
                        originalBody = replaceCidWithDataUrl(originalBody, attachmentResult.inlineImages),
                        fromLabel = "From:",
                        dateLabel = "Date:",
                        subjectLabel = "Subject:",
                        toLabel = "To:"
                    )

                    _uiState.update { state ->
                        state.copy(
                            mode = ComposeMode.Reply,
                            replyToEmail = email,
                            to = replyTo,
                            subject = if (email.subject.startsWith("Re:", ignoreCase = true)) {
                                email.subject
                            } else {
                                "Re: ${email.subject}"
                            },
                            body = "<br>$signatureHtml$replyBody",
                            attachments = attachmentResult.fileAttachments.map { att ->
                                AttachmentItem(att.uri, att.name, att.mimeType, att.size)
                            },
                            isLoading = false
                        )
                    }
                }
                is AttachmentLoader.LoadResult.Error -> {
                    _events.send(ComposeEvent.Error(attachmentResult.message))
                    _uiState.update { it.copy(isLoading = false) }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(ComposeEvent.Error(e.message ?: "Failed to load reply"))
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadForwardEmail(emailId: Long) {
        try {
            _uiState.update { it.copy(isLoading = true) }

            val email = withContext(dispatcher) {
                mailRepository.getEmailSync(emailId.toString())
            } ?: run {
                _events.send(ComposeEvent.Error("Email not found"))
                _uiState.update { it.copy(isLoading = false) }
                return
            }

            // Загрузить вложения через AttachmentLoader
            val account = _uiState.value.activeAccount
            val easClient = account?.let { accountRepository.createEasClient(it.id) }

            val folder = withContext(dispatcher) {
                mailRepository.getFolderSync(email.folderId)
            }
            val collectionId = folder?.serverId
            val emailServerId = email.serverId

            val attachmentResult = attachmentLoader.loadAttachments(
                source = AttachmentLoader.AttachmentSource.Forward,
                email = email,
                easClient = easClient,
                collectionId = collectionId,
                emailServerId = emailServerId
            )

            when (attachmentResult) {
                is AttachmentLoader.LoadResult.Success -> {
                    val signatureHtml = formatHtmlSignature(
                        _uiState.value.selectedSignature?.text ?: account?.signature,
                        _uiState.value.selectedSignature?.isHtml ?: false
                    )

                    val originalBody = if (email.body.looksLikeHtml()) {
                        email.body
                    } else {
                        email.body.escapeHtml().replace("\n", "<br>")
                    }

                    val forwardBody = formatHtmlQuote(
                        header = "---- Forwarded Message ----",
                        from = email.from,
                        date = ComposeUtils.formatEmailDate(email.dateReceived),
                        subject = email.subject,
                        toField = email.to,
                        originalBody = replaceCidWithDataUrl(originalBody, attachmentResult.inlineImages),
                        fromLabel = "From:",
                        dateLabel = "Date:",
                        subjectLabel = "Subject:",
                        toLabel = "To:"
                    )

                    _uiState.update { state ->
                        state.copy(
                            mode = ComposeMode.Forward,
                            forwardEmail = email,
                            to = "",
                            subject = if (email.subject.startsWith("Fwd:", ignoreCase = true) ||
                                         email.subject.startsWith("Fw:", ignoreCase = true)) {
                                email.subject
                            } else {
                                "Fwd: ${email.subject}"
                            },
                            body = "<br>$signatureHtml$forwardBody",
                            attachments = attachmentResult.fileAttachments.map { att ->
                                AttachmentItem(att.uri, att.name, att.mimeType, att.size)
                            },
                            isLoading = false
                        )
                    }
                }
                is AttachmentLoader.LoadResult.Error -> {
                    _events.send(ComposeEvent.Error(attachmentResult.message))
                    _uiState.update { it.copy(isLoading = false) }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(ComposeEvent.Error(e.message ?: "Failed to load forward"))
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadEditDraft(draftId: Long) {
        try {
            _uiState.update { it.copy(isLoading = true) }

            val draft = withContext(dispatcher) {
                mailRepository.getEmailSync(draftId.toString())
            } ?: run {
                _events.send(ComposeEvent.Error("Draft not found"))
                _uiState.update { it.copy(isLoading = false) }
                return
            }

            // Загрузить вложения через AttachmentLoader
            val account = _uiState.value.activeAccount
            val easClient = account?.let { accountRepository.createEasClient(it.id) }

            val folder = withContext(dispatcher) {
                mailRepository.getFolderSync(draft.folderId)
            }
            val collectionId = folder?.serverId
            val emailServerId = draft.serverId

            val attachmentResult = attachmentLoader.loadAttachments(
                source = AttachmentLoader.AttachmentSource.Draft,
                email = draft,
                easClient = easClient,
                collectionId = collectionId,
                emailServerId = emailServerId
            )

            when (attachmentResult) {
                is AttachmentLoader.LoadResult.Success -> {
                    val draftBody = if (draft.body.looksLikeHtml()) {
                        replaceCidWithDataUrl(draft.body, attachmentResult.inlineImages)
                    } else {
                        draft.body.escapeHtml().replace("\n", "<br>")
                    }

                    _uiState.update { state ->
                        state.copy(
                            mode = ComposeMode.EditDraft,
                            editingDraft = draft,
                            to = draft.to,
                            cc = draft.cc,
                            bcc = draft.bcc,
                            subject = draft.subject,
                            body = draftBody,
                            importance = draft.importance,
                            attachments = attachmentResult.fileAttachments.map { att ->
                                AttachmentItem(att.uri, att.name, att.mimeType, att.size)
                            },
                            isLoading = false
                        )
                    }
                }
                is AttachmentLoader.LoadResult.Error -> {
                    _events.send(ComposeEvent.Error(attachmentResult.message))
                    _uiState.update { it.copy(isLoading = false) }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(ComposeEvent.Error(e.message ?: "Failed to load draft"))
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun applyMailtoIntent() {
        _uiState.update { state ->
            state.copy(
                mode = ComposeMode.Mailto,
                to = initialTo ?: "",
                subject = initialSubject ?: "",
                body = initialBody ?: ""
            )
        }
    }

    private suspend fun initializeNewEmail() {
        _uiState.update { it.copy(mode = ComposeMode.New) }
    }

    private suspend fun loadSignaturesForAccount(accountId: Long) {
        try {
            // TODO: Загрузка подписей из БД (если будет реализовано)
            // val signatures = contactRepository.getSignaturesForAccount(accountId)
            // _uiState.update { it.copy(signatures = signatures) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load signatures: ${e.message}")
        }
    }

    private fun calculateHasRecipients(to: String, cc: String, bcc: String): Boolean {
        return to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank()
    }

    // Вспомогательные функции форматирования (из ComposeTextUtils.kt)

    private fun formatHtmlSignature(text: String?, isHtml: Boolean): String {
        return com.dedovmosol.iwomail.ui.screens.compose.formatHtmlSignature(text, isHtml)
    }

    private fun formatHtmlQuote(
        header: String,
        from: String,
        date: String,
        subject: String,
        toField: String,
        originalBody: String,
        fromLabel: String,
        dateLabel: String,
        subjectLabel: String,
        toLabel: String
    ): String {
        return com.dedovmosol.iwomail.ui.screens.compose.formatHtmlQuote(
            header, from, date, subject, toField, originalBody,
            fromLabel, dateLabel, subjectLabel, toLabel
        )
    }

    private fun String.looksLikeHtml(): Boolean {
        return this.contains("<html", ignoreCase = true) ||
               this.contains("<body", ignoreCase = true) ||
               this.contains("<div", ignoreCase = true) ||
               this.contains("<p", ignoreCase = true) ||
               this.contains("<br", ignoreCase = true)
    }

    companion object {
        private const val TAG = "ComposeViewModel"
        private const val SUGGESTION_DEBOUNCE_MS = 200L
    }

    /**
     * Factory для создания ComposeViewModel с зависимостями из RepositoryProvider.
     * Следует DIP (Dependency Inversion Principle).
     */
    class Factory(
        private val repositoryProvider: RepositoryProvider,
        private val context: Context,
        // Nav args
        private val initialAccountId: Long? = null,
        private val replyToEmailId: Long? = null,
        private val forwardEmailId: Long? = null,
        private val editDraftId: Long? = null,
        private val initialTo: String? = null,
        private val initialSubject: String? = null,
        private val initialBody: String? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ComposeViewModel(
                mailRepository = repositoryProvider.getMailRepository(context),
                accountRepository = repositoryProvider.getAccountRepository(context),
                contactRepository = repositoryProvider.getContactRepository(context),
                settingsRepository = RepositoryProvider.getSettingsRepository(context),
                context = context.applicationContext,
                dispatcher = Dispatchers.IO,
                initialAccountId = initialAccountId,
                replyToEmailId = replyToEmailId,
                forwardEmailId = forwardEmailId,
                editDraftId = editDraftId,
                initialTo = initialTo,
                initialSubject = initialSubject,
                initialBody = initialBody,
            ) as T
        }
    }
}

package com.dedovmosol.iwomail.ui.screens.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.SignatureEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.ContactRepository
import com.dedovmosol.iwomail.data.repository.EmailSyncService
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.domain.AttachmentLoader
import com.dedovmosol.iwomail.eas.DraftAttachmentData
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.components.AttachmentData
import com.dedovmosol.iwomail.ui.isRussianLanguage
import com.dedovmosol.iwomail.ui.components.PendingEmail
import com.dedovmosol.iwomail.ui.components.SendController
import com.dedovmosol.iwomail.ui.navigation.ShareIntentData
import com.dedovmosol.iwomail.ui.screens.MAX_TOTAL_ATTACHMENT_BYTES
import com.dedovmosol.iwomail.ui.screens.SUGGESTION_DEBOUNCE_MS
import com.dedovmosol.iwomail.ui.screens.composeAttachmentBudgetBytes
import com.dedovmosol.iwomail.ui.screens.expandGroupTokens
import com.dedovmosol.iwomail.ui.screens.extractAllEmails
import com.dedovmosol.iwomail.ui.screens.findDuplicateField
import com.dedovmosol.iwomail.ui.screens.formatEmailDate
import com.dedovmosol.iwomail.ui.screens.isValidRecipientList
import com.dedovmosol.iwomail.ui.screens.scheduleEmail
import com.dedovmosol.iwomail.util.escapeHtml
import com.dedovmosol.iwomail.util.loggingExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
 * Контракт с UI (Этап 4): перед [sendEmail]/[saveDraft] UI обязан синхронизировать
 * финальный HTML редактора через [setBody] (flush WebView — зона ответственности UI,
 * VM не держит ссылку на View: нет утечки, VM тестируема).
 *
 * @param mailRepository Репозиторий почты
 * @param accountRepository Репозиторий аккаунтов
 * @param contactRepository Репозиторий контактов
 * @param settingsRepository Репозиторий настроек (язык для локализации сообщений)
 * @param sendController Контроллер отправки с обратным отсчётом (общий с плашкой в MainActivity)
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
    private val sendController: SendController,
    private val context: Context, // applicationContext
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Nav args
    private val initialAccountId: Long?,
    private val replyToEmailId: String?,
    private val forwardEmailId: String?,
    private val editDraftId: String?,
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

    // Аккаунт на момент открытия экрана: при смене аккаунта в compose —
    // на выходе делаем его глобально активным (поведение старого ComposeScreen).
    private var openedWithAccountId: Long? = null

    // Подпись уже подставлена один раз — на смене аккаунта не возвращаем удалённую пользователем.
    private var signatureInitialized = false

    // Снимок черновика на момент открытия — для дельта-детекта при выходе (Этап 3).
    private var initialDraftTo: String? = null
    private var initialDraftCc: String? = null
    private var initialDraftSubject: String? = null
    private var initialDraftBody: String? = null
    private var initialDraftAttachments: List<AttachmentItem> = emptyList()

    init {
        // Инициализация в init блоке (строго последовательно)
        viewModelScope.launch(loggingExceptionHandler("ComposeViewModel.init")) {
            try {
                loadAccountsAndSignatures()

                // Определяем режим на основе nav-аргументов.
                // Эталон: mailto-параметры применяются при ЛЮБОМ из них (не только
                // при наличии получателя) — mailto с одной темой/телом тоже работает.
                when {
                    replyToEmailId != null -> loadReplyEmail(replyToEmailId)
                    forwardEmailId != null -> loadForwardEmail(forwardEmailId)
                    editDraftId != null -> loadEditDraft(editDraftId)
                    initialTo != null || initialSubject != null || initialBody != null ->
                        applyMailtoIntent()
                    else -> initializeNewEmail()
                }

                // Share intent: вложения из другого приложения (после режима —
                // дополняют любой сценарий, как в старом ComposeScreen)
                loadShareAttachments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error(e.message ?: initializationFailedMessage()))
            }
        }
    }

    // ========== Public API: поля письма ==========

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
     * Установить тело письма (HTML). UI вызывает после flush редактора (контракт класса).
     */
    fun setBody(value: String) {
        _uiState.update { it.copy(body = value) }
    }

    /**
     * Установить важность письма (EAS Importance: 0 = Low, 1 = Normal, 2 = High).
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

    // ========== Public API: вложения ==========

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
                    attachmentSizeBytes = (state.attachmentSizeBytes - removed.size).coerceAtLeast(0L)
                )
            } else {
                state
            }
        }
    }

    // ========== Public API: подсказки получателей (дебаунс CS-7) ==========

    /**
     * Поиск подсказок для автодополнения получателей.
     *
     * Источники (в порядке приоритета, как в старом ComposeScreen):
     * 0. Группы контактов (по имени группы, до 3)
     * 1. Локальные контакты (до 5)
     * 2. История писем (до 5)
     * — локальная часть публикуется после дебаунса [SUGGESTION_DEBOUNCE_MS];
     * 3. GAL (Exchange) — дополнительный дебаунс [GAL_DEBOUNCE_MS], до 5, итог до 10.
     *
     * Дебаунс-идиома: отмена предыдущего job + delay (стандартный паттерн вне Flow).
     * Дубликаты и собственный адрес отправителя исключаются.
     */
    fun searchSuggestions(query: String) {
        // Отменить предыдущий поиск
        suggestionSearchJob.getAndSet(null)?.cancel()

        val account = _uiState.value.activeAccount
        // Берём только последний вводимый адрес (после запятой/точки с запятой/перевода строки)
        val queryPart = extractQueryPart(query)
        val queryPartLower = queryPart.trim().lowercase()
        val queryToken = queryPart.substringBefore("@").trim().lowercase()

        if (account == null || queryToken.length < 3) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        // Если email уже полностью введён (содержит @ и домен с точкой) — не показываем подсказки
        if (queryPartLower.contains("@") && queryPartLower.substringAfter("@").contains(".")) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        val accountId = account.id
        val ownEmail = account.email.lowercase()

        val job = viewModelScope.launch(loggingExceptionHandler("searchSuggestions")) {
            try {
                // CS-7: запросы к БД идут только после паузы ввода
                delay(SUGGESTION_DEBOUNCE_MS)
                _uiState.update { it.copy(isLoadingContacts = true) }

                val suggestions = mutableListOf<EmailSuggestion>()
                val seenEmails = mutableSetOf<String>() // Для отслеживания дубликатов

                // Нормализация: извлекает email из формата "Name <email>", приводит к lowercase
                fun normalizeEmail(email: String): String =
                    extractEmailFromString(email, queryToken)?.lowercase() ?: ""

                val ownEmailNormalized = normalizeEmail(ownEmail)

                // 0. Поиск по группам контактов (по имени группы)
                withContext(dispatcher) {
                    val groups = contactRepository.getGroupsList(accountId)
                    groups.filter { it.name.lowercase().contains(queryToken) }.take(3).forEach { group ->
                        val members = contactRepository.getContactsByGroupList(accountId, group.id)
                        val emails = members.mapNotNull { c -> c.email.takeIf { it.isNotBlank() } }
                        if (emails.isNotEmpty()) {
                            suggestions.add(
                                EmailSuggestion(
                                    email = emails.joinToString(", "),
                                    name = "${group.name} (${emails.size})",
                                    source = SuggestionSource.GROUP,
                                    groupEmails = emails,
                                    groupName = group.name,
                                    groupColor = group.color
                                )
                            )
                        }
                    }
                }

                // 1. Поиск по локальным контактам (мгновенно)
                withContext(dispatcher) {
                    val contacts = contactRepository.searchForAutocomplete(accountId, queryToken, ownEmail, 5)
                    contacts.forEach { contact ->
                        val emailNormalized = normalizeEmail(contact.email)
                        // Не добавляем дубликаты и самого себя
                        if (emailNormalized.isNotBlank() && emailNormalized !in seenEmails && emailNormalized != ownEmailNormalized) {
                            seenEmails.add(emailNormalized)
                            val cleanEmail = extractEmailFromString(contact.email, queryToken) ?: return@forEach
                            suggestions.add(
                                EmailSuggestion(
                                    email = cleanEmail,
                                    name = contact.displayName,
                                    source = SuggestionSource.CONTACT
                                )
                            )
                        }
                    }
                }

                // 2. Поиск по истории писем (мгновенно)
                withContext(dispatcher) {
                    val history = mailRepository.searchEmailHistory(accountId, queryToken, ownEmail, 5)
                    history.forEach { result ->
                        val emailNormalized = normalizeEmail(result.email)
                        // Не добавляем дубликаты и самого себя
                        if (emailNormalized.isNotBlank() && emailNormalized !in seenEmails && emailNormalized != ownEmailNormalized) {
                            seenEmails.add(emailNormalized)
                            val cleanEmail = extractEmailFromString(result.email, queryToken) ?: return@forEach
                            suggestions.add(
                                EmailSuggestion(
                                    email = cleanEmail,
                                    name = result.name,
                                    source = SuggestionSource.HISTORY
                                )
                            )
                        }
                    }
                }

                // Проверяем что корутина не отменена (защита от race condition с выбором подсказки)
                yield()
                val localResults = suggestions.take(8)
                _uiState.update { it.copy(suggestions = localResults) }

                // 3. Поиск по GAL с дополнительным дебаунсом
                delay(GAL_DEBOUNCE_MS)
                yield()
                val galResult = withContext(dispatcher) {
                    contactRepository.searchGAL(accountId, queryToken)
                }
                yield()
                if (galResult is EasResult.Success) {
                    val galSuggestions = galResult.data.take(5).mapNotNull { gal ->
                        val emailNormalized = normalizeEmail(gal.email)
                        // Не добавляем дубликаты и самого себя
                        if (emailNormalized.isNotBlank() && emailNormalized !in seenEmails && emailNormalized != ownEmailNormalized) {
                            seenEmails.add(emailNormalized)
                            val cleanEmail = extractEmailFromString(gal.email, queryToken) ?: return@mapNotNull null
                            EmailSuggestion(
                                email = cleanEmail,
                                name = gal.displayName,
                                source = SuggestionSource.GAL
                            )
                        } else null
                    }
                    _uiState.update {
                        it.copy(
                            suggestions = (localResults + galSuggestions).take(10),
                            isLoadingContacts = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoadingContacts = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(suggestions = emptyList(), isLoadingContacts = false) }
            }
        }

        suggestionSearchJob.set(job)
    }

    /**
     * Применить выбранную подсказку к полю получателей (поток выпадающего списка).
     * При обнаружении дубликатов — не добавляет сразу, а сохраняет отложенное
     * добавление в [ComposeUiState.pendingDuplicateAddition] (UI покажет диалог).
     */
    fun applySuggestion(suggestion: EmailSuggestion, field: RecipientField = RecipientField.To) {
        viewModelScope.launch(loggingExceptionHandler("applySuggestion")) {
            try {
                // КРИТИЧНО: сначала отменяем фоновый поиск (GAL и т.д.),
                // иначе он может завершиться и снова показать подсказки
                suggestionSearchJob.getAndSet(null)?.cancel()
                _uiState.update { it.copy(suggestions = emptyList()) }

                val state = _uiState.value
                val fieldValue = recipientValue(state, field)

                // Проверка дубликатов: извлекаем подтверждённую часть целевого поля (без текущего ввода)
                val separators = charArrayOf(',', ';', '\n')
                val lastSepIdx = fieldValue.lastIndexOfAny(separators)
                val confirmedPart = if (lastSepIdx >= 0) fieldValue.substring(0, lastSepIdx) else ""

                val emailsToCheck = if (suggestion.source == SuggestionSource.GROUP && suggestion.groupEmails.isNotEmpty()) {
                    suggestion.groupEmails
                } else {
                    listOf(suggestion.email)
                }

                val allExisting = existingEmailsExcluding(confirmedPart, state, field)
                val dupes = emailsToCheck.filter { it.lowercase() in allExisting }

                if (dupes.isNotEmpty()) {
                    // Отложенное добавление — решение за пользователем (диалог в UI)
                    _uiState.update {
                        it.copy(
                            pendingDuplicateAddition = PendingDuplicateAddition(
                                duplicateEmail = dupes.first(),
                                duplicateFieldName = locateDuplicateField(dupes.first(), confirmedPart, state, field),
                                targetField = field,
                                emails = dupes,
                                groupName = if (suggestion.source == SuggestionSource.GROUP) suggestion.groupName else null,
                                groupEmails = if (suggestion.source == SuggestionSource.GROUP) suggestion.groupEmails else emptyList(),
                                groupColor = suggestion.groupColor,
                                replaceLastToken = true
                            )
                        )
                    }
                    return@launch
                }

                // GROUP: вставляем токен [groupName] и сохраняем маппинг
                if (suggestion.source == SuggestionSource.GROUP && suggestion.groupEmails.isNotEmpty()) {
                    val gName = suggestion.groupName
                    registerGroup(gName, suggestion.groupEmails, suggestion.groupColor)
                    setRecipientField(field, replaceLastRecipient(fieldValue, "[$gName]"))
                } else {
                    setRecipientField(field, replaceLastRecipient(fieldValue, suggestion.email))
                }

                // Увеличиваем счётчик использования контакта (best-effort, не блокируем UI)
                if (suggestion.source == SuggestionSource.CONTACT) {
                    launch(loggingExceptionHandler("incrementUseCount")) {
                        try {
                            withContext(dispatcher) {
                                contactRepository.incrementUseCountByEmail(state.activeAccount?.id ?: return@withContext, suggestion.email)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Счётчик использования — не критично
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error(e.message ?: failedToApplySuggestionMessage()))
            }
        }
    }

    // ========== Public API: получатели из контакт-пикера ==========

    /**
     * Добавить получателей из контакт-пикера (проверка дубликатов, как в старом экране).
     * Уникальные добавляются сразу; если есть дубликаты — [ComposeEvent.DuplicatesWarning]
     * (все дубликаты) или отложенное добавление (только дубликаты).
     */
    fun addRecipientsFromPicker(field: RecipientField, emails: List<String>) {
        viewModelScope.launch(loggingExceptionHandler("addRecipientsFromPicker")) {
            try {
                val state = _uiState.value
                val allExisting = extractAllEmails(state.to, state.groupMappings) +
                    extractAllEmails(state.cc, state.groupMappings) +
                    extractAllEmails(state.bcc, state.groupMappings)
                val dupes = emails.filter { it.lowercase() in allExisting }
                val unique = emails.filter { it.lowercase() !in allExisting }

                if (dupes.isNotEmpty() && unique.isEmpty()) {
                    // Все адреса — дубликаты: отложенное добавление (диалог в UI)
                    _uiState.update {
                        it.copy(
                            pendingDuplicateAddition = PendingDuplicateAddition(
                                duplicateEmail = dupes.first(),
                                duplicateFieldName = findDuplicateField(dupes.first(), state.to, state.cc, state.bcc, state.groupMappings),
                                targetField = field,
                                emails = emails,
                                replaceLastToken = false
                            )
                        )
                    }
                    return@launch
                }

                if (unique.isNotEmpty()) {
                    appendRecipients(field, unique.joinToString(", "))
                }
                if (dupes.isNotEmpty()) {
                    // Часть адресов пропущена — предупреждаем (текст локализует UI)
                    _events.send(ComposeEvent.DuplicatesWarning(dupes.size))
                    _uiState.update {
                        it.copy(
                            pendingDuplicateAddition = PendingDuplicateAddition(
                                duplicateEmail = dupes.first(),
                                duplicateFieldName = findDuplicateField(dupes.first(), state.to, state.cc, state.bcc, state.groupMappings),
                                targetField = field,
                                emails = dupes,
                                replaceLastToken = false
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error(e.message ?: failedToAddRecipientsMessage()))
            }
        }
    }

    /**
     * Добавить группы из контакт-пикера (токены [GroupName] + маппинги).
     */
    fun addGroupsFromPicker(field: RecipientField, groups: List<GroupSelection>) {
        viewModelScope.launch(loggingExceptionHandler("addGroupsFromPicker")) {
            try {
                val state = _uiState.value
                val allExisting = extractAllEmails(state.to, state.groupMappings) +
                    extractAllEmails(state.cc, state.groupMappings) +
                    extractAllEmails(state.bcc, state.groupMappings)
                val allGroupEmails = groups.flatMap { it.emails }
                val dupes = allGroupEmails.filter { it.lowercase() in allExisting }

                if (dupes.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            pendingDuplicateAddition = PendingDuplicateAddition(
                                duplicateEmail = dupes.first(),
                                duplicateFieldName = findDuplicateField(dupes.first(), state.to, state.cc, state.bcc, state.groupMappings),
                                targetField = field,
                                groups = groups,
                                replaceLastToken = false
                            )
                        )
                    }
                    return@launch
                }

                applyGroupsSelection(groups, field)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error(e.message ?: failedToAddGroupsMessage()))
            }
        }
    }

    /**
     * Подтвердить отложенное добавление дубликатов (кнопка "Добавить" диалога).
     */
    fun confirmDuplicateAddition() {
        viewModelScope.launch(loggingExceptionHandler("confirmDuplicateAddition")) {
            try {
                val pending = _uiState.value.pendingDuplicateAddition ?: return@launch
                _uiState.update { it.copy(pendingDuplicateAddition = null) }

                when {
                    // Группы из контакт-пикера
                    pending.groups.isNotEmpty() -> applyGroupsSelection(pending.groups, pending.targetField)
                    // Группа из подсказки: токен [GroupName] + маппинг
                    pending.groupName != null && pending.groupEmails.isNotEmpty() -> {
                        registerGroup(pending.groupName, pending.groupEmails, pending.groupColor)
                        val token = "[${pending.groupName}]"
                        val current = recipientValue(_uiState.value, pending.targetField)
                        if (pending.replaceLastToken) {
                            setRecipientField(pending.targetField, replaceLastRecipient(current, token))
                        } else {
                            appendRecipients(pending.targetField, token)
                        }
                    }
                    // Обычные email'ы
                    else -> {
                        val emailsStr = pending.emails.joinToString(", ")
                        if (emailsStr.isNotBlank()) {
                            appendRecipients(pending.targetField, emailsStr)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error(e.message ?: failedToConfirmAdditionMessage()))
            }
        }
    }

    /**
     * Отклонить отложенное добавление дубликатов (кнопка "Отмена" диалога).
     */
    fun dismissDuplicateAddition() {
        _uiState.update { it.copy(pendingDuplicateAddition = null) }
    }

    // ========== Public API: аккаунты и подписи ==========

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
     * Выбрать подпись (заменяет/добавляет signature-блок в теле, как в старом экране).
     */
    fun selectSignature(signature: SignatureEntity?) {
        if (signature == null) {
            _uiState.update { it.copy(selectedSignature = null) }
            return
        }
        _uiState.update { state ->
            val newSignatureHtml = formatHtmlSignature(signature.text, signature.isHtml)
            val newBody = if (state.body.contains("<div class=\"signature\">")) {
                replaceSignatureHtml(state.body, newSignatureHtml)
            } else {
                state.body + newSignatureHtml
            }
            state.copy(selectedSignature = signature, body = newBody)
        }
    }

    // ========== Public API: отправка (Этап 3.3) ==========

    /**
     * Отправить письмо.
     *
     * Последовательность (портирована из ComposeScreen.sendEmail):
     * 1. Guard повторного вызова (isSending)
     * 2. Валидация аккаунта и получателей
     * 3. Бюджет вложений ДО чтения байт (CS-1/CS-2, N-2)
     * 4. Ветка отложенной отправки (scheduleEmail) либо немедленная
     * 5. Чтение байт вложений, очистка тела, раскрытие групповых токенов
     * 6. Передача в [SendController] (отсчёт 3с + отправка + досинк Sent — живёт
     *    в процесс-долгоживущем scope, плашка на уровне MainActivity)
     * 7. [ComposeEvent.EmailSent] → UI закрывает экран (старое поведение: экран
     *    закрывается сразу, прогресс виден в общей плашке)
     *
     * Контракт: перед вызовом UI синхронизирует тело через [setBody] (flush WebView).
     */
    fun sendEmail(scheduledTime: Long? = null) {
        if (_uiState.value.isSending) return
        _uiState.update { it.copy(isSending = true) }

        viewModelScope.launch(loggingExceptionHandler("sendEmail")) {
            try {
                val state = _uiState.value
                val account = state.activeAccount
                if (account == null) {
                    _events.send(ComposeEvent.Error(accountNotFoundMessage()))
                    _uiState.update { it.copy(isSending = false) }
                    return@launch
                }

                // Валидация получателей (защита даже при обойдённом UI-гейтинге кнопки)
                when {
                    !state.toValid -> {
                        _events.send(ComposeEvent.ValidationError(ValidationField.To))
                        _uiState.update { it.copy(isSending = false) }
                        return@launch
                    }
                    !state.ccValid -> {
                        _events.send(ComposeEvent.ValidationError(ValidationField.Cc))
                        _uiState.update { it.copy(isSending = false) }
                        return@launch
                    }
                    !state.bccValid -> {
                        _events.send(ComposeEvent.ValidationError(ValidationField.Bcc))
                        _uiState.update { it.copy(isSending = false) }
                        return@launch
                    }
                    // Эталон: получатели в ЛЮБОМ из полей (to/cc/bcc). Прошлый
                    // код требовал только to — письмо с адресатом лишь в копии
                    // не отправлялось (нарушение логики).
                    !state.hasRecipients -> {
                        _events.send(ComposeEvent.ValidationError(ValidationField.NoRecipients))
                        _uiState.update { it.copy(isSending = false) }
                        return@launch
                    }
                }

                // N-2 / CS-2: проверяем суммарный бюджет (файловые вложения + inline-картинки
                // в теле) ДО чтения байт в память. Иначе крупное вложение ИЛИ тяжёлое тело с
                // inline data:URL вызовет OutOfMemoryError на readBytes/сборке MIME.
                val totalAttachmentSize = withContext(dispatcher) {
                    composeAttachmentBudgetBytes(context, state.attachments.toAttachmentInfoList(), state.body)
                }
                if (totalAttachmentSize > MAX_TOTAL_ATTACHMENT_BYTES) {
                    _events.send(ComposeEvent.AttachmentLimitExceeded(MAX_ATTACHMENT_LIMIT_MB))
                    _uiState.update { it.copy(isSending = false) }
                    return@launch
                }

                // Отложенная отправка (диалог планирования)
                if (scheduledTime != null) {
                    val sendDelay = scheduledTime - System.currentTimeMillis()
                    if (sendDelay > 0) {
                        val rawAttachments = readRawAttachmentBytes(state.attachments)
                        scheduleEmail(
                            context, account.id,
                            expandGroupTokens(state.to, state.groupMappings),
                            expandGroupTokens(state.cc, state.groupMappings),
                            expandGroupTokens(state.bcc, state.groupMappings),
                            state.subject, state.body, sendDelay,
                            state.requestReadReceipt, state.requestDeliveryReceipt,
                            state.importance,
                            rawAttachments
                        )
                        _events.send(ComposeEvent.EmailScheduled)
                        _events.send(ComposeEvent.EmailSent)
                        _uiState.update { it.copy(isSending = false) }
                        return@launch
                    }
                }

                val rawAttachments = readRawAttachmentBytes(state.attachments)
                val attachmentDataList = rawAttachments.map { (name, mimeType, bytes) ->
                    AttachmentData(name, mimeType, bytes)
                }

                val cleanBody = cleanBodyForSend(state.body)

                val pendingEmail = PendingEmail(
                    account = account,
                    to = expandGroupTokens(state.to, state.groupMappings),
                    cc = expandGroupTokens(state.cc, state.groupMappings),
                    bcc = expandGroupTokens(state.bcc, state.groupMappings),
                    subject = state.subject,
                    body = cleanBody,
                    attachments = attachmentDataList,
                    importance = state.importance,
                    requestReadReceipt = state.requestReadReceipt,
                    requestDeliveryReceipt = state.requestDeliveryReceipt,
                    draftId = editDraftId,
                    forwardSourceFolderId = state.forwardSourceFolderServerId,
                    forwardSourceItemId = state.forwardSourceEmailServerId
                )

                // CS-5: передаём applicationContext — SendController живёт в процесс-долгоживущем
                // scope и не должен удерживать Activity после закрытия экрана.
                sendController.startSend(
                    email = pendingEmail,
                    message = sendingProgressMessage(),
                    context = context,
                    mailRepo = mailRepository,
                    onSuccess = { /* звук/досинк/удаление черновика — внутри SendController */ },
                    onCancel = { _uiState.update { it.copy(isSending = false) } },
                    // Фикс зависшего isSending: без onError UI оставался заблокирован после ошибки
                    onError = { _uiState.update { it.copy(isSending = false) } }
                )

                // Экран закрывается сразу — отсчёт/отправка продолжаются в плашке MainActivity
                _events.send(ComposeEvent.EmailSent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error("${sendErrorMessage()}: ${e.message}"))
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    // ========== Public API: черновик (Этап 3.4) ==========

    /**
     * Сохранить черновик и выйти (поведение старого ComposeScreen: save = save + exit).
     *
     * Портировано 1:1, включая:
     * - бюджет вложений ДО чтения байт (CS-1/CS-2);
     * - inline data:URL → cid: + ContentId для сервера (Outlook/Word не рендерит data:URL;
     *   единственный рабочий способ на Exchange 2007 SP1);
     * - delete+create для черновиков с вложениями (EWS UpdateItem не умеет удалять вложения);
     * - защита от «воскрешения» (registerDeletedEmail) и верификация новой записи.
     */
    fun saveDraft() {
        if (_uiState.value.isSavingDraft) return
        _uiState.update { it.copy(isSavingDraft = true) }

        viewModelScope.launch(loggingExceptionHandler("saveDraft")) {
            try {
                val state = _uiState.value
                val account = state.activeAccount
                if (account == null) {
                    _events.send(ComposeEvent.Error(accountNotFoundMessage()))
                    return@launch
                }

                // CS-1 / CS-2: та же защита от OOM, что и при отправке
                val budgetBytes = withContext(dispatcher) {
                    composeAttachmentBudgetBytes(context, state.attachments.toAttachmentInfoList(), state.body)
                }
                if (budgetBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
                    _events.send(ComposeEvent.AttachmentLimitExceeded(MAX_ATTACHMENT_LIMIT_MB))
                    return@launch
                }

                val rawAttachments = readRawAttachmentBytes(state.attachments)
                val fileDraftAttachments = rawAttachments.map { (name, mimeType, bytes) ->
                    DraftAttachmentData(name = name, mimeType = mimeType, data = bytes)
                }

                // Извлекаем inline-картинки из body: data: URL → cid: ссылки.
                // На сервер отправляем cleanBody (с cid:) + вложения через CreateAttachment.
                // Локально храним body (с data: URL) — для отображения в приложении.
                val (cleanBody, inlineDraftAttachments) = extractInlineAttachments(state.body)

                val allServerAttachments = fileDraftAttachments + inlineDraftAttachments
                val normalizedTo = normalizeRecipients(state.to)
                val normalizedCc = normalizeRecipients(state.cc)
                val normalizedBcc = normalizeRecipients(state.bcc)

                val currentDraftId = editDraftId
                val success = if (currentDraftId != null && currentDraftId != "synced" &&
                    !currentDraftId.startsWith("local_draft_")
                ) {
                    saveExistingDraft(
                        draftId = currentDraftId,
                        account = account,
                        to = normalizedTo,
                        cc = normalizedCc,
                        bcc = normalizedBcc,
                        subject = state.subject,
                        serverBody = cleanBody,
                        localBody = state.body,
                        allServerAttachments = allServerAttachments
                    )
                } else {
                    // Новый черновик
                    val serverId = withContext(dispatcher) {
                        mailRepository.saveDraft(
                            accountId = account.id,
                            to = normalizedTo,
                            cc = normalizedCc,
                            bcc = normalizedBcc,
                            subject = state.subject,
                            serverBody = cleanBody,
                            localBody = state.body,
                            fromEmail = account.email,
                            fromName = account.displayName,
                            hasAttachments = allServerAttachments.isNotEmpty(),
                            attachmentFiles = allServerAttachments
                        )
                    }
                    serverId != null
                }

                if (success) {
                    _events.send(ComposeEvent.DraftSaved)
                    _events.send(ComposeEvent.NavigateBack)
                } else {
                    _events.send(ComposeEvent.Error(draftSaveErrorMessage()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.Error("${draftSaveErrorMessage()}: ${e.message}"))
            } finally {
                _uiState.update { it.copy(isSavingDraft = false) }
            }
        }
    }

    // ========== Public API: навигация/выход (дельта-детект) ==========

    /**
     * Обработка кнопки "Назад": если есть несохранённые изменения — показать диалог
     * (UI рисует из [ComposeUiState.showDiscardDialog]), иначе выйти.
     * Контракт: перед вызовом UI синхронизирует тело через [setBody] (flush WebView).
     */
    fun handleBackPress() {
        viewModelScope.launch(loggingExceptionHandler("handleBackPress")) {
            try {
                val state = _uiState.value
                if (editDraftId != null) {
                    val hasDraftChanges = state.to != initialDraftTo ||
                        state.cc != initialDraftCc ||
                        state.subject != initialDraftSubject ||
                        state.body != initialDraftBody ||
                        state.attachments != initialDraftAttachments
                    if (hasDraftChanges) {
                        _uiState.update { it.copy(showDiscardDialog = true) }
                    } else {
                        navigateBackWithAccountSync()
                    }
                } else {
                    val bodyPlainText = state.body
                        .replace(SIGNATURE_DIV_REGEX, "")
                        .replace(HTML_TAG_STRIP_REGEX, "")
                        .replace("&nbsp;", " ")
                        .trim()
                    val hasContent = state.to.isNotBlank() ||
                        state.subject.isNotBlank() ||
                        bodyPlainText.isNotBlank() ||
                        state.attachments.isNotEmpty() ||
                        (state.selectedAccountId != openedWithAccountId && openedWithAccountId != null)
                    if (hasContent) {
                        _uiState.update { it.copy(showDiscardDialog = true) }
                    } else {
                        navigateBackWithAccountSync()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ошибка детекта не должна блокировать выход
                _events.send(ComposeEvent.NavigateBack)
            }
        }
    }

    /** Закрыть диалог "сохранить изменения" без действий. */
    fun dismissDiscardDialog() {
        _uiState.update { it.copy(showDiscardDialog = false) }
    }

    /** "Сохранить" из диалога: сохраняет черновик и выходит (см. [saveDraft]). */
    fun saveDraftAndExit() {
        _uiState.update { it.copy(showDiscardDialog = false) }
        saveDraft()
    }

    /** "Не сохранять" из диалога: выход без сохранения. */
    fun discardAndExit() {
        viewModelScope.launch(loggingExceptionHandler("discardAndExit")) {
            try {
                _uiState.update { it.copy(showDiscardDialog = false) }
                navigateBackWithAccountSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ComposeEvent.NavigateBack)
            }
        }
    }

    // ========== Private: загрузка начальных данных ==========

    private suspend fun loadAccountsAndSignatures() {
        try {
            val accounts = accountRepository.accounts.first()
            _uiState.update { state ->
                val activeAccount = when {
                    initialAccountId != null -> accounts.find { it.id == initialAccountId }
                    else -> accounts.find { it.isActive } ?: accounts.firstOrNull()
                }
                state.copy(
                    accounts = accounts,
                    activeAccount = activeAccount,
                    selectedAccountId = activeAccount?.id
                )
            }
            openedWithAccountId = _uiState.value.activeAccount?.id

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

    private suspend fun loadReplyEmail(emailId: String) {
        try {
            _uiState.update { it.copy(isLoading = true) }

            val email = withContext(dispatcher) {
                mailRepository.getEmailSync(emailId)
            } ?: run {
                _events.send(ComposeEvent.Error(emailNotFoundMessage()))
                _uiState.update { it.copy(isLoading = false) }
                return
            }

            // Определить папку отправки (SENT_ITEMS → reply to recipient, иначе → reply to sender)
            val folder = withContext(dispatcher) {
                mailRepository.getFolderSync(email.folderId)
            }
            val isSentFolder = folder?.type == FolderType.SENT_ITEMS

            // normalizeRecipients() очищает формат "Name" <email> до чистого email (эталон)
            val replyTo = normalizeRecipients(if (isSentFolder) email.to else email.from)

            // Клиент для скачивания вложений создаётся от аккаунта ПИСЬМА, а не активного
            // аккаунта (мультиаккаунт: письмо может принадлежать другому аккаунту) — поведение эталона.
            val emailAccount = withContext(dispatcher) {
                accountRepository.getAccount(email.accountId)
            }
            val easClient = emailAccount?.let {
                withContext(dispatcher) { accountRepository.createEasClient(it.id) }
            }
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
                    // Построить тело письма с inline-картинками.
                    // Подпись — от аккаунта-отправителя (эталон: активный аккаунт).
                    val signatureHtml = formatHtmlSignature(
                        _uiState.value.selectedSignature?.text ?: _uiState.value.activeAccount?.signature,
                        _uiState.value.selectedSignature?.isHtml ?: false
                    )

                    val originalBody = if (email.body.looksLikeHtml()) {
                        email.body
                    } else {
                        email.body.escapeHtml().replace("\n", "<br>")
                    }

                    // cid: резолвятся сначала из локальных вложений, затем — дозагрузка
                    // с сервера (эталонный fallback для писем, где вложения не скачаны)
                    val resolvedOriginalBody = resolveRemainingCids(
                        originalBody, attachmentResult.inlineImages, easClient, collectionId, emailServerId
                    )

                    val replyBody = formatHtmlQuote(
                        header = originalMessageHeader(),
                        from = email.from,
                        date = formatEmailDate(email.dateReceived),
                        subject = email.subject,
                        // Эталон: в цитате ответа строка "Кому:" не выводится
                        toField = null,
                        originalBody = resolvedOriginalBody,
                        fromLabel = quoteFromLabel(),
                        dateLabel = quoteDateLabel(),
                        subjectLabel = quoteSubjectLabel(),
                        toLabel = quoteToLabel()
                    )

                    _uiState.update { state ->
                        state.copy(
                            mode = ComposeMode.Reply,
                            replyToEmail = email,
                            to = replyTo,
                            toValid = isValidRecipientList(replyTo),
                            hasRecipients = calculateHasRecipients(replyTo, state.cc, state.bcc),
                            subject = if (email.subject.startsWith("Re:", ignoreCase = true)) {
                                email.subject
                            } else {
                                "Re: ${email.subject}"
                            },
                            body = "<br>$signatureHtml$replyBody",
                            attachments = attachmentResult.fileAttachments.map { att ->
                                AttachmentItem(att.uri, att.name, att.mimeType, att.size)
                            },
                            attachmentSizeBytes = attachmentResult.fileAttachments.sumOf { it.size },
                            isLoading = false
                        )
                    }
                }
                is AttachmentLoader.LoadResult.Error -> {
                    // Детали серверной/системной ошибки — в лог; пользователю —
                    // чистое локализованное сообщение (локализация в VM, а не в
                    // доменном лоадере — SOC).
                    android.util.Log.w(TAG, "Attachment load failed: ${attachmentResult.message}")
                    _events.send(ComposeEvent.Error(failedToLoadAttachmentsMessage()))
                    _uiState.update { it.copy(isLoading = false) }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(ComposeEvent.Error(e.message ?: failedToLoadReplyMessage()))
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadForwardEmail(emailId: String) {
        try {
            _uiState.update { it.copy(isLoading = true) }

            val email = withContext(dispatcher) {
                mailRepository.getEmailSync(emailId)
            } ?: run {
                _events.send(ComposeEvent.Error(emailNotFoundMessage()))
                _uiState.update { it.copy(isLoading = false) }
                return
            }

            // Клиент для скачивания вложений создаётся от аккаунта ПИСЬМА (эталон)
            val emailAccount = withContext(dispatcher) {
                accountRepository.getAccount(email.accountId)
            }
            val easClient = emailAccount?.let {
                withContext(dispatcher) { accountRepository.createEasClient(it.id) }
            }

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
                        _uiState.value.selectedSignature?.text ?: _uiState.value.activeAccount?.signature,
                        _uiState.value.selectedSignature?.isHtml ?: false
                    )

                    val originalBody = if (email.body.looksLikeHtml()) {
                        email.body
                    } else {
                        email.body.escapeHtml().replace("\n", "<br>")
                    }

                    // cid: резолвятся из локальных вложений + дозагрузка с сервера (эталон)
                    val resolvedOriginalBody = resolveRemainingCids(
                        originalBody, attachmentResult.inlineImages, easClient, collectionId, emailServerId
                    )

                    val forwardBody = formatHtmlQuote(
                        header = forwardedMessageHeader(),
                        from = email.from,
                        date = formatEmailDate(email.dateReceived),
                        subject = email.subject,
                        toField = email.to,
                        originalBody = resolvedOriginalBody,
                        fromLabel = quoteFromLabel(),
                        dateLabel = quoteDateLabel(),
                        subjectLabel = quoteSubjectLabel(),
                        toLabel = quoteToLabel()
                    )

                    _uiState.update { state ->
                        state.copy(
                            mode = ComposeMode.Forward,
                            forwardEmail = email,
                            to = "",
                            subject = if (email.subject.startsWith("Fwd:", ignoreCase = true) ||
                                email.subject.startsWith("Fw:", ignoreCase = true)
                            ) {
                                email.subject
                            } else {
                                "Fwd: ${email.subject}"
                            },
                            body = "<br>$signatureHtml$forwardBody",
                            attachments = attachmentResult.fileAttachments.map { att ->
                                AttachmentItem(att.uri, att.name, att.mimeType, att.size)
                            },
                            attachmentSizeBytes = attachmentResult.fileAttachments.sumOf { it.size },
                            // SmartForward: source пересылки (MS-ASCMD §2.2.1.19).
                            // Без него пересылка уходила обычным SendMail с потерей
                            // оригинальных вложений на стороне сервера.
                            forwardSourceFolderServerId = folder?.serverId,
                            forwardSourceEmailServerId = email.serverId,
                            isLoading = false
                        )
                    }
                }
                is AttachmentLoader.LoadResult.Error -> {
                    // Детали серверной/системной ошибки — в лог; пользователю —
                    // чистое локализованное сообщение (локализация в VM, а не в
                    // доменном лоадере — SOC).
                    android.util.Log.w(TAG, "Attachment load failed: ${attachmentResult.message}")
                    _events.send(ComposeEvent.Error(failedToLoadAttachmentsMessage()))
                    _uiState.update { it.copy(isLoading = false) }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(ComposeEvent.Error(e.message ?: failedToLoadForwardMessage()))
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadEditDraft(draftId: String) {
        try {
            _uiState.update { it.copy(isLoading = true) }

            val draft = withContext(dispatcher) {
                mailRepository.getEmailSync(draftId)
            } ?: run {
                _events.send(ComposeEvent.Error(draftNotFoundMessage()))
                _uiState.update { it.copy(isLoading = false) }
                return
            }

            val isServerDraft = draft.serverId.isNotBlank() &&
                !draft.serverId.startsWith("local_draft_")

            // Тело черновика: локальное либо ленивая догрузка с сервера (эталон).
            // Черновики из синхронизации часто приходят с пустым body — без этого
            // шага пользователь видел бы пустое письмо.
            var draftBody = if (draft.body.looksLikeHtml()) {
                draft.body
            } else {
                draft.body.escapeHtml().replace("\n", "<br>")
            }
            if (draft.body.isBlank() && isServerDraft) {
                val bodyResult = withContext(dispatcher) {
                    mailRepository.loadEmailBody(draftId)
                }
                if (bodyResult is EasResult.Success && bodyResult.data.isNotBlank()) {
                    draftBody = if (bodyResult.data.looksLikeHtml()) {
                        bodyResult.data
                    } else {
                        bodyResult.data.escapeHtml().replace("\n", "<br>")
                    }
                }
            }

            // Обновляем метаданные вложений с сервера перед чтением из БД (эталон).
            // Если вложение добавлено в Outlook на ПК — без этого вызова его не будет
            // в локальной БД и оно не отобразится в списке.
            if (isServerDraft) {
                withContext(dispatcher) {
                    try {
                        mailRepository.refreshAttachmentMetadata(draftId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "refreshAttachmentMetadata failed: ${e.message}")
                    }
                }
            }

            // Клиент для скачивания вложений создаётся от аккаунта ЧЕРНОВИКА, а не
            // активного аккаунта (мультиаккаунт) — поведение эталона.
            val draftAccount = withContext(dispatcher) {
                accountRepository.getAccount(draft.accountId)
            }
            val easClient = draftAccount?.let {
                withContext(dispatcher) { accountRepository.createEasClient(it.id) }
            }

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
                    // Резолв cid: сначала локальные вложения, затем серверный fallback.
                    // Для черновиков серверный ID может быть EWS ItemId (длинные, с "=") —
                    // тогда нужен fetchInlineImagesEws (эталонный роутинг, критично для
                    // черновиков, созданных в Outlook/OWA на Exchange 2007).
                    draftBody = resolveDraftCids(
                        draftBody, attachmentResult.inlineImages, easClient, collectionId, draft.serverId
                    )

                    // КРИТИЧНО (эталон): сохраняем резолвленное тело (data: URLs) обратно
                    // в БД. После резолвинга cid: → data: тело самодостаточно и не требует
                    // повторного сетевого запроса при каждом открытии черновика.
                    if (draftBody.isNotBlank() && draftBody != draft.body &&
                        !draftBody.contains("cid:", ignoreCase = true)
                    ) {
                        withContext(dispatcher) {
                            try {
                                mailRepository.updateEmailBody(draftId, draftBody)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "updateEmailBody failed: ${e.message}")
                            }
                        }
                    }

                    val normalizedTo = normalizeRecipients(draft.to)
                    val normalizedCc = normalizeRecipients(draft.cc)
                    val loadedAttachments = attachmentResult.fileAttachments.map { att ->
                        AttachmentItem(att.uri, att.name, att.mimeType, att.size)
                    }

                    _uiState.update { state ->
                        state.copy(
                            mode = ComposeMode.EditDraft,
                            editingDraft = draft,
                            to = normalizedTo,
                            cc = normalizedCc,
                            // Bcc черновиков не хранится в EAS (MS-AS_EMAIL: поле отсутствует),
                            // поле остаётся пустым — совпадает с поведением старого ComposeScreen
                            bcc = "",
                            subject = draft.subject,
                            body = draftBody,
                            importance = draft.importance,
                            attachments = loadedAttachments,
                            attachmentSizeBytes = loadedAttachments.sumOf { it.size },
                            // Пересчёт валидации и флагов для загруженных значений
                            toValid = isValidRecipientList(normalizedTo),
                            ccValid = isValidRecipientList(normalizedCc),
                            bccValid = true,
                            hasRecipients = calculateHasRecipients(normalizedTo, normalizedCc, ""),
                            isLoading = false
                        )
                    }

                    // Снимок для дельта-детекта при выходе (после применения состояния)
                    initialDraftTo = normalizedTo
                    initialDraftCc = normalizedCc
                    initialDraftSubject = draft.subject
                    initialDraftBody = draftBody
                    initialDraftAttachments = loadedAttachments
                }
                is AttachmentLoader.LoadResult.Error -> {
                    // Детали серверной/системной ошибки — в лог; пользователю —
                    // чистое локализованное сообщение (локализация в VM, а не в
                    // доменном лоадере — SOC).
                    android.util.Log.w(TAG, "Attachment load failed: ${attachmentResult.message}")
                    _events.send(ComposeEvent.Error(failedToLoadAttachmentsMessage()))
                    _uiState.update { it.copy(isLoading = false) }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.send(ComposeEvent.Error(e.message ?: failedToLoadDraftMessage()))
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Применить mailto intent: получатель/тема/тело из внешних интентов.
     *
     * Подпись: эталон строит тело как «почитаемое тело + подпись»
     * (`ComposeScreen`: body = initialBody + signature). Оба блока в init —
     * независимые корутины, поэтому порядок завершения недетерминирован:
     * - подпись УЖЕ в теле -> переносим её под новое тело (этот фикс);
     * - подпись ещё НЕ в теле -> она будет добавлена `loadSignaturesForAccount`
     *   по обычному пути (append, т.к. тело ещё без подписи).
     * В обоих вариантах результат идентичен эталону; без фикса подпись терялась.
     */
    private suspend fun applyMailtoIntent() {
        val state = _uiState.value
        val mailtoBody = initialBody ?: ""
        val signatureDiv = SIGNATURE_DIV_REGEX.find(state.body)?.value.orEmpty()
        val newBody = if (signatureDiv.isNotEmpty()) "$mailtoBody$signatureDiv" else mailtoBody
        _uiState.update { state2 ->
            state2.copy(
                mode = ComposeMode.Mailto,
                to = initialTo ?: "",
                toValid = isValidRecipientList(initialTo ?: ""),
                hasRecipients = calculateHasRecipients(initialTo ?: "", state2.cc, state2.bcc),
                subject = initialSubject ?: "",
                body = newBody
            )
        }
    }

    private suspend fun initializeNewEmail() {
        _uiState.update { it.copy(mode = ComposeMode.New) }
    }

    /**
     * Вложения из Share intent (метаданные читаем вне главного потока).
     * Эталонное поведение: вложения ДОПОЛНЯЮТ любой режим (не затирают
     * reply/forward/draft-режим), а [ShareIntentData] очищается ТОЛЬКО после
     * успешного чтения метаданных (защита от молчаливой потери при отмене:
     * очистка до точки отмены + повторный запуск эффекта = пустой список).
     */
    private suspend fun loadShareAttachments() {
        val shareUris = ShareIntentData.attachments
        if (shareUris.isEmpty()) return

        val items = withContext(dispatcher) {
            shareUris.mapNotNull { uri ->
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "file" else "file"
                            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            AttachmentItem(uri, name, mimeType, size)
                        } else null
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
        }
        // Очистка — только ПОСЛЕ suspend-точки (withContext выше — точка отмены).
        // При отмене/повторном запуске init список не теряется.
        ShareIntentData.clear()

        if (items.isNotEmpty()) {
            _uiState.update { state ->
                state.copy(
                    // Режим Share — только если письмо ещё новое; не затираем
                    // режим, установленный ранее (эталон: share дополняет всё)
                    mode = if (state.mode == ComposeMode.New) ComposeMode.Share else state.mode,
                    attachments = state.attachments + items,
                    attachmentSizeBytes = state.attachmentSizeBytes + items.sumOf { it.size }
                )
            }
        }
    }

    /**
     * Загрузка подписей аккаунта + подстановка подписи в тело (только для нового письма:
     * reply/forward/draft формируют тело сами). Портировано из LaunchedEffect(activeAccount?.id).
     */
    private suspend fun loadSignaturesForAccount(accountId: Long) {
        try {
            val signatures = withContext(dispatcher) {
                accountRepository.getSignaturesForAccount(accountId)
            }
            val newSignature = signatures.find { it.isDefault } ?: signatures.firstOrNull()

            _uiState.update { state ->
                var newBody = state.body
                // Обновляем подпись в body только для нового письма (не reply/forward/draft)
                if (replyToEmailId == null && forwardEmailId == null && editDraftId == null) {
                    val newSignatureHtml = formatHtmlSignature(newSignature?.text, newSignature?.isHtml ?: false)
                    newBody = if (newBody.contains("<div class=\"signature\">")) {
                        // Заменяем старую подпись на новую (HTML формат)
                        replaceSignatureHtml(newBody, newSignatureHtml)
                    } else if (!signatureInitialized && newSignatureHtml.isNotBlank()) {
                        // Подпись в конце только при ПЕРВОЙ инициализации нового письма.
                        // На смене аккаунта (signatureInitialized=true) не возвращаем удалённую.
                        newBody + newSignatureHtml
                    } else {
                        newBody
                    }
                }
                state.copy(signatures = signatures, selectedSignature = newSignature, body = newBody)
            }
            signatureInitialized = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load signatures: ${e.message}")
        }
    }

    // ========== Private: отправка/черновик — внутренние шаги ==========

    /**
     * Сохранение существующего черновика (ветка редактирования). Портировано из
     * ComposeScreen.saveDraft: delete+create при вложениях (EWS UpdateItem не умеет
     * удалять вложения — MS docs), защита от «воскрешения», верификация новой записи.
     */
    private suspend fun saveExistingDraft(
        draftId: String,
        account: AccountEntity,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        serverBody: String,
        localBody: String,
        allServerAttachments: List<DraftAttachmentData>
    ): Boolean {
        val email = withContext(dispatcher) { mailRepository.getEmailSync(draftId) }

        if (email == null) {
            // Запись не найдена (удалена фоном) — создаём новый черновик
            val serverId = withContext(dispatcher) {
                mailRepository.saveDraft(
                    accountId = account.id,
                    to = to, cc = cc, bcc = bcc,
                    subject = subject,
                    serverBody = serverBody,
                    localBody = localBody,
                    fromEmail = account.email,
                    fromName = account.displayName,
                    hasAttachments = allServerAttachments.isNotEmpty(),
                    attachmentFiles = allServerAttachments
                )
            }
            return serverId != null
        }

        // #3: delete+create нужен не только когда есть ТЕКУЩИЕ вложения, но и когда
        // исходный черновик ИМЕЛ вложения (email.hasAttachments), а пользователь их
        // все удалил: EWS UpdateItem/SetItemField НЕ умеет удалять вложения.
        if (allServerAttachments.isNotEmpty() || email.hasAttachments) {
            // При наличии вложений: СНАЧАЛА создаём новый, ПОТОМ удаляем старый.
            // Если создание не удалось — старый черновик остаётся (защита от потери данных).
            val oldServerId = email.serverId
            // P1 FIX: регистрируем старый черновик как «удалённый» ДО создания нового.
            // Это предотвращает восстановление старого черновика фоновой синхронизацией
            // (PushService/SyncWorker) в окне между create нового и delete старого.
            EmailSyncService.registerDeletedEmail(draftId, context)

            val newServerId = withContext(dispatcher) {
                mailRepository.saveDraft(
                    accountId = account.id,
                    to = to, cc = cc, bcc = bcc,
                    subject = subject,
                    serverBody = serverBody,
                    localBody = localBody,
                    fromEmail = account.email,
                    fromName = account.displayName,
                    hasAttachments = allServerAttachments.isNotEmpty(),
                    attachmentFiles = allServerAttachments
                )
            }
            val success = newServerId != null

            // Удаляем старый черновик ТОЛЬКО после успешного создания нового.
            // КРИТИЧНО: передаём excludeEwsItemId = newServerId,
            // чтобы deleteDraft НЕ удалил НОВЫЙ черновик при поиске по subject.
            if (success && newServerId != null) {
                withContext(dispatcher) {
                    try {
                        mailRepository.deleteDraft(account.id, oldServerId, excludeEwsItemId = newServerId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e else Unit
                    }
                }
                // ЗАЩИТА: верифицируем что новая запись существует в БД.
                // deleteDraft может случайно удалить не тот черновик или
                // sync-loop внутри deleteDraft может продвинуть syncKey
                // мимо нового Add. Если запись пропала — воссоздаём.
                val newEmailId = "${account.id}_$newServerId"
                val verified = withContext(dispatcher) { mailRepository.getEmailSync(newEmailId) }
                if (verified == null) {
                    withContext(dispatcher) {
                        try {
                            val previewText = localBody
                                .replace(HTML_TAG_STRIP_REGEX, " ")
                                .replace(WHITESPACE_COLLAPSE_REGEX, " ")
                                .trim().take(150)
                            val fallback = EmailEntity(
                                id = newEmailId,
                                accountId = account.id,
                                folderId = email.folderId,
                                serverId = newServerId,
                                from = account.email,
                                fromName = account.displayName,
                                to = to,
                                cc = cc,
                                subject = subject,
                                preview = previewText,
                                body = localBody,
                                bodyType = 2,
                                dateReceived = System.currentTimeMillis(),
                                read = true,
                                hasAttachments = allServerAttachments.isNotEmpty()
                            )
                            // P3 FIX: восстанавливаем записи вложений (иначе черновик
                            // появляется в списке, но при открытии вложений нет).
                            val attEntities = allServerAttachments.map { att ->
                                AttachmentEntity(
                                    emailId = newEmailId,
                                    fileReference = "",
                                    displayName = att.name,
                                    contentType = att.mimeType,
                                    estimatedSize = att.data.size.toLong(),
                                    isInline = att.isInline,
                                    contentId = att.contentId
                                )
                            }
                            // Атомарная вставка (единая точка записи — репозиторий)
                            mailRepository.insertDraftRecord(fallback, attEntities)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e else Unit
                        }
                    }
                }
            }
            return success
        } else {
            // Без вложений — обычный updateDraft
            val result = withContext(dispatcher) {
                mailRepository.updateDraft(
                    accountId = account.id,
                    serverId = email.serverId,
                    to = to, cc = cc, bcc = bcc,
                    subject = subject,
                    body = localBody,
                    fromEmail = account.email,
                    fromName = account.displayName
                )
            }
            return result is EasResult.Success
        }
    }

    /**
     * Читает байты вложений (общий для отправки/черновика/планирования).
     * Нечитаемые пропускает (поведение старого экрана). Бюджет уже проверен ДО вызова.
     */
    private suspend fun readRawAttachmentBytes(
        attachments: List<AttachmentItem>
    ): List<Triple<String, String, ByteArray>> = withContext(dispatcher) {
        attachments.mapNotNull { att ->
            try {
                context.contentResolver.openInputStream(att.uri)?.use { input ->
                    Triple(att.name, att.mimeType, input.readBytes())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
    }

    /**
     * Извлекает inline data:URL-картинки из тела: base64 → байты, замена на cid: ссылки.
     * @return (cleanBody с cid:, список inline-вложений с ContentId)
     */
    private fun extractInlineAttachments(body: String): Pair<String, List<DraftAttachmentData>> {
        val inlineAttachments = mutableListOf<DraftAttachmentData>()
        var cleanBody = body
        var inlineCounter = 0
        for (match in DATA_URL_REGEX.findAll(body)) {
            inlineCounter++
            val mimeType = match.groupValues[1]
            val base64Data = match.groupValues[2]
            val contentId = "img${inlineCounter}_${System.currentTimeMillis()}"
            val ext = when {
                mimeType.contains("png") -> ".png"
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> ".jpg"
                mimeType.contains("gif") -> ".gif"
                else -> ".png"
            }
            try {
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                inlineAttachments.add(
                    DraftAttachmentData(
                        name = "image_$inlineCounter$ext",
                        mimeType = mimeType,
                        data = bytes,
                        isInline = true,
                        contentId = contentId
                    )
                )
                cleanBody = cleanBody.replace(
                    "data:$mimeType;base64,$base64Data",
                    "cid:$contentId"
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to decode inline image #$inlineCounter: ${e.message}")
            }
        }
        return cleanBody to inlineAttachments
    }

    /** Очистка тела перед отправкой (портировано из ComposeScreen.sendEmail). */
    private fun cleanBodyForSend(body: String): String = body
        .replace(TRAILING_EMPTY_DIV_REGEX, "")
        .replace(TRAILING_EMPTY_P_REGEX, "")
        .replace(TRAILING_BR_REGEX, "")
        .trimEnd()
        .replace(NON_HTML_TAG_REGEX, "")

    /**
     * Резолв оставшихся `cid:` ссылок в теле письма (для reply/forward).
     * Порядок (эталон): сначала локальные вложения → затем недостающие догружаются
     * с сервера через [EasClient.fetchInlineImages]. Если тело не содержит `cid:`,
     * сетевой вызов не делается (производительность).
     *
     * @return Тело с резолвленными (где возможно) `cid:`
     */
    private suspend fun resolveRemainingCids(
        body: String,
        localInlineImages: Map<String, String>,
        easClient: EasClient?,
        collectionId: String?,
        emailServerId: String?
    ): String {
        var resolved = replaceCidWithDataUrl(body, localInlineImages)
        if (!resolved.contains("cid:", ignoreCase = true)) return resolved
        if (easClient == null || collectionId == null || emailServerId.isNullOrBlank()) {
            return resolved
        }

        // Только НЕрезолвленные cid — не качать лишнего
        val resolvedCids = localInlineImages.keys.flatMap {
            listOf(it, it.removePrefix("<").removeSuffix(">"))
        }.toSet()
        val missingCids = CID_PATTERN.findAll(resolved).map { it.groupValues[1] }.toSet()
            .filter { cid -> !resolvedCids.contains(cid) && !resolvedCids.contains("<$cid>") }

        if (missingCids.isEmpty()) return resolved

        try {
            val fetchResult = withContext(dispatcher) {
                easClient.fetchInlineImages(collectionId, emailServerId)
            }
            if (fetchResult is EasResult.Success && fetchResult.data.isNotEmpty()) {
                resolved = replaceCidWithDataUrl(resolved, fetchResult.data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetchInlineImages failed: ${e.message}")
        }
        return resolved
    }

    /**
     * Резолв `cid:` для черновиков (эталонный роутинг). Черновики могут иметь
     * серверный ID двух видов:
     * - EAS формат (короткий, без "=") → [EasClient.fetchInlineImages];
     * - EWS ItemId (длинный, с "=", черновики из Outlook/OWA, в т.ч. на
     *   Exchange 2007 SP1/SP2) → [EasClient.fetchInlineImagesEws].
     * `local_draft_*` и пустые ID не обрабатываются (локальные черновики
     * хранят вложения как вложения, а не как `cid:`).
     *
     * @return Тело с резолвленными (где возможно) `cid:`
     */
    private suspend fun resolveDraftCids(
        body: String,
        localInlineImages: Map<String, String>,
        easClient: EasClient?,
        collectionId: String?,
        draftServerId: String
    ): String {
        var resolved = replaceCidWithDataUrl(body, localInlineImages)
        if (!resolved.contains("cid:", ignoreCase = true)) return resolved
        if (easClient == null || draftServerId.isBlank() ||
            draftServerId.startsWith("local_draft_")
        ) {
            return resolved
        }

        // Эталонный роутинг: длинные EWS ItemId (с "=") — только через EWS GetItem
        val isEwsItemId = draftServerId.length > 50 && draftServerId.contains("=")

        try {
            val fetchResult = if (isEwsItemId) {
                withContext(dispatcher) { easClient.fetchInlineImagesEws(draftServerId) }
            } else if (collectionId != null) {
                withContext(dispatcher) { easClient.fetchInlineImages(collectionId, draftServerId) }
            } else {
                null
            }
            if (fetchResult is EasResult.Success && fetchResult.data.isNotEmpty()) {
                resolved = replaceCidWithDataUrl(resolved, fetchResult.data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetchInlineImages(draft) failed: ${e.message}")
        }
        return resolved
    }

    // ========== Private: получатели — внутренние шаги ==========

    private fun recipientValue(state: ComposeUiState, field: RecipientField): String = when (field) {
        RecipientField.To -> state.to
        RecipientField.Cc -> state.cc
        RecipientField.Bcc -> state.bcc
    }

    private fun setRecipientField(field: RecipientField, value: String) {
        when (field) {
            RecipientField.To -> setTo(value)
            RecipientField.Cc -> setCc(value)
            RecipientField.Bcc -> setBcc(value)
        }
    }

    /** Дописать получателей в конец поля (стиль контакт-пикера). */
    private fun appendRecipients(field: RecipientField, emailsStr: String) {
        val current = recipientValue(_uiState.value, field)
        val newValue = if (current.isBlank()) emailsStr else "${current.trimEnd(',', ' ')}, $emailsStr"
        setRecipientField(field, newValue)
    }

    /**
     * Все существующие адреса во всех полях, где целевое поле представлено только
     * подтверждённой частью [confirmedTargetPart] (без текущего частичного ввода).
     */
    private fun existingEmailsExcluding(
        confirmedTargetPart: String,
        state: ComposeUiState,
        targetField: RecipientField
    ): Set<String> {
        val toPart = if (targetField == RecipientField.To) confirmedTargetPart else state.to
        val ccPart = if (targetField == RecipientField.Cc) confirmedTargetPart else state.cc
        val bccPart = if (targetField == RecipientField.Bcc) confirmedTargetPart else state.bcc
        return extractAllEmails(toPart, state.groupMappings) +
            extractAllEmails(ccPart, state.groupMappings) +
            extractAllEmails(bccPart, state.groupMappings)
    }

    /** Определяет имя поля, где найден дубликат (для текста диалога). */
    private fun locateDuplicateField(
        duplicateEmail: String,
        confirmedTargetPart: String,
        state: ComposeUiState,
        targetField: RecipientField
    ): String {
        val emailLower = duplicateEmail.lowercase()
        val toPart = if (targetField == RecipientField.To) confirmedTargetPart else state.to
        val ccPart = if (targetField == RecipientField.Cc) confirmedTargetPart else state.cc
        // bcc: если не найдено в To/Cc — дубликат в Bcc (эталонный else-ветка)
        return when {
            emailLower in extractAllEmails(toPart, state.groupMappings) -> "To"
            emailLower in extractAllEmails(ccPart, state.groupMappings) -> "Cc"
            else -> "Bcc"
        }
    }

    /** Зарегистрировать маппинг и цвет группы (состояние, переживает поворот в VM). */
    private fun registerGroup(name: String, emails: List<String>, color: Int) {
        _uiState.update { state ->
            state.copy(
                groupMappings = state.groupMappings + (name to emails),
                groupColors = state.groupColors + (name to color)
            )
        }
    }

    /** Применить выбор групп: токены [GroupName] дописываются в целевое поле. */
    private fun applyGroupsSelection(groups: List<GroupSelection>, field: RecipientField) {
        groups.forEach { group ->
            registerGroup(group.name, group.emails, group.color)
        }
        val groupsStr = groups.joinToString(", ") { "[${it.name}]" }
        appendRecipients(field, groupsStr)
    }

    // ========== Private: навигация ==========

    /**
     * Выход с синхронизацией активного аккаунта: если пользователь сменил аккаунт
     * в compose — делаем его глобально активным (поведение старого экрана).
     */
    private suspend fun navigateBackWithAccountSync() {
        val currentAccountId = _uiState.value.selectedAccountId
        if (currentAccountId != null && openedWithAccountId != null && currentAccountId != openedWithAccountId) {
            try {
                withContext(dispatcher) {
                    accountRepository.setActiveAccount(currentAccountId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        _events.send(ComposeEvent.NavigateBack)
    }

    // ========== Private: локализация сообщений ==========

    /** Язык приложения из настроек (дефолт "ru" — совпадает с дефолтом SettingsRepository). */
    private fun isRussian(): Boolean = isRussianLanguage(settingsRepository.getLanguageSync())

    // Метки цитаты исходного письма (эталон: Strings.quoteFrom/quoteDate/quoteSubject/quoteTo,
    // захваченные в старом ComposeScreen до LaunchedEffect). Локализация обязательна —
    // это требование релиза 1.6.3.
    private fun quoteFromLabel(): String = if (isRussian()) "От" else "From"
    private fun quoteDateLabel(): String = if (isRussian()) "Дата" else "Date"
    private fun quoteSubjectLabel(): String = if (isRussian()) "Тема" else "Subject"
    private fun quoteToLabel(): String = if (isRussian()) "Кому" else "To"

    // Заголовки цитат (эталон: Strings.originalMessage / Strings.forwardedMessage).
    private fun originalMessageHeader(): String =
        if (isRussian()) "Исходное сообщение" else "Original message"

    private fun forwardedMessageHeader(): String =
        if (isRussian()) "Пересылаемое сообщение" else "Forwarded message"

    private fun accountNotFoundMessage(): String =
        if (isRussian()) "Аккаунт не найден" else "Account not found"

    private fun draftSaveErrorMessage(): String =
        if (isRussian()) "Ошибка сохранения черновика" else "Draft save error"

    private fun sendErrorMessage(): String =
        if (isRussian()) "Ошибка отправки" else "Send error"

    private fun sendingProgressMessage(): String =
        if (isRussian()) "Отправка письма..." else "Sending email..."

    // Сообщения об ошибках загрузки/инициализации. Локализация обязательна —
    // требование релиза 1.6.3 «полноценная локализация всех ошибок».
    // Эталон (старый ComposeScreen) молча проглатывал эти ошибки; здесь они
    // показываются пользователю, поэтому обязаны быть на языке интерфейса.
    private fun emailNotFoundMessage(): String =
        if (isRussian()) "Письмо не найдено" else "Email not found"

    private fun draftNotFoundMessage(): String =
        if (isRussian()) "Черновик не найден" else "Draft not found"

    private fun initializationFailedMessage(): String =
        if (isRussian()) "Ошибка инициализации" else "Initialization failed"

    private fun failedToApplySuggestionMessage(): String =
        if (isRussian()) "Не удалось применить подсказку" else "Failed to apply suggestion"

    private fun failedToAddRecipientsMessage(): String =
        if (isRussian()) "Не удалось добавить получателей" else "Failed to add recipients"

    private fun failedToAddGroupsMessage(): String =
        if (isRussian()) "Не удалось добавить группы" else "Failed to add groups"

    private fun failedToConfirmAdditionMessage(): String =
        if (isRussian()) "Не удалось подтвердить добавление" else "Failed to confirm addition"

    private fun failedToLoadReplyMessage(): String =
        if (isRussian()) "Не удалось загрузить письмо для ответа" else "Failed to load reply"

    private fun failedToLoadForwardMessage(): String =
        if (isRussian()) "Не удалось загрузить письмо для пересылки" else "Failed to load forward"

    private fun failedToLoadDraftMessage(): String =
        if (isRussian()) "Не удалось загрузить черновик" else "Failed to load draft"

    private fun failedToLoadAttachmentsMessage(): String =
        if (isRussian()) "Не удалось загрузить вложения" else "Failed to load attachments"

    // ========== Private: утилиты ==========

    private fun calculateHasRecipients(to: String, cc: String, bcc: String): Boolean {
        return to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank()
    }

    /** AttachmentItem → AttachmentInfo для единой функции бюджета (поля идентичны). */
    private fun List<AttachmentItem>.toAttachmentInfoList(): List<AttachmentInfo> =
        map { AttachmentInfo(uri = it.uri, name = it.name, size = it.size, mimeType = it.mimeType) }

    companion object {
        private const val TAG = "ComposeViewModel"

        // SUGGESTION_DEBOUNCE_MS — общая константа из ComposeScreen.kt (DRY, CS-7)

        /** Дополнительный дебаунс GAL-поиска (сетевой запрос к Exchange). */
        private const val GAL_DEBOUNCE_MS = 500L

        /** Лимит суммарного бюджета вложений в МБ (для события AttachmentLimitExceeded). */
        private val MAX_ATTACHMENT_LIMIT_MB = (MAX_TOTAL_ATTACHMENT_BYTES / (1024L * 1024L)).toInt()
    }

    /**
     * Factory для создания ComposeViewModel с зависимостями из RepositoryProvider.
     * Следует DIP (Dependency Inversion Principle).
     *
     * @param sendController ОБЯЗАТЕЛЬНО общий инстанс с плашкой отправки
     *   (обычно `LocalSendController.current`), иначе прогресс не будет виден.
     */
    class Factory(
        private val repositoryProvider: RepositoryProvider,
        private val sendController: SendController,
        private val context: Context,
        // Nav args
        private val initialAccountId: Long? = null,
        private val replyToEmailId: String? = null,
        private val forwardEmailId: String? = null,
        private val editDraftId: String? = null,
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
                sendController = sendController,
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

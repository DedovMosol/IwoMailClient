package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.AttachmentEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailDeleteSelection
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.RepositoryErrors
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.isFlagRejected
import com.dedovmosol.iwomail.domain.ReadReceipts
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.eas.XmlUtils
import com.dedovmosol.iwomail.ui.screens.emaildetail.EmailDetailActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    // Content-ID, на которые ссылается тело (cid:); null — тело ещё не разобрано (см. isShownInBody)
    val inlineContentIds: Set<String>? = null,
    val isMoving: Boolean = false,
    val isRestoring: Boolean = false,
    val isDeleting: Boolean = false,
    // Запрос отчёта о прочтении, на который ждём ответа: адрес отчёта; письмо до ответа не помечается прочитанным
    val readReceiptRequestedBy: String? = null,
    val isAnsweringReadReceipt: Boolean = false,
    // Ссылку «Отписаться» здесь показывать можно (не свои письма и не «Нежелательная почта»)
    val unsubscribeOffered: Boolean = false,
    val isUnsubscribing: Boolean = false,
    // Отписка выполнена в этом открытии письма — ссылку «Отписаться» больше не показываем
    val unsubscribed: Boolean = false,
    val isSavingEml: Boolean = false
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
    data object ReadReceiptNotDeclined : EmailDetailEvent
    data object DeletedOnServer : EmailDetailEvent
    /** Синхронизация перенесла письмо под другой id ([emailId]): открыть его вместо этого экрана. */
    data class Reopen(val emailId: String) : EmailDetailEvent
    data object NoBodyFromServer : EmailDetailEvent
    data class Error(val message: String) : EmailDetailEvent
    data object Unsubscribed : EmailDetailEvent
    /** Отписка — на странице рассылки; [afterFailure] — отписка одним запросом не удалась. */
    data class OpenUnsubscribePage(val url: String, val afterFailure: Boolean) : EmailDetailEvent
    data object EmlSaved : EmailDetailEvent
    /** Письмо не сохранилось файлом; [message] — причина с сервера (null — не записался файл). */
    data class EmlFailed(val message: String?) : EmailDetailEvent
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Отписка от рассылки (null — недоступна, например в тестах без сети). */
    private val unsubscriber: com.dedovmosol.iwomail.domain.Unsubscriber? = null,
    /** Файл .eml, выбранный пользователем (SAF): записать или убрать (null — недоступно). */
    private val documents: DocumentSink? = null,
    /**
     * Отписка и сохранение .eml доводятся до конца и после выхода с экрана: «Отписаться» и сразу «Назад» не
     * обрывает запрос или письмо на середине. null — [viewModelScope] (тесты).
     */
    private val detachedScope: kotlinx.coroutines.CoroutineScope? = null
) : ViewModel() {

    /** Файл, выбранный пользователем через системный диалог: записать письмо или убрать файл, если письма нет. */
    interface DocumentSink {
        /** false — не записалось (поставщик документа отказал). */
        suspend fun write(uri: android.net.Uri, bytes: ByteArray): Boolean
        suspend fun discard(uri: android.net.Uri)
    }

    private val workScope: kotlinx.coroutines.CoroutineScope get() = detachedScope ?: viewModelScope

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

    private val inlineRetry = MutableStateFlow(0L)

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
        viewModelScope.launch(com.dedovmosol.iwomail.util.loggingExceptionHandler("EmailDetailVM")) {
            accountRepo.activeAccount
                .map { it?.id ?: 0L }
                .distinctUntilChanged()
                .collectLatest { id ->
                    val foldersFlow = if (id > 0L) mailRepo.getFolders(id) else flowOf(emptyList())
                    combine(emailFlow, attachmentsFlow, foldersFlow) { email, attachments, folders ->
                        Triple(email, attachments, folders)
                    }.collect { (email, attachments, folders) ->
                        val currentFolder = folders.find { it.id == email?.folderId }
                        // Запись пропала или теперь о другом письме — на экране не показывать (null)
                        val shown = onEmailPresence(email, isDraft = currentFolder?.type == FolderType.DRAFTS)
                        _uiState.update {
                            it.copy(
                                email = shown,
                                attachments = if (shown != null) attachments else emptyList(),
                                folders = folders,
                                isInTrash = currentFolder?.type == FolderType.DELETED_ITEMS,
                                isInSent = currentFolder?.type == FolderType.SENT_ITEMS,
                                isInDrafts = currentFolder?.type == FolderType.DRAFTS,
                                unsubscribeOffered = com.dedovmosol.iwomail.eas.ListUnsubscribe.offeredIn(currentFolder?.type)
                            )
                        }
                    }
                }
        }
    }

    // MAIL-05: письмо, которое было на экране, пропало из БД (окончательное удаление, удаление на сервере,
    // синхронизация) — экран закрывается, а не показывает «Загрузка письма» без конца. Закрытие — один раз:
    // повторный NavigateBack снял бы со стека ещё и предыдущий экран.
    private var emailSeen = false
    private var closed = false
    /** Свои операции, после которых письма может не стать (перенос, удаление): закрывают экран сами. */
    private var localOps = 0

    /** Открытое письмо (без тела): тема, отправитель, дата — по ним видно, что запись теперь о другом письме. */
    private var opened: EmailEntity? = null
    /** Запись пропала или переписана под другое письмо: ищем то же письмо под новым id ([reopenOrClose]). */
    private var identityLost = false

    /**
     * Что показать на экране из записи [email]. Exchange 2007 после SyncKey=0 нумерует письма папки заново:
     * синхронизация переносит письмо под другой id, а под прежним id может оказаться другое письмо. И то и другое —
     * не удаление: экран открывает то же письмо по новому id. Черновик правится на устройстве (тема, время) — у него
     * только исчезновение записи.
     */
    private fun onEmailPresence(email: EmailEntity?, isDraft: Boolean): EmailEntity? {
        if (identityLost) return null
        if (email == null) {
            if (emailSeen && localOps == 0) reopenOrClose()
            return null
        }
        val seen = opened
        if (seen != null && !isDraft && localOps == 0 && !sameMessage(seen, email)) {
            reopenOrClose()
            return null
        }
        emailSeen = true
        opened = email.copy(body = "")
        return email
    }

    /** Сущности XML не в счёт: разовая починка БД ([MailRepository.syncEmails]) раскрывает их в той же записи. */
    private fun sameMessage(a: EmailEntity, b: EmailEntity): Boolean =
        kotlin.math.abs(a.dateReceived - b.dateReceived) <= SAME_MESSAGE_WINDOW_MS &&
            sameText(a.from, b.from) && sameText(a.subject, b.subject)

    private fun sameText(a: String, b: String) = a == b || XmlUtils.unescape(a) == XmlUtils.unescape(b)

    /**
     * То же письмо под другим id — открыть его ([EmailDetailEvent.Reopen]), нет — «удалено на сервере». Пока
     * синхронизация папки идёт, письмо может прийти позже в ней же: ждём её (не дольше [REOPEN_WAIT_MS]).
     */
    private fun reopenOrClose(fallback: EmailEntity? = null) {
        if (closed || identityLost) return
        identityLost = true
        val target = opened ?: fallback ?: return closeAsDeletedOnServer()
        viewModelScope.launch {
            val newId = try {
                withContext(ioDispatcher) { findMovedEmail(target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EmailDetailViewModel", "Lookup of the same email failed: ${e.message}")
                null
            }
            if (newId == null) {
                closeAsDeletedOnServer()
            } else if (!closed) {
                closed = true
                sendEvent(EmailDetailEvent.Reopen(newId))
            }
        }
    }

    private suspend fun findMovedEmail(target: EmailEntity): String? {
        repeat((REOPEN_WAIT_MS / REOPEN_POLL_MS).toInt()) {
            actions.findSameEmailId(target)?.takeIf { it.isNotBlank() }?.let { return it }
            if (!actions.isFolderSyncing(target.folderId)) return null
            kotlinx.coroutines.delay(REOPEN_POLL_MS)
        }
        return actions.findSameEmailId(target)?.takeIf { it.isNotBlank() }
    }

    private fun navigateBackOnce() {
        if (closed) return
        closed = true
        sendEvent(EmailDetailEvent.NavigateBack)
    }

    private fun closeAsDeletedOnServer() {
        if (closed) return
        sendEvent(EmailDetailEvent.DeletedOnServer)
        navigateBackOnce()
    }

    /** Операция над этим письмом: пока она идёт, исчезновение записи — её результат, а не удаление на сервере. */
    private suspend fun <T> localOp(block: suspend () -> T): T {
        localOps++
        try {
            return block()
        } finally {
            localOps--
        }
    }

    /** После своей операции (и её тоста): записи не стало — закрыть экран. */
    private fun closeIfGone() {
        if (emailSeen && emailFlow.value == null) navigateBackOnce()
    }

    /** Ключ для inline-картинок: пересчитываем при изменении данных или ручном обновлении. */
    private data class InlineKey(
        val attempt: Long,
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
            combine(emailFlow, attachmentsFlow, inlineRetry) { email, attachments, attempt ->
                email?.let { InlineKey(attempt, it.body, it.bodyType, it.serverId, it.folderId, it.accountId, attachments) }
            }.distinctUntilChanged().collectLatest { key ->
                if (key == null || key.body.isEmpty()) {
                    _uiState.update { it.copy(inlineContentIds = null, inlineImages = emptyMap()) }
                    return@collectLatest
                }
                _uiState.update { it.copy(isLoadingInlineImages = true, inlineImages = emptyMap(), inlineContentIds = null) }
                try {
                    val referenced = withContext(ioDispatcher) {
                        val display = com.dedovmosol.iwomail.util.processEmailBodyForDisplay(key.body, key.bodyType)
                        if (!display.isHtml) emptySet()
                        else com.dedovmosol.iwomail.util.referencedContentIds(
                            com.dedovmosol.iwomail.util.sanitizeEmailHtml(display.text)
                        )
                    }
                    _uiState.update { it.copy(inlineContentIds = referenced) }
                    val loaded = actions.loadInlineImages(
                        emailId = emailId,
                        accountId = key.accountId,
                        cids = referenced,
                        mimeBody = key.body.takeIf { key.bodyType == 4 },
                        attachments = key.attachments
                    )
                    _uiState.update { it.copy(inlineImages = loaded) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("EmailDetailViewModel", "Failed to load inline images: ${e.message}")
                    _uiState.update { it.copy(inlineImages = emptyMap()) }
                } catch (e: Throwable) {
                    // OOM / StackOverflow и прочие Error на «тяжёлых» письмах (большой MIME/картинки,
                    // до 20 МБ): catch(Exception) их НЕ ловит → иначе краш всего процесса при открытии.
                    // Деградируем без inline-картинок; тело письма показывается.
                    Log.e("EmailDetailViewModel", "Inline images: fatal error (likely OOM) — degrading", e)
                    _uiState.update { it.copy(inlineImages = emptyMap()) }
                } finally {
                    _uiState.update { it.copy(isLoadingInlineImages = false) }
                }
            }
        }
    }

    /**
     * Один раз при открытии: подгружает тело, если локально пусто, и помечает прочитанным.
     * Выполняется один раз на инстанс VM → при повороте повторной сетевой загрузки тела нет
     * (исходный экран полагался на guard `body.isEmpty()`; здесь корректнее — операция в VM).
     *
     * Пометка — после загрузки: запрос отчёта о прочтении известен из заголовков, пришедших с телом, а
     * Exchange отправляет отчёт сам, как только письмо прочитано. Если отчёт просят — сначала спросить.
     */
    private fun openEmail() {
        viewModelScope.launch {
            try {
                val current = withContext(ioDispatcher) { actions.getEmailSync(emailId) }
                if (current == null) {
                    _uiState.update { it.copy(bodyLoadError = BodyLoadError.NotFound) }
                    return@launch
                }
                if (current.body.isEmpty() && !_uiState.value.isLoadingBody) {
                    loadBodyInitial(current)
                } else if (current.listUnsubscribe == null && !com.dedovmosol.iwomail.eas.RssItems.isRss(current.messageClass)) {
                    // Тело загружено раньше, заголовки отписки не проверялись — проверить фоном, один раз
                    checkListUnsubscribe()
                }
                if (!current.read) markReadOrAskReceipt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EmailDetailViewModel", "openEmail failed for $emailId", e)
                _uiState.update { it.copy(bodyLoadError = BodyLoadError.LoadFailed) }
            }
        }
    }

    /** Заголовки отписки письма — фоном: ссылка «Отписаться» появится, когда они придут (через Room). */
    private fun checkListUnsubscribe() {
        viewModelScope.launch {
            try {
                actions.ensureListUnsubscribe(emailId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EmailDetailViewModel", "List-Unsubscribe check failed: ${e.message}")
            }
        }
    }

    /** Помечает прочитанным; если просят отчёт о прочтении — сначала спрашивает ([answerReadReceipt]). */
    private suspend fun markReadOrAskReceipt() {
        try {
            val email = withContext(ioDispatcher) { actions.getEmailSync(emailId) } ?: return
            if (email.read) return
            val requestedBy = withContext(ioDispatcher) { actions.pendingReadReceipt(emailId) }
            if (requestedBy != null) {
                _uiState.update { it.copy(readReceiptRequestedBy = requestedBy) }
                return
            }
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
                    if (result.message == "OBJECT_NOT_FOUND" || result.message == RepositoryErrors.ITEM_MISMATCH) {
                        // PROTO-08: Status 6 — письмо удалено на сервере ИЛИ ещё не пришло в идущей полной
                        // синхронизации папки. Различает только синхронизация: удалённое она убирает из БД,
                        // остальное возвращает. Поэтому — один проход синхронизации и повторная загрузка.
                        // ITEM_MISMATCH — под id письма на сервере другое письмо: синхронизация будет полной и
                        // перепишет записи, а экран откроет это письмо под новым id (onEmailPresence).
                        val retry = if (withContext(ioDispatcher) { actions.getEmailSync(emailId) } != null) {
                            actions.syncAndReloadBody(emailId, current.accountId, current.folderId)
                        } else null
                        val fresh = withContext(ioDispatcher) { actions.getEmailSync(emailId) }
                        when {
                            identityLost || closed -> Unit
                            fresh == null || !sameMessage(current, fresh) -> reopenOrClose(fallback = current)
                            retry is EasResult.Success && fresh.body.isBlank() ->
                                _uiState.update { it.copy(bodyLoadError = BodyLoadError.NoBodyFromServer) }
                            retry is EasResult.Success -> Unit
                            else -> _uiState.update { it.copy(bodyLoadError = BodyLoadError.LoadFailed) }
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
                            inlineRetry.update { it + 1 }
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
                val result = localOp { actions.moveToTrash(listOf(emailId)) }
                when (result) {
                    is EasResult.Success -> {
                        val count = (result.data as? Int) ?: 0
                        sendEvent(if (count > 0) EmailDetailEvent.MovedToTrash else EmailDetailEvent.DeletedPermanently)
                        navigateBackOnce()
                    }
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isDeleting = false) }
                closeIfGone()
            }
        }
    }

    fun move(targetFolderId: String) {
        if (_uiState.value.isMoving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isMoving = true) }
            try {
                val result = localOp { actions.moveEmails(listOf(emailId), targetFolderId) }
                when (result) {
                    is EasResult.Success -> {
                        sendEvent(EmailDetailEvent.Moved)
                        navigateBackOnce()
                    }
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isMoving = false) }
                closeIfGone()
            }
        }
    }

    fun restore() {
        if (_uiState.value.isRestoring) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true) }
            try {
                val result = localOp { actions.restoreFromTrash(listOf(emailId)) }
                when (result) {
                    is EasResult.Success -> {
                        sendEvent(EmailDetailEvent.Restored)
                        navigateBackOnce()
                    }
                    is EasResult.Error -> sendEvent(EmailDetailEvent.Error(result.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isRestoring = false) }
                closeIfGone()
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

    /** Флаг целиком из меню звёздочки: срок, напоминание, «выполнено», снять. */
    fun setFlag(flag: com.dedovmosol.iwomail.eas.MailFlag) {
        viewModelScope.launch {
            try {
                when (val result = actions.setFlag(emailId, flag)) {
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

    /** Звёздочка: нет связи — флаг остаётся локально без тоста; отказ сервера — тост (звезда вернулась к серверной). */
    fun toggleFlag() {
        viewModelScope.launch {
            try {
                if (actions.toggleFlag(emailId).isFlagRejected()) {
                    sendEvent(EmailDetailEvent.Error(com.dedovmosol.iwomail.data.repository.RepositoryErrors.FLAG_REJECTED))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("EmailDetailViewModel", "toggleFlag failed for $emailId", e)
            }
        }
    }

    /**
     * Ответ на запрос отчёта о прочтении: письмо помечается прочитанным, отчёт отправляет Exchange;
     * при отказе сервер сначала подавляет отчёт. Не вышло отказаться — письмо остаётся непрочитанным.
     */
    fun answerReadReceipt(send: Boolean) {
        val state = _uiState.value
        if (state.readReceiptRequestedBy == null || state.isAnsweringReadReceipt) return
        _uiState.update { it.copy(isAnsweringReadReceipt = true) }
        viewModelScope.launch {
            try {
                when (val result = actions.answerReadReceipt(emailId, send)) {
                    is EasResult.Success -> if (send) sendEvent(EmailDetailEvent.ReadReceiptSent)
                    is EasResult.Error -> sendEvent(
                        if (result.message == ReadReceipts.NOT_DECLINED) EmailDetailEvent.ReadReceiptNotDeclined
                        else EmailDetailEvent.Error(result.message)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isAnsweringReadReceipt = false, readReceiptRequestedBy = null) }
            }
        }
    }

    /** Ответ отложен (закрыли диалог): письмо остаётся непрочитанным, спросим при следующем открытии. */
    fun postponeReadReceipt() {
        if (_uiState.value.isAnsweringReadReceipt) return
        _uiState.update { it.copy(readReceiptRequestedBy = null) }
    }

    // === Окончательное удаление с прогресс-баром (вызывается из DeletionController в UI) ===
    // Тонкая suspend-обёртка: выполняется на scope контроллера удаления (не в viewModelScope),
    // поэтому переживает выход с экрана — 1:1 с исходником и с EmailListViewModel.
    /**
     * Отписаться от рассылки этого письма — после подтверждения в UI. Страница отписки (или запасной вариант после
     * неудачной отписки одним запросом) открывается в браузере событием.
     */
    fun unsubscribe() {
        val unsubscriber = unsubscriber ?: return
        val state = _uiState.value
        val email = state.email ?: return
        if (state.isUnsubscribing || state.unsubscribed) return
        val target = com.dedovmosol.iwomail.eas.ListUnsubscribe.parse(email.listUnsubscribe, email.listUnsubscribeOneClick) ?: return
        _uiState.update { it.copy(isUnsubscribing = true) }
        viewModelScope.launch {
            try {
                val work = workScope.async { unsubscriber.unsubscribe(email.accountId, target) }
                when (val result = work.await()) {
                    com.dedovmosol.iwomail.domain.UnsubscribeResult.Done -> {
                        _uiState.update { it.copy(unsubscribed = true) }
                        sendEvent(EmailDetailEvent.Unsubscribed)
                    }
                    is com.dedovmosol.iwomail.domain.UnsubscribeResult.OpenPage -> sendEvent(
                        EmailDetailEvent.OpenUnsubscribePage(
                            result.url,
                            afterFailure = target.method is com.dedovmosol.iwomail.eas.ListUnsubscribe.Method.OneClick
                        )
                    )
                    is com.dedovmosol.iwomail.domain.UnsubscribeResult.Failed ->
                        sendEvent(EmailDetailEvent.Error(result.reason ?: "Unknown error"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEvent(EmailDetailEvent.Error(e.message ?: "Unknown error"))
            } finally {
                _uiState.update { it.copy(isUnsubscribing = false) }
            }
        }
    }

    /** Сохранить письмо целиком (.eml, с вложениями — как на сервере) в файл [uri], выбранный пользователем. */
    fun saveEml(uri: android.net.Uri) {
        val documents = documents ?: return
        if (_uiState.value.isSavingEml) return
        _uiState.update { it.copy(isSavingEml = true) }
        viewModelScope.launch {
            try {
                sendEvent(workScope.async { exportEml(documents, uri) }.await())
            } finally {
                _uiState.update { it.copy(isSavingEml = false) }
            }
        }
    }

    /** Письмо — в файл; не вышло — файл, созданный выбором места, не остаётся пустым или недописанным. */
    private suspend fun exportEml(documents: DocumentSink, uri: android.net.Uri): EmailDetailEvent {
        val failure = try {
            when (val mime = withContext(ioDispatcher) { mailRepo.fetchMessageMime(emailId) }) {
                is EasResult.Success -> when {
                    mime.data.isEmpty() -> EmailDetailEvent.EmlFailed(null)
                    withContext(ioDispatcher) { documents.write(uri, mime.data) } -> return EmailDetailEvent.EmlSaved
                    else -> EmailDetailEvent.EmlFailed(null)
                }
                is EasResult.Error -> EmailDetailEvent.EmlFailed(mime.message)
            }
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + ioDispatcher) { documents.discard(uri) }
            throw e
        } catch (e: Exception) {
            EmailDetailEvent.EmlFailed(e.message)
        } catch (e: OutOfMemoryError) {
            // Письмо в десятки мегабайт на устройстве с малой памятью — не падать, а сообщить
            Log.e("EmailDetailViewModel", "EML export: out of memory", e)
            EmailDetailEvent.EmlFailed(null)
        }
        withContext(ioDispatcher) { documents.discard(uri) }
        return failure
    }

    internal suspend fun deleteEmailPermanently(
        selection: MailDeleteSelection,
        onProgress: (deleted: Int, total: Int) -> Unit
    ): EasResult<Int> {
        // Запись исчезает посреди операции — это её результат: экран закроется после неё, без «удалено на сервере»
        try {
            return localOp { withContext(ioDispatcher) { mailRepo.deleteEmailsPermanentlyWithProgress(selection, onProgress) } }
        } finally {
            closeIfGone()
        }
    }

    private fun sendEvent(event: EmailDetailEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {
        /** Та же дата, что у сверки записи с Add ([com.dedovmosol.iwomail.data.repository.EmailSyncService.sameServerItem]). */
        private const val SAME_MESSAGE_WINDOW_MS = 2_000L
        /** Сколько ждать идущую синхронизацию папки, прежде чем признать письмо удалённым. */
        private const val REOPEN_WAIT_MS = 30_000L
        private const val REOPEN_POLL_MS = 500L

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
                    return EmailDetailViewModel(
                        emailId, actions, mailRepo, accountRepo,
                        unsubscriber = com.dedovmosol.iwomail.domain.Unsubscriber.forApp(application),
                        documents = ContentResolverSink(application.contentResolver),
                        detachedScope = DETACHED_WORK
                    ) as T
                }
            }

        /** Процессная область для работы, которая не должна обрываться с закрытием экрана. */
        private val DETACHED_WORK = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.Main.immediate, "EmailDetailWork")
    }

    private class ContentResolverSink(private val resolver: android.content.ContentResolver) : DocumentSink {
        override suspend fun write(uri: android.net.Uri, bytes: ByteArray): Boolean =
            com.dedovmosol.iwomail.util.EmlFiles.write(bytes) { mode -> resolver.openOutputStream(uri, mode) }

        override suspend fun discard(uri: android.net.Uri) {
            try {
                android.provider.DocumentsContract.deleteDocument(resolver, uri)
            } catch (e: Exception) {
                // Поставщик не умеет удалять (или файла уже нет) — остаётся пустой файл
                Log.w("EmailDetailViewModel", "Can't remove unsaved document: ${e.message}")
            }
        }
    }
}

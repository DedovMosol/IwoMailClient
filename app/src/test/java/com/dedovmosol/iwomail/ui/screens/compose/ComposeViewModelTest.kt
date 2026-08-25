package com.dedovmosol.iwomail.ui.screens.compose

import android.content.Context
import android.net.Uri
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.data.database.ContactGroupEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.EmailHistoryResult
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.SignatureEntity
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.navigation.ShareIntentData
import com.dedovmosol.iwomail.ui.screens.compose.GroupSelection
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.ContactRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.GalContact
import com.dedovmosol.iwomail.ui.components.PendingEmail
import com.dedovmosol.iwomail.ui.components.SendController
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Юнит-тесты для [ComposeViewModel] (Этап 3: бизнес-логика, CS-16).
 *
 * VM тестируется БЕЗ Robolectric благодаря конструкторной инъекции (DIP):
 * репозитории, [SendController] и контекст — MockK-моки, диспетчер подменяется
 * тестовым, поэтому время детерминировано через [advanceTimeBy]/[advanceUntilIdle]
 * (паттерн проекта из [com.dedovmosol.iwomail.ui.screens.EmailDetailViewModelTest]).
 *
 * Покрытие по чек-листу плана (5.1):
 * - валидация и нормализация получателей;
 * - дебаунс подсказок (CS-7) и слияние источников с дедупликацией;
 * - бюджет вложений ДО чтения байт (CS-1/CS-2, N-2);
 * - отправка через SendController с корректным PendingEmail;
 * - сохранение черновика + crash-resistance (finally isSavingDraft=false);
 * - дельта-детект при выходе и синхронизация активного аккаунта.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var mailRepo: MailRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var contactRepo: ContactRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var sendController: SendController
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        mailRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        contactRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        sendController = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { settingsRepo.getLanguageSync() } returns "en"
        every { accountRepo.accounts } returns flowOf(listOf(account(ACCOUNT_ID)))
        coEvery { accountRepo.getSignaturesForAccount(any()) } returns emptyList()

        // Подсказки: по умолчанию пусто (переопределяется в отдельных тестах)
        coEvery { contactRepo.getGroupsList(any()) } returns emptyList()
        coEvery { contactRepo.searchForAutocomplete(any(), any(), any(), any()) } returns emptyList()
        coEvery { mailRepo.searchEmailHistory(any(), any(), any(), any()) } returns emptyList()
        coEvery { contactRepo.searchGAL(any(), any(), any()) } returns EasResult.Error("no network")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun account(id: Long = ACCOUNT_ID, email: String = "me@corp.com"): AccountEntity {
        val acc = mockk<AccountEntity>()
        every { acc.id } returns id
        every { acc.email } returns email
        every { acc.displayName } returns "Me"
        every { acc.signature } returns ""
        every { acc.isActive } returns true
        return acc
    }

    private fun createViewModel(
        editDraftId: String? = null,
        replyToEmailId: String? = null,
        forwardEmailId: String? = null,
        initialTo: String? = null,
        initialSubject: String? = null,
        initialBody: String? = null,
        accounts: List<AccountEntity> = listOf(account())
    ): ComposeViewModel {
        every { accountRepo.accounts } returns flowOf(accounts)
        return ComposeViewModel(
            mailRepository = mailRepo,
            accountRepository = accountRepo,
            contactRepository = contactRepo,
            settingsRepository = settingsRepo,
            sendController = sendController,
            context = context,
            dispatcher = dispatcher,
            initialAccountId = null,
            replyToEmailId = replyToEmailId,
            forwardEmailId = forwardEmailId,
            editDraftId = editDraftId,
            initialTo = initialTo,
            initialSubject = initialSubject,
            initialBody = initialBody
        )
    }

    private fun emailEntity(
        id: String,
        accountId: Long = ACCOUNT_ID,
        folderId: String = "folder_1",
        serverId: String = "srv_$id",
        from: String = "Sender <sender@corp.com>",
        to: String = "Recipient <recipient@corp.com>",
        cc: String = "",
        subject: String = "Original",
        body: String = "<p>orig</p>",
        importance: Int = 1,
        hasAttachments: Boolean = false
    ): EmailEntity = EmailEntity(
        id = id,
        accountId = accountId,
        folderId = folderId,
        serverId = serverId,
        from = from,
        to = to,
        cc = cc,
        subject = subject,
        body = body,
        dateReceived = 1_700_000_000_000L,
        read = true,
        importance = importance,
        hasAttachments = hasAttachments
    )

    private fun folderEntity(
        id: String = "folder_1",
        serverId: String = "col_$id",
        type: Int = FolderType.INBOX
    ): FolderEntity = FolderEntity(
        id = id,
        accountId = ACCOUNT_ID,
        serverId = serverId,
        displayName = "Folder",
        parentId = "",
        type = type
    )

    // ===================== init =====================

    @Test
    fun `init loads accounts and selects first as active`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.accounts).hasSize(1)
        assertThat(state.activeAccount?.id).isEqualTo(ACCOUNT_ID)
        assertThat(state.selectedAccountId).isEqualTo(ACCOUNT_ID)
        assertThat(state.mode).isEqualTo(ComposeMode.New)
    }

    @Test
    fun `selectAccount switches active account and reloads signatures`() = runTest(dispatcher) {
        val second = account(id = 2L, email = "second@corp.com")
        val vm = createViewModel(accounts = listOf(account(), second))
        advanceUntilIdle()

        vm.selectAccount(2L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedAccountId).isEqualTo(2L)
        assertThat(vm.uiState.value.activeAccount?.email).isEqualTo("second@corp.com")
        coVerify { accountRepo.getSignaturesForAccount(2L) }
    }

    // ===================== валидация получателей (3.1) =====================

    @Test
    fun `setTo with valid email sets toValid and hasRecipients`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setTo("john@example.com")

        val state = vm.uiState.value
        assertThat(state.to).isEqualTo("john@example.com")
        assertThat(state.toValid).isTrue()
        assertThat(state.hasRecipients).isTrue()
    }

    @Test
    fun `setTo with invalid email sets toValid false`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setTo("not-an-email")

        assertThat(vm.uiState.value.toValid).isFalse()
    }

    @Test
    fun `setTo with group token stays valid`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setTo("[Team]")

        assertThat(vm.uiState.value.toValid).isTrue()
        assertThat(vm.uiState.value.hasRecipients).isTrue()
    }

    @Test
    fun `setCc and setBcc update validation independently`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setCc("bad")
        vm.setBcc("ok@x.com")

        val state = vm.uiState.value
        assertThat(state.ccValid).isFalse()
        assertThat(state.bccValid).isTrue()
        assertThat(state.hasRecipients).isTrue()
    }

    @Test
    fun `hasRecipients false when all fields blank`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setTo("a@b.com")
        vm.setTo("")

        assertThat(vm.uiState.value.hasRecipients).isFalse()
    }

    // ===================== подписи =====================

    @Test
    fun `selectSignature appends signature block to body`() = runTest(dispatcher) {
        val vm = createViewModel()
        val sig = SignatureEntity(id = 1, accountId = ACCOUNT_ID, name = "Work", text = "Regards", isHtml = false)

        vm.selectSignature(sig)

        assertThat(vm.uiState.value.body).contains("<div class=\"signature\">")
        assertThat(vm.uiState.value.body).contains("Regards")
        assertThat(vm.uiState.value.selectedSignature?.id).isEqualTo(1L)
    }

    @Test
    fun `selectSignature replaces existing signature block`() = runTest(dispatcher) {
        val vm = createViewModel()
        val first = SignatureEntity(id = 1, accountId = ACCOUNT_ID, name = "A", text = "First", isHtml = false)
        val second = SignatureEntity(id = 2, accountId = ACCOUNT_ID, name = "B", text = "Second", isHtml = false)

        vm.selectSignature(first)
        vm.selectSignature(second)

        assertThat(vm.uiState.value.body).contains("Second")
        assertThat(vm.uiState.value.body).doesNotContain("First")
    }

    // ===================== подсказки с дебаунсом (3.2, CS-7) =====================

    @Test
    fun `searchSuggestions with query shorter than 3 chars clears suggestions`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.searchSuggestions("jo")
        advanceUntilIdle()

        assertThat(vm.uiState.value.suggestions).isEmpty()
        coVerify(exactly = 0) { contactRepo.searchForAutocomplete(any(), any(), any(), any()) }
    }

    @Test
    fun `searchSuggestions suppresses when email fully typed`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.searchSuggestions("john@example.com")
        advanceUntilIdle()

        assertThat(vm.uiState.value.suggestions).isEmpty()
        coVerify(exactly = 0) { contactRepo.searchForAutocomplete(any(), any(), any(), any()) }
    }

    @Test
    fun `searchSuggestions debounce executes only last query`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.searchSuggestions("joh")
        advanceTimeBy(100)
        runCurrent()
        vm.searchSuggestions("john") // отменяет предыдущий поиск до истечения дебаунса
        advanceTimeBy(300)
        runCurrent()

        // Реальный запрос к БД — только один (последний)
        coVerify(exactly = 1) {
            contactRepo.searchForAutocomplete(any(), "john", any(), any())
        }
        coVerify(exactly = 0) {
            contactRepo.searchForAutocomplete(any(), "joh", any(), any())
        }
    }

    @Test
    fun `searchSuggestions merges groups contacts and history deduped excluding self`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        val group = ContactGroupEntity(id = "g1", accountId = ACCOUNT_ID, name = "Johns Team", color = 42)
        val member = contactEntity("member@corp.com", "Member One")
        coEvery { contactRepo.getGroupsList(ACCOUNT_ID) } returns listOf(group)
        coEvery { contactRepo.getContactsByGroupList(ACCOUNT_ID, "g1") } returns listOf(member)
        coEvery { contactRepo.searchForAutocomplete(ACCOUNT_ID, "john", any(), 5) } returns listOf(
            contactEntity("john@corp.com", "John Contact")
        )
        coEvery { mailRepo.searchEmailHistory(ACCOUNT_ID, "john", any(), 5) } returns listOf(
            // Дубликат контакта — должен быть исключён
            EmailHistoryResult(email = "john@corp.com", name = "John From History"),
            // Новый адрес из истории
            EmailHistoryResult(email = "johnny@other.com", name = "Johnny History"),
            // Собственный адрес — должен быть исключён
            EmailHistoryResult(email = "me@corp.com", name = "Myself")
        )

        vm.searchSuggestions("john")
        advanceUntilIdle()

        val suggestions = vm.uiState.value.suggestions
        assertThat(suggestions.map { it.source }).containsExactly(
            SuggestionSource.GROUP, SuggestionSource.CONTACT, SuggestionSource.HISTORY
        ).inOrder()
        assertThat(suggestions[0].groupEmails).containsExactly("member@corp.com")
        assertThat(suggestions[1].email).isEqualTo("john@corp.com")
        assertThat(suggestions[2].email).isEqualTo("johnny@other.com")
        assertThat(vm.uiState.value.isLoadingContacts).isFalse()
    }

    @Test
    fun `applySuggestion for contact replaces partial token and tracks usage`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("existing@x.com, joh")

        val suggestion = EmailSuggestion(
            email = "john@corp.com",
            name = "John Contact",
            source = SuggestionSource.CONTACT
        )
        vm.applySuggestion(suggestion)
        advanceUntilIdle()

        assertThat(vm.uiState.value.to).isEqualTo("existing@x.com, john@corp.com")
        assertThat(vm.uiState.value.suggestions).isEmpty()
        coVerify { contactRepo.incrementUseCountByEmail(ACCOUNT_ID, "john@corp.com") }
    }

    @Test
    fun `applySuggestion for group inserts token and registers mapping`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("joh")

        val suggestion = EmailSuggestion(
            email = "a@x.com, b@x.com",
            name = "Team (2)",
            source = SuggestionSource.GROUP,
            groupEmails = listOf("a@x.com", "b@x.com"),
            groupName = "Team",
            groupColor = 7
        )
        vm.applySuggestion(suggestion)
        advanceUntilIdle()

        assertThat(vm.uiState.value.to).isEqualTo("[Team]")
        assertThat(vm.uiState.value.groupMappings["Team"]).containsExactly("a@x.com", "b@x.com")
        assertThat(vm.uiState.value.groupColors["Team"]).isEqualTo(7)
    }

    // ===================== дубликаты получателей =====================

    @Test
    fun `applySuggestion with duplicate defers addition pending confirmation`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com, joh")

        val suggestion = EmailSuggestion(
            email = "john@corp.com",
            name = "John",
            source = SuggestionSource.CONTACT
        )
        vm.applySuggestion(suggestion)
        advanceUntilIdle()

        val pending = vm.uiState.value.pendingDuplicateAddition
        assertThat(pending).isNotNull()
        assertThat(pending!!.duplicateEmail).isEqualTo("john@corp.com")
        assertThat(pending.targetField).isEqualTo(RecipientField.To)
        // Ничего не добавлено до подтверждения
        assertThat(vm.uiState.value.to).isEqualTo("john@corp.com, joh")
    }

    @Test
    fun `confirmDuplicateAddition appends pending emails`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com, joh")
        vm.applySuggestion(EmailSuggestion("john@corp.com", "John", SuggestionSource.CONTACT))
        advanceUntilIdle()

        vm.confirmDuplicateAddition()
        advanceUntilIdle()

        assertThat(vm.uiState.value.to).isEqualTo("john@corp.com, joh, john@corp.com")
        assertThat(vm.uiState.value.pendingDuplicateAddition).isNull()
    }

    @Test
    fun `dismissDuplicateAddition clears pending state`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com, joh")
        vm.applySuggestion(EmailSuggestion("john@corp.com", "John", SuggestionSource.CONTACT))
        advanceUntilIdle()

        vm.dismissDuplicateAddition()

        assertThat(vm.uiState.value.pendingDuplicateAddition).isNull()
    }

    @Test
    fun `addRecipientsFromPicker appends unique and defers duplicates`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com")

        vm.addRecipientsFromPicker(RecipientField.To, listOf("john@corp.com", "new@corp.com"))
        advanceUntilIdle()

        // Уникальный добавлен сразу
        assertThat(vm.uiState.value.to).isEqualTo("john@corp.com, new@corp.com")
        // Дубликат — в отложенном добавлении
        val pending = vm.uiState.value.pendingDuplicateAddition
        assertThat(pending).isNotNull()
        assertThat(pending!!.duplicateEmail).isEqualTo("john@corp.com")
    }

    // ===================== отправка (3.3, CS-1/CS-2) =====================

    @Test
    fun `sendEmail blocked by attachment budget before reading bytes`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com")

        // 5 МБ файловое вложение + ~6 МБ inline base64 в теле → 11 МБ > лимита 10 МБ.
        // size > 0 → ContentResolver не трогается (бюджет считается по метаданным).
        vm.addAttachment(mockk<Uri>(), "big.bin", "application/octet-stream", 5_000_000L)
        val base64 = "QUJD".repeat(2_000_000) // ~6 МБ после декодирования
        vm.setBody("<img src=\"data:image/png;base64,$base64\">")

        vm.sendEmail()
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.AttachmentLimitExceeded::class.java)
        assertThat(vm.uiState.value.isSending).isFalse()
        coVerify(exactly = 0) {
            sendController.startSend(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `sendEmail passes correct PendingEmail to SendController and emits EmailSent`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com, [Team]")
        vm.setCc("cc@corp.com")
        vm.setSubject("Hello")
        vm.setBody("<p>Body</p><div>   </div>")
        // Маппинг группы (как после выбора групповой подсказки)
        vm.applySuggestion(
            EmailSuggestion("a@x.com", "Team (1)", SuggestionSource.GROUP,
                groupEmails = listOf("a@x.com"), groupName = "Team", groupColor = 1)
        )
        advanceUntilIdle()
        // applySuggestion заменил частичный ввод — вернём получателей как нужно
        vm.setTo("john@corp.com, [Team]")

        val pendingEmailSlot = slot<PendingEmail>()
        vm.sendEmail()
        advanceUntilIdle()

        coVerify {
            sendController.startSend(
                email = capture(pendingEmailSlot),
                message = any(),
                context = any(),
                mailRepo = mailRepo,
                onSuccess = any(),
                onCancel = any(),
                onError = any()
            )
        }
        val sent = pendingEmailSlot.captured
        // Групповой токен раскрыт в реальный адрес (никогда не уходит на сервер как есть)
        assertThat(sent.to).isEqualTo("john@corp.com, a@x.com")
        assertThat(sent.cc).isEqualTo("cc@corp.com")
        assertThat(sent.subject).isEqualTo("Hello")
        // Хвостовые пустые div удалены из тела
        assertThat(sent.body).isEqualTo("<p>Body</p>")
        assertThat(sent.importance).isEqualTo(1)
        assertThat(sent.draftId).isNull()

        // Экран закрывается сразу (прогресс — в общей плашке)
        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.EmailSent::class.java)
    }

    @Test
    fun `sendEmail without account emits Error and resets isSending`() = runTest(dispatcher) {
        every { accountRepo.accounts } returns flowOf(emptyList())
        val vm = createViewModel(accounts = emptyList())
        advanceUntilIdle()
        vm.setTo("john@corp.com")

        vm.sendEmail()
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.Error::class.java)
        assertThat(vm.uiState.value.isSending).isFalse()
    }

    @Test
    fun `sendEmail with invalid recipient emits ValidationError`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("invalid-email")

        vm.sendEmail()
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.ValidationError::class.java)
        assertThat((event as ComposeEvent.ValidationError).field).isEqualTo(ValidationField.To)
        assertThat(vm.uiState.value.isSending).isFalse()
        coVerify(exactly = 0) {
            sendController.startSend(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `sendEmail is idempotent while sending`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com")

        vm.sendEmail()
        vm.sendEmail() // второй вызов во время первого — игнорируется
        advanceUntilIdle()

        coVerify(exactly = 1) {
            sendController.startSend(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ===================== черновик (3.4) =====================

    @Test
    fun `saveDraft new draft succeeds with DraftSaved and NavigateBack`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com")
        vm.setSubject("Draft")
        vm.setBody("text")
        coEvery {
            mailRepo.saveDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "ewsItemId123"

        vm.saveDraft()
        advanceUntilIdle()

        val first = vm.events.first()
        val second = vm.events.first()
        assertThat(first).isInstanceOf(ComposeEvent.DraftSaved::class.java)
        assertThat(second).isInstanceOf(ComposeEvent.NavigateBack::class.java)
        assertThat(vm.uiState.value.isSavingDraft).isFalse()
    }

    @Test
    fun `saveDraft crash resistance - repo throws emits Error and resets flag`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com")
        coEvery {
            mailRepo.saveDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IOException("Network error")

        vm.saveDraft()
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.Error::class.java)
        assertThat(vm.uiState.value.isSavingDraft).isFalse()
    }

    @Test
    fun `saveDraft blocked by attachment budget`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.addToDraftModeWithBigAttachment()

        vm.saveDraft()
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.AttachmentLimitExceeded::class.java)
        assertThat(vm.uiState.value.isSavingDraft).isFalse()
        coVerify(exactly = 0) {
            mailRepo.saveDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `saveDraft normalizes recipients before persistence`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("John Doe <john@corp.com>, john@corp.com")
        vm.setSubject("S")
        coEvery {
            mailRepo.saveDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "id1"

        vm.saveDraft()
        advanceUntilIdle()

        coVerify {
            mailRepo.saveDraft(
                accountId = ACCOUNT_ID,
                to = "john@corp.com", // дубликат убран, имя извлечено
                cc = "",
                bcc = "",
                subject = "S",
                serverBody = "",
                localBody = "",
                fromEmail = "me@corp.com",
                fromName = "Me",
                hasAttachments = false,
                attachmentFiles = emptyList()
            )
        }
    }

    // ===================== выход и дельта-детект =====================

    @Test
    fun `handleBackPress shows discard dialog when new email has content`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("john@corp.com")

        vm.handleBackPress()
        advanceUntilIdle()

        assertThat(vm.uiState.value.showDiscardDialog).isTrue()
    }

    @Test
    fun `handleBackPress navigates back when new email is empty`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.handleBackPress()
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.NavigateBack::class.java)
        assertThat(vm.uiState.value.showDiscardDialog).isFalse()
    }

    @Test
    fun `discardAndExit syncs changed active account before navigating back`() = runTest(dispatcher) {
        val second = account(id = 2L, email = "second@corp.com")
        val vm = createViewModel(accounts = listOf(account(), second))
        advanceUntilIdle()
        vm.setTo("john@corp.com")
        vm.handleBackPress()
        advanceUntilIdle()

        // Пользователь сменил аккаунт и отказался сохранять
        vm.selectAccount(2L)
        advanceUntilIdle()
        vm.discardAndExit()
        advanceUntilIdle()

        coVerify { accountRepo.setActiveAccount(2L) }
        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.NavigateBack::class.java)
    }

    // ===================== эталонная сверка: аудит-фиксы (самоаудит 2026-08-24) =====================
    // Регрессии, найденные поразрядной сверкой с эталонным ComposeScreen и закрытые тестами.

    // --- Reply ---

    @Test
    fun `loadReply normalizes recipient name format and sets Re subject and mode`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("e1") } returns emailEntity(id = "e1")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()

        val vm = createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.mode).isEqualTo(ComposeMode.Reply)
        // "Name" <email> → чистый email (эталон: normalizeRecipients)
        assertThat(state.to).isEqualTo("sender@corp.com")
        assertThat(state.toValid).isTrue()
        assertThat(state.hasRecipients).isTrue()
        assertThat(state.subject).isEqualTo("Re: Original")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `loadReply for sent folder addresses original recipient`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("e1") } returns emailEntity(id = "e1")
        coEvery { mailRepo.getFolderSync("folder_1") } returns
            folderEntity(type = FolderType.SENT_ITEMS)

        val vm = createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        assertThat(vm.uiState.value.to).isEqualTo("recipient@corp.com")
    }

    @Test
    fun `loadReply quote uses russian labels without To line`() = runTest(dispatcher) {
        every { settingsRepo.getLanguageSync() } returns "ru"
        coEvery { mailRepo.getEmailSync("e1") } returns emailEntity(id = "e1")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()

        val vm = createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        val body = vm.uiState.value.body
        // Локализованные метки и заголовок (эталон: Strings.*)
        assertThat(body).contains("Исходное сообщение")
        assertThat(body).contains("<b>От:</b>")
        assertThat(body).contains("<b>Дата:</b>")
        assertThat(body).contains("<b>Тема:</b>")
        // Эталон: в цитате ответа строка "Кому:" НЕ выводится (toField = null)
        assertThat(body).doesNotContain("<b>Кому:</b>")
    }

    @Test
    fun `loadReply quote uses english labels`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("e1") } returns emailEntity(id = "e1")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()

        val vm = createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        val body = vm.uiState.value.body
        assertThat(body).contains("Original message")
        assertThat(body).contains("<b>From:</b>")
        assertThat(body).contains("<b>Date:</b>")
        assertThat(body).contains("<b>Subject:</b>")
    }

    @Test
    fun `loadReply uses email owner account for attachment client not active account`() = runTest(dispatcher) {
        // Мультиаккаунт: письмо принадлежит аккаунту 5, активный аккаунт — 1.
        // Скачивание вложений должно идти через клиент аккаунта 5 (эталон).
        val owner = account(id = 5L, email = "owner@corp.com")
        coEvery { mailRepo.getEmailSync("e1") } returns
            emailEntity(id = "e1", accountId = 5L)
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(5L) } returns owner
        coEvery { accountRepo.createEasClient(5L) } returns mockk()

        createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        coVerify(exactly = 1) { accountRepo.getAccount(5L) }
        coVerify(exactly = 1) { accountRepo.createEasClient(5L) }
        coVerify(exactly = 0) { accountRepo.createEasClient(ACCOUNT_ID) }
    }

    @Test
    fun `loadReply fetches remaining cids from server when locally unresolved`() = runTest(dispatcher) {
        val easClient = mockk<EasClient>()
        coEvery { mailRepo.getEmailSync("e1") } returns
            emailEntity(id = "e1", body = "<p>x</p><img src=\"cid:abc\">")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns easClient
        coEvery { easClient.fetchInlineImages("col_folder_1", "srv_e1") } returns
            EasResult.Success(mapOf("abc" to "data:image/png;base64,QQ=="))

        val vm = createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        val body = vm.uiState.value.body
        assertThat(body).contains("data:image/png")
        assertThat(body).doesNotContain("cid:abc")
        coVerify(exactly = 1) { easClient.fetchInlineImages("col_folder_1", "srv_e1") }
    }

    @Test
    fun `loadReply skips server fetch when body has no cids`() = runTest(dispatcher) {
        // Производительность: без cid: сетевой вызов не делается
        val easClient = mockk<EasClient>()
        coEvery { mailRepo.getEmailSync("e1") } returns
            emailEntity(id = "e1", body = "<p>plain</p>")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns easClient

        createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        coVerify(exactly = 0) { easClient.fetchInlineImages(any(), any()) }
    }

    // --- Forward ---

    @Test
    fun `loadForward sets empty to Fwd subject forward source and localized header`() = runTest(dispatcher) {
        every { settingsRepo.getLanguageSync() } returns "ru"
        coEvery { mailRepo.getEmailSync("e2") } returns emailEntity(id = "e2")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()

        val vm = createViewModel(forwardEmailId = "e2")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.mode).isEqualTo(ComposeMode.Forward)
        assertThat(state.to).isEmpty()
        assertThat(state.subject).isEqualTo("Fwd: Original")
        // SmartForward source (MS-ASCMD §2.2.1.19) — без него пересылка теряет вложения
        assertThat(state.forwardSourceFolderServerId).isEqualTo("col_folder_1")
        assertThat(state.forwardSourceEmailServerId).isEqualTo("srv_e2")
        assertThat(state.body).contains("Пересылаемое сообщение")
        assertThat(state.body).contains("<b>Кому:</b>") // в пересылке строка "Кому:" выводится
    }

    @Test
    fun `loadForward keeps existing Fwd or Fw prefix`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("e2") } returns
            emailEntity(id = "e2", subject = "Fw: Chain")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()

        val vm = createViewModel(forwardEmailId = "e2")
        advanceUntilIdle()

        assertThat(vm.uiState.value.subject).isEqualTo("Fw: Chain")
    }

    // --- Редактирование черновика ---

    @Test
    fun `loadEditDraft normalizes recipients sets validation and importance`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("d1") } returns emailEntity(
            id = "d1",
            to = "John Doe <john@corp.com>, john@corp.com",
            cc = "Cc User <cc@corp.com>",
            subject = "Draft S",
            body = "<p>draft body</p>",
            importance = 2
        )
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns mockk()

        val vm = createViewModel(editDraftId = "d1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.mode).isEqualTo(ComposeMode.EditDraft)
        // Нормализация: имя извлечено, дубликат убран (эталон)
        assertThat(state.to).isEqualTo("john@corp.com")
        assertThat(state.cc).isEqualTo("cc@corp.com")
        assertThat(state.toValid).isTrue()
        assertThat(state.ccValid).isTrue()
        assertThat(state.hasRecipients).isTrue()
        assertThat(state.importance).isEqualTo(2)
        assertThat(state.body).isEqualTo("<p>draft body</p>")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `loadEditDraft lazy loads body from server for empty local body`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("d1") } returns emailEntity(
            id = "d1", body = "", serverId = "5:1"
        )
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns mockk()
        coEvery { mailRepo.loadEmailBody("d1") } returns EasResult.Success("<p>from server</p>")

        val vm = createViewModel(editDraftId = "d1")
        advanceUntilIdle()

        assertThat(vm.uiState.value.body).isEqualTo("<p>from server</p>")
        coVerify(exactly = 1) { mailRepo.loadEmailBody("d1") }
    }

    @Test
    fun `loadEditDraft skips server body and metadata refresh for local draft`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("d1") } returns emailEntity(
            id = "d1", body = "", serverId = "local_draft_999"
        )
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns mockk()

        createViewModel(editDraftId = "d1")
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.loadEmailBody(any()) }
        coVerify(exactly = 0) { mailRepo.refreshAttachmentMetadata(any()) }
    }

    @Test
    fun `loadEditDraft refreshes attachment metadata for server draft`() = runTest(dispatcher) {
        // Вложения, добавленные в Outlook на ПК, не появятся в локальной БД
        // без обновления метаданных с сервера (эталон).
        coEvery { mailRepo.getEmailSync("d1") } returns emailEntity(
            id = "d1", body = "<p>x</p>", serverId = "5:1"
        )
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns mockk()

        createViewModel(editDraftId = "d1")
        advanceUntilIdle()

        coVerify(exactly = 1) { mailRepo.refreshAttachmentMetadata("d1") }
    }

    @Test
    fun `loadEditDraft resolves cid via EWS for long itemId and persists resolved body`() = runTest(dispatcher) {
        val easClient = mockk<EasClient>()
        val ewsItemId = "AAMkAGI2Nz" + "Q".repeat(50) + "=" // EWS ItemId: длинный, с "="
        coEvery { mailRepo.getEmailSync("d1") } returns emailEntity(
            id = "d1",
            body = "<p>hello</p><img src=\"cid:x1\">",
            serverId = ewsItemId
        )
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns easClient
        coEvery { easClient.fetchInlineImagesEws(ewsItemId) } returns
            EasResult.Success(mapOf("x1" to "data:image/png;base64,QUJD"))

        val vm = createViewModel(editDraftId = "d1")
        advanceUntilIdle()

        val body = vm.uiState.value.body
        assertThat(body).contains("data:image/png")
        assertThat(body).doesNotContain("cid:x1")
        // Эталон: резолвленное тело записывается в БД (не нужен повторный сетевой запрос)
        coVerify(exactly = 1) {
            mailRepo.updateEmailBody("d1", match { it.contains("data:image/png") && !it.contains("cid:") })
        }
        // EAS fetchInlineImages НЕ вызывается для EWS ItemId
        coVerify(exactly = 0) { easClient.fetchInlineImages(any(), any()) }
    }

    @Test
    fun `loadEditDraft does not persist unchanged body`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("d1") } returns emailEntity(
            id = "d1", body = "<p>hello</p>", serverId = "5:1"
        )
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns account()
        coEvery { accountRepo.createEasClient(ACCOUNT_ID) } returns mockk()

        createViewModel(editDraftId = "d1")
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.updateEmailBody(any(), any()) }
    }

    // --- Mailto / Share intent ---

    @Test
    fun `mailto with only subject and body applies Mailto mode`() = runTest(dispatcher) {
        val vm = createViewModel(initialSubject = "Hi", initialBody = "there")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.mode).isEqualTo(ComposeMode.Mailto)
        assertThat(state.subject).isEqualTo("Hi")
        assertThat(state.body).isEqualTo("there")
    }

    @Test
    fun `share attachments cleared after consumption and mode becomes Share for new email`() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        val resolver = mockk<android.content.ContentResolver>()
        val cursor = mockk<android.database.Cursor>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { resolver.query(uri, null, null, null, null) } returns cursor
        every { resolver.getType(uri) } returns "application/pdf"
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.getColumnIndex(android.provider.OpenableColumns.SIZE) } returns 1
        every { cursor.getString(0) } returns "doc.pdf"
        every { cursor.getLong(1) } returns 1234L
        ShareIntentData.attachments = listOf(uri)

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(ShareIntentData.attachments).isEmpty() // очищено после потребления
        assertThat(state.mode).isEqualTo(ComposeMode.Share)
        assertThat(state.attachments).hasSize(1)
        assertThat(state.attachments[0].name).isEqualTo("doc.pdf")
        assertThat(state.attachmentSizeBytes).isEqualTo(1234L)
    }

    @Test
    fun `share attachments do not override Mailto mode`() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        val resolver = mockk<android.content.ContentResolver>()
        val cursor = mockk<android.database.Cursor>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { resolver.query(uri, null, null, null, null) } returns cursor
        every { resolver.getType(uri) } returns "application/pdf"
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.getColumnIndex(android.provider.OpenableColumns.SIZE) } returns 1
        every { cursor.getString(0) } returns "doc.pdf"
        every { cursor.getLong(1) } returns 1234L
        ShareIntentData.attachments = listOf(uri)

        val vm = createViewModel(initialTo = "a@b.com")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.mode).isEqualTo(ComposeMode.Mailto) // не затёрт на Share
        assertThat(state.attachments).hasSize(1) // вложения дополняют, а не заменяют
    }

    // ===================== аудит раунд 2: локализация ошибок (релиз 1.6.3, требование №2) =====================

    @Test
    fun `loadReply emits localized Email not found error in english`() = runTest(dispatcher) {
        // setUp: язык "en" по умолчанию
        coEvery { mailRepo.getEmailSync("missing") } returns null

        val vm = createViewModel(replyToEmailId = "missing")
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.Error::class.java)
        assertThat((event as ComposeEvent.Error).message).isEqualTo("Email not found")
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `loadReply emits localized error in russian`() = runTest(dispatcher) {
        every { settingsRepo.getLanguageSync() } returns "ru"
        coEvery { mailRepo.getEmailSync("missing") } returns null

        val vm = createViewModel(replyToEmailId = "missing")
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.Error::class.java)
        assertThat((event as ComposeEvent.Error).message).isEqualTo("Письмо не найдено")
    }

    @Test
    fun `loadEditDraft emits localized Draft not found error`() = runTest(dispatcher) {
        every { settingsRepo.getLanguageSync() } returns "ru"
        coEvery { mailRepo.getEmailSync("missing") } returns null

        val vm = createViewModel(editDraftId = "missing")
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.Error::class.java)
        assertThat((event as ComposeEvent.Error).message).isEqualTo("Черновик не найден")
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `loadForward emits localized error when email missing`() = runTest(dispatcher) {
        coEvery { mailRepo.getEmailSync("missing") } returns null

        val vm = createViewModel(forwardEmailId = "missing")
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.Error::class.java)
        assertThat((event as ComposeEvent.Error).message).isEqualTo("Email not found")
    }

    @Test
    fun `attachment load failure shows localized message not raw server error`() = runTest(dispatcher) {
        // Внутренняя ошибка лоадера (например, системное сообщение на английском)
        // не должна доходить до пользователя — показываем чистое локализованное.
        every { settingsRepo.getLanguageSync() } returns "ru"
        coEvery { mailRepo.getEmailSync("e1") } returns emailEntity(id = "e1")
        coEvery { mailRepo.getFolderSync("folder_1") } returns folderEntity()
        coEvery { mailRepo.getAttachmentsSync("e1") } throws IOException("System crash: fd closed")

        val vm = createViewModel(replyToEmailId = "e1")
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.Error::class.java)
        val message = (event as ComposeEvent.Error).message
        assertThat(message).isEqualTo("Не удалось загрузить вложения")
        assertThat(message).doesNotContain("System crash")
        assertThat(vm.uiState.value.isLoading).isFalse()
    }

    // ===================== аудит раунд 2: mailto + подпись =====================

    @Test
    fun `mailto keeps default signature below initial body`() = runTest(dispatcher) {
        // Регрессия раунда 2: applyMailtoIntent перезаписывал body целиком —
        // подпись, подставленная до этого, терялась. Эталон строит тело как
        // «почитаемое тело + подпись».
        coEvery { accountRepo.getSignaturesForAccount(any()) } returns listOf(
            SignatureEntity(id = 9, accountId = ACCOUNT_ID, name = "Work", text = "Best regards", isDefault = true)
        )

        val vm = createViewModel(initialTo = "a@b.com", initialBody = "<p>Hello from share</p>")
        advanceUntilIdle()

        val body = vm.uiState.value.body
        assertThat(vm.uiState.value.mode).isEqualTo(ComposeMode.Mailto)
        assertThat(body).startsWith("<p>Hello from share</p>")
        assertThat(body).contains("<div class=\"signature\">")
        assertThat(body).contains("Best regards")
        // Подпись — ПОСЛЕ тела шаринга (позиция эталона: подпись всегда внизу)
        assertThat(body.indexOf("<p>Hello from share</p>"))
            .isLessThan(body.indexOf("<div class=\"signature\">"))
    }

    @Test
    fun `mailto without signature uses initial body as-is`() = runTest(dispatcher) {
        // setUp: подписей нет (пустой список)
        val vm = createViewModel(initialTo = "a@b.com", initialBody = "<p>Shared text</p>")
        advanceUntilIdle()

        assertThat(vm.uiState.value.body).isEqualTo("<p>Shared text</p>")
    }

    @Test
    fun `mailto with only body applies signature`() = runTest(dispatcher) {
        // mailto без получателя — тоже валидный сценарий (этап 3, фикс init)
        coEvery { accountRepo.getSignaturesForAccount(any()) } returns listOf(
            SignatureEntity(id = 9, accountId = ACCOUNT_ID, name = "Work", text = "Sig", isDefault = true)
        )

        val vm = createViewModel(initialBody = "plain shared line")
        advanceUntilIdle()

        val body = vm.uiState.value.body
        assertThat(vm.uiState.value.mode).isEqualTo(ComposeMode.Mailto)
        assertThat(body).contains("plain shared line")
        assertThat(body).contains("<div class=\"signature\">")
    }

    @Test
    fun `sendEmail allows recipients only in cc or bcc`() = runTest(dispatcher) {
        // Регрессия раунда 2: валидация «нет получателей» смотрела только на to —
        // письмо с адресатом лишь в копии/скрытой копии блокировалось.
        // Эталон гейтит по любому из полей (hasRecipients).
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setTo("")
        vm.setCc("cc-only@corp.com")
        vm.sendEmail()
        advanceUntilIdle()

        // Доказательство: валидация пройдена и отправка дошла до контроллера
        // с корректными получателями (to пуст, адресат только в копии).
        // После успеха экран закрывается (эталон: onSent), isSending не сбрасывается —
        // как в эталоне, где он остаётся истинным до закрытия экрана.
        val pendingEmailSlot = slot<PendingEmail>()
        coVerify(exactly = 1) {
            sendController.startSend(
                email = capture(pendingEmailSlot),
                message = any(), context = any(), mailRepo = any(),
                onSuccess = any(), onCancel = any(), onError = any()
            )
        }
        assertThat(pendingEmailSlot.captured.to).isEqualTo("")
        assertThat(pendingEmailSlot.captured.cc).isEqualTo("cc-only@corp.com")
    }

    @Test
    fun `sendEmail blocks when no recipients in any field`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setTo("")
        vm.setCc("")
        vm.setBcc("")
        vm.sendEmail()
        advanceUntilIdle()

        val event = vm.events.first()
        assertThat(event).isInstanceOf(ComposeEvent.ValidationError::class.java)
        assertThat((event as ComposeEvent.ValidationError).field).isEqualTo(ValidationField.NoRecipients)
        coVerify(exactly = 0) {
            sendController.startSend(any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ===================== аудит раунд 4: покрытие всех публичных методов =====================

    @Test
    fun `setImportance updates state`() = runTest(dispatcher) {
        val vm = createViewModel()
        assertThat(vm.uiState.value.importance).isEqualTo(1) // дефолт Normal
        vm.setImportance(2) // High
        assertThat(vm.uiState.value.importance).isEqualTo(2)
    }

    @Test
    fun `setRequestReadReceipt updates state`() = runTest(dispatcher) {
        val vm = createViewModel()
        assertThat(vm.uiState.value.requestReadReceipt).isFalse()
        vm.setRequestReadReceipt(true)
        assertThat(vm.uiState.value.requestReadReceipt).isTrue()
    }

    @Test
    fun `setRequestDeliveryReceipt updates state`() = runTest(dispatcher) {
        val vm = createViewModel()
        assertThat(vm.uiState.value.requestDeliveryReceipt).isFalse()
        vm.setRequestDeliveryReceipt(true)
        assertThat(vm.uiState.value.requestDeliveryReceipt).isTrue()
    }

    @Test
    fun `removeAttachment removes item and decrements size`() = runTest(dispatcher) {
        val vm = createViewModel()
        val uri = mockk<Uri>()
        vm.addAttachment(uri, "doc.pdf", "application/pdf", 1000L)
        assertThat(vm.uiState.value.attachments).hasSize(1)
        assertThat(vm.uiState.value.attachmentSizeBytes).isEqualTo(1000L)

        vm.removeAttachment(uri)
        assertThat(vm.uiState.value.attachments).isEmpty()
        assertThat(vm.uiState.value.attachmentSizeBytes).isEqualTo(0L)
    }

    @Test
    fun `removeAttachment with unknown uri is no-op`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.addAttachment(mockk<Uri>(), "doc.pdf", "application/pdf", 1000L)
        val before = vm.uiState.value

        vm.removeAttachment(mockk<Uri>()) // другой uri
        assertThat(vm.uiState.value).isEqualTo(before)
    }

    @Test
    fun `dismissDiscardDialog hides dialog`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("a@b.com") // есть несохранённые изменения
        vm.handleBackPress()
        advanceUntilIdle() // handleBackPress асинхронный (viewModelScope.launch)
        assertThat(vm.uiState.value.showDiscardDialog).isTrue()

        vm.dismissDiscardDialog()
        assertThat(vm.uiState.value.showDiscardDialog).isFalse()
    }

    @Test
    fun `saveDraftAndExit hides dialog and delegates to saveDraft`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("a@b.com")
        vm.handleBackPress()
        advanceUntilIdle() // handleBackPress асинхронный (viewModelScope.launch)
        assertThat(vm.uiState.value.showDiscardDialog).isTrue()

        vm.saveDraftAndExit()
        advanceUntilIdle()

        assertThat(vm.uiState.value.showDiscardDialog).isFalse()
        // saveDraft сохранил черновик и закрыл экран (проверяем через репозиторий)
        coVerify { mailRepo.saveDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addGroupsFromPicker appends unique group and defers duplicate`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("existing@corp.com")

        val group1 = GroupSelection(name = "Team", emails = listOf("new@corp.com"), color = 1)
        val group2 = GroupSelection(name = "Dup", emails = listOf("existing@corp.com"), color = 2)
        vm.addGroupsFromPicker(RecipientField.To, listOf(group1, group2))
        advanceUntilIdle() // addGroupsFromPicker асинхронный (viewModelScope.launch)

        val state = vm.uiState.value
        // При наличии дубликата все группы откладываются в pending (паттерн VM)
        assertThat(state.to).doesNotContain("[Team]")
        assertThat(state.to).doesNotContain("[Dup]")
        assertThat(state.pendingDuplicateAddition).isNotNull()
        assertThat(state.pendingDuplicateAddition!!.duplicateEmail).isEqualTo("existing@corp.com")
        assertThat(state.pendingDuplicateAddition!!.groups).hasSize(2)
    }

    @Test
    fun `addGroupsFromPicker with only duplicates defers all`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTo("a@corp.com")

        val group = GroupSelection(name = "All", emails = listOf("a@corp.com"), color = 3)
        vm.addGroupsFromPicker(RecipientField.To, listOf(group))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.to).isEqualTo("a@corp.com") // ничего не добавлено
        assertThat(state.pendingDuplicateAddition).isNotNull()
        assertThat(state.pendingDuplicateAddition!!.groups).hasSize(1)
    }

    // ===================== helpers =====================

    private fun ComposeViewModel.addToDraftModeWithBigAttachment() {
        setTo("john@corp.com")
        // 11 МБ — превышает лимит 10 МБ; size > 0 → ContentResolver не нужен
        addAttachment(mockk<Uri>(), "big.bin", "application/octet-stream", 11_000_000L)
    }

    private fun contactEntity(email: String, name: String): ContactEntity = ContactEntity(
        id = "id_$email",
        accountId = ACCOUNT_ID,
        displayName = name,
        email = email
    )

    private companion object {
        const val ACCOUNT_ID = 1L
    }
}

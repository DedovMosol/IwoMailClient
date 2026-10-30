package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.screens.emaildetail.EmailDetailActions
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты для [EmailDetailViewModel] (ядро MVVM-миграции экрана просмотра письма).
 *
 * VM тестируется БЕЗ Robolectric благодаря конструкторной инъекции (DIP): [EmailDetailActions],
 * [MailRepository] и [AccountRepository] — MockK-моки, IO-диспетчер подменяется тестовым, поэтому
 * всё детерминировано через [advanceUntilIdle]. Side-effect'ы (тосты/навигация) проверяются через
 * канал one-shot событий.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var actions: EmailDetailActions
    private lateinit var mailRepo: MailRepository
    private lateinit var accountRepo: AccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        actions = mockk(relaxed = true)
        mailRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)

        every { accountRepo.activeAccount } returns flowOf(accountMock(ACCOUNT_ID))
        every { mailRepo.getEmail(EMAIL_ID) } returns flowOf(email())
        every { mailRepo.getAttachments(EMAIL_ID) } returns flowOf(emptyList())
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(listOf(folder(INBOX_ID, FolderType.INBOX)))

        // Открытие письма: по умолчанию письмо прочитано и тело уже загружено → без markAsRead/loadBody.
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email()
        coEvery { actions.markAsRead(any(), any()) } returns EasResult.Success(Unit)
        coEvery { actions.pendingReadReceipt(any()) } returns null
        coEvery { actions.loadInlineImages(any(), any(), any(), any(), any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): EmailDetailViewModel =
        EmailDetailViewModel(EMAIL_ID, actions, mailRepo, accountRepo, dispatcher)

    // ===================== init / data =====================

    @Test
    fun `init loads email, folders and derives non-trash flags`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.email?.id).isEqualTo(EMAIL_ID)
        assertThat(s.folders.map { it.id }).containsExactly(INBOX_ID)
        assertThat(s.isInTrash).isFalse()
        assertThat(s.isInSent).isFalse()
        assertThat(s.isInDrafts).isFalse()
    }

    @Test
    fun `init derives isInTrash when current folder is deleted items`() = runTest(dispatcher) {
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(listOf(folder(INBOX_ID, FolderType.DELETED_ITEMS)))
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.isInTrash).isTrue()
    }

    // ===================== openEmail =====================

    @Test
    fun `open unread email marks it as read`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(read = false)
        createViewModel()
        advanceUntilIdle()
        coVerify { actions.markAsRead(EMAIL_ID, true) }
    }

    @Test
    fun `open email with empty body loads body and clears loading`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(body = "", preview = "p")
        coEvery { actions.loadEmailBody(EMAIL_ID) } returns EasResult.Success("loaded")
        val vm = createViewModel()
        advanceUntilIdle()
        coVerify { actions.loadEmailBody(EMAIL_ID) }
        assertThat(vm.uiState.value.isLoadingBody).isFalse()
        assertThat(vm.uiState.value.bodyLoadError).isNull()
    }

    @Test
    fun `open missing email sets NotFound body error`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns null
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.bodyLoadError).isEqualTo(BodyLoadError.NotFound)
    }

    @Test
    fun `body OBJECT_NOT_FOUND that no longer exists emits DeletedOnServer and NavigateBack`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returnsMany listOf(email(body = ""), null)
        coEvery { actions.loadEmailBody(EMAIL_ID) } returns EasResult.Error("OBJECT_NOT_FOUND")
        val vm = createViewModel()
        val events = collectEvents(vm)
        advanceUntilIdle()
        assertThat(events).contains(EmailDetailEvent.DeletedOnServer)
        assertThat(events).contains(EmailDetailEvent.NavigateBack)
    }

    // ===================== inline images =====================

    @Test
    fun `inline images are loaded into state`() = runTest(dispatcher) {
        coEvery { actions.loadInlineImages(any(), any(), any(), any(), any()) } returns mapOf("cid" to "data:img")
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.inlineImages).containsEntry("cid", "data:img")
        coVerify { actions.loadInlineImages(any(), any(), any(), any(), any()) }
    }

    // ===================== refresh =====================

    @Test
    fun `refresh with body emits Refreshed and clears loading`() = runTest(dispatcher) {
        coEvery { actions.syncAndReloadBody(EMAIL_ID, ACCOUNT_ID, INBOX_ID) } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.refresh()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Refreshed)
        assertThat(vm.uiState.value.isLoadingBody).isFalse()
    }

    @Test
    fun `refresh with empty body emits NoBodyFromServer and sets body error`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        coEvery { actions.syncAndReloadBody(EMAIL_ID, ACCOUNT_ID, INBOX_ID) } returns EasResult.Success(Unit)
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(body = "")

        vm.refresh()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.NoBodyFromServer)
        assertThat(vm.uiState.value.bodyLoadError).isEqualTo(BodyLoadError.NoBodyFromServer)
    }

    @Test
    fun `refresh error emits Error and sets raw body error`() = runTest(dispatcher) {
        coEvery { actions.syncAndReloadBody(EMAIL_ID, ACCOUNT_ID, INBOX_ID) } returns EasResult.Error("sync-fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.refresh()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Error("sync-fail"))
        assertThat(vm.uiState.value.bodyLoadError).isEqualTo(BodyLoadError.Raw("sync-fail"))
    }

    // ===================== delete / move / restore =====================

    @Test
    fun `deleteToTrash moved emits MovedToTrash and NavigateBack`() = runTest(dispatcher) {
        coEvery { actions.deleteEmails(listOf(EMAIL_ID), false) } returns EasResult.Success(1)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteToTrash()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.MovedToTrash)
        assertThat(events).contains(EmailDetailEvent.NavigateBack)
        assertThat(vm.uiState.value.isDeleting).isFalse()
    }

    @Test
    fun `deleteToTrash with zero count emits DeletedPermanently`() = runTest(dispatcher) {
        coEvery { actions.deleteEmails(listOf(EMAIL_ID), false) } returns EasResult.Success(0)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteToTrash()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.DeletedPermanently)
    }

    @Test
    fun `deleteToTrash error emits Error`() = runTest(dispatcher) {
        coEvery { actions.deleteEmails(listOf(EMAIL_ID), false) } returns EasResult.Error("del-fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteToTrash()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Error("del-fail"))
    }

    @Test
    fun `move success emits Moved, NavigateBack and clears isMoving`() = runTest(dispatcher) {
        coEvery { actions.moveEmails(listOf(EMAIL_ID), "target") } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.move("target")
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Moved)
        assertThat(events).contains(EmailDetailEvent.NavigateBack)
        assertThat(vm.uiState.value.isMoving).isFalse()
    }

    @Test
    fun `move error emits Error`() = runTest(dispatcher) {
        coEvery { actions.moveEmails(listOf(EMAIL_ID), "target") } returns EasResult.Error("move-fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.move("target")
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Error("move-fail"))
    }

    @Test
    fun `restore success emits Restored and NavigateBack`() = runTest(dispatcher) {
        coEvery { actions.restoreFromTrash(listOf(EMAIL_ID)) } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.restore()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Restored)
        assertThat(events).contains(EmailDetailEvent.NavigateBack)
    }

    // ===================== mark unread / flag / MDN =====================

    @Test
    fun `markUnread error emits Error`() = runTest(dispatcher) {
        coEvery { actions.markAsRead(EMAIL_ID, false) } returns EasResult.Error("unread-fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.markUnread()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Error("unread-fail"))
        coVerify { actions.markAsRead(EMAIL_ID, false) }
    }

    @Test
    fun `flag from the menu is saved, rejection is reported`() = runTest(dispatcher) {
        val flag = com.dedovmosol.iwomail.eas.MailFlag(com.dedovmosol.iwomail.eas.MailFlag.STATUS_COMPLETE)
        coEvery { actions.setFlag(EMAIL_ID, flag) } returns EasResult.Error("Change Status=6")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.setFlag(flag)
        advanceUntilIdle()

        coVerify { actions.setFlag(EMAIL_ID, flag) }
        assertThat(events).contains(EmailDetailEvent.Error("Change Status=6"))
    }

    @Test
    fun `toggleFlag delegates to actions`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleFlag()
        advanceUntilIdle()

        coVerify { actions.toggleFlag(EMAIL_ID) }
    }

    // ===================== read receipt =====================

    @Test
    fun `unread email with receipt request asks first and is not marked read`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(read = false)
        coEvery { actions.pendingReadReceipt(EMAIL_ID) } returns RECEIPT_TO
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.readReceiptRequestedBy).isEqualTo(RECEIPT_TO)
        coVerify(exactly = 0) { actions.markAsRead(EMAIL_ID, true) }
    }

    @Test
    fun `body with headers is loaded before the email is marked read`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(read = false, body = "")
        coEvery { actions.loadEmailBody(EMAIL_ID) } returns EasResult.Success("loaded")
        createViewModel()
        advanceUntilIdle()

        coVerifyOrder {
            actions.loadEmailBody(EMAIL_ID)
            actions.pendingReadReceipt(EMAIL_ID)
            actions.markAsRead(EMAIL_ID, true)
        }
    }

    @Test
    fun `read email is neither asked about nor marked again`() = runTest(dispatcher) {
        coEvery { actions.pendingReadReceipt(EMAIL_ID) } returns RECEIPT_TO
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.readReceiptRequestedBy).isNull()
        coVerify(exactly = 0) { actions.pendingReadReceipt(any()) }
        coVerify(exactly = 0) { actions.markAsRead(EMAIL_ID, true) }
    }

    @Test
    fun `sending the receipt emits ReadReceiptSent and closes the prompt`() = runTest(dispatcher) {
        val vm = askedViewModel()
        coEvery { actions.answerReadReceipt(EMAIL_ID, true) } returns EasResult.Success(Unit)
        val events = collectEvents(vm)

        vm.answerReadReceipt(send = true)
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.ReadReceiptSent)
        assertThat(vm.uiState.value.readReceiptRequestedBy).isNull()
        assertThat(vm.uiState.value.isAnsweringReadReceipt).isFalse()
    }

    @Test
    fun `declining quietly closes the prompt`() = runTest(dispatcher) {
        val vm = askedViewModel()
        coEvery { actions.answerReadReceipt(EMAIL_ID, false) } returns EasResult.Success(Unit)
        val events = collectEvents(vm)

        vm.answerReadReceipt(send = false)
        advanceUntilIdle()

        assertThat(events).isEmpty()
        assertThat(vm.uiState.value.readReceiptRequestedBy).isNull()
    }

    @Test
    fun `failed decline reports that the email stays unread`() = runTest(dispatcher) {
        val vm = askedViewModel()
        coEvery { actions.answerReadReceipt(EMAIL_ID, false) } returns
            EasResult.Error(com.dedovmosol.iwomail.domain.ReadReceipts.NOT_DECLINED)
        val events = collectEvents(vm)

        vm.answerReadReceipt(send = false)
        advanceUntilIdle()

        assertThat(events).containsExactly(EmailDetailEvent.ReadReceiptNotDeclined)
        assertThat(vm.uiState.value.readReceiptRequestedBy).isNull()
    }

    @Test
    fun `answer error emits Error`() = runTest(dispatcher) {
        val vm = askedViewModel()
        coEvery { actions.answerReadReceipt(EMAIL_ID, true) } returns EasResult.Error("mark-fail")
        val events = collectEvents(vm)

        vm.answerReadReceipt(send = true)
        advanceUntilIdle()

        assertThat(events).containsExactly(EmailDetailEvent.Error("mark-fail"))
    }

    @Test
    fun `answer without a pending request does nothing`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.answerReadReceipt(send = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { actions.answerReadReceipt(any(), any()) }
    }

    @Test
    fun `answer is sent once while in progress`() = runTest(dispatcher) {
        val vm = askedViewModel()
        coEvery { actions.answerReadReceipt(EMAIL_ID, true) } returns EasResult.Success(Unit)

        vm.answerReadReceipt(send = true)
        vm.answerReadReceipt(send = true)
        vm.postponeReadReceipt()
        assertThat(vm.uiState.value.isAnsweringReadReceipt).isTrue()
        advanceUntilIdle()

        coVerify(exactly = 1) { actions.answerReadReceipt(EMAIL_ID, true) }
    }

    @Test
    fun `postponing closes the prompt without answering`() = runTest(dispatcher) {
        val vm = askedViewModel()

        vm.postponeReadReceipt()
        advanceUntilIdle()

        assertThat(vm.uiState.value.readReceiptRequestedBy).isNull()
        coVerify(exactly = 0) { actions.answerReadReceipt(any(), any()) }
        coVerify(exactly = 0) { actions.markAsRead(EMAIL_ID, true) }
    }

    // ===================== crash resistance (exception handling) =====================

    @Test
    fun `openEmail does not crash when markAsRead throws exception`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(read = false)
        coEvery { actions.markAsRead(EMAIL_ID, true) } throws RuntimeException("EAS protocol error")
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.bodyLoadError).isNull()
    }

    @Test
    fun `openEmail does not crash when getEmailSync throws SQLiteException`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } throws RuntimeException("database corruption")
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.bodyLoadError).isEqualTo(BodyLoadError.LoadFailed)
    }

    @Test
    fun `deleteToTrash does not crash when actions throws exception`() = runTest(dispatcher) {
        coEvery { actions.deleteEmails(listOf(EMAIL_ID), false) } throws RuntimeException("network failure")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteToTrash()
        advanceUntilIdle()

        assertThat(events.any { it is EmailDetailEvent.Error }).isTrue()
        assertThat(vm.uiState.value.isDeleting).isFalse()
    }

    @Test
    fun `move does not crash when actions throws exception`() = runTest(dispatcher) {
        coEvery { actions.moveEmails(listOf(EMAIL_ID), "target") } throws RuntimeException("EAS sync error")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.move("target")
        advanceUntilIdle()

        assertThat(events.any { it is EmailDetailEvent.Error }).isTrue()
        assertThat(vm.uiState.value.isMoving).isFalse()
    }

    @Test
    fun `restore does not crash when actions throws exception`() = runTest(dispatcher) {
        coEvery { actions.restoreFromTrash(listOf(EMAIL_ID)) } throws RuntimeException("server error")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.restore()
        advanceUntilIdle()

        assertThat(events.any { it is EmailDetailEvent.Error }).isTrue()
        assertThat(vm.uiState.value.isRestoring).isFalse()
    }

    @Test
    fun `answerReadReceipt does not crash when actions throws exception`() = runTest(dispatcher) {
        val vm = askedViewModel()
        coEvery { actions.answerReadReceipt(EMAIL_ID, true) } throws RuntimeException("EWS failed")
        val events = collectEvents(vm)

        vm.answerReadReceipt(send = true)
        advanceUntilIdle()

        assertThat(events.any { it is EmailDetailEvent.Error }).isTrue()
        assertThat(vm.uiState.value.isAnsweringReadReceipt).isFalse()
        assertThat(vm.uiState.value.readReceiptRequestedBy).isNull()
    }

    @Test
    fun `markUnread does not crash when actions throws exception`() = runTest(dispatcher) {
        coEvery { actions.markAsRead(EMAIL_ID, false) } throws RuntimeException("db error")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.markUnread()
        advanceUntilIdle()

        assertThat(events.any { it is EmailDetailEvent.Error }).isTrue()
    }

    // ===================== permanent delete wrapper =====================

    @Test
    fun `deleteEmailPermanently delegates to repository with progress`() = runTest(dispatcher) {
        val ids = listOf(EMAIL_ID)
        coEvery { mailRepo.deleteEmailsPermanentlyWithProgress(ids, any()) } returns EasResult.Success(1)
        val vm = createViewModel()
        advanceUntilIdle()

        val result = vm.deleteEmailPermanently(ids) { _, _ -> }

        assertThat(result).isEqualTo(EasResult.Success(1))
        coVerify { mailRepo.deleteEmailsPermanentlyWithProgress(ids, any()) }
    }

    // ===================== helpers =====================

    /** Открыто непрочитанное письмо с запросом отчёта: VM ждёт ответа. */
    private fun TestScope.askedViewModel(): EmailDetailViewModel {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(read = false)
        coEvery { actions.pendingReadReceipt(EMAIL_ID) } returns RECEIPT_TO
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.readReceiptRequestedBy).isEqualTo(RECEIPT_TO)
        return vm
    }

    // ===================== отписка от рассылки =====================

    private val newsletter = email().copy(
        listUnsubscribe = "<mailto:leave@list.example>, <https://list.example/u/1>",
        listUnsubscribeOneClick = true
    )

    private fun unsubscribeVm(
        result: com.dedovmosol.iwomail.domain.UnsubscribeResult,
        target: EmailEntity = newsletter
    ): Pair<EmailDetailViewModel, com.dedovmosol.iwomail.domain.Unsubscriber> {
        every { mailRepo.getEmail(EMAIL_ID) } returns flowOf(target)
        val unsubscriber = mockk<com.dedovmosol.iwomail.domain.Unsubscriber>()
        coEvery { unsubscriber.unsubscribe(any(), any()) } returns result
        return EmailDetailViewModel(EMAIL_ID, actions, mailRepo, accountRepo, dispatcher, unsubscriber = unsubscriber) to unsubscriber
    }

    @Test
    fun `unsubscribe one-click succeeds once and hides the link`() = runTest(dispatcher) {
        val (vm, unsubscriber) = unsubscribeVm(com.dedovmosol.iwomail.domain.UnsubscribeResult.Done)
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.unsubscribe()
        advanceUntilIdle()
        vm.unsubscribe() // повторное нажатие — запроса больше нет
        advanceUntilIdle()

        coVerify(exactly = 1) {
            unsubscriber.unsubscribe(ACCOUNT_ID, match { it.method is com.dedovmosol.iwomail.eas.ListUnsubscribe.Method.OneClick })
        }
        assertThat(vm.uiState.value.unsubscribed).isTrue()
        assertThat(vm.uiState.value.isUnsubscribing).isFalse()
        assertThat(events).containsExactly(EmailDetailEvent.Unsubscribed)
    }

    @Test
    fun `unsubscribe started on the screen finishes after it is closed`() = runTest(dispatcher) {
        every { mailRepo.getEmail(EMAIL_ID) } returns flowOf(newsletter)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var finished = false
        val unsubscriber = mockk<com.dedovmosol.iwomail.domain.Unsubscriber>()
        coEvery { unsubscriber.unsubscribe(any(), any()) } coAnswers {
            gate.await()
            finished = true
            com.dedovmosol.iwomail.domain.UnsubscribeResult.Done
        }
        val detached = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val vm = EmailDetailViewModel(EMAIL_ID, actions, mailRepo, accountRepo, dispatcher, unsubscriber = unsubscriber, detachedScope = detached)
        advanceUntilIdle()

        vm.unsubscribe()
        advanceUntilIdle()
        vm.viewModelScope.cancel() // «Отписаться» и сразу «Назад»
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(finished).isTrue()
        detached.cancel()
    }

    @Test
    fun `failed one-click opens the page and says so`() = runTest(dispatcher) {
        val (vm, _) = unsubscribeVm(com.dedovmosol.iwomail.domain.UnsubscribeResult.OpenPage("https://list.example/u/1"))
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.unsubscribe()
        advanceUntilIdle()

        assertThat(events).containsExactly(EmailDetailEvent.OpenUnsubscribePage("https://list.example/u/1", afterFailure = true))
        assertThat(vm.uiState.value.unsubscribed).isFalse()
    }

    @Test
    fun `web-only unsubscribe opens the page without a failure note`() = runTest(dispatcher) {
        val webOnly = email().copy(listUnsubscribe = "<https://list.example/u/1>")
        val (vm, _) = unsubscribeVm(com.dedovmosol.iwomail.domain.UnsubscribeResult.OpenPage("https://list.example/u/1"), webOnly)
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.unsubscribe()
        advanceUntilIdle()

        assertThat(events).containsExactly(EmailDetailEvent.OpenUnsubscribePage("https://list.example/u/1", afterFailure = false))
    }

    @Test
    fun `email without List-Unsubscribe does nothing`() = runTest(dispatcher) {
        val (vm, unsubscriber) = unsubscribeVm(com.dedovmosol.iwomail.domain.UnsubscribeResult.Done, email())
        advanceUntilIdle()

        vm.unsubscribe()
        advanceUntilIdle()

        coVerify(exactly = 0) { unsubscriber.unsubscribe(any(), any()) }
    }

    @Test
    fun `email opened before unsubscribe support gets its headers checked once in the background`() = runTest(dispatcher) {
        // Тело уже в базе (открывали раньше), отписка не проверялась (null)
        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { actions.ensureListUnsubscribe(EMAIL_ID) }
    }

    @Test
    fun `checked or freshly loaded emails do not ask for headers separately`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email().copy(listUnsubscribe = "")
        createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { actions.ensureListUnsubscribe(any()) }

        // Тело грузится сейчас — заголовки приходят с ним
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email(body = "")
        coEvery { actions.loadEmailBody(EMAIL_ID) } returns EasResult.Success("<p>x</p>")
        createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { actions.ensureListUnsubscribe(any()) }
    }

    @Test
    fun `RSS feed item does not ask for unsubscribe headers`() = runTest(dispatcher) {
        coEvery { actions.getEmailSync(EMAIL_ID) } returns email().copy(messageClass = "IPM.Post.RSS")
        createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { actions.ensureListUnsubscribe(any()) }
    }

    @Test
    fun `failed header check does not break opening the email`() = runTest(dispatcher) {
        coEvery { actions.ensureListUnsubscribe(EMAIL_ID) } throws IllegalStateException("db closed")
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.email?.id).isEqualTo(EMAIL_ID)
        assertThat(vm.uiState.value.bodyLoadError).isNull()
    }

    @Test
    fun `junk folder is reported so the link is hidden there`() = runTest(dispatcher) {
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(listOf(folder(INBOX_ID, FolderType.JUNK_EMAIL)))
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.unsubscribeOffered).isFalse()
    }

    // ===================== .eml =====================

    /** Файл SAF в памяти: что записано и что убрано. */
    private class FakeDocuments(private val writeOk: Boolean = true) : EmailDetailViewModel.DocumentSink {
        val written = mutableListOf<ByteArray>()
        var discarded = 0
        override suspend fun write(uri: android.net.Uri, bytes: ByteArray): Boolean {
            written += bytes
            return writeOk
        }
        override suspend fun discard(uri: android.net.Uri) {
            discarded++
        }
    }

    private fun emlVm(
        mime: EasResult<ByteArray>,
        documents: FakeDocuments,
        detached: kotlinx.coroutines.CoroutineScope? = null
    ): EmailDetailViewModel {
        coEvery { mailRepo.fetchMessageMime(EMAIL_ID) } returns mime
        return EmailDetailViewModel(EMAIL_ID, actions, mailRepo, accountRepo, dispatcher, documents = documents, detachedScope = detached)
    }

    @Test
    fun `eml is written to the chosen file`() = runTest(dispatcher) {
        val documents = FakeDocuments()
        val vm = emlVm(EasResult.Success("From: a@b.c\r\n\r\nhi".toByteArray()), documents)
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.saveEml(mockk(relaxed = true))
        advanceUntilIdle()

        assertThat(documents.written.single().decodeToString()).isEqualTo("From: a@b.c\r\n\r\nhi")
        assertThat(documents.discarded).isEqualTo(0)
        assertThat(events).containsExactly(EmailDetailEvent.EmlSaved)
        assertThat(vm.uiState.value.isSavingEml).isFalse()
    }

    @Test
    fun `server error, empty MIME or failed write remove the created file`() = runTest(dispatcher) {
        val cases = listOf(
            Triple(EasResult.Error("HTTP 503") as EasResult<ByteArray>, FakeDocuments(), EmailDetailEvent.EmlFailed("HTTP 503")),
            Triple(EasResult.Success(ByteArray(0)), FakeDocuments(), EmailDetailEvent.EmlFailed(null)),
            Triple(EasResult.Success(byteArrayOf(1)), FakeDocuments(writeOk = false), EmailDetailEvent.EmlFailed(null))
        )
        for ((mime, documents, expected) in cases) {
            val vm = emlVm(mime, documents)
            val events = collectEvents(vm)
            advanceUntilIdle()

            vm.saveEml(mockk(relaxed = true))
            advanceUntilIdle()

            assertThat(events).containsExactly(expected)
            // Файл, созданный выбором места, не остаётся пустым или недописанным
            assertThat(documents.discarded).isEqualTo(1)
        }
    }

    @Test
    fun `eml export finishes after the screen is closed`() = runTest(dispatcher) {
        val documents = FakeDocuments()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { mailRepo.fetchMessageMime(EMAIL_ID) } coAnswers { gate.await(); EasResult.Success(byteArrayOf(7)) }
        // Отдельная область, как процессная в приложении: закрытие экрана её не отменяет
        val detached = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val vm = EmailDetailViewModel(EMAIL_ID, actions, mailRepo, accountRepo, dispatcher, documents = documents, detachedScope = detached)
        advanceUntilIdle()

        vm.saveEml(mockk(relaxed = true))
        advanceUntilIdle()
        vm.viewModelScope.cancel() // экран закрыт, пока письмо ещё грузится
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(documents.written.single().toList()).containsExactly(7.toByte())
        detached.cancel()
    }

    private fun TestScope.collectEvents(vm: EmailDetailViewModel): List<EmailDetailEvent> {
        val received = mutableListOf<EmailDetailEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { received.add(it) }
        }
        return received
    }

    private fun accountMock(id: Long): AccountEntity {
        val account = mockk<AccountEntity>()
        every { account.id } returns id
        return account
    }

    private fun email(
        id: String = EMAIL_ID,
        read: Boolean = true,
        body: String = "hi",
        folderId: String = INBOX_ID,
        preview: String = ""
    ): EmailEntity = EmailEntity(
        id = id,
        accountId = ACCOUNT_ID,
        folderId = folderId,
        serverId = "srv",
        from = "a@b.c",
        to = "x@y.z",
        subject = "s",
        body = body,
        dateReceived = 0L,
        read = read,
        preview = preview
    )

    private fun folder(id: String, type: Int): FolderEntity = FolderEntity(
        id = id,
        accountId = ACCOUNT_ID,
        serverId = id,
        displayName = "F",
        parentId = "",
        type = type
    )

    private companion object {
        const val ACCOUNT_ID = 1L
        const val EMAIL_ID = "acc_msg1"
        const val INBOX_ID = "inbox"
        const val RECEIPT_TO = "boss@corp.example"
    }
}

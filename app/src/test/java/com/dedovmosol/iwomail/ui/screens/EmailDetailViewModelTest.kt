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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        coEvery { actions.loadInlineImages(any(), any(), any(), any(), any(), any()) } returns emptyMap()
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
        val vm = createViewModel()
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
        coEvery { actions.loadInlineImages(any(), any(), any(), any(), any(), any()) } returns mapOf("cid" to "data:img")
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.inlineImages).containsEntry("cid", "data:img")
        coVerify { actions.loadInlineImages(any(), any(), any(), any(), any(), any()) }
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
    fun `toggleFlag delegates to actions`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleFlag()
        advanceUntilIdle()

        coVerify { actions.toggleFlag(EMAIL_ID) }
    }

    @Test
    fun `sendMdn success emits ReadReceiptSent`() = runTest(dispatcher) {
        coEvery { actions.sendMdn(EMAIL_ID) } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.sendMdn()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.ReadReceiptSent)
        assertThat(vm.uiState.value.isSendingMdn).isFalse()
    }

    @Test
    fun `sendMdn error emits Error`() = runTest(dispatcher) {
        coEvery { actions.sendMdn(EMAIL_ID) } returns EasResult.Error("mdn-fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.sendMdn()
        advanceUntilIdle()

        assertThat(events).contains(EmailDetailEvent.Error("mdn-fail"))
    }

    @Test
    fun `dismissMdn marks mdn as sent`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.dismissMdn()
        advanceUntilIdle()

        coVerify { actions.markMdnSent(EMAIL_ID) }
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

        assertThat(events).anyMatch { it is EmailDetailEvent.Error }
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

        assertThat(events).anyMatch { it is EmailDetailEvent.Error }
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

        assertThat(events).anyMatch { it is EmailDetailEvent.Error }
        assertThat(vm.uiState.value.isRestoring).isFalse()
    }

    @Test
    fun `sendMdn does not crash when actions throws exception`() = runTest(dispatcher) {
        coEvery { actions.sendMdn(EMAIL_ID) } throws RuntimeException("MDN send failed")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.sendMdn()
        advanceUntilIdle()

        assertThat(events).anyMatch { it is EmailDetailEvent.Error }
        assertThat(vm.uiState.value.isSendingMdn).isFalse()
    }

    @Test
    fun `markUnread does not crash when actions throws exception`() = runTest(dispatcher) {
        coEvery { actions.markAsRead(EMAIL_ID, false) } throws RuntimeException("db error")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.markUnread()
        advanceUntilIdle()

        assertThat(events).anyMatch { it is EmailDetailEvent.Error }
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
    }
}

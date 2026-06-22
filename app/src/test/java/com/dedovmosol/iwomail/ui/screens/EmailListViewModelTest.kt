package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * Юнит-тесты для [EmailListViewModel].
 *
 * VM тестируется БЕЗ Robolectric благодаря конструкторной инъекции (DIP): [MailRepository] и
 * [AccountRepository] — MockK-моки, IO-диспетчер подменяется тестовым, поэтому всё детерминировано
 * через [advanceUntilIdle]. Side-effect'ы (тосты) проверяются через канал one-shot событий.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var mailRepo: MailRepository
    private lateinit var accountRepo: AccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mailRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        every { accountRepo.activeAccount } returns flowOf(accountMock(ACCOUNT_ID))
        // Источники писем для всех вариантов экрана.
        every { mailRepo.getEmails(any()) } returns flowOf(listOf(email("a"), email("b")))
        every { mailRepo.getFlaggedEmails(ACCOUNT_ID) } returns flowOf(listOf(email("a")))
        every { mailRepo.getTodayEmailsAcrossFolders(ACCOUNT_ID) } returns flowOf(listOf(email("a")))
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(listOf(folderMock(INBOX_ID, FolderType.INBOX)))
        coEvery { mailRepo.syncEmails(any(), any(), any()) } returns EasResult.Success(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        folderId: String = INBOX_ID,
        initialFilter: MailFilter = MailFilter.ALL,
        initialDateFilter: EmailDateFilter = EmailDateFilter.ALL
    ): EmailListViewModel =
        EmailListViewModel(folderId, mailRepo, accountRepo, initialFilter, initialDateFilter, dispatcher)

    // ===================== init / loading =====================

    @Test
    fun `init loads emails, folders, current folder and accountId`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.accountId).isEqualTo(ACCOUNT_ID)
        assertThat(s.emails.map { it.id }).containsExactly("a", "b")
        assertThat(s.folders.map { it.id }).containsExactly(INBOX_ID)
        assertThat(s.folder?.id).isEqualTo(INBOX_ID)
    }

    @Test
    fun `initial filter opens the filter panel and sets state`() = runTest(dispatcher) {
        val vm = createViewModel(initialFilter = MailFilter.UNREAD, initialDateFilter = EmailDateFilter.TODAY)
        val s = vm.uiState.value
        assertThat(s.mailFilter).isEqualTo(MailFilter.UNREAD)
        assertThat(s.dateFilter).isEqualTo(EmailDateFilter.TODAY)
        assertThat(s.showFilters).isTrue()
    }

    @Test
    fun `favorites uses flagged emails source and keeps folder null`() = runTest(dispatcher) {
        val vm = createViewModel(folderId = "favorites")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(vm.isFavorites).isTrue()
        assertThat(s.folder).isNull()
        assertThat(s.emails.map { it.id }).containsExactly("a")
        verify(exactly = 0) { mailRepo.getEmails(any()) }
    }

    @Test
    fun `today-all uses cross-folder source`() = runTest(dispatcher) {
        val vm = createViewModel(folderId = "TODAY_ALL")
        advanceUntilIdle()
        assertThat(vm.isTodayAll).isTrue()
        assertThat(vm.uiState.value.emails.map { it.id }).containsExactly("a")
    }

    @Test
    fun `drafts folder auto-syncs exactly once`() = runTest(dispatcher) {
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(listOf(folderMock(DRAFTS_ID, FolderType.DRAFTS)))
        val vm = createViewModel(folderId = DRAFTS_ID)
        advanceUntilIdle()
        coVerify(exactly = 1) { mailRepo.syncEmails(ACCOUNT_ID, DRAFTS_ID, false) }
    }

    @Test
    fun `inbox folder does not auto-sync`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { mailRepo.syncEmails(any(), any(), any()) }
    }

    // ===================== sync / refresh =====================

    @Test
    fun `refresh runs sync and toggles isRefreshing`() = runTest(dispatcher) {
        coEvery { mailRepo.syncEmails(ACCOUNT_ID, INBOX_ID, false) } returns EasResult.Success(3)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        coVerify { mailRepo.syncEmails(ACCOUNT_ID, INBOX_ID, false) }
        assertThat(vm.uiState.value.isRefreshing).isFalse()
        assertThat(vm.uiState.value.errorMessage).isNull()
    }

    @Test
    fun `refresh error sets errorMessage and dismissError clears it`() = runTest(dispatcher) {
        coEvery { mailRepo.syncEmails(ACCOUNT_ID, INBOX_ID, false) } returns EasResult.Error("boom")
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.uiState.value.errorMessage).isEqualTo("boom")

        vm.dismissError()
        assertThat(vm.uiState.value.errorMessage).isNull()
    }

    @Test
    fun `refresh is a no-op on favorites`() = runTest(dispatcher) {
        val vm = createViewModel(folderId = "favorites")
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.syncEmails(any(), any(), any()) }
    }

    // ===================== filters =====================

    @Test
    fun `filter setters and clear update state`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setMailFilter(MailFilter.STARRED)
        vm.setDateFilter(EmailDateFilter.WEEK)
        assertThat(vm.uiState.value.mailFilter).isEqualTo(MailFilter.STARRED)
        assertThat(vm.uiState.value.dateFilter).isEqualTo(EmailDateFilter.WEEK)

        vm.clearFilters()
        assertThat(vm.uiState.value.mailFilter).isEqualTo(MailFilter.ALL)
        assertThat(vm.uiState.value.dateFilter).isEqualTo(EmailDateFilter.ALL)
    }

    @Test
    fun `toggleFilters flips visibility`() = runTest(dispatcher) {
        val vm = createViewModel()
        val initial = vm.uiState.value.showFilters
        vm.toggleFilters()
        assertThat(vm.uiState.value.showFilters).isEqualTo(!initial)
    }

    // ===================== selection =====================

    @Test
    fun `toggleSelection adds then removes id`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.toggleSelection("x")
        assertThat(vm.uiState.value.selectedIds).containsExactly("x")
        vm.toggleSelection("x")
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `setSelection replaces and clearSelection empties`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setSelection(setOf("b", "c"))
        assertThat(vm.uiState.value.selectedIds).containsExactly("b", "c")
        vm.clearSelection()
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `selectAll selects all then deselects when already all selected`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.selectAll(listOf("a", "b"))
        assertThat(vm.uiState.value.selectedIds).containsExactly("a", "b")
        vm.selectAll(listOf("a", "b"))
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    // ===================== batch operations =====================

    @Test
    fun `deleteSelectedToTrash emits MovedToTrash and clears selection`() = runTest(dispatcher) {
        coEvery { mailRepo.moveToTrash(any()) } returns EasResult.Success(2)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a", "b"))

        vm.deleteSelectedToTrash()
        advanceUntilIdle()

        assertThat(events).contains(EmailListEvent.MovedToTrash(2))
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        coVerify { mailRepo.moveToTrash(match { it.size == 2 }) }
    }

    @Test
    fun `deleteSelectedToTrash is a no-op when nothing selected`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteSelectedToTrash()
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.moveToTrash(any()) }
    }

    @Test
    fun `deleteSelectedDrafts emits DeletedPermanently`() = runTest(dispatcher) {
        coEvery { mailRepo.deleteDrafts(any()) } returns EasResult.Success(1)
        val vm = createViewModel(folderId = DRAFTS_ID)
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a"))

        vm.deleteSelectedDrafts()
        advanceUntilIdle()

        assertThat(events).contains(EmailListEvent.DeletedPermanently(1))
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `markSelectedAsRead clears selection and calls repository`() = runTest(dispatcher) {
        coEvery { mailRepo.markAsReadBatch(any(), true) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSelection(setOf("a", "b"))

        vm.markSelectedAsRead(true)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).isEmpty()
        coVerify { mailRepo.markAsReadBatch(match { it.size == 2 }, true) }
    }

    @Test
    fun `markSelectedAsRead error emits Error`() = runTest(dispatcher) {
        coEvery { mailRepo.markAsReadBatch(any(), false) } returns EasResult.Error("read-fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a"))

        vm.markSelectedAsRead(false)
        advanceUntilIdle()

        assertThat(events).contains(EmailListEvent.Error("read-fail"))
    }

    @Test
    fun `starSelected toggles flag for each selected and clears selection`() = runTest(dispatcher) {
        coEvery { mailRepo.toggleFlag(any()) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSelection(setOf("a", "b"))

        vm.starSelected()
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).isEmpty()
        coVerify { mailRepo.toggleFlag("a") }
        coVerify { mailRepo.toggleFlag("b") }
    }

    @Test
    fun `toggleFlag delegates single email to repository`() = runTest(dispatcher) {
        coEvery { mailRepo.toggleFlag("a") } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleFlag("a")
        advanceUntilIdle()

        coVerify { mailRepo.toggleFlag("a") }
    }

    @Test
    fun `moveSelectedTo emits Moved and clears selection`() = runTest(dispatcher) {
        coEvery { mailRepo.moveEmails(any(), "target") } returns EasResult.Success(2)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a", "b"))

        vm.moveSelectedTo("target")
        advanceUntilIdle()

        assertThat(events).contains(EmailListEvent.Moved(2))
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `restoreSelected emits Restored`() = runTest(dispatcher) {
        coEvery { mailRepo.restoreFromTrash(any()) } returns EasResult.Success(1)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a"))

        vm.restoreSelected()
        advanceUntilIdle()

        assertThat(events).contains(EmailListEvent.Restored(1))
    }

    @Test
    fun `moveSelectedToSpam emits MovedToSpam`() = runTest(dispatcher) {
        coEvery { mailRepo.moveToSpam(any()) } returns EasResult.Success(3)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a", "b"))

        vm.moveSelectedToSpam()
        advanceUntilIdle()

        assertThat(events).contains(EmailListEvent.MovedToSpam(3))
    }

    @Test
    fun `move error emits Error event`() = runTest(dispatcher) {
        coEvery { mailRepo.moveEmails(any(), any()) } returns EasResult.Error("move-fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a"))

        vm.moveSelectedTo("target")
        advanceUntilIdle()

        assertThat(events).contains(EmailListEvent.Error("move-fail"))
    }

    // ===================== progress-bar wrapper (вызывается из DeletionController) =====================

    @Test
    fun `deleteEmailsPermanently delegates to repository with progress`() = runTest(dispatcher) {
        val ids = listOf("a", "b")
        coEvery { mailRepo.deleteEmailsPermanentlyWithProgress(ids, any()) } returns EasResult.Success(2)
        val vm = createViewModel()
        advanceUntilIdle()

        val result = vm.deleteEmailsPermanently(ids) { _, _ -> }

        assertThat(result).isEqualTo(EasResult.Success(2))
        coVerify { mailRepo.deleteEmailsPermanentlyWithProgress(ids, any()) }
    }

    // ===================== helpers =====================

    private fun TestScope.collectEvents(vm: EmailListViewModel): List<EmailListEvent> {
        val received = mutableListOf<EmailListEvent>()
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

    private fun email(id: String): EmailEntity {
        val e = mockk<EmailEntity>(relaxed = true)
        every { e.id } returns id
        return e
    }

    private fun folderMock(id: String, type: Int): FolderEntity {
        val f = mockk<FolderEntity>(relaxed = true)
        every { f.id } returns id
        every { f.type } returns type
        every { f.accountId } returns ACCOUNT_ID
        return f
    }

    private companion object {
        const val ACCOUNT_ID = 1L
        const val INBOX_ID = "inbox"
        const val DRAFTS_ID = "drafts"
    }
}

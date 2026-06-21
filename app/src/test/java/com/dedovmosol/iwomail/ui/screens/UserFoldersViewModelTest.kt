package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Юнит-тесты для [UserFoldersViewModel].
 *
 * VM тестируется БЕЗ Robolectric благодаря конструкторной инъекции (DIP): [MailRepository] и
 * [AccountRepository] — MockK-моки, IO-диспетчер подменяется тестовым, поэтому всё детерминировано
 * через [advanceUntilIdle]. Side-effect'ы (тосты) проверяются через канал one-shot событий.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserFoldersViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var mailRepo: MailRepository
    private lateinit var accountRepo: AccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mailRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        every { accountRepo.activeAccount } returns flowOf(accountMock(ACCOUNT_ID))
        // По умолчанию: один пользовательский + одна системная папка → авто-синхронизация не запускается.
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(
            listOf(folder("u1", type = 12, name = "Work"), folder("sys", type = 2, name = "Inbox"))
        )
        coEvery { mailRepo.syncFolders(ACCOUNT_ID) } returns EasResult.Success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): UserFoldersViewModel =
        UserFoldersViewModel(mailRepo, accountRepo, dispatcher)

    // ===================== init / loading =====================

    @Test
    fun `init filters to user folders only and sorts by name`() = runTest(dispatcher) {
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(
            listOf(
                folder("u2", type = 1, name = "Zeta"),
                folder("u1", type = 12, name = "Alpha"),
                folder("sys", type = 2, name = "Inbox")    // системная — исключается
            )
        )
        val vm = createViewModel()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.accountId).isEqualTo(ACCOUNT_ID)
        // Только пользовательские (type 1 и 12), отсортированы по имени.
        assertThat(s.folders.map { it.id }).containsExactly("u1", "u2").inOrder()
        assertThat(s.isInitialLoadDone).isTrue()
    }

    @Test
    fun `auto-sync runs once when there are no local user folders`() = runTest(dispatcher) {
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(emptyList())
        val vm = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { mailRepo.syncFolders(ACCOUNT_ID) }
        assertThat(vm.uiState.value.isInitialLoadDone).isTrue()
    }

    @Test
    fun `auto-sync is skipped when local folders exist`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { mailRepo.syncFolders(any()) }
    }

    @Test
    fun `no active account marks load done and makes syncFolders a no-op`() = runTest(dispatcher) {
        every { accountRepo.activeAccount } returns flowOf(null)
        val vm = createViewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.accountId).isEqualTo(0L)
        assertThat(vm.uiState.value.isInitialLoadDone).isTrue()

        vm.syncFolders()
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.syncFolders(any()) }
    }

    // ===================== sync =====================

    @Test
    fun `syncFolders success emits Synced`() = runTest(dispatcher) {
        coEvery { mailRepo.syncFolders(ACCOUNT_ID) } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.syncFolders()
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.Synced)
        assertThat(vm.uiState.value.isSyncing).isFalse()
    }

    @Test
    fun `syncFolders error emits Error`() = runTest(dispatcher) {
        coEvery { mailRepo.syncFolders(ACCOUNT_ID) } returns EasResult.Error("boom")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.syncFolders()
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.Error("boom"))
    }

    // ===================== create =====================

    @Test
    fun `createFolder success emits FolderCreated`() = runTest(dispatcher) {
        coEvery { mailRepo.createFolder(ACCOUNT_ID, "Docs") } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.createFolder("Docs")
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.FolderCreated)
        assertThat(vm.uiState.value.isCreatingFolder).isFalse()
        coVerify { mailRepo.createFolder(ACCOUNT_ID, "Docs") }
    }

    @Test
    fun `createFolder trims name before sending`() = runTest(dispatcher) {
        coEvery { mailRepo.createFolder(ACCOUNT_ID, "Docs") } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.createFolder("  Docs  ")
        advanceUntilIdle()

        coVerify { mailRepo.createFolder(ACCOUNT_ID, "Docs") }
    }

    @Test
    fun `createFolder guards against double-tap`() = runTest(dispatcher) {
        coEvery { mailRepo.createFolder(ACCOUNT_ID, "Docs") } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.createFolder("Docs")  // ставит isCreatingFolder=true синхронно
        vm.createFolder("Docs")  // должен быть проигнорирован
        advanceUntilIdle()

        coVerify(exactly = 1) { mailRepo.createFolder(ACCOUNT_ID, "Docs") }
    }

    @Test
    fun `createFolder with blank name is a no-op`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.createFolder("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.createFolder(any(), any()) }
    }

    @Test
    fun `createFolder error emits Error and clears creating flag`() = runTest(dispatcher) {
        coEvery { mailRepo.createFolder(ACCOUNT_ID, "Docs") } returns EasResult.Error("nope")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.createFolder("Docs")
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.Error("nope"))
        assertThat(vm.uiState.value.isCreatingFolder).isFalse()
    }

    // ===================== rename =====================

    @Test
    fun `renameFolder success emits FolderRenamed (trimmed)`() = runTest(dispatcher) {
        coEvery { mailRepo.renameFolder(ACCOUNT_ID, "u1", "Renamed") } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.renameFolder("u1", "  Renamed ")
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.FolderRenamed)
        coVerify { mailRepo.renameFolder(ACCOUNT_ID, "u1", "Renamed") }
    }

    @Test
    fun `renameFolder error emits Error`() = runTest(dispatcher) {
        coEvery { mailRepo.renameFolder(ACCOUNT_ID, "u1", "X") } returns EasResult.Error("fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.renameFolder("u1", "X")
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.Error("fail"))
    }

    @Test
    fun `renameFolder with blank name is a no-op`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.renameFolder("u1", "  ")
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.renameFolder(any(), any(), any()) }
    }

    // ===================== single delete =====================

    @Test
    fun `deleteFolder success emits FolderDeleted`() = runTest(dispatcher) {
        coEvery { mailRepo.deleteFolder(ACCOUNT_ID, "u1") } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteFolder("u1")
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.FolderDeleted)
        coVerify { mailRepo.deleteFolder(ACCOUNT_ID, "u1") }
    }

    @Test
    fun `deleteFolder error emits Error`() = runTest(dispatcher) {
        coEvery { mailRepo.deleteFolder(ACCOUNT_ID, "u1") } returns EasResult.Error("no")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteFolder("u1")
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.Error("no"))
    }

    // ===================== batch delete =====================

    @Test
    fun `deleteSelectedFolders deletes each, clears selection and reports count`() = runTest(dispatcher) {
        coEvery { mailRepo.deleteFolder(ACCOUNT_ID, any()) } returns EasResult.Success(Unit)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("u1", "u2"))

        vm.deleteSelectedFolders()
        // Выделение снято и прогресс инициализирован синхронно (до запуска цикла).
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        assertThat(vm.uiState.value.batchDeleteProgress).isEqualTo(0 to 2)

        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.FoldersDeleted(2))
        assertThat(vm.uiState.value.batchDeleteProgress).isNull()
        coVerify(exactly = 1) { mailRepo.deleteFolder(ACCOUNT_ID, "u1") }
        coVerify(exactly = 1) { mailRepo.deleteFolder(ACCOUNT_ID, "u2") }
    }

    @Test
    fun `deleteSelectedFolders reports partial success and first error`() = runTest(dispatcher) {
        coEvery { mailRepo.deleteFolder(ACCOUNT_ID, "u1") } returns EasResult.Success(Unit)
        coEvery { mailRepo.deleteFolder(ACCOUNT_ID, "u2") } returns EasResult.Error("denied")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("u1", "u2"))

        vm.deleteSelectedFolders()
        advanceUntilIdle()

        assertThat(events).contains(UserFoldersEvent.FoldersDeleted(1))
        assertThat(events).contains(UserFoldersEvent.Error("denied"))
    }

    @Test
    fun `deleteSelectedFolders with empty selection just clears`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteSelectedFolders()
        advanceUntilIdle()

        coVerify(exactly = 0) { mailRepo.deleteFolder(any(), any()) }
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    // ===================== selection =====================

    @Test
    fun `toggleSelection adds then removes id`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.toggleSelection("u1")
        assertThat(vm.uiState.value.selectedIds).containsExactly("u1")
        vm.toggleSelection("u1")
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `setSelection replaces and clearSelection empties`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setSelection(setOf("u1", "u2"))
        assertThat(vm.uiState.value.selectedIds).containsExactly("u1", "u2")
        assertThat(vm.uiState.value.isSelectionMode).isTrue()
        vm.clearSelection()
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        assertThat(vm.uiState.value.isSelectionMode).isFalse()
    }

    @Test
    fun `toggleSelectAll selects all then deselects`() = runTest(dispatcher) {
        every { mailRepo.getFolders(ACCOUNT_ID) } returns flowOf(
            listOf(folder("u1", type = 12, name = "A"), folder("u2", type = 1, name = "B"))
        )
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleSelectAll()
        assertThat(vm.uiState.value.selectedIds).containsExactly("u1", "u2")
        vm.toggleSelectAll()
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `stale selection ids are dropped when folder list changes`() = runTest(dispatcher) {
        val foldersFlow = MutableStateFlow(
            listOf(folder("u1", type = 12, name = "A"), folder("u2", type = 12, name = "B"))
        )
        every { mailRepo.getFolders(ACCOUNT_ID) } returns foldersFlow
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setSelection(setOf("u1", "u2"))

        // Папка u2 исчезла после sync — её id должен уйти из выделения.
        foldersFlow.value = listOf(folder("u1", type = 12, name = "A"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).containsExactly("u1")
    }

    // ===================== helpers =====================

    private fun TestScope.collectEvents(vm: UserFoldersViewModel): List<UserFoldersEvent> {
        val received = mutableListOf<UserFoldersEvent>()
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

    private fun folder(id: String, type: Int, name: String): FolderEntity {
        val f = mockk<FolderEntity>(relaxed = true)
        every { f.id } returns id
        every { f.type } returns type
        every { f.displayName } returns name
        return f
    }

    private companion object {
        const val ACCOUNT_ID = 1L
    }
}

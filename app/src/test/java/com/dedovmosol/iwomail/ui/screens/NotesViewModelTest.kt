package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.NoteEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.NoteRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
 * Юнит-тесты для [NotesViewModel].
 *
 * VM тестируется БЕЗ Robolectric благодаря конструкторной инъекции (DIP): [NoteRepository] и
 * [AccountRepository] — MockK-моки, IO-диспетчер подменяется тестовым, поэтому всё детерминировано
 * через [advanceUntilIdle]. Side-effect'ы (тосты) проверяются через канал one-shot событий.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var noteRepo: NoteRepository
    private lateinit var accountRepo: AccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        noteRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        every { accountRepo.activeAccount } returns flowOf(accountMock(ACCOUNT_ID))
        // По умолчанию: один активный + один удалённый → авто-синхронизация не запускается.
        every { noteRepo.getNotes(ACCOUNT_ID) } returns flowOf(listOf(note("a")))
        every { noteRepo.getDeletedNotes(ACCOUNT_ID) } returns flowOf(listOf(note("d", deleted = true)))
        every { noteRepo.getDeletedNotesCount(ACCOUNT_ID) } returns flowOf(1)
        coEvery { noteRepo.syncNotes(any(), any()) } returns EasResult.Success(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): NotesViewModel =
        NotesViewModel(noteRepo, accountRepo, dispatcher)

    // ===================== init / loading =====================

    @Test
    fun `init loads notes, deleted and count into state`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.accountId).isEqualTo(ACCOUNT_ID)
        assertThat(s.notes.map { it.id }).containsExactly("a")
        assertThat(s.deletedNotes.map { it.id }).containsExactly("d")
        assertThat(s.deletedCount).isEqualTo(1)
        assertThat(s.isInitialLoadDone).isTrue()
    }

    @Test
    fun `auto-sync runs once when there are no local notes`() = runTest(dispatcher) {
        every { noteRepo.getNotes(ACCOUNT_ID) } returns flowOf(emptyList())
        every { noteRepo.getDeletedNotes(ACCOUNT_ID) } returns flowOf(emptyList())
        every { noteRepo.getDeletedNotesCount(ACCOUNT_ID) } returns flowOf(0)
        val vm = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { noteRepo.syncNotes(ACCOUNT_ID, false) }
        assertThat(vm.uiState.value.isInitialLoadDone).isTrue()
    }

    // ===================== sync =====================

    @Test
    fun `syncNotes success emits Synced with count`() = runTest(dispatcher) {
        coEvery { noteRepo.syncNotes(ACCOUNT_ID, false) } returns EasResult.Success(5)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.syncNotes()
        advanceUntilIdle()

        assertThat(events).contains(NotesEvent.Synced(5))
        assertThat(vm.uiState.value.isSyncing).isFalse()
    }

    @Test
    fun `syncNotes error emits Error`() = runTest(dispatcher) {
        coEvery { noteRepo.syncNotes(ACCOUNT_ID, false) } returns EasResult.Error("boom")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.syncNotes()
        advanceUntilIdle()

        assertThat(events).contains(NotesEvent.Error("boom"))
    }

    // ===================== tabs =====================

    @Test
    fun `selectTab to trash triggers a silent deleted sync`() = runTest(dispatcher) {
        coEvery { noteRepo.syncNotes(ACCOUNT_ID, true) } returns EasResult.Success(3)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.selectTab(1)
        advanceUntilIdle()

        coVerify { noteRepo.syncNotes(ACCOUNT_ID, true) }
        assertThat(vm.uiState.value.selectedTab).isEqualTo(1)
        // Silent: без события Synced
        assertThat(events.none { it is NotesEvent.Synced }).isTrue()
    }

    @Test
    fun `selectTab to trash is throttled within 30 seconds`() = runTest(dispatcher) {
        coEvery { noteRepo.syncNotes(ACCOUNT_ID, true) } returns EasResult.Success(0)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectTab(1)
        advanceUntilIdle()
        vm.selectTab(0)
        advanceUntilIdle()
        vm.selectTab(1)
        advanceUntilIdle()

        coVerify(exactly = 1) { noteRepo.syncNotes(ACCOUNT_ID, true) }
    }

    @Test
    fun `selectTab clears selection`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.addToSelection("a")

        vm.selectTab(1)

        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    // ===================== create / update =====================

    @Test
    fun `saveNote create emits NoteCreated then ScrollToTop`() = runTest(dispatcher) {
        coEvery { noteRepo.createNote(ACCOUNT_ID, "s", "b") } returns EasResult.Success(note("new"))
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.saveNote(null, "s", "b")
        advanceUntilIdle()

        assertThat(events).containsAtLeast(NotesEvent.NoteCreated, NotesEvent.ScrollToTop).inOrder()
        assertThat(vm.uiState.value.isCreating).isFalse()
        coVerify { noteRepo.createNote(ACCOUNT_ID, "s", "b") }
    }

    @Test
    fun `saveNote update emits NoteUpdated without ScrollToTop`() = runTest(dispatcher) {
        val existing = note("a")
        coEvery { noteRepo.updateNote(existing, "s", "b") } returns EasResult.Success(existing)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.saveNote(existing, "s", "b")
        advanceUntilIdle()

        assertThat(events).contains(NotesEvent.NoteUpdated)
        assertThat(events).doesNotContain(NotesEvent.ScrollToTop)
        coVerify { noteRepo.updateNote(existing, "s", "b") }
    }

    @Test
    fun `saveNote guards against double-tap`() = runTest(dispatcher) {
        coEvery { noteRepo.createNote(ACCOUNT_ID, "s", "b") } returns EasResult.Success(note("new"))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.saveNote(null, "s", "b")  // ставит isCreating=true синхронно
        vm.saveNote(null, "s", "b")  // должен быть проигнорирован
        advanceUntilIdle()

        coVerify(exactly = 1) { noteRepo.createNote(ACCOUNT_ID, "s", "b") }
    }

    @Test
    fun `saveNote error emits Error`() = runTest(dispatcher) {
        coEvery { noteRepo.createNote(ACCOUNT_ID, "s", "b") } returns EasResult.Error("nope")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.saveNote(null, "s", "b")
        advanceUntilIdle()

        assertThat(events).contains(NotesEvent.Error("nope"))
        assertThat(vm.uiState.value.isCreating).isFalse()
    }

    // ===================== soft delete =====================

    @Test
    fun `deleteNoteToTrash success emits MovedToTrash`() = runTest(dispatcher) {
        val target = note("a")
        coEvery { noteRepo.deleteNote(target) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteNoteToTrash(target)
        advanceUntilIdle()

        assertThat(events).contains(NotesEvent.MovedToTrash(1))
        coVerify { noteRepo.deleteNote(target) }
    }

    @Test
    fun `deleteSelectedToTrash deletes selected active notes and clears selection`() = runTest(dispatcher) {
        coEvery { noteRepo.deleteNotes(any()) } returns EasResult.Success(1)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a"))

        vm.deleteSelectedToTrash()
        advanceUntilIdle()

        assertThat(events).contains(NotesEvent.MovedToTrash(1))
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        coVerify { noteRepo.deleteNotes(match { it.size == 1 && it[0].id == "a" }) }
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
    fun `onQueryChange and clearQuery update query`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.onQueryChange("note")
        assertThat(vm.uiState.value.query).isEqualTo("note")
        vm.clearQuery()
        assertThat(vm.uiState.value.query).isEmpty()
    }

    // ===================== progress-bar wrappers (вызываются из DeletionController) =====================

    @Test
    fun `deleteNotePermanently delegates to repository`() = runTest(dispatcher) {
        val target = note("d", deleted = true)
        coEvery { noteRepo.deleteNotePermanently(target) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()

        val result = vm.deleteNotePermanently(target)

        assertThat(result).isEqualTo(EasResult.Success(true))
        coVerify { noteRepo.deleteNotePermanently(target) }
    }

    @Test
    fun `restoreNote delegates to repository`() = runTest(dispatcher) {
        val target = note("d", deleted = true)
        coEvery { noteRepo.restoreNote(target) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()

        val result = vm.restoreNote(target)

        assertThat(result).isEqualTo(EasResult.Success(true))
        coVerify { noteRepo.restoreNote(target) }
    }

    @Test
    fun `emptyTrash delegates to repository with active account id`() = runTest(dispatcher) {
        val serverIds = listOf("srv-d")
        coEvery { noteRepo.emptyNotesTrashWithProgress(ACCOUNT_ID, serverIds, any()) } returns EasResult.Success(1)
        val vm = createViewModel()
        advanceUntilIdle()

        val result = vm.emptyTrash(serverIds) { _, _ -> }

        assertThat(result).isEqualTo(EasResult.Success(1))
        coVerify { noteRepo.emptyNotesTrashWithProgress(ACCOUNT_ID, serverIds, any()) }
    }

    @Test
    fun `deleteNotesPermanently and restoreNotes delegate with progress`() = runTest(dispatcher) {
        val notes = listOf(note("d", deleted = true))
        coEvery { noteRepo.deleteNotesPermanentlyWithProgress(notes, any()) } returns EasResult.Success(1)
        coEvery { noteRepo.restoreNotesWithProgress(notes, any()) } returns EasResult.Success(1)
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.deleteNotesPermanently(notes) { _, _ -> }).isEqualTo(EasResult.Success(1))
        assertThat(vm.restoreNotes(notes) { _, _ -> }).isEqualTo(EasResult.Success(1))
        coVerify { noteRepo.deleteNotesPermanentlyWithProgress(notes, any()) }
        coVerify { noteRepo.restoreNotesWithProgress(notes, any()) }
    }

    // ===================== crash resistance (exception handling, M-1) =====================
    // Если бы mutation-функции не ловили исключение, непойманный throw в viewModelScope.launch
    // (SupervisorJob) уронил бы тест через runTest (uncaught exception) — как уронил бы процесс.

    @Test
    fun `saveNote does not crash when repository throws`() = runTest(dispatcher) {
        coEvery { noteRepo.createNote(any(), any(), any()) } throws RuntimeException("db error")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.saveNote(null, "s", "b")
        advanceUntilIdle()

        assertThat(events.any { it is NotesEvent.Error }).isTrue()
        assertThat(vm.uiState.value.isCreating).isFalse()
    }

    @Test
    fun `deleteNoteToTrash does not crash when repository throws`() = runTest(dispatcher) {
        val target = note("a")
        coEvery { noteRepo.deleteNote(target) } throws RuntimeException("network failure")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteNoteToTrash(target)
        advanceUntilIdle()

        assertThat(events.any { it is NotesEvent.Error }).isTrue()
    }

    @Test
    fun `deleteSelectedToTrash does not crash when repository throws`() = runTest(dispatcher) {
        coEvery { noteRepo.deleteNotes(any()) } throws RuntimeException("EWS error")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setSelection(setOf("a"))

        vm.deleteSelectedToTrash()
        advanceUntilIdle()

        assertThat(events.any { it is NotesEvent.Error }).isTrue()
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    // ===================== helpers =====================

    private fun TestScope.collectEvents(vm: NotesViewModel): List<NotesEvent> {
        val received = mutableListOf<NotesEvent>()
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

    private fun note(id: String, deleted: Boolean = false): NoteEntity {
        val n = mockk<NoteEntity>(relaxed = true)
        every { n.id } returns id
        every { n.serverId } returns "srv-$id"
        every { n.isDeleted } returns deleted
        return n
    }

    private companion object {
        const val ACCOUNT_ID = 1L
    }
}

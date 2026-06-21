package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.TaskRepository
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
 * Юнит-тесты для [TasksViewModel].
 *
 * VM тестируется БЕЗ Robolectric благодаря конструкторной инъекции (DIP): [TaskRepository] и
 * [AccountRepository] — MockK-моки, IO-диспетчер подменяется тестовым, поэтому всё детерминировано
 * через [advanceUntilIdle]. Side-effect'ы (тосты) проверяются через канал one-shot событий, а
 * пакетные обёртки (восстановление/окончательное удаление) — на корректность цикла и onProgress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var taskRepo: TaskRepository
    private lateinit var accountRepo: AccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        taskRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        every { accountRepo.activeAccount } returns flowOf(accountMock(ACCOUNT_ID))
        // По умолчанию: один активный + один удалённый → авто-синхронизация не запускается.
        every { taskRepo.getTasks(ACCOUNT_ID) } returns flowOf(listOf(task("a")))
        every { taskRepo.getDeletedTasks(ACCOUNT_ID) } returns flowOf(listOf(task("d", deleted = true)))
        coEvery { taskRepo.syncTasks(any(), any()) } returns EasResult.Success(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(initialFilter: TaskFilter = TaskFilter.ALL): TasksViewModel =
        TasksViewModel(taskRepo, accountRepo, initialFilter, dispatcher)

    // ===================== init / loading =====================

    @Test
    fun `init loads active, deleted and account email into state`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertThat(s.accountId).isEqualTo(ACCOUNT_ID)
        assertThat(s.accountEmail).isEqualTo(ACCOUNT_EMAIL)
        assertThat(s.tasks.map { it.id }).containsExactly("a")
        assertThat(s.deletedTasks.map { it.id }).containsExactly("d")
        assertThat(s.isInitialLoadDone).isTrue()
        assertThat(s.filter).isEqualTo(TaskFilter.ALL)
    }

    @Test
    fun `initial filter is honored`() = runTest(dispatcher) {
        val vm = createViewModel(TaskFilter.TODAY)
        advanceUntilIdle()
        assertThat(vm.uiState.value.filter).isEqualTo(TaskFilter.TODAY)
    }

    @Test
    fun `auto-sync runs once when there are no local tasks`() = runTest(dispatcher) {
        every { taskRepo.getTasks(ACCOUNT_ID) } returns flowOf(emptyList())
        every { taskRepo.getDeletedTasks(ACCOUNT_ID) } returns flowOf(emptyList())
        val vm = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { taskRepo.syncTasks(ACCOUNT_ID, false) }
        assertThat(vm.uiState.value.isInitialLoadDone).isTrue()
    }

    @Test
    fun `auto-sync is skipped when local tasks exist`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { taskRepo.syncTasks(any(), any()) }
    }

    // ===================== sync =====================

    @Test
    fun `syncTasks success emits Synced with count`() = runTest(dispatcher) {
        coEvery { taskRepo.syncTasks(ACCOUNT_ID, false) } returns EasResult.Success(5)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.syncTasks()
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.Synced(5))
        assertThat(vm.uiState.value.isSyncing).isFalse()
    }

    @Test
    fun `syncTasks error emits Error`() = runTest(dispatcher) {
        coEvery { taskRepo.syncTasks(ACCOUNT_ID, false) } returns EasResult.Error("boom")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.syncTasks()
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.Error("boom"))
    }

    // ===================== filter =====================

    @Test
    fun `selectFilter to trash triggers a silent sync`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.selectFilter(TaskFilter.DELETED)
        advanceUntilIdle()

        coVerify { taskRepo.syncTasks(ACCOUNT_ID, false) }
        assertThat(vm.uiState.value.filter).isEqualTo(TaskFilter.DELETED)
        assertThat(events.none { it is TasksEvent.Synced }).isTrue()
    }

    @Test
    fun `selectFilter to trash is throttled within 30 seconds`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.selectFilter(TaskFilter.DELETED)
        advanceUntilIdle()
        vm.selectFilter(TaskFilter.ALL)
        advanceUntilIdle()
        vm.selectFilter(TaskFilter.DELETED)
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepo.syncTasks(ACCOUNT_ID, false) }
    }

    @Test
    fun `selectFilter clears both selection sets`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.addActiveSelection("a")
        vm.addDeletedSelection("d")

        vm.selectFilter(TaskFilter.ACTIVE)

        assertThat(vm.uiState.value.selectedActiveIds).isEmpty()
        assertThat(vm.uiState.value.selectedDeletedIds).isEmpty()
    }

    // ===================== create / update =====================

    @Test
    fun `saveTask create emits TaskCreated`() = runTest(dispatcher) {
        coEvery {
            taskRepo.createTask(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns EasResult.Success(task("new"))
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.saveTask(null, "s", "b", 0L, 0L, 1, false, 0L, null)
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.TaskCreated)
        assertThat(vm.uiState.value.isCreating).isFalse()
        coVerify { taskRepo.createTask(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `saveTask update emits TaskUpdated`() = runTest(dispatcher) {
        val existing = task("a")
        coEvery {
            taskRepo.updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns EasResult.Success(existing)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.saveTask(existing, "s", "b", 0L, 0L, 1, false, 0L, null)
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.TaskUpdated)
        coVerify { taskRepo.updateTask(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `saveTask guards against double-tap`() = runTest(dispatcher) {
        coEvery {
            taskRepo.createTask(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns EasResult.Success(task("new"))
        val vm = createViewModel()
        advanceUntilIdle()

        vm.saveTask(null, "s", "b", 0L, 0L, 1, false, 0L, null)  // ставит isCreating=true синхронно
        vm.saveTask(null, "s", "b", 0L, 0L, 1, false, 0L, null)  // должен быть проигнорирован
        advanceUntilIdle()

        coVerify(exactly = 1) { taskRepo.createTask(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `saveTask error emits Error`() = runTest(dispatcher) {
        coEvery {
            taskRepo.createTask(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns EasResult.Error("nope")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.saveTask(null, "s", "b", 0L, 0L, 1, false, 0L, null)
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.Error("nope"))
        assertThat(vm.uiState.value.isCreating).isFalse()
    }

    // ===================== toggle complete =====================

    @Test
    fun `toggleComplete emits CompleteToggled with new state`() = runTest(dispatcher) {
        val target = task("a")
        coEvery { taskRepo.toggleTaskComplete(target) } returns EasResult.Success(task("a", complete = true))
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.toggleComplete(target)
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.CompleteToggled(true))
        coVerify { taskRepo.toggleTaskComplete(target) }
    }

    // ===================== soft delete =====================

    @Test
    fun `deleteToTrash success emits MovedToTrash`() = runTest(dispatcher) {
        val target = task("a")
        coEvery { taskRepo.deleteTask(target) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)

        vm.deleteToTrash(target)
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.MovedToTrash(1))
        coVerify { taskRepo.deleteTask(target) }
    }

    @Test
    fun `deleteSelectedToTrash deletes each, clears selection and reports count`() = runTest(dispatcher) {
        val t1 = task("a")
        val t2 = task("b")
        coEvery { taskRepo.deleteTask(any()) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.setActiveSelection(setOf("a", "b"))

        vm.deleteSelectedToTrash(listOf(t1, t2))
        advanceUntilIdle()

        assertThat(events).contains(TasksEvent.MovedToTrash(2))
        assertThat(vm.uiState.value.selectedActiveIds).isEmpty()
        coVerify(exactly = 1) { taskRepo.deleteTask(t1) }
        coVerify(exactly = 1) { taskRepo.deleteTask(t2) }
    }

    // ===================== selection =====================

    @Test
    fun `toggleActiveSelection adds then removes id`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.toggleActiveSelection("x")
        assertThat(vm.uiState.value.selectedActiveIds).containsExactly("x")
        vm.toggleActiveSelection("x")
        assertThat(vm.uiState.value.selectedActiveIds).isEmpty()
    }

    @Test
    fun `set and clear selection work for both sets`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setActiveSelection(setOf("a", "b"))
        vm.setDeletedSelection(setOf("d"))
        assertThat(vm.uiState.value.selectedActiveIds).containsExactly("a", "b")
        assertThat(vm.uiState.value.selectedDeletedIds).containsExactly("d")
        assertThat(vm.uiState.value.isSelectionMode).isTrue()
        vm.clearSelection()
        assertThat(vm.uiState.value.isSelectionMode).isFalse()
    }

    @Test
    fun `removeFromSelection drops ids from both sets`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setActiveSelection(setOf("a", "b"))
        vm.setDeletedSelection(setOf("d", "e"))

        vm.removeFromSelection(setOf("b", "d"))

        assertThat(vm.uiState.value.selectedActiveIds).containsExactly("a")
        assertThat(vm.uiState.value.selectedDeletedIds).containsExactly("e")
    }

    @Test
    fun `onQueryChange and clearQuery update query`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.onQueryChange("task")
        assertThat(vm.uiState.value.query).isEqualTo("task")
        vm.clearQuery()
        assertThat(vm.uiState.value.query).isEmpty()
    }

    // ===================== progress-bar wrappers (вызываются из DeletionController) =====================

    @Test
    fun `restoreTask and deleteTaskPermanently delegate to repository`() = runTest(dispatcher) {
        val target = task("d", deleted = true)
        coEvery { taskRepo.restoreTask(target) } returns EasResult.Success(true)
        coEvery { taskRepo.deleteTaskPermanently(target) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.restoreTask(target)).isEqualTo(EasResult.Success(true))
        assertThat(vm.deleteTaskPermanently(target)).isEqualTo(EasResult.Success(true))
        coVerify { taskRepo.restoreTask(target) }
        coVerify { taskRepo.deleteTaskPermanently(target) }
    }

    @Test
    fun `restoreTasks loops and reports progress`() = runTest(dispatcher) {
        val tasks = listOf(task("d1", deleted = true), task("d2", deleted = true))
        coEvery { taskRepo.restoreTask(any()) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = vm.restoreTasks(tasks) { done, total -> progress.add(done to total) }

        assertThat(result).isEqualTo(EasResult.Success(2))
        assertThat(progress).containsExactly(1 to 2, 2 to 2).inOrder()
        coVerify(exactly = 2) { taskRepo.restoreTask(any()) }
    }

    @Test
    fun `deleteTasksPermanently loops, counts successes and reports progress`() = runTest(dispatcher) {
        val t1 = task("d1", deleted = true)
        val t2 = task("d2", deleted = true)
        coEvery { taskRepo.deleteTaskPermanently(t1) } returns EasResult.Success(true)
        coEvery { taskRepo.deleteTaskPermanently(t2) } returns EasResult.Error("fail")
        val vm = createViewModel()
        advanceUntilIdle()
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = vm.deleteTasksPermanently(listOf(t1, t2)) { done, total -> progress.add(done to total) }

        assertThat(result).isEqualTo(EasResult.Success(1))
        assertThat(progress).containsExactly(1 to 2, 1 to 2).inOrder()
    }

    @Test
    fun `emptyTrash delegates to repository with active account id`() = runTest(dispatcher) {
        coEvery { taskRepo.emptyTasksTrash(ACCOUNT_ID) } returns EasResult.Success(3)
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.emptyTrash()).isEqualTo(EasResult.Success(3))
        coVerify { taskRepo.emptyTasksTrash(ACCOUNT_ID) }
    }

    // ===================== helpers =====================

    private fun TestScope.collectEvents(vm: TasksViewModel): List<TasksEvent> {
        val received = mutableListOf<TasksEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { received.add(it) }
        }
        return received
    }

    private fun accountMock(id: Long): AccountEntity {
        val account = mockk<AccountEntity>()
        every { account.id } returns id
        every { account.email } returns ACCOUNT_EMAIL
        return account
    }

    private fun task(id: String, deleted: Boolean = false, complete: Boolean = false): TaskEntity {
        val t = mockk<TaskEntity>(relaxed = true)
        every { t.id } returns id
        every { t.serverId } returns "srv-$id"
        every { t.accountId } returns ACCOUNT_ID
        every { t.isDeleted } returns deleted
        every { t.complete } returns complete
        return t
    }

    private companion object {
        const val ACCOUNT_ID = 1L
        const val ACCOUNT_EMAIL = "user@corp.local"
    }
}

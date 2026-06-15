package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
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
 * Юнит-тесты для [SearchViewModel].
 *
 * VM тестируется БЕЗ Android/Robolectric благодаря конструкторной инъекции (DIP):
 * репозитории — MockK-моки (их Android-зависимый конструктор не запускается),
 * IO-диспетчер подменяется тестовым, поэтому всё детерминировано через [advanceUntilIdle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var mailRepo: MailRepository
    private lateinit var accountRepo: AccountRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mailRepo = mockk(relaxed = true)
        accountRepo = mockk(relaxed = true)
        // Активный аккаунт по умолчанию (читается синхронно в search() через stateIn)
        every { accountRepo.activeAccount } returns flowOf(accountMock(ACCOUNT_ID))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SearchViewModel =
        SearchViewModel(mailRepo, accountRepo, dispatcher)

    // ===================== query / filter =====================

    @Test
    fun `onQueryChange updates query in state`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.onQueryChange("hello")
        assertThat(vm.uiState.value.query).isEqualTo("hello")
    }

    @Test
    fun `setDateFilter updates filter in state`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.setDateFilter(DateFilter.WEEK)
        assertThat(vm.uiState.value.dateFilter).isEqualTo(DateFilter.WEEK)
    }

    // ===================== search =====================

    @Test
    fun `search ignores query shorter than two characters`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onQueryChange("a")
        vm.search()
        advanceUntilIdle()
        coVerify(exactly = 0) { mailRepo.search(any(), any()) }
        assertThat(vm.uiState.value.isSearching).isFalse()
    }

    @Test
    fun `search populates results and resets isSearching`() = runTest(dispatcher) {
        coEvery { mailRepo.search(ACCOUNT_ID, "report") } returns listOf(email("1"), email("2"))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onQueryChange("report")
        vm.search()
        advanceUntilIdle()
        assertThat(vm.uiState.value.allResults.map { it.id }).containsExactly("1", "2").inOrder()
        assertThat(vm.uiState.value.isSearching).isFalse()
    }

    @Test
    fun `search with magic phrase emits ShowEasterEgg and does not query`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.onQueryChange("I Want Out")
        vm.search()
        advanceUntilIdle()
        assertThat(events).contains(SearchEvent.ShowEasterEgg)
        coVerify(exactly = 0) { mailRepo.search(any(), any()) }
    }

    @Test
    fun `clearQuery resets query results and selection`() = runTest(dispatcher) {
        coEvery { mailRepo.search(ACCOUNT_ID, "report") } returns listOf(email("1"))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onQueryChange("report")
        vm.search()
        advanceUntilIdle()
        vm.addToSelection("1")

        vm.clearQuery()

        val state = vm.uiState.value
        assertThat(state.query).isEmpty()
        assertThat(state.allResults).isEmpty()
        assertThat(state.selectedIds).isEmpty()
    }

    // ===================== selection =====================

    @Test
    fun `toggleSelection adds then removes id`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.toggleSelection("a")
        assertThat(vm.uiState.value.selectedIds).containsExactly("a")
        vm.toggleSelection("a")
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `setSelection replaces the whole selection`() = runTest(dispatcher) {
        val vm = createViewModel()
        vm.addToSelection("a")
        vm.setSelection(setOf("b", "c"))
        assertThat(vm.uiState.value.selectedIds).containsExactly("b", "c")
    }

    // ===================== deleteSelected =====================

    @Test
    fun `deleteSelected emits PlayDeleteSound then MovedToTrash and removes results`() = runTest(dispatcher) {
        coEvery { mailRepo.search(ACCOUNT_ID, "report") } returns listOf(email("1"), email("2"))
        coEvery { mailRepo.moveToTrash(listOf("1")) } returns EasResult.Success(1)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.onQueryChange("report")
        vm.search()
        advanceUntilIdle()
        vm.addToSelection("1")

        vm.deleteSelected()
        advanceUntilIdle()

        assertThat(events).containsAtLeast(SearchEvent.PlayDeleteSound, SearchEvent.MovedToTrash).inOrder()
        assertThat(vm.uiState.value.allResults.map { it.id }).containsExactly("2")
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    @Test
    fun `deleteSelected with zero moved emits DeletedPermanently`() = runTest(dispatcher) {
        coEvery { mailRepo.search(ACCOUNT_ID, "report") } returns listOf(email("1"))
        coEvery { mailRepo.moveToTrash(listOf("1")) } returns EasResult.Success(0)
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.onQueryChange("report")
        vm.search()
        advanceUntilIdle()
        vm.addToSelection("1")

        vm.deleteSelected()
        advanceUntilIdle()

        assertThat(events).contains(SearchEvent.DeletedPermanently)
    }

    @Test
    fun `deleteSelected error emits ShowError and keeps results`() = runTest(dispatcher) {
        coEvery { mailRepo.search(ACCOUNT_ID, "report") } returns listOf(email("1"))
        coEvery { mailRepo.moveToTrash(listOf("1")) } returns EasResult.Error("boom")
        val vm = createViewModel()
        advanceUntilIdle()
        val events = collectEvents(vm)
        vm.onQueryChange("report")
        vm.search()
        advanceUntilIdle()
        vm.addToSelection("1")

        vm.deleteSelected()
        advanceUntilIdle()

        assertThat(events).contains(SearchEvent.ShowError("boom"))
        assertThat(vm.uiState.value.allResults).hasSize(1)
        assertThat(vm.uiState.value.selectedIds).isEmpty()
    }

    // ===================== markSelectedAsRead / starSelected =====================

    @Test
    fun `markSelectedAsRead updates read flag only for selected and clears selection`() = runTest(dispatcher) {
        coEvery { mailRepo.search(ACCOUNT_ID, "report") } returns
            listOf(email("1", read = false), email("2", read = false))
        coEvery { mailRepo.markAsReadBatch(listOf("1"), true) } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onQueryChange("report")
        vm.search()
        advanceUntilIdle()
        vm.addToSelection("1")

        vm.markSelectedAsRead(true)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.allResults.first { it.id == "1" }.read).isTrue()
        assertThat(state.allResults.first { it.id == "2" }.read).isFalse()
        assertThat(state.selectedIds).isEmpty()
    }

    @Test
    fun `starSelected toggles flagged for selected and clears selection`() = runTest(dispatcher) {
        coEvery { mailRepo.search(ACCOUNT_ID, "report") } returns listOf(email("1", flagged = false))
        coEvery { mailRepo.toggleFlag("1") } returns EasResult.Success(true)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onQueryChange("report")
        vm.search()
        advanceUntilIdle()
        vm.addToSelection("1")

        vm.starSelected()
        advanceUntilIdle()

        assertThat(vm.uiState.value.allResults.first().flagged).isTrue()
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        coVerify { mailRepo.toggleFlag("1") }
    }

    // ===================== helpers =====================

    /** Подписывается на one-shot события VM в фоне; список наполняется по мере прихода событий. */
    private fun TestScope.collectEvents(vm: SearchViewModel): List<SearchEvent> {
        val received = mutableListOf<SearchEvent>()
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
        id: String,
        read: Boolean = false,
        flagged: Boolean = false
    ): EmailEntity = EmailEntity(
        id = id,
        accountId = ACCOUNT_ID,
        folderId = "folder",
        serverId = id,
        from = "sender@example.com",
        to = "me@example.com",
        subject = "subject $id",
        body = "body $id",
        dateReceived = 0L,
        read = read,
        flagged = flagged
    )

    private companion object {
        const val ACCOUNT_ID = 1L
    }
}

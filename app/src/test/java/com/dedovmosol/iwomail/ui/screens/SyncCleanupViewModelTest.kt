package com.dedovmosol.iwomail.ui.screens

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты для [SyncCleanupViewModel].
 *
 * VM тестируется БЕЗ Robolectric: репозитории и системные side-effect'ы ([SyncEffects])
 * передаются через конструктор (DIP) и подменяются MockK-моками. Это проверяет, что
 * каждое действие обновляет БД, дёргает нужные side-effect'ы и перечитывает состояние —
 * без касания PushService/SyncWorker/Application.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCleanupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var accountRepo: AccountRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var effects: SyncEffects

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        accountRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        effects = mockk(relaxed = true)
        // Дефолтная загрузка состояния в init {}
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returns accountMock()
        coEvery { settingsRepo.getAutoCleanupDownloadsDays() } returns 7
        coEvery { settingsRepo.getAutoCleanupRollbackDays() } returns 14
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SyncCleanupViewModel =
        SyncCleanupViewModel(ACCOUNT_ID, accountRepo, settingsRepo, effects)

    // ===================== init =====================

    @Test
    fun `init loads account and cleanup settings into state`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertThat(state.account).isNotNull()
        assertThat(state.downloadsDays).isEqualTo(7)
        assertThat(state.rollbackDays).isEqualTo(14)
    }

    // ===================== setSyncMode =====================

    @Test
    fun `setSyncMode PUSH persists mode enables push reschedules and refreshes`() = runTest(dispatcher) {
        val refreshed = accountMock()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returnsMany listOf(accountMock(), refreshed)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSyncMode(SyncMode.PUSH)
        advanceUntilIdle()

        coVerify { accountRepo.updateSyncMode(ACCOUNT_ID, SyncMode.PUSH) }
        verify { effects.setPushEnabled(true) }
        coVerify { effects.rescheduleSync() }
        assertThat(vm.uiState.value.account).isSameInstanceAs(refreshed)
    }

    @Test
    fun `setSyncMode SCHEDULED disables push`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSyncMode(SyncMode.SCHEDULED)
        advanceUntilIdle()

        coVerify { accountRepo.updateSyncMode(ACCOUNT_ID, SyncMode.SCHEDULED) }
        verify { effects.setPushEnabled(false) }
        coVerify { effects.rescheduleSync() }
    }

    // ===================== schedule-affecting setters =====================

    @Test
    fun `setSyncInterval persists and reschedules without touching push`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setSyncInterval(30)
        advanceUntilIdle()

        coVerify { accountRepo.updateSyncInterval(ACCOUNT_ID, 30) }
        coVerify { effects.rescheduleSync() }
        verify(exactly = 0) { effects.setPushEnabled(any()) }
    }

    @Test
    fun `setNightModeEnabled persists and reschedules`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setNightModeEnabled(true)
        advanceUntilIdle()

        coVerify { accountRepo.updateNightModeEnabled(ACCOUNT_ID, true) }
        coVerify { effects.rescheduleSync() }
    }

    @Test
    fun `setIgnoreBatterySaver persists and reschedules`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setIgnoreBatterySaver(true)
        advanceUntilIdle()

        coVerify { accountRepo.updateIgnoreBatterySaver(ACCOUNT_ID, true) }
        coVerify { effects.rescheduleSync() }
    }

    // ===================== per-collection intervals (no reschedule) =====================

    @Test
    fun `setContactsSyncInterval persists and refreshes but does not reschedule`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setContactsSyncInterval(3)
        advanceUntilIdle()

        coVerify { accountRepo.updateContactsSyncInterval(ACCOUNT_ID, 3) }
        coVerify(exactly = 0) { effects.rescheduleSync() }
        verify(exactly = 0) { effects.setPushEnabled(any()) }
    }

    @Test
    fun `setNotesSyncInterval persists`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setNotesSyncInterval(5)
        advanceUntilIdle()
        coVerify { accountRepo.updateNotesSyncInterval(ACCOUNT_ID, 5) }
    }

    @Test
    fun `setCalendarSyncInterval persists`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setCalendarSyncInterval(7)
        advanceUntilIdle()
        coVerify { accountRepo.updateCalendarSyncInterval(ACCOUNT_ID, 7) }
    }

    @Test
    fun `setTasksSyncInterval persists`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setTasksSyncInterval(14)
        advanceUntilIdle()
        coVerify { accountRepo.updateTasksSyncInterval(ACCOUNT_ID, 14) }
    }

    // ===================== auto-cleanup (account-backed) =====================

    @Test
    fun `setAutoCleanupTrashDays persists and refreshes account`() = runTest(dispatcher) {
        val refreshed = accountMock()
        coEvery { accountRepo.getAccount(ACCOUNT_ID) } returnsMany listOf(accountMock(), refreshed)
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setAutoCleanupTrashDays(30)
        advanceUntilIdle()

        coVerify { accountRepo.updateAutoCleanupTrashDays(ACCOUNT_ID, 30) }
        assertThat(vm.uiState.value.account).isSameInstanceAs(refreshed)
    }

    @Test
    fun `setAutoCleanupDraftsDays persists`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setAutoCleanupDraftsDays(7)
        advanceUntilIdle()
        coVerify { accountRepo.updateAutoCleanupDraftsDays(ACCOUNT_ID, 7) }
    }

    @Test
    fun `setAutoCleanupSpamDays persists`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setAutoCleanupSpamDays(3)
        advanceUntilIdle()
        coVerify { accountRepo.updateAutoCleanupSpamDays(ACCOUNT_ID, 3) }
    }

    // ===================== app-file cleanup (DataStore-backed, optimistic state) =====================

    @Test
    fun `setDownloadsDays persists to settings and updates state optimistically`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setDownloadsDays(5)
        advanceUntilIdle()

        coVerify { settingsRepo.setAutoCleanupDownloadsDays(5) }
        assertThat(vm.uiState.value.downloadsDays).isEqualTo(5)
    }

    @Test
    fun `setRollbackDays persists to settings and updates state optimistically`() = runTest(dispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setRollbackDays(9)
        advanceUntilIdle()

        coVerify { settingsRepo.setAutoCleanupRollbackDays(9) }
        assertThat(vm.uiState.value.rollbackDays).isEqualTo(9)
    }

    // ===================== helpers =====================

    private fun accountMock(): AccountEntity = mockk(relaxed = true)

    private companion object {
        const val ACCOUNT_ID = 1L
    }
}

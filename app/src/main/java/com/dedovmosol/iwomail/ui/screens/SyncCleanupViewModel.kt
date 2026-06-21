package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.sync.PushService
import com.dedovmosol.iwomail.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Неизменяемое состояние экрана "Синхронизация и очистка".
 *
 * Живёт в [SyncCleanupViewModel] и переживает поворот экрана / возврат из фона
 * без ручных rememberSaveable-флагов. Раньше `account`/`downloadsDays`/`rollbackDays`
 * хранились в обычном `remember` и перечитывались из БД при каждом повороте.
 */
data class SyncCleanupUiState(
    val account: AccountEntity? = null,
    val downloadsDays: Int = 0,
    val rollbackDays: Int = 0
)

/**
 * Абстракция системных side-effect'ов экрана: управление push-службой и
 * перепланирование фоновой синхронизации.
 *
 * Вынесена за интерфейс (DIP): [SyncCleanupViewModel] больше не зависит от Android-классов
 * PushService/SyncWorker напрямую → юнит-тестируется без Robolectric.
 */
interface SyncEffects {
    /** Включает/выключает push-службу в зависимости от режима синхронизации. */
    fun setPushEnabled(enabled: Boolean)

    /** Перепланирует периодическую синхронизацию с учётом ночного режима. */
    suspend fun rescheduleSync()
}

/**
 * Боевая реализация [SyncEffects] поверх Application-контекста.
 * Application живёт всё время процесса → безопасно по утечкам.
 */
class AndroidSyncEffects(context: Context) : SyncEffects {
    private val appContext: Context = context.applicationContext

    override fun setPushEnabled(enabled: Boolean) {
        if (enabled) PushService.start(appContext) else PushService.stop(appContext)
    }

    override suspend fun rescheduleSync() {
        SyncWorker.scheduleWithNightMode(appContext)
    }
}

/**
 * ViewModel экрана "Синхронизация и очистка".
 *
 * Зачем:
 * - Состояние переживает конфигурационные изменения «бесплатно» (ViewModel не пересоздаётся при повороте),
 *   что убирает лишние чтения из БД и класс багов с потерей состояния.
 * - Вся презентационная логика собрана в одном месте (DRY/SRP), а Composable отвечает только за отрисовку.
 *
 * Зависимости передаются через конструктор (DIP) — репозитории и [SyncEffects], что делает VM
 * тестируемой без Robolectric. Протокол EAS/EWS и совместимость с Exchange 2007 SP1/SP2 не затрагиваются.
 */
class SyncCleanupViewModel(
    private val accountId: Long,
    private val accountRepo: AccountRepository,
    private val settingsRepo: SettingsRepository,
    private val effects: SyncEffects
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncCleanupUiState())
    val uiState: StateFlow<SyncCleanupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val account = accountRepo.getAccount(accountId)
            val downloads = settingsRepo.getAutoCleanupDownloadsDays()
            val rollback = settingsRepo.getAutoCleanupRollbackDays()
            _uiState.update {
                it.copy(account = account, downloadsDays = downloads, rollbackDays = rollback)
            }
        }
    }

    private suspend fun refreshAccount() {
        val account = accountRepo.getAccount(accountId)
        _uiState.update { it.copy(account = account) }
    }

    fun setSyncMode(mode: SyncMode) {
        viewModelScope.launch {
            accountRepo.updateSyncMode(accountId, mode)
            effects.setPushEnabled(mode == SyncMode.PUSH)
            effects.rescheduleSync()
            refreshAccount()
        }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            accountRepo.updateSyncInterval(accountId, minutes)
            effects.rescheduleSync()
            refreshAccount()
        }
    }

    fun setNightModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            accountRepo.updateNightModeEnabled(accountId, enabled)
            effects.rescheduleSync()
            refreshAccount()
        }
    }

    fun setIgnoreBatterySaver(ignore: Boolean) {
        viewModelScope.launch {
            accountRepo.updateIgnoreBatterySaver(accountId, ignore)
            effects.rescheduleSync()
            refreshAccount()
        }
    }

    fun setContactsSyncInterval(days: Int) {
        viewModelScope.launch {
            accountRepo.updateContactsSyncInterval(accountId, days)
            refreshAccount()
        }
    }

    fun setNotesSyncInterval(days: Int) {
        viewModelScope.launch {
            accountRepo.updateNotesSyncInterval(accountId, days)
            refreshAccount()
        }
    }

    fun setCalendarSyncInterval(days: Int) {
        viewModelScope.launch {
            accountRepo.updateCalendarSyncInterval(accountId, days)
            refreshAccount()
        }
    }

    fun setTasksSyncInterval(days: Int) {
        viewModelScope.launch {
            accountRepo.updateTasksSyncInterval(accountId, days)
            refreshAccount()
        }
    }

    fun setAutoCleanupTrashDays(days: Int) {
        viewModelScope.launch {
            accountRepo.updateAutoCleanupTrashDays(accountId, days)
            refreshAccount()
        }
    }

    fun setAutoCleanupDraftsDays(days: Int) {
        viewModelScope.launch {
            accountRepo.updateAutoCleanupDraftsDays(accountId, days)
            refreshAccount()
        }
    }

    fun setAutoCleanupSpamDays(days: Int) {
        viewModelScope.launch {
            accountRepo.updateAutoCleanupSpamDays(accountId, days)
            refreshAccount()
        }
    }

    fun setDownloadsDays(days: Int) {
        viewModelScope.launch {
            settingsRepo.setAutoCleanupDownloadsDays(days)
            _uiState.update { it.copy(downloadsDays = days) }
        }
    }

    fun setRollbackDays(days: Int) {
        viewModelScope.launch {
            settingsRepo.setAutoCleanupRollbackDays(days)
            _uiState.update { it.copy(rollbackDays = days) }
        }
    }

    companion object {
        /**
         * Фабрика: собирает зависимости из RepositoryProvider + боевую реализацию side-effect'ов.
         * Внешняя сигнатура (Application, accountId) не меняется — вызов из Composable прежний.
         */
        fun provideFactory(application: Application, accountId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SyncCleanupViewModel(
                        accountId = accountId,
                        accountRepo = RepositoryProvider.getAccountRepository(application),
                        settingsRepo = SettingsRepository.getInstance(application),
                        effects = AndroidSyncEffects(application)
                    ) as T
                }
            }
    }
}

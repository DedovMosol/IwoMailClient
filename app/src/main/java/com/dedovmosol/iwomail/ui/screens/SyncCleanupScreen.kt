package com.dedovmosol.iwomail.ui.screens

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.repository.SettingsRepository.AutoCleanupDays
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons

/** Интервалы фоновой синхронизации почты, минуты. */
private val SYNC_INTERVALS_MINUTES = listOf(1, 2, 3, 5, 10, 15, 30)

/** Как часто синхронизировать контакты, заметки, календарь и задачи, дни (0 — никогда). */
private val DATA_SYNC_DAYS = listOf(0, 1, 7, 14, 30)

@Composable
fun SyncCleanupScreen(
    accountId: Long,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val isRu = isRussian()

    val viewModel: SyncCleanupViewModel = viewModel(
        factory = SyncCleanupViewModel.provideFactory(
            context.applicationContext as android.app.Application,
            accountId
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val currentAccount = uiState.account ?: return
    val accountType = AccountType.fromStored(currentAccount.accountType)
    val exchange = accountType == AccountType.EXCHANGE
    val syncMode = try {
        SyncMode.valueOf(currentAccount.syncMode)
    } catch (_: Exception) {
        SyncMode.PUSH
    }
    val daysLabel: @Composable (Int) -> String = { getContactsSyncIntervalText(it, isRu) }
    val cleanupLabel: @Composable (AutoCleanupDays) -> String = { it.getDisplayName(isRu) }

    SettingsPageScaffold(title = Strings.syncAndCleanup, onBackClick = onBackClick, hue = SettingsSearchDest.Accounts.hue) {
        item { SettingsSectionTitle(Strings.syncSettings) }
        item {
            SettingsGroup {
                if (exchange) {
                    SettingsChoiceRow(
                        title = Strings.syncMode,
                        options = SyncMode.entries,
                        selected = syncMode,
                        label = { it.getDisplayName(isRu) },
                        onSelect = viewModel::setSyncMode,
                        icon = AppIcons.Sync
                    )
                }
                if (!exchange || syncMode == SyncMode.SCHEDULED) {
                    SettingsChoiceRow(
                        title = Strings.syncInterval,
                        options = SYNC_INTERVALS_MINUTES,
                        selected = currentAccount.syncIntervalMinutes,
                        label = { Strings.minutes(it) },
                        onSelect = viewModel::setSyncInterval,
                        icon = AppIcons.Schedule
                    )
                }
                SettingsSwitchRow(
                    title = Strings.nightMode,
                    description = Strings.nightModeDesc,
                    icon = { Icon(AppIcons.NightsStay, null) },
                    checked = currentAccount.nightModeEnabled,
                    onChange = viewModel::setNightModeEnabled
                )
                SettingsSwitchRow(
                    title = Strings.ignoreBatterySaver,
                    icon = { Icon(AppIcons.BatterySaver, null) },
                    checked = currentAccount.ignoreBatterySaver,
                    onChange = viewModel::setIgnoreBatterySaver
                )
            }
        }
        if (exchange) {
            item {
                SettingsGroup {
                    SettingsChoiceRow(
                        title = Strings.contactsSync,
                        subtitle = Strings.contactsSyncDesc,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.contactsSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setContactsSyncInterval,
                        icon = AppIcons.ContactPhone
                    )
                    SettingsChoiceRow(
                        title = Strings.notesSync,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.notesSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setNotesSyncInterval,
                        icon = AppIcons.StickyNote
                    )
                    SettingsChoiceRow(
                        title = Strings.calendarSync,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.calendarSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setCalendarSyncInterval,
                        icon = AppIcons.CalendarMonth
                    )
                    SettingsChoiceRow(
                        title = Strings.tasksSync,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.tasksSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setTasksSyncInterval,
                        icon = AppIcons.Task
                    )
                }
            }
        }

        // Очистка: раньше — диалог-аккордеон, теперь строка на каждую папку
        item { SettingsSectionTitle(Strings.autoCleanup) }
        item {
            SettingsGroup {
                SettingsChoiceRow(
                    title = Strings.autoCleanupTrash,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(currentAccount.autoCleanupTrashDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setAutoCleanupTrashDays(it.days) },
                    icon = AppIcons.Delete
                )
                SettingsChoiceRow(
                    title = Strings.autoCleanupDrafts,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(currentAccount.autoCleanupDraftsDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setAutoCleanupDraftsDays(it.days) },
                    icon = AppIcons.Drafts
                )
                SettingsChoiceRow(
                    title = Strings.autoCleanupSpam,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(currentAccount.autoCleanupSpamDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setAutoCleanupSpamDays(it.days) },
                    icon = AppIcons.Report
                )
            }
        }
        item {
            SettingsGroupHint(Strings.autoCleanupFoldersHint)
        }
        item { SettingsSectionTitle(Strings.autoCleanupAppFiles) }
        item {
            SettingsGroup {
                SettingsChoiceRow(
                    title = Strings.autoCleanupDownloads,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(uiState.downloadsDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setDownloadsDays(it.days) },
                    icon = AppIcons.Download
                )
                SettingsChoiceRow(
                    title = Strings.autoCleanupRollback,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(uiState.rollbackDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setRollbackDays(it.days) },
                    icon = AppIcons.Restore
                )
            }
        }
    }
}

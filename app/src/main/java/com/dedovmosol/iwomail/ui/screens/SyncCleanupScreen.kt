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

/** Как часто синхронизировать контакты, заметки, календарь и задачи: «Вместе с основной», «Никогда» или дни. */
private val DATA_SYNC_DAYS = com.dedovmosol.iwomail.domain.PimSyncInterval.OPTIONS

/**
 * Синхронизация и очистка аккаунта. [highlight] — id строки из поиска настроек ([rememberSettingsCatalog]): она
 * подсвечивается и прокручивается в видимую часть.
 */
@Composable
fun SyncCleanupScreen(
    accountId: Long,
    onBackClick: () -> Unit,
    highlight: String = ""
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
        // Одним элементом списка: строку из поиска внизу страницы есть кому подсветить и прокрутить к ней (элемент
        // LazyColumn за экраном не создаётся). Строк около двадцати — ленивость не нужна.
        item {
            SettingsSectionTitle(Strings.syncSettings)
            SettingsGroup {
                if (exchange) {
                    SettingsChoiceRow(
                        title = Strings.syncMode,
                        options = SyncMode.entries,
                        selected = syncMode,
                        label = { it.getDisplayName(isRu) },
                        onSelect = viewModel::setSyncMode,
                        icon = AppIcons.Sync,
                        highlightId = "syncMode",
                        highlight = highlight
                    )
                }
                if (!exchange || syncMode == SyncMode.SCHEDULED) {
                    SettingsChoiceRow(
                        title = Strings.syncInterval,
                        options = SYNC_INTERVALS_MINUTES,
                        selected = currentAccount.syncIntervalMinutes,
                        label = { Strings.minutes(it) },
                        onSelect = viewModel::setSyncInterval,
                        icon = AppIcons.Schedule,
                        highlightId = "syncInterval",
                        highlight = highlight
                    )
                }
                SettingsSwitchRow(
                    title = Strings.nightMode,
                    description = Strings.nightModeDesc,
                    icon = { Icon(AppIcons.NightsStay, null) },
                    checked = currentAccount.nightModeEnabled,
                    highlightId = "nightMode",
                    highlight = highlight,
                    onChange = viewModel::setNightModeEnabled
                )
                SettingsSwitchRow(
                    title = Strings.ignoreBatterySaver,
                    icon = { Icon(AppIcons.BatterySaver, null) },
                    checked = currentAccount.ignoreBatterySaver,
                    highlightId = "ignoreBatterySaver",
                    highlight = highlight,
                    onChange = viewModel::setIgnoreBatterySaver
                )
            }
            if (exchange) {
                SettingsGroup {
                    SettingsChoiceRow(
                        title = Strings.contactsSync,
                        subtitle = Strings.contactsSyncDesc,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.contactsSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setContactsSyncInterval,
                        icon = AppIcons.ContactPhone,
                        highlightId = "contactsSync",
                        highlight = highlight
                    )
                    SettingsChoiceRow(
                        title = Strings.notesSync,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.notesSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setNotesSyncInterval,
                        icon = AppIcons.StickyNote,
                        highlightId = "notesSync",
                        highlight = highlight
                    )
                    SettingsChoiceRow(
                        title = Strings.calendarSync,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.calendarSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setCalendarSyncInterval,
                        icon = AppIcons.CalendarMonth,
                        highlightId = "calendarSync",
                        highlight = highlight
                    )
                    SettingsChoiceRow(
                        title = Strings.tasksSync,
                        options = DATA_SYNC_DAYS,
                        selected = currentAccount.tasksSyncIntervalDays,
                        label = daysLabel,
                        onSelect = viewModel::setTasksSyncInterval,
                        icon = AppIcons.Task,
                        highlightId = "tasksSync",
                        highlight = highlight
                    )
                }
            }

            // Очистка: раньше — диалог-аккордеон, теперь строка на каждую папку
            SettingsSectionTitle(Strings.autoCleanup)
            SettingsGroup {
                SettingsChoiceRow(
                    title = Strings.autoCleanupTrash,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(currentAccount.autoCleanupTrashDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setAutoCleanupTrashDays(it.days) },
                    icon = AppIcons.Delete,
                    highlightId = "autoCleanupTrash",
                    highlight = highlight
                )
                SettingsChoiceRow(
                    title = Strings.autoCleanupDrafts,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(currentAccount.autoCleanupDraftsDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setAutoCleanupDraftsDays(it.days) },
                    icon = AppIcons.Drafts,
                    highlightId = "autoCleanupDrafts",
                    highlight = highlight
                )
                SettingsChoiceRow(
                    title = Strings.autoCleanupSpam,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(currentAccount.autoCleanupSpamDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setAutoCleanupSpamDays(it.days) },
                    icon = AppIcons.Report,
                    highlightId = "autoCleanupSpam",
                    highlight = highlight
                )
            }
            SettingsGroupHint(Strings.autoCleanupFoldersHint)
            SettingsSectionTitle(Strings.autoCleanupAppFiles)
            SettingsGroup {
                SettingsChoiceRow(
                    title = Strings.autoCleanupDownloads,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(uiState.downloadsDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setDownloadsDays(it.days) },
                    icon = AppIcons.Download,
                    highlightId = "autoCleanupDownloads",
                    highlight = highlight
                )
                SettingsChoiceRow(
                    title = Strings.autoCleanupRollback,
                    options = AutoCleanupDays.entries,
                    selected = AutoCleanupDays.fromDays(uiState.rollbackDays),
                    label = cleanupLabel,
                    onSelect = { viewModel.setRollbackDays(it.days) },
                    icon = AppIcons.Restore,
                    highlightId = "autoCleanupRollback",
                    highlight = highlight
                )
            }
        }
    }
}

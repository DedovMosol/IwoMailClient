package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.domain.QuietHours
import com.dedovmosol.iwomail.sync.MailNotificationChannels
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.launch

/** Уведомления о почте: тихие часы, тактильный отклик, звук и вибрация каждого аккаунта (каналы системы). */
@Composable
internal fun MailAlertsSettingsSection(
    settingsRepo: SettingsRepository,
    accounts: List<AccountEntity>,
    highlight: String = ""
) {
    val context = LocalContext.current
    val scope = com.dedovmosol.iwomail.ui.components.rememberSafeScope()
    val prefs by settingsRepo.mailAlertPrefs.collectAsStateWithLifecycle(initialValue = null)
    // Открытый выбор времени: "start" или "end" (переживает поворот)
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    fun time(minutes: Int): String = timeFormat.format(
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
            set(java.util.Calendar.MINUTE, minutes % 60)
        }.time
    )
    val ru = isRussian()
    val sameTimeText = Strings.quietHoursSameTime

    Column {
        val quiet = prefs?.quietHours ?: QuietHours(false, QuietHours.DEFAULT_START, QuietHours.DEFAULT_END)
        SettingsSwitchRow(
            title = Strings.quietHours,
            description = Strings.quietHoursDesc("${time(quiet.startMinutes)}–${time(quiet.endMinutes)}"),
            icon = { Icon(AppIcons.Notifications, null) },
            checked = quiet.enabled,
            enabled = prefs != null,
            highlightId = "quietHours",
            highlight = highlight,
            onChange = { enabled -> scope.launch { settingsRepo.setQuietHours(quiet.copy(enabled = enabled)) } }
        )
        if (quiet.enabled) {
            SettingsNavRow(title = Strings.quietStartLabel, icon = AppIcons.Notifications,
                value = time(quiet.startMinutes), highlightId = "quietStart", highlight = highlight,
                onClick = { picking = "start" })
            SettingsNavRow(title = Strings.quietEndLabel, icon = AppIcons.Notifications,
                value = time(quiet.endMinutes), highlightId = "quietEnd", highlight = highlight,
                onClick = { picking = "end" })
        }

        // Тактильный отклик приложения: выделение, свайпы, долгое нажатие (AppHaptics). Системные настройки — сверху.
        val haptics by settingsRepo.hapticsEnabled.collectAsStateWithLifecycle(initialValue = settingsRepo.getHapticsEnabledSync())
        SettingsSwitchRow(
            title = Strings.hapticsSetting,
            description = Strings.hapticsSettingDesc,
            icon = { Icon(AppIcons.Vibration, null) },
            checked = haptics,
            highlightId = "haptics",
            highlight = highlight,
            onChange = { enabled ->
                scope.launch {
                    settingsRepo.setHapticsEnabled(enabled)
                    // Включили — короткое подтверждение, что отклик работает
                    if (enabled) com.dedovmosol.iwomail.ui.utils.AppHaptics.from(context).confirm()
                }
            }
        )

        // Звук, вибрация и важность — у каждого аккаунта свои, в настройках системы (канал аккаунта)
        for (account in accounts) {
            SettingsNavRow(
                title = Strings.accountNotificationSettings,
                subtitle = account.email,
                icon = AppIcons.Email,
                highlightId = accountNotificationsFocus(account.id),
                highlight = highlight,
                onClick = {
                    MailNotificationChannels.ensure(context, account.id, account.email, ru)
                    try {
                        context.startActivity(MailNotificationChannels.settingsIntent(context, account.id))
                    } catch (e: android.content.ActivityNotFoundException) {
                        android.util.Log.w("MailAlertsSettings", "No notification settings screen: ${e.message}")
                    }
                }
            )
        }
    }

    picking?.let { which ->
        val start = which == "start"
        val current = prefs?.quietHours ?: return@let
        val minutes = if (start) current.startMinutes else current.endMinutes
        com.dedovmosol.iwomail.ui.theme.SimpleTimeInputDialog(
            onDismissRequest = { picking = null },
            title = Strings.quietHours,
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            confirmText = Strings.ok,
            dismissText = Strings.cancel,
            onConfirm = { hour, minute ->
                picking = null
                val value = hour * 60 + minute
                val updated = if (start) current.copy(startMinutes = value) else current.copy(endMinutes = value)
                if (updated.startMinutes == updated.endMinutes) {
                    // Пустое окно молча ничего бы не глушило — не сохраняем и говорим почему
                    com.dedovmosol.iwomail.util.SafeToast.short(context, sameTimeText)
                } else {
                    scope.launch { settingsRepo.setQuietHours(updated) }
                }
            },
            onDismiss = { picking = null }
        )
    }
}

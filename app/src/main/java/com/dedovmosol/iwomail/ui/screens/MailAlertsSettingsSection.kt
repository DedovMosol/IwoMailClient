package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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

/** Уведомления о почте: тихие часы, важные отправители, звук и вибрация каждого аккаунта (каналы системы). */
@Composable
internal fun MailAlertsSettingsSection(settingsRepo: SettingsRepository, accounts: List<AccountEntity>) {
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

    Column {
        Text(
            text = Strings.mailAlertsSection,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(16.dp)
        )

        val quiet = prefs?.quietHours ?: QuietHours(false, QuietHours.DEFAULT_START, QuietHours.DEFAULT_END)
        ListItem(
            headlineContent = { Text(Strings.quietHours) },
            supportingContent = { Text(Strings.quietHoursDesc("${time(quiet.startMinutes)}–${time(quiet.endMinutes)}")) },
            leadingContent = { Icon(AppIcons.Notifications, null) },
            trailingContent = {
                Switch(
                    checked = quiet.enabled,
                    enabled = prefs != null,
                    onCheckedChange = { enabled -> scope.launch { settingsRepo.setQuietHours(quiet.copy(enabled = enabled)) } }
                )
            }
        )
        if (quiet.enabled) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 56.dp)) {
                TextButton(onClick = { picking = "start" }) { Text(Strings.quietFrom(time(quiet.startMinutes))) }
                TextButton(onClick = { picking = "end" }) { Text(Strings.quietUntil(time(quiet.endMinutes))) }
            }
        }

        val vip = prefs?.vipSenders.orEmpty().sorted()
        ListItem(
            headlineContent = { Text(Strings.vipSenders) },
            supportingContent = { if (vip.isEmpty()) Text(Strings.vipSendersHint) },
            leadingContent = { Icon(AppIcons.Star, null) }
        )
        for (address in vip) {
            ListItem(
                headlineContent = { Text(address) },
                trailingContent = {
                    IconButton(onClick = { scope.launch { settingsRepo.setVipSender(address, vip = false) } }) {
                        Icon(AppIcons.Close, Strings.removeVipSender)
                    }
                },
                modifier = Modifier.padding(start = 40.dp)
            )
        }

        // Звук, вибрация и важность — у каждого аккаунта свои, в настройках системы (канал аккаунта)
        for (account in accounts) {
            ListItem(
                headlineContent = { Text(Strings.accountNotificationSettings) },
                supportingContent = { Text(account.email) },
                leadingContent = { Icon(AppIcons.Email, null) },
                modifier = Modifier.clickable {
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
                scope.launch {
                    settingsRepo.setQuietHours(if (start) current.copy(startMinutes = value) else current.copy(endMinutes = value))
                }
            },
            onDismiss = { picking = null }
        )
    }
}

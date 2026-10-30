package com.dedovmosol.iwomail.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.dedovmosol.iwomail.domain.FlagPreset
import com.dedovmosol.iwomail.domain.FlagPresets
import com.dedovmosol.iwomail.eas.MailFlag
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.screens.ScheduleSendDialog
import com.dedovmosol.iwomail.ui.theme.AppColors
import com.dedovmosol.iwomail.ui.theme.AppIcons

/**
 * Меню флага письма: срок, напоминание, «выполнено», снять. Выбор — новым флагом целиком ([onChoose]).
 * Показывать рядом со звёздочкой (долгое нажатие).
 */
@Composable
fun FlagMenu(
    expanded: Boolean,
    flag: MailFlag,
    onDismiss: () -> Unit,
    onChoose: (MailFlag) -> Unit
) {
    var pickReminder by rememberSaveable { mutableStateOf(false) }
    if (pickReminder) {
        ScheduleSendDialog(
            onDismiss = { pickReminder = false },
            onSchedule = { time ->
                pickReminder = false
                onChoose(FlagPresets.withReminder(flag, time))
            },
            title = Strings.flagRemind.trimEnd('…'),
            timeLabel = Strings.flagRemind.trimEnd('…'),
            confirmLabel = Strings.ok
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        fun choose(preset: FlagPreset) {
            onDismiss()
            onChoose(FlagPresets.flag(preset, flag, System.currentTimeMillis()))
        }
        DropdownMenuItem(text = { Text(Strings.flagToday) }, onClick = { choose(FlagPreset.TODAY) })
        DropdownMenuItem(text = { Text(Strings.flagTomorrow) }, onClick = { choose(FlagPreset.TOMORROW) })
        DropdownMenuItem(text = { Text(Strings.flagThisWeek) }, onClick = { choose(FlagPreset.THIS_WEEK) })
        DropdownMenuItem(text = { Text(Strings.flagNextWeek) }, onClick = { choose(FlagPreset.NEXT_WEEK) })
        DropdownMenuItem(text = { Text(Strings.flagNoDate) }, onClick = { choose(FlagPreset.NO_DATE) })
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(Strings.flagRemind) },
            leadingIcon = { Icon(AppIcons.Notifications, null) },
            onClick = { onDismiss(); pickReminder = true }
        )
        if (!flag.completed) {
            DropdownMenuItem(
                text = { Text(Strings.flagComplete) },
                leadingIcon = { Icon(AppIcons.CheckCircle, null) },
                onClick = { onDismiss(); onChoose(MailFlag(MailFlag.STATUS_COMPLETE)) }
            )
        }
        if (flag.status != MailFlag.STATUS_CLEARED) {
            DropdownMenuItem(
                text = { Text(Strings.flagClear) },
                leadingIcon = { Icon(AppIcons.Close, null) },
                onClick = { onDismiss(); onChoose(MailFlag.NONE) }
            )
        }
    }
}

/** Значок флага: звезда (активен/нет) или галочка (выполнен). */
@Composable
fun flagIcon(flag: MailFlag) = when {
    flag.completed -> AppIcons.CheckCircle
    flag.active -> AppIcons.Star
    else -> AppIcons.StarOutline
}

@Composable
fun flagTint(flag: MailFlag) = when {
    flag.completed -> AppColors.favorites.copy(alpha = 0.6f)
    flag.active -> AppColors.favorites
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Срок флага коротко: «Срок: завтра», «Просрочено»; без срока — ничего. */
@Composable
fun FlagDueText(flag: MailFlag, modifier: Modifier = Modifier) {
    val due = flag.dueDate?.takeIf { flag.active } ?: return
    val state = remember(due) { com.dedovmosol.iwomail.domain.FlagDue.of(due, System.currentTimeMillis()) }
    val dateText = remember(due) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(due))
    }
    val text = when (state) {
        com.dedovmosol.iwomail.domain.FlagDue.OVERDUE -> Strings.flagOverdue
        com.dedovmosol.iwomail.domain.FlagDue.TODAY -> Strings.flagDue(Strings.flagToday.lowercase())
        com.dedovmosol.iwomail.domain.FlagDue.TOMORROW -> Strings.flagDue(Strings.flagTomorrow.lowercase())
        com.dedovmosol.iwomail.domain.FlagDue.LATER -> Strings.flagDue(dateText)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (state == com.dedovmosol.iwomail.domain.FlagDue.OVERDUE) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

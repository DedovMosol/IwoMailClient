package com.dedovmosol.iwomail.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog
import com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton

/** Запрос, который приложение показывает само (не системное окно Android). */
enum class AppPermissionPrompt {
    BackgroundWork,
    ExactAlarms
}

/** Какая иконка у запроса: батарея не для будильников. */
fun AppPermissionPrompt.iconKey(): String = when (this) {
    AppPermissionPrompt.BackgroundWork -> "battery"
    AppPermissionPrompt.ExactAlarms -> "schedule"
}

/**
 * Тот же каркас, что у остальных диалогов ([ScaledAlertDialog]): иконка M3, без «красной строки»,
 * кнопки «Позже» / «Продолжить». Системный запрос POST_NOTIFICATIONS этим не заменяется.
 */
@Composable
fun AppPermissionDialog(
    prompt: AppPermissionPrompt,
    title: String,
    text: String,
    dismissText: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = when (prompt) {
                    AppPermissionPrompt.BackgroundWork -> AppIcons.BatteryChargingFull
                    AppPermissionPrompt.ExactAlarms -> AppIcons.Schedule
                },
                contentDescription = null
            )
        },
        title = { Text(title) },
        text = { Text(text, textAlign = TextAlign.Start) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
        confirmButton = {
            ThemeOutlinedButton(onClick = onConfirm, text = confirmText)
        }
    )
}

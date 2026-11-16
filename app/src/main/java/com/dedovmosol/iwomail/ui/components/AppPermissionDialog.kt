package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme
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
 * Каркас как у остальных диалогов, иконка в скруглении цветом темы (как плитки главной),
 * не голый серый Alert. Системный POST_NOTIFICATIONS этим не заменяется.
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
    val theme = LocalColorTheme.current
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (prompt) {
                        AppPermissionPrompt.BackgroundWork -> AppIcons.BatteryChargingFull
                        AppPermissionPrompt.ExactAlarms -> AppIcons.Schedule
                    },
                    contentDescription = null,
                    tint = theme.gradientStart
                )
            }
        },
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
        confirmButton = {
            ThemeOutlinedButton(onClick = onConfirm, text = confirmText)
        }
    )
}

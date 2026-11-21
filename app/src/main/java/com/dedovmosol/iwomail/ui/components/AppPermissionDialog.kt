package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.LocalColorTheme

/** Запрос, который приложение показывает само (не системное окно Android). */
enum class AppPermissionPrompt {
    BackgroundWork,
    ExactAlarms
}

fun AppPermissionPrompt.iconKey(): String = when (this) {
    AppPermissionPrompt.BackgroundWork -> "battery"
    AppPermissionPrompt.ExactAlarms -> "schedule"
}

/**
 * Как карточка на главной: градиент темы, скругление extraLarge, основная кнопка цветом градиента.
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
    val gradient = remember(theme.gradientStart, theme.gradientEnd) {
        Brush.horizontalGradient(listOf(theme.gradientStart, theme.gradientEnd))
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(gradient)
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (prompt) {
                                    AppPermissionPrompt.BackgroundWork -> AppIcons.BatteryChargingFull
                                    AppPermissionPrompt.ExactAlarms -> AppIcons.Schedule
                                },
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 2
                        )
                    }
                }
                Text(
                    text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = if (prompt == AppPermissionPrompt.BackgroundWork) TextAlign.Center else TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 16.dp, bottom = 12.dp),
                    // Как у остальных диалогов приложения (ScaledAlertDialog): отказ слева, согласие справа — не
                    // рядом, мимо не нажать
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text(dismissText) }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.gradientStart)
                    ) { Text(confirmText) }
                }
            }
        }
    }
}

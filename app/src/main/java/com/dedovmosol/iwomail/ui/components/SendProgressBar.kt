package com.dedovmosol.iwomail.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppIcons

/**
 * Данные вложения для отправки
 */
data class AttachmentData(
    val name: String,
    val mimeType: String,
    val data: ByteArray
)

/**
 * Данные для отправки письма
 */
data class PendingEmail(
    val account: AccountEntity,
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    val attachments: List<AttachmentData>,
    val importance: Int,
    val requestReadReceipt: Boolean,
    val requestDeliveryReceipt: Boolean,
    val draftId: String? = null, // ID черновика для удаления после отправки
    val forwardSourceFolderId: String? = null, // ServerId папки оригинального письма (для SmartForward)
    val forwardSourceItemId: String? = null    // ServerId оригинального письма (для SmartForward)
) {
    fun toOutgoingMail() = OutgoingMail(
        to = to, cc = cc, bcc = bcc, subject = subject, body = body,
        attachments = attachments.map { Triple(it.name, it.mimeType, it.data) },
        importance = importance,
        requestReadReceipt = requestReadReceipt,
        requestDeliveryReceipt = requestDeliveryReceipt,
        forwardFolderId = forwardSourceFolderId,
        forwardItemId = forwardSourceItemId
    )
}

/**
 * Состояние отправки. В фазе [SendPhase.COUNTDOWN] письмо ждёт [countdownMs] с момента [countdownStartedAt]
 * (SystemClock.elapsedRealtime) — по ним плашка рисует прогресс и после поворота экрана.
 */
data class SendState(
    val isActive: Boolean = false,
    val message: String = "",
    val phase: SendPhase = SendPhase.SENDING,
    val countdownStartedAt: Long = 0L,
    val countdownMs: Long = 0L
)

enum class SendPhase {
    COUNTDOWN,  // Ожидание (можно отменить)
    SENDING     // Подготовка и отправка (нельзя отменить)
}

/**
 * CompositionLocal для доступа к контроллеру отправки
 */
val LocalSendController = staticCompositionLocalOf { SendController.shared }

// Зелёные цвета для прогресс-бара отправки
private val SendContainerColor = Color(0xFFE8F5E9) // Light green background
private val SendContentColor = Color(0xFF2E7D32) // Dark green text
private val SendProgressColor = Color(0xFF4CAF50) // Green progress

/**
 * Плашка отправки (зелёная): ожидание с отсчётом секунд и кнопкой «Отменить», затем отправка.
 * Прогресс ожидания — анимацией (читается при отрисовке, без перекомпоновки на каждый кадр).
 */
@Composable
fun SendProgressBar(
    controller: SendController,
    modifier: Modifier = Modifier
) {
    val state = controller.state
    if (!state.isActive) return

    val countdown = state.phase == SendPhase.COUNTDOWN
    val progress = remember(state.countdownStartedAt) { Animatable(0f) }
    LaunchedEffect(state.countdownStartedAt, countdown) {
        if (!countdown || state.countdownMs <= 0) return@LaunchedEffect
        // С учётом уже прошедшего (поворот экрана): плашка не начинает отсчёт заново
        val elapsed = (SystemClock.elapsedRealtime() - state.countdownStartedAt).coerceIn(0L, state.countdownMs)
        progress.snapTo(elapsed.toFloat() / state.countdownMs)
        progress.animateTo(1f, tween((state.countdownMs - elapsed).toInt(), easing = LinearEasing))
    }
    val secondsLeft by remember(state.countdownStartedAt) {
        derivedStateOf { kotlin.math.ceil((1f - progress.value) * state.countdownMs / 1000f).toInt().coerceAtLeast(1) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 80.dp),
        color = SendContainerColor,
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = SendContentColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (countdown) Strings.sendingIn(secondsLeft) else Strings.sending,
                        style = MaterialTheme.typography.bodySmall,
                        color = SendContentColor.copy(alpha = 0.7f)
                    )
                }

                // Кнопка отмены только во время ожидания
                if (countdown) {
                    TextButton(
                        onClick = { controller.cancel() },
                        colors = ButtonDefaults.textButtonColors(contentColor = SendContentColor)
                    ) {
                        Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(Strings.undo)
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = SendProgressColor,
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { if (countdown) progress.value else 1f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = SendProgressColor,
                trackColor = SendContentColor.copy(alpha = 0.2f)
            )
        }
    }
}

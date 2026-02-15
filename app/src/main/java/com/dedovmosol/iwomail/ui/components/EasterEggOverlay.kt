package com.dedovmosol.iwomail.ui.components

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dedovmosol.iwomail.R

private const val PREFS_NAME = "iwo_easter"
private const val KEY_FOUND = "easter_found"

/**
 * Синглтон для MediaPlayer пасхалки.
 * Переживает поворот экрана (configuration change) — музыка не прерывается.
 * При смене ориентации меняется только фоновая картинка.
 */
object EasterEggPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var onComplete: (() -> Unit)? = null
    private var lastConfigChangeAt: Long = 0L

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true

    fun start(context: Context, resId: Int, onDone: () -> Unit) {
        // Если уже играет — просто обновляем callback (после поворота)
        if (mediaPlayer?.isPlaying == true) {
            onComplete = onDone
            mediaPlayer?.setOnCompletionListener { stop(); onDone() }
            return
        }
        stop() // На всякий случай
        onComplete = onDone
        try {
            val mp = MediaPlayer.create(context.applicationContext, resId)
            mp?.setOnCompletionListener { stop(); onDone() }
            mp?.start()
            mediaPlayer = mp
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        onComplete = null
    }

    fun markConfigChanged() {
        lastConfigChangeAt = System.currentTimeMillis()
    }

    fun isRecentConfigChange(windowMs: Long = 2000L): Boolean {
        return System.currentTimeMillis() - lastConfigChangeAt <= windowMs
    }
}

fun isEasterEggFound(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_FOUND, false)
}

private fun markEasterEggFound(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_FOUND, true).apply()
}

@Suppress("DEPRECATION")
private fun vibrateDevice(context: Context) {
    try {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            vibrator.vibrate(300)
        }
    } catch (_: Exception) {}
}

@Composable
fun EasterEggOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    LaunchedEffect(configuration.orientation, visible) {
        if (visible) {
            EasterEggPlayer.markConfigChanged()
        }
    }

    // Запуск звука и вибрации при появлении.
    // MediaPlayer живёт в EasterEggPlayer-синглтоне и переживает поворот экрана.
    DisposableEffect(visible) {
        if (visible) {
            markEasterEggFound(context)
            if (!EasterEggPlayer.isPlaying) {
                vibrateDevice(context)
            }
            EasterEggPlayer.start(context, R.raw.pashalka_iwo, onDismiss)
        }
        onDispose {
            // НЕ останавливаем музыку при onDispose — она должна пережить поворот.
            // Остановка только по клику (onDismiss) или по завершении трека.
        }
    }

    // Пульсирующая анимация логотипа
    val infiniteTransition = rememberInfiniteTransition(label = "iwo_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(400))
    ) {
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bgImage = if (isLandscape) R.drawable.pashalka_albom else R.drawable.pashalka_portret

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    EasterEggPlayer.stop()
                    onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            // Полупрозрачная фоновая картинка
            Image(
                painter = painterResource(id = bgImage),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.45f),
                contentScale = ContentScale.Crop
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Логотип "iwo"
                Text(
                    text = "iwo",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = glowAlpha),
                    modifier = Modifier.scale(pulseScale)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Текст песни
                Text(
                    text = "I want out, to live my life alone\nI want out, leave me be\nI want out, to do things on my own\nI want out, to live my life and to be free",
                    fontSize = 18.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Кредит
                Text(
                    text = "Helloween — I Want Out",
                    fontSize = 14.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }
        }
    }
}

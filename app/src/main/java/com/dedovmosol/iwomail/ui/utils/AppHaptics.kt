package com.dedovmosol.iwomail.ui.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Тактильный отклик выделения. Compose [androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove]
 * на многих OEM (Samsung, Xiaomi) ничего не делает: это отклик текстового курсора, не выбора в списке.
 *
 * Здесь — системный тактильный API ([VibrationEffect]): короткий тик при смене строки, чуть сильнее —
 * подтверждение долгого нажатия. Нет отклика на устройстве, выключен системный «виброотклик» или отказ API —
 * тишина, без исключения.
 *
 * Один экземпляр на процесс: строки LazyColumn не ходят в [Context.getSystemService] при каждой композиции.
 */
class AppHaptics internal constructor(
    private val hasVibrator: Boolean,
    private val systemEnabled: () -> Boolean,
    private val sdk: Int,
    private val playEffect: (VibrationEffect) -> Unit
) {
    fun tick() = play(Pulse.Tick)

    fun confirm() = play(Pulse.Confirm)

    private fun play(pulse: Pulse) {
        if (!shouldPlay(hasVibrator, systemEnabled())) return
        try {
            playEffect(effect(sdk, pulse))
        } catch (_: Exception) {
            // OEM без эффекта / SecurityException на редких прошивках
        }
    }

    enum class Pulse { Tick, Confirm }

    companion object {
        fun shouldPlay(hasVibrator: Boolean, systemEnabled: Boolean): Boolean =
            hasVibrator && systemEnabled

        /** API 29+: системный тик/клик. Ниже — короткий one-shot (VibrationEffect есть с API 26 = minSdk). */
        fun effect(sdk: Int, pulse: Pulse): VibrationEffect {
            if (sdk >= 29) {
                val id = when (pulse) {
                    Pulse.Tick -> VibrationEffect.EFFECT_TICK
                    Pulse.Confirm -> VibrationEffect.EFFECT_CLICK
                }
                return VibrationEffect.createPredefined(id)
            }
            val ms = when (pulse) {
                Pulse.Tick -> 12L
                Pulse.Confirm -> 25L
            }
            return VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        @Volatile
        private var cached: AppHaptics? = null
        private val lock = Any()

        fun from(context: Context, sdk: Int = Build.VERSION.SDK_INT): AppHaptics {
            cached?.let { return it }
            synchronized(lock) {
                cached?.let { return it }
                val app = context.applicationContext
                val vibrator = resolveVibrator(app, sdk)
                val created = AppHaptics(
                    hasVibrator = vibrator?.hasVibrator() == true,
                    systemEnabled = { isSystemHapticEnabled(app) },
                    sdk = sdk,
                    playEffect = { effect -> vibrator?.vibrate(effect) }
                )
                cached = created
                return created
            }
        }

        internal fun clearCacheForTests() {
            synchronized(lock) { cached = null }
        }

        fun isSystemHapticEnabled(context: Context): Boolean {
            return try {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    1
                ) != 0
            } catch (_: Exception) {
                true
            }
        }

        private fun resolveVibrator(app: Context, sdk: Int): Vibrator? {
            return try {
                if (sdk >= Build.VERSION_CODES.S) {
                    val manager = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    manager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val context = LocalContext.current
    return remember(context.applicationContext) { AppHaptics.from(context) }
}

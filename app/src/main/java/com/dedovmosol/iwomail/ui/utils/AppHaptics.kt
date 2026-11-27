package com.dedovmosol.iwomail.ui.utils

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
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
 * подтверждение долгого нажатия. Вибрация помечена как отклик на касание — так же, как системный отклик
 * интерфейса: Android 13+ — [VibrationAttributes.USAGE_TOUCH], ниже — [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION].
 * Поэтому её масштабирует и глушит системная «вибрация при касании» (HyperOS, One UI, ColorOS), а не громкость
 * уведомлений или будильника. Нет вибратора, выключен отклик в приложении или в системе, отказ API — тишина.
 *
 * Тики протягивания — не чаще [TICK_MIN_INTERVAL_MS]: при быстрой прокрутке вибратор иначе получал запрос каждый
 * кадр и на части прошивок гудел сплошь или пропускал тики.
 *
 * Один экземпляр на процесс: строки LazyColumn не ходят в [Context.getSystemService] при каждой композиции.
 */
class AppHaptics internal constructor(
    private val hasVibrator: Boolean,
    private val systemEnabled: () -> Boolean,
    private val sdk: Int,
    private val playEffect: (VibrationEffect) -> Unit,
    private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
    private val appEnabled: () -> Boolean = { true },
    /** API 30+: поддерживает ли вибратор системный тик; null — неизвестно (тогда тик, у системы есть замена). */
    private val tickSupported: () -> Boolean? = { null }
) {
    @Volatile
    private var lastConfirmAt = Long.MIN_VALUE / 2
    @Volatile
    private var lastTickAt = Long.MIN_VALUE / 2

    fun tick() = play(Pulse.Tick)

    fun confirm() = play(Pulse.Confirm)

    private fun play(pulse: Pulse) {
        if (!appEnabled() || !shouldPlay(hasVibrator, systemEnabled())) return
        val t = now()
        when (pulse) {
            // Долгое нажатие строки ловят сразу два обработчика (combinedClickable строки и drag-выделение списка):
            // второе подтверждение в том же жесте — одна вибрация, не двойной толчок
            Pulse.Confirm -> {
                if (t - lastConfirmAt < CONFIRM_DEDUP_MS) return
                lastConfirmAt = t
            }
            Pulse.Tick -> {
                if (t - lastTickAt < TICK_MIN_INTERVAL_MS) return
                lastTickAt = t
            }
        }
        try {
            playEffect(effect(sdk, pulse, tickSupported()))
        } catch (_: Exception) {
            // OEM без эффекта / SecurityException на редких прошивках
        }
    }

    enum class Pulse { Tick, Confirm }

    companion object {
        internal const val CONFIRM_DEDUP_MS = 150L
        /** Не чаще ~20 тиков в секунду: тик различим, вибратор успевает остановиться между ними. */
        internal const val TICK_MIN_INTERVAL_MS = 50L
        /** API 26–28: короче ~20 мс мотор ERM (бюджетные и старые устройства) не успевает раскрутиться — тишина. */
        internal const val LEGACY_TICK_MS = 20L
        internal const val LEGACY_CONFIRM_MS = 35L

        fun shouldPlay(hasVibrator: Boolean, systemEnabled: Boolean): Boolean =
            hasVibrator && systemEnabled

        /**
         * API 29+: системный тик/клик. Вибратор без тика (API 30+ сообщает «не поддерживается») — клик, иначе на таком
         * устройстве протягивание было бы беззвучным. Ниже API 29 — one-shot ([VibrationEffect] есть с API 26 = minSdk).
         * [sdk] для тестов; Lint не видит параметр как guard, поэтому NewApi подавлен — вызов только при sdk>=29.
         */
        @Suppress("NewApi")
        fun effect(sdk: Int, pulse: Pulse, tickSupported: Boolean? = null): VibrationEffect {
            if (sdk >= 29) {
                val id = when {
                    pulse == Pulse.Confirm -> VibrationEffect.EFFECT_CLICK
                    tickSupported == false -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_TICK
                }
                return VibrationEffect.createPredefined(id)
            }
            val ms = when (pulse) {
                Pulse.Tick -> LEGACY_TICK_MS
                Pulse.Confirm -> LEGACY_CONFIRM_MS
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
                val settings = com.dedovmosol.iwomail.data.repository.SettingsRepository.getInstance(app)
                val created = AppHaptics(
                    hasVibrator = vibrator?.hasVibrator() == true,
                    systemEnabled = systemHapticGate(app, sdk),
                    sdk = sdk,
                    playEffect = { effect -> playOnDevice(vibrator, effect, sdk) },
                    appEnabled = settings::getHapticsEnabledSync,
                    tickSupported = tickSupport(vibrator, sdk)
                )
                cached = created
                return created
            }
        }

        internal fun clearCacheForTests() {
            synchronized(lock) { cached = null }
        }

        @Suppress("DEPRECATION")
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

        /**
         * Android 13+: системный «отклик на касание» сам масштабирует и глушит вибрации USAGE_TOUCH; устаревший
         * HAPTIC_FEEDBACK_ENABLED там часть прошивок не обновляет — по нему вибрация пропадала зря. Ниже — флаг
         * читается один раз и обновляется наблюдателем, а не запросом к ContentResolver на каждый тик.
         */
        private fun systemHapticGate(app: Context, sdk: Int): () -> Boolean {
            if (sdk >= 33) return { true }
            val enabled = java.util.concurrent.atomic.AtomicBoolean(isSystemHapticEnabled(app))
            try {
                @Suppress("DEPRECATION")
                app.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED), false,
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            enabled.set(isSystemHapticEnabled(app))
                        }
                    }
                )
            } catch (_: Exception) {
                // Без наблюдателя — значение на момент запуска
            }
            return enabled::get
        }

        /** Ответ вибратора не меняется — спрашиваем один раз. */
        @Suppress("NewApi")
        private fun tickSupport(vibrator: Vibrator?, sdk: Int): () -> Boolean? {
            if (vibrator == null || sdk < 30) return { null }
            val support = try {
                vibrator.areEffectsSupported(VibrationEffect.EFFECT_TICK).firstOrNull()
            } catch (_: Exception) {
                null
            }
            val result = when (support) {
                Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> true
                Vibrator.VIBRATION_EFFECT_SUPPORT_NO -> false
                else -> null
            }
            return { result }
        }

        private val legacyTouchAttributes: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }

        /** Вибрация как отклик на касание (см. описание класса): атрибуты на всех версиях Android. */
        @Suppress("NewApi", "DEPRECATION")
        private fun playOnDevice(vibrator: Vibrator?, effect: VibrationEffect, sdk: Int) {
            if (vibrator == null) return
            if (sdk >= 33) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()
                )
            } else {
                vibrator.vibrate(effect, legacyTouchAttributes)
            }
        }

        @Suppress("NewApi")
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

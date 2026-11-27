package com.dedovmosol.iwomail.ui.utils

import android.os.VibrationEffect
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Выбор эффекта и когда бить: TextHandleMove не используем.
 * API 29+ — системный тик/клик, 26–28 — короткий one-shot.
 * Нет вибратора или выключен системный виброотклик — тишина.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppHapticsTest {

    @After
    fun tearDown() {
        AppHaptics.clearCacheForTests()
    }

    @Test
    fun `API 29 tick is the system tick, confirm is a click`() {
        val tick = AppHaptics.effect(29, AppHaptics.Pulse.Tick)
        val confirm = AppHaptics.effect(29, AppHaptics.Pulse.Confirm)

        assertThat(tick).isEqualTo(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        assertThat(confirm).isEqualTo(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        assertThat(tick).isNotEqualTo(confirm)
    }

    @Test
    fun `API 26 uses a one-shot long enough for an ERM motor because predefined effects do not exist yet`() {
        val tick = AppHaptics.effect(26, AppHaptics.Pulse.Tick)
        val confirm = AppHaptics.effect(26, AppHaptics.Pulse.Confirm)

        assertThat(tick).isEqualTo(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE))
        assertThat(confirm).isEqualTo(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
        assertThat(tick).isNotEqualTo(confirm)
    }

    @Test
    fun `a vibrator that reports no system tick gets a click so drag selection is not silent`() {
        assertThat(AppHaptics.effect(30, AppHaptics.Pulse.Tick, tickSupported = false))
            .isEqualTo(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        // Неизвестно — системный тик: у Android для него есть замена
        assertThat(AppHaptics.effect(30, AppHaptics.Pulse.Tick, tickSupported = null))
            .isEqualTo(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    @Test
    fun `app setting off silences ticks and confirms`() {
        var played = 0
        var appOn = false
        val haptics = AppHaptics(
            hasVibrator = true,
            systemEnabled = { true },
            sdk = 34,
            playEffect = { played++ },
            appEnabled = { appOn }
        )
        haptics.tick()
        haptics.confirm()
        assertThat(played).isEqualTo(0)
        appOn = true
        haptics.confirm()
        assertThat(played).isEqualTo(1)
    }

    @Test
    fun `ticks during a fast drag are throttled`() {
        var clock = 10_000L
        var played = 0
        val haptics = AppHaptics(
            hasVibrator = true,
            systemEnabled = { true },
            sdk = 34,
            playEffect = { played++ },
            now = { clock }
        )
        // Автопрокрутка: новая строка каждый кадр (16 мс) в течение 480 мс
        repeat(30) { haptics.tick(); clock += 16 }
        // Тики проходят на 0, 64, 128 … 448 мс — 8 из 30 запросов
        assertThat(played).isEqualTo(8)
        // Медленное протягивание — каждый тик
        played = 0
        repeat(5) { clock += AppHaptics.TICK_MIN_INTERVAL_MS; haptics.tick() }
        assertThat(played).isEqualTo(5)
    }

    @Test
    @Config(sdk = [28])
    fun `Android 9 plays a one-shot marked as touch feedback`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppHaptics.from(context).tick()
        val vibrator = org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
        assertThat(vibrator.milliseconds).isEqualTo(AppHaptics.LEGACY_TICK_MS)
        assertThat(vibrator.audioAttributesFromLastVibration?.usage)
            .isEqualTo(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    }

    @Test
    @Config(sdk = [30])
    fun `Android 11 plays the system tick marked as touch feedback`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppHaptics.from(context).tick()
        val vibrator = org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
        assertThat(vibrator.effectId).isEqualTo(VibrationEffect.EFFECT_TICK)
        assertThat(vibrator.audioAttributesFromLastVibration?.usage)
            .isEqualTo(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 plays the system tick with USAGE_TOUCH`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppHaptics.from(context).tick()
        val manager = context.getSystemService(android.os.VibratorManager::class.java)
        val vibrator = org.robolectric.Shadows.shadowOf(manager.defaultVibrator)
        // Сам эффект (системный тик) проверяет тест выбора эффекта: Robolectric на API 31+ не сохраняет id эффекта
        val attributes = vibrator.vibrationAttributesFromLastVibration as android.os.VibrationAttributes
        assertThat(attributes.usage).isEqualTo(android.os.VibrationAttributes.USAGE_TOUCH)
    }

    @Test
    @Config(sdk = [28])
    fun `turning system touch vibration off while the app runs silences it without a restart`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val haptics = AppHaptics.from(context)
        @Suppress("DEPRECATION")
        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        org.robolectric.shadows.ShadowVibrator.reset()
        haptics.confirm()
        val vibrator = org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
        assertThat(vibrator.isVibrating).isFalse()
        assertThat(vibrator.milliseconds).isEqualTo(0L)
    }

    @Test
    fun `app setting is read from settings without waiting for DataStore`() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = com.dedovmosol.iwomail.data.repository.SettingsRepository.getInstance(context)
        settings.setHapticsEnabled(false)
        assertThat(settings.getHapticsEnabledSync()).isFalse()
        settings.setHapticsEnabled(true)
        assertThat(settings.getHapticsEnabledSync()).isTrue()
    }

    @Test
    fun `should play only with a vibrator and system haptic on`() {
        assertThat(AppHaptics.shouldPlay(hasVibrator = true, systemEnabled = true)).isTrue()
        assertThat(AppHaptics.shouldPlay(hasVibrator = false, systemEnabled = true)).isFalse()
        assertThat(AppHaptics.shouldPlay(hasVibrator = true, systemEnabled = false)).isFalse()
        assertThat(AppHaptics.shouldPlay(hasVibrator = false, systemEnabled = false)).isFalse()
    }

    @Test
    fun `missing vibrator does not play`() {
        var played = 0
        val haptics = AppHaptics(
            hasVibrator = false,
            systemEnabled = { true },
            sdk = 34,
            playEffect = { played++ }
        )
        haptics.tick()
        haptics.confirm()
        assertThat(played).isEqualTo(0)
    }

    @Test
    fun `system haptic off does not play`() {
        var played = 0
        val haptics = AppHaptics(
            hasVibrator = true,
            systemEnabled = { false },
            sdk = 34,
            playEffect = { played++ }
        )
        haptics.tick()
        assertThat(played).isEqualTo(0)
    }

    @Test
    fun `tick and confirm each play when enabled`() {
        val effects = mutableListOf<VibrationEffect>()
        val haptics = AppHaptics(
            hasVibrator = true,
            systemEnabled = { true },
            sdk = 34,
            playEffect = { effects += it }
        )
        haptics.tick()
        haptics.confirm()
        assertThat(effects).containsExactly(
            AppHaptics.effect(34, AppHaptics.Pulse.Tick),
            AppHaptics.effect(34, AppHaptics.Pulse.Confirm)
        ).inOrder()
    }

    @Test
    fun `two confirms of one long press vibrate once, a later press vibrates again`() {
        var clock = 1_000L
        var played = 0
        val haptics = AppHaptics(
            hasVibrator = true,
            systemEnabled = { true },
            sdk = 34,
            playEffect = { played++ },
            now = { clock }
        )
        haptics.confirm()
        clock += 40 // drag-выделение и combinedClickable в одном жесте
        haptics.confirm()
        assertThat(played).isEqualTo(1)
        clock += AppHaptics.CONFIRM_DEDUP_MS
        haptics.confirm()
        assertThat(played).isEqualTo(2)
        // Тики при протягивании подтверждением не гасятся (свой предел частоты — отдельный тест)
        haptics.tick(); clock += AppHaptics.TICK_MIN_INTERVAL_MS; haptics.tick()
        assertThat(played).isEqualTo(4)
    }

    @Test
    fun `player exception is swallowed`() {
        val haptics = AppHaptics(
            hasVibrator = true,
            systemEnabled = { true },
            sdk = 26,
            playEffect = { error("oem") }
        )
        haptics.tick()
        haptics.confirm()
    }

    @Test
    fun `from caches one instance so list rows do not look up the service again`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = AppHaptics.from(context)
        val second = AppHaptics.from(context)
        assertThat(first).isSameInstanceAs(second)
    }
}

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
    fun `API 26 uses a short one-shot because predefined effects do not exist yet`() {
        val tick = AppHaptics.effect(26, AppHaptics.Pulse.Tick)
        val confirm = AppHaptics.effect(26, AppHaptics.Pulse.Confirm)

        assertThat(tick).isEqualTo(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE))
        assertThat(confirm).isEqualTo(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))
        assertThat(tick).isNotEqualTo(confirm)
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

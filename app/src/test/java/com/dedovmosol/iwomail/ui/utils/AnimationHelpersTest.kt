package com.dedovmosol.iwomail.ui.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ONB-19: бесконечная анимация не перекомпоновывает экран на каждом кадре — значение читается при отрисовке слоя.
 * Без анимаций значение постоянное.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class AnimationHelpersTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a running pulse does not recompose its caller every frame`() {
        var compositions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val pulse = rememberPulseScale(enabled = true, from = 1f, to = 1.1f, durationMs = 1000)
            val rotation = rememberRotation(enabled = true, durationMs = 1000)
            SideEffect { compositions++ }
            Box(Modifier.size(10.dp).animatedScale(pulse).animatedRotation(rotation))
        }
        rule.waitForIdle()
        val afterStart = compositions
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
        // 60 кадров анимации — ни одной новой композиции вызывающего
        assertThat(compositions).isEqualTo(afterStart)
    }

    @Test
    fun `control - reading the value in composition recomposes every frame (the old helper behaviour)`() {
        var compositions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val scale = rememberPulseScale(enabled = true, from = 1f, to = 1.1f, durationMs = 1000).value
            SideEffect { compositions++ }
            Box(Modifier.size(10.dp).animatedScale(androidx.compose.runtime.rememberUpdatedState(scale)))
        }
        rule.waitForIdle()
        val afterStart = compositions
        repeat(60) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
        assertThat(compositions - afterStart).isAtLeast(30)
    }

    @Test
    fun `disabled animations give a constant value`() {
        var pulse = -1f
        var rotation = -1f
        var shake = -1f
        var wobble = -1f
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.size(10.dp))
            pulse = rememberPulseScale(enabled = false, from = 1f, to = 1.1f).value
            rotation = rememberRotation(enabled = false).value
            shake = rememberShake(enabled = false).value
            wobble = rememberWobble(enabled = false).value
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertThat(pulse).isEqualTo(1f)
        assertThat(rotation).isEqualTo(0f)
        assertThat(shake).isEqualTo(0f)
        assertThat(wobble).isEqualTo(0f)
    }
}

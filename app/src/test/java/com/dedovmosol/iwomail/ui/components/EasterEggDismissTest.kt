package com.dedovmosol.iwomail.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EasterEggDismissTest {
    @get:Rule val rule = createComposeRule()

    private fun dismissWith(gesture: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
        var closed = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            var visible by remember { mutableStateOf(true) }
            EasterEggOverlay(visible) { visible = false; closed++ }
        }
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().performTouchInput(gesture)
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun `horizontal swipe dismisses the overlay`() = dismissWith { swipeLeft() }
    @Test fun `vertical swipe dismisses the overlay`() = dismissWith { swipeUp() }
    @Test fun `tap still dismisses once`() = dismissWith { click() }
}

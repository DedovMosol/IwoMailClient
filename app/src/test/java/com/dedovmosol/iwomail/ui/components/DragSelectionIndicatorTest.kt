package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MAIL-19: значок выбора различим (контраст не меньше 3:1), строка списка объявляет, выбрана ли она. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DragSelectionIndicatorTest {

    @get:Rule
    val rule = createComposeRule()

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun `checked circle and its check mark have enough contrast`() {
        assertThat(contrast(DragSelectionCheckedColor, Color.White)).isAtLeast(4.5f)
        assertThat(contrast(DragSelectionCheckedColor, Color(0xFF121212))).isAtLeast(3f)
        // Прежний цвет — 2,1:1 с белой галочкой
        assertThat(contrast(Color(0xFF68C957), Color.White)).isLessThan(3f)
    }

    @Test
    fun `unchecked outline stands out from the surface in both themes`() {
        val outlines = mutableListOf<Pair<Color, Color>>()
        rule.setContent {
            ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                outlines += MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surface
            }
            ExchangeMailTheme(darkTheme = true, dynamicColor = false) {
                outlines += MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surface
            }
        }
        rule.waitForIdle()
        assertThat(outlines).hasSize(2)
        for ((outline, surface) in outlines) assertThat(contrast(outline, surface)).isAtLeast(3f)
    }

    @Test
    fun `a list row announces whether it is selected`() {
        rule.setContent {
            ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                var selected by remember { mutableStateOf(false) }
                Row(modifier = Modifier.clickable { selected = !selected }) {
                    DragSelectionIndicator(selected = selected)
                    Text("Quarterly report")
                }
            }
        }
        val row = rule.onNodeWithText("Quarterly report")
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        row.performClick()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }
}

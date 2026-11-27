package com.dedovmosol.iwomail.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** SET-24: выбор значения выпадающим списком и нижним листом, сворачивание крупного заголовка. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsChoiceUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) { body() }
            }
        }
    }

    private val radioSelected = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
        SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)

    @Test
    fun `short list also opens a bottom sheet with radio buttons and applies the choice`() {
        val picked = mutableListOf<String>()
        content {
            var value by remember { mutableStateOf("Light") }
            SettingsGroup {
                SettingsChoiceRow(
                    title = "Theme",
                    options = listOf("System", "Light", "Dark"),
                    selected = value,
                    label = { it },
                    onSelect = { value = it; picked += it }
                )
            }
        }
        // Значение справа в строке
        rule.onNodeWithText("Light").assertIsDisplayed()
        rule.onNodeWithText("Theme").performClick()
        rule.waitForIdle()
        // Лист с заголовком выбора: название строки — и в строке, и в листе
        rule.onAllNodes(hasText("Theme")).assertCountEquals(2)
        rule.onNodeWithText("Dark").assertIsDisplayed()
        // Текущий вариант отмечен для TalkBack как выбранная радиокнопка
        rule.onNode(hasText("Light") and radioSelected, useUnmergedTree = false).assertExists()
        rule.onNodeWithText("Dark").performClick()
        rule.waitForIdle()
        assertThat(picked).containsExactly("Dark")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("System")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Dark").assertIsDisplayed()
    }

    @Test
    fun `picking the current value does not fire onSelect`() {
        val picked = mutableListOf<String>()
        content {
            SettingsChoiceRow(
                title = "Theme", options = listOf("A", "B"), selected = "A",
                label = { it }, onSelect = { picked += it }
            )
        }
        rule.onNodeWithText("Theme").performClick()
        rule.onNode(hasText("A") and radioSelected).performClick()
        rule.waitForIdle()
        assertThat(picked).isEmpty()
    }

    @Test
    fun `long list opens a bottom sheet with a title and radio buttons`() {
        val picked = mutableListOf<Int>()
        val minutes = listOf(1, 2, 3, 5, 10, 15, 30)
        content {
            var value by remember { mutableStateOf(5) }
            SettingsChoiceRow(
                title = "Sync interval",
                options = minutes,
                selected = value,
                label = { "$it min" },
                description = "How often to check mail",
                onSelect = { value = it; picked += it }
            )
        }
        rule.onNodeWithText("Sync interval").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("How often to check mail").assertIsDisplayed()
        rule.onNode(hasText("5 min") and radioSelected).assertExists()
        rule.onNodeWithText("15 min").performClick()
        rule.waitForIdle()
        assertThat(picked).containsExactly(15)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("How often to check mail")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a long choice inside a sheet opens a dropdown, not a second sheet`() {
        val colors = listOf("Blue", "Red", "Green", "Yellow", "Orange", "Pink", "Teal", "Gray")
        val picked = mutableListOf<String>()
        content {
            SettingsSheet(title = "Daily themes", onDismiss = {}) {
                SettingsChoiceRow(
                    title = "Monday", options = colors, selected = "Blue",
                    label = { it }, onSelect = { picked += it }
                )
            }
        }
        rule.onNodeWithText("Monday").performClick()
        rule.waitForIdle()
        // Лист один: его заголовок один раз, вариант выбирается из списка у строки
        rule.onAllNodes(hasText("Daily themes")).assertCountEquals(1)
        rule.onNodeWithText("Gray").performClick()
        rule.waitForIdle()
        assertThat(picked).containsExactly("Gray")
    }

    @Test
    fun `disabled choice does not open`() {
        content {
            SettingsChoiceRow(
                title = "Scrollbar", options = listOf("Blue", "Red"), selected = "Blue",
                label = { it }, onSelect = {}, enabled = false
            )
        }
        rule.onNodeWithText("Scrollbar").performClick()
        rule.onNodeWithText("Red").assertDoesNotExist()
    }

    @Test
    fun `title sits in one line with the back arrow from the first frame and stays there on scroll`() {
        content {
            SettingsPageScaffold(title = "Appearance", onBackClick = {}) {
                items(60) { i -> Text("Row $i") }
            }
        }
        val density = rule.density.density
        val bar = rule.onNodeWithTag(SETTINGS_TOP_BAR_TAG).fetchSemanticsNode()
        // Одна строка 56 dp сразу, без крупного блока названия под стрелкой
        assertThat(bar.size.height / density).isWithin(1f).of(SettingsTokens.TopBarHeight.value)
        val title = rule.onNodeWithText("Appearance").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val back = rule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        // Название справа от стрелки на той же высоте
        assertThat(title.left).isGreaterThan(back.right - 1f)
        assertThat(title.center.y).isWithin(4f * density).of(back.center.y)

        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
        val after = rule.onNodeWithTag(SETTINGS_TOP_BAR_TAG).fetchSemanticsNode().size.height
        assertThat(after).isEqualTo(bar.size.height)
        rule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun `title is announced once as a heading`() {
        content {
            SettingsPageScaffold(title = "Settings", onBackClick = {}) {
                items(3) { i -> Text("Row $i") }
            }
        }
        rule.onAllNodes(hasText("Settings")).assertCountEquals(1)
        rule.onNode(hasText("Settings") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test
    fun `nav row shows the value on the right as text`() {
        content {
            Column {
                SettingsNavRow(title = "Signature", value = "2 signatures", onClick = {})
            }
        }
        rule.onNodeWithText("2 signatures").assertIsDisplayed()
    }
}

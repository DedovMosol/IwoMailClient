package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Вкладки контактов «Локальные / Личные / Глобальные»: ни название, ни число не обрезаются — на узком телефоне
 * (320 dp), при крупном шрифте и в диалоге выбора получателей с четвёртой вкладкой «Группы».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w320dp-h640dp-xxhdpi")
// Настоящая отрисовка и измерение текста: без NATIVE Robolectric меряет строку числом символов
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ContactSourceTabsTest {

    @get:Rule
    val rule = createComposeRule()

    private val labels = listOf("Локальные", "Личные", "Глобальные")

    private fun show(widthDp: Int? = null, fontScale: Float = 1f, groups: Boolean = false) {
        var selected by mutableIntStateOf(0)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalLanguage provides AppLanguage.RUSSIAN,
                LocalDensity provides Density(base.density, fontScale)
            ) {
                val tabs = buildList {
                    add(ContactTab(Strings.contactsTabLocal, 1234))
                    add(ContactTab(Strings.contactsTabPersonal, 5678))
                    add(ContactTab(Strings.contactsTabGlobal, 12345))
                    if (groups) add(ContactTab(Strings.contactGroups, 12))
                }
                ContactSourceTabs(
                    tabs = tabs, selected = selected, onSelect = { selected = it },
                    modifier = if (widthDp != null) Modifier.width(widthDp.dp) else Modifier.fillMaxWidth()
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * Текст целиком: одна строка, ширина узла не меньше полной ширины текста (не урезан), узел внутри своей вкладки.
     * hasVisualOverflow здесь не годится: у вкладок с прокруткой ширина не ограничена, и флаг истинен всегда.
     */
    private fun assertWhole(text: String, tabIndex: Int) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertWithMessage("$text lines").that(layout.lineCount).isEqualTo(1)
        assertWithMessage("$text width ${layout.size.width} < ${layout.multiParagraph.maxIntrinsicWidth}")
            .that(layout.size.width.toFloat()).isAtLeast(layout.multiParagraph.maxIntrinsicWidth - 0.5f)
        assertWithMessage("$text height").that(layout.didOverflowHeight).isFalse()
        val tab = rule.onNodeWithTag("contactTab_$tabIndex").fetchSemanticsNode().boundsInRoot
        val box = node.boundsInRoot
        assertWithMessage("$text inside its tab").that(box.left >= tab.left - 0.5f && box.right <= tab.right + 0.5f).isTrue()
    }

    /** Вкладки помещаются на экране целиком — ни одна не уходит за край. */
    private fun assertAllTabsOnScreen(count: Int, widthPx: Float) {
        for (i in 0 until count) {
            val tab = rule.onNodeWithTag("contactTab_$i").fetchSemanticsNode().boundsInRoot
            assertWithMessage("tab $i on screen").that(tab.left >= -0.5f && tab.right <= widthPx + 0.5f).isTrue()
        }
    }

    @Test
    fun `three tabs on a 320 dp phone show whole names and counts, all on screen without scrolling`() {
        show()
        labels.forEachIndexed { i, label -> assertWhole(label, i) }
        assertWhole("12345", 2)
        assertAllTabsOnScreen(3, with(rule.density) { 320.dp.toPx() })
    }

    @Test
    fun `large font keeps names whole - tabs scroll instead of cutting text`() {
        show(fontScale = 1.3f)
        labels.forEachIndexed { i, label -> assertWhole(label, i) }
    }

    @Test
    fun `recipient picker with four tabs in a narrow dialog shows whole names`() {
        show(widthDp = 304, groups = true)
        (labels + "Группы").forEachIndexed { i, label -> assertWhole(label, i) }
        // Диалог на 320 dp (95 % ширины): четыре вкладки видны все сразу, «Группы» не уходят за край
        assertAllTabsOnScreen(4, with(rule.density) { 304.dp.toPx() })
    }

    @Test
    fun `layout choice - equal, then compact, then by content, scrolling only as a last resort`() {
        // 3 вкладки по 100 px текста
        assertThat(chooseContactTabsLayout(listOf(100, 100, 100), 32, 8, 400)).isEqualTo(ContactTabsLayout.EQUAL to 32)
        assertThat(chooseContactTabsLayout(listOf(100, 100, 100), 32, 8, 330)).isEqualTo(ContactTabsLayout.EQUAL to 8)
        // Одна длинная: равные доли не помещаются, по тексту — да
        assertThat(chooseContactTabsLayout(listOf(200, 60, 60), 32, 8, 360)).isEqualTo(ContactTabsLayout.BY_CONTENT to 8)
        assertThat(chooseContactTabsLayout(listOf(200, 60, 60), 32, 8, 300)).isEqualTo(ContactTabsLayout.SCROLL to 32)
        assertThat(chooseContactTabsLayout(emptyList(), 32, 8, 300).first).isEqualTo(ContactTabsLayout.EQUAL)
    }

    @Test
    fun `tapping a tab selects it`() {
        show()
        rule.onNodeWithTag("contactTab_2").performClick()
        rule.waitForIdle()
        assertThat(rule.onNodeWithTag("contactTab_2").fetchSemanticsNode().config[SemanticsProperties.Selected]).isTrue()
    }

    /** Зона нажатия — вся вкладка, не только текст: нажатие у самого края и у нижней кромки тоже выбирает её. */
    @Test
    fun `tap near the tab edge selects it - the whole tab is the touch target`() {
        show()
        // Координаты корня, а не узла: узел с testTag сливается с узлом selectable, и его границы — уже сама зона
        // нажатия (поля снаружи не видны). В равном TabRow вторая вкладка начинается ровно с трети ширины, ряд — сверху
        rule.onRoot().performTouchInput { click(Offset(width / 3f + 2f, 2f)) }
        rule.waitForIdle()
        assertThat(rule.onNodeWithTag("contactTab_1").fetchSemanticsNode().config[SemanticsProperties.Selected]).isTrue()
        val heightDp = with(rule.density) { rule.onNodeWithTag("contactTab_1").fetchSemanticsNode().size.height.toDp() }
        assertWithMessage("touch target height").that(heightDp.value).isAtLeast(48f)
    }
}

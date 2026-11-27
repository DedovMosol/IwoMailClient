package com.dedovmosol.iwomail.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Протягивание после долгого нажатия ([rememberDragSelectModifier]). Во «Входящих», «Отправленных» и «Черновиках»
 * синхронизация меняет список прямо во время жеста — выделение не должно обрываться.
 */
@RunWith(RobolectricTestRunner::class)
// Высокий экран: список 800 dp целиком в окне, иначе нижняя зона автопрокрутки сработала бы раньше
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragSelectModifierTest {

    @get:Rule
    val rule = createComposeRule()

    private var keys by mutableStateOf((0 until 12).map { "k$it" })
    private var selected by mutableStateOf(emptySet<String>())
    private var row = 0f

    private fun pump(frames: Int) = repeat(frames) {
        rule.runOnUiThread { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun setList(heightDp: Int = 800) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val state = rememberLazyListState()
            val drag = rememberDragSelectModifier(state, keys, selected, onSelectionChange = { selected = it })
            LazyColumn(state = state, modifier = Modifier.testTag("list").size(300.dp, heightDp.dp).then(drag)) {
                items(keys, key = { it }) { Box(Modifier.fillMaxWidth().height(50.dp)) }
            }
        }
        pump(5)
        row = with(rule.density) { 50.dp.toPx() }
    }

    private fun longPressAt(rowIndex: Float) {
        rule.onNodeWithTag("list").performTouchInput { down(Offset(100f, row * rowIndex)) }
        pump(40) // дольше таймаута долгого нажатия
    }

    /** Ведёт палец вниз на [rows] строк шагами по 1/5 строки. */
    private fun dragRows(rows: Int) = repeat(rows * 5) {
        rule.onNodeWithTag("list").performTouchInput { moveBy(Offset(0f, row / 5)) }
        pump(2)
    }

    private fun release() {
        rule.onNodeWithTag("list").performTouchInput { up() }
        pump(3)
    }

    @Test
    fun `dragging after a long press selects the whole range`() {
        setList()
        longPressAt(3.5f)
        dragRows(5)
        release()
        assertThat(selected).containsExactly("k3", "k4", "k5", "k6", "k7", "k8")
    }

    @Test
    fun `dragging in a list long enough to scroll selects instead of scrolling`() {
        // Большая папка (Входящие, Отправленные, Черновики): список прокручивается. Раньше жест забирала прокрутка
        // списка — протягивание отменялось на первом движении пальца
        keys = (0 until 60).map { "k$it" }
        setList()
        longPressAt(3.5f)
        dragRows(5)
        release()
        assertThat(selected).containsExactly("k3", "k4", "k5", "k6", "k7", "k8")
    }

    @Test
    fun `a sync that appends mail mid-gesture does not stop the selection`() {
        setList()
        longPressAt(3.5f)
        dragRows(2)
        assertThat(selected).containsExactly("k3", "k4", "k5")
        rule.runOnUiThread { keys = keys + "k12" }
        pump(3)
        dragRows(3)
        release()
        assertThat(selected).containsExactly("k3", "k4", "k5", "k6", "k7", "k8")
    }

    @Test
    fun `new mail above the start row keeps the range anchored to the pressed email`() {
        setList()
        longPressAt(3.5f)
        dragRows(2)
        // Новое письмо сверху сдвигает всё вниз на строку: начало — то же письмо k3, а не строка с тем же номером
        rule.runOnUiThread { keys = listOf("new") + keys }
        pump(3)
        dragRows(1)
        release()
        assertThat(selected).doesNotContain("new")
        assertThat(selected).contains("k3")
        assertThat(selected.minOf { it.removePrefix("k").toInt() }).isEqualTo(3)
    }

    @Test
    fun `holding the finger at the edge of a list that cannot scroll further leaves the screen idle`() {
        setList(heightDp = 400)
        longPressAt(0.3f) // верхняя зона автопрокрутки, список уже в самом верху
        rule.onNodeWithTag("list").performTouchInput { moveBy(Offset(0f, 4f)) }
        pump(3)
        // Раньше цикл автопрокрутки крутился каждый кадр, пока палец у края: Compose не становился idle
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        release()
        assertThat(selected).contains("k0")
    }
}

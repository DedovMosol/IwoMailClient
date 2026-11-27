package com.dedovmosol.iwomail.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.screens.SettingsPageScaffold
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Скроллбар настроек: разделы из одного-двух высоких элементов («Персонализация», «Синхронизация и очистка», «Почта»,
 * «Безопасность») раньше не показывали ползунок совсем или показывали его на пол-экрана.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LazyColumnScrollbarTest {

    @get:Rule
    val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var mode by mutableIntStateOf(0)

    private fun show() = rule.setContent {
        CompositionLocalProvider(LocalScrollbarColor provides ScrollbarColor.RED) {
            SettingsPageScaffold(title = "T", onBackClick = {}) {
                when (mode) {
                    0 -> item { Box(Modifier.fillMaxWidth().height(2000.dp)) }
                    1 -> { item { Box(Modifier.fillMaxWidth().height(1200.dp)) }; item { Box(Modifier.fillMaxWidth().height(1200.dp)) } }
                    else -> item { Box(Modifier.fillMaxWidth().height(200.dp)) } // помещается — ползунка нет
                }
            }
        }
    }

    /** Красные строки пикселей у правого края: верх и высота ползунка (null — его нет). */
    private fun thumbRows(): IntRange? {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val stripe = (12 * v.resources.displayMetrics.density).toInt()
        val rows = (0 until v.height).filter { y ->
            (v.width - stripe until v.width).any { x ->
                val c = bmp.getPixel(x, y)
                android.graphics.Color.red(c) > 170 && android.graphics.Color.green(c) < 90 && android.graphics.Color.blue(c) < 90
            }
        }
        return if (rows.isEmpty()) null else rows.first()..rows.last()
    }

    @Test
    fun `one tall item shows a short thumb that moves when scrolled`() {
        show()
        val top = checkNotNull(thumbRows()) { "no thumb on a single-item screen" }
        val screen = rule.activity.window.decorView.height
        assertThat(top.last - top.first).isLessThan(screen / 2)
        rule.onRoot().performTouchInput { swipeUp() }
        val after = checkNotNull(thumbRows())
        assertThat(after.first).isGreaterThan(top.first)
    }

    @Test
    fun `two tall items show a thumb proportional to the content, not half the screen`() {
        mode = 1
        show()
        val thumb = checkNotNull(thumbRows())
        val screen = rule.activity.window.decorView.height
        // 2400 dp содержимого в ~830 dp: ползунок около трети дорожки, не половина
        assertThat(thumb.last - thumb.first).isLessThan((screen * 0.45).toInt())
    }

    @Test
    fun `content that fits shows no thumb`() {
        mode = 2
        show()
        assertThat(thumbRows()).isNull()
    }

    // --- Расчёт без отрисовки ---

    private fun info(viewport: Int, total: Int, vararg items: Pair<Int, Int>) = object : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = items.mapIndexed { i, (offset, size) ->
            object : LazyListItemInfo {
                override val index = i
                override val key: Any = i
                override val offset = offset
                override val size = size
                override val contentType: Any? = null
            }
        }
        override val viewportStartOffset = 0
        override val viewportEndOffset = viewport
        override val totalItemsCount = total
        override val viewportSize = IntSize(100, viewport)
        override val orientation = Orientation.Vertical
        override val reverseLayout = false
        override val beforeContentPadding = 0
        override val afterContentPadding = 0
        override val mainAxisItemSpacing = 0
    }

    @Test
    fun `thumb is exact at the ends and proportional in the middle of a single item`() {
        val atTop = lazyListThumb(info(1000, 1, 0 to 4000), canScrollBackward = false, canScrollForward = true)!!
        assertThat(atTop.lengthFraction).isWithin(1e-4f).of(0.25f)
        assertThat(atTop.positionFraction).isEqualTo(0f)
        val middle = lazyListThumb(info(1000, 1, -1500 to 4000), canScrollBackward = true, canScrollForward = true)!!
        assertThat(middle.positionFraction).isWithin(1e-4f).of(0.5f)
        val atEnd = lazyListThumb(info(1000, 1, -3000 to 4000), canScrollBackward = true, canScrollForward = false)!!
        assertThat(atEnd.positionFraction).isEqualTo(1f)
        assertThat(lazyListThumb(info(1000, 1, 0 to 800), canScrollBackward = false, canScrollForward = false)).isNull()
    }
}

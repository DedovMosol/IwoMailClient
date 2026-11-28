package com.dedovmosol.iwomail.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ручная визуальная проверка виджета: PNG в app/build/widget-shots. Запуск: IWO_SHOTS=1, --no-daemon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "ru-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetScreenshotProbe {

    @Before
    fun only() = Assume.assumeTrue(System.getenv("IWO_SHOTS") == "1")

    private val base get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun shots() {
        val sizes = listOf(
            DpSize(110.dp, 100.dp), DpSize(180.dp, 110.dp), DpSize(180.dp, 140.dp), DpSize(250.dp, 140.dp),
            DpSize(180.dp, 210.dp), DpSize(250.dp, 250.dp), DpSize(320.dp, 280.dp), DpSize(400.dp, 420.dp), DpSize(530.dp, 600.dp)
        )
        for (scale in listOf(1f, 1.3f, 2f)) {
            val ctx = WidgetRenderSupport.context(base, scale)
            for (s in sizes) {
                val v = WidgetRenderSupport.render(ctx, s, WidgetSamples.full)
                WidgetRenderSupport.savePng(v, "w${s.width.value.toInt()}x${s.height.value.toInt()}_f${(scale * 100).toInt()}")
            }
        }
    }
}

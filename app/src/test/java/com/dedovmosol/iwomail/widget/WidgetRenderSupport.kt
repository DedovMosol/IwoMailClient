package com.dedovmosol.iwomail.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.roundToInt

/**
 * Настоящая отрисовка виджета под Robolectric: Glance → RemoteViews → View-дерево лаунчера заданного размера.
 * Даёт PNG для ручной проверки и дерево для автоматической проверки обрезки и наложений.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
internal object WidgetRenderSupport {

    /** Контекст с заданным масштабом шрифта системы — как у пользователя с «Крупным шрифтом». */
    fun context(base: Context, fontScale: Float): Context {
        val config = Configuration(base.resources.configuration).apply { this.fontScale = fontScale }
        return base.createConfigurationContext(config)
    }

    /** Виджет размера [size] (dp), разложенный как в лаунчере. */
    fun render(context: Context, size: DpSize, data: WidgetData): View {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context, size) { MailWidgetContent(data, context) }.remoteViews
        }
        val density = context.resources.displayMetrics.density
        val w = (size.width.value * density).roundToInt()
        val h = (size.height.value * density).roundToInt()
        val host = FrameLayout(context)
        val view = remoteViews.apply(context, host)
        host.addView(view, FrameLayout.LayoutParams(w, h))
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        return host
    }

    fun savePng(view: View, name: String) {
        val dir = File(System.getProperty("user.dir"), "build/widget-shots").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // Подложка «обоев», чтобы видеть скругления и прозрачность
        bmp.eraseColor(0xFF3A4A5A.toInt())
        view.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Видимые TextView с текстом. */
    fun texts(root: View): List<TextView> = buildList {
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && v.text.isNotEmpty()) add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** Прямоугольник вида в координатах корня. */
    fun bounds(v: View, root: View): android.graphics.Rect {
        val loc = IntArray(2)
        var x = 0
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== root) {
            x += cur.left - cur.scrollX
            y += cur.top - cur.scrollY
            cur = cur.parent as? View
        }
        loc[0] = x; loc[1] = y
        return android.graphics.Rect(x, y, x + v.width, y + v.height)
    }
}

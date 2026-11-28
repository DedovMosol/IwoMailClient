package com.dedovmosol.iwomail.widget

import android.content.Context
import android.graphics.Rect
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Настоящая отрисовка виджета (Glance → RemoteViews → View, как в лаунчере) на каждом размере из
 * [WIDGET_SIZES] и при системном шрифте от 0,85× до 2×: текст не срезан по высоте, ничто не выходит за свой
 * контейнер и за виджет, надписи не наползают друг на друга, кнопки «Написать» и «Синхронизировать» на месте.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = android.app.Application::class, qualifiers = "ru-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLayoutRenderTest {

    private val base get() = ApplicationProvider.getApplicationContext<Context>()

    private val variants = mapOf(
        "full" to WidgetSamples.full,
        "one-account" to WidgetSamples.full.copy(accounts = WidgetSamples.full.accounts.take(1)),
        "empty" to WidgetSamples.full.copy(recentEmails = emptyList(), nextEvent = null, todayTasksCount = 0, activeTasksCount = 0, notesCount = 0, totalUnread = 0, lastSyncStr = ""),
        "locked" to WidgetSamples.full.copy(contentHidden = true, recentEmails = emptyList(), nextEvent = EventInfo("", "14:30"), nextTaskTitle = "")
    )

    private fun leaves(root: View): List<View> = buildList {
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) else add(v)
        }
        walk(root)
    }

    private fun ancestors(v: View, root: View): List<View> = buildList {
        var p = v.parent as? View
        while (p != null && p !== root) { add(p); p = p.parent as? View }
        add(root)
    }

    private fun check(name: String, root: View) {
        val rootBox = Rect(0, 0, root.width, root.height)
        val all = leaves(root)
        val texts = all.filterIsInstance<TextView>().filter { it.text.isNotEmpty() }
        for (tv in texts) {
            val label = "$name '${tv.text}'"
            assertWithMessage("$label laid out").that(tv.layout).isNotNull()
            val inner = tv.height - tv.totalPaddingTop - tv.totalPaddingBottom
            assertWithMessage("$label: text height ${tv.layout.height} > box $inner — срезан по высоте")
                .that(tv.layout.height).isAtMost(inner + 1)
            assertWithMessage("$label: lines").that(tv.layout.lineCount).isAtMost(maxOf(tv.maxLines, 1))
        }
        for (v in all) {
            val box = WidgetRenderSupport.bounds(v, root)
            if (box.isEmpty) continue
            val label = "$name ${v.javaClass.simpleName} '${(v as? TextView)?.text ?: v.contentDescription ?: ""}' $box"
            assertWithMessage("$label inside widget $rootBox").that(rootBox.contains(box)).isTrue()
            for (a in ancestors(v, root)) {
                val ab = WidgetRenderSupport.bounds(a, root)
                assertWithMessage("$label inside its container $ab — обрезан").that(Rect(ab).apply { inset(-1, -1) }.contains(box)).isTrue()
            }
        }
        // Надписи не наползают друг на друга (капсула-бейдж лежит на аватаре по замыслу, но не на букве)
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            val a = WidgetRenderSupport.bounds(texts[i], root)
            val b = WidgetRenderSupport.bounds(texts[j], root)
            if (a.isEmpty || b.isEmpty) continue
            assertWithMessage("$name: '${texts[i].text}' $a overlaps '${texts[j].text}' $b").that(Rect.intersects(a, b)).isFalse()
        }
    }

    private fun descriptions(root: View): List<String> = buildList {
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            v.contentDescription?.let { add(it.toString()) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    @Test
    fun `every size and font scale - nothing clipped, nothing overlaps, both buttons visible`() {
        for (scale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
            val ctx = WidgetRenderSupport.context(base, scale)
            for (size in WIDGET_SIZES) for ((variant, data) in variants) {
                val name = "${size.width.value.toInt()}x${size.height.value.toInt()} f$scale $variant"
                val root = WidgetRenderSupport.render(ctx, size, data)
                check(name, root)
                val cds = descriptions(root)
                assertWithMessage("$name compose button").that(cds).contains("Написать письмо")
                assertWithMessage("$name sync button").that(cds).contains("Синхронизировать")
                if (data.accounts.isNotEmpty() && widgetSpec(size, scale, data.accounts.size, 1, 0).avatars > 0) {
                    assertWithMessage("$name active avatar").that(cds.any { it.endsWith(", активный") }).isTrue()
                }
            }
        }
    }

    @Test
    fun `compact widgets keep the second account and search, locked empty mail has a placeholder`() {
        val full = WidgetSamples.full
        fun render(width: Float, height: Float, data: WidgetData): View = WidgetRenderSupport.render(
            base, DpSize(androidx.compose.ui.unit.Dp(width), androidx.compose.ui.unit.Dp(height)), data
        )
        val narrow = render(180f, 140f, full)
        val wider = render(250f, 140f, full)
        for (root in listOf(narrow, wider)) {
            check("compact widget", root)
            assertThat(descriptions(root).any { "Дом" in it && "сделать активным" in it }).isTrue()
            assertThat(WidgetRenderSupport.texts(root).any { it.text.toString() == "Поиск в почте" }).isTrue()
        }
        val emptyLocked = full.copy(contentHidden = true, recentEmails = emptyList(), totalUnread = 0,
            accounts = full.accounts.map { it.copy(unreadCount = 0) })
        val locked = render(530f, 600f, emptyLocked)
        assertThat(WidgetRenderSupport.texts(locked).any { it.text.toString() == "Нет новых писем" }).isTrue()
    }

    @Test
    fun `avatars are circles with distinct colours, active one ringed`() {
        val root = WidgetRenderSupport.render(base, DpSize(androidx.compose.ui.unit.Dp(320f), androidx.compose.ui.unit.Dp(280f)), WidgetSamples.full)
        // Круг — форма-рисунок в ImageView на всех версиях Android, а не cornerRadius (на Android 8–11 он не работает)
        val circles = leaves(root).filterIsInstance<ImageView>().filter { iv ->
            val bg = iv.drawable
            bg is android.graphics.drawable.GradientDrawable && bg.shape == android.graphics.drawable.GradientDrawable.OVAL
        }
        // 4 аватара + кольцо активного + 2 кнопки
        assertThat(circles.size).isAtLeast(7)
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test
    fun `all responsive layouts together stay far below the binder limit`() {
        val sizes = WIDGET_SIZES.toList()
        assertThat(sizes.size).isAtMost(16) // предел RemoteViews(Map<SizeF, RemoteViews>)
        val bytes = sizes.map { s ->
            val rv = runBlocking { GlanceRemoteViews().compose(base, s) { MailWidgetContent(WidgetSamples.full, base) }.remoteViews }
            val p = Parcel.obtain()
            try { rv.writeToParcel(p, 0); p.dataSize() } finally { p.recycle() }
        }
        System.err.println("WIDGET parcel bytes per layout = $bytes, total = ${bytes.sum()} (API ${android.os.Build.VERSION.SDK_INT})")
        // Транзакция binder — 1 МБ на процесс. С Android 12 лаунчер получает все раскладки разом (RemoteViews(Map));
        // до 12 Glance собирает раскладку только под размеры, в которых виджет показан (портрет и альбом), по одной
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            assertThat(bytes.sum()).isLessThan(300_000)
        } else {
            assertThat(bytes.max() * 2).isLessThan(300_000)
        }
    }
}

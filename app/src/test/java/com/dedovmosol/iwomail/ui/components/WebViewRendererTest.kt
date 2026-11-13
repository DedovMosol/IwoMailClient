package com.dedovmosol.iwomail.ui.components

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Жизненный цикл WebView при смерти рендерера: метка, освобождение, причина смерти. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewRendererTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a fresh web view is alive, a marked one is gone`() {
        val view = WebView(context)

        assertThat(WebViewRenderer.isGone(view)).isFalse()
        WebViewRenderer.markGone(view)
        assertThat(WebViewRenderer.isGone(view)).isTrue()
    }

    @Test
    fun `release detaches the web view and does not throw for a live one`() {
        val parent = FrameLayout(context)
        val view = WebView(context)
        parent.addView(view)

        WebViewRenderer.release(view)

        assertThat(parent.childCount).isEqualTo(0)
    }

    @Test
    fun `release of a dead renderer only detaches and destroys`() {
        val parent = FrameLayout(context)
        val view = WebView(context)
        parent.addView(view)
        WebViewRenderer.markGone(view)

        WebViewRenderer.release(view)

        assertThat(parent.childCount).isEqualTo(0)
    }

    @Test
    fun `release survives a web view that is already destroyed`() {
        val view = WebView(context)
        view.destroy()

        // Второй release (onRelease после onRenderProcessGone) не должен ронять приложение
        WebViewRenderer.release(view)
    }

    @Test
    fun `a crash is told from a kill for memory, no details counts as a crash`() {
        val crashed = mockk<RenderProcessGoneDetail> { every { didCrash() } returns true }
        val killed = mockk<RenderProcessGoneDetail> { every { didCrash() } returns false }

        assertThat(WebViewRenderer.crashed(crashed)).isTrue()
        assertThat(WebViewRenderer.crashed(killed)).isFalse()
        assertThat(WebViewRenderer.crashed(null)).isTrue()
    }
}

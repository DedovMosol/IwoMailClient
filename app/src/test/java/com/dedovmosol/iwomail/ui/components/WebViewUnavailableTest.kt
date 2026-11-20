package com.dedovmosol.iwomail.ui.components

import android.content.Context
import android.util.AndroidRuntimeException
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Системный WebView недоступен (обновляется, отключён — WebViewFactory бросает AndroidRuntimeException): экраны с
 * WebView не роняют приложение. Редактор письма объясняет и даёт «Повторить», письмо показывается текстом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewUnavailableTest {

    @get:Rule
    val rule = createComposeRule()

    private val realFactory = WebViewRenderer.factory
    private var failing = true

    private fun failWebView() {
        WebViewRenderer.factory = { ctx ->
            if (failing) throw AndroidRuntimeException("android.webkit.WebViewFactory\$MissingWebViewPackageException")
            WebView(ctx)
        }
    }

    @After
    fun restore() {
        WebViewRenderer.factory = realFactory
    }

    @Test
    fun `create returns null instead of throwing when the provider is missing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        failWebView()
        assertThat(WebViewRenderer.create(context)).isNull()
        failing = false
        assertThat(WebViewRenderer.create(context)).isInstanceOf(WebView::class.java)
    }

    @Test
    fun `the editor explains and retries instead of crashing`() {
        failWebView()
        val controller = RichTextEditorController()
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    RichTextEditor(initialHtml = "<p>Hi</p>", onHtmlChanged = {}, controller = controller)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Android System WebView", substring = true).assertExists()
        assertThat(controller.webView).isNull()

        // WebView обновился — «Повторить» создаёт редактор
        failing = false
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Android System WebView", substring = true).assertDoesNotExist()
        assertThat(controller.webView).isNotNull()
    }

    @Test
    fun `a message is shown as text instead of crashing`() {
        failWebView()
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    HtmlBodyView(html = "<p>Quarterly report</p>", contentKey = 1L, onMailto = {}, remoteContentAllowed = false)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("showing its text", substring = true).assertExists()
        // Текст из HTML извлекается не на главном потоке
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Quarterly report", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

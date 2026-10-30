package com.dedovmosol.iwomail.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Печать письма через системный диалог (PrintManager) — там же «Сохранить как PDF». Страница грузится в отдельный
 * WebView без скриптов и доступа к файлам. WebView держится, пока не создано задание печати: иначе его соберёт
 * сборщик мусора раньше onPageFinished (developer.android.com, «Print HTML documents»); после — задание держит его
 * само до конца печати.
 */
class EmailPrinter internal constructor(private val activity: Activity) {

    private var pending: WebView? = null

    /** false — печать недоступна (нет службы печати). */
    @MainThread
    fun print(jobName: String, html: String, remoteAllowed: Boolean): Boolean {
        val manager = activity.getSystemService(PrintManager::class.java) ?: return false
        release()
        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkImage = !remoteAllowed
        }
        webView.webViewClient = object : WebViewClient() {
            // Ссылки на странице печати не открываем
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                if (pending !== view) return
                pending = null
                if (activity.isFinishing || activity.isDestroyed) return
                manager.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
            }
        }
        pending = webView
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        return true
    }

    /** Экран закрыт до загрузки страницы — задание не нужно. */
    @MainThread
    fun release() {
        pending?.let { webView ->
            pending = null
            webView.stopLoading()
            webView.destroy()
        }
    }
}

/** [EmailPrinter] экрана; null — экран не в Activity (превью). */
@Composable
fun rememberEmailPrinter(): EmailPrinter? {
    val context = LocalContext.current
    val printer = remember(context) { context.findActivity()?.let(::EmailPrinter) }
    DisposableEffect(printer) {
        onDispose { printer?.release() }
    }
    return printer
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

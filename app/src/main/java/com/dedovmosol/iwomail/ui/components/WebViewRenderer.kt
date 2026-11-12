package com.dedovmosol.iwomail.ui.components

import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import com.dedovmosol.iwomail.R

/**
 * Жизненный цикл WebView при смерти рендерера (Termination Handling API,
 * developer.android.com/develop/ui/views/layout/webapps/managing-webview) — один для всех WebView приложения (DRY).
 *
 * Рендерер умирает от нехватки памяти или сбоя на тяжёлом содержимом. Если `WebViewClient.onRenderProcessGone` не
 * вернул true, система завершает всё приложение. Вернув true, приложение обязано больше не пользоваться этим WebView:
 * только убрать из иерархии и уничтожить ([release]); на его месте можно создать новый.
 */
internal object WebViewRenderer {

    /** Пометить WebView, у которого умер рендерер: [release] не вызовет на нём ничего, кроме destroy(). */
    fun markGone(view: WebView) {
        view.setTag(R.id.webview_renderer_gone, true)
    }

    fun isGone(view: WebView): Boolean = view.getTag(R.id.webview_renderer_gone) == true

    /** Рендерер упал (а не выгружен системой ради памяти). Без подробностей — считаем падением (безопаснее). */
    fun crashed(detail: RenderProcessGoneDetail?): Boolean = detail?.didCrash() ?: true

    /**
     * Уничтожить WebView без утечки нативных ресурсов. Живой — остановить загрузку и очистить страницу (иначе краш в
     * RenderThread при уничтожении посреди отрисовки); с умершим рендерером — только отцепить и destroy().
     */
    fun release(view: WebView) {
        try {
            if (!isGone(view)) {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.clearHistory()
            }
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        } catch (e: Exception) {
            android.util.Log.w("WebViewRenderer", "WebView release failed", e)
        }
    }
}

/**
 * Сколько раз пересоздавать WebView после падения рендерера на одном содержимом: та же страница, скорее всего,
 * уронит рендерер снова (там же, в документации), поэтому после [maxCrashes] падений содержимое не показывается.
 * Выгрузка рендерера системой ради памяти ([crashed] = false) падением не считается — WebView пересоздаётся всегда.
 */
internal class RendererRecovery(private val maxCrashes: Int = 2) {
    var crashes = 0
        private set

    /** true — создать новый WebView; false — больше не показывать это содержимое в WebView. */
    fun onRendererGone(crashed: Boolean): Boolean {
        if (crashed) crashes++
        return crashes < maxCrashes
    }
}

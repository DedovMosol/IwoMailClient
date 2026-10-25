package com.dedovmosol.iwomail.ui.components

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.util.SafeToast

/**
 * Безопасный рендер HTML-тела (письма, события календаря) в WebView — единая реализация (DRY),
 * вынесенная из EmailDetailScreen без изменения поведения.
 *
 * Безопасность: [html] должен быть уже пропущен через `sanitizeEmailHtml`; страница грузится с
 * `loadDataWithBaseURL(null, …)` и CSP с nonce — собственные скрипты письма не исполняются, работает
 * только наш скрипт (замер высоты, плейсхолдеры битых картинок, автоссылки). Переходы по ссылкам
 * перехватываются: mailto → [onMailto], tel → набор номера, прочее → внешний браузер.
 *
 * Высота: WebView без собственного скролла (скроллит родитель), высота карточки берётся из замера
 * содержимого после загрузки. [contentKey] — идентичность документа (id письма/события): при его
 * смене WebView пересоздаётся и корректно уничтожается (без утечки нативных ресурсов).
 */
@Composable
fun HtmlBodyView(
    html: String,
    contentKey: Any,
    onMailto: (String) -> Unit,
) {
    val context = LocalContext.current
    val isRussian = isRussian()
    var webViewHeight by remember(contentKey) { mutableIntStateOf(0) }
    var webViewRef by remember(contentKey) { mutableStateOf<WebView?>(null) }
    // PERF: Отслеживаем последний загруженный HTML чтобы не перезагружать WebView при каждой рекомпозиции
    var lastLoadedHtml by remember(contentKey) { mutableStateOf("") }
    DisposableEffect(contentKey) {
        onDispose {
            webViewRef?.let { webView ->
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView.removeAllViews()
                    webView.destroy()
                } catch (_: Exception) { }
            }
            webViewRef = null
        }
    }

    key(contentKey) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .then(if (webViewHeight > 0) Modifier.height(webViewHeight.dp) else Modifier),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color.White
            )
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.apply {
                            javaScriptEnabled = true // Нужен для измерения высоты
                            // Масштабирование контента под ширину экрана
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            // Включаем зум для широкого контента
                            builtInZoomControls = true
                            displayZoomControls = false
                            setSupportZoom(true)
                            // Включаем загрузку изображений
                            loadsImagesAutomatically = true
                            blockNetworkImage = false
                            blockNetworkLoads = false
                            // Разрешаем смешанный контент (http изображения на https странице)
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            // Дополнительные настройки для загрузки контента
                            domStorageEnabled = true
                            allowContentAccess = true
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        }
                        setBackgroundColor(android.graphics.Color.WHITE)
                        // Отключаем скролл внутри WebView - скроллит родитель
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false

                        // Умная обработка touch событий для горизонтального скролла
                        var startX = 0f
                        var startY = 0f
                        setOnTouchListener { v, event ->
                            when (event.action) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    startX = event.x
                                    startY = event.y
                                    // Пока не знаем направление - разрешаем родителю
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    val deltaX = kotlin.math.abs(event.x - startX)
                                    val deltaY = kotlin.math.abs(event.y - startY)

                                    // Если горизонтальное движение больше вертикального - блокируем родителя
                                    if (deltaX > deltaY && deltaX > 10) {
                                        v.parent?.requestDisallowInterceptTouchEvent(true)
                                    } else if (deltaY > deltaX && deltaY > 10) {
                                        // Вертикальное движение - разрешаем родителю
                                        v.parent?.requestDisallowInterceptTouchEvent(false)
                                    }
                                }
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                    // Сбрасываем блокировку
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }
                        // Измеряем высоту контента после загрузки
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                request?.url?.let { uri ->
                                    when (uri.scheme) {
                                        "mailto" -> {
                                            // Открываем окно написания письма в нашем приложении
                                            val mailtoRecipient = uri.schemeSpecificPart.substringBefore("?")
                                            onMailto(mailtoRecipient)
                                        }
                                        "tel" -> {
                                            // Номеронабиратель с fallback на буфер обмена (планшеты без телефона)
                                            try {
                                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, uri))
                                            } catch (_: Exception) {
                                                val phone = uri.schemeSpecificPart
                                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Phone", phone))
                                                SafeToast.short(context, if (isRussian) "Номер скопирован" else "Number copied")
                                            }
                                        }
                                        else -> {
                                            // http/https и прочее — открываем в браузере
                                            try {
                                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                            } catch (_: Exception) {
                                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Link", uri.toString()))
                                                SafeToast.short(context, if (isRussian) "Ссылка скопирована" else "Link copied")
                                            }
                                        }
                                    }
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val measureJs = "(function(){var b=document.body,d=document.documentElement;return Math.max(b.scrollHeight||0,b.offsetHeight||0,d.scrollHeight||0);})()"
                                fun measure(v: WebView) {
                                    if (v !== webViewRef) return
                                    try {
                                        v.evaluateJavascript(measureJs) { result ->
                                            if (v !== webViewRef) return@evaluateJavascript
                                            val cssH = result?.replace("\"", "")?.toIntOrNull()
                                            if (cssH != null && cssH > 0) {
                                                @Suppress("DEPRECATION")
                                                val scale = v.scale
                                                val density = v.resources.displayMetrics.density
                                                val dpH = (cssH.toFloat() * scale / density).toInt()
                                                if (dpH > 0 && dpH < 20000) {
                                                    webViewHeight = dpH + 16
                                                }
                                            }
                                        }
                                    } catch (_: Exception) { }
                                }
                                view?.postDelayed({ measure(view) }, 500)
                                view?.postDelayed({ measure(view) }, 2000)
                            }
                        }
                    }
                },
                update = { webView ->
                    webViewRef = webView
                    if (html == lastLoadedHtml) return@AndroidView
                    lastLoadedHtml = html

                    val nonce = java.util.UUID.randomUUID().toString().replace("-", "")

                    val styledHtml = """
                        <html>
                        <head>
                            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; img-src data: cid: https: http: blob:; font-src https: data:;">
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                            <style>
                                html { margin: 0 !important; padding: 0 !important; }
                                body {
                                    background-color: white !important;
                                    color: black !important;
                                    margin: 0 8px !important;
                                    padding: 0 !important;
                                    font-family: sans-serif;
                                    font-size: 14px;
                                    word-wrap: break-word;
                                    overflow-wrap: break-word;
                                }
                                a { color: #1a73e8; word-break: break-all; }
                                img {
                                    max-width: 100% !important;
                                    height: auto !important;
                                }
                                /* Placeholder для сломанных/незагруженных картинок */
                                img.broken-img {
                                    display: inline-block;
                                    min-width: 120px;
                                    min-height: 40px;
                                    background: #f0f0f0;
                                    border: 1px dashed #ccc;
                                    border-radius: 4px;
                                    position: relative;
                                }
                                img.broken-img::after {
                                    content: attr(alt);
                                    display: block;
                                    padding: 8px;
                                    color: #888;
                                    font-size: 12px;
                                    text-align: center;
                                }
                                table {
                                    max-width: 100% !important;
                                    table-layout: fixed;
                                }
                                td, th { word-wrap: break-word; }
                                pre, code {
                                    white-space: pre-wrap;
                                    word-wrap: break-word;
                                    max-width: 100%;
                                    overflow-x: auto;
                                }
                                /* Скрываем сломанные CID-изображения (не загрузились из вложений) */
                                img[src^="cid:"].broken-img { display: none; }
                                /* Убираем хвост пустых элементов от RichTextEditor */
                                .image-block + br, .image-block + div:empty,
                                body > br:last-child, body > div:last-child:empty,
                                body > p:last-child:empty { display: none; }
                            </style>
                            <script nonce="$nonce">
                            document.addEventListener('DOMContentLoaded', function() {
                                // Обработка сломанных изображений — показываем placeholder
                                document.querySelectorAll('img').forEach(function(img) {
                                    img.onerror = function() {
                                        this.classList.add('broken-img');
                                        if (!this.alt) this.alt = '\u{1F5BC} Image';
                                        this.onerror = null;
                                    };
                                    // Перепроверяем уже failed изображения
                                    if (img.complete && img.naturalHeight === 0 && img.src && !img.src.startsWith('data:')) {
                                        img.classList.add('broken-img');
                                        if (!img.alt) img.alt = '\u{1F5BC} Image';
                                    }
                                });
                                function cleanTrail(el, depth) {
                                    if (depth > 5) return;
                                    while (el.lastChild) {
                                        var last = el.lastChild;
                                        if (last.nodeType === 3 && last.textContent.trim() === '') {
                                            el.removeChild(last); continue;
                                        }
                                        if (last.nodeType !== 1) break;
                                        var tag = last.tagName;
                                        if (tag === 'IMG' && last.classList.contains('broken-img')) {
                                            el.removeChild(last); continue;
                                        }
                                        if (tag === 'BR') { el.removeChild(last); continue; }
                                        var inner = last.innerHTML ? last.innerHTML.replace(/&nbsp;/g,'').replace(/<br\s*\/?>/gi,'').trim() : '';
                                        if ((tag === 'P' || tag === 'DIV' || tag === 'SPAN') && inner === '') {
                                            el.removeChild(last); continue;
                                        }
                                        if ((tag === 'DIV' || tag === 'P' || tag === 'SPAN') && last.children.length > 0) {
                                            var onlyBroken = true;
                                            for (var ci = 0; ci < last.children.length; ci++) {
                                                var ch = last.children[ci];
                                                if (!(ch.tagName === 'IMG' && ch.classList.contains('broken-img')) && ch.tagName !== 'BR') {
                                                    onlyBroken = false; break;
                                                }
                                            }
                                            if (onlyBroken && last.textContent.trim() === '') {
                                                el.removeChild(last); continue;
                                            }
                                        }
                                        break;
                                    }
                                }
                                cleanTrail(document.body, 0);
                                var lastSig = document.body.lastElementChild;
                                if (lastSig && /^(DIV|SECTION|TD|ARTICLE)$/.test(lastSig.tagName)) {
                                    cleanTrail(lastSig, 1);
                                }
                                function isInsideLink(node) {
                                    var p = node.parentNode;
                                    while (p && p !== document.body) {
                                        if (p.tagName === 'A') return true;
                                        p = p.parentNode;
                                    }
                                    return false;
                                }
                                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                                var nodes = [];
                                while (walker.nextNode()) nodes.push(walker.currentNode);
                                nodes.forEach(function(node) {
                                    if (isInsideLink(node)) return;
                                    var p = node.parentNode;
                                    if (p && (p.tagName === 'SCRIPT' || p.tagName === 'STYLE')) return;
                                    var text = node.textContent;
                                    var html = text.replace(
                                        /(https?:\/\/[^\s<>"']+)|(\b[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b)|(\+?\d[\d\s\-()]{6,}\d)/g,
                                        function(m, url, email, phone) {
                                            if (url) return '<a href="' + url + '">' + url + '</a>';
                                            if (email) return '<a href="mailto:' + email + '">' + email + '</a>';
                                            if (phone) return '<a href="tel:' + phone.replace(/[\s\-()]/g, '') + '">' + phone + '</a>';
                                            return m;
                                        }
                                    );
                                    if (html !== text) {
                                        var span = document.createElement('span');
                                        span.innerHTML = html;
                                        p.replaceChild(span, node);
                                    }
                                });
                            });
                            </script>
                        </head>
                        <body>$html</body>
                        </html>
                    """.trimIndent()
                    webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
    }
}
}

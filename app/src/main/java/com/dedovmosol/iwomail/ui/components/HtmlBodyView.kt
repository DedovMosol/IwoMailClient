package com.dedovmosol.iwomail.ui.components

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
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
 * смене WebView пересоздаётся и уничтожается в onRelease ([WebViewRenderer.release], без утечки нативных ресурсов).
 * Жест: горизонтальная прокрутка и масштаб — WebView, вертикальная — экран ([TouchDirectionLock]).
 * Смерть рендерера не роняет приложение ([WebViewRenderer], [RendererRecovery]).
 */
@Composable
fun HtmlBodyView(
    html: String,
    contentKey: Any,
    onMailto: (String) -> Unit,
    /** Загружать картинки и шрифты из интернета ([com.dedovmosol.iwomail.util.RemoteImages]). */
    remoteContentAllowed: Boolean,
) {
    val context = LocalContext.current
    val isRussian = isRussian()
    var webViewHeight by remember(contentKey) { mutableIntStateOf(0) }
    var webViewRef by remember(contentKey) { mutableStateOf<WebView?>(null) }
    // PERF: Отслеживаем последний загруженный HTML чтобы не перезагружать WebView при каждой рекомпозиции
    var lastLoadedHtml by remember(contentKey) { mutableStateOf("") }
    var lastRemoteAllowed by remember(contentKey) { mutableStateOf(false) }
    // Рендерер WebView умер: новый WebView (key) — после выгрузки ради памяти всегда, после падения — один раз;
    // второе падение — вместо оформления текст письма ([HtmlBodyTextFallback])
    var rendererGeneration by remember(contentKey) { mutableIntStateOf(0) }
    val rendererRecovery = remember(contentKey) { RendererRecovery() }
    var rendererFailed by remember(contentKey) { mutableStateOf(false) }

    key(contentKey) {
        if (rendererFailed) HtmlBodyTextFallback(html)
        else Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .then(if (webViewHeight > 0) Modifier.height(webViewHeight.dp) else Modifier),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color.White
            )
        ) {
            key(rendererGeneration) {
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
                            // Встроенные картинки (data:) — всегда; сеть — только с разрешения (см. update)
                            loadsImagesAutomatically = true
                            // Разрешаем смешанный контент (http изображения на https странице)
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            domStorageEnabled = false
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        }
                        setBackgroundColor(android.graphics.Color.WHITE)
                        // Отключаем скролл внутри WebView - скроллит родитель
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        // Вертикально WebView не прокручивается (высота — по содержимому): эффект края (растяжение
                        // Android 12+) на вертикальном дрейфе пальца дёргал бы содержимое при горизонтальной прокрутке
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER

                        // Горизонтальная прокрутка и масштаб — у WebView, вертикальная — у экрана; направление
                        // решается один раз на жест ([TouchDirectionLock])
                        val directionLock = TouchDirectionLock(
                            android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
                        )
                        setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN -> directionLock.onDown(event.x, event.y)
                                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                                    directionLock.onSecondPointer()
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    val before = directionLock.owner
                                    val owner = directionLock.onMove(event.x, event.y)
                                    if (owner == TouchDirectionLock.Owner.CHILD && before != owner) {
                                        v.parent?.requestDisallowInterceptTouchEvent(true)
                                    }
                                }
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                    directionLock.onEnd()
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
                                fun measure(v: WebView) {
                                    if (v !== webViewRef) return
                                    try {
                                        v.evaluateJavascript(MEASURE_CONTENT_JS) { result ->
                                            if (v !== webViewRef) return@evaluateJavascript
                                            val dpH = contentHeightDp(result, v.width, v.resources.displayMetrics.density)
                                            // + отступы WebView внутри карточки (padding 8.dp сверху и снизу)
                                            if (dpH != null) webViewHeight = dpH + 16
                                        }
                                    } catch (_: Exception) { }
                                }
                                view?.postDelayed({ measure(view) }, 500)
                                view?.postDelayed({ measure(view) }, 2000)
                            }

                            /**
                             * Рендерер умер ([WebViewRenderer]): приложение продолжает работу, этот WebView уничтожается
                             * (onRelease) и создаётся новый; второе падение на том же письме — текст вместо оформления.
                             */
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?
                            ): Boolean {
                                val crashed = WebViewRenderer.crashed(detail)
                                android.util.Log.e("HtmlBodyView", "WebView renderer gone, crashed=$crashed")
                                view?.let(WebViewRenderer::markGone)
                                if (webViewRef === view) webViewRef = null
                                // Новый WebView загрузит страницу заново
                                lastLoadedHtml = ""
                                if (rendererRecovery.onRendererGone(crashed)) rendererGeneration++ else rendererFailed = true
                                return true
                            }
                        }
                    }
                },
                onRelease = { webView ->
                    if (webViewRef === webView) webViewRef = null
                    WebViewRenderer.release(webView)
                },
                update = { webView ->
                    if (WebViewRenderer.isGone(webView)) return@AndroidView
                    webViewRef = webView
                    if (html == lastLoadedHtml && remoteContentAllowed == lastRemoteAllowed) return@AndroidView
                    lastRemoteAllowed = remoteContentAllowed
                    // Сеть закрыта дважды: CSP страницы и запрет загрузок самого WebView
                    webView.settings.blockNetworkImage = !remoteContentAllowed
                    webView.settings.blockNetworkLoads = !remoteContentAllowed
                    webView.settings.mixedContentMode =
                        if (remoteContentAllowed) android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        else android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

                    try {
                        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")

                        // DOCTYPE XHTML Transitional — «почти стандартный» режим, как в вёрстке писем: без него (quirks)
                        // body и height:100% растягиваются на высоту окна, и замер не меньше уже выставленной высоты —
                        // карточка не уменьшается и внизу остаются пустые белые листы.
                        val styledHtml = """
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
                        <html>
                        <head>
                            <meta http-equiv="Content-Security-Policy" content="${com.dedovmosol.iwomail.util.RemoteImages.contentSecurityPolicy(nonce, remoteContentAllowed)}">
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                            <style>
                                html { margin: 0 !important; padding: 0 !important; }
                                /* Высота страницы — по содержимому: body/таблицы height:100% из вёрстки писем иначе
                                   равны высоте окна, и карточка не уменьшается (пустые белые листы под письмом). */
                                html, body { height: auto !important; min-height: 0 !important; }
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
                        lastLoadedHtml = html
                    } catch (_: OutOfMemoryError) {
                        lastLoadedHtml = ""
                    } catch (_: Exception) {
                        lastLoadedHtml = ""
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
            }
    }
}
}

/** Сколько символов текста письма показывать без оформления: длинный Text раскладывается на главном потоке. */
private const val TEXT_FALLBACK_MAX_CHARS = 100_000

/**
 * Письмо, на котором дважды упал рендерер WebView: вместо оформления — его текст (выделяемый), чтобы письмо можно
 * было прочитать. Текст извлекается из HTML не на главном потоке.
 */
@Composable
private fun HtmlBodyTextFallback(html: String) {
    val text by androidx.compose.runtime.produceState<String?>(initialValue = null, html) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.dedovmosol.iwomail.util.stripHtml(html).take(TEXT_FALLBACK_MAX_CHARS)
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
            androidx.compose.material3.Text(
                text = com.dedovmosol.iwomail.ui.Strings.htmlBodyUnavailable,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            text?.let {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.material3.Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * Высота содержимого и ширина видимой области, обе в CSS px: "высота,ширина". Нижняя граница содержимого (Range по
 * всему body), а не documentElement.scrollHeight — тот не бывает меньше окна WebView, и карточка не уменьшалась бы.
 */
internal const val MEASURE_CONTENT_JS =
    "(function(){var b=document.body;if(!b)return '';" +
        "var r=document.createRange();r.selectNodeContents(b);" +
        "var bottom=Math.ceil(r.getBoundingClientRect().bottom+(window.scrollY||0));" +
        // Высота корня с полями детей (поля последнего блока проходят сквозь body, но не сквозь html): без неё WebView
        // был ниже страницы на эти поля и прокручивался внутри — горизонтальная прокрутка дёргалась по вертикали
        "var root=Math.ceil(document.documentElement.getBoundingClientRect().height||0);" +
        "var h=Math.max(b.scrollHeight||0,bottom,root);" +
        "var w=(window.visualViewport&&window.visualViewport.width)||window.innerWidth||0;" +
        "return h+','+w;})()"

/** Предел высоты карточки письма (dp): защита от ошибочного замера. */
private const val MAX_CONTENT_HEIGHT_DP = 20_000

/**
 * Высота содержимого в dp по ответу [MEASURE_CONTENT_JS]. Пикселей экрана на CSS px — по фактической ширине
 * ([viewWidthPx] / ширина видимой области), а не по WebView.scale: тот устарел и сразу после загрузки ещё не
 * учитывает уменьшение широкого письма под экран (высота выходила в 2–3 раза больше). null — замер не годится.
 */
internal fun contentHeightDp(jsResult: String?, viewWidthPx: Int, density: Float): Int? {
    val parts = jsResult?.trim('"')?.split(',') ?: return null
    if (parts.size != 2 || viewWidthPx <= 0 || density <= 0f) return null
    val cssHeight = parts[0].toDoubleOrNull() ?: return null
    val cssWidth = parts[1].toDoubleOrNull() ?: return null
    if (cssHeight <= 0.0 || cssWidth <= 0.0) return null
    val dp = (cssHeight * (viewWidthPx / cssWidth) / density).toInt()
    return dp.takeIf { it in 1 until MAX_CONTENT_HEIGHT_DP }
}

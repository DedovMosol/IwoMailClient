package com.dedovmosol.iwomail.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.PopupProperties
import com.dedovmosol.iwomail.ui.isRussian
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.util.escapeHtml

/**
 * Парсит цвет из разных форматов (hex, rgb(), rgba(), название)
 * @param colorString строка с цветом
 * @param asHex если true - возвращает hex строку, иначе Compose Color
 */
private fun parseColor(colorString: String, asHex: Boolean = false): Any? {
    return try {
        val androidColor = when {
            colorString.startsWith("#") -> {
                android.graphics.Color.parseColor(colorString)
            }
            colorString.startsWith("rgb(") -> {
                val values = colorString
                    .removePrefix("rgb(")
                    .removeSuffix(")")
                    .split(",")
                    .map { it.trim().toInt() }
                if (values.size >= 3) {
                    android.graphics.Color.rgb(values[0], values[1], values[2])
                } else 0
            }
            colorString.startsWith("rgba(") -> {
                val values = colorString
                    .removePrefix("rgba(")
                    .removeSuffix(")")
                    .split(",")
                    .map { it.trim() }
                if (values.size >= 4) {
                    android.graphics.Color.argb(
                        (values[3].toFloat() * 255).toInt(),
                        values[0].toInt(),
                        values[1].toInt(),
                        values[2].toInt()
                    )
                } else 0
            }
            else -> {
                android.graphics.Color.parseColor(colorString)
            }
        }
        
        if (asHex) {
            String.format("#%06X", 0xFFFFFF and androidColor).uppercase()
        } else {
            androidx.compose.ui.graphics.Color(androidColor)
        }
    } catch (e: Exception) {
        if (asHex) colorString.uppercase() else null
    }
}

/**
 * Безопасный парсинг цвета из разных форматов (hex, rgb(), название)
 * Возвращает Compose Color или null
 */
private fun parseColorSafe(colorString: String): androidx.compose.ui.graphics.Color? {
    return parseColor(colorString, asHex = false) as? androidx.compose.ui.graphics.Color
}

/**
 * Нормализует цвет к hex формату для сравнения
 * Браузер может возвращать цвета в разных форматах (rgb(), #hex, название)
 */
private fun normalizeColor(colorString: String): String {
    return parseColor(colorString, asHex = true) as? String ?: colorString.uppercase()
}

/**
 * Контроллер для управления Rich Text Editor из Compose
 */
class RichTextEditorController {
    internal var webView: WebView? = null
    internal var isLoaded = false
    
    // Состояние форматирования
    var isBold by mutableStateOf(false)
        internal set
    var isItalic by mutableStateOf(false)
        internal set
    var isUnderline by mutableStateOf(false)
        internal set
    var currentTextColor by mutableStateOf<String?>(null)
        internal set
    var currentHighlightColor by mutableStateOf<String?>(null)
        internal set
    var currentFontSize by mutableStateOf<String?>(null)
        internal set
    var currentFontName by mutableStateOf<String?>("Arial")
        internal set
    var currentAlignment by mutableStateOf<String>("left")
        internal set
    
    fun toggleBold() = execJs("toggleBold()")
    fun toggleItalic() = execJs("toggleItalic()")
    fun toggleUnderline() = execJs("toggleUnderline()")
    fun alignLeft() = execJs("alignLeft()")
    fun alignCenter() = execJs("alignCenter()")
    fun alignRight() = execJs("alignRight()")
    fun alignJustify() = execJs("alignJustify()")
    fun setFontSize(size: Int) = execJs("setFontSize($size)")
    fun setFontName(name: String) {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        execJs("setFontName('$escaped')")
    }
    fun setTextColor(color: String) {
        // Чёрный цвет храним как null (по умолчанию)
        currentTextColor = if (color == "#000000" || color.lowercase() == "black") null else color
        execJs("setTextColor('$color')")
    }
    fun setHighlightColor(color: String) {
        // Сохраняем цвет как есть (нормализация будет при получении из WebView)
        currentHighlightColor = color
        execJs("setHighlightColor('$color')")
    }
    fun removeHighlight() {
        currentHighlightColor = null
        execJs("removeHighlight()")
    }
    fun insertImage(base64: String, mimeType: String) {
        // base64 не содержит спецсимволов, но mimeType может
        val escapedMime = mimeType.replace("\\", "\\\\").replace("'", "\\'")
        execJs("insertImageBase64('$base64', '$escapedMime')")
    }
    fun setHtml(html: String) {
        val escaped = html.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        execJs("setHtml(\"$escaped\")")
    }
    fun focus() = execJs("focusEditor()")
    fun checkFormatState() = execJs("checkFormatState()")
    fun saveSelection() = execJs("saveCurrentSelection()")
    
    private fun execJs(script: String) {
        if (isLoaded) {
            webView?.evaluateJavascript(script, null)
        }
    }
    
    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<RichTextEditorController, Map<String, Any?>> = 
            androidx.compose.runtime.saveable.Saver(
                save = { controller ->
                    mapOf(
                        "isBold" to controller.isBold,
                        "isItalic" to controller.isItalic,
                        "isUnderline" to controller.isUnderline,
                        "currentTextColor" to controller.currentTextColor,
                        "currentHighlightColor" to controller.currentHighlightColor,
                        "currentFontSize" to controller.currentFontSize,
                        "currentFontName" to controller.currentFontName,
                        "currentAlignment" to controller.currentAlignment
                    )
                },
                restore = { saved ->
                    RichTextEditorController().apply {
                        isBold = saved["isBold"] as? Boolean ?: false
                        isItalic = saved["isItalic"] as? Boolean ?: false
                        isUnderline = saved["isUnderline"] as? Boolean ?: false
                        currentTextColor = saved["currentTextColor"] as? String
                        currentHighlightColor = saved["currentHighlightColor"] as? String
                        currentFontSize = saved["currentFontSize"] as? String
                        currentFontName = saved["currentFontName"] as? String ?: "Arial"
                        currentAlignment = saved["currentAlignment"] as? String ?: "left"
                    }
                }
            )
    }
}

@Composable
fun rememberRichTextEditorController(): RichTextEditorController {
    return rememberSaveable(saver = RichTextEditorController.Saver) { 
        RichTextEditorController() 
    }
}

/**
 * Rich Text Editor на базе WebView с contenteditable
 * Поддерживает форматирование: Bold, Italic, Underline, выравнивание, шрифты
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichTextEditor(
    initialHtml: String,
    onHtmlChanged: (String) -> Unit,
    controller: RichTextEditorController,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: Int = 200,
    isDarkTheme: Boolean = false
) {
    var isLoaded by remember { mutableStateOf(false) }
    
    // Используем общую функцию escapeHtml из HtmlUtils
    val escapedPlaceholder = placeholder.escapeHtml()
    
    // Цвета для темы
    val bgColor = if (isDarkTheme) "#1C1B1F" else "#FFFFFF"
    val textColor = if (isDarkTheme) "#E6E1E5" else "#1C1B1F"
    val placeholderColor = if (isDarkTheme) "#938F99" else "#79747E"
    val quoteColor = if (isDarkTheme) "#938F99" else "#666666"
    val borderColor = if (isDarkTheme) "#49454F" else "#CCCCCC"
    
    // HTML шаблон редактора
    val editorHtml = remember(escapedPlaceholder, minHeight, bgColor, textColor, placeholderColor, quoteColor, borderColor) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * { box-sizing: border-box; }
                html, body { 
                    margin: 0; 
                    padding: 0; 
                    height: 100%;
                    font-family: Arial, sans-serif;
                    font-size: 16px;
                    line-height: 1.5;
                    background-color: $bgColor;
                    color: $textColor;
                }
                #editor {
                    min-height: ${minHeight}px;
                    padding: 12px;
                    outline: none;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                    background-color: $bgColor;
                    color: $textColor;
                }
                #editor:empty:before {
                    content: attr(data-placeholder);
                    color: $placeholderColor;
                    pointer-events: none;
                }
                #editor img {
                    max-width: 100%;
                    height: auto;
                    display: block;
                    margin: 0;
                }
                #editor .image-block {
                    display: block;
                    margin: 12px 0 16px;
                }
                #editor div:empty,
                #editor p:empty {
                    min-height: 0;
                    margin: 0;
                    padding: 0;
                }
                blockquote {
                    margin: 10px 0;
                    padding-left: 15px;
                    border-left: 3px solid $borderColor;
                    color: $quoteColor;
                }
                .signature {
                    margin-top: 12px;
                    color: $quoteColor;
                }
            </style>
        </head>
        <body>
            <div id="editor" contenteditable="true" data-placeholder="$escapedPlaceholder"></div>
            <script>
                var editor = document.getElementById('editor');
                var lastSelectionRange = null;
                var ignoreNextFormatCheck = false; // Флаг для игнорирования checkFormatState после removeHighlight
                var forceHighlightOff = false; // Принудительно выключаем highlight для ввода
                
                // Явное сохранение выделения (вызывается перед открытием меню)
                function saveCurrentSelection() {
                    var sel = window.getSelection();
                    if (sel.rangeCount > 0) {
                        lastSelectionRange = sel.getRangeAt(0).cloneRange();
                    }
                }
                
                // Отправляем HTML в Kotlin при изменении
                editor.addEventListener('input', function() {
                    Android.onHtmlChanged(editor.innerHTML);
                });
                
                // Функции форматирования
                function execCmd(cmd, value) {
                    // Восстанавливаем выделение если оно потерялось (например, при открытии меню)
                    var selection = window.getSelection();
                    if (!selection.rangeCount && lastSelectionRange) {
                        try {
                            selection.removeAllRanges();
                            selection.addRange(lastSelectionRange);
                        } catch (e) {}
                    }
                    document.execCommand(cmd, false, value);
                    editor.focus();
                    Android.onHtmlChanged(editor.innerHTML);
                    checkFormatState(); // Обновляем состояние после применения форматирования
                }
                
                function toggleBold() { execCmd('bold'); }
                function toggleItalic() { execCmd('italic'); }
                function toggleUnderline() { execCmd('underline'); }
                
                function alignLeft() { execCmd('justifyLeft'); }
                function alignCenter() { execCmd('justifyCenter'); }
                function alignRight() { execCmd('justifyRight'); }
                function alignJustify() { execCmd('justifyFull'); }
                
                function setFontSize(size) { execCmd('fontSize', size); }
                function setFontName(name) {
                    // Используем CSS font-family с fallback для корректной работы на Android
                    var fontMap = {
                        'Arial': 'Arial, Helvetica, sans-serif',
                        'Times New Roman': "'Times New Roman', Times, serif",
                        'Courier New': "'Courier New', Courier, monospace",
                        'Verdana': 'Verdana, Geneva, sans-serif',
                        'Georgia': 'Georgia, serif'
                    };
                    var cssFont = fontMap[name] || name;
                    var sel = window.getSelection();
                    if (!sel.rangeCount && lastSelectionRange) {
                        try { sel.removeAllRanges(); sel.addRange(lastSelectionRange); } catch(e){}
                    }
                    if (sel.rangeCount > 0 && !sel.isCollapsed) {
                        var range = sel.getRangeAt(0);
                        try {
                            var span = document.createElement('span');
                            span.style.fontFamily = cssFont;
                            range.surroundContents(span);
                            sel.removeAllRanges();
                            sel.addRange(range);
                        } catch(e) {
                            // surroundContents бросит DOMException если выделение
                            // пересекает границы элементов — fallback на execCommand
                            execCmd('fontName', name);
                        }
                    } else {
                        // Если нет выделения, используем execCommand для курсора
                        execCmd('fontName', name);
                    }
                    editor.focus();
                    Android.onHtmlChanged(editor.innerHTML);
                    checkFormatState();
                }
                function setTextColor(color) { execCmd('foreColor', color); }
                
                function supportsCommand(cmd) {
                    try {
                        return document.queryCommandSupported && document.queryCommandSupported(cmd);
                    } catch (e) {
                        return false;
                    }
                }
                
                function hasHighlightStyle(el) {
                    if (!el || el.nodeType !== 1) return false;
                    if (el.tagName !== 'SPAN') return false;
                    if (el.style && (el.style.backgroundColor || el.style.background)) return true;
                    if (el.hasAttribute && el.hasAttribute('bgcolor')) return true;
                    return false;
                }
                
                function findHighlightSpan(node) {
                    var el = node && node.nodeType === 1 ? node : (node ? node.parentNode : null);
                    while (el && el !== editor && el !== document.body) {
                        if (hasHighlightStyle(el)) return el;
                        el = el.parentNode;
                    }
                    return null;
                }
                
                function cloneSpanWithoutBackground(span) {
                    var clone = span.cloneNode(false);
                    if (clone.style) {
                        clone.style.backgroundColor = '';
                        clone.style.background = '';
                    }
                    if (clone.hasAttribute && clone.hasAttribute('bgcolor')) {
                        clone.removeAttribute('bgcolor');
                    }
                    return clone;
                }
                
                function exitHighlightAtCaret(selection) {
                    if (!selection || !selection.rangeCount) return false;
                    var range = selection.getRangeAt(0);
                    if (!range.collapsed) return false;
                    
                    var highlightSpan = findHighlightSpan(range.startContainer);
                    if (!highlightSpan) return false;
                    
                    var container = range.startContainer;
                    var offset = range.startOffset;
                    
                    if (container.nodeType === 3) {
                        if (offset < container.nodeValue.length) {
                            var afterText = container.splitText(offset);
                            var newSpan = cloneSpanWithoutBackground(highlightSpan);
                            var current = afterText;
                            while (current) {
                                var next = current.nextSibling;
                                newSpan.appendChild(current);
                                current = next;
                            }
                            highlightSpan.parentNode.insertBefore(newSpan, highlightSpan.nextSibling);
                            if (newSpan.firstChild) {
                                range.setStart(newSpan.firstChild, 0);
                                range.setEnd(newSpan.firstChild, 0);
                                selection.removeAllRanges();
                                selection.addRange(range);
                                return true;
                            }
                        }
                    }
                    
                    // Если не смогли разделить — ставим курсор после highlight-спана
                    var textNode = document.createTextNode('\u200B');
                    highlightSpan.parentNode.insertBefore(textNode, highlightSpan.nextSibling);
                    range.setStart(textNode, 1);
                    range.setEnd(textNode, 1);
                    selection.removeAllRanges();
                    selection.addRange(range);
                    return true;
                }
                
                function wrapSelectionWithSpan(color) {
                    var selection = window.getSelection();
                    if (!selection.rangeCount) return false;
                    var range = selection.getRangeAt(0);
                    if (range.collapsed) return false;
                    var span = document.createElement('span');
                    span.style.backgroundColor = color;
                    range.surroundContents(span);
                    selection.removeAllRanges();
                    selection.addRange(range);
                    return true;
                }
                
                function setHighlightColor(color) { 
                    forceHighlightOff = false;
                    var selection = window.getSelection();
                    if (!selection.rangeCount && lastSelectionRange) {
                        try {
                            selection.removeAllRanges();
                            selection.addRange(lastSelectionRange);
                        } catch (e) {}
                    }
                    var applied = false;
                    if (supportsCommand('hiliteColor')) {
                        applied = document.execCommand('hiliteColor', false, color);
                    }
                    if (!applied && supportsCommand('backColor')) {
                        applied = document.execCommand('backColor', false, color);
                    }
                    if (!applied) {
                        wrapSelectionWithSpan(color);
                    }
                }
                
                function removeHighlight() {
                    var selection = window.getSelection();
                    forceHighlightOff = true;
                    
                    // Восстанавливаем выделение если потеряно
                    if (!selection.rangeCount && lastSelectionRange) {
                        try {
                            selection.removeAllRanges();
                            selection.addRange(lastSelectionRange);
                        } catch (e) {}
                    }
                    
                    if (!selection.rangeCount) {
                        // Нет выделения — просто выходим
                        return;
                    }
                    
                    var range = selection.getRangeAt(0);
                    var editor = document.getElementById('editor');
                    
                    // Функция для удаления backgroundColor у элемента
                    function clearBackground(el) {
                        if (!el || el.nodeType !== 1) return;
                        if (el.style) {
                            el.style.backgroundColor = '';
                            el.style.background = '';
                        }
                        if (el.hasAttribute && el.hasAttribute('bgcolor')) {
                            el.removeAttribute('bgcolor');
                        }
                    }
                    
                    // Функция для удаления пустых span
                    function cleanupSpan(el) {
                        if (el && el.tagName === 'SPAN' && el.style && el.style.cssText.trim() === '') {
                            var parent = el.parentNode;
                            if (parent) {
                                while (el.firstChild) {
                                    parent.insertBefore(el.firstChild, el);
                                }
                                parent.removeChild(el);
                            }
                        }
                    }
                    
                    // Если есть выделенный текст
                    if (!range.collapsed) {
                        // Получаем все текстовые узлы в выделении
                        var startNode = range.startContainer;
                        var endNode = range.endContainer;
                        
                        // Собираем все родительские элементы до editor
                        var parentsToClean = [];
                        var node = startNode;
                        while (node && node !== editor && node !== document.body) {
                            if (node.nodeType === 1) parentsToClean.push(node);
                            node = node.parentNode;
                        }
                        node = endNode;
                        while (node && node !== editor && node !== document.body) {
                            if (node.nodeType === 1 && parentsToClean.indexOf(node) === -1) {
                                parentsToClean.push(node);
                            }
                            node = node.parentNode;
                        }
                        
                        // Находим общий контейнер
                        var container = range.commonAncestorContainer;
                        if (container.nodeType === 3) container = container.parentNode;
                        
                        // Собираем все элементы внутри выделения
                        var elementsInRange = [];
                        if (container && container !== editor) {
                            var walker = document.createTreeWalker(container, NodeFilter.SHOW_ELEMENT, null);
                            var current;
                            while (current = walker.nextNode()) {
                                try {
                                    if (range.intersectsNode(current)) {
                                        elementsInRange.push(current);
                                    }
                                } catch (e) {}
                            }
                        }
                        
                        // Очищаем все найденные элементы
                        parentsToClean.forEach(clearBackground);
                        elementsInRange.forEach(clearBackground);
                        
                        // Удаляем пустые span
                        parentsToClean.forEach(cleanupSpan);
                        elementsInRange.forEach(cleanupSpan);
                        
                        // Устанавливаем флаг чтобы игнорировать автоматический checkFormatState
                        ignoreNextFormatCheck = true;
                        
                        // Явно отправляем состояние с пустым highlightColor
                        var bold = document.queryCommandState('bold');
                        var italic = document.queryCommandState('italic');
                        var underline = document.queryCommandState('underline');
                        var textColor = document.queryCommandValue('foreColor') || '';
                        var fontSize = document.queryCommandValue('fontSize') || '';
                        var fontName = document.queryCommandValue('fontName') || '';
                        fontName = fontName.replace(/['"]/g, '');
                        var alignment = 'left';
                        if (document.queryCommandState('justifyCenter')) alignment = 'center';
                        else if (document.queryCommandState('justifyRight')) alignment = 'right';
                        else if (document.queryCommandState('justifyFull')) alignment = 'justify';
                        
                        Android.onFormatStateChanged(bold, italic, underline, textColor, '', fontSize, fontName, alignment);
                    } else {
                        // Курсор без выделения - вставляем пустой текстовый узел чтобы "выйти" из span с фоном
                        if (supportsCommand('hiliteColor')) {
                            try { document.execCommand('hiliteColor', false, 'transparent'); } catch (e) {}
                        }
                        if (supportsCommand('backColor')) {
                            try { document.execCommand('backColor', false, 'transparent'); } catch (e) {}
                        }
                        var moved = exitHighlightAtCaret(selection);
                        if (!moved) {
                            var textNode = document.createTextNode('\u200B'); // Zero-width space
                            range.insertNode(textNode);
                            range.setStartAfter(textNode);
                            range.setEndAfter(textNode);
                            selection.removeAllRanges();
                            selection.addRange(range);
                        }
                        
                        // Устанавливаем флаг чтобы игнорировать автоматический checkFormatState
                        ignoreNextFormatCheck = true;
                        
                        // Принудительно отправляем состояние с пустым highlightColor
                        var bold = document.queryCommandState('bold');
                        var italic = document.queryCommandState('italic');
                        var underline = document.queryCommandState('underline');
                        var textColor = document.queryCommandValue('foreColor') || '';
                        var fontSize = document.queryCommandValue('fontSize') || '';
                        var fontName = document.queryCommandValue('fontName') || '';
                        fontName = fontName.replace(/['"]/g, '');
                        var alignment = 'left';
                        if (document.queryCommandState('justifyCenter')) alignment = 'center';
                        else if (document.queryCommandState('justifyRight')) alignment = 'right';
                        else if (document.queryCommandState('justifyFull')) alignment = 'justify';
                        
                        Android.onFormatStateChanged(bold, italic, underline, textColor, '', fontSize, fontName, alignment);
                    }
                }
                
                function insertImage(src) {
                    execCmd('insertImage', src);
                }
                
                function insertImageBase64(base64, mimeType) {
                    var img = document.createElement('img');
                    img.src = 'data:' + mimeType + ';base64,' + base64;
                    img.style.maxWidth = '100%';
                    img.style.display = 'block';
                    img.style.margin = '0';
                    var wrapper = document.createElement('div');
                    wrapper.className = 'image-block';
                    wrapper.style.display = 'block';
                    wrapper.style.marginTop = '12px';
                    wrapper.style.marginBottom = '16px';
                    wrapper.appendChild(img);
                    var signature = editor.querySelector('.signature');
                    var selection = window.getSelection();
                    var hasSelection = selection && selection.rangeCount > 0 && editor.contains(selection.getRangeAt(0).startContainer);
                    var selectionInSignature = signature && selection && selection.rangeCount > 0 && signature.contains(selection.getRangeAt(0).startContainer);
                    if (signature && (!hasSelection || selectionInSignature)) {
                        var parent = signature.parentNode;
                        var br = document.createElement('br');
                        var beforeBlock = document.createElement('div');
                        beforeBlock.innerHTML = '<br>';
                        parent.insertBefore(beforeBlock, signature);
                        parent.insertBefore(wrapper, signature);
                        parent.insertBefore(br, signature);
                        var range = document.createRange();
                        range.setStart(beforeBlock, 0);
                        range.collapse(true);
                        selection.removeAllRanges();
                        selection.addRange(range);
                    } else if (hasSelection) {
                        var range = selection.getRangeAt(0);
                        range.deleteContents();
                        var beforeBlock = document.createElement('div');
                        beforeBlock.innerHTML = '<br>';
                        range.insertNode(beforeBlock);
                        range.insertNode(wrapper);
                        // Добавляем пустой блок после картинки для курсора
                        var afterBlock = document.createElement('div');
                        afterBlock.innerHTML = '<br>';
                        range.setStartAfter(wrapper);
                        range.insertNode(afterBlock);
                        range.setStart(beforeBlock, 0);
                        range.collapse(true);
                        selection.removeAllRanges();
                        selection.addRange(range);
                    } else {
                        var beforeBlock = document.createElement('div');
                        beforeBlock.innerHTML = '<br>';
                        editor.appendChild(beforeBlock);
                        editor.appendChild(wrapper);
                        var endBlock = document.createElement('div');
                        endBlock.innerHTML = '<br>';
                        editor.appendChild(endBlock);
                        var endRange = document.createRange();
                        endRange.setStart(beforeBlock, 0);
                        endRange.collapse(true);
                        selection.removeAllRanges();
                        selection.addRange(endRange);
                    }
                    Android.onHtmlChanged(editor.innerHTML);
                }
                
                function setHtml(html) {
                    editor.innerHTML = html;
                    // Не вызываем onHtmlChanged чтобы избежать цикла
                    // Но обновляем состояние форматирования
                    setTimeout(function() {
                        checkFormatState();
                    }, 100);
                }
                
                function getHtml() {
                    return editor.innerHTML;
                }
                
                function focusEditor() {
                    editor.focus();
                    // Восстанавливаем выделение если потерялось (после закрытия меню)
                    var selection = window.getSelection();
                    if (!selection.rangeCount && lastSelectionRange) {
                        try {
                            selection.removeAllRanges();
                            selection.addRange(lastSelectionRange);
                        } catch (e) {}
                    }
                }
                
                // Проверка состояния форматирования
                function checkFormatState() {
                    // Если только что удалили highlight — игнорируем этот вызов
                    if (ignoreNextFormatCheck) {
                        ignoreNextFormatCheck = false;
                        return;
                    }
                    var sel = null;
                    try {
                        sel = window.getSelection();
                        if (sel && sel.rangeCount > 0) {
                            lastSelectionRange = sel.getRangeAt(0).cloneRange();
                        }
                    } catch (e) {}
                    
                    if (forceHighlightOff && sel && sel.rangeCount > 0) {
                        var range = sel.getRangeAt(0);
                        if (range.collapsed) {
                            if (exitHighlightAtCaret(sel)) {
                                ignoreNextFormatCheck = true;
                            }
                        }
                    }
                    
                    var isCollapsedSelection = false;
                    if (sel && sel.rangeCount > 0) {
                        isCollapsedSelection = sel.getRangeAt(0).collapsed;
                    }
                    var bold = document.queryCommandState('bold');
                    var italic = document.queryCommandState('italic');
                    var underline = document.queryCommandState('underline');
                    var textColor = document.queryCommandValue('foreColor') || '';
                    var highlightColor = document.queryCommandValue('hiliteColor') || '';
                    var fontSize = document.queryCommandValue('fontSize') || '';
                    var fontName = document.queryCommandValue('fontName') || '';
                    
                    // Вспомогательная функция: является ли цвет «прозрачным»
                    function isTransparentColor(c) {
                        return !c || c === 'transparent' || c === 'rgba(0, 0, 0, 0)' || c === 'initial' || c === 'inherit';
                    }
                    
                    // Если fontName или highlightColor пустые, пробуем getComputedStyle
                    try {
                        if (sel && sel.rangeCount > 0) {
                            var range = sel.getRangeAt(0);
                            var container = range.commonAncestorContainer;
                            var element = container.nodeType === 3 ? container.parentNode : container;
                            
                            if (element && element !== document.body && element !== editor) {
                                var style = window.getComputedStyle(element);
                                
                                // Получаем fontFamily если пустой
                                if (!fontName && style.fontFamily) {
                                    fontName = style.fontFamily;
                                }
                                
                                // Получаем backgroundColor — обходим дерево вверх до editor
                                if (isTransparentColor(highlightColor)) {
                                    var walkEl = container.nodeType === 3 ? container.parentNode : container;
                                    while (walkEl && walkEl !== editor && walkEl !== document.body) {
                                        var bg = window.getComputedStyle(walkEl).backgroundColor;
                                        if (!isTransparentColor(bg)) {
                                            highlightColor = bg;
                                            break;
                                        }
                                        walkEl = walkEl.parentNode;
                                    }
                                }
                            }
                        }
                    } catch (e) {
                        // Игнорируем ошибки getComputedStyle
                    }
                    
                    if (forceHighlightOff && isCollapsedSelection) {
                        highlightColor = '';
                    }
                    
                    // Убираем кавычки из fontName
                    fontName = fontName.replace(/['"]/g, '');
                    
                    // Определяем выравнивание
                    var alignment = 'left';
                    if (document.queryCommandState('justifyLeft')) alignment = 'left';
                    else if (document.queryCommandState('justifyCenter')) alignment = 'center';
                    else if (document.queryCommandState('justifyRight')) alignment = 'right';
                    else if (document.queryCommandState('justifyFull')) alignment = 'justify';
                    
                    Android.onFormatStateChanged(bold, italic, underline, textColor, highlightColor, fontSize, fontName, alignment);
                }
                
                // Проверяем состояние при изменении выделения
                document.addEventListener('selectionchange', function() {
                    checkFormatState();
                });
                
                // Проверяем состояние при клике в редакторе
                editor.addEventListener('click', function() {
                    // Сбрасываем forceHighlightOff при явном клике — 
                    // пользователь переместил курсор, нужно показать актуальное состояние
                    forceHighlightOff = false;
                    checkFormatState();
                });
                
                // Проверяем состояние при вводе
                editor.addEventListener('keyup', function() {
                    checkFormatState();
                });
            </script>
        </body>
        </html>
        """.trimIndent()
    }
    
    // Bundle для сохранения состояния WebView при повороте экрана
    val webViewStateBundle = rememberSaveable { android.os.Bundle() }
    
    // Храним актуальный callback в ref
    val onHtmlChangedRef = rememberUpdatedState(onHtmlChanged)
    
    // Храним актуальный initialHtml для использования в onPageFinished
    val initialHtmlRef = rememberUpdatedState(initialHtml)
    
    // Отслеживаем последний HTML из WebView чтобы избежать циклов
    // Используем object чтобы можно было менять значение из jsInterface
    val lastHtmlFromWebView = remember { mutableStateOf("") }
    
    // JS Interface для связи WebView → Kotlin
    // ВАЖНО: @JavascriptInterface методы вызываются на background thread,
    // поэтому нужно переключиться на main thread для обновления Compose state
    val jsInterface = remember(controller) {
        object {
            @JavascriptInterface
            fun onHtmlChanged(html: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    lastHtmlFromWebView.value = html
                    onHtmlChangedRef.value(html)
                }
            }
            
            @JavascriptInterface
            fun onFormatStateChanged(
                bold: Boolean, 
                italic: Boolean, 
                underline: Boolean, 
                textColor: String, 
                highlightColor: String,
                fontSize: String,
                fontName: String,
                alignment: String
            ) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    controller.isBold = bold
                    controller.isItalic = italic
                    controller.isUnderline = underline
                    
                    // Фильтруем дефолтные значения цветов (чёрный текст, без выделения)
                    // Браузер может вернуть цвет в разных форматах: rgb(), #hex, название
                    controller.currentTextColor = textColor.takeIf { 
                        it.isNotBlank() && 
                        it != "rgb(0, 0, 0)" && 
                        it != "#000000" && 
                        it.lowercase() != "black"
                    }
                    
                    // Для highlightColor нормализуем цвет перед сохранением
                    val normalizedHighlight = if (highlightColor.isNotBlank() && 
                        highlightColor != "transparent" && 
                        highlightColor != "rgba(0, 0, 0, 0)" &&
                        highlightColor != "rgb(0, 0, 0)") {
                        try {
                            normalizeColor(highlightColor)
                        } catch (e: Exception) {
                            highlightColor
                        }
                    } else {
                        null
                    }
                    controller.currentHighlightColor = normalizedHighlight
                    
                    // Сохраняем размер шрифта (fontSize возвращает 1-7, нам нужно маппить)
                    controller.currentFontSize = fontSize.takeIf { it.isNotBlank() }
                    
                    // Сохраняем семейство шрифта
                    // Дефолтные системные шрифты маппим на Arial, чтобы галочка ставилась корректно
                    val normalizedFont = fontName.replace("\"", "").replace("'", "").trim()
                    val firstFamily = normalizedFont.split(",").firstOrNull()?.trim().orEmpty()
                    val normalizedLower = normalizedFont.lowercase()
                    val firstLower = firstFamily.lowercase()
                    val isDefaultSans = listOf(
                        "arial",
                        "sans-serif",
                        "roboto",
                        "segoe ui",
                        "system-ui",
                        "-apple-system",
                        "blinkmacsystemfont"
                    ).any { token ->
                        normalizedLower.contains(token) || firstLower.contains(token)
                    }
                    controller.currentFontName = when {
                        normalizedFont.isBlank() -> "Arial"
                        isDefaultSans -> "Arial"
                        firstFamily.isNotBlank() -> firstFamily
                        else -> normalizedFont
                    }
                    
                    // Сохраняем выравнивание
                    controller.currentAlignment = alignment
                }
            }
        }
    }
    
    // Обновляем WebView когда initialHtml меняется извне (Reply/Forward/Draft/Signature)
    // Но НЕ когда изменение пришло из самого WebView (пользователь печатает)
    LaunchedEffect(initialHtml, isLoaded) {
        if (isLoaded && initialHtml != lastHtmlFromWebView.value) {
            lastHtmlFromWebView.value = initialHtml // Обновляем чтобы избежать повторного вызова
            controller.setHtml(initialHtml)
        }
    }
    
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(Color.TRANSPARENT)
                // Скрываем WebView до загрузки чтобы не мерцал белый прямоугольник
                alpha = 0f
                
                addJavascriptInterface(jsInterface, "Android")
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Плавно показываем WebView после загрузки
                        view?.animate()?.alpha(1f)?.setDuration(150)?.start()
                        isLoaded = true
                        controller.isLoaded = true
                        // Устанавливаем начальный HTML после загрузки
                        // Используем initialHtmlRef.value чтобы получить актуальное значение
                        val currentInitialHtml = initialHtmlRef.value
                        if (currentInitialHtml.isNotEmpty()) {
                            lastHtmlFromWebView.value = currentInitialHtml
                            val escapedHtml = currentInitialHtml
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("'", "\\'")
                                .replace("\n", "\\n")
                                .replace("\r", "")
                            evaluateJavascript("setHtml(\"$escapedHtml\");", null)
                        }
                        // Вызываем checkFormatState чтобы обновить состояние форматирования
                        evaluateJavascript("checkFormatState();", null)
                    }
                }
                
                loadDataWithBaseURL(null, editorHtml, "text/html", "UTF-8", null)
                controller.webView = this
            }
        },
        modifier = modifier,
        onRelease = { webView ->
            // Правильно уничтожаем WebView чтобы избежать краша в RenderThread
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            // Удаляем из parent
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
            
            controller.webView = null
            controller.isLoaded = false
        },
        update = { webView ->
            controller.webView = webView
        }
    )
}

/**
 * Панель инструментов форматирования
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RichTextToolbar(
    controller: RichTextEditorController,
    onInsertImage: () -> Unit,
    modifier: Modifier = Modifier,
    showInsertImage: Boolean = true
) {
    var showFontMenu by rememberSaveable { mutableStateOf(false) }
    var showTextColorMenu by rememberSaveable { mutableStateOf(false) }
    var showHighlightMenu by rememberSaveable { mutableStateOf(false) }
    var showAlignMenu by rememberSaveable { mutableStateOf(false) }
    val isRu = isRussian()
    
    // Цвета для активного состояния
    val activeColor = MaterialTheme.colorScheme.primaryContainer
    val activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val inactiveContentColor = MaterialTheme.colorScheme.onSurface
    
    // Размеры шрифта (fontSize использует значения 1-7)
    val fontSizes = listOf(
        Triple(1, "Мелкий", "Small"),
        Triple(3, "Обычный", "Normal"), 
        Triple(5, "Крупный", "Large"),
        Triple(7, "Очень крупный", "Extra Large")
    )
    
    // Типы шрифтов (названия шрифтов не локализуются)
    val fontFamilies = listOf(
        "Arial",
        "Times New Roman",
        "Courier New",
        "Verdana",
        "Georgia"
    )
    
    // Цвета текста
    val textColors = listOf(
        Triple("#000000", "Чёрный", "Black"),
        Triple("#FF0000", "Красный", "Red"),
        Triple("#0000FF", "Синий", "Blue"),
        Triple("#008000", "Зелёный", "Green"),
        Triple("#FFA500", "Оранжевый", "Orange"),
        Triple("#800080", "Фиолетовый", "Purple"),
        Triple("#808080", "Серый", "Gray")
    )
    
    // Цвета выделения
    val highlightColors = listOf(
        Triple("#FFFF00", "Жёлтый", "Yellow"),
        Triple("#00FF00", "Зелёный", "Green"),
        Triple("#00FFFF", "Голубой", "Cyan"),
        Triple("#FF69B4", "Розовый", "Pink"),
        Triple("#FFA500", "Оранжевый", "Orange"),
        Triple(null, "Убрать", "Remove")
    )
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        // Bold
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(36.dp)
                .focusProperties { canFocus = false }
                .then(
                    if (controller.isBold) 
                        Modifier.background(activeColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    else 
                        Modifier
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = { controller.toggleBold() }
                ),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                "B", 
                style = MaterialTheme.typography.titleMedium, 
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = if (controller.isBold) activeContentColor else inactiveContentColor
            )
        }
        
        // Italic
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(36.dp)
                .focusProperties { canFocus = false }
                .then(
                    if (controller.isItalic) 
                        Modifier.background(activeColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    else 
                        Modifier
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = { controller.toggleItalic() }
                ),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                "I", 
                style = MaterialTheme.typography.titleMedium, 
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = if (controller.isItalic) activeContentColor else inactiveContentColor
            )
        }
        
        // Underline
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(36.dp)
                .focusProperties { canFocus = false }
                .then(
                    if (controller.isUnderline) 
                        Modifier.background(activeColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    else 
                        Modifier
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = { controller.toggleUnderline() }
                ),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                "U", 
                style = MaterialTheme.typography.titleMedium, 
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                color = if (controller.isUnderline) activeContentColor else inactiveContentColor
            )
        }
        
        VerticalDivider(modifier = Modifier.height(24.dp))
        
        // Шрифт (размер + тип) — объединённое меню
        Box {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(36.dp)
                    .focusProperties { canFocus = false }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { 
                                controller.saveSelection()
                                showFontMenu = true
                            }
                        )
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Icon(AppIcons.FormatSize, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showFontMenu,
                    onDismissRequest = { 
                        showFontMenu = false
                        controller.focus()
                    },
                    modifier = Modifier.focusProperties { canFocus = false },
                    properties = PopupProperties(focusable = false)
                ) {
                    // Заголовок: Размер
                    Text(
                        if (isRu) "Размер" else "Size",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    fontSizes.forEach { (size, labelRu, labelEn) ->
                        val isActive = controller.currentFontSize == size.toString()
                        
                        DropdownMenuItem(
                            text = { 
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isRu) labelRu else labelEn, modifier = Modifier.weight(1f))
                                    if (isActive) {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                controller.setFontSize(size)
                                showFontMenu = false
                                controller.focus()
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    // Заголовок: Шрифт
                    Text(
                        if (isRu) "Шрифт" else "Font",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    fontFamilies.forEach { fontName ->
                        val isActive = controller.currentFontName?.equals(fontName, ignoreCase = true) == true
                        
                        DropdownMenuItem(
                            text = { 
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(fontName, modifier = Modifier.weight(1f))
                                    if (isActive) {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                controller.setFontName(fontName)
                                showFontMenu = false
                                controller.focus()
                            }
                        )
                    }
                }
            }
        
        // Цвет текста
        Box {
            val hasTextColor = controller.currentTextColor != null
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(36.dp)
                    .focusProperties { canFocus = false }
                    .then(
                        if (hasTextColor) 
                            Modifier.background(activeColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        else 
                            Modifier
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { 
                                controller.saveSelection()
                                showTextColorMenu = true 
                            }
                        )
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                // Буква A с цветной полоской снизу (как в Word/Google Docs)
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(
                        "A", 
                        style = MaterialTheme.typography.titleSmall,
                        color = if (hasTextColor) activeContentColor else inactiveContentColor
                    )
                    // Полоска с тонкой обводкой для видимости на любом фоне
                    val colorBarColor = if (hasTextColor && controller.currentTextColor != null) {
                        parseColorSafe(controller.currentTextColor!!) 
                            ?: MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(4.dp)
                            .background(
                                colorBarColor,
                                androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                            )
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
            DropdownMenu(
                expanded = showTextColorMenu,
                onDismissRequest = { 
                    showTextColorMenu = false
                    controller.focus()
                },
                modifier = Modifier.focusProperties { canFocus = false },
                properties = PopupProperties(focusable = false)
            ) {
                    textColors.forEach { (color, labelRu, labelEn) ->
                        // Чёрный цвет (#000000) считается активным когда currentTextColor == null
                        val isActive = if (color == "#000000") {
                            controller.currentTextColor == null || 
                            normalizeColor(controller.currentTextColor ?: "") == normalizeColor(color)
                        } else {
                            controller.currentTextColor?.let { 
                                normalizeColor(it) == normalizeColor(color)
                            } ?: false
                        }
                        
                        DropdownMenuItem(
                            text = { 
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(
                                                androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(color)),
                                                androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isRu) labelRu else labelEn, modifier = Modifier.weight(1f))
                                    if (isActive) {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                controller.setTextColor(color)
                                showTextColorMenu = false
                                controller.focus()
                            }
                        )
                    }
                }
            }
        
        // Выделение цветом (highlight)
        Box {
            val hasHighlight = controller.currentHighlightColor != null
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(36.dp)
                    .focusProperties { canFocus = false }
                    .then(
                        if (hasHighlight) 
                            Modifier.background(activeColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        else 
                            Modifier
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { 
                                controller.saveSelection() // Сохраняем выделение перед открытием меню
                                showHighlightMenu = true 
                            }
                        )
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                // Иконка маркера с цветной полоской снизу
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Icon(
                        AppIcons.FormatColorFill, 
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (hasHighlight) activeContentColor else inactiveContentColor
                    )
                    // Полоска с цветом выделения
                    if (hasHighlight && controller.currentHighlightColor != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(3.dp)
                                .background(
                                    parseColorSafe(controller.currentHighlightColor!!) 
                                        ?: MaterialTheme.colorScheme.primary,
                                    androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                                )
                                .border(
                                    width = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
            }
            DropdownMenu(
                expanded = showHighlightMenu,
                onDismissRequest = { 
                    showHighlightMenu = false
                    controller.focus()
                },
                    modifier = Modifier.focusProperties { canFocus = false },
                    properties = PopupProperties(focusable = false)
                ) {
                    highlightColors.forEach { (color, labelRu, labelEn) ->
                        val isActive = if (color != null) {
                            controller.currentHighlightColor?.let { 
                                normalizeColor(it) == normalizeColor(color)
                            } ?: false
                        } else {
                            // Для "Убрать" показываем галочку когда выделения нет
                            controller.currentHighlightColor == null
                        }
                        
                        DropdownMenuItem(
                            text = { 
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (color != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .background(
                                                    androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(color)),
                                                    androidx.compose.foundation.shape.CircleShape
                                                )
                                        )
                                    } else {
                                        Icon(AppIcons.Clear, null, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isRu) labelRu else labelEn, modifier = Modifier.weight(1f))
                                    if (isActive) {
                                        Icon(
                                            AppIcons.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                if (color != null) {
                                    controller.setHighlightColor(color)
                                } else {
                                    controller.removeHighlight()
                                }
                                showHighlightMenu = false
                                controller.focus()
                            }
                        )
                    }
                }
            }
        
        VerticalDivider(modifier = Modifier.height(24.dp))
        
        // Выравнивание — объединённое меню
        Box {
            // Определяем иконку в зависимости от текущего выравнивания
            val alignIcon = when (controller.currentAlignment) {
                "center" -> AppIcons.FormatAlignCenter
                "right" -> AppIcons.FormatAlignRight
                "justify" -> AppIcons.FormatAlignJustify
                else -> AppIcons.FormatAlignLeft // left или null
            }
            
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(36.dp)
                    .focusProperties { canFocus = false }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { 
                                controller.saveSelection()
                                showAlignMenu = true 
                            }
                        )
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(alignIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = showAlignMenu,
                onDismissRequest = { 
                    showAlignMenu = false
                    controller.focus()
                },
                modifier = Modifier.focusProperties { canFocus = false },
                properties = PopupProperties(focusable = false)
            ) {
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.FormatAlignLeft, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRu) "По левому краю" else "Align Left", modifier = Modifier.weight(1f))
                            if (controller.currentAlignment == "left") {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.alignLeft()
                        showAlignMenu = false
                        controller.focus()
                    }
                )
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.FormatAlignCenter, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRu) "По центру" else "Align Center", modifier = Modifier.weight(1f))
                            if (controller.currentAlignment == "center") {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.alignCenter()
                        showAlignMenu = false
                        controller.focus()
                    }
                )
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.FormatAlignRight, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRu) "По правому краю" else "Align Right", modifier = Modifier.weight(1f))
                            if (controller.currentAlignment == "right") {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.alignRight()
                        showAlignMenu = false
                        controller.focus()
                    }
                )
                DropdownMenuItem(
                    text = { 
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(AppIcons.FormatAlignJustify, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRu) "По ширине" else "Justify", modifier = Modifier.weight(1f))
                            if (controller.currentAlignment == "justify") {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.alignJustify()
                        showAlignMenu = false
                        controller.focus()
                    }
                )
            }
            }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Вставка изображения
        if (showInsertImage) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(36.dp)
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = onInsertImage
                    ),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(AppIcons.Image, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

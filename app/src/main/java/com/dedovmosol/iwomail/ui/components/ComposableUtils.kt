package com.dedovmosol.iwomail.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Создаёт CoroutineScope для синхронизации, который не отменяется при навигации
 * Автоматически отменяется при выходе с экрана
 */
@Composable
fun rememberSyncScope(): CoroutineScope {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    DisposableEffect(Unit) {
        onDispose { scope.cancel() }
    }
    return scope
}

/**
 * Создаёт debounced-состояние для поиска
 * @param initialValue Начальное значение
 * @param delayMs Задержка debounce в миллисекундах (по умолчанию 300)
 * @return Pair из (текущее значение, debounced значение, setter)
 */
@Composable
fun rememberDebouncedSearch(
    initialValue: String = "",
    delayMs: Long = 300L
): Triple<String, String, (String) -> Unit> {
    var value by remember { mutableStateOf(initialValue) }
    var debouncedValue by remember { mutableStateOf(initialValue) }
    
    LaunchedEffect(value) {
        delay(delayMs)
        debouncedValue = value
    }
    
    return Triple(value, debouncedValue) { newValue -> value = newValue }
}

/**
 * Упрощённая версия для получения только debounced значения
 */
@Composable
fun <T> rememberDebouncedState(
    value: T,
    delayMs: Long = 300L
): State<T> {
    val debouncedState = remember { mutableStateOf(value) }
    
    LaunchedEffect(value) {
        delay(delayMs)
        debouncedState.value = value
    }
    
    return debouncedState
}

// ════════════════════════════════════════════════════════════════
// Скроллбары — единая точка управления стилем (DRY)
// ════════════════════════════════════════════════════════════════

/** Общие настройки для всех скроллбаров приложения. */
object ScrollbarDefaults {
    /** Ширина thumb (dp) */
    const val THUMB_WIDTH = 5f
    /** Минимальная высота thumb (dp) */
    const val THUMB_MIN_HEIGHT = 28f
    /** Вертикальный отступ (dp) */
    const val PADDING_VERTICAL = 4f
    /** Отступ от правого края (dp) */
    const val PADDING_END = 2f
    /** Прозрачность при полной видимости */
    const val ALPHA = 0.6f
    /** Время появления (мс) */
    const val FADE_IN_MS = 150
    /** Время исчезновения (мс) */
    const val FADE_OUT_MS = 800
    /** Задержка перед скрытием (мс) */
    const val AUTO_HIDE_DELAY_MS = 2500L
    
    /** Цвет скроллбара — фиксированный голубой (Outlook-style). */
    val color: Color get() = Color(0xFF0078D4)
}

/**
 * Скроллбар-индикатор для LazyColumn.
 * Показывается при прокрутке, плавно исчезает через [ScrollbarDefaults.AUTO_HIDE_DELAY_MS].
 *
 * Использование:
 * ```
 * Box(modifier = Modifier.fillMaxSize()) {
 *     LazyColumn(state = listState) { ... }
 *     LazyColumnScrollbar(listState)
 * }
 * ```
 */
@Composable
fun BoxScope.LazyColumnScrollbar(listState: LazyListState) {
    val canScroll = listState.canScrollForward || listState.canScrollBackward
    val isScrolling = listState.isScrollInProgress
    
    var showScrollbar by remember { mutableStateOf(false) }
    
    // Показываем скроллбар при первом отображении если контент прокручиваем
    LaunchedEffect(canScroll) {
        if (canScroll) {
            showScrollbar = true
            delay(ScrollbarDefaults.AUTO_HIDE_DELAY_MS)
            if (!listState.isScrollInProgress) {
                showScrollbar = false
            }
        }
    }
    
    LaunchedEffect(isScrolling) {
        if (isScrolling) {
            showScrollbar = true
        } else {
            delay(ScrollbarDefaults.AUTO_HIDE_DELAY_MS)
            showScrollbar = false
        }
    }
    
    val alpha by animateFloatAsState(
        targetValue = if (showScrollbar && canScroll) ScrollbarDefaults.ALPHA else 0f,
        animationSpec = tween(
            durationMillis = if (showScrollbar) ScrollbarDefaults.FADE_IN_MS else ScrollbarDefaults.FADE_OUT_MS
        ),
        label = "scrollbar_alpha"
    )
    
    if (alpha > 0.01f) {
        val scrollbarColor = ScrollbarDefaults.color
        val thumbWidth = ScrollbarDefaults.THUMB_WIDTH
        val thumbMinHeight = ScrollbarDefaults.THUMB_MIN_HEIGHT
        
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(thumbWidth.dp + ScrollbarDefaults.PADDING_END.dp)
                .padding(
                    top = ScrollbarDefaults.PADDING_VERTICAL.dp,
                    bottom = ScrollbarDefaults.PADDING_VERTICAL.dp,
                    end = ScrollbarDefaults.PADDING_END.dp
                )
        ) {
            val totalItems = listState.layoutInfo.totalItemsCount.toFloat()
            val visibleItems = listState.layoutInfo.visibleItemsInfo.size.toFloat()
            
            if (totalItems > 0 && visibleItems < totalItems) {
                val firstVisible = listState.firstVisibleItemIndex.toFloat()
                val thumbHeight = ((visibleItems / totalItems) * size.height)
                    .coerceAtLeast(thumbMinHeight.dp.toPx())
                val scrollRange = size.height - thumbHeight
                val thumbY = if (totalItems - visibleItems > 0)
                    (firstVisible / (totalItems - visibleItems)) * scrollRange
                else 0f
                
                drawRoundRect(
                    color = scrollbarColor.copy(alpha = alpha),
                    topLeft = Offset(0f, thumbY.coerceIn(0f, scrollRange)),
                    size = Size(thumbWidth.dp.toPx(), thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth.dp.toPx() / 2)
                )
            }
        }
    }
}

/**
 * Скроллбар-индикатор для Column с verticalScroll(scrollState).
 * Всегда виден когда контент прокручивается. Для диалогов и форм.
 *
 * Использование:
 * ```
 * Box(modifier = Modifier.heightIn(max = 300.dp)) {
 *     Column(modifier = Modifier.verticalScroll(scrollState)) { ... }
 *     ScrollColumnScrollbar(scrollState)
 * }
 * ```
 */
@Composable
fun BoxScope.ScrollColumnScrollbar(scrollState: ScrollState) {
    if (scrollState.maxValue > 0) {
        val scrollbarColor = ScrollbarDefaults.color
        val thumbWidth = ScrollbarDefaults.THUMB_WIDTH
        
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(thumbWidth.dp + ScrollbarDefaults.PADDING_END.dp)
                .padding(
                    top = ScrollbarDefaults.PADDING_VERTICAL.dp,
                    bottom = ScrollbarDefaults.PADDING_VERTICAL.dp,
                    end = ScrollbarDefaults.PADDING_END.dp
                )
        ) {
            val scrollFraction = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
            val viewportFraction = size.height / (size.height + scrollState.maxValue)
            val thumbHeight = (viewportFraction * size.height)
                .coerceAtLeast(ScrollbarDefaults.THUMB_MIN_HEIGHT.dp.toPx())
            val scrollRange = size.height - thumbHeight
            val thumbY = scrollFraction * scrollRange
            
            drawRoundRect(
                color = scrollbarColor.copy(alpha = ScrollbarDefaults.ALPHA),
                topLeft = Offset(0f, thumbY.coerceIn(0f, scrollRange)),
                size = Size(thumbWidth.dp.toPx(), thumbHeight),
                cornerRadius = CornerRadius(thumbWidth.dp.toPx() / 2)
            )
        }
    }
}

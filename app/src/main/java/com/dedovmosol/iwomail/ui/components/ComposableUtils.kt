package com.dedovmosol.iwomail.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/**
 * Предустановленные цвета скроллбара.
 */
enum class ScrollbarColor(val code: String, val color: Color, val displayNameRu: String, val displayNameEn: String) {
    BLUE("blue", Color(0xFF2196F3), "Синий", "Blue"),
    PURPLE("purple", Color(0xFF9C27B0), "Фиолетовый", "Purple"),
    RED("red", Color(0xFFF44336), "Красный", "Red"),
    GREEN("green", Color(0xFF4CAF50), "Зелёный", "Green"),
    ORANGE("orange", Color(0xFFFF9800), "Оранжевый", "Orange"),
    PINK("pink", Color(0xFFFF4081), "Розовый", "Pink"),
    GRAY("gray", Color(0xFF757575), "Серый", "Gray");

    fun getDisplayName(isRussian: Boolean): String = if (isRussian) displayNameRu else displayNameEn

    companion object {
        fun fromCode(code: String): ScrollbarColor = entries.find { it.code == code } ?: BLUE
    }
}

/** CompositionLocal для текущего цвета скроллбара. */
val LocalScrollbarColor = compositionLocalOf { ScrollbarColor.BLUE }

/** Общие настройки для всех скроллбаров приложения. */
object ScrollbarDefaults {
    /** Ширина thumb (dp) */
    const val THUMB_WIDTH = 6f
    /** Минимальная высота thumb (dp) */
    const val THUMB_MIN_HEIGHT = 28f
    /** Вертикальный отступ (dp) */
    const val PADDING_VERTICAL = 4f
    /** Отступ от правого края (dp) */
    const val PADDING_END = 2f
    /** Прозрачность при полной видимости */
    const val ALPHA = 0.85f
    /** Время появления (мс) */
    const val FADE_IN_MS = 150
    /** Время исчезновения (мс) */
    const val FADE_OUT_MS = 800
    /** Задержка перед скрытием (мс) */
    const val AUTO_HIDE_DELAY_MS = 2500L
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
        val scrollbarColor = LocalScrollbarColor.current.color
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
        val scrollbarColor = LocalScrollbarColor.current.color
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

// ════════════════════════════════════════════════════════════════
// Drag Selection — long press + drag для выделения диапазона (DRY)
// ════════════════════════════════════════════════════════════════

/**
 * Modifier для drag selection в LazyColumn.
 * Долгий тап + протягивание = выделение диапазона элементов.
 * Авто-скролл при приближении к краям, вибрация при каждом новом элементе.
 *
 * @param listState состояние LazyColumn
 * @param itemKeys ключи элементов в порядке отображения (должны совпадать с key { } в items())
 * @param selectedIds текущий набор выделенных ключей
 * @param onSelectionChange callback при изменении выделения
 */
@Composable
fun rememberDragSelectModifier(
    listState: LazyListState,
    itemKeys: List<String>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragCurrentIndex by remember { mutableStateOf(-1) }
    var initialSelection by remember { mutableStateOf(setOf<String>()) }
    
    // rememberUpdatedState — closure всегда видит актуальные значения
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentOnChange by rememberUpdatedState(onSelectionChange)
    
    val indexMap = remember(itemKeys) {
        itemKeys.withIndex().associate { (index, key) -> key to index }
    }
    
    return Modifier.pointerInput(itemKeys) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val index = findItemIndexAtY(listState, indexMap, offset.y)
                if (index >= 0 && index < itemKeys.size) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragStartIndex = index
                    dragCurrentIndex = index
                    initialSelection = currentSelectedIds
                    currentOnChange(initialSelection + itemKeys[index])
                }
            },
            onDrag = { change, _ ->
                if (dragStartIndex >= 0) {
                    change.consume()
                    val index = findItemIndexAtY(listState, indexMap, change.position.y)
                    if (index >= 0 && index != dragCurrentIndex) {
                        dragCurrentIndex = index
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val start = minOf(dragStartIndex, dragCurrentIndex)
                        val end = minOf(maxOf(dragStartIndex, dragCurrentIndex), itemKeys.size - 1)
                        val rangeIds = itemKeys.subList(start, end + 1).toSet()
                        currentOnChange(initialSelection + rangeIds)
                    }
                    
                    // Авто-скролл при приближении к краям
                    val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()
                    val scrollZone = viewportHeight * 0.12f
                    val scrollSpeed = 15f
                    when {
                        change.position.y < scrollZone ->
                            scope.launch { listState.scrollBy(-scrollSpeed) }
                        change.position.y > viewportHeight - scrollZone ->
                            scope.launch { listState.scrollBy(scrollSpeed) }
                    }
                }
            },
            onDragEnd = {
                dragStartIndex = -1
                dragCurrentIndex = -1
            },
            onDragCancel = {
                dragStartIndex = -1
                dragCurrentIndex = -1
            }
        )
    }
}

private fun findItemIndexAtY(
    listState: LazyListState,
    indexMap: Map<String, Int>,
    y: Float
): Int {
    val layoutInfo = listState.layoutInfo
    for (itemInfo in layoutInfo.visibleItemsInfo) {
        val key = itemInfo.key
        if (key is String) {
            val idx = indexMap[key] ?: continue
            val itemTop = (itemInfo.offset - layoutInfo.viewportStartOffset).toFloat()
            val itemBottom = itemTop + itemInfo.size.toFloat()
            if (y >= itemTop && y < itemBottom) return idx
        }
    }
    return -1
}

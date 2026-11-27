package com.dedovmosol.iwomail.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Вкладка контактов: название и число контактов (null — без числа). */
internal data class ContactTab(val label: String, val count: Int?)

/** Поля текста внутри вкладки: 16 dp с каждой стороны, как у вкладки M3. */
private val TAB_TEXT_PADDING = 32.dp
/** Узкий экран: по 4 dp с каждой стороны — названия целиком, без прокрутки. */
private val TAB_TEXT_PADDING_COMPACT = 8.dp
/** Две строки (название и число) и поля сверху и снизу; не меньше 48 dp — минимальная зона нажатия. */
private val TAB_MIN_HEIGHT = 56.dp

/** Как разложить вкладки по ширине. */
internal enum class ContactTabsLayout {
    /** Равные доли ширины — фиксированные вкладки M3. */
    EQUAL,
    /** Каждой — ширина её текста и поровну остатка: равные доли не помещаются, а сам текст помещается. */
    BY_CONTENT,
    /** Вкладки по ширине текста с прокруткой: не помещается и текст (крупный шрифт, очень узкое окно). */
    SCROLL
}

/**
 * Выбор раскладки по ширине текста вкладок [contentPx] (без полей), полям [paddingPx] и ширине ряда [maxWidthPx].
 * Сначала равные доли с полями M3, затем с узкими полями, затем по ширине текста, и только потом прокрутка.
 */
internal fun chooseContactTabsLayout(contentPx: List<Int>, paddingPx: Int, compactPaddingPx: Int, maxWidthPx: Int): Pair<ContactTabsLayout, Int> {
    if (contentPx.isEmpty()) return ContactTabsLayout.EQUAL to paddingPx
    val widest = contentPx.max()
    for (p in listOf(paddingPx, compactPaddingPx)) {
        if ((widest + p) * contentPx.size <= maxWidthPx) return ContactTabsLayout.EQUAL to p
    }
    if (contentPx.sumOf { it + compactPaddingPx } <= maxWidthPx) return ContactTabsLayout.BY_CONTENT to compactPaddingPx
    return ContactTabsLayout.SCROLL to paddingPx
}

/**
 * Вкладки источников контактов — экран «Контакты» и выбор получателей (DRY). Ничего не обрезается и не прячется
 * за край: название в одну строку без многоточия, число — второй строкой мельче. Раскладка — [chooseContactTabsLayout].
 */
@Composable
internal fun ContactSourceTabs(
    tabs: List<ContactTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val labelStyle = MaterialTheme.typography.titleSmall
    val countStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Ширина текста каждой вкладки; от чисел зависит только их длина в цифрах — не пересчитываем на каждое число
    val labels = tabs.map { it.label }
    val digits = tabs.map { it.count?.toString()?.length ?: 0 }
    val contentPx = remember(labels, digits, labelStyle, countStyle, density) {
        tabs.map { tab ->
            val label = measurer.measure(tab.label, labelStyle, maxLines = 1, softWrap = false).size.width
            val count = tab.count?.let { measurer.measure("8".repeat(it.toString().length), countStyle, maxLines = 1).size.width } ?: 0
            maxOf(label, count)
        }
    }
    BoxWithConstraints(modifier) {
        val (layout, paddingPx) = chooseContactTabsLayout(
            contentPx = contentPx,
            paddingPx = with(density) { TAB_TEXT_PADDING.roundToPx() },
            compactPaddingPx = with(density) { TAB_TEXT_PADDING_COMPACT.roundToPx() },
            maxWidthPx = constraints.maxWidth
        )
        val padding = with(density) { paddingPx.toDp() }

        @Composable
        fun TabItem(index: Int, tab: ContactTab, tabModifier: Modifier) {
            // Перегрузка со своим содержимым: у вкладки со слотом text высота фиксирована (48 dp), и вторая
            // строка с числом не помещалась по высоте. Поля — внутри содержимого: модификатор Tab стоит до
            // selectable, и поля в нём сузили бы зону нажатия и ripple
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                modifier = tabModifier
                    .testTag("contactTab_$index")
                    .heightIn(min = TAB_MIN_HEIGHT)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = padding / 2, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(tab.label, style = labelStyle, maxLines = 1, softWrap = false)
                    if (tab.count != null) {
                        Text(
                            "${tab.count}",
                            style = countStyle,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        if (layout == ContactTabsLayout.SCROLL) {
            ScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, tab -> TabItem(i, tab, Modifier) }
            }
        } else {
            // Доли ширины: равные или по тексту с полями (остаток делится поровну — промежутки между словами равные)
            val total = constraints.maxWidth.toFloat()
            val widthsPx = if (layout == ContactTabsLayout.EQUAL || tabs.isEmpty()) {
                List(tabs.size) { total / tabs.size.coerceAtLeast(1) }
            } else {
                val spare = (total - contentPx.sumOf { it + paddingPx }) / tabs.size
                contentPx.map { it + paddingPx + spare }
            }
            FixedContactTabRow(widthsPx = widthsPx, selected = selected) { i, w ->
                TabItem(i, tabs[i], Modifier.width(with(density) { w.toDp() }))
            }
        }
    }
}

/**
 * Ряд вкладок с заданной шириной каждой — как TabRow M3 (те же цвета, разделитель, индикатор и его анимация),
 * но доли могут быть неравными: стандартный TabRow делит ширину только поровну.
 */
@Composable
private fun FixedContactTabRow(
    widthsPx: List<Float>,
    selected: Int,
    tab: @Composable (index: Int, widthPx: Float) -> Unit
) {
    val density = LocalDensity.current
    val index = selected.coerceIn(0, (widthsPx.size - 1).coerceAtLeast(0))
    val left = with(density) { widthsPx.take(index).sum().toDp() }
    val width = with(density) { (widthsPx.getOrNull(index) ?: 0f).toDp() }
    // Анимация индикатора — как у TabRow (tabIndicatorOffset): 250 мс, FastOutSlowIn
    val spec = tween<Dp>(durationMillis = 250, easing = FastOutSlowInEasing)
    val indicatorLeft by animateDpAsState(left, spec, label = "contactTabIndicatorLeft")
    val indicatorWidth by animateDpAsState(width, spec, label = "contactTabIndicatorWidth")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TabRowDefaults.primaryContainerColor,
        contentColor = TabRowDefaults.primaryContentColor
    ) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                widthsPx.forEachIndexed { i, w -> tab(i, w) }
            }
            HorizontalDivider(Modifier.align(Alignment.BottomStart))
            if (widthsPx.isNotEmpty()) {
                TabRowDefaults.SecondaryIndicator(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = indicatorLeft)
                        .width(indicatorWidth)
                )
            }
        }
    }
}

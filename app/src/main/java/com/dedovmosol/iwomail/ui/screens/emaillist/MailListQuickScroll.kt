package com.dedovmosol.iwomail.ui.screens.emaillist

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.theme.AppIcons
import kotlinx.coroutines.launch

// Keep the final row reachable above the compose FAB (56 dp + two 16 dp margins).
internal fun mailListContentPadding(selectionMode: Boolean) =
    PaddingValues(bottom = if (selectionMode) 0.dp else 88.dp)

internal enum class ListEdge { START, END }

internal fun quickScrollEdge(itemCount: Int, canScrollBackward: Boolean, canScrollForward: Boolean): ListEdge? = when {
    itemCount <= 0 -> null
    canScrollBackward -> ListEdge.START
    canScrollForward -> ListEdge.END
    else -> null
}

internal fun ListEdge.targetIndex(itemCount: Int): Int? =
    if (itemCount <= 0) null else if (this == ListEdge.START) 0 else itemCount - 1

internal fun remainingEndScroll(itemOffset: Int, itemSize: Int, afterPadding: Int, viewportEnd: Int): Float =
    (itemOffset.toLong() + itemSize + afterPadding - viewportEnd).coerceAtLeast(0L).toFloat()

private fun LazyListState.quickScrollEdge(): ListEdge? {
    val layout = layoutInfo
    val first = layout.visibleItemsInfo.firstOrNull() ?: return null
    val last = layout.visibleItemsInfo.last()
    // The trailing FAB gutter alone should not make a short list offer a jump.
    if (first.index == 0 && last.index == layout.totalItemsCount - 1 &&
        last.offset.toLong() + last.size - first.offset <= layout.viewportSize.height) return null
    return quickScrollEdge(layout.totalItemsCount, canScrollBackward, canScrollForward)
}

/** In the safe content area, opposite the compose FAB; never over selection controls. */
@Composable
internal fun MailListQuickScroll(listState: LazyListState, enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val edge by remember(listState) { derivedStateOf { listState.quickScrollEdge() } }
    val action = edge ?: return
    val scope = rememberCoroutineScope()
    FilledTonalIconButton(
        onClick = {
            scope.launch {
                // Read the current layout, not the data size or the last-composed index.
                if (listState.quickScrollEdge() != action) return@launch
                val target = action.targetIndex(listState.layoutInfo.totalItemsCount) ?: return@launch
                listState.scrollToItem(target)
                if (action == ListEdge.END && listState.canScrollForward) {
                    // A row can itself be taller than the viewport (e.g. at large font scale).
                    val layout = listState.layoutInfo
                    val last = layout.visibleItemsInfo.lastOrNull { it.index == layout.totalItemsCount - 1 }
                    if (last != null) listState.scrollBy(
                        remainingEndScroll(last.offset, last.size, layout.afterContentPadding, layout.viewportEndOffset)
                    )
                }
            }
        },
        modifier = modifier.padding(start = 16.dp, bottom = 16.dp).size(48.dp),
        shape = CircleShape
    ) {
        val start = action == ListEdge.START
        Icon(
            imageVector = if (start) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
            contentDescription = if (start) mailListLabel("В начало списка", "To start of list")
            else mailListLabel("В конец списка", "To end of list")
        )
    }
}

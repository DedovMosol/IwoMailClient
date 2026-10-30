package com.dedovmosol.iwomail.ui.screens.emaillist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.domain.SwipeAction
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppColors
import com.dedovmosol.iwomail.ui.theme.AppIcons

/** Действие свайпа с учётом папки: удалять из «Удалённых» и Черновиков свайпом нельзя, флаг — там, где есть звёздочка. */
internal fun SwipeAction.availableIn(deleteAllowed: Boolean, flagAllowed: Boolean): SwipeAction = when (this) {
    SwipeAction.DELETE -> if (deleteAllowed) this else SwipeAction.NONE
    SwipeAction.FLAG -> if (flagAllowed) this else SwipeAction.NONE
    else -> this
}

/**
 * Строка письма со свайпами: слева направо — [startAction], справа налево — [endAction]. Строка всегда возвращается
 * на место: удалённое письмо уберёт из списка сама база (при ошибке оно просто остаётся). Те же действия — в меню
 * специальных возможностей (TalkBack).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeableEmailRow(
    startAction: SwipeAction,
    endAction: SwipeAction,
    read: Boolean,
    flagged: Boolean,
    onSwipe: (SwipeAction) -> Unit,
    content: @Composable () -> Unit
) {
    if (startAction == SwipeAction.NONE && endAction == SwipeAction.NONE) {
        content()
        return
    }
    val haptic = LocalHapticFeedback.current
    val currentStart by rememberUpdatedState(startAction)
    val currentEnd by rememberUpdatedState(endAction)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentStart
                SwipeToDismissBoxValue.EndToStart -> currentEnd
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            if (action != SwipeAction.NONE) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnSwipe(action)
            }
            false
        }
    )
    val labels = listOf(startAction, endAction).distinct().filter { it != SwipeAction.NONE }
        .map { it to label(it, read, flagged) }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = startAction != SwipeAction.NONE,
        enableDismissFromEndToStart = endAction != SwipeAction.NONE,
        modifier = Modifier.semantics {
            customActions = labels.map { (action, text) -> CustomAccessibilityAction(text) { currentOnSwipe(action); true } }
        },
        backgroundContent = {
            val direction = state.dismissDirection
            val action = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> startAction
                SwipeToDismissBoxValue.EndToStart -> endAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            // Фон рисуется только во время свайпа — в покое лишней отрисовки под строкой нет
            if (action != SwipeAction.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color(action))
                        .padding(horizontal = 24.dp),
                    contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    Icon(icon(action, read, flagged), contentDescription = null, tint = Color.White)
                }
            }
        }
    ) {
        content()
    }
}

@Composable
private fun color(action: SwipeAction): Color = when (action) {
    SwipeAction.DELETE -> MaterialTheme.colorScheme.error
    SwipeAction.FLAG -> AppColors.favorites
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun icon(action: SwipeAction, read: Boolean, flagged: Boolean) = when (action) {
    SwipeAction.DELETE -> AppIcons.Delete
    SwipeAction.FLAG -> if (flagged) AppIcons.StarOutline else AppIcons.Star
    else -> if (read) AppIcons.MarkEmailUnread else AppIcons.MarkEmailRead
}

@Composable
private fun label(action: SwipeAction, read: Boolean, flagged: Boolean): String = when (action) {
    SwipeAction.DELETE -> Strings.delete
    SwipeAction.FLAG -> if (flagged) Strings.removeFromFavorites else Strings.addToFavorites
    else -> if (read) Strings.markUnread else Strings.markRead
}

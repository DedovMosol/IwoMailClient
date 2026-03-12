package com.dedovmosol.iwomail.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.theme.AppIcons

private val DragSelectionCheckedColor = Color(0xFF68C957)
private val DragSelectionUncheckedColor = Color(0xFFF5F7F7)
private const val DragSelectionAnimationMs = 140

@Composable
fun DragSelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    DragSelectionIndicator(
        state = if (selected) ToggleableState.On else ToggleableState.Off,
        modifier = modifier,
        size = size
    )
}

@Composable
fun DragSelectionIndicator(
    state: ToggleableState,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    val isActive = state != ToggleableState.Off
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.94f,
        animationSpec = tween(DragSelectionAnimationMs),
        label = "drag_selection_scale"
    )
    val fillColor by animateColorAsState(
        targetValue = if (isActive) {
            DragSelectionCheckedColor
        } else {
            DragSelectionUncheckedColor
        },
        animationSpec = tween(DragSelectionAnimationMs),
        label = "drag_selection_fill"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) {
            Color.White
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
        },
        animationSpec = tween(DragSelectionAnimationMs),
        label = "drag_selection_border"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(DragSelectionAnimationMs),
        label = "drag_selection_icon_alpha"
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isActive) 4.dp else 0.dp,
                shape = CircleShape,
                clip = false
            )
            .clip(CircleShape)
            .background(fillColor)
            .border(width = 2.5.dp, color = borderColor, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            ToggleableState.On -> {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .fillMaxSize(0.58f)
                        .graphicsLayer { alpha = iconAlpha }
                )
            }

            ToggleableState.Indeterminate -> {
                Box(
                    modifier = Modifier
                        .width(size * 0.38f)
                        .height(3.dp)
                        .graphicsLayer { alpha = iconAlpha }
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White)
                )
            }

            ToggleableState.Off -> Unit
        }
    }
}

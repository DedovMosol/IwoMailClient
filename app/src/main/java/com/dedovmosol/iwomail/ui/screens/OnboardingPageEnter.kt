package com.dedovmosol.iwomail.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

internal object OnboardingPageEnter {
    fun isArmed(page: Int, currentPage: Int, targetPage: Int): Boolean =
        page == currentPage || page == targetPage

    fun initialProgress(animationsEnabled: Boolean, alreadyShown: Boolean): Float =
        if (animationsEnabled && !alreadyShown) 0f else 1f

    fun alpha(progress: Float): Float = 0.85f + 0.15f * progress
}

/** Prefetched pages keep their content measured and visible; entering only animates the layer. */
@Composable
internal fun Modifier.onboardingPageEnter(armed: Boolean, animationsEnabled: Boolean): Modifier {
    var shown by rememberSaveable { mutableStateOf(false) }
    val currentArmed by rememberUpdatedState(armed)
    val progress = remember { Animatable(OnboardingPageEnter.initialProgress(animationsEnabled, shown)) }
    // Losing targetPage after a cancelled swipe must not stop the entrance part-way through.
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled || shown) {
            shown = true
            progress.snapTo(1f)
        } else {
            snapshotFlow { currentArmed }.first { it }
            shown = true
            progress.animateTo(1f, tween(220))
        }
    }
    return if (!animationsEnabled) this else graphicsLayer {
        alpha = OnboardingPageEnter.alpha(progress.value)
        translationY = 10.dp.toPx() * (1f - progress.value)
    }
}

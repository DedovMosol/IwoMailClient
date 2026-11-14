package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Появление слайда онбординга.
 *
 * HorizontalPager с beyondBounds=1 заранее композит соседа. Если enter стартует на первой
 * композиции, «Органайзер» и «Настройки» доигрывают за экраном — на свайпе уже всё на месте.
 *
 * Прятать соседа до currentPage нельзя: во время свайпа превью пустое.
 * Пауза перед enter тоже нельзя: первые 100 мс жеста сосед пустой.
 *
 * Enter стартует, когда слайд текущий **или** цель жеста ([androidx.compose.foundation.pager.PagerState.targetPage]).
 * Уже показанный слайд не прячем: возврат назад не вспыхивает.
 */
internal object OnboardingPageEnter {

    fun isArmed(page: Int, currentPage: Int, targetPage: Int): Boolean =
        page == currentPage || page == targetPage

    enum class Content { Hidden, Show, Animate }

    fun content(armed: Boolean, animationsEnabled: Boolean, alreadyShown: Boolean): Content {
        if (alreadyShown) return Content.Show
        if (!animationsEnabled) return Content.Show
        if (!armed) return Content.Hidden
        return Content.Animate
    }
}

@Composable
internal fun rememberOnboardingVisible(armed: Boolean, animationsEnabled: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    val phase = OnboardingPageEnter.content(armed, animationsEnabled, shown)
    LaunchedEffect(armed, animationsEnabled) {
        when (OnboardingPageEnter.content(armed, animationsEnabled, shown)) {
            OnboardingPageEnter.Content.Show -> shown = true
            OnboardingPageEnter.Content.Hidden -> Unit
            OnboardingPageEnter.Content.Animate -> shown = true
        }
    }
    return when (phase) {
        OnboardingPageEnter.Content.Hidden -> false
        OnboardingPageEnter.Content.Show -> true
        OnboardingPageEnter.Content.Animate -> shown
    }
}

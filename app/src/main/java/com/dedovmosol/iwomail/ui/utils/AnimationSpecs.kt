package com.dedovmosol.iwomail.ui.utils

import androidx.compose.animation.core.*

/**
 * Утилиты для стандартных спецификаций анимаций
 * Устраняет дублирование кода анимаций по всему приложению
 */
object AnimationSpecs {
    
    /**
     * Пульсирующая анимация (увеличение-уменьшение)
     * Используется для привлечения внимания к элементам
     */
    fun pulse(durationMs: Int = 1000): InfiniteRepeatableSpec<Float> {
        return infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    }
    
    /**
     * Анимация вращения (0° → 360°)
     * Используется для индикаторов загрузки и иконок
     */
    fun rotation(durationMs: Int = 1000): InfiniteRepeatableSpec<Float> {
        return infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    }
    
    /**
     * Анимация тряски (влево-вправо)
     * Используется для привлечения внимания или индикации ошибки
     */
    fun shake(durationMs: Int = 300): InfiniteRepeatableSpec<Float> {
        return infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    }
}

package com.dedovmosol.iwomail.ui.utils

import androidx.compose.animation.core.*
import androidx.compose.runtime.*

/**
 * Composable хелперы для стандартных анимаций
 * Устраняет дублирование кода анимаций по всему приложению
 */

/**
 * Пульсирующая анимация масштаба
 * @param enabled - включены ли анимации
 * @param from - начальное значение масштаба
 * @param to - конечное значение масштаба
 * @param durationMs - длительность анимации в миллисекундах
 * @return Float - текущее значение масштаба
 */
@Composable
fun rememberPulseScale(
    enabled: Boolean,
    from: Float = 1f,
    to: Float = 1.1f,
    durationMs: Int = 1000
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val animated by infiniteTransition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = AnimationSpecs.pulse(durationMs),
        label = "scale"
    )
    return if (enabled) animated else from
}

/**
 * Анимация вращения (0° → 360°)
 * @param enabled - включены ли анимации
 * @param durationMs - длительность одного оборота в миллисекундах
 * @return Float - текущий угол вращения в градусах
 */
@Composable
fun rememberRotation(
    enabled: Boolean,
    durationMs: Int = 1000
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val animated by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = AnimationSpecs.rotation(durationMs),
        label = "rotation"
    )
    return if (enabled) animated else 0f
}

/**
 * Анимация тряски (влево-вправо) с линейным easing
 * @param enabled - включены ли анимации
 * @param amplitude - амплитуда тряски (максимальное отклонение)
 * @param durationMs - длительность одного цикла в миллисекундах
 * @return Float - текущее смещение
 */
@Composable
fun rememberShake(
    enabled: Boolean,
    amplitude: Float = 10f,
    durationMs: Int = 300
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "shake")
    val animated by infiniteTransition.animateFloat(
        initialValue = -amplitude,
        targetValue = amplitude,
        animationSpec = AnimationSpecs.shake(durationMs),
        label = "shake"
    )
    return if (enabled) animated else 0f
}

/**
 * Анимация покачивания (влево-вправо) с плавным easing
 * Отличается от shake использованием FastOutSlowInEasing вместо LinearEasing
 * @param enabled - включены ли анимации
 * @param amplitude - амплитуда покачивания
 * @param durationMs - длительность одного цикла в миллисекундах
 * @return Float - текущее смещение
 */
@Composable
fun rememberWobble(
    enabled: Boolean,
    amplitude: Float = 8f,
    durationMs: Int = 600
): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "wobble")
    val animated by infiniteTransition.animateFloat(
        initialValue = -amplitude,
        targetValue = amplitude,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wobble"
    )
    return if (enabled) animated else 0f
}

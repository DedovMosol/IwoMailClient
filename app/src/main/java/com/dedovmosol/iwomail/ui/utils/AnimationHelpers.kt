package com.dedovmosol.iwomail.ui.utils

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Composable хелперы для стандартных анимаций
 * Устраняет дублирование кода анимаций по всему приложению
 *
 * ONB-19: хелперы возвращают [State], а не Float. Значение бесконечной анимации, прочитанное в композиции,
 * перекомпоновывало бы весь вызывающий экран на каждом кадре (онбординг, диалоги, индикатор обновления).
 * Читать `.value` нужно в фазе отрисовки — через [animatedScale] / [animatedRotation] (graphicsLayer с лямбдой):
 * тогда на кадр перерисовывается только слой.
 */

/**
 * Пульсирующая анимация масштаба
 * @param enabled - включены ли анимации
 * @param from - начальное значение масштаба
 * @param to - конечное значение масштаба
 * @param durationMs - длительность анимации в миллисекундах
 * @return текущее значение масштаба; без анимаций — [from]
 */
@Composable
fun rememberPulseScale(
    enabled: Boolean,
    from: Float = 1f,
    to: Float = 1.1f,
    durationMs: Int = 1000
): State<Float> {
    if (!enabled) return rememberUpdatedState(from)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    return infiniteTransition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = AnimationSpecs.pulse(durationMs),
        label = "scale"
    )
}

/**
 * Анимация вращения (0° → 360°)
 * @param enabled - включены ли анимации
 * @param durationMs - длительность одного оборота в миллисекундах
 * @return текущий угол вращения в градусах; без анимаций — 0
 */
@Composable
fun rememberRotation(
    enabled: Boolean,
    durationMs: Int = 1000
): State<Float> {
    if (!enabled) return rememberUpdatedState(0f)

    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = AnimationSpecs.rotation(durationMs),
        label = "rotation"
    )
}

/**
 * Анимация тряски (влево-вправо) с линейным easing
 * @param enabled - включены ли анимации
 * @param amplitude - амплитуда тряски (максимальное отклонение)
 * @param durationMs - длительность одного цикла в миллисекундах
 * @return текущее смещение; без анимаций — 0
 */
@Composable
fun rememberShake(
    enabled: Boolean,
    amplitude: Float = 10f,
    durationMs: Int = 300
): State<Float> {
    if (!enabled) return rememberUpdatedState(0f)

    val infiniteTransition = rememberInfiniteTransition(label = "shake")
    return infiniteTransition.animateFloat(
        initialValue = -amplitude,
        targetValue = amplitude,
        animationSpec = AnimationSpecs.shake(durationMs),
        label = "shake"
    )
}

/**
 * Анимация покачивания (влево-вправо) с плавным easing
 * Отличается от shake использованием FastOutSlowInEasing вместо LinearEasing
 * @param enabled - включены ли анимации
 * @param amplitude - амплитуда покачивания
 * @param durationMs - длительность одного цикла в миллисекундах
 * @return текущее смещение; без анимаций — 0
 */
@Composable
fun rememberWobble(
    enabled: Boolean,
    amplitude: Float = 8f,
    durationMs: Int = 600
): State<Float> {
    if (!enabled) return rememberUpdatedState(0f)

    val infiniteTransition = rememberInfiniteTransition(label = "wobble")
    return infiniteTransition.animateFloat(
        initialValue = -amplitude,
        targetValue = amplitude,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wobble"
    )
}

/** Масштаб из анимации — читается при отрисовке слоя, без перекомпоновки на кадр (ONB-19). */
fun Modifier.animatedScale(scale: State<Float>): Modifier = graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
}

/** Поворот из анимации (градусы) — читается при отрисовке слоя, без перекомпоновки на кадр (ONB-19). */
fun Modifier.animatedRotation(degrees: State<Float>): Modifier = graphicsLayer { rotationZ = degrees.value }

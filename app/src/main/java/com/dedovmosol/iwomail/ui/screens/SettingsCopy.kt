package com.dedovmosol.iwomail.ui.screens

import kotlin.math.pow

/**
 * Подпись строки «Аккаунты» на корне настроек.
 * Один ящик — его адрес, несколько — «N аккаунтов», пусто — «Добавить аккаунт».
 */
internal fun settingsAccountsSubtitle(
    count: Int,
    firstEmail: String,
    emptyLabel: String,
    manyLabel: String
): String = when {
    count <= 0 -> emptyLabel
    count == 1 -> firstEmail
    else -> manyLabel
}

/** Чёрный или белый глиф на цветном кружке ящика (относительная яркость sRGB). */
internal fun contrastingContentArgb(backgroundArgb: Int): Int {
    val opaque = if ((backgroundArgb ushr 24) == 0) backgroundArgb or 0xFF000000.toInt() else backgroundArgb
    val r = ((opaque shr 16) and 0xFF) / 255f
    val g = ((opaque shr 8) and 0xFF) / 255f
    val b = (opaque and 0xFF) / 255f
    fun lin(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    val lum = 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b)
    return if (lum > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}

package com.dedovmosol.iwomail.ui.screens

import kotlin.math.pow

/**
 * Подпись строки «Аккаунты» на корне настроек.
 * Один ящик — его адрес, несколько — «N аккаунтов», пусто — «Добавить аккаунт».
 */
internal fun settingsAccountsSubtitle(
    emails: List<String>,
    emptyLabel: String,
    countLabel: String
): String = when {
    emails.isEmpty() -> emptyLabel
    emails.size == 1 -> emails.first()
    else -> countLabel
}

/** Чёрный или белый глиф на цветном кружке ящика (WCAG-ish luminance). */
internal fun contrastingContentArgb(backgroundArgb: Int): Int {
    val r = ((backgroundArgb shr 16) and 0xFF) / 255f
    val g = ((backgroundArgb shr 8) and 0xFF) / 255f
    val b = (backgroundArgb and 0xFF) / 255f
    fun lin(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    val lum = 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b)
    return if (lum > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}

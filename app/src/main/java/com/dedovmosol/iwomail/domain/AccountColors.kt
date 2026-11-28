package com.dedovmosol.iwomail.domain

/**
 * Цвета аккаунтов (DRY: выбор при добавлении, боковое меню, виджет). Палитра — та, из которой пользователь выбирает
 * цвет в настройке аккаунта.
 */
object AccountColors {
    val PALETTE: List<Int> = listOf(
        0xFF1976D2.toInt(), // Blue
        0xFF388E3C.toInt(), // Green
        0xFFD32F2F.toInt(), // Red
        0xFF7B1FA2.toInt(), // Purple
        0xFFF57C00.toInt(), // Orange
        0xFF0097A7.toInt(), // Cyan
        0xFF5D4037.toInt(), // Brown
        0xFF455A64.toInt(), // Blue Grey
    )

    /** Цвет нового аккаунта: первый из палитры, которого ещё нет у аккаунтов; все заняты — по кругу. */
    fun forNewAccount(used: Collection<Int>): Int =
        PALETTE.firstOrNull { it !in used } ?: PALETTE[used.size % PALETTE.size]

    /**
     * Цвета для показа: у аккаунтов с одинаковым цветом (раньше новый аккаунт всегда получал синий) первый
     * сохраняет свой, следующие получают свободный цвет палитры — аватары различимы. Сохранённый цвет не меняется;
     * палитра кончилась — цвет как есть.
     */
    fun distinct(colors: List<Int>): List<Int> {
        val taken = HashSet<Int>()
        val firstOwner = HashSet<Int>()
        // Сначала занимаем все различные исходные цвета, чтобы замена не совпала с чужим собственным цветом
        colors.forEach { taken += it }
        return colors.map { color ->
            if (firstOwner.add(color)) color
            else PALETTE.firstOrNull { it !in taken }?.also { taken += it } ?: color
        }
    }
}

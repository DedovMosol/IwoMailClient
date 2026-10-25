package com.dedovmosol.iwomail.util

import java.util.Locale

/**
 * Палитра цветовых категорий Outlook — [MS-OXOCFG] 2.2.5.2.2, атрибут `color`: индексы -1..24,
 * «базовые» RGB из спецификации. Значение вне диапазона MUST трактоваться как -1 (без цвета).
 *
 * Здесь только данные и правила подбора; подписи цветов — в Strings (локализация), хранение и
 * приоритет источников — в CategoryColorRepository.
 */
object OutlookCategoryPalette {
    const val NONE = -1
    const val MAX = 24

    /** Все допустимые индексы, включая «без цвета», в порядке палитры Outlook. */
    val ALL: List<Int> = (NONE..MAX).toList()

    private val RGB = intArrayOf(
        rgb(214, 37, 46),   // 0  Red
        rgb(240, 108, 21),  // 1  Orange
        rgb(255, 202, 76),  // 2  Peach
        rgb(255, 254, 61),  // 3  Yellow
        rgb(74, 182, 63),   // 4  Green
        rgb(64, 189, 149),  // 5  Teal
        rgb(133, 154, 82),  // 6  Olive
        rgb(50, 103, 184),  // 7  Blue
        rgb(97, 61, 180),   // 8  Purple
        rgb(163, 78, 120),  // 9  Maroon
        rgb(196, 204, 221), // 10 Steel
        rgb(140, 156, 189), // 11 Dark steel
        rgb(196, 196, 196), // 12 Gray
        rgb(165, 165, 165), // 13 Dark gray
        rgb(28, 28, 28),    // 14 Black
        rgb(175, 30, 37),   // 15 Dark red
        rgb(177, 79, 13),   // 16 Dark orange
        rgb(171, 123, 5),   // 17 Dark peach
        rgb(153, 148, 0),   // 18 Dark yellow
        rgb(53, 121, 43),   // 19 Dark green
        rgb(46, 125, 100),  // 20 Dark teal
        rgb(95, 108, 58),   // 21 Dark olive
        rgb(42, 81, 145),   // 22 Dark blue
        rgb(80, 50, 143),   // 23 Dark purple
        rgb(130, 55, 95)    // 24 Dark maroon
    )

    /**
     * Цвета для автоподбора: яркие и тёмные оттенки без серых/стальных/чёрного (10..14) — у
     * «случайной» категории цвет должен различаться, а не сливаться с фоном.
     */
    private val AUTO = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24)

    /**
     * Стандартные категории Outlook 2007–2013 (EN/RU интерфейс). Exchange 2007 не отдаёт список
     * категорий клиенту, но имена по умолчанию однозначно задают цвет (пример MCL в [MS-OXOCFG], Protocol Examples:
     * Red=0, Orange=1, Yellow=3, Green=4, Blue=7, Purple=8).
     */
    private val PRESETS: Map<String, Int> = mapOf(
        "red category" to 0, "orange category" to 1, "yellow category" to 3,
        "green category" to 4, "blue category" to 7, "purple category" to 8,
        "красная категория" to 0, "оранжевая категория" to 1, "желтая категория" to 3,
        "зеленая категория" to 4, "синяя категория" to 7, "фиолетовая категория" to 8
    )

    private val SPACES = Regex("\\s+")

    /** Индекс из XML/БД в допустимый: вне -1..24 → [NONE] ([MS-OXOCFG] 2.2.5.2.2). */
    fun normalize(color: Int?): Int = if (color != null && color in 0..MAX) color else NONE

    /** ARGB цвета палитры; null — «без цвета». */
    fun argb(color: Int): Int? = if (color in 0..MAX) RGB[color] else null

    /** Цвет стандартной категории Outlook по имени (без учёта регистра, «ё» = «е»); null — не стандартная. */
    fun presetColorFor(name: String): Int? = PRESETS[matchKey(name)]

    /** Детерминированный цвет для категории без известного цвета: одно имя — всегда один цвет. */
    fun autoColorFor(name: String): Int = AUTO[Math.floorMod(matchKey(name).hashCode(), AUTO.size)]

    private fun matchKey(name: String): String =
        name.trim().replace(SPACES, " ").lowercase(Locale.ROOT).replace('ё', 'е')

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

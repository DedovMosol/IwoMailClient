package com.dedovmosol.iwomail.util

import java.util.Locale

/**
 * Имена категорий Outlook — единые правила сравнения и хранения (DRY для парсера MCL, БД и UI).
 *
 * [MS-OXOCFG] 2.2.5.2.2: имя категории уникально без учёта регистра, не пустое, не длиннее 255
 * символов и НЕ содержит запятую — поэтому у события категории хранятся строкой через запятую.
 */
object CalendarCategories {
    const val MAX_NAME_LENGTH = 255

    /** Ключ сравнения имён (без учёта регистра, независимо от локали устройства). */
    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    /** Категории события из CSV-колонки: порядок сохраняется, повторы (без учёта регистра) убираются. */
    fun parse(csv: String): List<String> {
        if (csv.isBlank()) return emptyList()
        return csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { key(it) }
    }

    /** Список имён → CSV-колонка события (обратная к [parse]). */
    fun toCsv(names: List<String>): String =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { key(it) }.joinToString(",")
}

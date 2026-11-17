package com.dedovmosol.iwomail.ui.utils

/**
 * Поле времени «ЧЧ:ММ», которое вводят цифрами (события календаря, задачи).
 *
 * PIM-04: раньше поле принимало любые 4 цифры («99:99»), а значок часов открывал TimePicker с initialHour = 99 —
 * Material3 проверяет `require(initialHour in 0..23)` и приложение падало. Теперь невозможная цифра не вводится,
 * а для пикера время всегда в допустимых границах.
 */
object TimeInput {

    /**
     * Цифры из [raw] → «Ч», «ЧЧ», «ЧЧ:М» или «ЧЧ:ММ» с часами 0-23 и минутами 0-59. Первая цифра 3-9 — это час
     * «0N» (набрал «9» — получил «09»). Цифра, после которой время стало бы невозможным, отбрасывается.
     */
    fun sanitize(raw: String): String {
        val out = StringBuilder(4)
        for (c in raw) {
            if (c !in '0'..'9') continue
            val d = c - '0'
            when (out.length) {
                0 -> { if (d > 2) out.append('0'); out.append(c) }
                1 -> if ((out[0] - '0') * 10 + d <= 23) out.append(c)
                2 -> if (d <= 5) out.append(c)
                3 -> out.append(c)
            }
            if (out.length == 4) break
        }
        return if (out.length <= 2) out.toString() else "${out.substring(0, 2)}:${out.substring(2)}"
    }

    /** Час и минута из текста поля для TimePicker; что не разобрать или вне границ — [defaultHour]:[defaultMinute]. */
    fun hourMinute(text: String, defaultHour: Int, defaultMinute: Int = 0): Pair<Int, Int> {
        val parts = text.split(':')
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 0..23 } ?: defaultHour.coerceIn(0, 23)
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 0..59 } ?: defaultMinute.coerceIn(0, 59)
        return hour to minute
    }
}

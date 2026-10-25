package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Юнит-тесты `CalendarDateUtils.parseEwsDateTime` (N-4) — учёт смещения таймзоны.
 *
 * `parseEwsDateTime` использует `java.text.SimpleDateFormat` (чистый JVM, без Android). Ключевая
 * проверка: смещение `±HH:MM` теперь УЧИТЫВАЕТСЯ (паттерн XXX), а не выбрасывается — иначе время
 * с ненулевым offset «съезжало» (трактовалось как UTC).
 */
class CalendarDateUtilsTest {

    @Test
    fun `parses Z as UTC`() {
        assertThat(CalendarDateUtils.parseEwsDateTime("2024-01-15T10:30:00Z")).isNotNull()
    }

    @Test
    fun `applies positive offset - regression for N-4`() {
        // 13:30 при +03:00 == 10:30 UTC. Раньше offset вырезался → 13:30 трактовалось как UTC (сдвиг).
        val withOffset = CalendarDateUtils.parseEwsDateTime("2024-01-15T13:30:00+03:00")
        val utc = CalendarDateUtils.parseEwsDateTime("2024-01-15T10:30:00Z")
        assertThat(withOffset).isEqualTo(utc)
    }

    @Test
    fun `applies negative offset`() {
        // 07:30 при -03:00 == 10:30 UTC
        val withOffset = CalendarDateUtils.parseEwsDateTime("2024-01-15T07:30:00-03:00")
        val utc = CalendarDateUtils.parseEwsDateTime("2024-01-15T10:30:00Z")
        assertThat(withOffset).isEqualTo(utc)
    }

    @Test
    fun `no timezone is treated as UTC`() {
        val noTz = CalendarDateUtils.parseEwsDateTime("2024-01-15T10:30:00")
        val utc = CalendarDateUtils.parseEwsDateTime("2024-01-15T10:30:00Z")
        assertThat(noTz).isEqualTo(utc)
    }

    @Test
    fun `strips fractional seconds`() {
        val frac = CalendarDateUtils.parseEwsDateTime("2024-01-15T10:30:00.123Z")
        val plain = CalendarDateUtils.parseEwsDateTime("2024-01-15T10:30:00Z")
        assertThat(frac).isEqualTo(plain)
    }

    @Test
    fun `null or empty returns null`() {
        assertThat(CalendarDateUtils.parseEwsDateTime(null)).isNull()
        assertThat(CalendarDateUtils.parseEwsDateTime("")).isNull()
    }

    /**
     * Регрессия локали: `localDateFromIso` строит дату для EWS `<StartDate>` (CalendarRecurrenceBuilder)
     * через `String.format`. Без `Locale.US` в локалях с не-ASCII цифрами дата уходила бы как
     * «۲۰۲۶-۱۰-۲۴» → сервер отклоняет (xs:date требует ASCII `[0-9]`). TZ фиксируем на UTC, чтобы
     * сравнение не зависело от часового пояса машины (localDateFromIso берёт дату в локальной TZ).
     */
    @Test
    fun `localDateFromIso stays ASCII and locale-invariant`() {
        val prevLocale = Locale.getDefault()
        val prevTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.forLanguageTag("en-US"))
            val en = CalendarDateUtils.localDateFromIso("2026-10-24T00:00:00Z")
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            val fa = CalendarDateUtils.localDateFromIso("2026-10-24T00:00:00Z")
            assertThat(fa).isEqualTo(en)
            assertThat(fa.all { it.code < 128 }).isTrue()
            assertThat(fa).matches("\\d{4}-\\d{2}-\\d{2}")
        } finally {
            Locale.setDefault(prevLocale)
            TimeZone.setDefault(prevTz)
        }
    }
}

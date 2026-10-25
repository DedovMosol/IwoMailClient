package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Формат тела события: [MS-ASAIRS] Type 1 — plain text, 2 — HTML (для календаря только эти два),
 * EWS BodyType Text/HTML. HTML с сервера хранится как есть, текст — с дедупликацией строк Outlook.
 */
class CalendarBodyFormatTest {

    private val html = "<html><body><div>Line</div><br><div>Line</div><img src=\"cid:a\"></body></html>"

    @Test
    fun `html body keeps markup, repeated lines and cid references`() {
        assertThat(CalendarBodyFormat.normalizeIncoming("  $html \n")).isEqualTo(html)
    }

    @Test
    fun `plain body is deduplicated as before`() {
        assertThat(CalendarBodyFormat.normalizeIncoming("A\nB\nA\n\nC")).isEqualTo("A\nB\n\nC")
    }

    @Test
    fun `wire body type follows content`() {
        assertThat(CalendarBodyFormat.easType(html)).isEqualTo(2)
        assertThat(CalendarBodyFormat.ewsType(html)).isEqualTo("HTML")
        assertThat(CalendarBodyFormat.easType("just text")).isEqualTo(1)
        assertThat(CalendarBodyFormat.ewsType("just text")).isEqualTo("Text")
    }
}

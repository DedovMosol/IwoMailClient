package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CalendarRepository.attendeeEmailsCsv] — префилл поля участников в диалоге редактирования.
 * Robolectric нужен для org.json. Пустое поле на правке встречи демотировало бы её на EAS 14+
 * (MeetingStatus=0) и теряло участников, поэтому корректный префилл критичен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarAttendeeCsvTest {

    @Test
    fun `joins attendee emails in order`() {
        val json = """[{"email":"a@corp.local","name":"A","status":3},{"email":"b@corp.local","name":"B","status":0}]"""
        assertThat(CalendarRepository.attendeeEmailsCsv(json)).isEqualTo("a@corp.local, b@corp.local")
    }

    @Test
    fun `blank and malformed json yield empty`() {
        assertThat(CalendarRepository.attendeeEmailsCsv("")).isEmpty()
        assertThat(CalendarRepository.attendeeEmailsCsv("   ")).isEmpty()
        assertThat(CalendarRepository.attendeeEmailsCsv("not json")).isEmpty()
        assertThat(CalendarRepository.attendeeEmailsCsv("[]")).isEmpty()
    }

    @Test
    fun `skips entries with blank or missing email, trims`() {
        val json = """[{"email":" a@corp.local "},{"name":"no email"},{"email":""},{"email":"b@corp.local"}]"""
        assertThat(CalendarRepository.attendeeEmailsCsv(json)).isEqualTo("a@corp.local, b@corp.local")
    }

    @Test
    fun `round-trips what the edit dialog later splits back`() {
        // Диалог сохраняет строку через split(",",";").map{trim}.filter{contains("@")} (CalendarScreen).
        val json = """[{"email":"x@corp.local"},{"email":"y@corp.local"}]"""
        val csv = CalendarRepository.attendeeEmailsCsv(json)
        val reparsed = csv.split(",", ";").map { it.trim() }.filter { it.contains("@") }
        assertThat(reparsed).containsExactly("x@corp.local", "y@corp.local").inOrder()
    }
}

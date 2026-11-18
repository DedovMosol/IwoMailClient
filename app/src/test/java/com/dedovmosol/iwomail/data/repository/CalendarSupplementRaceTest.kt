package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PIM-28: EWS-дополнение исключений считается от снимка до сетевого запроса. Правка вхождения пользователем за это
 * время не должна затираться: запись только при неизменных исключениях.
 */
class CalendarSupplementRaceTest {

    private val snapshot = """[{"exceptionStartTime":100,"deleted":false,"subject":"old"}]"""
    private val supplemented = """[{"exceptionStartTime":100,"deleted":false,"subject":"from server"}]"""
    private val userEdit = """[{"exceptionStartTime":100,"deleted":false,"subject":"user edit"}]"""

    @Test
    fun `unchanged row gets the supplement`() {
        assertThat(CalendarRepository.supplementToWrite(snapshot, snapshot, supplemented)).isEqualTo(supplemented)
    }

    @Test
    fun `user edit between snapshot and write is kept`() {
        assertThat(CalendarRepository.supplementToWrite(userEdit, snapshot, supplemented)).isNull()
    }

    @Test
    fun `occurrence deleted by the user in between is kept`() {
        val deleted = """[{"exceptionStartTime":100,"deleted":true}]"""
        assertThat(CalendarRepository.supplementToWrite(deleted, snapshot, supplemented)).isNull()
    }

    @Test
    fun `nothing to write when the supplement is missing, has no snapshot or changes nothing`() {
        assertThat(CalendarRepository.supplementToWrite(snapshot, snapshot, null)).isNull()
        assertThat(CalendarRepository.supplementToWrite(snapshot, null, supplemented)).isNull()
        assertThat(CalendarRepository.supplementToWrite(supplemented, supplemented, supplemented)).isNull()
    }

    @Test
    fun `series without exceptions in the snapshot still gets the first exception`() {
        assertThat(CalendarRepository.supplementToWrite("", "", supplemented)).isEqualTo(supplemented)
    }
}

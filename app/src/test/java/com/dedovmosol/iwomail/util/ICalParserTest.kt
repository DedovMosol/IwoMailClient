package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Юнит-тесты для ICalParser (RFC 5545 / Exchange 2007 SP1 приглашения).
 * TimeZone закреплён в UTC для детерминированности дат без TZID.
 */
class ICalParserTest {

    private lateinit var originalTz: TimeZone

    @Before
    fun setUp() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTz)
    }

    private fun utcMillis(value: String): Long {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.parse(value)!!.time
    }

    // ===================== parseICalDate =====================

    @Test
    fun `parseICalDate returns null for null or blank`() {
        assertThat(ICalParser.parseICalDate(null)).isNull()
        assertThat(ICalParser.parseICalDate("")).isNull()
    }

    @Test
    fun `parseICalDate parses UTC datetime`() {
        assertThat(ICalParser.parseICalDate("20260115T100000Z"))
            .isEqualTo(utcMillis("20260115T100000"))
    }

    @Test
    fun `parseICalDate parses all-day date in default timezone`() {
        val expected = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("20260115")!!.time
        assertThat(ICalParser.parseICalDate("20260115")).isEqualTo(expected)
    }

    @Test
    fun `parseICalDate honors explicit TZID`() {
        assertThat(ICalParser.parseICalDate("20260115T100000", "UTC"))
            .isEqualTo(utcMillis("20260115T100000"))
    }

    @Test
    fun `parseICalDate returns null for invalid length`() {
        assertThat(ICalParser.parseICalDate("2026")).isNull()
        assertThat(ICalParser.parseICalDate("20260115T1000")).isNull()
    }

    // ===================== parseMeetingFromIcal =====================

    @Test
    fun `parseMeetingFromIcal returns null for non-calendar content`() {
        assertThat(ICalParser.parseMeetingFromIcal("just a plain email body")).isNull()
    }

    @Test
    fun `parseMeetingFromIcal extracts all fields`() {
        val ical = """
            BEGIN:VCALENDAR
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:abc-123
            SUMMARY:Team Sync
            DTSTART:20260115T100000Z
            DTEND:20260115T110000Z
            LOCATION:Room 1
            ORGANIZER:mailto:boss@x.com
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val info = ICalParser.parseMeetingFromIcal(ical)

        assertThat(info).isNotNull()
        assertThat(info!!.summary).isEqualTo("Team Sync")
        assertThat(info.location).isEqualTo("Room 1")
        assertThat(info.organizer).isEqualTo("boss@x.com")
        assertThat(info.method).isEqualTo("REQUEST")
        assertThat(info.uid).isEqualTo("abc-123")
        assertThat(info.dtStart).isEqualTo(utcMillis("20260115T100000"))
        assertThat(info.dtEnd).isEqualTo(utcMillis("20260115T110000"))
    }

    // ===================== parseTaskFromEmailBody =====================

    @Test
    fun `parseTaskFromEmailBody returns null for non-task subject`() {
        assertThat(ICalParser.parseTaskFromEmailBody("Hello", "body")).isNull()
    }

    @Test
    fun `parseTaskFromEmailBody extracts title and description with fallback due date`() {
        val before = System.currentTimeMillis()
        val info = ICalParser.parseTaskFromEmailBody("Task: Buy milk", "<p>Описание: details</p>")

        assertThat(info).isNotNull()
        assertThat(info!!.subject).isEqualTo("Buy milk")
        assertThat(info.description).isEqualTo("details")
        assertThat(info.dueDate).isAtLeast(before)
    }

    @Test
    fun `parseTaskFromEmailBody parses explicit due date`() {
        val expected = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .parse("31.12.2026 23:59")!!.time
        val info = ICalParser.parseTaskFromEmailBody(
            subject = "Task: Report",
            bodyHtml = "Due date: 31.12.2026 23:59"
        )

        assertThat(info).isNotNull()
        assertThat(info!!.subject).isEqualTo("Report")
        assertThat(info.dueDate).isEqualTo(expected)
    }
}

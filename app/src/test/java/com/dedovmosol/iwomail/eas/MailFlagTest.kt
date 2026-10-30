package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Флаг письма [MS-ASEMAIL] Flag: запись в Sync Change и разбор ответа. Даты срока — локальный день; StartDate/DueDate
 * пишутся «стенными часами», Utc* — тем же моментом в UTC; все четыре вместе или ни одной.
 */
class MailFlagTest {

    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private val utc = TimeZone.getTimeZone("UTC")

    private fun local(text: String, zone: TimeZone = moscow): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.parse(text)!!.time

    private val now = local("2026-10-29 15:30")

    @Test
    fun `active flag with dates writes all four dates and the reminder`() {
        val flag = MailFlag.due(local("2026-10-29 00:00"), local("2026-10-30 00:00"), local("2026-10-30 09:00"))

        assertThat(MailFlags.toXml(flag, now, moscow)).isEqualTo(
            "<Flag xmlns=\"Email\"><FlagStatus>2</FlagStatus><FlagType>for Follow Up</FlagType>" +
                "<tasks:StartDate>2026-10-29T00:00:00.000Z</tasks:StartDate>" +
                "<tasks:DueDate>2026-10-30T00:00:00.000Z</tasks:DueDate>" +
                "<tasks:UtcStartDate>2026-10-28T21:00:00.000Z</tasks:UtcStartDate>" +
                "<tasks:UtcDueDate>2026-10-29T21:00:00.000Z</tasks:UtcDueDate>" +
                "<tasks:ReminderSet>1</tasks:ReminderSet>" +
                "<tasks:ReminderTime>2026-10-30T06:00:00.000Z</tasks:ReminderTime></Flag>"
        )
    }

    @Test
    fun `active flag without dates writes no dates at all`() {
        val xml = MailFlags.toXml(MailFlag(MailFlag.STATUS_ACTIVE), now, moscow)
        assertThat(xml).isEqualTo(
            "<Flag xmlns=\"Email\"><FlagStatus>2</FlagStatus><FlagType>for Follow Up</FlagType>" +
                "<tasks:ReminderSet>0</tasks:ReminderSet></Flag>"
        )
    }

    @Test
    fun `start never after due`() {
        val xml = MailFlags.toXml(MailFlag(MailFlag.STATUS_ACTIVE, local("2026-11-05 00:00"), local("2026-11-02 00:00")), now, moscow)
        assertThat(xml).contains("<tasks:StartDate>2026-11-02T00:00:00.000Z</tasks:StartDate>")
    }

    @Test
    fun `complete carries completion times, cleared is status 0`() {
        val complete = MailFlags.toXml(MailFlag(MailFlag.STATUS_COMPLETE), now, moscow)
        assertThat(complete).contains("<FlagStatus>1</FlagStatus>")
        assertThat(complete).contains("<tasks:DateCompleted>2026-10-29T12:30:00.000Z</tasks:DateCompleted>")
        assertThat(complete).contains("<CompleteTime>2026-10-29T12:30:00.000Z</CompleteTime>")
        assertThat(complete).doesNotContain("DueDate")

        assertThat(MailFlags.toXml(MailFlag.NONE, now, moscow)).isEqualTo("<Flag xmlns=\"Email\"><FlagStatus>0</FlagStatus></Flag>")
    }

    @Test
    fun `parse takes the local day from the wall-clock date`() {
        val flagXml = "<FlagStatus>2</FlagStatus><FlagType>Follow up</FlagType>" +
            "<tasks:StartDate>2026-10-29T00:00:00.000Z</tasks:StartDate><tasks:DueDate>2026-10-30T00:00:00.000Z</tasks:DueDate>" +
            // Ящик в другом поясе: Utc-значения не совпадают с полуночью Москвы — день берётся по DueDate
            "<tasks:UtcStartDate>2026-10-29T07:00:00.000Z</tasks:UtcStartDate><tasks:UtcDueDate>2026-10-30T07:00:00.000Z</tasks:UtcDueDate>" +
            "<tasks:ReminderSet>1</tasks:ReminderSet><tasks:ReminderTime>2026-10-30T06:00:00.000Z</tasks:ReminderTime>"

        assertThat(MailFlags.parse(flagXml, moscow)).isEqualTo(
            MailFlag(MailFlag.STATUS_ACTIVE, local("2026-10-29 00:00"), local("2026-10-30 00:00"), local("2026-10-30 09:00"))
        )
    }

    @Test
    fun `parse falls back to the utc date in the device zone`() {
        val flagXml = "<FlagStatus>2</FlagStatus><UtcDueDate>2026-10-29T21:00:00.000Z</UtcDueDate>"
        assertThat(MailFlags.parse(flagXml, moscow).dueDate).isEqualTo(local("2026-10-30 00:00"))
    }

    @Test
    fun `reminder is ignored when not set`() {
        val flagXml = "<FlagStatus>2</FlagStatus><ReminderSet>0</ReminderSet><ReminderTime>2026-10-30T06:00:00.000Z</ReminderTime>"
        assertThat(MailFlags.parse(flagXml, moscow).reminderTime).isNull()
    }

    @Test
    fun `complete and cleared flags carry no dates`() {
        assertThat(MailFlags.parse("<FlagStatus>1</FlagStatus><CompleteTime>2026-10-29T12:30:00.000Z</CompleteTime>" +
            "<tasks:DueDate>2026-10-30T00:00:00.000Z</tasks:DueDate>", moscow)).isEqualTo(MailFlag(MailFlag.STATUS_COMPLETE))
        assertThat(MailFlags.parse("<FlagStatus>0</FlagStatus>", moscow)).isEqualTo(MailFlag.NONE)
        assertThat(MailFlags.parse("", moscow)).isEqualTo(MailFlag.NONE)
        assertThat(MailFlags.parse(null, moscow)).isEqualTo(MailFlag.NONE)
    }

    @Test
    fun `written flag survives WBXML with the real code pages and reads back`() {
        val flag = MailFlag.due(local("2026-10-29 00:00"), local("2026-11-06 00:00"), local("2026-11-02 10:00"))
        val sync = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Sync xmlns=\"AirSync\" xmlns:tasks=\"Tasks\"><Collections>" +
            "<Collection><SyncKey>1</SyncKey><CollectionId>5</CollectionId><Commands><Change><ServerId>5:1</ServerId>" +
            "<ApplicationData>${MailFlags.toXml(flag, now, moscow)}</ApplicationData></Change></Commands></Collection>" +
            "</Collections></Sync>"
        val parser = WbxmlParser()

        val decoded = parser.parse(parser.generate(sync))

        val flagXml = XmlUtils.extractTagValue(decoded, "Flag")
        assertThat(MailFlags.parse(flagXml, moscow)).isEqualTo(flag)
        // Даты — на странице Tasks (9), статус и тип — на Email (2)
        assertThat(decoded).contains("for Follow Up")
    }

    @Test
    fun `utc helper is independent of the device zone`() {
        val flag = MailFlag.due(local("2026-10-30 00:00", utc), local("2026-10-30 00:00", utc))
        assertThat(MailFlags.toXml(flag, now, utc)).contains("<tasks:UtcDueDate>2026-10-30T00:00:00.000Z</tasks:UtcDueDate>")
    }
}

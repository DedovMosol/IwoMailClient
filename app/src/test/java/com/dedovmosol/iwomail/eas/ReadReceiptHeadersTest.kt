package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadReceiptHeadersTest {

    private fun headers(vararg lines: String) = lines.joinToString("\r\n", postfix = "\r\n")

    @Test
    fun `Disposition-Notification-To with a display name`() {
        val h = headers("From: Boss <boss@corp.example>", "Disposition-Notification-To: \"Doe, John\" <john@corp.example>")
        assertThat(readReceiptRequestAddress(h)).isEqualTo("john@corp.example")
    }

    @Test
    fun `bare address and case-insensitive header name`() {
        assertThat(readReceiptRequestAddress(headers("disposition-notification-to: a@b.cd"))).isEqualTo("a@b.cd")
    }

    @Test
    fun `folded header is unfolded`() {
        val h = headers("Disposition-Notification-To: \"Very Long Name\"", "\t<long@corp.example>", "Subject: S")
        assertThat(readReceiptRequestAddress(h)).isEqualTo("long@corp.example")
    }

    @Test
    fun `first address of a list`() {
        assertThat(readReceiptRequestAddress(headers("Disposition-Notification-To: a@b.cd, c@d.ef"))).isEqualTo("a@b.cd")
    }

    @Test
    fun `legacy X-Confirm-Reading-To`() {
        assertThat(readReceiptRequestAddress(headers("X-Confirm-Reading-To: <old@corp.example>"))).isEqualTo("old@corp.example")
    }

    @Test
    fun `Return-Receipt-To asks for a delivery report, not a read receipt`() {
        assertThat(readReceiptRequestAddress(headers("Return-Receipt-To: <dr@corp.example>"))).isNull()
    }

    @Test
    fun `no request, empty or address-less value`() {
        assertThat(readReceiptRequestAddress(headers("Subject: S"))).isNull()
        assertThat(readReceiptRequestAddress(headers("Disposition-Notification-To:"))).isNull()
        assertThat(readReceiptRequestAddress(headers("Disposition-Notification-To: nobody"))).isNull()
    }

    @Test
    fun `LF-only line endings`() {
        assertThat(readReceiptRequestAddress("Subject: S\nDisposition-Notification-To: <a@b.cd>\n")).isEqualTo("a@b.cd")
    }
}

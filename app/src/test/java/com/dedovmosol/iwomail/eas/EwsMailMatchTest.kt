package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EwsMailMatchTest {

    /** FieldURI, которые Exchange 2007 SP1 принимает в GetItem (проверены на сервере, output_EasProbe_fielduris/ewsmid). */
    private val validOn2007 = setOf(
        "message:InternetMessageId", "item:InReplyTo", "item:Subject", "item:DateTimeReceived", "item:DateTimeSent"
    )

    @Test
    fun `body GetItem asks only for FieldURIs of the Exchange 2007 schema`() {
        val request = EwsMailMatch.bodyGetItemRequest("AAMk+id/1=")

        val fields = Regex("FieldURI=\"([^\"]+)\"").findAll(request).map { it.groupValues[1] }.toSet()
        // message:InReplyTo роняет весь запрос SOAP Fault'ом (HTTP 500) — раньше откат тела не работал
        assertThat(fields).doesNotContain("message:InReplyTo")
        assertThat(fields).containsExactlyElementsIn(validOn2007)
        assertThat(request).contains("<t:ItemId Id=\"AAMk+id/1=\"/>")
        assertThat(request).contains("<t:RequestServerVersion Version=\"Exchange2007_SP1\"/>")
    }

    @Test
    fun messageIdNormalizesBracketsAndCase() {
        assertThat(EwsMailMatch.sameMessageId("<A@B.com>", "a@b.com")).isTrue()
        assertThat(EwsMailMatch.sameMessageId("a@b.com", "c@d.com")).isFalse()
        assertThat(EwsMailMatch.sameMessageId(null, "a@b.com")).isFalse()
        assertThat(EwsMailMatch.messageIdOnly("  <id@x> ")).isTrue()
        assertThat(EwsMailMatch.messageIdOnly("")).isFalse()
    }

    @Test
    fun acceptBodyByMessageIdIgnoresSubject() {
        assertThat(
            EwsMailMatch.acceptBody(
                localSubject = "A",
                localDateReceived = 1L,
                localMessageId = "<id@x>",
                ewsSubject = "B",
                ewsDateMillis = 9_999_999L,
                ewsMessageId = "id@x",
                lookedUpByMessageId = true
            )
        ).isTrue()
        assertThat(
            EwsMailMatch.acceptBody(
                localSubject = "A",
                localDateReceived = 1L,
                localMessageId = "id@x",
                ewsSubject = "A",
                ewsDateMillis = 1L,
                ewsMessageId = "other@x",
                lookedUpByMessageId = true
            )
        ).isFalse()
        assertThat(
            EwsMailMatch.acceptBody(
                localSubject = "A",
                localDateReceived = 1L,
                localMessageId = "id@x",
                ewsSubject = null,
                ewsDateMillis = null,
                ewsMessageId = null,
                lookedUpByMessageId = true
            )
        ).isTrue()
    }

    @Test
    fun acceptBodyBySubjectRejectsWrongIdDateOrSubject() {
        val ok = EwsMailMatch.acceptBody(
            localSubject = "Report",
            localDateReceived = 1_000_000L,
            localMessageId = null,
            ewsSubject = "report",
            ewsDateMillis = 1_050_000L,
            ewsMessageId = null,
            lookedUpByMessageId = false
        )
        assertThat(ok).isTrue()
        assertThat(
            EwsMailMatch.acceptBody(
                "Report", 1_000_000L, null, "Other", 1_000_000L, null, false
            )
        ).isFalse()
        assertThat(
            EwsMailMatch.acceptBody(
                "Report", 1_000_000L, null, "Report", 1_000_000L + 121_000L, null, false
            )
        ).isFalse()
        assertThat(
            EwsMailMatch.acceptBody(
                "Report", 1_000_000L, "id@x", "Report", 1_000_000L, "other@x", false
            )
        ).isFalse()
    }

    @Test
    fun dateWindowsStayTight() {
        assertThat(EwsMailMatch.dateWindowsMs()).containsExactly(15_000L, 120_000L).inOrder()
    }

    @Test
    fun forEwsRestrictionAddsRfcBrackets() {
        assertThat(EwsMailMatch.forEwsRestriction("id@x")).isEqualTo("<id@x>")
        assertThat(EwsMailMatch.forEwsRestriction("<id@x>")).isEqualTo("<id@x>")
        assertThat(EwsMailMatch.messageIdRestrictionValues("id@x"))
            .containsExactly("<id@x>", "id@x").inOrder()
    }
}

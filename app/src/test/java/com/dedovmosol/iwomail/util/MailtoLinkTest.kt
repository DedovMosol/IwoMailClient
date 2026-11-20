package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Разбор mailto: по RFC 6068 (NAV-13, MAIL-10): адреса, копия, скрытая копия, тема и текст не теряются. */
class MailtoLinkTest {

    @Test
    fun `all fields of a link are kept`() {
        val link = MailtoLink.parse(
            "mailto:a@corp.example,b@corp.example?cc=c@corp.example&bcc=d@corp.example" +
                "&subject=%D0%9E%D1%82%D1%87%D1%91%D1%82%20Q3&body=Line%201%0D%0ALine%202"
        )!!

        assertThat(link.to).containsExactly("a@corp.example", "b@corp.example").inOrder()
        assertThat(link.cc).containsExactly("c@corp.example")
        assertThat(link.bcc).containsExactly("d@corp.example")
        assertThat(link.subject).isEqualTo("Отчёт Q3")
        assertThat(link.body).isEqualTo("Line 1\nLine 2")
    }

    @Test
    fun `plus is a character, not a space`() {
        val link = MailtoLink.parse("mailto:user+tag@corp.example?subject=1+1")!!

        assertThat(link.to).containsExactly("user+tag@corp.example")
        assertThat(link.subject).isEqualTo("1+1")
    }

    @Test
    fun `to field adds recipients, names are case-insensitive, duplicates collapse`() {
        val link = MailtoLink.parse("MAILTO:?To=a@corp.example,%20b@corp.example&TO=a@corp.example&CC=c@corp.example")!!

        assertThat(link.to).containsExactly("a@corp.example", "b@corp.example").inOrder()
        assertThat(link.cc).containsExactly("c@corp.example")
    }

    @Test
    fun `the first subject wins and a subject is one line`() {
        val link = MailtoLink.parse("mailto:a@corp.example?subject=Hi%0D%0ABcc:%20x@evil.example&subject=Second")!!

        assertThat(link.subject).isEqualTo("Hi Bcc: x@evil.example")
    }

    @Test
    fun `an empty link opens an empty message, other schemes are not mailto`() {
        assertThat(MailtoLink.parse("mailto:")).isEqualTo(MailtoLink(emptyList()))
        assertThat(MailtoLink.parse("https://corp.example")).isNull()
        assertThat(MailtoLink.parse(null)).isNull()
    }

    @Test
    fun `broken percent encoding keeps the raw text instead of failing`() {
        val link = MailtoLink.parse("mailto:a@corp.example?subject=100%&body=%ZZ")!!

        assertThat(link.subject).isEqualTo("100%")
        assertThat(link.body).isEqualTo("%ZZ")
    }

    @Test
    fun `plain text becomes escaped html with line breaks`() {
        assertThat(plainTextToHtml("a < b & \"c\"\r\nnext\rlast"))
            .isEqualTo("a &lt; b &amp; &quot;c&quot;<br>next<br>last")
        assertThat(plainTextToHtml("<img src=x onerror=alert(1)>")).doesNotContain("<img")
    }
}

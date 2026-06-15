package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Юнит-тесты для EmailUtils.
 * Locale закреплён, т.к. formatFileSize зависит от языка системы.
 */
class EmailUtilsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // ===================== extractName =====================

    @Test
    fun `extractName returns empty for blank input`() {
        assertThat(extractName("")).isEqualTo("")
        assertThat(extractName("   ")).isEqualTo("")
    }

    @Test
    fun `extractName extracts CN from Exchange DN`() {
        assertThat(extractName("/O=ORG/OU=X/CN=John Smith")).isEqualTo("John Smith")
    }

    @Test
    fun `extractName extracts name before angle bracket`() {
        assertThat(extractName("John Smith <john@x.com>")).isEqualTo("John Smith")
    }

    @Test
    fun `extractName extracts quoted name before angle bracket`() {
        assertThat(extractName("\"John Smith\" <john@x.com>")).isEqualTo("John Smith")
    }

    @Test
    fun `extractName returns empty for bare email`() {
        assertThat(extractName("john@x.com")).isEqualTo("")
    }

    @Test
    fun `extractName returns plain name when no email`() {
        assertThat(extractName("John Smith")).isEqualTo("John Smith")
    }

    // ===================== extractDisplayName =====================

    @Test
    fun `extractDisplayName lowercases and capitalizes CN from DN`() {
        assertThat(extractDisplayName("/O=ORG/OU=X/CN=John Smith")).isEqualTo("John smith")
    }

    @Test
    fun `extractDisplayName skips RECIPIENTS and uses last CN`() {
        assertThat(extractDisplayName("/O=ORG/OU=X/CN=RECIPIENTS/CN=jsmith")).isEqualTo("Jsmith")
    }

    @Test
    fun `extractDisplayName extracts RFC name`() {
        assertThat(extractDisplayName("John Smith <john@x.com>")).isEqualTo("John Smith")
    }

    @Test
    fun `extractDisplayName falls back to local part of bare email`() {
        assertThat(extractDisplayName("john@x.com")).isEqualTo("john")
    }

    // ===================== extractEmailAddress =====================

    @Test
    fun `extractEmailAddress extracts from angle brackets`() {
        assertThat(extractEmailAddress("John <john@x.com>")).isEqualTo("john@x.com")
    }

    @Test
    fun `extractEmailAddress returns empty for Exchange DN`() {
        assertThat(extractEmailAddress("/O=ORG/CN=x")).isEqualTo("")
    }

    @Test
    fun `extractEmailAddress returns bare email as is`() {
        assertThat(extractEmailAddress("john@x.com")).isEqualTo("john@x.com")
    }

    @Test
    fun `extractEmailAddress returns empty when no email present`() {
        assertThat(extractEmailAddress("John Smith")).isEqualTo("")
    }

    // ===================== parseRecipientPairs =====================

    @Test
    fun `parseRecipientPairs returns empty list for blank`() {
        assertThat(parseRecipientPairs("")).isEmpty()
    }

    @Test
    fun `parseRecipientPairs splits multiple recipients`() {
        val pairs = parseRecipientPairs("John <john@x.com>, Jane <jane@y.com>")
        assertThat(pairs).hasSize(2)
        assertThat(pairs[0]).isEqualTo("John" to "john@x.com")
        assertThat(pairs[1]).isEqualTo("Jane" to "jane@y.com")
    }

    // ===================== formatRecipients =====================

    @Test
    fun `formatRecipients keeps name and email when distinct`() {
        assertThat(formatRecipients("Johnny <john@x.com>")).isEqualTo("Johnny <john@x.com>")
    }

    @Test
    fun `formatRecipients collapses to email when name duplicates local part`() {
        assertThat(formatRecipients("john@x.com")).isEqualTo("john@x.com")
    }

    @Test
    fun `formatRecipients returns empty for blank`() {
        assertThat(formatRecipients("")).isEqualTo("")
    }

    // ===================== formatFileSize =====================

    @Test
    fun `formatFileSize formats bytes`() {
        assertThat(formatFileSize(500)).isEqualTo("500 B")
        assertThat(formatFileSize(1023)).isEqualTo("1023 B")
    }

    @Test
    fun `formatFileSize formats kilobytes`() {
        assertThat(formatFileSize(1024)).isEqualTo("1 KB")
        assertThat(formatFileSize(2048)).isEqualTo("2 KB")
    }

    @Test
    fun `formatFileSize formats megabytes`() {
        assertThat(formatFileSize(1024L * 1024)).isEqualTo("1 MB")
        assertThat(formatFileSize(5L * 1024 * 1024)).isEqualTo("5 MB")
    }

    @Test
    fun `formatFileSize uses Russian units under ru locale`() {
        Locale.setDefault(Locale("ru"))
        assertThat(formatFileSize(500)).isEqualTo("500 Б")
        assertThat(formatFileSize(2048)).isEqualTo("2 КБ")
    }

    // ===================== stripHtml =====================

    @Test
    fun `stripHtml removes tags and collapses whitespace`() {
        assertThat(stripHtml("<p>Hello <b>World</b></p>")).isEqualTo("Hello World")
    }

    @Test
    fun `stripHtml removes style block entirely`() {
        assertThat(stripHtml("<style>x{}</style><p>Hi</p>")).isEqualTo("Hi")
    }

    @Test
    fun `stripHtml converts br to space`() {
        assertThat(stripHtml("Line1<br>Line2")).isEqualTo("Line1 Line2")
    }

    @Test
    fun `stripHtml decodes basic entities`() {
        assertThat(stripHtml("a&nbsp;b&amp;c")).isEqualTo("a b&c")
    }
}

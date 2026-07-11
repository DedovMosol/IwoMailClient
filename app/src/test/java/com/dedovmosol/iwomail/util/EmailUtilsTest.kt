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

    // ===================== processEmailBodyForDisplay =====================

    @Test
    fun `processEmailBodyForDisplay plain text stays plain`() {
        val result = processEmailBodyForDisplay("Просто текст письма", bodyType = 1)
        assertThat(result.text).isEqualTo("Просто текст письма")
        assertThat(result.isHtml).isFalse()
    }

    @Test
    fun `processEmailBodyForDisplay detects html fragment`() {
        val result = processEmailBodyForDisplay("<div>Привет<br>мир</div>", bodyType = 2)
        assertThat(result.text).isEqualTo("<div>Привет<br>мир</div>")
        assertThat(result.isHtml).isTrue()
    }

    @Test
    fun `processEmailBodyForDisplay unescapes encoded html entities safety-net`() {
        // Старые письма в БД после WBXML-парсинга: <div> сохранён как &lt;div&gt;
        val result = processEmailBodyForDisplay("&lt;div&gt;Тело&lt;br&gt;письма&lt;/div&gt;", bodyType = 2)
        assertThat(result.text).isEqualTo("<div>Тело<br>письма</div>")
        assertThat(result.isHtml).isTrue()
    }

    @Test
    fun `processEmailBodyForDisplay does not unescape plain text with entities`() {
        // Обычный текст с &lt; без HTML-структуры не должен расэкранироваться.
        // Важно: после &lt; не должно идти имя HTML-тега — «&lt; b» эвристика
        // legit трактует как закодированный <b> (см. ENCODED_HTML_EMAIL_FRAGMENT)
        val plain = "5 &lt; 7 и всё"
        val result = processEmailBodyForDisplay(plain, bodyType = 1)
        assertThat(result.text).isEqualTo(plain)
        assertThat(result.isHtml).isFalse()
    }

    @Test
    fun `processEmailBodyForDisplay strips exchange separators`() {
        val result = processEmailBodyForDisplay("Текст*~*~*~*~*~*~*~*~хвост", bodyType = 1)
        assertThat(result.text).doesNotContain("*~")
        assertThat(result.text).startsWith("Текст")
    }

    @Test
    fun `processEmailBodyForDisplay empty body yields empty text not crash`() {
        val result = processEmailBodyForDisplay("", bodyType = 1)
        assertThat(result.text).isEmpty()
        assertThat(result.isHtml).isFalse()
    }

    @Test
    fun `processEmailBodyForDisplay bodyType 4 extracts html part from mime`() {
        // 8bit-кодировка, чтобы не задевать android util Base64 (в юнит-тестах он замокан дефолтами)
        val mime = "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/alternative; boundary=\"BOUND\"\r\n" +
            "\r\n" +
            "--BOUND\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n" +
            "\r\n" +
            "plain версия\r\n" +
            "--BOUND\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n" +
            "\r\n" +
            "<div>html версия</div>\r\n" +
            "--BOUND--\r\n"
        val result = processEmailBodyForDisplay(mime, bodyType = 4)
        assertThat(result.text).contains("html версия")
        assertThat(result.isHtml).isTrue()
    }

    // ===================== detectMeetingEmail =====================

    @Test
    fun `detectMeetingEmail plain email is not meeting`() {
        val d = detectMeetingEmail("Обычное письмо", "Обычная тема", hasCalendarAttachment = false)
        assertThat(d.isMeetingInvitation).isFalse()
        assertThat(d.isMeetingResponse).isFalse()
        assertThat(d.bodyHasVEvent).isFalse()
    }

    @Test
    fun `detectMeetingEmail vevent body is invitation`() {
        val d = detectMeetingEmail(
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VCALENDAR",
            "Планёрка",
            hasCalendarAttachment = false
        )
        assertThat(d.bodyHasVEvent).isTrue()
        assertThat(d.isMeetingInvitation).isTrue()
    }

    @Test
    fun `detectMeetingEmail calendar attachment is invitation`() {
        val d = detectMeetingEmail("Тело", "Тема", hasCalendarAttachment = true)
        assertThat(d.isMeetingInvitation).isTrue()
    }

    @Test
    fun `detectMeetingEmail exchange when-where body is invitation`() {
        val d = detectMeetingEmail("Когда: завтра\nГде: переговорка", "Обсуждение", hasCalendarAttachment = false)
        assertThat(d.isMeetingInvitation).isTrue()
    }

    @Test
    fun `detectMeetingEmail accepted response is not invitation`() {
        val d = detectMeetingEmail(
            "BEGIN:VEVENT",
            "Принято: Планёрка",
            hasCalendarAttachment = false
        )
        assertThat(d.isAcceptedResponse).isTrue()
        assertThat(d.isMeetingResponse).isTrue()
        assertThat(d.isMeetingInvitation).isFalse()
    }

    @Test
    fun `detectMeetingEmail declined and tentative responses in both languages`() {
        assertThat(detectMeetingEmail("", "Отклонено: X", false).isDeclinedResponse).isTrue()
        assertThat(detectMeetingEmail("", "Declined: X", false).isDeclinedResponse).isTrue()
        assertThat(detectMeetingEmail("", "Под вопросом: X", false).isTentativeResponse).isTrue()
        assertThat(detectMeetingEmail("", "Tentative: X", false).isTentativeResponse).isTrue()
        assertThat(detectMeetingEmail("", "Accepted: X", false).isAcceptedResponse).isTrue()
    }

    @Test
    fun `detectMeetingEmail invitation subject keywords`() {
        assertThat(detectMeetingEmail("", "Приглашение: демо", false).isMeetingInvitation).isTrue()
        assertThat(detectMeetingEmail("", "Meeting: demo", false).isMeetingInvitation).isTrue()
    }

    // ===================== parseIcalMeetingInfo =====================

    private val sampleIcal = "BEGIN:VCALENDAR\r\n" +
        "BEGIN:VEVENT\r\n" +
        "ORGANIZER;CN=Boss:mailto:boss@corp.ru\r\n" +
        "SUMMARY:Планёрка\\, важная\r\n" +
        "DTSTART:20260715T100000Z\r\n" +
        "DTEND:20260715T110000Z\r\n" +
        "LOCATION:Переговорка 1\r\n" +
        "DESCRIPTION:Первая строка\\nВторая\r\n" +
        "END:VEVENT\r\n" +
        "END:VCALENDAR"

    @Test
    fun `parseIcalMeetingInfo extracts all fields`() {
        val info = parseIcalMeetingInfo(sampleIcal, "fallback@x.ru", "Fallback тема")
        assertThat(info.organizerEmail).isEqualTo("boss@corp.ru")
        assertThat(info.summary).isEqualTo("Планёрка, важная")
        assertThat(info.location).isEqualTo("Переговорка 1")
        assertThat(info.description).isEqualTo("Первая строка\nВторая")
        // 2026-07-15T10:00:00Z и ровно час длительности
        assertThat(info.endTime - info.startTime).isEqualTo(60 * 60 * 1000L)
        assertThat(info.startTime)
            .isEqualTo(java.time.Instant.parse("2026-07-15T10:00:00Z").toEpochMilli())
    }

    @Test
    fun `parseIcalMeetingInfo falls back to sender and subject`() {
        val now = 1_000_000L
        val info = parseIcalMeetingInfo("", "from@x.ru", "Тема письма", nowMillis = now)
        assertThat(info.organizerEmail).isEqualTo("from@x.ru")
        assertThat(info.summary).isEqualTo("Тема письма")
        assertThat(info.location).isEmpty()
        assertThat(info.startTime).isEqualTo(now)
        assertThat(info.endTime).isEqualTo(now + 60 * 60 * 1000L)
    }
}

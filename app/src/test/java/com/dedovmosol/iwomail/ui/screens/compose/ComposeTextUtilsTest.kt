package com.dedovmosol.iwomail.ui.screens.compose

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты для ComposeTextUtils.
 * Тест в том же пакете — internal-функции доступны из test source set того же модуля.
 */
class ComposeTextUtilsTest {

    // ===================== formatHtmlSignature =====================

    @Test
    fun `formatHtmlSignature returns empty for null or blank`() {
        assertThat(formatHtmlSignature(null)).isEqualTo("")
        assertThat(formatHtmlSignature("   ")).isEqualTo("")
    }

    @Test
    fun `formatHtmlSignature escapes plain text and converts newlines`() {
        assertThat(formatHtmlSignature("John\nDoe", isHtml = false))
            .isEqualTo("<div class=\"signature\"><br>--<br>John<br>Doe</div><!--/signature-->")
    }

    @Test
    fun `formatHtmlSignature keeps html content verbatim`() {
        assertThat(formatHtmlSignature("<b>Hi</b>", isHtml = true))
            .isEqualTo("<div class=\"signature\"><br>--<br><b>Hi</b></div><!--/signature-->")
    }

    @Test
    fun `formatHtmlSignature escapes special chars in plain mode`() {
        assertThat(formatHtmlSignature("a<b>", isHtml = false))
            .isEqualTo("<div class=\"signature\"><br>--<br>a&lt;b&gt;</div><!--/signature-->")
    }

    // ===================== replaceSignatureHtml: $ и \ в подписи (краш-регрессия) =====================

    @Test
    fun `replaceSignatureHtml inserts signature with dollar sign literally`() {
        // Без Regex.escapeReplacement '$' трактуется как ссылка на группу -> исключение/мусор
        val oldSig = "<div class=\"signature\"><br>--<br>Old</div><!--/signature-->"
        val body = "<p>Hello</p>$oldSig"
        val newSig = formatHtmlSignature("Prices from $99", isHtml = false)

        val result = replaceSignatureHtml(body, newSig)

        assertThat(result).contains("<br>--<br>Prices from $99</div><!--/signature-->")
        assertThat(result).doesNotContain("Old")
        assertThat(result).isEqualTo("<p>Hello</p>$newSig")
    }

    @Test
    fun `replaceSignatureHtml inserts signature with backslash literally`() {
        // '\' в replacement — управляющий символ; без экранирования замена падает
        val oldSig = "<div class=\"signature\"><br>--<br>Old</div><!--/signature-->"
        val body = "<p>Hi</p>$oldSig"
        val newSig = formatHtmlSignature("Path: C:\\Users\\john", isHtml = false)

        val result = replaceSignatureHtml(body, newSig)

        assertThat(result).contains("Path: C:\\Users\\john")
        assertThat(result).isEqualTo("<p>Hi</p>$newSig")
    }

    @Test
    fun `replaceSignatureHtml handles dollar group-reference patterns safely`() {
        // $1..$99 выглядят как ссылки на несуществующие группы — обязаны вставиться буквально
        val oldSig = "<div class=\"signature\"><br>--<br>Old</div><!--/signature-->"
        val body = "X$oldSig"
        val newSig = formatHtmlSignature("$1 and $99 and $$", isHtml = false)

        val result = replaceSignatureHtml(body, newSig)

        assertThat(result).isEqualTo("X$newSig")
        assertThat(result).contains("$1 and $99 and $$")
    }

    @Test
    fun `replaceSignatureHtml handles html-mode signature with special chars`() {
        val oldSig = "<div class=\"signature\"><br>--<br>Old</div><!--/signature-->"
        val body = "B$oldSig"
        val newSig = formatHtmlSignature("<b>$500 \\ off</b>", isHtml = true)

        val result = replaceSignatureHtml(body, newSig)

        assertThat(result).isEqualTo("B$newSig")
        assertThat(result).contains("<b>$500 \\ off</b>")
    }

    @Test
    fun `replaceSignatureHtml returns body unchanged when no signature present`() {
        val body = "<p>No signature here, price is $5 \\ each</p>"
        assertThat(replaceSignatureHtml(body, "new")).isEqualTo(body)
    }

    @Test
    fun `replaceSignatureHtml removes signature when replacement is empty`() {
        val oldSig = "<div class=\"signature\"><br>--<br>Old</div><!--/signature-->"
        val body = "<p>Hello</p>$oldSig"
        assertThat(replaceSignatureHtml(body, "")).isEqualTo("<p>Hello</p>")
    }

    @Test
    fun `formatHtmlSignature preserves dollar and backslash in plain mode`() {
        // escapeHtml не трогает $ и \ — они доходят до Regex-замены как есть
        assertThat(formatHtmlSignature("$500 \\ 2", isHtml = false))
            .isEqualTo("<div class=\"signature\"><br>--<br>$500 \\ 2</div><!--/signature-->")
    }

    // ===================== formatHtmlQuote =====================

    @Test
    fun `formatHtmlQuote includes escaped headers and raw original body`() {
        val quote = formatHtmlQuote(
            header = "Original",
            from = "john@x.com",
            date = "2026-01-15",
            subject = "Hello",
            toField = null,
            originalBody = "<p>body</p>"
        )

        assertThat(quote).contains("<b>--- Original ---</b>")
        assertThat(quote).contains("<b>From:</b> john@x.com")
        assertThat(quote).contains("<b>Date:</b> 2026-01-15")
        assertThat(quote).contains("<b>Subject:</b> Hello")
        assertThat(quote).contains("<p>body</p>")
        assertThat(quote).doesNotContain("<b>To:</b>")
    }

    @Test
    fun `formatHtmlQuote includes To line when toField present`() {
        val quote = formatHtmlQuote(
            header = "Original",
            from = "a@x.com",
            date = "2026-01-15",
            subject = "Hi",
            toField = "jane@y.com",
            originalBody = "body"
        )
        assertThat(quote).contains("<b>To:</b> jane@y.com")
    }

    @Test
    fun `formatHtmlQuote uses localized field labels when provided`() {
        val quote = formatHtmlQuote(
            header = "Исходное сообщение",
            from = "ivan@x.ru",
            date = "15.01.2026",
            subject = "Привет",
            toField = "petr@y.ru",
            originalBody = "тело",
            fromLabel = "От",
            dateLabel = "Дата",
            subjectLabel = "Тема",
            toLabel = "Кому"
        )
        assertThat(quote).contains("<b>От:</b> ivan@x.ru")
        assertThat(quote).contains("<b>Дата:</b> 15.01.2026")
        assertThat(quote).contains("<b>Тема:</b> Привет")
        assertThat(quote).contains("<b>Кому:</b> petr@y.ru")
        // Английские метки не должны протекать при заданных русских
        assertThat(quote).doesNotContain("<b>From:</b>")
        assertThat(quote).doesNotContain("<b>To:</b>")
    }

    @Test
    fun `formatHtmlQuote escapes field labels`() {
        val quote = formatHtmlQuote(
            header = "H",
            from = "a@x.com",
            date = "d",
            subject = "s",
            toField = null,
            originalBody = "b",
            fromLabel = "<b&>"
        )
        assertThat(quote).contains("&lt;b&amp;&gt;:")
    }

    // ===================== looksLikeHtml =====================

    @Test
    fun `looksLikeHtml detects html tag`() {
        assertThat("<div>x</div>".looksLikeHtml()).isTrue()
    }

    @Test
    fun `looksLikeHtml rejects plain text and lone less-than`() {
        assertThat("plain text".looksLikeHtml()).isFalse()
        assertThat("a < b".looksLikeHtml()).isFalse()
        assertThat("<3 you".looksLikeHtml()).isFalse()
    }

    // ===================== extractEmailFromString =====================

    @Test
    fun `extractEmailFromString prefers bracketed address`() {
        assertThat(extractEmailFromString("John <john@x.com>")).isEqualTo("john@x.com")
    }

    @Test
    fun `extractEmailFromString returns null for blank or no email`() {
        assertThat(extractEmailFromString("")).isNull()
        assertThat(extractEmailFromString("   ")).isNull()
        assertThat(extractEmailFromString("no email here")).isNull()
    }

    @Test
    fun `extractEmailFromString finds plain address in text`() {
        assertThat(extractEmailFromString("contact me at bob@y.com please")).isEqualTo("bob@y.com")
    }

    @Test
    fun `extractEmailFromString honors query hint`() {
        assertThat(extractEmailFromString("a@x.com, b@y.com", "b@y")).isEqualTo("b@y.com")
    }

    // ===================== replaceCidWithDataUrl =====================

    @Test
    fun `replaceCidWithDataUrl replaces plain cid reference`() {
        val result = replaceCidWithDataUrl(
            "<img src=\"cid:abc\">",
            mapOf("abc" to "data:image/png;base64,XX")
        )
        assertThat(result).isEqualTo("<img src=\"data:image/png;base64,XX\">")
    }

    @Test
    fun `replaceCidWithDataUrl strips angle brackets from cid key`() {
        val result = replaceCidWithDataUrl("cid:abc", mapOf("<abc>" to "DATA"))
        assertThat(result).isEqualTo("DATA")
    }

    @Test
    fun `replaceCidWithDataUrl leaves content without cid untouched`() {
        assertThat(replaceCidWithDataUrl("hello", mapOf("a" to "b"))).isEqualTo("hello")
    }

    // ===================== normalizeRecipients (Этап 3, CS-16) =====================

    @Test
    fun `normalizeRecipients removes duplicates preserving order`() {
        assertThat(normalizeRecipients("a@x.com, a@x.com, b@y.com"))
            .isEqualTo("a@x.com, b@y.com")
    }

    @Test
    fun `normalizeRecipients extracts email from bracketed display name`() {
        assertThat(normalizeRecipients("John Doe <john@x.com>")).isEqualTo("john@x.com")
    }

    @Test
    fun `normalizeRecipients strips quotes and brackets`() {
        assertThat(normalizeRecipients("\"Doe, John\" <john@x.com>")).isEqualTo("john@x.com")
    }

    @Test
    fun `normalizeRecipients drops invalid tokens and blank segments`() {
        assertThat(normalizeRecipients("a@x.com, , invalid, b@y.com"))
            .isEqualTo("a@x.com, b@y.com")
    }

    @Test
    fun `normalizeRecipients keeps group tokens intact`() {
        assertThat(normalizeRecipients("[Team], a@x.com")).isEqualTo("[Team], a@x.com")
    }

    @Test
    fun `normalizeRecipients splits on semicolon too`() {
        assertThat(normalizeRecipients("a@x.com; b@y.com")).isEqualTo("a@x.com, b@y.com")
    }

    @Test
    fun `normalizeRecipients empty input gives empty output`() {
        assertThat(normalizeRecipients("")).isEqualTo("")
        assertThat(normalizeRecipients("  ;  ")).isEqualTo("")
    }

    // ===================== extractQueryPart (Этап 3) =====================

    @Test
    fun `extractQueryPart returns text after last separator`() {
        assertThat(extractQueryPart("a@x.com, jo")).isEqualTo("jo")
        assertThat(extractQueryPart("a@x.com; jo")).isEqualTo("jo")
        assertThat(extractQueryPart("a@x.com\njo")).isEqualTo("jo")
    }

    @Test
    fun `extractQueryPart returns whole text when no separator`() {
        assertThat(extractQueryPart("john")).isEqualTo("john")
    }

    // ===================== replaceLastRecipient (Этап 3) =====================

    @Test
    fun `replaceLastRecipient replaces token after last separator`() {
        assertThat(replaceLastRecipient("a@x.com, jo", "john@x.com"))
            .isEqualTo("a@x.com, john@x.com")
    }

    @Test
    fun `replaceLastRecipient returns new email when no separator`() {
        assertThat(replaceLastRecipient("jo", "john@x.com")).isEqualTo("john@x.com")
    }

    @Test
    fun `replaceLastRecipient adds space after separator when missing`() {
        assertThat(replaceLastRecipient("a@x.com,jo", "john@x.com"))
            .isEqualTo("a@x.com, john@x.com")
    }
}

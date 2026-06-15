package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Юнит-тесты для HtmlUtils.
 * Особое внимание sanitizeEmailHtml — это security-critical XSS-защита.
 */
class HtmlUtilsTest {

    // ===================== escapeHtml =====================

    @Test
    fun `escapeHtml escapes all special characters in correct order`() {
        assertThat("a<b>c&d\"e'f".escapeHtml())
            .isEqualTo("a&lt;b&gt;c&amp;d&quot;e&#39;f")
    }

    @Test
    fun `escapeHtml escapes ampersand first to avoid double encoding`() {
        assertThat("<&>".escapeHtml()).isEqualTo("&lt;&amp;&gt;")
    }

    @Test
    fun `escapeHtml returns plain text unchanged`() {
        assertThat("Hello World".escapeHtml()).isEqualTo("Hello World")
    }

    @Test
    fun `escapeHtml on empty string returns empty`() {
        assertThat("".escapeHtml()).isEqualTo("")
    }

    // ===================== sanitizeEmailHtml (XSS) =====================

    @Test
    fun `sanitize removes script tag with its content`() {
        val result = sanitizeEmailHtml("<p>hi</p><script>alert(1)</script>")
        assertThat(result).isEqualTo("<p>hi</p>")
        assertThat(result).doesNotContain("alert")
        assertThat(result).doesNotContain("<script")
    }

    @Test
    fun `sanitize removes standalone script open tag`() {
        assertThat(sanitizeEmailHtml("<script>")).isEqualTo("")
    }

    @Test
    fun `sanitize removes iframe`() {
        assertThat(sanitizeEmailHtml("<iframe src=\"evil\"></iframe>")).isEqualTo("")
    }

    @Test
    fun `sanitize removes object embed and applet`() {
        assertThat(sanitizeEmailHtml("<object data=\"x\"></object>")).isEqualTo("")
        assertThat(sanitizeEmailHtml("<embed src=\"x\">")).isEqualTo("")
        assertThat(sanitizeEmailHtml("<applet code=\"x\"></applet>")).isEqualTo("")
    }

    @Test
    fun `sanitize removes meta refresh redirect`() {
        val html = "<meta http-equiv=\"refresh\" content=\"0;url=evil\">"
        assertThat(sanitizeEmailHtml(html)).isEqualTo("")
    }

    @Test
    fun `sanitize removes double quoted event handler`() {
        assertThat(sanitizeEmailHtml("<a onclick=\"steal()\">x</a>")).isEqualTo("<a>x</a>")
    }

    @Test
    fun `sanitize removes single quoted event handler`() {
        assertThat(sanitizeEmailHtml("<a onmouseover='x'>L</a>")).isEqualTo("<a>L</a>")
    }

    @Test
    fun `sanitize removes unquoted event handler`() {
        assertThat(sanitizeEmailHtml("<a onclick=steal()>L</a>")).isEqualTo("<a>L</a>")
    }

    @Test
    fun `sanitize neutralizes javascript URI in double quotes`() {
        val result = sanitizeEmailHtml("<a href=\"javascript:alert(1)\">x</a>")
        assertThat(result).isEqualTo("<a href=\"#\">x</a>")
        assertThat(result).doesNotContain("javascript:")
    }

    @Test
    fun `sanitize neutralizes javascript URI in single quotes`() {
        assertThat(sanitizeEmailHtml("<a href='javascript:x'>L</a>"))
            .isEqualTo("<a href='#'>L</a>")
    }

    @Test
    fun `sanitize neutralizes data text html URI`() {
        assertThat(sanitizeEmailHtml("<a href=\"data:text/html,hello\">x</a>"))
            .isEqualTo("<a href=\"#\">x</a>")
    }

    @Test
    fun `sanitize preserves safe content with style attribute`() {
        val safe = "<p style=\"color:red\">Hello <b>World</b></p>"
        assertThat(sanitizeEmailHtml(safe)).isEqualTo(safe)
    }

    @Test
    fun `sanitize preserves inline data image (only data text html is blocked)`() {
        val img = "<img src=\"data:image/png;base64,AAAA\">"
        assertThat(sanitizeEmailHtml(img)).isEqualTo(img)
    }

    @Test
    fun `sanitize is idempotent`() {
        val payload = "<div onclick=\"x\"><script>e</script>" +
            "<a href=\"javascript:y\">L</a></div>"
        val once = sanitizeEmailHtml(payload)
        assertThat(once).isEqualTo("<div><a href=\"#\">L</a></div>")
        assertThat(sanitizeEmailHtml(once)).isEqualTo(once)
    }

    // ===================== looksLikeHtmlEmailContent =====================

    @Test
    fun `looksLikeHtmlEmailContent detects known html fragments`() {
        assertThat(looksLikeHtmlEmailContent("<div>hi</div>")).isTrue()
        assertThat(looksLikeHtmlEmailContent("text<br>more")).isTrue()
        assertThat(looksLikeHtmlEmailContent("<table><tr><td>x</td></tr></table>")).isTrue()
    }

    @Test
    fun `looksLikeHtmlEmailContent rejects plain text and unknown tags`() {
        assertThat(looksLikeHtmlEmailContent("just plain text")).isFalse()
        assertThat(looksLikeHtmlEmailContent("<custom>nope</custom>")).isFalse()
    }

    @Test
    fun `looksLikeEncodedHtmlEmailContent detects encoded fragments`() {
        assertThat(looksLikeEncodedHtmlEmailContent("&lt;div&gt;")).isTrue()
        assertThat(looksLikeEncodedHtmlEmailContent("&lt;custom&gt;")).isFalse()
        assertThat(looksLikeEncodedHtmlEmailContent("<div>")).isFalse()
    }

    // ===================== stripHtmlIfNeeded =====================

    @Test
    fun `stripHtmlIfNeeded returns text unchanged when no html body or font markers`() {
        // Нет <html/<body/<font — функция возвращает текст как есть (даже с <div>)
        assertThat(stripHtmlIfNeeded("<div>hi</div>")).isEqualTo("<div>hi</div>")
        assertThat(stripHtmlIfNeeded("plain text")).isEqualTo("plain text")
    }

    @Test
    fun `stripHtmlIfNeeded strips tags and converts br to newline`() {
        val html = "<html><body>Hello<br>World</body></html>"
        assertThat(stripHtmlIfNeeded(html)).isEqualTo("Hello\nWorld")
    }

    @Test
    fun `stripHtmlIfNeeded decodes numeric entities`() {
        assertThat(stripHtmlIfNeeded("<font>caf&#233;</font>")).isEqualTo("café")
    }

    // ===================== HtmlRegex.decodeNumericEntity =====================

    @Test
    fun `decodeNumericEntity decodes decimal entity`() {
        val decoded = HtmlRegex.HTML_ENTITY.replace("&#65;") { HtmlRegex.decodeNumericEntity(it) }
        assertThat(decoded).isEqualTo("A")
    }

    @Test
    fun `decodeNumericEntity decodes hex entity`() {
        val decoded = HtmlRegex.HTML_ENTITY.replace("&#x41;") { HtmlRegex.decodeNumericEntity(it) }
        assertThat(decoded).isEqualTo("A")
    }

    @Test
    fun `decodeNumericEntity returns original on overflow`() {
        val input = "&#999999999999;"
        val decoded = HtmlRegex.HTML_ENTITY.replace(input) { HtmlRegex.decodeNumericEntity(it) }
        assertThat(decoded).isEqualTo(input)
    }
}

package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Тесты извлечения text/html|text/plain из MIME (bodyType=4).
 *
 * Регрессия: ленивый regex с lookahead (?=--|$) обрезал тело части на ПЕРВОМ "--"
 * в любом месте — на Outlook conditional comments ("<!--[if mso]>"), "<style><!--"
 * и "-----Original Message-----". Теперь основной путь — RFC 2046-сплит по boundary.
 *
 * Все сэмплы используют CTE 8bit/без CTE — android.util.Base64 в юнит-тестах
 * замокан null-дефолтами, base64-ветки здесь не задеваются.
 */
class MimeHtmlProcessorExtractHtmlTest {

    private fun multipart(vararg parts: Pair<String, String>): String {
        val b = "----=_NextPart_000_ABCD"
        return buildString {
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: multipart/alternative; boundary=\"$b\"\r\n")
            append("\r\n")
            for ((contentType, body) in parts) {
                append("--$b\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Transfer-Encoding: 8bit\r\n")
                append("\r\n")
                append(body)
                append("\r\n")
            }
            append("--$b--\r\n")
        }
    }

    // ===================== регрессия «обрезка на --» =====================

    @Test
    fun `outlook conditional comments survive extraction`() {
        val html = "<html><head><!--[if mso]><style>v\\:* {behavior:url(#default#VML);}</style><![endif]--></head>" +
            "<body><div>Тело письма после комментария</div></body></html>"
        val result = MimeHtmlProcessor.extractHtmlFromMime(multipart("text/html; charset=utf-8" to html))
        assertThat(result).contains("Тело письма после комментария")
        assertThat(result).contains("<![endif]-->")
    }

    @Test
    fun `style block with html comment survives extraction`() {
        val html = "<html><head><style><!--\r\np {margin:0;}\r\n--></style></head><body>После стилей</body></html>"
        val result = MimeHtmlProcessor.extractHtmlFromMime(multipart("text/html; charset=utf-8" to html))
        assertThat(result).contains("После стилей")
    }

    @Test
    fun `original message separator survives extraction`() {
        val html = "<div>Ответ</div>\r\n-----Original Message-----\r\n<div>Цитата исходного письма</div>"
        val result = MimeHtmlProcessor.extractHtmlFromMime(multipart("text/html; charset=utf-8" to html))
        assertThat(result).contains("Цитата исходного письма")
        assertThat(result).contains("-----Original Message-----")
    }

    @Test
    fun `plain text signature delimiter survives extraction`() {
        val text = "Привет!\r\n-- \r\nИван Иванов\r\nОтдел ИТ"
        val result = MimeHtmlProcessor.extractHtmlFromMime(multipart("text/plain; charset=utf-8" to text))
        assertThat(result).contains("Иван Иванов")
        assertThat(result).contains("Отдел ИТ")
    }

    // ===================== выбор части и вложенность =====================

    @Test
    fun `html part preferred over plain`() {
        val result = MimeHtmlProcessor.extractHtmlFromMime(
            multipart(
                "text/plain; charset=utf-8" to "plain версия",
                "text/html; charset=utf-8" to "<div>html версия</div>"
            )
        )
        assertThat(result).contains("html версия")
        assertThat(result).doesNotContain("plain версия")
    }

    @Test
    fun `nested multipart mixed with alternative finds html`() {
        val inner = "----=_Inner_001"
        val outer = "----=_Outer_001"
        val mime = "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"$outer\"\r\n" +
            "\r\n" +
            "--$outer\r\n" +
            "Content-Type: multipart/alternative; boundary=\"$inner\"\r\n" +
            "\r\n" +
            "--$inner\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "\r\n" +
            "plain версия\r\n" +
            "--$inner\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "\r\n" +
            "<div>вложенный html</div>\r\n" +
            "--$inner--\r\n" +
            "--$outer\r\n" +
            "Content-Type: application/octet-stream; name=\"file.bin\"\r\n" +
            "\r\n" +
            "BINDATA\r\n" +
            "--$outer--\r\n"
        val result = MimeHtmlProcessor.extractHtmlFromMime(mime)
        assertThat(result).contains("вложенный html")
        assertThat(result).doesNotContain("BINDATA")
    }

    @Test
    fun `plain fallback converts newlines to br`() {
        val result = MimeHtmlProcessor.extractHtmlFromMime(
            multipart("text/plain; charset=utf-8" to "строка 1\nстрока 2")
        )
        assertThat(result).contains("строка 1<br>строка 2")
    }

    @Test
    fun `mail with a large raw PDF section still yields the small decoded HTML part`() {
        val mime = multipart(
            "application/pdf" to "A".repeat(9 * 1024 * 1024),
            "text/html; charset=utf-8" to "<img src='cid:Logo'>"
        )
        assertThat(MimeHtmlProcessor.extractHtmlFromMime(mime)).isEqualTo("<img src='cid:Logo'>")
    }

    // ===================== fallback без boundary =====================

    @Test
    fun `large base64 wrapped mime streams past adjacent pdf to small html`() {
        val raw = multipart(
            "application/pdf" to "A".repeat(7 * 1024 * 1024),
            "text/html; charset=utf-8" to "<div>small html after pdf</div>"
        )
        val wrapped = java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.ISO_8859_1))
        assertThat(wrapped.length).isGreaterThan(8 * 1024 * 1024)
        assertThat(MimeHtmlProcessor.extractHtmlFromMime(wrapped)).isEqualTo("<div>small html after pdf</div>")
    }

    @Test
    fun `streamed wrapper decodes nested folded charset and ignores false boundaries in the pdf`() {
        val html = "<div>Тело <!-- сохранено --></div>"
        val encoder = java.util.Base64.getEncoder()
        val innerHtml = encoder.encodeToString(html.toByteArray(Charsets.UTF_8))
        val mime = "Content-Type: multipart/mixed;\r\n boundary=outer\r\n\r\n" +
            "--outer\r\nContent-Type: application/pdf\r\n\r\n" +
            "A".repeat(7 * 1024 * 1024) +
            "\r\n--outerFAKE\r\nContent-Type: text/html\r\n\r\n<html>not the body</html>\r\n" +
            "--outer\r\nContent-Type: multipart/alternative; boundary=inner\r\n\r\n" +
            "--inner\r\nContent-Type: text/plain\r\n\r\nfallback\r\n" +
            "--inner\r\nContent-Type: text/html;\r\n charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n$innerHtml\r\n--inner--\r\n--outer--"
        val wrapped = java.util.Base64.getMimeEncoder().encodeToString(mime.toByteArray(Charsets.ISO_8859_1))
        assertThat(MimeHtmlProcessor.extractHtmlFromMime(wrapped)).isEqualTo(html)
    }

    @Test
    fun `large wrapped pdf alone is not returned as message text`() {
        val mime = multipart("application/pdf" to "A".repeat(7 * 1024 * 1024))
        val wrapped = java.util.Base64.getEncoder().encodeToString(mime.toByteArray(Charsets.ISO_8859_1))
        assertThat(MimeHtmlProcessor.extractHtmlFromMime(wrapped)).isEmpty()
    }

    @Test
    fun `single part mime without boundary uses regex fallback`() {
        val mime = "MIME-Version: 1.0\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n" +
            "\r\n" +
            "<div>одночастное письмо</div>"
        val result = MimeHtmlProcessor.extractHtmlFromMime(mime)
        assertThat(result).contains("одночастное письмо")
    }

    @Test
    fun `non mime input returned as is`() {
        val body = "просто текст без MIME"
        assertThat(MimeHtmlProcessor.extractHtmlFromMime(body)).isEqualTo(body)
    }
}

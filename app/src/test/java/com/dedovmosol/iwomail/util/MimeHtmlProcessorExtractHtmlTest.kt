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

    // ===================== fallback без boundary =====================

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

package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Страница печати: шапка экранирована, сеть — по правилам картинок, текстовое письмо сохраняет переносы. */
class EmailPrintDocumentTest {

    private val labels = EmailPrintDocument.Labels(from = "От", to = "Кому", cc = "Копия", date = "Дата")
    private val header = EmailPrintDocument.Header(
        subject = "Счёт <№1> & \"срочно\"", from = "Иван <ivan@corp.example>", to = "me@corp.example", cc = "", date = "1 янв. 2026"
    )

    @Test
    fun `header values are escaped and empty rows are skipped`() {
        val html = EmailPrintDocument.html(header, labels, "<p>Тело</p>", bodyIsHtml = true, remoteAllowed = false)

        assertThat(html).contains("<h1>Счёт &lt;№1&gt; &amp; &quot;срочно&quot;</h1>")
        assertThat(html).contains("<th>От</th><td>Иван &lt;ivan@corp.example&gt;</td>")
        assertThat(html).doesNotContain("<th>Копия</th>")
        assertThat(html).contains("<p>Тело</p>")
    }

    @Test
    fun `remote content is blocked unless allowed`() {
        val blocked = EmailPrintDocument.html(header, labels, "", bodyIsHtml = true, remoteAllowed = false)
        val allowed = EmailPrintDocument.html(header, labels, "", bodyIsHtml = true, remoteAllowed = true)

        assertThat(blocked).contains("img-src data: cid: blob:;")
        assertThat(blocked).doesNotContain("https:")
        assertThat(allowed).contains("img-src data: cid: blob: https: http:;")
    }

    @Test
    fun `plain text body is escaped and keeps its line breaks`() {
        val html = EmailPrintDocument.html(header, labels, "a < b\nвторая строка", bodyIsHtml = false, remoteAllowed = false)

        assertThat(html).contains("<div class=\"plain\">a &lt; b\nвторая строка</div>")
        assertThat(html).contains("white-space:pre-wrap")
    }
}

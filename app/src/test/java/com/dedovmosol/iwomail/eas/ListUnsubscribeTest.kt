package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** List-Unsubscribe (RFC 2369) и отписка одним запросом (RFC 8058) из верхних заголовков MIME. */
class ListUnsubscribeTest {

    private val headers = """
        From: News <news@shop.example>
        List-Unsubscribe: <mailto:unsub@shop.example?subject=unsubscribe%20me>,
         <https://shop.example/u/abc123>
        List-Unsubscribe-Post: List-Unsubscribe=One-Click
        Subject: Sale
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `folded header with both URIs and one-click is read`() {
        val (value, oneClick) = requireNotNull(ListUnsubscribe.fromHeaders(headers))

        assertThat(oneClick).isTrue()
        val parsed = requireNotNull(ListUnsubscribe.parse(value, oneClick))
        assertThat(parsed.https).isEqualTo("https://shop.example/u/abc123")
        assertThat(parsed.mailto).isEqualTo(ListUnsubscribe.Mailto("unsub@shop.example", "unsubscribe me", ""))
        // Отписка одним запросом — без браузера и без письма от имени пользователя
        assertThat(parsed.method).isEqualTo(ListUnsubscribe.Method.OneClick("https://shop.example/u/abc123"))
    }

    @Test
    fun `without one-click mail is preferred to the web page`() {
        val parsed = requireNotNull(ListUnsubscribe.parse("<https://a.example/u>, <mailto:list@a.example>", oneClick = false))

        assertThat(parsed.method).isEqualTo(ListUnsubscribe.Method.Mail(ListUnsubscribe.Mailto("list@a.example", "unsubscribe", "")))
    }

    @Test
    fun `only a web page opens it`() {
        val parsed = requireNotNull(ListUnsubscribe.parse("<https://a.example/u?id=1>", oneClick = false))

        assertThat(parsed.method).isEqualTo(ListUnsubscribe.Method.Web("https://a.example/u?id=1"))
    }

    @Test
    fun `one-click without an https URI is not one-click`() {
        val parsed = requireNotNull(ListUnsubscribe.parse("<mailto:list@a.example>", oneClick = true))

        assertThat(parsed.oneClick).isFalse()
        assertThat(parsed.method).isInstanceOf(ListUnsubscribe.Method.Mail::class.java)
    }

    @Test
    fun `plain http, javascript and broken URIs are ignored`() {
        // http: — запрос виден и подменяем по пути, в адресе отписки — идентификатор получателя
        assertThat(ListUnsubscribe.parse("<http://a.example/u>", oneClick = true)).isNull()
        assertThat(ListUnsubscribe.parse("<javascript:alert(1)>", oneClick = false)).isNull()
        assertThat(ListUnsubscribe.parse("<https://user:pw@a.example/u>", oneClick = false)).isNull()
        assertThat(ListUnsubscribe.parse("<mailto:not-an-address>", oneClick = false)).isNull()
        assertThat(ListUnsubscribe.parse("https://a.example/u", oneClick = false)).isNull() // без угловых скобок
        assertThat(ListUnsubscribe.parse(null, oneClick = false)).isNull()
    }

    @Test
    fun `whitespace inside brackets and comments outside are skipped`() {
        val parsed = ListUnsubscribe.parse("(Use this to leave) <https://a.example/ u/1>", oneClick = false)

        assertThat(parsed?.https).isEqualTo("https://a.example/u/1")
    }

    @Test
    fun `mailto fields are percent-decoded and plus stays a plus`() {
        val parsed = ListUnsubscribe.parse("<mailto:list+leave@a.example?Subject=Leave%20list&body=id%3D7+x>", oneClick = false)

        assertThat(parsed?.mailto).isEqualTo(ListUnsubscribe.Mailto("list+leave@a.example", "Leave list", "id=7+x"))
    }

    @Test
    fun `no header, too long or useless header gives nothing`() {
        assertThat(ListUnsubscribe.fromHeaders("From: a@b.example\r\nSubject: x")).isNull()
        assertThat(ListUnsubscribe.fromHeaders("List-Unsubscribe: <https://a.example/${"x".repeat(3000)}>")).isNull()
        assertThat(ListUnsubscribe.fromHeaders("List-Unsubscribe: <ftp://a.example/u>")).isNull()
    }

    @Test
    fun `List-Unsubscribe-Post alone is not mistaken for List-Unsubscribe`() {
        assertThat(ListUnsubscribe.fromHeaders("List-Unsubscribe-Post: List-Unsubscribe=One-Click")).isNull()
    }

    @Test
    fun `other Post values are not one-click`() {
        val result = ListUnsubscribe.fromHeaders("List-Unsubscribe: <https://a.example/u>\r\nList-Unsubscribe-Post: Something=Else")

        assertThat(result?.second).isFalse()
    }
}

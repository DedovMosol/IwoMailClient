package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** List-Unsubscribe (RFC 2369) и отписка одним запросом (RFC 8058) из верхних заголовков MIME. */
class ListUnsubscribeTest {

    private val dkim = "DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=shop.example; s=s1;\r\n" +
        " h=From:To:Subject:List-Unsubscribe:List-Unsubscribe-Post; bh=47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=;\r\n" +
        " b=AbCd\r\n"

    private val headers = dkim + """
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
    fun `one-click needs a DKIM signature that covers both unsubscribe headers`() {
        val unsigned = headers.removePrefix(dkim)
        assertThat(ListUnsubscribe.fromHeaders(unsigned)?.second).isFalse()

        // Подпись есть, но List-Unsubscribe-Post дописан после неё — не охвачен
        val partly = headers.replace("List-Unsubscribe:List-Unsubscribe-Post", "List-Unsubscribe")
        assertThat(ListUnsubscribe.fromHeaders(partly)?.second).isFalse()

        // Без one-click отписка остаётся — письмом или на странице
        assertThat(ListUnsubscribe.parse(ListUnsubscribe.fromHeaders(unsigned)?.first, oneClick = false)?.method)
            .isInstanceOf(ListUnsubscribe.Method.Mail::class.java)
    }

    @Test
    fun `any of several signatures may cover the headers, case and spaces ignored`() {
        val signatures = "DKIM-Signature: v=1; d=esp.example; h=From:Subject; bh=x; b=y\r\n" +
            "DKIM-Signature: v=1; d=shop.example; H=x; h = from : list-unsubscribe : LIST-UNSUBSCRIBE-POST ; b=z\r\n"
        val signed = signatures + headers.removePrefix(dkim)

        assertThat(ListUnsubscribe.dkimCoversUnsubscribe(signed)).isTrue()
        assertThat(ListUnsubscribe.fromHeaders(signed)?.second).isTrue()
    }

    @Test
    fun `body hash tag is not mistaken for the header list`() {
        val tricky = "DKIM-Signature: v=1; bh=List-Unsubscribe:List-Unsubscribe-Post; h=From; b=x\r\n"

        assertThat(ListUnsubscribe.dkimCoversUnsubscribe(tricky)).isFalse()
    }

    @Test
    fun `unsubscribe is not offered in own mail and junk`() {
        assertThat(ListUnsubscribe.offeredIn(FolderType.INBOX)).isTrue()
        assertThat(ListUnsubscribe.offeredIn(FolderType.DELETED_ITEMS)).isTrue()
        assertThat(ListUnsubscribe.offeredIn(null)).isTrue()
        for (type in listOf(FolderType.SENT_ITEMS, FolderType.DRAFTS, FolderType.OUTBOX, FolderType.JUNK_EMAIL)) {
            assertThat(ListUnsubscribe.offeredIn(type)).isFalse()
        }
    }

    @Test
    fun `other Post values are not one-click`() {
        val result = ListUnsubscribe.fromHeaders("List-Unsubscribe: <https://a.example/u>\r\nList-Unsubscribe-Post: Something=Else")

        assertThat(result?.second).isFalse()
    }
}

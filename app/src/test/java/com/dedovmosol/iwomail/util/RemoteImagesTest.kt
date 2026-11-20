package com.dedovmosol.iwomail.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteImagesTest {

    @Test
    fun `internet images and backgrounds are detected`() {
        assertThat(RemoteImages.present("""<img src="https://track.example/p.gif" width="1">""")).isTrue()
        assertThat(RemoteImages.present("""<IMG SRC='http://x.example/a.png'>""")).isTrue()
        assertThat(RemoteImages.present("""<img src=http://x.example/a.png>""")).isTrue()
        assertThat(RemoteImages.present("""<img src="//cdn.example/a.png">""")).isTrue()
        assertThat(RemoteImages.present("""<img srcset="https://x.example/a.png 2x">""")).isTrue()
        assertThat(RemoteImages.present("""<td background="https://x.example/bg.jpg">""")).isTrue()
        assertThat(RemoteImages.present("""<div style="background-image: url('https://x.example/bg.jpg')">""")).isTrue()
        assertThat(RemoteImages.present("""<style>@font-face{src:url(https://f.example/f.woff)}</style>""")).isTrue()
    }

    @Test
    fun `mixed srcset detects a later external candidate without splitting a data URL`() {
        val html = """<picture><source srcset='cid:small 1x, //cdn.example/large.png 2x'><img srcset="data:image/png;base64,ABCD 1x, https://cdn.example/retina.png 2x"></picture>"""
        assertThat(RemoteImages.present(html)).isTrue()
        assertThat(RemoteImages.present("""<img srcset='data:image/png;base64,ABCD 1x, cid:small 2x'>""")).isFalse()
        assertThat(RemoteImages.prepareForWebView(html, false)).isEqualTo(
            """<picture><source srcset='cid:small 1x'><img srcset="data:image/png;base64,ABCD 1x"></picture>"""
        )
        assertThat(RemoteImages.prepareForWebView(html, true)).contains("https://cdn.example/large.png 2x")
    }

    @Test
    fun `protocol relative render URLs become HTTPS but links and absolute schemes stay intact`() {
        val html = """<a href='//example.test/'>link</a><img src=//cdn.test/a><td background='//cdn.test/b'><p style="background:url(//cdn.test/c)"><img src='http://cdn.test/d'>"""
        assertThat(RemoteImages.prepareForWebView(html, true)).isEqualTo(
            """<a href='//example.test/'>link</a><img src=https://cdn.test/a><td background='https://cdn.test/b'><p style="background:url(https://cdn.test/c)"><img src='http://cdn.test/d'>"""
        )
    }

    @Test
    fun `embedded images and links are not remote content`() {
        assertThat(RemoteImages.present("""<img src="cid:image001.png@01D">""")).isFalse()
        assertThat(RemoteImages.present("""<img src="data:image/png;base64,iVBORw0KGgo=">""")).isFalse()
        assertThat(RemoteImages.present("""<a href="https://example.com">site</a> https://example.com""")).isFalse()
        assertThat(RemoteImages.present("<p>plain</p>")).isFalse()
    }

    @Test
    fun `allowed when always, shown once or the sender is trusted`() {
        val from = "Boss <Boss@Corp.Example>"
        assertThat(RemoteImages.allowed(false, emptySet(), from, false)).isFalse()
        assertThat(RemoteImages.allowed(true, emptySet(), from, false)).isTrue()
        assertThat(RemoteImages.allowed(false, emptySet(), from, true)).isTrue()
        assertThat(RemoteImages.allowed(false, setOf("boss@corp.example"), from, false)).isTrue()
        assertThat(RemoteImages.allowed(false, setOf("other@corp.example"), from, false)).isFalse()
    }

    @Test
    fun `sender without an address is never trusted`() {
        assertThat(RemoteImages.senderKey("/O=CORP/OU=EXCHANGE/CN=RECIPIENTS/CN=BOSS")).isEmpty()
        assertThat(RemoteImages.allowed(false, setOf(""), "/O=CORP/CN=BOSS", false)).isFalse()
        assertThat(RemoteImages.senderKey(" a@B.c ")).isEqualTo("a@b.c")
    }

    @Test
    fun `policy blocks the network unless allowed`() {
        val blocked = RemoteImages.contentSecurityPolicy("n1", remoteAllowed = false)
        assertThat(blocked).contains("img-src data: cid: blob:;")
        assertThat(blocked).contains("font-src data:;")
        assertThat(blocked).doesNotContain("http")
        assertThat(blocked).contains("default-src 'none'")
        assertThat(blocked).contains("script-src 'nonce-n1'")

        val allowed = RemoteImages.contentSecurityPolicy("n1", remoteAllowed = true)
        assertThat(allowed).contains("img-src data: cid: blob: https:;")
        assertThat(allowed).contains("font-src data: https:;")
        assertThat(allowed).doesNotContain(" http:")
        // Внешние стили и @import — никогда
        assertThat(allowed).contains("style-src 'unsafe-inline';")
    }

    @Test
    fun `forms never submit and base never rebases links (MAIL-09)`() {
        for (remote in listOf(false, true)) {
            val policy = RemoteImages.contentSecurityPolicy("n1", remoteAllowed = remote)
            // default-src не распространяется на form-action и base-uri — без них форма письма отправила бы пароль
            assertThat(policy).contains("form-action 'none';")
            assertThat(policy).contains("base-uri 'none';")
        }
    }
}

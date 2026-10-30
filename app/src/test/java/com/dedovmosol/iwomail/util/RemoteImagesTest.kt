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
        assertThat(allowed).contains("img-src data: cid: blob: https: http:;")
        assertThat(allowed).contains("font-src data: https: http:;")
        // Внешние стили и @import — никогда
        assertThat(allowed).contains("style-src 'unsafe-inline';")
    }
}

package com.dedovmosol.iwomail.domain

import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.ListUnsubscribe
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import java.io.IOException

/**
 * Отписка: POST One-Click (RFC 8058 §3.2) реальным OkHttp — сеть заменена перехватчиком; письмо отписки — через
 * отправку аккаунта.
 */
class UnsubscriberTest {

    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private val sent = mutableListOf<Pair<Long, OutgoingMail>>()

    private fun client(respond: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
            respond(request)
        })
        .build()

    private fun response(request: Request, code: Int, location: String? = null) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("x")
        .apply { location?.let { header("Location", it) } }
        .body("".toResponseBody(null))
        .build()

    private fun unsubscriber(http: OkHttpClient, sendResult: EasResult<Boolean> = EasResult.Success(true)) =
        Unsubscriber(http) { accountId, mail -> sent += accountId to mail; sendResult }

    private val oneClick = ListUnsubscribe("https://list.example/u/1", ListUnsubscribe.Mailto("leave@list.example", "unsubscribe", ""), oneClick = true)

    @Test
    fun `one-click is a form POST with the RFC 8058 body and no credentials`() = runBlocking<Unit> {
        val result = unsubscriber(client { response(it, 200) }).unsubscribe(1L, oneClick)

        assertThat(result).isEqualTo(UnsubscribeResult.Done)
        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("https://list.example/u/1")
        assertThat(request.body?.contentType().toString()).startsWith("application/x-www-form-urlencoded")
        assertThat(bodies.single()).isEqualTo("List-Unsubscribe=One-Click")
        assertThat(request.header("Authorization")).isNull()
        assertThat(request.header("Cookie")).isNull()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `redirect answer is not a success and the page opens instead`() = runBlocking<Unit> {
        val result = unsubscriber(client { response(it, 302, location = "https://evil.example/") }).unsubscribe(1L, oneClick)

        assertThat(result).isEqualTo(UnsubscribeResult.OpenPage("https://list.example/u/1"))
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `production client follows no redirects and keeps no cookies`() {
        // RFC 8058 §3.2: отправителю перенаправлять запрещено — POST по чужому адресу не повторяем
        val http = Unsubscriber.DEFAULT_HTTP

        assertThat(http.followRedirects).isFalse()
        assertThat(http.followSslRedirects).isFalse()
        assertThat(http.cookieJar).isEqualTo(okhttp3.CookieJar.NO_COOKIES)
    }

    @Test
    fun `server error or no connection falls back to the page`() = runBlocking<Unit> {
        assertThat(unsubscriber(client { response(it, 500) }).unsubscribe(1L, oneClick))
            .isEqualTo(UnsubscribeResult.OpenPage("https://list.example/u/1"))
        assertThat(unsubscriber(client { throw IOException("offline") }).unsubscribe(1L, oneClick))
            .isEqualTo(UnsubscribeResult.OpenPage("https://list.example/u/1"))
    }

    @Test
    fun `mail unsubscribe is sent from the email's account`() = runBlocking<Unit> {
        val byMail = ListUnsubscribe(null, ListUnsubscribe.Mailto("leave@list.example", "Leave", "id=7 <x>"), oneClick = false)

        val result = unsubscriber(client { error("no HTTP expected") }).unsubscribe(7L, byMail)

        assertThat(result).isEqualTo(UnsubscribeResult.Done)
        val (accountId, mail) = sent.single()
        assertThat(accountId).isEqualTo(7L)
        assertThat(mail.to).isEqualTo("leave@list.example")
        assertThat(mail.subject).isEqualTo("Leave")
        assertThat(mail.body).isEqualTo("id=7 &lt;x&gt;")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `mail that fails to send reports the reason`() = runBlocking<Unit> {
        val byMail = ListUnsubscribe(null, ListUnsubscribe.Mailto("leave@list.example", "Leave", ""), oneClick = false)

        val result = unsubscriber(client { error("no HTTP") }, EasResult.Error("HTTP 503")).unsubscribe(1L, byMail)

        assertThat(result).isEqualTo(UnsubscribeResult.Failed("HTTP 503"))
    }

    @Test
    fun `web page only is opened without any request`() = runBlocking<Unit> {
        val web = ListUnsubscribe("https://list.example/u/1", null, oneClick = false)

        assertThat(unsubscriber(client { error("no HTTP") }).unsubscribe(1L, web))
            .isEqualTo(UnsubscribeResult.OpenPage("https://list.example/u/1"))
        assertThat(requests).isEmpty()
    }
}

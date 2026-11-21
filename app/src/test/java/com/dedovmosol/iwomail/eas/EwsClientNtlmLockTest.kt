package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Тесты M-3: NTLM-лок в EwsClient.
 *
 * Проверяем, что ntlmLock — per-instance (не глобальный статический).
 * Это гарантирует, что сериализация EWS-NTLM запросов происходит
 * только в пределах одного аккаунта, а не блокирует другие аккаунты.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EwsClientNtlmLockTest {

    @Test
    fun `ntlmLock is per-instance, not static`() {
        // Arrange: рефлексия для доступа к приватному полю ntlmLock
        val ntlmLockField = EwsClient::class.java.getDeclaredField("ntlmLock")
        ntlmLockField.isAccessible = true

        // Assert: поле не статическое
        assertFalse(
            "ntlmLock должен быть per-instance, не static",
            Modifier.isStatic(ntlmLockField.modifiers)
        )
    }

    @Test
    fun `two EwsClient instances have different ntlmLock instances`() {
        // Arrange: создаём два EwsClient с разными параметрами
        val client1 = createTestEwsClient("https://server1.example.com")
        val client2 = createTestEwsClient("https://server2.example.com")

        // Act: получаем ntlmLock через рефлексию
        val ntlmLockField = EwsClient::class.java.getDeclaredField("ntlmLock")
        ntlmLockField.isAccessible = true
        val lock1 = ntlmLockField.get(client1)
        val lock2 = ntlmLockField.get(client2)

        // Assert: разные экземпляры (per-instance)
        assertNotSame(
            "ntlmLock должен быть уникальным для каждого EwsClient (per-account)",
            lock1,
            lock2
        )
    }

    @Test
    fun `Basic follows only an explicit challenge with no fallback on ambiguous CreateItem`() = runBlocking<Unit> {
        for ((status, challenge) in listOf(401 to "Basic realm=\"Exchange\"", 401 to "Negotiate", 200 to null, 503 to null, 0 to null)) {
            val auth = mutableListOf<String>()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                auth.add(chain.request().header("Authorization").orEmpty())
                if (status == 0) throw IOException("Connection reset after request")
                val code = if (auth.size == 1) status else 200
                val content = if (auth.size == 1) "" else EwsXml.success("CreateItem")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
                    .apply { if (auth.size == 1 && challenge != null) header("WWW-Authenticate", challenge) }
                    .body(content.toResponseBody("text/xml".toMediaType())).build()
            }.build()
            val result = createTestEwsClient("http://example.test/ews", http).executeRequest("<m:CreateItem/>", "CreateItem")
            assertThat(auth.first()).startsWith("NTLM ")
            if (challenge?.startsWith("Basic") == true) {
                assertThat(auth).hasSize(2)
                assertThat(auth.last()).startsWith("Basic ")
                assertThat(result).isInstanceOf(EwsResult.Success::class.java)
            } else {
                assertThat(auth).hasSize(1)
                assertThat(result).isInstanceOf(EwsResult.Error::class.java)
            }
        }
    }

    @Test
    fun `cancellation interrupts a socket blocked on headers or a streaming body`() = runBlocking<Unit> {
        for (sendHeaders in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val blocked = CountDownLatch(1)
                val peerClosed = CountDownLatch(1)
                val worker = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        try {
                            val input = socket.getInputStream().bufferedReader()
                            var contentLength = 0
                            while (true) {
                                val line = input.readLine() ?: return@thread
                                if (line.isBlank()) break
                                if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toInt()
                            }
                            repeat(contentLength) { input.read() } // request below is ASCII
                            if (sendHeaders) {
                                socket.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
                                    flush()
                                }
                            } else blocked.countDown()
                            if (input.read() == -1) peerClosed.countDown()
                        } catch (_: IOException) { }
                    }
                }
                val client = createTestEwsClient("http://127.0.0.1:${server.localPort}/ews")
                val call = launch {
                    client.executeStreaming("<soap/>", "GetAttachment") { input, _ ->
                        blocked.countDown()
                        input.read()
                    }
                }
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        assertThat(blocked.await(3, TimeUnit.SECONDS)).isTrue()
                    }
                    withTimeout(2000) { call.cancelAndJoin() }
                    assertThat(peerClosed.await(1, TimeUnit.SECONDS)).isTrue()
                } finally {
                    call.cancel()
                    worker.join(6000)
                }
            }
        }
    }

    private fun createTestEwsClient(url: String, httpClient: OkHttpClient = OkHttpClient()): EwsClient {
        // Создаём EwsClient с минимальными зависимостями для теста
        // Нам не нужен реальный NtlmAuthenticator или OkHttpClient
        val ntlmAuth = NtlmAuthenticator("DOMAIN", "user", "pass")
        return EwsClient(
            ewsUrl = url,
            username = "user",
            password = "pass",
            domain = "DOMAIN",
            httpClient = httpClient,
            ntlmAuth = ntlmAuth
        )
    }
}
package com.dedovmosol.iwomail.network

import com.google.common.truth.Truth.assertThat
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.security.Security
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.net.ServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * TLS на настоящих сокетах с тем же Conscrypt 2.5.2 (BoringSSL), что в APK: в APK — conscrypt-android,
 * в юнит-тестах — conscrypt-openjdk-uber той же версии (приходит с Robolectric).
 *
 * Сервер как Exchange 2007 на Windows Server 2003/2008: только TLS 1.0, RSA + AES-CBC. Сертификаты —
 * `app/src/test/resources/certs/tls` (keytool): tls_ok (SAN ip:127.0.0.1), tls_expired (истёк в 2024),
 * tls_dns_only (SAN только dns:mail.company.local).
 */
class HttpClientProviderTls10Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<TlsServer>()

    /**
     * Как в приложении: MailApplication.initConscrypt ставит bundled Conscrypt первым провайдером
     * (TrustManagerFactory/CertificateFactory тоже из него). Тест не зависит от того, успел ли это
     * сделать Robolectric-тест в той же JVM.
     */
    @Before
    fun installConscryptFirst() {
        Security.insertProviderAt(conscryptProvider(), 1)
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        Security.removeProvider("Conscrypt")
    }

    @Test
    fun `TLS 1_0-only Exchange 2007 server connects in a single handshake`() {
        val server = startServer("tls_ok", arrayOf("TLSv1"), EXCHANGE_2007_CIPHERS)

        exchangeClient("tls_ok").newCall(request(server)).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.handshake?.tlsVersion).isEqualTo(TlsVersion.TLS_1_0)
        }
        // [MODERN_TLS, COMPATIBLE_TLS] давал два соединения: первое без TLS 1.0 отвергалось.
        assertThat(server.acceptedConnections.get()).isEqualTo(1)
    }

    @Test
    fun `Windows Server 2003 without AES - TLS 1_0 with 3DES still connects`() {
        // Schannel Server 2003 без KB948963 знает только RC4/3DES/DES; RC4 в BoringSSL нет, 3DES — есть.
        val server = startServer("tls_ok", arrayOf("TLSv1"), arrayOf("SSL_RSA_WITH_3DES_EDE_CBC_SHA"))

        exchangeClient("tls_ok").newCall(request(server)).execute().use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.handshake?.tlsVersion).isEqualTo(TlsVersion.TLS_1_0)
            assertThat(response.handshake?.cipherSuite?.javaName).contains("3DES_EDE_CBC_SHA")
        }
    }

    @Test
    fun `modern server still negotiates TLS 1_2 or newer`() {
        val server = startServer("tls_ok", arrayOf("TLSv1.2", "TLSv1.3"))

        exchangeClient("tls_ok").newCall(request(server)).execute().use { response ->
            assertThat(response.handshake?.tlsVersion).isAnyOf(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        }
    }

    @Test
    fun `expired certificate is reported as expiry in one attempt`() {
        val server = startServer("tls_expired", arrayOf("TLSv1"), EXCHANGE_2007_CIPHERS)

        val error = assertThrows(IOException::class.java) {
            exchangeClient("tls_expired").newCall(request(server)).execute().close()
        }

        assertThat(ConnectionFailure.classify(error)).isEqualTo(ConnectionFailure.CERT_EXPIRED)
        assertThat(server.acceptedConnections.get()).isEqualTo(1)
    }

    @Test
    fun `old two-spec setup hid the real cause behind UNSUPPORTED_PROTOCOL`() {
        // Сценарий скриншота: первая попытка (TLS 1.2+) отвергнута сервером TLS 1.0, вторая (с TLS 1.0)
        // упала на сертификате, а OkHttp бросил ПЕРВОЕ исключение — исход повтора лежит в suppressed.
        val server = startServer("tls_expired", arrayOf("TLSv1"), EXCHANGE_2007_CIPHERS)
        val legacy = exchangeClient("tls_expired").newBuilder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .build()

        val error = assertThrows(IOException::class.java) { legacy.newCall(request(server)).execute().close() }

        // Schannel TLS 1.0 отвечает ServerHello 1.0 → у клиента UNSUPPORTED_PROTOCOL (скриншот);
        // BoringSSL-сервер в тесте шлёт alert protocol_version. Обе формы — отказ версии.
        assertThat(error.message).containsMatch("UNSUPPORTED_PROTOCOL|PROTOCOL_VERSION")
        assertThat(server.acceptedConnections.get()).isEqualTo(2)
        // Классификатор смотрит весь граф и называет настоящую причину.
        assertThat(ConnectionFailure.classify(error)).isEqualTo(ConnectionFailure.CERT_EXPIRED)
    }

    @Test
    fun `address missing from the certificate is a hostname error`() {
        val server = startServer("tls_dns_only", arrayOf("TLSv1"), EXCHANGE_2007_CIPHERS)

        val error = assertThrows(IOException::class.java) {
            exchangeClient("tls_dns_only").newCall(request(server)).execute().close()
        }

        assertThat(ConnectionFailure.classify(error)).isEqualTo(ConnectionFailure.CERT_HOSTNAME)
    }

    @Test
    fun `update channel stays on TLS 1_2+`() {
        val server = startServer("tls_ok", arrayOf("TLSv1"), EXCHANGE_2007_CIPHERS)

        val error = assertThrows(IOException::class.java) {
            HttpClientProvider.getSystemTrustOnlyClient().newCall(request(server)).execute().close()
        }

        assertThat(ConnectionFailure.classify(error)).isEqualTo(ConnectionFailure.TLS_VERSION)
    }

    // ---------- helpers ----------

    private fun exchangeClient(cert: String): OkHttpClient =
        HttpClientProvider.getClient(certificatePath = pemFile(cert), connectTimeout = 10, readTimeout = 10, writeTimeout = 10)

    private fun request(server: TlsServer) =
        Request.Builder().url("https://127.0.0.1:${server.port}/Microsoft-Server-ActiveSync").build()

    private fun pemFile(name: String): String {
        val file = tmp.newFile("$name-${System.nanoTime()}.pem")
        resource("$name.pem").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    private fun resource(name: String) =
        requireNotNull(HttpClientProviderTls10Test::class.java.getResourceAsStream("/certs/tls/$name")) { "нет /certs/tls/$name" }

    private fun startServer(cert: String, protocols: Array<String>, cipherSuites: Array<String>? = null): TlsServer {
        val keyStore = KeyStore.getInstance("PKCS12")
        resource("$cert.p12").use { keyStore.load(it, PASSWORD) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, PASSWORD) }.keyManagers
        val context = SSLContext.getInstance("TLS", conscryptProvider())
        context.init(keyManagers, null, null)
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        return TlsServer(socket) { raw ->
            // Как MockWebServer: TLS поверх принятого сокета с явным именем хоста. Иначе серверный
            // Conscrypt на JDK 17 узнаёт имя пира рефлексией в java.net (закрыта модулями) и рвёт рукопожатие.
            (context.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket).apply {
                useClientMode = false
                enabledProtocols = protocols
                cipherSuites?.let { enabledCipherSuites = it }
            }
        }.also { servers += it }
    }

    /** Минимальный HTTPS-сервер: отвечает 200 и считает принятые TCP-соединения. */
    private class TlsServer(
        private val socket: ServerSocket,
        private val wrap: (java.net.Socket) -> SSLSocket
    ) : AutoCloseable {
        val port: Int = socket.localPort
        val acceptedConnections = AtomicInteger()

        private val worker = thread(isDaemon = true, name = "tls-test-server-$port") {
            while (!socket.isClosed) {
                val raw = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                acceptedConnections.incrementAndGet()
                try {
                    wrap(raw).use { serve(it) }
                } catch (_: IOException) {
                    // Клиент оборвал рукопожатие (сертификат, версия) — ожидаемо в негативных тестах.
                }
            }
        }

        private fun serve(client: SSLSocket) {
            client.soTimeout = 10_000
            val input = client.inputStream.bufferedReader(Charsets.ISO_8859_1)
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
            }
            client.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            client.outputStream.flush()
        }

        override fun close() {
            socket.close()
            worker.join(5_000)
        }
    }

    private companion object {
        val PASSWORD = "changeit".toCharArray()

        /**
         * Сервер — тоже на BoringSSL (Conscrypt): SunJSSE запрещает TLS 1.0 (jdk.tls.disabledAlgorithms).
         * conscrypt-openjdk-uber есть только в runtime-classpath тестов (через Robolectric), поэтому рефлексия.
         */
        fun conscryptProvider(): java.security.Provider =
            Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null) as java.security.Provider

        /** Наборы Schannel Windows Server 2008 для TLS 1.0 (без ECDHE): RSA + AES-CBC. */
        val EXCHANGE_2007_CIPHERS = arrayOf("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA")
    }
}

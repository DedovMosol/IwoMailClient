package com.dedovmosol.iwomail.shared

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Writer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Collections
import javax.mail.MessagingException
import javax.mail.Session
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * UI2-01 / UI2-02: с той же библиотекой, что в приложении (JavaMail android-mail 1.6.7), против поддельных серверов
 * на loopback. Возможности серверов — как у реального Exchange 2007 (192.0.2.10): IMAP объявляет STARTTLS и
 * AUTH=PLAIN, POP3 — STLS и USER. Проверяется, что клиент шлёт на линию, — пароль не должен уйти открытым текстом
 * и не должен уйти серверу с сертификатом на чужое имя.
 */
class MailSessionPropertiesTest {

    private val servers = mutableListOf<ServerSocket>()

    // Robolectric-тесты этой JVM (MailApplication.initConscrypt) ставят Conscrypt первым провайдером; клиент JavaMail
    // берёт SSLContext по умолчанию, а Conscrypt на JDK 17 без имени пира виснет на рукопожатии. На Android
    // Conscrypt — штатный провайдер; здесь проверяются свойства JavaMail, поэтому — штатный JSSE.
    private val removedProviders = mutableListOf<Pair<java.security.Provider, Int>>()

    @Before fun useJsse() {
        java.security.Security.getProviders().forEachIndexed { i, p ->
            if (p.name == "Conscrypt") removedProviders += p to i + 1
        }
        removedProviders.forEach { java.security.Security.removeProvider(it.first.name) }
    }

    @After fun tearDown() {
        servers.forEach { it.close() }
        removedProviders.forEach { (p, position) -> java.security.Security.insertProviderAt(p, position) }
    }

    @Test fun `plain port always requires STARTTLS and server identity is checked`() {
        val p = MailSessionProperties.build(MailSessionProperties.Protocol.IMAP, "mail.corp.example", 143, useSsl = false, acceptAllCerts = false)

        assertThat(p.getProperty("mail.store.protocol")).isEqualTo("imap")
        assertThat(p.getProperty("mail.imap.starttls.enable")).isEqualTo("true")
        assertThat(p.getProperty("mail.imap.starttls.required")).isEqualTo("true")
        assertThat(p.getProperty("mail.imap.ssl.checkserveridentity")).isEqualTo("true")
        assertThat(p.getProperty("mail.imap.ssl.trust")).isNull()
    }

    @Test fun `legacy Exchange 2007 TLS - TLS 1_0 and 3DES are enabled, added after the default suites`() {
        // Conscrypt (BoringSSL) — как на Android; в JSSE JDK 17 наборов 3DES уже нет совсем
        val ctx = SSLContext.getInstance("TLS", com.dedovmosol.iwomail.network.TestConscrypt.provider()).apply { init(null, null, null) }

        val tls = MailSessionProperties.tlsSettings(ctx)

        val protocols = tls.protocols.split(' ')
        assertThat(protocols).contains("TLSv1")
        assertThat(protocols).contains("TLSv1.2")
        val ciphers = tls.cipherSuites.split(' ')
        val legacy = ciphers.indexOfFirst { it.endsWith("_WITH_3DES_EDE_CBC_SHA") && it.contains("_RSA_") && !it.contains("DHE") }
        assertThat(legacy).isAtLeast(0)
        // Сильные наборы по умолчанию — раньше старого
        assertThat(ciphers.subList(0, legacy)).containsAtLeastElementsIn(ctx.defaultSSLParameters.cipherSuites.filter { !it.contains("3DES") })
        // Только то, что провайдер поддерживает: иначе JavaMail упал бы на setEnabledCipherSuites
        assertThat(ctx.supportedSSLParameters.cipherSuites.toList()).containsAtLeastElementsIn(ciphers)
        assertThat(ctx.supportedSSLParameters.protocols.toList()).containsAtLeastElementsIn(protocols)
    }

    @Test fun `values not supported by the socket that will be used are dropped`() {
        val conscrypt = SSLContext.getInstance("TLS", com.dedovmosol.iwomail.network.TestConscrypt.provider()).apply { init(null, null, null) }
        val jsseSocket = SSLContext.getInstance("TLS", JSSE).apply { init(null, null, null) }.socketFactory.createSocket() as javax.net.ssl.SSLSocket

        val tls = jsseSocket.use { MailSessionProperties.tlsSettings(conscrypt, listOf(it)) }

        assertThat(jsseSocket.supportedCipherSuites.toList()).containsAtLeastElementsIn(tls.cipherSuites.split(' '))
        assertThat(jsseSocket.supportedProtocols.toList()).containsAtLeastElementsIn(tls.protocols.split(' '))
    }

    @Test fun `implicit TLS port checks identity, accept-all is an explicit opt-out of certificate checks only`() {
        val ssl = MailSessionProperties.build(MailSessionProperties.Protocol.POP3, "h", 995, useSsl = true, acceptAllCerts = false)
        assertThat(ssl.getProperty("mail.store.protocol")).isEqualTo("pop3s")
        assertThat(ssl.getProperty("mail.pop3s.ssl.enable")).isEqualTo("true")
        assertThat(ssl.getProperty("mail.pop3s.ssl.checkserveridentity")).isEqualTo("true")

        val all = MailSessionProperties.build(MailSessionProperties.Protocol.POP3, "h", 110, useSsl = false, acceptAllCerts = true)
        assertThat(all.getProperty("mail.pop3.ssl.trust")).isEqualTo("*")
        assertThat(all.getProperty("mail.pop3.ssl.checkserveridentity")).isEqualTo("false")
        // Шифрование при этом остаётся обязательным
        assertThat(all.getProperty("mail.pop3.starttls.required")).isEqualTo("true")
    }

    @Test fun `IMAP on port 143 negotiates STARTTLS before sending any credentials`() {
        val (port, wire) = fakeImap(capabilities = REAL_IMAP_CAPS)

        val error = connectFails(MailSessionProperties.Protocol.IMAP, port, useSsl = false)

        // Префикс тегов JavaMail («A», «B»…) зависит от числа созданных сессий — смотрим на команду
        assertThat(wire.first { !it.contains("CAPABILITY") }).endsWith("STARTTLS")
        assertThat(wire.joinToString("\n")).doesNotContain(SECRET)
        assertThat(wire.none { it.contains("AUTHENTICATE") || it.contains("LOGIN") }).isTrue()
        assertThat(error).isNotNull()
    }

    @Test fun `IMAP server without STARTTLS never receives the password`() {
        val (port, wire) = fakeImap(capabilities = "IMAP4rev1 AUTH=PLAIN")

        val error = connectFails(MailSessionProperties.Protocol.IMAP, port, useSsl = false)

        assertThat(error.message).contains("STARTTLS")
        assertThat(wire.joinToString("\n")).doesNotContain(SECRET)
    }

    @Test fun `POP3 on port 110 negotiates STLS before USER and PASS`() {
        val (port, wire) = fakePop3(capa = listOf("TOP", "UIDL", "SASL NTLM GSSAPI PLAIN", "USER", "STLS"))

        connectFails(MailSessionProperties.Protocol.POP3, port, useSsl = false)

        assertThat(wire).contains("STLS")
        assertThat(wire.none { it.startsWith("USER") || it.startsWith("PASS") || it.startsWith("AUTH") }).isTrue()
    }

    @Test fun `POP3 server without STLS never receives the password`() {
        val (port, wire) = fakePop3(capa = listOf("TOP", "UIDL", "USER"))

        val error = connectFails(MailSessionProperties.Protocol.POP3, port, useSsl = false)

        assertThat(error.message).contains("STLS")
        assertThat(wire.joinToString("\n")).doesNotContain(SECRET)
    }

    @Test fun `IMAPS server whose trusted certificate names another host does not get the password`() {
        // tls_dns_only: SAN только dns:mail.company.local, подключаемся к 127.0.0.1. Сертификат клиенту доверен —
        // отличается только имя: раньше (без checkserveridentity) пароль уходил такому серверу.
        val (port, wire) = fakeImapTls("tls_dns_only")
        val props = MailSessionProperties.build(MailSessionProperties.Protocol.IMAP, LOOPBACK, port, useSsl = true, acceptAllCerts = false)
        // «Сертификат доверенный»: цепочку не проверяем, проверяется только имя (ssl.socketFactory в JavaMail 1.6.7
        // оборачивает TLS-сокет повторно, поэтому доверие задано через ssl.trust)
        props["mail.imaps.ssl.trust"] = "*"

        val error = runCatching {
            Session.getInstance(props).getStore("imaps").connect(LOOPBACK, port, "u", SECRET)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(MessagingException::class.java)
        assertThat(error!!.message).contains("verify identity")
        Thread.sleep(200)
        assertThat(wire.joinToString("\n")).doesNotContain(SECRET)
    }

    @Test fun `control - without the identity check the same server receives the password`() {
        val (port, wire) = fakeImapTls("tls_dns_only")
        val props = MailSessionProperties.build(MailSessionProperties.Protocol.IMAP, LOOPBACK, port, useSsl = true, acceptAllCerts = false)
        props["mail.imaps.ssl.trust"] = "*"
        props["mail.imaps.ssl.checkserveridentity"] = "false" // как было до UI2-02

        runCatching { Session.getInstance(props).getStore("imaps").connect(LOOPBACK, port, "u", SECRET) }
        Thread.sleep(300)

        // AUTHENTICATE PLAIN: пароль в base64 внутри TLS — сервер с чужим сертификатом его получил
        val auth = wire.joinToString("\n")
        assertThat(auth).contains("AUTHENTICATE")
        assertThat(String(java.util.Base64.getDecoder().decode(wire.last().trim()))).contains(SECRET)
    }

    // ---------- поддельные серверы ----------

    private fun connectFails(protocol: MailSessionProperties.Protocol, port: Int, useSsl: Boolean): Exception {
        val props = MailSessionProperties.build(protocol, LOOPBACK, port, useSsl = useSsl, acceptAllCerts = false)
        val store = Session.getInstance(props).getStore(MailSessionProperties.storeProtocol(protocol, useSsl))
        val error = runCatching { store.connect(LOOPBACK, port, "u", SECRET) }.exceptionOrNull()
        Thread.sleep(200) // сервер дописывает журнал
        return requireNotNull(error as? Exception) { "connect must fail against the fake server" }
    }

    /** IMAP: отвечает на CAPABILITY, на STARTTLS — BAD (пароль не должен прийти и после этого). */
    private fun fakeImap(capabilities: String): Pair<Int, List<String>> =
        serve(ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) { input, out, wire ->
            out.line("* OK Microsoft Exchange Server 2007 IMAP4 service ready")
            while (true) {
                val line = input.readLine() ?: break
                wire += line
                val tag = line.substringBefore(' ')
                when (line.substringAfter(' ').substringBefore(' ').uppercase()) {
                    "CAPABILITY" -> out.line("* CAPABILITY $capabilities\r\n$tag OK CAPABILITY completed.")
                    "STARTTLS" -> { out.line("$tag BAD test stop"); break }
                    "LOGOUT" -> { out.line("* BYE\r\n$tag OK"); break }
                    "AUTHENTICATE" -> { out.line("+ "); wire += input.readLine().orEmpty(); out.line("$tag NO failed"); break }
                    else -> { out.line("$tag NO failed"); break }
                }
            }
        }

    private fun fakePop3(capa: List<String>): Pair<Int, List<String>> =
        serve(ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) { input, out, wire ->
            out.line("+OK Microsoft Exchange Server 2007 POP3 service ready")
            while (true) {
                val line = input.readLine() ?: break
                wire += line
                when (line.substringBefore(' ').uppercase()) {
                    "CAPA" -> out.line("+OK\r\n" + capa.joinToString("\r\n") + "\r\n.")
                    "STLS" -> { out.line("-ERR test stop"); break }
                    "QUIT" -> { out.line("+OK"); break }
                    else -> { out.line("-ERR failed"); break }
                }
            }
        }

    /** IMAPS с сертификатом [cert]: записывает всё, что клиент прислал после рукопожатия. */
    private fun fakeImapTls(cert: String): Pair<Int, List<String>> {
        // Явно SunJSSE: другие тесты JVM ставят Conscrypt первым провайдером, а серверный Conscrypt на JDK 17
        // без имени пира рвёт рукопожатие (см. HttpClientProviderTls10Test)
        val ctx = SSLContext.getInstance("TLS", JSSE).apply { init(keyManagers(cert), null, null) }
        val socket = ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName(LOOPBACK)) as SSLServerSocket
        return serve(socket) { input, out, wire ->
            out.line("* OK IMAP4 ready")
            while (true) {
                val line = input.readLine() ?: break
                wire += line
                val tag = line.substringBefore(' ')
                if (line.contains("CAPABILITY")) out.line("* CAPABILITY IMAP4rev1 AUTH=PLAIN\r\n$tag OK") else {
                    if (line.contains("AUTHENTICATE")) { out.line("+ "); wire += input.readLine().orEmpty() }
                    out.line("$tag NO failed"); break
                }
            }
        }
    }

    private fun serve(socket: ServerSocket, session: (BufferedReader, Writer, MutableList<String>) -> Unit): Pair<Int, List<String>> {
        servers += socket
        val wire = Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            try {
                socket.accept().use { s: Socket ->
                    s.soTimeout = 5_000
                    val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                    val out = s.getOutputStream().writer(Charsets.ISO_8859_1)
                    session(input, out, wire)
                }
            } catch (_: Exception) {
                // клиент оборвал соединение (рукопожатие, проверка имени) — ожидаемо
            }
        }
        return socket.localPort to wire
    }

    private fun Writer.line(s: String) {
        write("$s\r\n")
        flush()
    }

    private fun keyManagers(cert: String) = KeyManagerFactory.getInstance("SunX509", JSSE).apply {
        init(keyStore(cert), PASSWORD)
    }.keyManagers


    private fun keyStore(cert: String) = KeyStore.getInstance("PKCS12").apply {
        requireNotNull(MailSessionPropertiesTest::class.java.getResourceAsStream("/certs/tls/$cert.p12")).use { load(it, PASSWORD) }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val JSSE = "SunJSSE"
        const val SECRET = "fake-secret-7f3a"
        const val REAL_IMAP_CAPS = "IMAP4 IMAP4rev1 AUTH=NTLM AUTH=GSSAPI AUTH=PLAIN STARTTLS IDLE NAMESPACE LITERAL+"
        val PASSWORD = "changeit".toCharArray()
    }
}

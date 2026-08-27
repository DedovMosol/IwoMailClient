package com.dedovmosol.iwomail.network

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

/**
 * Поведенческие тесты L-5: hostname verification для режима импортированного сертификата.
 *
 * История находки: при импортированном серверном сертификате (`certificatePath != null`)
 * hostname-проверка была безусловно отключена — комбинация «верификатор { _, _ -> true } +
 * fallback на системный trust» позволяла MITM с ЛЮБЫМ валидным CA-сертификатом на чужой
 * домен. В `00e05a4` верификация была возвращена, но через внутренний (нестабильный) класс
 * библиотеки; аудит 2026-08-27 (этот коммит) заменил его на собственный
 * [Rfc6125HostnameVerifier] — только публичные JCA-апи.
 *
 * Почему не готовые варианты (всё проверено эмпирически):
 * - внутренний верификатор библиотеки — нестабильный внутренний класс;
 * - дефолтный верификатор билдера НЕ откатывается на CN при отсутствии SAN — самоподписанные
 *   сертификаты Exchange 2007 SP1/SP2 часто без SAN, все такие подключения падали бы;
 * - стандартный верификатор `javax.net.ssl.HttpsURLConnection` без инициализированного
 *   https-обработчика является заглушкой, отклоняющей ВСЕ имена (даже точное совпадение
 *   `CN=хост`), что сломало бы весь TLS (проверено на JDK 23).
 *
 * Тесты проверяют ПОВЕДЕНИЕ, а не имена классов: реальные самоподписанные X.509-сертификаты
 * (сгенерированы keytool, `app/src/test/resources/certs/`) + поддельная [SSLSession] (mockk).
 */
class HttpClientProviderL5Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ========== Режим импортированного сертификата: строгая сверка имени ==========

    @Test
    fun `pinned mode - exact CN match passes, foreign host is rejected`() {
        val client = pinnedClient("cn_ok")
        val session = sessionFor(loadCert("cn_ok"))

        assertTrue(
            "Точное совпадение имени хоста с CN должно приниматься",
            client.hostnameVerifier.verify("mail.company.local", session)
        )
        assertFalse(
            "Чужой домен с тем же сертификатом должен отклоняться (главный вектор MITM в L-5)",
            client.hostnameVerifier.verify("evil.attacker.example", session)
        )
    }

    @Test
    fun `pinned mode - CN-only cert without SAN is accepted for its own host (Exchange 2007 self-signed)`() {
        // Типичный самоподписанный сертификат старого Exchange: только CN, без SAN.
        val client = pinnedClient("cn_wrong")
        val session = sessionFor(loadCert("cn_wrong"))

        assertFalse(
            "CN=wrong.example.com не должен приниматься для чужого хоста",
            client.hostnameVerifier.verify("mail.company.local", session)
        )
        assertTrue(
            "Собственный хост сертификата с только-CN должен проходить (совместимость с Exchange 2007)",
            client.hostnameVerifier.verify("wrong.example.com", session)
        )
    }

    @Test
    fun `SAN takes precedence over CN - RFC 6125`() {
        // san_ok: CN=irrelevant.example.com, SAN dns:mail.company.local
        val client = pinnedClient("san_ok")
        val session = sessionFor(loadCert("san_ok"))

        assertTrue(
            "SAN dnsName должен приниматься",
            client.hostnameVerifier.verify("mail.company.local", session)
        )
        assertFalse(
            "Если в сертификате есть SAN, CN не должен использоваться (RFC 6125 §6.4.4)",
            client.hostnameVerifier.verify("irrelevant.example.com", session)
        )
    }

    @Test
    fun `wildcard SAN matches only the leftmost label`() {
        // san_wildcard: SAN dns:*.company.local
        val client = pinnedClient("san_wildcard")
        val session = sessionFor(loadCert("san_wildcard"))

        assertTrue(
            "*.company.local должен совпадать с mail.company.local",
            client.hostnameVerifier.verify("mail.company.local", session)
        )
        assertFalse(
            "wildcard не должен покрывать больше одного ярлыка",
            client.hostnameVerifier.verify("sub.mail.company.local", session)
        )
        assertFalse(
            "wildcard не должен совпадать с самим базовым доменом",
            client.hostnameVerifier.verify("company.local", session)
        )
    }

    @Test
    fun `IP literal is verified against iPAddress SAN (Exchange 2007 by direct IP)`() {
        // ip_ok: CN=10.0.0.5, SAN ip:10.0.0.5 — типичный доступ к старому Exchange по прямому IP
        val client = pinnedClient("ip_ok")
        val session = sessionFor(loadCert("ip_ok"))

        assertTrue(
            "Точный адрес из iPAddress SAN должен приниматься",
            client.hostnameVerifier.verify("10.0.0.5", session)
        )
        assertFalse(
            "Чужой IP должен отклоняться",
            client.hostnameVerifier.verify("10.0.0.6", session)
        )
        assertFalse(
            "DNS-имя не должно совпадать по iPAddress-записям",
            client.hostnameVerifier.verify("mail.company.local", session)
        )
    }

    @Test
    fun `cert without CN and without SAN rejects every host (fail-closed)`() {
        val client = pinnedClient("no_cn")
        val session = sessionFor(loadCert("no_cn"))

        assertFalse(
            client.hostnameVerifier.verify("mail.company.local", session)
        )
        assertFalse(
            client.hostnameVerifier.verify("anything.example", session)
        )
    }

    @Test
    fun `edge cases - case insensitivity, trailing FQDN dot, empty host`() {
        val cnOk = loadCert("cn_ok")

        assertTrue(
            "Сравнение имён не должно учитывать регистр (RFC 4343)",
            Rfc6125HostnameVerifier.matchesHostname("MAIL.Company.Local", cnOk)
        )
        assertTrue(
            "Завершающая точка FQDN должна игнорироваться",
            Rfc6125HostnameVerifier.matchesHostname("mail.company.local.", cnOk)
        )
        assertFalse(
            "Пустое имя хоста должно отклоняться",
            Rfc6125HostnameVerifier.matchesHostname("", cnOk)
        )
        assertFalse(
            Rfc6125HostnameVerifier.matchesHostname("   ", cnOk)
        )
    }

    // ========== Режимы доверия: выбор верификатора клиентом ==========

    @Test
    fun `acceptAllCerts keeps permissive verifier - explicit user choice`() {
        val certFile = materializeCert("cn_ok")
        val client = HttpClientProvider.getClient(
            acceptAllCerts = true,
            certificatePath = certFile.absolutePath
        )

        assertTrue(client.hostnameVerifier.verify("any.host.com", null))
        assertTrue(client.hostnameVerifier.verify("another.host.org", null))
    }

    @Test
    fun `system trust mode keeps strict verification via builder default`() {
        // Системный режим: импортированного серта нет — работает дефолтный верификатор билдера
        // (публичный контракт строгой проверки; публичные CA всегда выдают SAN).
        val client = HttpClientProvider.getClient(
            acceptAllCerts = false,
            certificatePath = null
        )
        val session = sessionFor(loadCert("san_ok"))

        assertTrue(client.hostnameVerifier.verify("mail.company.local", session))
        assertFalse(client.hostnameVerifier.verify("evil.attacker.example", session))
    }

    @Test
    fun `pinned and acceptAll clients expose different verifiers`() {
        val pinned = pinnedClient("cn_ok")
        val certFile = materializeCert("cn_ok")
        val permissive = HttpClientProvider.getClient(
            acceptAllCerts = true,
            certificatePath = certFile.absolutePath
        )

        assertNotSame(pinned.hostnameVerifier, permissive.hostnameVerifier)
    }

    // ========== Кэш-ключ клиента (аудит 2026-08-27) ==========

    @Test
    fun `cache key separates clients by accountId and serverUrl`() {
        val certFile = materializeCert("cn_ok")
        val pin = HttpClientProvider.CertificateInfo(
            hash = "deadbeef".repeat(8),
            cn = "mail.company.local",
            organization = "Test",
            validFrom = 0L,
            validTo = Long.MAX_VALUE
        )

        val base = HttpClientProvider.getClient(
            certificatePath = certFile.absolutePath,
            pinnedCertInfo = pin,
            accountId = 1L,
            serverUrl = "https://mail.company.local/EWS/Exchange.asmx"
        )
        val sameKey = HttpClientProvider.getClient(
            certificatePath = certFile.absolutePath,
            pinnedCertInfo = pin,
            accountId = 1L,
            serverUrl = "https://mail.company.local/EWS/Exchange.asmx"
        )
        val otherAccount = HttpClientProvider.getClient(
            certificatePath = certFile.absolutePath,
            pinnedCertInfo = pin,
            accountId = 2L,
            serverUrl = "https://mail.company.local/EWS/Exchange.asmx"
        )
        val otherUrl = HttpClientProvider.getClient(
            certificatePath = certFile.absolutePath,
            pinnedCertInfo = pin,
            accountId = 1L,
            serverUrl = "https://backup.company.local/EWS/Exchange.asmx"
        )

        assertSame(
            "Одинаковая конфигурация должна отдавать один кэшированный клиент",
            base, sameKey
        )
        assertNotSame(
            "Разные аккаунты не должны делить клиент (иначе диалог смены сертификата покажет чужой хост)",
            base, otherAccount
        )
        assertNotSame(
            "Разные серверы не должны делить клиент",
            base, otherUrl
        )
    }

    @Test
    fun `no internal library API is referenced from HttpClientProvider source`() {
        // Защита от регрессии: поведение проверено выше; ссылку на нестабильный внутренний
        // пакет библиотеки ловит отдельный статический тест по исходнику.
        val sourceFile = java.io.File(
            "src/main/java/com/dedovmosol/iwomail/network/HttpClientProvider.kt"
        )
        val source = sourceFile.takeIf { it.exists() }
            ?: java.io.File("app/src/main/java/com/dedovmosol/iwomail/network/HttpClientProvider.kt")
        assertTrue("Не удалось найти исходник HttpClientProvider.kt", source.exists())
        assertFalse(
            "HttpClientProvider не должен ссылаться на внутренний (нестабильный) пакет библиотеки",
            source.readText().contains("okhttp3.internal")
        )
    }

    // ========== Вспомогательные методы ==========

    /** Клиент с импортированным сертификатом и строгим режимом доверия (уникальный кэш-ключ на тест). */
    private fun pinnedClient(certResource: String): okhttp3.OkHttpClient {
        val certFile = materializeCert(certResource)
        return HttpClientProvider.getClient(
            acceptAllCerts = false,
            certificatePath = certFile.absolutePath
        )
    }

    /** Копирует тестовый ресурс-сертификат во временную папку (уникальный путь на тест). */
    private fun materializeCert(certResource: String): java.io.File {
        val certFile = tempFolder.newFile("${certResource}_${System.nanoTime()}.pem")
        certFile.writeBytes(loadCert(certResource).encoded)
        return certFile
    }

    /** Читает реальный X.509-сертификат из тестовых ресурсов. */
    private fun loadCert(name: String): X509Certificate {
        val stream = javaClass.getResourceAsStream("/certs/$name.pem")
            ?: error("Тестовый сертификат /certs/$name.pem не найден в ресурсах")
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    /** Поддельная сессия, отдающая реальный сертификат сервера. */
    private fun sessionFor(cert: X509Certificate): SSLSession {
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns arrayOf(cert)
        return session
    }
}

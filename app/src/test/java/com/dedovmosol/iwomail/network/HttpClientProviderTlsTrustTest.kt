package com.dedovmosol.iwomail.network

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

/**
 * NET-1 / NET-2 — поведенческие тесты доверия TLS.
 *
 * Используются реальные самоподписанные X.509 из `app/src/test/resources/certs` (та же база,
 * что и в HttpClientProviderL5Test). Plain JVM: `TrustManagerFactory`/`CertificateFactory`/
 * `MessageDigest` — чистый JCA, Android-рантайм не нужен.
 *
 * NET-1: режим «доверять всем» (opt-in) по-прежнему ПРИНИМАЕТ любой сертификат (пользователи
 *        самоподписанного Exchange 2007 SP2 на это полагаются), но больше не молча — фиксирует
 *        отпечаток принятого сертификата (прозрачность).
 * NET-2: для не-Exchange HTTPS (обновления) доверяем ТОЛЬКО системным корням — без user-CA;
 *        такой TrustManager ОТВЕРГАЕТ то, что trust-all принимает.
 */
class HttpClientProviderTlsTrustTest {

    @Before
    fun setUp() {
        HttpClientProvider.TrustAllObserver.reset()
    }

    // ---------- NET-1 ----------

    @Test
    fun trustAllManager_acceptsAnyCert_butRecordsFingerprint() {
        val cert = loadCert("cn_ok")
        val tm = HttpClientProvider.createTrustAllManager()

        // Поведение не изменилось: самоподписанный серт принимается без исключения.
        tm.checkServerTrusted(arrayOf(cert), "RSA")

        // NET-1: зафиксирован отпечаток того, что принято вслепую (реальная проводка
        // checkServerTrusted → TrustAllObserver, а не изолированный хелпер).
        val recorded = HttpClientProvider.TrustAllObserver.lastTrusted
        assertThat(recorded).isNotNull()
        assertThat(recorded!!.cn).isEqualTo("mail.company.local")
        assertThat(recorded.hash).isEqualTo(HttpClientProvider.calculateCertHash(cert))
    }

    @Test
    fun trustAllManager_emptyChain_doesNotCrashOrRecord() {
        val tm = HttpClientProvider.createTrustAllManager()
        tm.checkServerTrusted(emptyArray(), "RSA") // не бросает и не падает
        assertThat(HttpClientProvider.TrustAllObserver.lastTrusted).isNull()
    }

    // ---------- NET-2 ----------

    @Test
    fun systemOnlyManager_rejectsSelfSignedCert_unlikeTrustAll() {
        val cert = loadCert("cn_ok")

        // Контраст: trust-all принимает этот самоподписанный серт.
        HttpClientProvider.createTrustAllManager().checkServerTrusted(arrayOf(cert), "RSA")

        // system-only (без user-CA) отвергает его — настоящая валидация по системным корням.
        // Именно это защищает канал обновлений от MITM через подсунутый user-CA.
        val systemOnly = HttpClientProvider.createSystemOnlyTrustManager()
        assertThrows(CertificateException::class.java) {
            systemOnly.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test
    fun getSystemTrustOnlyClient_isCachedAndUsesStrictHostnameVerifier() {
        val c1 = HttpClientProvider.getSystemTrustOnlyClient()
        val c2 = HttpClientProvider.getSystemTrustOnlyClient()
        assertThat(c1).isSameInstanceAs(c2) // lazy → единый инстанс

        // Строгий верификатор (НЕ trust-all `{ _, _ -> true }`): чужой хост отвергается.
        val session = sessionFor(loadCert("cn_ok"))
        assertThat(c1.hostnameVerifier.verify("evil.attacker.example", session)).isFalse()
    }

    // ---------- helpers (как в HttpClientProviderL5Test) ----------

    private fun loadCert(name: String): X509Certificate {
        val stream = javaClass.getResourceAsStream("/certs/$name.pem")
            ?: error("Тестовый сертификат /certs/$name.pem не найден в ресурсах")
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    private fun sessionFor(cert: X509Certificate): SSLSession {
        val session = mockk<SSLSession>()
        every { session.peerCertificates } returns arrayOf(cert)
        return session
    }
}

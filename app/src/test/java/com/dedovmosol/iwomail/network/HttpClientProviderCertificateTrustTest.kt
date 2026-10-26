package com.dedovmosol.iwomail.network

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Доверие серверу с импортированным сертификатом (поле «Сертификат сервера») — без Certificate Pinning,
 * т.е. то, на что опирается защита, когда пользователь пиннинг выключил.
 *
 * Сертификаты — `certs/tls` (keytool): tls_ok — самоподписанный сервер; ca — УЦ организации и
 * leaf_by_ca — выданный им сертификат сервера; attacker — чужой самоподписанный на то же имя
 * 127.0.0.1; tls_expired — истёкший самоподписанный.
 */
class HttpClientProviderCertificateTrustTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() = TestConscrypt.installFirst()

    @After
    fun tearDown() = TestConscrypt.uninstall()

    @Test
    fun `imported self-signed server certificate is trusted`() {
        trustManagerFor("tls_ok").checkServerTrusted(arrayOf(cert("tls_ok")), AUTH)
    }

    @Test
    fun `imported certificate appended to a foreign chain is rejected (MITM)`() {
        // Импортированный сертификат публичный (сервер отдаёт его каждому): дописать его после своего
        // сертификата может кто угодно — подписи такой цепочки не сходятся.
        assertThrows(CertificateException::class.java) {
            trustManagerFor("tls_ok").checkServerTrusted(arrayOf(cert("attacker"), cert("tls_ok")), AUTH)
        }
    }

    @Test
    fun `server certificate issued by the imported CA is trusted`() {
        trustManagerFor("ca").checkServerTrusted(arrayOf(cert("leaf_by_ca"), cert("ca")), AUTH)
        // Корневой сертификат сервер обычно не присылает — якорь берётся из импортированного.
        trustManagerFor("ca").checkServerTrusted(arrayOf(cert("leaf_by_ca")), AUTH)
    }

    @Test
    fun `imported CA appended to a foreign chain is rejected (MITM)`() {
        assertThrows(CertificateException::class.java) {
            trustManagerFor("ca").checkServerTrusted(arrayOf(cert("attacker"), cert("ca")), AUTH)
        }
    }

    @Test
    fun `expired imported certificate reports expiry, not distrust`() {
        val error = assertThrows(CertificateException::class.java) {
            trustManagerFor("tls_expired").checkServerTrusted(arrayOf(cert("tls_expired")), AUTH)
        }
        assertThat(ConnectionFailure.classify(error)).isEqualTo(ConnectionFailure.CERT_EXPIRED)
    }

    @Test
    fun `empty chain is rejected`() {
        assertThrows(CertificateException::class.java) {
            trustManagerFor("tls_ok").checkServerTrusted(emptyArray(), AUTH)
        }
    }

    private fun trustManagerFor(name: String): X509TrustManager {
        val file = tmp.newFile("$name-${System.nanoTime()}.pem")
        resource(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return requireNotNull(HttpClientProvider.createCertificateTrustManager(file.absolutePath))
    }

    private fun cert(name: String): X509Certificate =
        resource(name).use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private fun resource(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/certs/tls/$name.pem")) { "нет /certs/tls/$name.pem" }

    private companion object {
        const val AUTH = "RSA"
    }
}

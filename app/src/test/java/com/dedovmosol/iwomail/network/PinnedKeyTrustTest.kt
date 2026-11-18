package com.dedovmosol.iwomail.network

import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.repository.CertificateTrust
import com.dedovmosol.iwomail.data.repository.ServerCertificateEndpoint
import com.dedovmosol.iwomail.network.HttpClientProvider.StartTls
import com.dedovmosol.iwomail.network.HttpClientProvider.TrustMode
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** ONB-08: доверие ровно одному ключу сервера (TOFU) и когда его предлагать. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinnedKeyTrustTest {

    private fun cert(name: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(requireNotNull(javaClass.getResourceAsStream("/certs/tls/$name.pem"))) as X509Certificate

    @After fun clear() = HttpClientProvider.CertificateChangeDetector.clearChange(5)

    @Test
    fun `the pinned key is accepted whatever the CA, name and validity`() {
        val expired = cert("tls_expired")
        val tm = HttpClientProvider.createPinnedKeyTrustManager(HttpClientProvider.extractCertificateInfo(expired), 5, "https://192.0.2.10:443")
        // Истёкший и не от доверенного УЦ — но ключ тот, которому доверились
        tm.checkServerTrusted(arrayOf(expired), "RSA")
        assertThat(HttpClientProvider.CertificateChangeDetector.getChange(5)).isNull()
    }

    @Test
    fun `another key is refused and reported as a certificate change for the account`() {
        val pinned = HttpClientProvider.extractCertificateInfo(cert("tls_ok"))
        val tm = HttpClientProvider.createPinnedKeyTrustManager(pinned, 5, "https://192.0.2.10:443")

        val error = runCatching { tm.checkServerTrusted(arrayOf(cert("attacker")), "RSA") }.exceptionOrNull()

        assertThat(error).isInstanceOf(CertificatePinMismatchException::class.java)
        val change = HttpClientProvider.CertificateChangeDetector.getChange(5)!!
        assertThat(change.oldCert).isEqualTo(pinned)
        assertThat(change.hostname).isEqualTo("https://192.0.2.10:443")
        assertThat(runCatching { tm.checkServerTrusted(arrayOf(), "RSA") }.exceptionOrNull())
            .isInstanceOf(java.security.cert.CertificateException::class.java)
    }

    private fun rootCert(name: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(requireNotNull(javaClass.getResourceAsStream("/certs/$name.pem"))) as X509Certificate

    private val acceptingTm = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    private val refusingTm = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            throw java.security.cert.CertificateException("unknown CA")
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    @Test
    fun `system verdict - trusted only with a SAN for this host`() {
        val san = rootCert("san_ok") // SAN dns:mail.company.local
        assertThat(HttpClientProvider.systemTrustOf(arrayOf(san), "RSA", "mail.company.local", acceptingTm))
            .isEqualTo(HttpClientProvider.SystemTrust.TRUSTED)
        assertThat(HttpClientProvider.systemTrustOf(arrayOf(san), "RSA", "other.example", acceptingTm))
            .isEqualTo(HttpClientProvider.SystemTrust.NAME_MISMATCH)
        // Только CN: стандартный верификатор OkHttp в системном режиме CN не смотрит — «доверяет» нельзя обещать
        assertThat(HttpClientProvider.systemTrustOf(arrayOf(rootCert("cn_ok")), "RSA", "mail.company.local", acceptingTm))
            .isEqualTo(HttpClientProvider.SystemTrust.NAME_MISMATCH)
        assertThat(HttpClientProvider.systemTrustOf(arrayOf(san), "RSA", "mail.company.local", refusingTm))
            .isEqualTo(HttpClientProvider.SystemTrust.NOT_TRUSTED)
        assertThat(HttpClientProvider.systemTrustOf(arrayOf(), "RSA", "mail.company.local", acceptingTm))
            .isEqualTo(HttpClientProvider.SystemTrust.NOT_TRUSTED)
    }

    @Test
    fun `trust mode - accept all wins, then the imported file, then the pinned key, else the system`() {
        val pin = HttpClientProvider.extractCertificateInfo(cert("tls_ok"))
        assertThat(HttpClientProvider.trustModeOf(true, "/c.pem", pin)).isEqualTo(TrustMode.ACCEPT_ALL)
        assertThat(HttpClientProvider.trustModeOf(false, "/c.pem", pin)).isEqualTo(TrustMode.IMPORTED)
        assertThat(HttpClientProvider.trustModeOf(false, null, pin)).isEqualTo(TrustMode.PINNED_KEY)
        assertThat(HttpClientProvider.trustModeOf(false, null, null)).isEqualTo(TrustMode.SYSTEM)
    }

    @Test
    fun `certificate description has both fingerprints and the issuer`() {
        val c = HttpClientProvider.describeCertificate(cert("leaf_by_ca"))
        assertThat(c.sha256.split(':')).hasSize(32)
        assertThat(c.sha1.split(':')).hasSize(20)
        assertThat(c.selfSigned).isFalse()
        assertThat(c.issuerName).isNotEmpty()
        assertThat(c.info.hash).isEqualTo(HttpClientProvider.calculateCertHash(cert("leaf_by_ca")))
    }

    @Test
    fun `trust is offered only for an unverified certificate when nothing is chosen yet`() {
        assertThat(CertificateTrust.canOfferTrust(acceptAllCerts = false, certificatePath = null, pinned = false)).isTrue()
        assertThat(CertificateTrust.canOfferTrust(acceptAllCerts = true, certificatePath = null, pinned = false)).isFalse()
        assertThat(CertificateTrust.canOfferTrust(acceptAllCerts = false, certificatePath = "/c.pem", pinned = false)).isFalse()
        assertThat(CertificateTrust.canOfferTrust(acceptAllCerts = false, certificatePath = null, pinned = true)).isFalse()

        assertThat(CertificateTrust.isUntrusted("[NET:CERT_UNTRUSTED] CertPathValidatorException")).isTrue()
        assertThat(CertificateTrust.isUntrusted("[NET:CERT_HOSTNAME] SSLPeerUnverifiedException")).isTrue()
        assertThat(CertificateTrust.isUntrusted(CertificateTrust.NOT_TRUSTED)).isTrue()
        // Смена закреплённого ключа — не «довериться впервые», а предупреждение в настройках
        assertThat(CertificateTrust.isUntrusted("[NET:CERT_PIN] mismatch")).isFalse()
        assertThat(CertificateTrust.isUntrusted("UNAUTHORIZED")).isFalse()

        val javaMail = javax.mail.MessagingException("STARTTLS failure",
            javax.net.ssl.SSLHandshakeException("x").apply { initCause(java.security.cert.CertPathValidatorException("Trust anchor for certification path not found.")) })
        assertThat(CertificateTrust.isUntrusted(javaMail)).isTrue()
        val changed = javax.mail.MessagingException("x", javax.net.ssl.SSLHandshakeException("y").apply { initCause(CertificatePinMismatchException("pin")) })
        assertThat(CertificateTrust.isUntrusted(changed)).isFalse()
        assertThat(CertificateTrust.isUntrusted(javax.mail.AuthenticationFailedException("LOGIN failed"))).isFalse()
    }

    @Test
    fun `certificate is fetched from the same address and port as the client uses`() {
        assertThat(ServerCertificateEndpoint.of(AccountType.EXCHANGE, "192.0.2.10", 443, true))
            .isEqualTo(ServerCertificateEndpoint("192.0.2.10", 443, StartTls.NONE))
        // Порт в адресе важнее порта аккаунта, схема не мешает
        assertThat(ServerCertificateEndpoint.of(AccountType.EXCHANGE, "https://mail.corp.example:8443/", 443, true))
            .isEqualTo(ServerCertificateEndpoint("mail.corp.example", 8443, StartTls.NONE))
        assertThat(ServerCertificateEndpoint.of(AccountType.EXCHANGE, "192.0.2.10", 80, false)).isNull()
        assertThat(ServerCertificateEndpoint.of(AccountType.IMAP, "192.0.2.10", 143, false))
            .isEqualTo(ServerCertificateEndpoint("192.0.2.10", 143, StartTls.IMAP))
        assertThat(ServerCertificateEndpoint.of(AccountType.POP3, "192.0.2.10", 110, false))
            .isEqualTo(ServerCertificateEndpoint("192.0.2.10", 110, StartTls.POP3))
        assertThat(ServerCertificateEndpoint.of(AccountType.POP3, "192.0.2.10", 995, true))
            .isEqualTo(ServerCertificateEndpoint("192.0.2.10", 995, StartTls.NONE))
    }
}

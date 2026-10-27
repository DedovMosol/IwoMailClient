package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Пин сертификата для основного и резервного сервера аккаунта (Dual-URL). */
class CertificatePinsTest {

    private val account = AccountEntity(
        email = "user@corp.example",
        displayName = "User",
        serverUrl = "mail.corp.example",
        alternateServerUrl = "10.0.0.5:8443",
        username = "user",
        incomingPort = 443,
        pinnedCertificateHash = "primary-spki",
        pinnedCertificateCN = "mail.corp.example",
        certificatePinningEnabled = true
    )

    @Test
    fun `backup server uses the primary pin until its own is confirmed`() {
        assertThat(CertificatePins.forEndpoint(account, useAlternate = false)?.hash).isEqualTo("primary-spki")
        assertThat(CertificatePins.forEndpoint(account, useAlternate = true)?.hash).isEqualTo("primary-spki")

        val confirmed = account.copy(alternatePinnedCertificateHash = "backup-spki", alternatePinnedCertificateCN = "10.0.0.5")
        assertThat(CertificatePins.forEndpoint(confirmed, useAlternate = true)?.hash).isEqualTo("backup-spki")
        assertThat(CertificatePins.forEndpoint(confirmed, useAlternate = true)?.cn).isEqualTo("10.0.0.5")
        // Пин основного не трогается подтверждением резервного.
        assertThat(CertificatePins.forEndpoint(confirmed, useAlternate = false)?.hash).isEqualTo("primary-spki")
    }

    @Test
    fun `no pins when pinning is off or nothing is pinned`() {
        val disabled = account.copy(certificatePinningEnabled = false, alternatePinnedCertificateHash = "backup-spki")
        assertThat(CertificatePins.forEndpoint(disabled, useAlternate = true)).isNull()
        assertThat(CertificatePins.forEndpoint(account.copy(pinnedCertificateHash = null), useAlternate = false)).isNull()
    }

    @Test
    fun `certificate change is attributed to the server it came from`() {
        assertThat(CertificatePins.isAlternateEndpoint(account, "https://10.0.0.5:8443")).isTrue()
        assertThat(CertificatePins.isAlternateEndpoint(account, "https://mail.corp.example:443")).isFalse()
        // Порт резервного — из его адреса: тот же хост на порту основного — другой сервер.
        assertThat(CertificatePins.isAlternateEndpoint(account, "https://10.0.0.5:443")).isFalse()
        assertThat(CertificatePins.isAlternateEndpoint(account.copy(alternateServerUrl = null), "https://10.0.0.5:8443")).isFalse()
        // Резервный = основной (с точностью до схемы) — это основной.
        val same = account.copy(alternateServerUrl = "https://mail.corp.example/owa")
        assertThat(CertificatePins.isAlternateEndpoint(same, "https://mail.corp.example:443")).isFalse()
    }

    @Test
    fun `change on a server that is no longer configured belongs to neither`() {
        assertThat(CertificatePins.endpointOf(account, "https://mail.corp.example:443")).isEqualTo(CertificatePins.Endpoint.PRIMARY)
        assertThat(CertificatePins.endpointOf(account, "https://10.0.0.5:8443")).isEqualTo(CertificatePins.Endpoint.ALTERNATE)
        // Адрес резервного сменили после обнаружения: сертификат прежнего сервера не должен стать пином основного.
        val moved = account.copy(alternateServerUrl = "10.0.0.6:8443")
        assertThat(CertificatePins.endpointOf(moved, "https://10.0.0.5:8443")).isNull()
        assertThat(CertificatePins.endpointOf(moved, "https://mail.corp.example:443")).isEqualTo(CertificatePins.Endpoint.PRIMARY)
        // Сменились схема или порт аккаунта — тоже уже другой сервер.
        assertThat(CertificatePins.endpointOf(account.copy(useSSL = false), "https://mail.corp.example:443")).isNull()
        assertThat(CertificatePins.endpointOf(account.copy(incomingPort = 8080), "https://mail.corp.example:443")).isNull()
        assertThat(CertificatePins.endpointOf(account.copy(alternateServerUrl = " "), "https://10.0.0.5:8443")).isNull()
    }

    @Test
    fun `changing the backup address drops only its pin`() {
        val confirmed = account.copy(alternatePinnedCertificateHash = "backup-spki", alternatePinnedCertificateValidTo = 5L)
        val reset = CertificatePins.withoutAlternatePin(confirmed)
        assertThat(reset.alternatePinnedCertificateHash).isNull()
        assertThat(reset.alternatePinnedCertificateValidTo).isNull()
        assertThat(reset.pinnedCertificateHash).isEqualTo("primary-spki")
    }
}

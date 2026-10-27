package com.dedovmosol.iwomail.network

import com.dedovmosol.iwomail.network.HttpClientProvider.CertificateChangeDetector
import com.dedovmosol.iwomail.network.HttpClientProvider.CertificateInfo
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Обнаруженные смены сертификата — наблюдаемое состояние: экран настроек обновляется после «Принять»
 * и «Отключить» сам (раньше ключом перерисовки был пин основного, и принятый сертификат резервного
 * сервера оставлял предупреждение на экране).
 */
class CertificateChangeDetectorTest {

    private val pinned = CertificateInfo("pinned-spki", "mail.corp.example", "Corp", 1L, 2L)
    private val presented = CertificateInfo("other-spki", "mail.corp.example", "Corp", 3L, 4L)

    @After
    fun tearDown() {
        CertificateChangeDetector.clearChange(ACCOUNT)
        CertificateChangeDetector.clearChange(OTHER_ACCOUNT)
    }

    @Test
    fun `repeated handshakes with the same certificate do not emit a new state`() {
        CertificateChangeDetector.onCertificateChanged(ACCOUNT, pinned, presented, URL)
        val first = CertificateChangeDetector.changes.value

        CertificateChangeDetector.onCertificateChanged(ACCOUNT, pinned, presented, URL)

        // Тот же экземпляр: StateFlow не эмитит, время первого обнаружения сохранено.
        assertThat(CertificateChangeDetector.changes.value).isSameInstanceAs(first)
        assertThat(CertificateChangeDetector.getChange(ACCOUNT)?.newCert).isEqualTo(presented)
    }

    @Test
    fun `another certificate replaces the change, clearing removes only that account`() {
        CertificateChangeDetector.onCertificateChanged(ACCOUNT, pinned, presented, URL)
        CertificateChangeDetector.onCertificateChanged(OTHER_ACCOUNT, pinned, presented, URL)
        val rotated = presented.copy(hash = "rotated-spki")

        CertificateChangeDetector.onCertificateChanged(ACCOUNT, pinned, rotated, URL)
        assertThat(CertificateChangeDetector.getChange(ACCOUNT)?.newCert?.hash).isEqualTo("rotated-spki")

        CertificateChangeDetector.clearChange(ACCOUNT)
        assertThat(CertificateChangeDetector.changes.value).doesNotContainKey(ACCOUNT)
        assertThat(CertificateChangeDetector.getChange(OTHER_ACCOUNT)).isNotNull()
    }

    private companion object {
        const val ACCOUNT = 9_001L
        const val OTHER_ACCOUNT = 9_002L
        const val URL = "https://mail.corp.example:443"
    }
}

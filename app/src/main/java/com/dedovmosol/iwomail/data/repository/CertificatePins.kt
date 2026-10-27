package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.eas.EasClient
import com.dedovmosol.iwomail.network.HttpClientProvider.CertificateInfo

/**
 * Пины сертификатов по серверам аккаунта (Dual-URL).
 *
 * У основного — пин импортированного сертификата (или принятого пользователем нового). У резервного —
 * свой, подтверждённый при первом подключении к нему; до подтверждения резервный проверяется по пину
 * основного (один сертификат на оба имени, SAN, подходит сразу). Раньше пин был общий: при разных
 * сертификатах принятие сертификата одного сервера ломало другой, и предупреждение о смене сертификата
 * появлялось при каждом переключении.
 */
internal object CertificatePins {

    /** Пин для клиента основного или резервного сервера; null — Certificate Pinning выключен. */
    fun forEndpoint(account: AccountEntity, useAlternate: Boolean): CertificateInfo? {
        if (!account.certificatePinningEnabled) return null
        val primary = account.primaryPin() ?: return null
        return if (useAlternate) account.alternatePin() ?: primary else primary
    }

    enum class Endpoint { PRIMARY, ALTERNATE }

    /**
     * Сервер аккаунта, на котором обнаружен сертификат: CertificateChange.hostname — базовый URL клиента
     * (EasClient.normalizeUrl с портом и схемой аккаунта, как в AccountRepository.buildEasClient).
     * null — ни один из текущих: адрес, порт или схема сменились после обнаружения. Адреса совпадают —
     * основной.
     */
    fun endpointOf(account: AccountEntity, clientBaseUrl: String): Endpoint? {
        fun baseUrl(raw: String) = EasClient.normalizeUrl(raw, account.incomingPort, account.useSSL)
        if (baseUrl(account.serverUrl).equals(clientBaseUrl, ignoreCase = true)) return Endpoint.PRIMARY
        val alternate = account.alternateServerUrl?.takeIf { it.isNotBlank() } ?: return null
        return if (baseUrl(alternate).equals(clientBaseUrl, ignoreCase = true)) Endpoint.ALTERNATE else null
    }

    fun isAlternateEndpoint(account: AccountEntity, clientBaseUrl: String): Boolean =
        endpointOf(account, clientBaseUrl) == Endpoint.ALTERNATE

    /** Аккаунт без пина резервного (сменился адрес резервного — другой сервер подтверждается заново). */
    fun withoutAlternatePin(account: AccountEntity): AccountEntity = account.copy(
        alternatePinnedCertificateHash = null,
        alternatePinnedCertificateCN = null,
        alternatePinnedCertificateOrg = null,
        alternatePinnedCertificateValidFrom = null,
        alternatePinnedCertificateValidTo = null
    )

    private fun AccountEntity.primaryPin(): CertificateInfo? = pinnedCertificateHash?.let {
        CertificateInfo(
            hash = it,
            cn = pinnedCertificateCN ?: "Unknown",
            organization = pinnedCertificateOrg ?: "Unknown",
            validFrom = pinnedCertificateValidFrom ?: 0L,
            validTo = pinnedCertificateValidTo ?: 0L
        )
    }

    private fun AccountEntity.alternatePin(): CertificateInfo? = alternatePinnedCertificateHash?.let {
        CertificateInfo(
            hash = it,
            cn = alternatePinnedCertificateCN ?: "Unknown",
            organization = alternatePinnedCertificateOrg ?: "Unknown",
            validFrom = alternatePinnedCertificateValidFrom ?: 0L,
            validTo = alternatePinnedCertificateValidTo ?: 0L
        )
    }
}

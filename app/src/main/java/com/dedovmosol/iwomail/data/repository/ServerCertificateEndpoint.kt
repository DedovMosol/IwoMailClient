package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.network.HttpClientProvider.StartTls

/**
 * Куда подключаться за сертификатом основного сервера аккаунта: тот же адрес и порт, что у клиента
 * (EAS — ServerAddress, как EasClient.normalizeUrl; IMAP/POP3 — сервер и входящий порт, без SSL — через STARTTLS/STLS).
 */
data class ServerCertificateEndpoint(val host: String, val port: Int, val startTls: StartTls) {
    companion object {
        /** Сервер без TLS (Exchange по http): сертификата нет. */
        const val NO_TLS = "CERTIFICATE_NO_TLS"

        fun of(account: AccountEntity): ServerCertificateEndpoint? = of(
            AccountType.fromStored(account.accountType), account.serverUrl, account.incomingPort, account.useSSL
        )

        fun of(type: AccountType, serverUrl: String, port: Int, useSsl: Boolean): ServerCertificateEndpoint? = when (type) {
            // Порт из адреса (host:port) важнее порта аккаунта — как в запросах (ServerAddress)
            AccountType.EXCHANGE -> if (!useSsl) null else com.dedovmosol.iwomail.eas.ServerAddress.parse(serverUrl, port)?.let {
                ServerCertificateEndpoint(it.host, it.port, StartTls.NONE)
            }
            AccountType.IMAP -> com.dedovmosol.iwomail.eas.ServerAddress.parse(serverUrl, port)?.let {
                ServerCertificateEndpoint(it.host, it.port, if (useSsl) StartTls.NONE else StartTls.IMAP)
            }
            AccountType.POP3 -> com.dedovmosol.iwomail.eas.ServerAddress.parse(serverUrl, port)?.let {
                ServerCertificateEndpoint(it.host, it.port, if (useSsl) StartTls.NONE else StartTls.POP3)
            }
        }
    }
}

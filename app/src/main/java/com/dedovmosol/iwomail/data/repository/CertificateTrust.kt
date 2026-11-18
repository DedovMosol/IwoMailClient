package com.dedovmosol.iwomail.data.repository

import com.dedovmosol.iwomail.network.CertificatePinMismatchException
import com.dedovmosol.iwomail.network.ConnectionFailure

/**
 * ONB-08: когда предлагать «доверять сертификату сервера по отпечатку».
 *
 * Только если сертификат не принят системой (внутренний УЦ, другое имя — подключение по IP, истёк), а пользователь
 * ещё ничего не выбрал: нет «Принимать все», импортированного сертификата и закреплённого ключа. Смена уже
 * закреплённого ключа — другой сценарий (предупреждение о смене в настройках аккаунта), здесь не предлагается.
 */
object CertificateTrust {

    /** Ошибка входа IMAP/POP3: сертификат не подтверждён, можно довериться по отпечатку. */
    const val NOT_TRUSTED = "CERTIFICATE_NOT_TRUSTED"

    private val UNTRUSTED = setOf(ConnectionFailure.CERT_UNTRUSTED, ConnectionFailure.CERT_HOSTNAME, ConnectionFailure.CERT_EXPIRED)

    fun canOfferTrust(acceptAllCerts: Boolean, certificatePath: String?, pinned: Boolean): Boolean =
        !acceptAllCerts && certificatePath == null && !pinned

    /** Ошибка EAS/EWS ([ConnectionFailure] в тексте) или код [NOT_TRUSTED]. */
    fun isUntrusted(errorMessage: String?): Boolean =
        errorMessage == NOT_TRUSTED || ConnectionFailure.fromMessage(errorMessage) in UNTRUSTED

    /** Исключение JavaMail: в цепочке причин — отказ проверки сертификата, но не смена закреплённого ключа. */
    fun isUntrusted(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(10).toList()
        if (chain.any { it is CertificatePinMismatchException }) return false
        return chain.any {
            it is java.security.cert.CertPathValidatorException ||
                it is java.security.cert.CertificateExpiredException ||
                it is javax.net.ssl.SSLPeerUnverifiedException ||
                it.message?.contains("verify identity of server", ignoreCase = true) == true ||
                it.message?.contains("Trust anchor", ignoreCase = true) == true
        }
    }
}

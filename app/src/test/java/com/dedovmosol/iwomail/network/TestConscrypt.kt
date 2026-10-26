package com.dedovmosol.iwomail.network

import java.security.Provider
import java.security.Security

/**
 * Bundled Conscrypt (BoringSSL) в юнит-тестах — как в приложении: MailApplication.initConscrypt ставит
 * его первым провайдером (TrustManagerFactory, CertificateFactory, SSLContext — из него). Тесты не
 * зависят от того, успел ли это сделать Robolectric-тест в той же JVM.
 *
 * conscrypt-openjdk-uber есть только в runtime-classpath тестов (приходит с Robolectric), поэтому рефлексия.
 */
internal object TestConscrypt {
    fun provider(): Provider =
        Class.forName("org.conscrypt.Conscrypt").getMethod("newProvider").invoke(null) as Provider

    fun installFirst() {
        Security.insertProviderAt(provider(), 1)
    }

    fun uninstall() {
        Security.removeProvider("Conscrypt")
    }
}

package com.dedovmosol.iwomail.network

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.net.ssl.HostnameVerifier

/**
 * Тесты L-5: hostname verification для pinned-сертификата.
 *
 * Проверяем, что при наличии certificatePath (и acceptAllCerts=false)
 * hostname verifier НЕ является "accept all" ({ _, _ -> true }),
 * а при acceptAllCerts=true — остаётся "accept all" (явный выбор пользователя).
 *
 * Это сужает MITM-окно: раньше при импортированном серте hostname-проверка
 * была безусловно отключена, и MITM с любым валидным CA-сертом проходил.
 */
class HttpClientProviderL5Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `hostname verifier is OkHostnameVerifier when certificatePath is set and acceptAllCerts is false`() {
        // Arrange: создаём временный файл сертификата (содержимое не важно для verifier)
        val certFile = tempFolder.newFile("test_cert.cer")
        certFile.writeText("dummy")

        // Act: создаём клиент с pinned-сертом
        val client = HttpClientProvider.getClient(
            acceptAllCerts = false,
            certificatePath = certFile.absolutePath
        )

        // Assert: hostname verifier должен быть OkHostnameVerifier (RFC 2818),
        // а не "accept all" лямбда. Проверяем, что это не анонимная лямбда.
        val verifier = client.hostnameVerifier
        val verifierClass = verifier.javaClass.name
        assertTrue(
            "Expected OkHostnameVerifier, got $verifierClass",
            verifierClass.contains("OkHostnameVerifier")
        )
    }

    @Test
    fun `hostname verifier is accept-all when acceptAllCerts is true`() {
        // Arrange: создаём временный файл сертификата
        val certFile = tempFolder.newFile("test_cert.cer")
        certFile.writeText("dummy")

        // Act: создаём клиент с acceptAllCerts=true (явный небезопасный режим)
        val client = HttpClientProvider.getClient(
            acceptAllCerts = true,
            certificatePath = certFile.absolutePath
        )

        // Assert: hostname verifier должен быть "accept all" (лямбда { _, _ -> true })
        val verifier = client.hostnameVerifier
        // Лямбда { _, _ -> true } всегда возвращает true для любого хоста
        assertTrue(verifier.verify("any.host.com", null))
        assertTrue(verifier.verify("another.host.org", null))
    }

    @Test
    fun `hostname verifier is default when no certificatePath and acceptAllCerts is false`() {
        // Act: базовый клиент без сертификатов
        val client = HttpClientProvider.getClient(
            acceptAllCerts = false,
            certificatePath = null
        )

        // Assert: hostname verifier должен быть системным по умолчанию (OkHostnameVerifier)
        val verifier = client.hostnameVerifier
        val verifierClass = verifier.javaClass.name
        assertTrue(
            "Expected default OkHostnameVerifier, got $verifierClass",
            verifierClass.contains("OkHostnameVerifier")
        )
    }

    @Test
    fun `certificate client and accept-all client have different hostname verifiers`() {
        // Arrange: временный файл сертификата
        val certFile = tempFolder.newFile("test_cert.cer")
        certFile.writeText("dummy")

        // Act: создаём два клиента
        val pinnedClient = HttpClientProvider.getClient(
            acceptAllCerts = false,
            certificatePath = certFile.absolutePath
        )
        val trustAllClient = HttpClientProvider.getClient(
            acceptAllCerts = true,
            certificatePath = certFile.absolutePath
        )

        // Assert: verifier'ы разные
        assertNotSame(pinnedClient.hostnameVerifier, trustAllClient.hostnameVerifier)
    }
}
package com.dedovmosol.iwomail.eas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Тесты M-3: NTLM-лок в EwsClient.
 *
 * Проверяем, что ntlmLock — per-instance (не глобальный статический).
 * Это гарантирует, что сериализация EWS-NTLM запросов происходит
 * только в пределах одного аккаунта, а не блокирует другие аккаунты.
 */
class EwsClientNtlmLockTest {

    @Test
    fun `ntlmLock is per-instance, not static`() {
        // Arrange: рефлексия для доступа к приватному полю ntlmLock
        val ntlmLockField = EwsClient::class.java.getDeclaredField("ntlmLock")
        ntlmLockField.isAccessible = true

        // Assert: поле не статическое
        assertFalse(
            "ntlmLock должен быть per-instance, не static",
            Modifier.isStatic(ntlmLockField.modifiers)
        )
    }

    @Test
    fun `two EwsClient instances have different ntlmLock instances`() {
        // Arrange: создаём два EwsClient с разными параметрами
        val client1 = createTestEwsClient("https://server1.example.com")
        val client2 = createTestEwsClient("https://server2.example.com")

        // Act: получаем ntlmLock через рефлексию
        val ntlmLockField = EwsClient::class.java.getDeclaredField("ntlmLock")
        ntlmLockField.isAccessible = true
        val lock1 = ntlmLockField.get(client1)
        val lock2 = ntlmLockField.get(client2)

        // Assert: разные экземпляры (per-instance)
        assertNotSame(
            "ntlmLock должен быть уникальным для каждого EwsClient (per-account)",
            lock1,
            lock2
        )
    }

    private fun createTestEwsClient(url: String): EwsClient {
        // Создаём EwsClient с минимальными зависимостями для теста
        // Нам не нужен реальный NtlmAuthenticator или OkHttpClient
        val ntlmAuth = NtlmAuthenticator("DOMAIN", "user", "pass")
        val httpClient = okhttp3.OkHttpClient()
        return EwsClient(
            ewsUrl = url,
            username = "user",
            password = "pass",
            domain = "DOMAIN",
            httpClient = httpClient,
            ntlmAuth = ntlmAuth
        )
    }
}
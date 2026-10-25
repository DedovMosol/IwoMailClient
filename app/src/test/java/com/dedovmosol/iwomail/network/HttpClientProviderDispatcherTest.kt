package com.dedovmosol.iwomail.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Регрессия многоящичного Direct Push.
 *
 * EAS Ping — долгий async long-poll (один на ящик, держится весь heartbeat). OkHttp по умолчанию
 * ограничивает async-вызовы до maxRequestsPerHost=5, поэтому 6+ ящиков на одном Exchange-хосте
 * голодали бы: лишние Ping бесконечно ждут в очереди диспетчера, а занятые слоты тормозят Sync.
 *
 * Тест фиксирует: (1) per-host лимит поднят над дефолтными 5; (2) ВСЕ варианты клиентов делят
 * один и тот же Dispatcher — значит лимит на хост считается глобально по всем ящикам и операциям,
 * а не изолированно на каждый клиент.
 */
class HttpClientProviderDispatcherTest {

    @Test
    fun `dispatcher raises limits for multi-mailbox push`() {
        val d = HttpClientProvider.getClient().dispatcher
        assertThat(d.maxRequestsPerHost).isEqualTo(32)
        assertThat(d.maxRequests).isEqualTo(128)
    }

    @Test
    fun `per-host limit exceeds OkHttp default of 5`() {
        // Дефолт (5) голодал бы при >5 долгих Ping на одном хосте — это и чиним.
        assertThat(HttpClientProvider.getClient().dispatcher.maxRequestsPerHost).isGreaterThan(5)
    }

    @Test
    fun `all client variants share one dispatcher`() {
        val base = HttpClientProvider.getClient().dispatcher
        val trustAll = HttpClientProvider.getClient(acceptAllCerts = true).dispatcher
        val attachment = HttpClientProvider.getAttachmentClient().dispatcher
        val systemOnly = HttpClientProvider.getSystemTrustOnlyClient().dispatcher
        assertThat(trustAll).isSameInstanceAs(base)
        assertThat(attachment).isSameInstanceAs(base)
        assertThat(systemOnly).isSameInstanceAs(base)
    }
}

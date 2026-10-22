package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SYNC-6: стабильный Message-ID. Детерминированность при одинаковом clientId гарантирует,
 * что повторная (retry) отправка одной записи не создаёт новый идентификатор письма.
 */
class EasSendIdentityTest {

    @Test
    fun stableClientId_yieldsDeterministicMessageId() {
        assertThat(buildStableMessageId("abc123", "dev42")).isEqualTo("<abc123@dev42>")
    }

    @Test
    fun sameClientId_isStableAcrossCalls() {
        // Главное свойство для retry: один clientId → один и тот же Message-ID.
        assertThat(buildStableMessageId("cid", "d")).isEqualTo(buildStableMessageId("cid", "d"))
    }

    @Test
    fun nullClientId_isFreshAndWellFormed() {
        val m = buildStableMessageId(null, "dev")
        assertThat(m).startsWith("<")
        assertThat(m).endsWith("@dev>")
    }

    @Test
    fun nullClientId_isUniquePerCall() {
        // Без clientId (интерактивная отправка) каждый Message-ID уникален — не склеиваем письма.
        assertThat(buildStableMessageId(null, "dev")).isNotEqualTo(buildStableMessageId(null, "dev"))
    }

    /**
     * SYNC-6 (DRY/wiring): оба пути отправки — с вложениями (EasAttachmentService) и без
     * (EasEmailService.buildMimeMessageBytes) — строят Message-ID через ЭТОТ же buildStableMessageId,
     * а fallback-токен берут из единого freshSendToken(). Тест фиксирует эти свойства источника.
     */
    @Test
    fun freshSendToken_isUniqueAndHasNoMessageIdDelimiters() {
        val a = freshSendToken()
        val b = freshSendToken()
        assertThat(a).isNotEqualTo(b)
        // Токен — «сырой» идентификатор без угловых скобок/@; обёртку добавляет buildStableMessageId.
        assertThat(a).doesNotContain("<")
        assertThat(a).doesNotContain(">")
        assertThat(a).doesNotContain("@")
    }

    @Test
    fun stableMessageId_usesFreshTokenShapeWhenClientIdAbsent() {
        // Когда clientId не задан, идентификатор = "<" + freshSendToken-подобный токен + "@dev>".
        val m = buildStableMessageId(null, "dev")
        val inner = m.removePrefix("<").removeSuffix("@dev>")
        // Формат freshSendToken(): "<millis>.<nanos>" — две десятичные части через точку.
        assertThat(inner).matches("\\d+\\.\\d+")
    }
}

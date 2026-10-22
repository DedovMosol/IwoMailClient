package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DATA-6: `isRetryable` не должен повторять терминальные ошибки (аутентификация/политика/очистка) —
 * повтор бесполезен и вреден. Проверяем приоритет NON_RETRYABLE над retryable-ключевыми словами.
 */
class EasResultRetryTest {

    @Test
    fun terminalAuthErrors_areNotRetryable() {
        assertThat(EasResult.Error("UNAUTHORIZED").isRetryable()).isFalse()
        assertThat(EasResult.Error("ACCESS_DENIED").isRetryable()).isFalse()
        assertThat(EasResult.Error("PROVISION_REQUIRED").isRetryable()).isFalse()
        assertThat(EasResult.Error("REMOTE_WIPE").isRetryable()).isFalse()
    }

    @Test
    fun terminalMarkerWins_evenIfMessageContainsRetryableKeyword() {
        // Содержит "error", но терминальный маркер должен победить и запретить повтор.
        assertThat(EasResult.Error("UNAUTHORIZED: auth error").isRetryable()).isFalse()
    }

    @Test
    fun transientErrors_remainRetryable() {
        assertThat(EasResult.Error("markAsRead failed: Status=3").isRetryable()).isTrue()
        assertThat(EasResult.Error("HTTP 503: error").isRetryable()).isTrue()
    }

    @Test
    fun success_isNotRetryable() {
        assertThat(EasResult.Success(Unit).isRetryable()).isFalse()
    }
}

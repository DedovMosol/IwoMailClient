package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Тесты защиты цикла синхронизации от зацикливания ([EmailSyncService.shouldContinueSync]).
 *
 * Покрывает два критичных сценария:
 * 1. Сервер возвращает тот же syncKey ≥5 раз подряд → принудительная остановка
 * 2. Сервер возвращает moreAvailable=true без данных ≥5 раз подряд → принудительная остановка
 *
 * Без этой защиты клиент мог бесконечно крутиться в цикле синхронизации,
 * истощая батарею и трафик (реальный баг на некоторых Exchange-серверах).
 */
class EmailSyncServiceTest {

    @Test
    fun `shouldContinueSync returns true for normal sync progress`() {
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 0, emptyDataCount = 0)).isTrue()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 1, emptyDataCount = 0)).isTrue()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 0, emptyDataCount = 2)).isTrue()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 4, emptyDataCount = 4)).isTrue()
    }

    @Test
    fun `shouldContinueSync returns false when syncKey repeats 5 times`() {
        // Критичный случай: сервер зациклился, возвращает тот же syncKey
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 5, emptyDataCount = 0)).isFalse()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 6, emptyDataCount = 0)).isFalse()
    }

    @Test
    fun `shouldContinueSync returns false when no data for 5 iterations`() {
        // Критичный случай: сервер говорит "есть ещё данные", но не присылает их
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 0, emptyDataCount = 5)).isFalse()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 0, emptyDataCount = 7)).isFalse()
    }

    @Test
    fun `shouldContinueSync returns false when both guards trigger`() {
        // Оба счётчика превысили порог
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 5, emptyDataCount = 5)).isFalse()
    }

    @Test
    fun `shouldContinueSync boundary values`() {
        // Граничные значения: 4 = продолжаем, 5 = стоп
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 4, emptyDataCount = 0)).isTrue()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 5, emptyDataCount = 0)).isFalse()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 0, emptyDataCount = 4)).isTrue()
        assertThat(EmailSyncService.shouldContinueSync(sameKeyCount = 0, emptyDataCount = 5)).isFalse()
    }
}

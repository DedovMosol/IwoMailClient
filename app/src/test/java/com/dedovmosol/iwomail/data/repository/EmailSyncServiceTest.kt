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

    // ─── Дедупликация черновиков (цель «серверные черновики») ───
    // Эвристика [EmailSyncService.isDraftDuplicate] защищает от дублей, когда
    // createDraftMime() создал черновик на сервере, но ItemId не был извлечён.

    @Test
    fun `isDraftDuplicate detects same subject within time window`() {
        // Тот же субъект, разница дат 30 с (< 120 с) — дубликат.
        val server = listOf("Отчёт" to 1_000_000L)
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", 1_030_000L, server)).isTrue()
    }

    @Test
    fun `isDraftDuplicate rejects same subject outside time window`() {
        // Тот же субъект, но разница дат 5 минут — разные черновики (не дубль).
        val server = listOf("Отчёт" to 1_000_000L)
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", 1_300_000L, server)).isFalse()
    }

    @Test
    fun `isDraftDuplicate rejects different subject within time window`() {
        // Разные темы в одно и то же время — разные черновики.
        val server = listOf("Отчёт" to 1_000_000L)
        assertThat(EmailSyncService.isDraftDuplicate("Встреча", 1_000_000L, server)).isFalse()
    }

    @Test
    fun `isDraftDuplicate boundary at window edge`() {
        // Граница окна: ровно 120 с = НЕ дубль (условие строго <).
        val server = listOf("Отчёт" to 0L)
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", EmailSyncService.DRAFT_DEDUP_WINDOW_MS, server)).isFalse()
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", EmailSyncService.DRAFT_DEDUP_WINDOW_MS - 1, server)).isTrue()
    }

    @Test
    fun `isDraftDuplicate with empty server list and negative time diff`() {
        // Пустой список серверных черновиков — никогда не дубль.
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", 500L, emptyList())).isFalse()
        // Отрицательная разница дат (локальный старше серверного) — модуль работает.
        val server = listOf("Отчёт" to 2_000_000L)
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", 1_950_000L, server)).isTrue()
    }

    @Test
    fun `isDraftDuplicate matches any server draft`() {
        // Совпадение с любым из нескольких серверных черновиков.
        val server = listOf(
            "Встреча" to 100L,
            "Отчёт" to 500L,
            "Планы" to 900L
        )
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", 550L, server)).isTrue()
        // 5 000 000 мс далеко за окном 120 с — совпадений быть не должно.
        assertThat(EmailSyncService.isDraftDuplicate("Отчёт", 5_000_000L, server)).isFalse()
    }
}

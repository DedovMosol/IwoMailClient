package com.dedovmosol.iwomail.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Тесты полноты локализации ошибок (цель релиза «локализация всех ошибок»).
 *
 * [NotificationStrings.localizeError] — единая точка перевода ошибок из
 * репозиториев и EAS-клиента. Типовой дефект: строки ошибок захардкожены на
 * русском в источнике, и без ветки перевода англоязычный пользователь видит
 * русский текст. Тесты фиксируют, что каждая типовая ошибка репозиториев
 * переводится на английский и не искажается для русского.
 *
 * Чистая функция (флаг isRussian вместо Compose-контекста) → обычный JUnit.
 */
class LocalizationErrorMapTest {

    // ─── Английский интерфейс: русские ошибки источника переводятся ───

    @Test
    fun `typical repository errors are translated to English`() {
        val cases = mapOf(
            "Аккаунт не найден" to "Account not found",
            "Папка не найдена" to "Folder not found",
            "Синхронизация уже выполняется" to "Sync is already in progress",
            "Аутентификация не удалась" to "Authentication failed",
            "NTLM аутентификация не удалась" to "NTLM authentication failed",
            "Пустой ответ" to "Empty response",
            "Письмо не найдено" to "Email not found",
            "Письма не найдены" to "No emails found",
            "Не удалось получить SyncKey" to "Failed to obtain SyncKey",
            "Не удалось получить ChangeKey" to "Failed to obtain ChangeKey",
            "Не удалось создать клиент" to "Failed to create client",
            "Не удалось создать IMAP клиент" to "Failed to create IMAP client",
            "Не удалось создать POP3 клиент" to "Failed to create POP3 client",
            "Не удалось выполнить запрос к EWS" to "EWS request failed",
            "Ошибка схемы EWS" to "EWS schema error",
            "Задача не найдена" to "Task not found",
            "Заметка не найдена" to "Note not found",
            "Не поддерживается" to "Not supported",
            "Ошибка авторизации (401)" to "Authorization error (401)",
            "Нельзя удалить системную папку" to "Cannot delete a system folder",
            "Нельзя переименовать системную папку" to "Cannot rename a system folder",
            "Ошибка декодирования вложения" to "Attachment decoding error",
            "Нет данных вложения" to "No attachment data"
        )
        for ((russian, expectedEnglish) in cases) {
            assertThat(NotificationStrings.localizeError(russian, isRussian = false))
                .isEqualTo(expectedEnglish)
        }
    }

    @Test
    fun `folder-not-found errors are translated to English`() {
        val cases = mapOf(
            "Папка черновиков не найдена" to "Drafts folder not found",
            "Папка календаря не найдена" to "Calendar folder not found",
            "Папка задач не найдена" to "Tasks folder not found",
            "Папка контактов не найдена" to "Contacts folder not found",
            "Папка Notes не найдена" to "Notes folder not found",
            "Папка Deleted Items не найдена" to "Deleted Items folder not found"
        )
        for ((russian, expectedEnglish) in cases) {
            assertThat(NotificationStrings.localizeError(russian, isRussian = false))
                .isEqualTo(expectedEnglish)
        }
    }

    @Test
    fun `sync errors with details keep the detail in English`() {
        // Ошибки вида «Ошибка синхронизации папки: status=9» сохраняют деталь.
        assertThat(NotificationStrings.localizeError("Ошибка синхронизации папки: status=9", isRussian = false))
            .isEqualTo("Folder sync error: status=9")
        assertThat(NotificationStrings.localizeError("Ошибка синхронизации папок: слишком много попыток", isRussian = false))
            .isEqualTo("Folder sync error: слишком много попыток") // внутренний суффикс не в карте — остаётся
    }

    // ─── Русский интерфейс: ошибки не искажаются ───

    @Test
    fun `russian errors pass through unchanged for Russian locale`() {
        val russian = listOf(
            "Аккаунт не найден",
            "Папка не найдена",
            "Синхронизация уже выполняется",
            "Папка черновиков не найдена",
            "Ошибка синхронизации папки: status=9"
        )
        for (msg in russian) {
            assertThat(NotificationStrings.localizeError(msg, isRussian = true)).isEqualTo(msg)
        }
    }

    // ─── Существующее покрытие: кодовые ошибки и фоллбек ───

    @Test
    fun `code-based errors still localize`() {
        assertThat(NotificationStrings.localizeError("NO_INTERNET", isRussian = false))
            .isEqualTo("No internet connection. Check your network.")
        assertThat(NotificationStrings.localizeError("NO_INTERNET", isRussian = true))
            .contains("Нет подключения")
    }

    @Test
    fun `unknown error falls back to original message`() {
        // Незнакомая ошибка — не теряется и не искажается.
        assertThat(NotificationStrings.localizeError("Some brand new failure", isRussian = false))
            .isEqualTo("Some brand new failure")
    }

    @Test
    fun `biometric prompt strings localize both ways`() {
        assertThat(NotificationStrings.getAppLockBiometricTitle(false)).isEqualTo("Unlock")
        assertThat(NotificationStrings.getAppLockBiometricTitle(true)).isEqualTo("Разблокировка")
        assertThat(NotificationStrings.getAppLockBiometricNegative(false)).isEqualTo("Use password")
        assertThat(NotificationStrings.getAppLockBiometricSubtitle(true)).isEqualTo("Приложите палец к датчику")
    }
}

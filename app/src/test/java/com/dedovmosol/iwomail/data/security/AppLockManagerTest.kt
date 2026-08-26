package com.dedovmosol.iwomail.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты менеджера блокировки приложения (цель релиза «пароль + дактилоскопия»).
 *
 * Покрывает:
 *  - чистые крипто-функции (PBKDF2, формат хеша, верификация, fail-closed парсинг);
 *  - полный цикл через изолированные SharedPreferences: установка, проверка,
 *    смена, удаление пароля, состояние сессии;
 *  - защиту от коротких паролей и повторную разблокировку.
 *
 * Изоляция по паттерну [PasswordStorageTest]: сброс процесс-синглтона до/после
 * каждого теста + отдельный файл SharedPreferences (Robolectric пересоздаёт
 * окружение на тест, но статический синглтон переживает классы тестов в одной JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26]) // minSdk 26
class AppLockManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AppLockManager.resetForTesting()
        prefs = context.getSharedPreferences("app_lock_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        AppLockManager.setStorageForTesting(prefs)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        AppLockManager.resetForTesting()
    }

    // ─── Чистые крипто-функции ───

    @Test
    fun `pbkdf2 is deterministic for same salt and iterations`() {
        val salt = ByteArray(16) { it.toByte() }
        val a = AppLockManager.pbkdf2("password", salt, 10_000, 32)
        val b = AppLockManager.pbkdf2("password", salt, 10_000, 32)
        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(32)
    }

    @Test
    fun `pbkdf2 differs for different salt or password`() {
        val salt1 = ByteArray(16) { it.toByte() }
        val salt2 = ByteArray(16) { (it + 1).toByte() }
        assertThat(AppLockManager.pbkdf2("password", salt1, 10_000, 32))
            .isNotEqualTo(AppLockManager.pbkdf2("password", salt2, 10_000, 32))
        assertThat(AppLockManager.pbkdf2("password", salt1, 10_000, 32))
            .isNotEqualTo(AppLockManager.pbkdf2("password2", salt1, 10_000, 32))
    }

    @Test
    fun `stored hash round-trips through parse`() {
        val salt = ByteArray(16) { it.toByte() }
        val stored = AppLockManager.buildStoredHash("secret", salt, 1234)
        val parsed = AppLockManager.parseStoredHash(stored)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.iterations).isEqualTo(1234)
        assertThat(parsed.salt).isEqualTo(salt)
    }

    @Test
    fun `verifyStoredHash accepts correct and rejects wrong password`() {
        val salt = ByteArray(16) { it.toByte() }
        val stored = AppLockManager.buildStoredHash("correct-horse", salt, 10_000)

        assertThat(AppLockManager.verifyStoredHash(stored, "correct-horse")).isTrue()
        assertThat(AppLockManager.verifyStoredHash(stored, "correct-horsx")).isFalse()
        assertThat(AppLockManager.verifyStoredHash(stored, "")).isFalse()
    }

    @Test
    fun `parseStoredHash fails closed on malformed input`() {
        // Повреждённые/чужие форматы = отказ (не «пароль не задан», а невалидный хеш).
        assertThat(AppLockManager.parseStoredHash("")).isNull()
        assertThat(AppLockManager.parseStoredHash("v2:1:abc:xyz")).isNull() // чужая версия
        assertThat(AppLockManager.parseStoredHash("v1:only:two")).isNull() // мало частей
        assertThat(AppLockManager.parseStoredHash("v1:-5:c2FsdA:aGFzaA")).isNull() // отрицательные итерации
        assertThat(AppLockManager.parseStoredHash("v1:NaN:c2FsdA:aGFzaA")).isNull() // не число
        assertThat(AppLockManager.parseStoredHash("v1:100:!!!not-base64!!!:aGFzaA")).isNull()
    }

    @Test
    fun `verifyStoredHash fails closed on malformed stored value`() {
        assertThat(AppLockManager.verifyStoredHash("garbage", "anything")).isFalse()
        assertThat(AppLockManager.verifyStoredHash("", "anything")).isFalse()
    }

    // ─── Полный цикл с хранилищем ───

    @Test
    fun `password not set initially`() {
        assertThat(AppLockManager.isPasswordSet()).isFalse()
        assertThat(AppLockManager.isLocked()).isFalse() // evaluateInitialState: нет пароля → разблокировано
    }

    @Test
    fun `setPassword rejects too short password`() {
        assertThat(AppLockManager.setPassword("abc")).isFalse() // 3 < MIN_PASSWORD_LENGTH
        assertThat(AppLockManager.isPasswordSet()).isFalse()
    }

    @Test
    fun `setPassword then checkPassword verifies correct and rejects wrong`() {
        assertThat(AppLockManager.setPassword("test-pass-1")).isTrue()
        assertThat(AppLockManager.isPasswordSet()).isTrue()

        assertThat(AppLockManager.checkPassword("test-pass-1")).isTrue()
        assertThat(AppLockManager.checkPassword("test-pass-2")).isFalse()
        assertThat(AppLockManager.checkPassword("")).isFalse()
    }

    @Test
    fun `verifyPassword unlocks session on success only`() {
        AppLockManager.setPassword("unlock-me")
        AppLockManager.lock()
        assertThat(AppLockManager.isLocked()).isTrue()

        assertThat(AppLockManager.verifyPassword("wrong")).isFalse()
        assertThat(AppLockManager.isLocked()).isTrue() // неудача не разблокирует

        assertThat(AppLockManager.verifyPassword("unlock-me")).isTrue()
        assertThat(AppLockManager.isLocked()).isFalse()
    }

    @Test
    fun `checkPassword does not change session state`() {
        AppLockManager.setPassword("state-safe")
        AppLockManager.lock()

        // Чистая проверка (используется в диалогах настроек) не снимает блокировку.
        AppLockManager.checkPassword("state-safe")
        assertThat(AppLockManager.isLocked()).isTrue()
    }

    @Test
    fun `removePassword clears hash and unlocks`() {
        AppLockManager.setPassword("to-remove")
        AppLockManager.lock()

        AppLockManager.removePassword()
        assertThat(AppLockManager.isPasswordSet()).isFalse()
        assertThat(AppLockManager.isLocked()).isFalse()
        assertThat(AppLockManager.checkPassword("to-remove")).isFalse()
    }

    @Test
    fun `stored hash persists across storage reload`() {
        // Имитация смерти процесса: синглтон сброшен, но файл хранилища жив.
        AppLockManager.setPassword("persist-me")
        AppLockManager.resetForTesting()
        AppLockManager.setStorageForTesting(prefs) // тот же файл

        assertThat(AppLockManager.isPasswordSet()).isTrue()
        assertThat(AppLockManager.isLocked()).isTrue() // fail-closed после «перезапуска»
        assertThat(AppLockManager.verifyPassword("persist-me")).isTrue()
    }

    @Test
    fun `stored hash uses salt so identical passwords produce different hashes`() {
        AppLockManager.setPassword("same-pass")
        val hash1 = prefs.getString("app_lock_password_hash", null)

        prefs.edit().clear().commit()
        AppLockManager.setPassword("same-pass")
        val hash2 = prefs.getString("app_lock_password_hash", null)

        // Разные случайные соли → разные хранимые значения (защита от прекомпутов).
        assertThat(hash1).isNotEqualTo(hash2)
        // Но оба проверяются одинаково.
        assertThat(AppLockManager.checkPassword("same-pass")).isTrue()
    }
}

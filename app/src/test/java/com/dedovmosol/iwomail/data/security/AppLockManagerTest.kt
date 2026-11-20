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
    fun `password not set initially, the session starts locked but nothing is shown without the lock`() {
        assertThat(AppLockManager.isPasswordSet()).isFalse()
        // NAV-04: старт всегда fail-closed; без блокировки гейт открыт
        assertThat(AppLockManager.isLocked()).isTrue()
        assertThat(AppLockManager.gate(AppLockManager.isLocked(), lockFlag = false)).isEqualTo(AppLockManager.Gate.OPEN)
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

    // ─── Чистые гейты (таблицы истинности) ───

    @Test
    fun `gate truth table`() {
        val open = AppLockManager.Gate.OPEN
        // Без пароля: флаг включён — сброс по паролю учётной записи, а не вход без пароля (NAV-04, было fail-open)
        assertThat(AppLockManager.gate(true, lockFlag = true, passwordSet = false)).isEqualTo(AppLockManager.Gate.RECOVERY)
        assertThat(AppLockManager.gate(true, lockFlag = false, passwordSet = false)).isEqualTo(open)
        // A cold start after key loss must not briefly reveal the mailbox.
        assertThat(AppLockManager.gate(true, lockFlag = null, passwordSet = false)).isEqualTo(AppLockManager.Gate.WAITING)
        assertThat(AppLockManager.gate(false, lockFlag = true, passwordSet = false)).isEqualTo(open)

        // С паролем: экран, пока сессия заблокирована и флаг не выключен (до чтения настроек — тоже, fail-closed)
        assertThat(AppLockManager.gate(true, lockFlag = true, passwordSet = true)).isEqualTo(AppLockManager.Gate.LOCKED)
        assertThat(AppLockManager.gate(true, lockFlag = null, passwordSet = true)).isEqualTo(AppLockManager.Gate.LOCKED)
        assertThat(AppLockManager.gate(true, lockFlag = false, passwordSet = true)).isEqualTo(open) // флаг выключен
        assertThat(AppLockManager.gate(false, lockFlag = true, passwordSet = true)).isEqualTo(open) // сессия открыта

        // По умолчанию — хеш из хранилища
        AppLockManager.setPassword("gate-pass")
        AppLockManager.lock()
        assertThat(AppLockManager.gate(true, lockFlag = true)).isEqualTo(AppLockManager.Gate.LOCKED)
    }

    @Test
    fun `a lost hash with the lock on is recovery, not an open app (NAV-04)`() {
        AppLockManager.setPassword("lost-pass")
        // Хранилище ключей сброшено: хеш пропал, флаг в настройках остался
        prefs.edit().clear().commit()
        AppLockManager.resetForTesting()
        AppLockManager.setStorageForTesting(prefs)

        assertThat(AppLockManager.isLocked()).isTrue()
        assertThat(AppLockManager.gate(AppLockManager.isLocked(), lockFlag = true)).isEqualTo(AppLockManager.Gate.RECOVERY)
        assertThat(AppLockManager.lockRequired(lockFlag = true)).isTrue()
    }

    @Test
    fun `recovery attempts are counted and paused like the lock password`() = kotlinx.coroutines.runBlocking {
        AppLockManager.elapsedRealtimeMs = { clock }
        AppLockManager.bootCount = { boot }
        repeat(4) { assertThat(AppLockManager.recoveryAttempt { false }).isInstanceOf(AppLockManager.Attempt.Wrong::class.java) }
        assertThat(AppLockManager.recoveryAttempt { false }).isEqualTo(AppLockManager.Attempt.Wrong(0, 30_000L))
        // Во время паузы проверка не выполняется
        var checked = false
        assertThat(AppLockManager.recoveryAttempt { checked = true; true }).isEqualTo(AppLockManager.Attempt.LockedOut(30_000L))
        assertThat(checked).isFalse()
        // Проверить нечем (нет связи) — не неудача
        clock += 30_000L
        assertThat(AppLockManager.recoveryAttempt { null }).isNull()
        assertThat(AppLockManager.failedAttempts()).isEqualTo(5)
        // Верный пароль учётной записи обнуляет счётчик
        assertThat(AppLockManager.recoveryAttempt { true }).isEqualTo(AppLockManager.Attempt.Success)
        assertThat(AppLockManager.failedAttempts()).isEqualTo(0)
    }

    @Test
    fun `canRequestBiometricUnlock truth table`() {
        // Все три члена обязательны: сессия заблокирована И флаг включён И
        // устройство поддерживает биометрию.
        assertThat(AppLockManager.canRequestBiometricUnlock(true, true, true)).isTrue()

        assertThat(AppLockManager.canRequestBiometricUnlock(false, true, true)).isFalse()
        assertThat(AppLockManager.canRequestBiometricUnlock(true, false, true)).isFalse()
        assertThat(AppLockManager.canRequestBiometricUnlock(true, true, false)).isFalse()
        assertThat(AppLockManager.canRequestBiometricUnlock(false, false, true)).isFalse()
        assertThat(AppLockManager.canRequestBiometricUnlock(false, true, false)).isFalse()
        assertThat(AppLockManager.canRequestBiometricUnlock(true, false, false)).isFalse()
        assertThat(AppLockManager.canRequestBiometricUnlock(false, false, false)).isFalse()
    }

    @Test
    fun `picker onStop does not lock if returned within a minute`() {
        AppLockManager.unlock()
        var now = 1_000L
        AppLockManager.elapsedRealtimeMs = { now }
        AppLockManager.noteLeavingForExternalUi()
        AppLockManager.onProcessBackground()
        assertThat(AppLockManager.isLocked()).isFalse()
        now += 5_000L
        AppLockManager.onProcessForeground()
        assertThat(AppLockManager.isLocked()).isFalse()
    }

    @Test
    fun `long absence after picker locks on resume`() {
        AppLockManager.unlock()
        var now = 1_000L
        AppLockManager.elapsedRealtimeMs = { now }
        AppLockManager.noteLeavingForExternalUi()
        AppLockManager.onProcessBackground()
        now += AppLockManager.EXTERNAL_UI_MAX_UNLOCK_MS
        AppLockManager.onProcessForeground()
        assertThat(AppLockManager.isLocked()).isTrue()
    }

    @Test
    fun `background without picker locks immediately`() {
        AppLockManager.unlock()
        AppLockManager.onProcessBackground()
        assertThat(AppLockManager.isLocked()).isTrue()
    }

    @Test
    fun `shouldLockAfterExternalUi uses inclusive threshold`() {
        assertThat(AppLockManager.shouldLockAfterExternalUi(59_999L, 60_000L)).isFalse()
        assertThat(AppLockManager.shouldLockAfterExternalUi(60_000L, 60_000L)).isTrue()
    }

    // ─── NAV-01: защита от перебора ───

    private var clock = 10_000L
    private var boot = 7

    /** Хеш с малым числом итераций (600k в каждом тесте — секунды) и управляемые часы/номер загрузки. */
    private fun seedPassword(password: String = "right-pass") {
        prefs.edit().putString(
            AppLockManager.KEY_LOCK_HASH,
            AppLockManager.buildStoredHash(password, ByteArray(16) { 3 }, 1_000)
        ).commit()
        AppLockManager.elapsedRealtimeMs = { clock }
        AppLockManager.bootCount = { boot }
    }

    private fun fail(times: Int): AppLockManager.Attempt {
        var last: AppLockManager.Attempt = AppLockManager.Attempt.Success
        repeat(times) { last = AppLockManager.attempt("wrong", unlockOnSuccess = true) }
        return last
    }

    @Test
    fun `lockout schedule grows and caps at 15 minutes`() {
        assertThat((0..4).map(AppLockManager::lockoutForFailures)).containsExactly(0L, 0L, 0L, 0L, 0L)
        assertThat(AppLockManager.lockoutForFailures(5)).isEqualTo(30_000L)
        assertThat(AppLockManager.lockoutForFailures(6)).isEqualTo(60_000L)
        assertThat(AppLockManager.lockoutForFailures(7)).isEqualTo(300_000L)
        assertThat(AppLockManager.lockoutForFailures(8)).isEqualTo(900_000L)
        assertThat(AppLockManager.lockoutForFailures(100)).isEqualTo(900_000L)
    }

    @Test
    fun `wrong attempts count down and the fifth starts a pause`() {
        seedPassword()
        assertThat(fail(1)).isEqualTo(AppLockManager.Attempt.Wrong(4, 0L))
        assertThat(fail(3)).isEqualTo(AppLockManager.Attempt.Wrong(1, 0L))
        assertThat(fail(1)).isEqualTo(AppLockManager.Attempt.Wrong(0, 30_000L))
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(30_000L)
        assertThat(AppLockManager.failedAttempts()).isEqualTo(5)
    }

    @Test
    fun `during a pause even the right password is not checked`() {
        seedPassword()
        AppLockManager.lock()
        fail(5)
        clock += 10_000L
        val result = AppLockManager.attempt("right-pass", unlockOnSuccess = true)
        assertThat(result).isEqualTo(AppLockManager.Attempt.LockedOut(20_000L))
        assertThat(AppLockManager.isLocked()).isTrue()
        // Пауза не тратит попытку
        assertThat(AppLockManager.failedAttempts()).isEqualTo(5)
    }

    @Test
    fun `after the pause the next failure gives a longer pause`() {
        seedPassword()
        fail(5)
        clock += 30_000L
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(0L)
        assertThat(fail(1)).isEqualTo(AppLockManager.Attempt.Wrong(0, 60_000L))
    }

    @Test
    fun `success resets the counter and unlocks`() {
        seedPassword()
        AppLockManager.lock()
        fail(4)
        assertThat(AppLockManager.attempt("right-pass", unlockOnSuccess = true)).isEqualTo(AppLockManager.Attempt.Success)
        assertThat(AppLockManager.isLocked()).isFalse()
        assertThat(AppLockManager.failedAttempts()).isEqualTo(0)
        assertThat(fail(1)).isEqualTo(AppLockManager.Attempt.Wrong(4, 0L))
    }

    @Test
    fun `settings check does not unlock the session`() {
        seedPassword()
        AppLockManager.lock()
        assertThat(AppLockManager.attempt("right-pass", unlockOnSuccess = false)).isEqualTo(AppLockManager.Attempt.Success)
        assertThat(AppLockManager.isLocked()).isTrue()
    }

    @Test
    fun `counter and pause survive process death`() {
        seedPassword()
        fail(5)
        AppLockManager.resetForTesting()
        AppLockManager.setStorageForTesting(prefs)
        AppLockManager.elapsedRealtimeMs = { clock }
        AppLockManager.bootCount = { boot }
        clock += 5_000L
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(25_000L)
        assertThat(AppLockManager.failedAttempts()).isEqualTo(5)
    }

    @Test
    fun `reboot restarts the pause in full`() {
        seedPassword()
        fail(5)
        clock += 20_000L
        // Новая загрузка: elapsedRealtime начат заново и меньше старой отметки
        boot += 1
        clock = 1_000L
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(30_000L)
        clock += 29_999L
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(1L)
        clock += 1L
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(0L)
    }

    @Test
    fun `stored deadline beyond the pause length is clamped`() {
        seedPassword()
        fail(5)
        // Отметка в будущем дальше длины паузы (подмена файла или сбой без смены BOOT_COUNT) — не дольше самой паузы
        prefs.edit().putLong("app_lock_lockout_until_elapsed", clock + 10_000_000L).commit()
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(30_000L)
    }

    @Test
    fun `resetAttempts clears the pause (biometric unlock)`() {
        seedPassword()
        fail(5)
        AppLockManager.resetAttempts()
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(0L)
        assertThat(AppLockManager.failedAttempts()).isEqualTo(0)
    }

    @Test
    fun `setting and removing the password clear the counter`() {
        seedPassword()
        fail(5)
        AppLockManager.removePassword()
        assertThat(AppLockManager.failedAttempts()).isEqualTo(0)
        assertThat(AppLockManager.lockoutRemainingMs()).isEqualTo(0L)
    }

    @Test
    fun `attempt against a corrupted hash still counts as a failure`() {
        seedPassword()
        // Повреждённый хеш: проверка уходит в отказ, попытка засчитана
        prefs.edit().putString(AppLockManager.KEY_LOCK_HASH, "garbage").commit()
        AppLockManager.attempt("anything", unlockOnSuccess = true)
        assertThat(AppLockManager.failedAttempts()).isEqualTo(1)
    }

    @Test
    fun `fail-closed blocks writing the lock password to obfuscated storage`() {
        SecurityTelemetry.resetForTesting()
        SecurityTelemetry.getInstance(context).setFailClosedMode(true)
        AppLockManager.markInsecureForTesting(context)
        assertThat(AppLockManager.setPassword("123456")).isFalse()
        assertThat(AppLockManager.isPasswordSet()).isFalse()
        SecurityTelemetry.resetForTesting()
    }
}

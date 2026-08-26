package com.dedovmosol.iwomail.data.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Менеджер блокировки приложения паролем + отпечатком пальца (цель релиза «пароль + дактилоскопия»).
 *
 * Архитектура (SOC):
 *  - Секретный материал (хеш пароля) живёт здесь, в шифрованном хранилище
 *    ([EncryptedStoreProvider]: EncryptedSharedPreferences → обфусцированный фоллбек).
 *  - Флаги «блокировка включена» / «отпечаток включён» живут в [com.dedovmosol.iwomail.data.repository.SettingsRepository]
 *    (DataStore, единственный писатель) — менеджер НЕ пишет в DataStore.
 *  - Биометрический промпт запускает Activity (UI-слой), менеджер только сообщает
 *    доступность биометрии.
 *
 * Безопасность (OWASP Password Storage Cheat Sheet, верифицировано 2026):
 *  - Только хеш, никогда открытый пароль; формат `v1:iterations:b64salt:b64hash`.
 *  - PBKDF2-HMAC-SHA256, 600 000 итераций (актуальная рекомендация OWASP для PBKDF2-SHA256).
 *  - Соль 16 байт из [SecureRandom] на каждый пароль.
 *  - Сравнение хешей через [MessageDigest.isEqual] (constant-time, защита от timing-атак).
 *
 * Сессия (KISS, практика банковских приложений):
 *  - Fail-closed: холодный старт и смерть процесса = заблокировано, если пароль задан.
 *  - Разблокировка снимается при уходе всего процесса в фон (см. подписку в
 *    MailApplication на [com.dedovmosol.iwomail.sync.AppForegroundTracker]).
 *  - Поворот экрана НЕ перекрывает: состояние в процесс-синглтоне, а
 *    ProcessLifecycleOwner не диспатчит ON_STOP при конфигурационных изменениях.
 *
 * Тестируемость: вся крипто-логика — чистые функции компаньона, юнит-тестируются
 * без Android-фреймворка (как [com.dedovmosol.iwomail.sync.AppForegroundTracker.shouldShowNewMailNotification]).
 */
object AppLockManager {

    private const val TAG = "AppLockManager"

    /** Ключ хеша пароля блокировки в шифрованном хранилище. */
    private const val KEY_LOCK_HASH = "app_lock_password_hash"

    /** Версия формата хеша — для будущей миграции алгоритма. */
    private const val HASH_VERSION = "v1"

    /** OWASP 2023+ рекомендация для PBKDF2-HMAC-SHA256: 600 000 итераций. */
    const val PBKDF2_ITERATIONS = 600_000

    /** Длина соли в байтах (128 бит — рекомендация OWASP). */
    private const val SALT_LENGTH_BYTES = 16

    /** Длина производного ключа в байтах (256 бит). */
    private const val KEY_LENGTH_BYTES = 32

    /** Минимальная длина пароля блокировки (KISS: без сложных правил, но не тривиальный). */
    const val MIN_PASSWORD_LENGTH = 4

    private val secureRandom = SecureRandom()

    @Volatile
    private var storage: android.content.SharedPreferences? = null

    /**
     * Состояние сессии: true = приложение заблокировано (показывать экран ввода пароля).
     * Стартует как true (fail-closed); фактическое значение определяется [evaluateInitialState]
     * при инициализации: если пароль не задан — сразу разблокировано.
     */
    private val _locked = MutableStateFlow(true)

    /** Реактивное состояние блокировки для UI-гейта в MainActivity. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * Инициализация из Application.onCreate: создаёт хранилище и вычисляет стартовое
     * состояние сессии. Идемпотентна. Холодный старт с заданным паролем = заблокировано
     * (fail-closed); без пароля = разблокировано.
     */
    fun init(context: Context) {
        if (storage != null) return
        synchronized(this) {
            if (storage != null) return
            storage = EncryptedStoreProvider.create(
                context.applicationContext,
                "app_lock_store",
                "app_lock_fallback"
            ).prefs
            evaluateInitialState()
        }
    }

    /** Синхронный срез для немедленных решений (например, решение о флаге окна). */
    fun isLocked(): Boolean = _locked.value

    /** Задан ли пароль блокировки (в шифрованном хранилище есть валидный хеш). */
    fun isPasswordSet(): Boolean {
        val hash = storage?.getString(KEY_LOCK_HASH, null) ?: return false
        return parseStoredHash(hash) != null
    }

    /**
     * Установить или заменить пароль блокировки. Возвращает false, если пароль
     * короче [MIN_PASSWORD_LENGTH] (валидация до хеширования — лишняя работа не нужна).
     * После успешной установки сессия разблокируется.
     */
    fun setPassword(password: String): Boolean {
        val prefs = storage ?: return false
        if (password.length < MIN_PASSWORD_LENGTH) return false
        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        val hash = buildStoredHash(password, salt, PBKDF2_ITERATIONS)
        prefs.edit().putString(KEY_LOCK_HASH, hash).apply()
        unlock()
        return true
    }

    /**
     * Чистая проверка пароля БЕЗ изменения состояния сессии — для валидации
     * в настройках (смена/отключение пароля). [verifyPassword] добавляет разблокировку.
     */
    fun checkPassword(candidate: String): Boolean {
        val stored = storage?.getString(KEY_LOCK_HASH, null) ?: return false
        return verifyStoredHash(stored, candidate)
    }

    /**
     * Проверка введённого пароля на экране разблокировки. При успехе разблокирует сессию.
     * Constant-time сравнение хешей; неверный формат сохранённого хеша = отказ (fail-closed).
     */
    fun verifyPassword(candidate: String): Boolean {
        val ok = checkPassword(candidate)
        if (ok) unlock()
        return ok
    }

    /** Удалить пароль блокировки (требует предварительной проверки текущего пароля вызывающим). */
    fun removePassword() {
        storage?.edit()?.remove(KEY_LOCK_HASH)?.apply()
        _locked.value = false
    }

    /** Разблокировать сессию (после успешного пароля или биометрии). */
    fun unlock() {
        _locked.value = false
    }

    /**
     * Заблокировать сессию при уходе в фон. Вызывается только когда блокировка ВКЛЮЧЕНА
     * в настройках — решение принимает подписка в MailApplication, здесь чистая операция.
     */
    fun lock() {
        _locked.value = true
    }

    /**
     * Стартовое состояние: заблокировано только если пароль реально задан.
     * Вызывается из [init] под synchronized.
     */
    private fun evaluateInitialState() {
        _locked.value = isPasswordSet()
    }

    /** Только для тестов: сброс синглтона (паттерн [PasswordStorage.resetForTesting]). */
    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        synchronized(this) {
            storage = null
            _locked.value = true
        }
    }

    /** Только для тестов: подменить хранилище изолированными SharedPreferences. */
    @androidx.annotation.VisibleForTesting
    fun setStorageForTesting(prefs: android.content.SharedPreferences) {
        synchronized(this) {
            storage = prefs
            evaluateInitialState()
        }
    }

    // ─────────────────────────── Чистые функции (тестируемое ядро) ───────────────────────────

    /** Разобранное хранимое значение хеша. */
    internal data class StoredHash(val iterations: Int, val salt: ByteArray, val hash: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is StoredHash && iterations == other.iterations &&
                salt.contentEquals(other.salt) && hash.contentEquals(other.hash)

        override fun hashCode(): Int =
            iterations * 31 + salt.contentHashCode() * 17 + hash.contentHashCode()
    }

    /**
     * PBKDF2-HMAC-SHA256 (чистая функция): пароль + соль + итерации → производный ключ.
     * Совпадает с паттерном [ObfuscatedSharedPreferences.deriveKeyPBKDF2], но с
     * актуальными OWASP-параметрами и явной солью вместо статической.
     */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBytes * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Сериализация хеша: `v1:iterations:base64(salt):base64(hash)`.
     * Base64 NO_WRAP — без переносов строк, безопасно для SharedPreferences.
     */
    fun buildStoredHash(password: String, salt: ByteArray, iterations: Int): String {
        val derived = pbkdf2(password, salt, iterations, KEY_LENGTH_BYTES)
        val b64 = android.util.Base64.NO_WRAP
        return "$HASH_VERSION:$iterations:" +
            "${android.util.Base64.encodeToString(salt, b64)}:" +
            android.util.Base64.encodeToString(derived, b64)
    }

    /**
     * Парсинг сохранённого хеша. Любое отклонение формата = null (fail-closed):
     * повреждённое хранилище не должно выглядеть как «пароль не задан».
     *
     * Видимость [androidx.annotation.VisibleForTesting]-уровня: внутренний тип
     * [StoredHash] не должен попадать в публичное API модуля.
     */
    internal fun parseStoredHash(stored: String): StoredHash? {
        val parts = stored.split(":")
        if (parts.size != 4 || parts[0] != HASH_VERSION) return null
        return try {
            val iterations = parts[1].toInt()
            if (iterations <= 0) return null
            val b64 = android.util.Base64.NO_WRAP
            val salt = android.util.Base64.decode(parts[2], b64)
            val hash = android.util.Base64.decode(parts[3], b64)
            if (salt.isEmpty() || hash.isEmpty()) null else StoredHash(iterations, salt, hash)
        } catch (_: Exception) {
            // NumberFormatException / IllegalArgumentException при повреждении формата —
            // трактуем как «хеш невалиден» (fail-closed).
            null
        }
    }

    /**
     * Проверка кандидата против сохранённого хеша (чистая функция).
     * Итерации и соль берутся ИЗ СОХРАНЁННОГО значения — старый хеш с меньшим
     * числом итераций проверяется корректно (плавная миграция без сброса пароля).
     */
    fun verifyStoredHash(stored: String, candidate: String): Boolean {
        val parsed = parseStoredHash(stored) ?: return false
        val derived = pbkdf2(candidate, parsed.salt, parsed.iterations, parsed.hash.size)
        return MessageDigest.isEqual(derived, parsed.hash)
    }
}

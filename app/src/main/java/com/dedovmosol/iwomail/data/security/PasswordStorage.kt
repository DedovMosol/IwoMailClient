package com.dedovmosol.iwomail.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure password storage with fallback and telemetry.
 *
 * Primary path: EncryptedSharedPreferences (AES256-GCM via Android KeyStore).
 * Fallback path: ObfuscatedSharedPreferences (PBKDF2-based key derivation from ANDROID_ID).
 *
 * L-7 fix: Added security telemetry, user notification, and optional fail-closed mode.
 *
 * ARCHITECTURE: Separation of Concerns
 * - PasswordStorage: public API for password operations
 * - SecurePreferences/ObfuscatedPreferences: internal storage implementations
 * - SecurityTelemetry: logging and notification of security events
 *
 * SOLID: Single Responsibility Principle
 * - Each class has one reason to change
 * - Storage backend selection logic isolated from usage
 *
 * KISS: Keep It Simple
 * - API remains simple: save/get/delete
 * - Complexity hidden in internal implementations
 *
 * DRY: Don't Repeat Yourself
 * - Password storage logic centralized in one place
 * - No duplication across AccountRepository
 */
class PasswordStorage private constructor(
    private val context: Context
) {
    private val telemetry = SecurityTelemetry.getInstance(context)

    /**
     * Storage backend result: either secure or obfuscated with reason.
     */
    private sealed class StorageBackend {
        data class Secure(val prefs: SharedPreferences) : StorageBackend()
        data class Obfuscated(val prefs: SharedPreferences, val reason: String) : StorageBackend()
    }

    private val storage: StorageBackend by lazy {
        // Единая фабрика шифрованного хранилища (DRY): выбор бэкенда
        // EncryptedSharedPreferences → обфусцированный фоллбек + телеметрия
        // инкапсулированы в EncryptedStoreProvider (общий с AppLockManager).
        val result = EncryptedStoreProvider.create(
            context,
            fileName = "secure_passwords",
            fallbackFileName = "passwords_fallback"
        )
        if (result.insecureReason == null) {
            StorageBackend.Secure(result.prefs)
        } else {
            StorageBackend.Obfuscated(result.prefs, result.insecureReason)
        }
    }

    /**
     * Check if currently using insecure (obfuscated) storage.
     */
    fun isUsingInsecureStorage(): Boolean = storage is StorageBackend.Obfuscated

    /**
     * Get reason for fallback to insecure storage, or null if using secure storage.
     */
    fun getInsecureStorageReason(): String? = (storage as? StorageBackend.Obfuscated)?.reason

    /**
     * Save password for account.
     * If using insecure storage and fail-closed mode is enabled, throws SecurityException.
     */
    fun savePassword(accountId: Long, password: String) {
        if (isUsingInsecureStorage() && telemetry.isFailClosedEnabled()) {
            throw SecurityException("Cannot save password: insecure storage and fail-closed mode enabled")
        }

        val prefs = when (val s = storage) {
            is StorageBackend.Secure -> s.prefs
            is StorageBackend.Obfuscated -> s.prefs
        }

        prefs.edit()
            .putString("password_$accountId", password)
            .apply()
    }

    /**
     * Get password for account.
     * Returns null if password not found.
     */
    fun getPassword(accountId: Long): String? {
        val prefs = when (val s = storage) {
            is StorageBackend.Secure -> s.prefs
            is StorageBackend.Obfuscated -> s.prefs
        }

        return prefs.getString("password_$accountId", null)
    }

    /**
     * Delete password for account.
     */
    fun deletePassword(accountId: Long) {
        val prefs = when (val s = storage) {
            is StorageBackend.Secure -> s.prefs
            is StorageBackend.Obfuscated -> s.prefs
        }

        prefs.edit()
            .remove("password_$accountId")
            .apply()
    }

    /**
     * Save client certificate password for account.
     */
    fun saveClientCertPassword(accountId: Long, password: String) {
        if (isUsingInsecureStorage() && telemetry.isFailClosedEnabled()) {
            throw SecurityException("Cannot save password: insecure storage and fail-closed mode enabled")
        }

        val prefs = when (val s = storage) {
            is StorageBackend.Secure -> s.prefs
            is StorageBackend.Obfuscated -> s.prefs
        }

        prefs.edit()
            .putString("client_cert_password_$accountId", password)
            .apply()
    }

    /**
     * Get client certificate password for account.
     */
    fun getClientCertPassword(accountId: Long): String? {
        val prefs = when (val s = storage) {
            is StorageBackend.Secure -> s.prefs
            is StorageBackend.Obfuscated -> s.prefs
        }

        return prefs.getString("client_cert_password_$accountId", null)
    }

    /**
     * Delete client certificate password for account.
     */
    fun deleteClientCertPassword(accountId: Long) {
        val prefs = when (val s = storage) {
            is StorageBackend.Secure -> s.prefs
            is StorageBackend.Obfuscated -> s.prefs
        }

        prefs.edit()
            .remove("client_cert_password_$accountId")
            .apply()
    }

    companion object {
        private const val TAG = "PasswordStorage"

        @Volatile
        private var instance: PasswordStorage? = null

        fun getInstance(context: Context): PasswordStorage {
            return instance ?: synchronized(this) {
                instance ?: PasswordStorage(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Только для тестов: сброс синглтона.
         *
         * В Robolectric каждый тест получает новое окружение (свои
         * SharedPreferences), а статический синглтон переживает тест-классы
         * в одной JVM и держит СТАРУЮ ссылку на чужие настройки — без сброса
         * состояние (например, чужой режим «небезопасное хранилище») утекает
         * между тестами.
         */
        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}

/**
 * SharedPreferences wrapper: PBKDF2-based obfuscation of values.
 *
 * NOT cryptographic protection, but better than plaintext or simple XOR.
 * Uses PBKDF2-HMAC-SHA256 with 10,000 iterations to derive key from ANDROID_ID.
 *
 * L-7 improvement: Replaced SHA-256(ANDROID_ID) with PBKDF2 for defense-in-depth.
 *
 * Security properties:
 * - Resistant to rainbow table attacks (PBKDF2 iterations)
 * - Unique key per device (ANDROID_ID as seed)
 * - XOR with derived key (simple but effective obfuscation)
 *
 * Threat model:
 * - Protects against: casual file inspection, accidental backup exposure
 * - Does NOT protect against: root access + knowledge of algorithm, targeted attacks
 *
 * YAGNI: No unnecessary complexity (e.g., IV, salt rotation) — this is fallback-only.
 * KISS: Simple XOR with PBKDF2-derived key, not full encryption.
 */
internal class ObfuscatedSharedPreferences(
    private val delegate: SharedPreferences,
    context: Context
) : SharedPreferences by delegate {

    private val obfKey: ByteArray by lazy {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "fallback_device_id_iwomail_2024"

        // L-7: PBKDF2 instead of plain SHA-256 for better security
        deriveKeyPBKDF2(
            password = "iwomail_obf_v2_$androidId",
            salt = "iwomail_static_salt_v2".toByteArray(Charsets.UTF_8),
            iterations = 10_000,
            keyLength = 32
        )
    }

    override fun getString(key: String?, defValue: String?): String? {
        val raw = delegate.getString(key, null) ?: return defValue
        return try {
            xorTransform(
                android.util.Base64.decode(raw, android.util.Base64.NO_WRAP),
                obfKey
            ).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("ObfuscatedPrefs", "Failed to decode value for key: $key", e)
            defValue
        }
    }

    override fun edit(): SharedPreferences.Editor =
        ObfuscatedEditor(delegate.edit(), obfKey)

    private class ObfuscatedEditor(
        private val editor: SharedPreferences.Editor,
        private val key: ByteArray
    ) : SharedPreferences.Editor by editor {

        // ВАЖНО (контракт SharedPreferences.Editor): все методы обязаны возвращать
        // ЭТОТ ЖЕ редактор для цепочек. Раньше возвращался делегат — и второй
        // вызов в цепочке putString().putString() писал пароль В ОТКРЫТОМ ВИДЕ
        // мимо обфускации (реальный прод-баг).
        override fun putString(k: String?, value: String?): SharedPreferences.Editor {
            if (value == null) {
                editor.putString(k, null)
                return this
            }
            val obfuscated = android.util.Base64.encodeToString(
                xorTransform(value.toByteArray(Charsets.UTF_8), key),
                android.util.Base64.NO_WRAP
            )
            editor.putString(k, obfuscated)
            return this
        }
    }

    companion object {
        /**
         * Derive key using PBKDF2-HMAC-SHA256.
         * Internet best practice: OWASP recommends 10,000+ iterations for 2024.
         */
        private fun deriveKeyPBKDF2(
            password: String,
            salt: ByteArray,
            iterations: Int,
            keyLength: Int
        ): ByteArray {
            val spec = javax.crypto.spec.PBEKeySpec(
                password.toCharArray(),
                salt,
                iterations,
                keyLength * 8
            )
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }

        private fun xorTransform(data: ByteArray, key: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            return out
        }
    }
}

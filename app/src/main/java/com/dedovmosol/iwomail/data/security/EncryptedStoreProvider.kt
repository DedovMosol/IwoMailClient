package com.dedovmosol.iwomail.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Единая фабрика шифрованных SharedPreferences (DRY).
 *
 * Основной путь: [EncryptedSharedPreferences] (AES256-GCM через Android KeyStore).
 * Запасной путь: [ObfuscatedSharedPreferences] (PBKDF2-обфускация) + событие
 * [SecurityTelemetry.recordInsecureStorageUsed] — тот же контракт, что исторически
 * реализовывал [PasswordStorage] инлайн. Фабрика вынесена, чтобы хранилище пароля
 * блокировки приложения ([AppLockManager]) не дублировало логику выбора бэкенда.
 *
 * SOC: выбор бэкенда и телеметрия инкапсулированы здесь; потребители получают
 * готовый [SharedPreferences] и признак деградации.
 */
internal object EncryptedStoreProvider {

    /**
     * Результат создания хранилища.
     * @param prefs готовое хранилище (шифрованное или обфусцированное).
     * @param insecureReason причина деградации; `null` если хранилище шифрованное.
     */
    data class Result(val prefs: SharedPreferences, val insecureReason: String?)

    fun create(context: Context, fileName: String, fallbackFileName: String): Result {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encrypted = EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            Result(encrypted, null)
        } catch (e: Exception) {
            android.util.Log.e(
                "EncryptedStoreProvider",
                "EncryptedSharedPreferences init failed for $fileName, using obfuscated fallback",
                e
            )

            val reason = when {
                e.message?.contains("KeyStore", ignoreCase = true) == true -> "KeyStore unavailable"
                e.message?.contains("InvalidKey", ignoreCase = true) == true -> "Invalid master key"
                else -> e.message ?: "Unknown error"
            }
            SecurityTelemetry.getInstance(context).recordInsecureStorageUsed(reason)

            val fallbackPrefs = context.getSharedPreferences(fallbackFileName, Context.MODE_PRIVATE)
            Result(ObfuscatedSharedPreferences(fallbackPrefs, context), reason)
        }
    }
}

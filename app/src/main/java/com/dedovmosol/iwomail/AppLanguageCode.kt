package com.dedovmosol.iwomail

import android.content.Context

/**
 * Язык UI вне Compose. DataStore обновляет кэш, [load] читает последний
 * записанный код до асинхронного collect, чтобы Toast при старте
 * (деградация Keystore в [AppLockManager.init]) не был на чужом языке.
 */
object AppLanguageCode {
    private const val PREFS = "app_language_code"
    private const val KEY = "code"

    @Volatile
    private var code: String = "ru"

    fun isRussian(): Boolean = code != "en"

    fun set(code: String) {
        this.code = if (code == "en") "en" else "ru"
    }

    fun load(context: Context) {
        val stored = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        if (stored != null) set(stored)
    }

    fun persist(context: Context, code: String) {
        val normalized = if (code == "en") "en" else "ru"
        if (this.code == normalized) return
        this.code = normalized
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, normalized)
            .commit()
    }
}

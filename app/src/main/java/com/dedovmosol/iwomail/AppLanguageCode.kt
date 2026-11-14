package com.dedovmosol.iwomail

/**
 * Язык UI вне Compose: DataStore пишет сюда, Toast и security читают без
 * зависимости на [com.dedovmosol.iwomail.data.repository.SettingsRepository].
 */
object AppLanguageCode {
    @Volatile
    private var code: String = "ru"

    fun isRussian(): Boolean = code != "en"

    fun set(code: String) {
        this.code = if (code == "en") "en" else "ru"
    }
}

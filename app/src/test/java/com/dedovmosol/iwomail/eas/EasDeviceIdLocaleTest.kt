package com.dedovmosol.iwomail.eas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Регрессия локали для EAS DeviceId.
 *
 * DeviceId уходит в query-строку URL EAS ([MS-ASHTTP] 2.2.1.1.1.4), поэтому ОБЯЗАН быть ASCII и
 * НЕ зависеть от языка устройства. До фикса `String.format("%010d")` вызывался без `Locale`, и в
 * локалях с не-ASCII цифрами (fa-IR, ar-EG, bn-BD, ne-NP, mr-IN…) `%d` подставлял восточные цифры
 * (۰۱۲ / ٠١٢) через `DecimalFormatSymbols.getZeroDigit()` — URL ломался, а при смене языка телефона
 * сервер видел «новое устройство» (пере-provisioning, лимиты).
 *
 * Тест реальный: гоняет рабочий генератор [EasClient.generateStableDeviceId] под «опасными»
 * локалями и восстанавливает `Locale.getDefault()` в finally.
 */
class EasDeviceIdLocaleTest {

    private val nonLatinDigitLocales = listOf("fa-IR", "ar-EG", "ar-SA", "bn-BD", "ne-NP", "mr-IN")

    private fun <T> withLocale(tag: String, block: () -> T): T {
        val prev = Locale.getDefault()
        return try {
            Locale.setDefault(Locale.forLanguageTag(tag))
            block()
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test
    fun `deviceId is pure ASCII under non-latin-digit locales`() {
        for (tag in nonLatinDigitLocales) {
            val id = withLocale(tag) { EasClient.generateStableDeviceId("[email protected]", "") }
            assertThat(id.all { it.code < 128 }).isTrue()
            // \d в java.util.regex по умолчанию — только ASCII [0-9]: восточные цифры не пройдут.
            assertThat(id).matches("androidc\\d{10}")
        }
    }

    @Test
    fun `deviceId is identical regardless of device locale`() {
        val reference = withLocale("en-US") { EasClient.generateStableDeviceId("[email protected]", "dev") }
        for (tag in nonLatinDigitLocales) {
            val id = withLocale(tag) { EasClient.generateStableDeviceId("[email protected]", "dev") }
            assertThat(id).isEqualTo(reference)
        }
    }

    @Test
    fun `deviceId is deterministic for same inputs`() {
        assertThat(EasClient.generateStableDeviceId("user", "s"))
            .isEqualTo(EasClient.generateStableDeviceId("user", "s"))
    }
}

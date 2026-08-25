package com.dedovmosol.iwomail.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit tests для [SettingsRepository.ThemeMode].
 *
 * Покрытие:
 * - Парсинг имени режима (валидные значения, невалидные, null)
 * - Fallback на SYSTEM при неизвестном значении (защита от повреждённых данных)
 * - Локализованные названия (RU/EN)
 * - Полнота набора режимов (системный/светлая/тёмная)
 */
class ThemeModeTest {

    @Test
    fun `fromName parses all valid modes`() {
        assertThat(SettingsRepository.ThemeMode.fromName("SYSTEM"))
            .isEqualTo(SettingsRepository.ThemeMode.SYSTEM)
        assertThat(SettingsRepository.ThemeMode.fromName("LIGHT"))
            .isEqualTo(SettingsRepository.ThemeMode.LIGHT)
        assertThat(SettingsRepository.ThemeMode.fromName("DARK"))
            .isEqualTo(SettingsRepository.ThemeMode.DARK)
    }

    @Test
    fun `fromName returns SYSTEM for null (key never written)`() {
        assertThat(SettingsRepository.ThemeMode.fromName(null))
            .isEqualTo(SettingsRepository.ThemeMode.SYSTEM)
    }

    @Test
    fun `fromName returns SYSTEM for unknown value (corrupted data protection)`() {
        assertThat(SettingsRepository.ThemeMode.fromName("garbage"))
            .isEqualTo(SettingsRepository.ThemeMode.SYSTEM)
        assertThat(SettingsRepository.ThemeMode.fromName(""))
            .isEqualTo(SettingsRepository.ThemeMode.SYSTEM)
        assertThat(SettingsRepository.ThemeMode.fromName("light"))
            .isEqualTo(SettingsRepository.ThemeMode.SYSTEM)
    }

    @Test
    fun `display names are localized for russian`() {
        assertThat(SettingsRepository.ThemeMode.SYSTEM.getDisplayName(isRussian = true))
            .isEqualTo("Как в системе")
        assertThat(SettingsRepository.ThemeMode.LIGHT.getDisplayName(isRussian = true))
            .isEqualTo("Светлая")
        assertThat(SettingsRepository.ThemeMode.DARK.getDisplayName(isRussian = true))
            .isEqualTo("Тёмная")
    }

    @Test
    fun `display names are localized for english`() {
        assertThat(SettingsRepository.ThemeMode.SYSTEM.getDisplayName(isRussian = false))
            .isEqualTo("Follow system")
        assertThat(SettingsRepository.ThemeMode.LIGHT.getDisplayName(isRussian = false))
            .isEqualTo("Light")
        assertThat(SettingsRepository.ThemeMode.DARK.getDisplayName(isRussian = false))
            .isEqualTo("Dark")
    }

    @Test
    fun `enum contains exactly three modes`() {
        assertThat(SettingsRepository.ThemeMode.entries)
            .containsExactly(
                SettingsRepository.ThemeMode.SYSTEM,
                SettingsRepository.ThemeMode.LIGHT,
                SettingsRepository.ThemeMode.DARK
            )
            .inOrder()
    }

    @Test
    fun `fromName roundtrips every mode name`() {
        SettingsRepository.ThemeMode.entries.forEach { mode ->
            assertThat(SettingsRepository.ThemeMode.fromName(mode.name)).isEqualTo(mode)
        }
    }
}

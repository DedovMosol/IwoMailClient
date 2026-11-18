package com.dedovmosol.iwomail.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dedovmosol.iwomail.ui.screens.SettingsTokens
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * SET-24: схема без dynamic color. Контраст по WCAG 2.x: текст не ниже 4,5:1, элементы и крупный текст не ниже 3:1.
 * Карточка настроек: светлая тема — surfaceContainerLowest, тёмная — surfaceContainerHigh (см. SettingsTokens).
 */
class AppColorSchemeContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    private fun check(name: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrast(fg, bg)
        assertWithMessage(String.format(java.util.Locale.ROOT, "%s: %.2f < %.1f", name, ratio, min)).that(ratio).isAtLeast(min)
    }

    @Test
    fun `settings text and tiles keep contrast in every theme`() {
        for (theme in AppColorTheme.entries) for (dark in listOf(false, true)) {
            val s = appColorScheme(theme, dark)
            val tag = "${theme.code}/${if (dark) "dark" else "light"}"
            val card = if (dark) s.surfaceContainerHigh else s.surfaceContainerLowest
            val page = if (dark) s.background else s.surfaceContainer
            check("$tag onSurface/card", s.onSurface, card, 4.5)
            check("$tag onSurfaceVariant/card", s.onSurfaceVariant, card, 4.5)
            check("$tag onSurfaceVariant/page (section title)", s.onSurfaceVariant, page, 4.5)
            check("$tag onPrimaryContainer/primaryContainer (tile)", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check("$tag onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check("$tag onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            check("$tag error/card", s.error, card, 4.5)
            // primary — ещё и цвет текста кнопок (TextButton) и ссылок: норма текста
            check("$tag primary/card (text buttons, links)", s.primary, card, 4.5)
            check("$tag card/page (group edge)", card, page, 1.05)
            val success = if (dark) SettingsTokens.SUCCESS_DARK else SettingsTokens.SUCCESS_LIGHT
            val warning = if (dark) SettingsTokens.WARNING_DARK else SettingsTokens.WARNING_LIGHT
            check("$tag success/card", success, card, 4.5)
            check("$tag warning/card", warning, card, 4.5)
        }
    }

    @Test
    fun `white text on the gradient bar and buttons meets 4_5 to 1 (SET-08)`() {
        // Свёрнутый заголовок панели 22 sp и текст ThemeButton — обычный текст: норма 4,5:1 по всему градиенту
        for (theme in AppColorTheme.entries) {
            check("${theme.code} white/gradientStart", Color.White, theme.gradientStart, 4.5)
            check("${theme.code} white/gradientEnd", Color.White, theme.gradientEnd, 4.5)
            check("${theme.code} white/primaryLight", Color.White, theme.primaryLight, 4.5)
        }
    }

    @Test
    fun `containers follow the chosen theme instead of baseline purple`() {
        val blue = appColorScheme(AppColorTheme.BLUE, dark = false)
        val green = appColorScheme(AppColorTheme.GREEN, dark = false)
        assertWithMessage("blue container is blue-ish").that(blue.primaryContainer.blue).isGreaterThan(blue.primaryContainer.red)
        assertWithMessage("green container is green-ish").that(green.primaryContainer.green).isGreaterThan(green.primaryContainer.red)
        // Поверхности нейтральные: без оттенка темы
        for (s in listOf(blue, green)) {
            val c = s.surfaceContainer
            assertWithMessage("neutral surfaceContainer").that(maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)).isLessThan(0.02f)
        }
    }
}

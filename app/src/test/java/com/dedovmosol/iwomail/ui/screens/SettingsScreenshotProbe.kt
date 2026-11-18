package com.dedovmosol.iwomail.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Ручная визуальная проверка SET-24: PNG в app/build/settings-shots. Те же компоненты, что у экранов, на статических
 * данных (реальные экраны держат DataStore и Room и под Robolectric не уходят в idle). Запуск: IWO_SHOTS=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotProbe {

    @get:Rule
    val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before
    fun only() = Assume.assumeTrue(System.getenv("IWO_SHOTS") == "1")

    /** Программная отрисовка окна (PixelCopy под Robolectric не отвечает); всплывающие окна — отдельными файлами. */
    private fun shot(name: String) {
        val dir = File(System.getProperty("user.dir"), "build/settings-shots").apply { mkdirs() }
        rule.waitForIdle()
        val views = mutableListOf(rule.activity.window.decorView)
        // Выпадающий список и нижний лист — отдельные окна (Popup/Dialog)
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val all = org.robolectric.util.ReflectionHelpers.getField<Any>(global, "mViews") as List<android.view.View>
        views += all.filter { v -> v !== rule.activity.window.decorView }
        views.distinct().forEachIndexed { i, v ->
            if (v.width <= 0 || v.height <= 0) return@forEachIndexed
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(dir, if (i == 0) "$name.png" else "${name}_popup$i.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun render(dark: Boolean, theme: AppColorTheme, fontScale: Float = 1f, body: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.RUSSIAN) {
                ExchangeMailTheme(darkTheme = dark, dynamicColor = false, colorTheme = theme, fontScale = fontScale) { body() }
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun Hub() = SettingsPageScaffold(title = "Настройки", onBackClick = {}) {
        item { SettingsGroup { SettingsHubRow("Аккаунты", "user@example.com", AppIcons.Person) {} } }
        item {
            SettingsGroup {
                SettingsHubRow("Почта", "Цепочки, свайпы, картинки", AppIcons.Email) {}
                SettingsHubRow("Уведомления", "Тихие часы, важные отправители", AppIcons.Notifications) {}
            }
        }
        item {
            SettingsGroup {
                SettingsHubRow("Оформление", "Язык, шрифт, тема", AppIcons.Palette) {}
                SettingsHubRow("Блокировка приложения", "Пароль и отпечаток", AppIcons.Lock) {}
            }
        }
        item { SettingsGroup { SettingsHubRow("О приложении", "Версия, обновления", AppIcons.Info) {} } }
    }

    @Composable
    private fun Swatch(theme: AppColorTheme, size: Int = 20) =
        Box(Modifier.size(size.dp).clip(CircleShape).background(theme.gradientStart))

    @Composable
    private fun Appearance() = SettingsPageScaffold(title = "Персонализация", onBackClick = {}) {
        item {
            SettingsGroup {
                SettingsChoiceRow("Язык", listOf("Русский", "English"), "Русский", { it }, {}, icon = AppIcons.Language)
                SettingsChoiceRow("Размер шрифта", listOf("Мелкий", "Средний", "Крупный"), "Средний", { it }, {}, icon = AppIcons.TextFields)
            }
        }
        item {
            SettingsGroup {
                SettingsChoiceRow(
                    "Цветовая тема", AppColorTheme.entries, AppColorTheme.GREEN, { it.code }, {},
                    optionLeading = { Swatch(it) }, leading = { Swatch(AppColorTheme.GREEN, 40) }
                )
                SettingsChoiceRow("Режим темы", listOf("Системная", "Светлая", "Тёмная"), "Системная", { it }, {}, icon = AppIcons.Contrast)
                SettingsSwitchRow("Темы по дням недели", "Своя тема на каждый день", icon = { Icon(AppIcons.CalendarMonth, null) }, checked = true) {}
                SettingsNavRow("Настроить темы по дням", icon = AppIcons.Settings, onClick = {})
            }
        }
        item { SettingsSectionTitle("Очистка") }
        item {
            SettingsGroup {
                SettingsChoiceRow("Корзина", listOf(0, 3, 7, 14, 30, 60), 30, { "Раз в $it дней" }, {}, icon = AppIcons.Delete)
                SettingsNavRow("Удалить аккаунт", titleColor = androidx.compose.material3.MaterialTheme.colorScheme.error, trailingIcon = null, onClick = {})
            }
        }
        item { SettingsGroupHint("Письма старше срока удаляются на сервере") }
    }

    @Test fun hubLightPurple() { render(false, AppColorTheme.PURPLE) { Hub() }; shot("hub_light_purple") }
    @Test fun hubDarkYellow() { render(true, AppColorTheme.YELLOW) { Hub() }; shot("hub_dark_yellow") }
    @Test fun appearanceLightBlue() { render(false, AppColorTheme.BLUE) { Appearance() }; shot("appearance_light_blue") }
    @Test fun appearanceDarkGreen() { render(true, AppColorTheme.GREEN) { Appearance() }; shot("appearance_dark_green") }
    @Test fun appearanceFont2() { render(false, AppColorTheme.PURPLE, fontScale = 2f) { Appearance() }; shot("appearance_font2") }

    @Test fun dropdownOpen() {
        render(false, AppColorTheme.GREEN) { Appearance() }
        rule.onNodeWithText("Цветовая тема").performClick()
        rule.waitForIdle()
        shot("appearance_dropdown")
    }

    @Test fun sheetOpen() {
        render(false, AppColorTheme.BLUE) { Appearance() }
        rule.onNodeWithText("Корзина").performClick()
        rule.waitForIdle()
        shot("appearance_sheet")
    }
}

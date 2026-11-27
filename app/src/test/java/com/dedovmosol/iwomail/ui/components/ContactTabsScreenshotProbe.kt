package com.dedovmosol.iwomail.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.theme.AppColorTheme
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
 * Ручная визуальная проверка вкладок контактов: PNG в app/build/settings-shots (contact_tabs_*). Запуск: IWO_SHOTS=1
 * и `--no-daemon` (демон Gradle не передаёт переменные окружения в JVM тестов).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactTabsScreenshotProbe {

    @get:Rule
    val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before
    fun only() = Assume.assumeTrue(System.getenv("IWO_SHOTS") == "1")

    private fun shot(name: String, language: AppLanguage, fontScale: Float = 1f, picker: Boolean = false, dark: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides language) {
                ExchangeMailTheme(darkTheme = dark, dynamicColor = false, colorTheme = AppColorTheme.PURPLE, fontScale = fontScale) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxWidth()) {
                            val tabs = buildList {
                                add(ContactTab(Strings.contactsTabLocal, 37))
                                add(ContactTab(Strings.contactsTabPersonal, 1254))
                                add(ContactTab(Strings.contactsTabGlobal, 18342))
                                if (picker) add(ContactTab(Strings.contactGroups, 4))
                            }
                            ContactSourceTabs(
                                tabs = tabs, selected = 1, onSelect = {},
                                modifier = if (picker) Modifier.width(312.dp) else Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val dir = File(System.getProperty("user.dir"), "build/settings-shots").apply { mkdirs() }
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, (v.height).coerceAtMost(300 * v.resources.displayMetrics.density.toInt()), Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        File(dir, "contact_tabs_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun phone320Ru() = shot("320_ru", AppLanguage.RUSSIAN)

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phone411En() = shot("411_en", AppLanguage.ENGLISH, dark = true)

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun phone360RuLargeFont() = shot("360_ru_font130", AppLanguage.RUSSIAN, fontScale = 1.3f)

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun picker360Ru() = shot("picker_ru", AppLanguage.RUSSIAN, picker = true)
}

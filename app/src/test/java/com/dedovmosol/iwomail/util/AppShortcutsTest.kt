package com.dedovmosol.iwomail.util

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.MainActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ярлыки долгого тапа: только при наличии аккаунта и на языке приложения. Голый Application —
 * чтобы MailApplication не трогал ярлыки параллельно с тестом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppShortcutsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun published() = ShortcutManagerCompat.getDynamicShortcuts(context).associate { it.id to it.shortLabel.toString() }

    @Test
    fun `no shortcuts before login, all four after`() {
        AppShortcuts.update(context, hasAccounts = false, language = "ru")
        assertThat(published()).isEmpty()

        AppShortcuts.update(context, hasAccounts = true, language = "ru")
        assertThat(published()).containsExactly(
            "widget", "Виджет", "compose", "Написать", "inbox", "Входящие", "sync", "Синхронизация"
        )

        AppShortcuts.update(context, hasAccounts = false, language = "ru")
        assertThat(published()).isEmpty()
    }

    @Test
    fun `labels follow the app language`() {
        AppShortcuts.update(context, hasAccounts = true, language = "ru")
        AppShortcuts.update(context, hasAccounts = true, language = "en")

        assertThat(published()["sync"]).isEqualTo("Sync")
        assertThat(published()["compose"]).isEqualTo("Compose")
    }

    @Test
    fun `no static shortcuts and rotation without activity recreation`() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA
        )
        // Статические ярлыки нельзя скрыть до входа — их больше нет в манифесте.
        assertThat(info.metaData?.containsKey("android.app.shortcuts") ?: false).isFalse()
        // Поворот и смена размера окна не пересоздают Activity: состояние полей ввода (курсор) сохраняется.
        val handled = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_KEYBOARD_HIDDEN
        assertThat(info.configChanges and handled).isEqualTo(handled)
    }
}

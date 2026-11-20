package com.dedovmosol.iwomail.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** SET-12: кнопки виджета больше прежних 26–43 dp, где хватает места — 48 dp; у кнопок и аватаров есть названия. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetButtonsTest {

    @Test
    fun `wide widget gets the 48 dp minimum touch target`() {
        // 320 dp: строка 288 dp, 4 аватара по 37 + 4 dp
        val visual = (32 * 0.93f).dp
        assertThat(widgetButtonTouchSize(288.dp, (40 * 0.93f).dp * 4 + 4.dp * 4, visual)).isEqualTo(48.dp)
    }

    @Test
    fun `narrow widget shares what is left but never shrinks below the circle`() {
        val visual = (32 * 0.8f).dp
        // 180 dp: строка 148 dp, 2 аватара по 32 + 4 dp — (148 − 72 − 8) / 2 = 34 dp
        assertThat(widgetButtonTouchSize(148.dp, 72.dp, visual)).isEqualTo(34.dp)
        assertThat(widgetButtonTouchSize(148.dp, 72.dp, visual)).isGreaterThan(visual)
        // Места нет совсем — размер круга, а не меньше
        assertThat(widgetButtonTouchSize(60.dp, 72.dp, visual)).isEqualTo(visual)
    }

    @Test
    fun `private inbox clicks distinguish accounts without a message payload`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val first = privateInboxIntent(ctx, 1)
        val second = privateInboxIntent(ctx, 2)
        assertThat(first.filterEquals(second)).isFalse()
        assertThat(second.data?.host).isEqualTo("private-inbox")
        assertThat(second.component?.className).isEqualTo(com.dedovmosol.iwomail.MainActivity::class.java.name)
        assertThat(second.extras!!.keySet()).containsExactly(com.dedovmosol.iwomail.MainActivity.EXTRA_SWITCH_ACCOUNT_ID, com.dedovmosol.iwomail.MainActivity.EXTRA_OPEN_INBOX_UNREAD)
        assertThat(second.getLongExtra(com.dedovmosol.iwomail.MainActivity.EXTRA_SWITCH_ACCOUNT_ID, 0)).isEqualTo(2L)
    }

    @Test
    fun `buttons and avatars are named in both languages`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (locale in listOf(Locale.ENGLISH, Locale("ru"))) {
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            val ctx = base.createConfigurationContext(config)
            assertThat(ctx.getString(R.string.widget_compose)).isNotEmpty()
            assertThat(ctx.getString(R.string.widget_sync_now)).isNotEmpty()
            assertThat(ctx.getString(R.string.widget_account, "Work")).contains("Work")
            assertThat(ctx.getString(R.string.widget_account_unread, "Work", 5)).contains("5")
        }
        val ru = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale("ru")) })
        assertThat(ru.getString(R.string.widget_compose)).isEqualTo("Написать письмо")
    }
}

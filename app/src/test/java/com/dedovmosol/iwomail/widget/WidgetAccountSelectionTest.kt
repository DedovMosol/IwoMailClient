package com.dedovmosol.iwomail.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Выбор активного аккаунта на виджете: кольцо и подпись у активного, нажатие на другой — действие выбора (без
 * открытия приложения), на активный — открытие его почты; действие меняет активный аккаунт в настоящей БД.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "ru")
class WidgetAccountSelectionTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
    }

    @After
    fun tearDown() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
    }

    private fun acc(id: Long, active: Boolean = false) = AccountUnread(id, "Acc$id", 0xFF1976D2.toInt(), 0, active)

    @Test
    fun `avatars keep their order and always include the active account`() {
        val all = (1L..6L).map { acc(it, active = it == 5L) }
        assertThat(widgetAvatars(all, 4).map { it.id }).containsExactly(1L, 2L, 3L, 5L).inOrder()
        assertThat(widgetAvatars(all, 2).map { it.id }).containsExactly(1L, 5L).inOrder()
        val firstActive = (1L..6L).map { acc(it, active = it == 2L) }
        assertThat(widgetAvatars(firstActive, 4).map { it.id }).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(widgetAvatars((1L..3L).map { acc(it) }, 4).map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(widgetAvatars(all, 0)).isEmpty()
    }

    @Test
    fun `active avatar opens its mail, another avatar selects it without opening the app`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(320.dp, 280.dp))
        setContext(context)
        provideComposable {
            MailWidgetContent(
                WidgetData(accounts = listOf(acc(1, active = true), acc(2)), nextEvent = null, hasAccount = true),
                context
            )
        }
        onNode(hasContentDescriptionEqualTo("Аккаунт Acc1, активный"))
            .assert(
                hasStartActivityClickAction(
                    android.content.Intent(context, com.dedovmosol.iwomail.MainActivity::class.java)
                        .setData(android.net.Uri.parse("iwomail://account/1"))
                )
            )
        onNode(hasContentDescriptionEqualTo("Аккаунт Acc2. Нажмите, чтобы сделать активным"))
            .assert(
                hasRunCallbackClickAction<SelectWidgetAccountAction>(
                    actionParametersOf(SelectWidgetAccountAction.ACCOUNT_ID to 2L)
                )
            )
    }

    @Test
    fun `select action makes the account active and ignores a deleted account`() = runBlocking<Unit> {
        val dao = MailDatabase.getInstance(context).accountDao()
        val first = dao.insert(AccountEntity(email = "a@corp.local", displayName = "A", serverUrl = "https://x", username = "a"))
        val second = dao.insert(AccountEntity(email = "b@corp.local", displayName = "B", serverUrl = "https://x", username = "b"))
        dao.setActiveAccount(first)

        val action = SelectWidgetAccountAction()
        action.onAction(context, androidx.glance.appwidget.AppWidgetId(1), actionParametersOf(SelectWidgetAccountAction.ACCOUNT_ID to second))
        assertThat(dao.getActiveAccountSync()?.id).isEqualTo(second)

        // Снимок виджета старый: аккаунт уже удалён — активный не сбрасывается
        action.onAction(context, androidx.glance.appwidget.AppWidgetId(1), actionParametersOf(SelectWidgetAccountAction.ACCOUNT_ID to 999L))
        assertThat(dao.getActiveAccountSync()?.id).isEqualTo(second)
    }
}

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
    fun `widget content follows the selected account - mail, events, tasks, notes and counters`() = runBlocking<Unit> {
        val db = MailDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        suspend fun seed(email: String, tag: String, unread: Int, tasks: Int, notes: Int): Long {
            val id = db.accountDao().insert(AccountEntity(email = email, displayName = tag, serverUrl = "https://x", username = tag))
            val inbox = "${id}_inbox"
            db.folderDao().insert(com.dedovmosol.iwomail.data.database.FolderEntity(inbox, id, "inbox", "Входящие", "0", 2))
            repeat(unread) { n ->
                db.emailDao().insert(
                    com.dedovmosol.iwomail.data.database.EmailEntity(
                        id = "${id}_m$n", accountId = id, folderId = inbox, serverId = "m$n", from = "s@corp.local",
                        fromName = "От $tag", to = email, subject = "Письмо $tag $n", body = "", dateReceived = now - n * 1000L, read = false
                    )
                )
            }
            db.calendarEventDao().insert(
                com.dedovmosol.iwomail.data.database.CalendarEventEntity(
                    id = "${id}_e1", accountId = id, serverId = "e1", subject = "Встреча $tag", startTime = now + hour, endTime = now + 2 * hour
                )
            )
            repeat(tasks) { n ->
                db.taskDao().insert(com.dedovmosol.iwomail.data.database.TaskEntity(id = "${id}_t$n", accountId = id, serverId = "t$n", subject = "Задача $tag $n", dueDate = now - hour))
            }
            repeat(notes) { n ->
                db.noteDao().insert(com.dedovmosol.iwomail.data.database.NoteEntity(id = "${id}_n$n", accountId = id, serverId = "n$n", subject = "З", body = "", lastModified = now))
            }
            return id
        }
        val work = seed("work@corp.local", "Работа", unread = 2, tasks = 1, notes = 3)
        val home = seed("home@corp.local", "Дом", unread = 1, tasks = 2, notes = 1)
        RepositoryProvider.getSettingsRepository(context).setAppLockEnabled(false)

        db.accountDao().setActiveAccount(work)
        MailWidget().loadWidgetData(context).let { data ->
            assertThat(data.totalUnread).isEqualTo(2)
            assertThat(data.recentEmails.map { it.sender }.toSet()).containsExactly("От Работа")
            assertThat(data.nextEvent!!.title).isEqualTo("Встреча Работа")
            assertThat(data.nextTaskTitle).startsWith("Задача Работа")
            assertThat(data.todayTasksCount).isEqualTo(1)
            assertThat(data.activeTasksCount).isEqualTo(1)
            assertThat(data.notesCount).isEqualTo(3)
            assertThat(data.calendarEventsCount).isEqualTo(1)
            // Аватары — все аккаунты со своими числами
            assertThat(data.accounts.associate { it.id to it.unreadCount }).containsExactly(work, 2, home, 1)
        }

        db.accountDao().setActiveAccount(home)
        MailWidget().loadWidgetData(context).let { data ->
            assertThat(data.totalUnread).isEqualTo(1)
            assertThat(data.recentEmails.map { it.sender }.toSet()).containsExactly("От Дом")
            assertThat(data.nextEvent!!.title).isEqualTo("Встреча Дом")
            assertThat(data.nextTaskTitle).startsWith("Задача Дом")
            assertThat(data.todayTasksCount).isEqualTo(2)
            assertThat(data.activeTasksCount).isEqualTo(2)
            assertThat(data.notesCount).isEqualTo(1)
            assertThat(data.accounts.single { it.isActive }.id).isEqualTo(home)
        }
    }

    @Test
    fun `every widget element opens its own target`() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(530.dp, 600.dp))
        setContext(context)
        provideComposable { MailWidgetContent(WidgetSamples.full, context) }
        fun open(uri: String) = android.content.Intent(context, com.dedovmosol.iwomail.MainActivity::class.java).setData(android.net.Uri.parse(uri))
        for (uri in listOf(
            "iwomail://widget/search", "iwomail://widget/calendar", "iwomail://widget/tasks", "iwomail://widget/notes",
            "iwomail://widget/compose", "iwomail://email/1", "iwomail://email/2", "iwomail://email/3", "iwomail://account/1"
        )) {
            onAllNodes(hasStartActivityClickAction(open(uri))).assertCountEquals(1)
        }
        onAllNodes(
            androidx.glance.appwidget.testing.unit.hasSendBroadcastAction(
                android.content.Intent(com.dedovmosol.iwomail.sync.SyncAlarmReceiver.ACTION_SYNC_NOW)
                    .setClass(context, com.dedovmosol.iwomail.sync.SyncAlarmReceiver::class.java)
            )
        ).assertCountEquals(1)
        for (id in 2L..4L) {
            onAllNodes(hasRunCallbackClickAction<SelectWidgetAccountAction>(actionParametersOf(SelectWidgetAccountAction.ACCOUNT_ID to id)))
                .assertCountEquals(1)
        }
    }

    @Test
    fun `account colours - duplicates become distinct, new account gets an unused colour`() {
        val blue = com.dedovmosol.iwomail.domain.AccountColors.PALETTE[0]
        val green = com.dedovmosol.iwomail.domain.AccountColors.PALETTE[1]
        val shown = com.dedovmosol.iwomail.domain.AccountColors.distinct(listOf(blue, blue, green, blue))
        assertThat(shown.toSet()).hasSize(4)
        assertThat(shown[0]).isEqualTo(blue)
        assertThat(shown[2]).isEqualTo(green)
        assertThat(com.dedovmosol.iwomail.domain.AccountColors.forNewAccount(listOf(blue, green)))
            .isEqualTo(com.dedovmosol.iwomail.domain.AccountColors.PALETTE[2])
        val all = com.dedovmosol.iwomail.domain.AccountColors.PALETTE
        assertThat(com.dedovmosol.iwomail.domain.AccountColors.forNewAccount(all)).isIn(all)
    }

    @Test
    fun `without an active account the widget shows the first account`() {
        assertThat(widgetActiveAccountId(listOf(acc(3), acc(4)))).isEqualTo(3L)
        assertThat(widgetActiveAccountId(listOf(acc(3), acc(4, active = true)))).isEqualTo(4L)
        assertThat(widgetActiveAccountId(emptyList())).isEqualTo(-1L)
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

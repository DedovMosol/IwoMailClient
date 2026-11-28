@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.dedovmosol.iwomail.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.widget.FrameLayout
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.AppWidgetSession
import androidx.glance.appwidget.SizeMode
import androidx.glance.session.runSession
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.eas.FolderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ru-w411dp-h891dp")
class WidgetPrivacySessionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `warm widget views redact on App Lock and never restore the previous account`() = runBlocking<Unit> {
        val db = MailDatabase.getInstance(context)
        val settings = SettingsRepository.getInstance(context)
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        fun privateTexts(tag: String) = listOf("$tag Sender", "$tag Subject", "$tag Event", "$tag Task")

        suspend fun seed(tag: String, active: Boolean): Long {
            val address = "$tag@example.invalid"
            val id = db.accountDao().insert(AccountEntity(
                email = address, displayName = "$tag account", serverUrl = "example.invalid", username = tag,
                isActive = active
            ))
            val inbox = "${id}_inbox"
            db.folderDao().insert(FolderEntity(inbox, id, "inbox", "Inbox", "0", FolderType.INBOX))
            db.emailDao().insert(EmailEntity(
                id = "${id}_message", accountId = id, folderId = inbox, serverId = "message", from = address,
                fromName = "$tag Sender", to = address, subject = "$tag Subject", body = "", dateReceived = now, read = false
            ))
            db.calendarEventDao().insert(CalendarEventEntity(
                id = "${id}_event", accountId = id, serverId = "event", subject = "$tag Event",
                startTime = now + hour, endTime = now + 2 * hour
            ))
            db.taskDao().insert(TaskEntity(id = "${id}_task", accountId = id, serverId = "task", subject = "$tag Task", dueDate = now))
            return id
        }

        fun widgetSession(id: Int, width: Float, height: Float) = AppWidgetSession(
            MailWidget(), AppWidgetId(id),
            initialOptions = Bundle().apply {
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width, height)))
            },
            lambdaReceiver = ComponentName(context, MailWidgetReceiver::class.java),
            sizeMode = SizeMode.Exact, // One fixed-size RemoteViews per widget; bypasses the responsive loading preview.
            shouldPublish = false
        )
        suspend fun AppWidgetSession.rendered(predicate: (List<String>) -> Boolean): List<String> = withTimeout(12_000) {
            lastRemoteViews.filterNotNull().map { remote ->
                WidgetRenderSupport.texts(remote.apply(context, FrameLayout(context))).map { it.text.toString() }
            }.first(predicate)
        }

        seed("Work", true)
        val home = seed("Home", false)
        val workSecrets = privateTexts("Work")
        val homeSecrets = privateTexts("Home")
        val sessions = listOf(widgetSession(31, 400f, 420f), widgetSession(32, 530f, 600f))
        settings.setAppLockEnabled(false)
        val jobs = sessions.map { session -> launch(Dispatchers.Default) { session.runSession(context) } }
        try {
            sessions.forEach { session ->
                val original = session.rendered { "Work Sender" in it }
                assertTrue("Initial RemoteViews: $original", workSecrets.all { secret -> original.any { secret in it } })
            }

            settings.setAppLockEnabled(true)
            sessions.forEach { session ->
                val locked = session.rendered { text -> text.any { "Work account" in it } }
                assertFalse("Locked RemoteViews: $locked", workSecrets.any { secret -> locked.any { secret in it } })
            }

            db.accountDao().setActiveAccount(home)
            updateMailWidget(context)
            sessions.forEach { session ->
                val switched = session.rendered { text -> text.any { "Home account" in it } }
                assertFalse("Switched RemoteViews: $switched", (workSecrets + homeSecrets).any { secret -> switched.any { secret in it } })
            }

            settings.setAppLockEnabled(false)
            sessions.forEach { session ->
                val unlocked = session.rendered { "Home Sender" in it }
                assertTrue("Unlocked RemoteViews: $unlocked", homeSecrets.all { secret -> unlocked.any { secret in it } })
                assertFalse("Unlocked RemoteViews: $unlocked", workSecrets.any { secret -> unlocked.any { secret in it } })
            }
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            settings.setAppLockEnabled(false)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a late unlocked load cannot overwrite the masked view or a newer account`() = runTest {
        val lock = MutableStateFlow(false)
        val refresh = MutableStateFlow(0L)
        val finishOldLoad = CompletableDeferred<Unit>()
        val finishLockedLoad = CompletableDeferred<Unit>()
        val original = WidgetData(
            accounts = listOf(AccountUnread(1L, "Work", 0, 1, true)),
            nextEvent = EventInfo("Work Event", "12:00"), hasAccount = true,
            recentEmails = listOf(RecentEmail("old", "Work Sender", "Work Subject")),
            nextTaskTitle = "Work Task", totalUnread = 1
        )
        val newer = original.copy(recentEmails = listOf(RecentEmail("new", "Home Sender", "Home Subject")))
        val published = mutableListOf<WidgetData>()
        var loads = 0
        backgroundScope.launch {
            observeWidgetSnapshots(lock, { lock.value }, refresh, load = {
                when (++loads) {
                    1 -> original
                    2 -> withContext(NonCancellable) { finishOldLoad.await(); original }
                    3 -> withContext(NonCancellable) { finishLockedLoad.await(); original }
                    else -> newer
                }
            }, publish = { published += it })
        }
        try {
            runCurrent()
            assertEquals(listOf(original), published)
            refresh.value++
            runCurrent()
            assertEquals(2, loads)

            lock.value = true
            runCurrent()
            val redacted = published.last()
            assertTrue(redacted.contentHidden)
            assertTrue(redacted.recentEmails.isEmpty())
            assertEquals("", redacted.nextEvent?.title)
            assertEquals("", redacted.nextTaskTitle)
            assertEquals(1, redacted.totalUnread)
            finishOldLoad.complete(Unit)
            runCurrent()
            assertEquals(redacted, published.last())

            lock.value = false
            runCurrent()
            assertEquals(newer, published.last())
            finishLockedLoad.complete(Unit)
            runCurrent()
            assertEquals(newer, published.last())
            assertEquals(4, loads)
        } finally {
            finishOldLoad.complete(Unit)
            finishLockedLoad.complete(Unit)
        }
    }
}

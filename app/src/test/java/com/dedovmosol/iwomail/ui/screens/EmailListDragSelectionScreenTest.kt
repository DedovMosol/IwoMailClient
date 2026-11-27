package com.dedovmosol.iwomail.ui.screens

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.EmailEntity
import com.dedovmosol.iwomail.data.database.FolderEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.eas.FolderType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Протягивание во «Входящих» на настоящем экране: во время жеста синхронизация приносит новое письмо (Room выдаёт
 * новый список, модель списка перестраивается в фоне). Раньше на кадр перестройки выделение пропадало, а обновления
 * жеста отбрасывались — выделение застревало на тех строках, что успели до прихода письма.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmailListDragSelectionScreenTest {

    private var accountId = 0L
    private val inboxId get() = "${accountId}_inbox"

    private fun email(n: Int, date: Long) = EmailEntity(
        id = "${accountId}_m$n", accountId = accountId, folderId = inboxId, serverId = "m$n",
        from = "sender$n@corp.example", fromName = "Sender $n", to = "me@corp.example",
        subject = "Тема $n", body = "", dateReceived = date, read = true
    )

    private val seed = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                MailDatabase.resetForTesting()
                RepositoryProvider.resetForTesting()
                val context = ApplicationProvider.getApplicationContext<android.app.Application>()
                androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
                com.dedovmosol.iwomail.data.security.AppLockManager.init(context)
                runBlocking {
                    RepositoryProvider.getSettingsRepository(context).setAppLockEnabled(false)
                    val db = MailDatabase.getInstance(context)
                    accountId = db.accountDao().insert(
                        AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me")
                    )
                    RepositoryProvider.getAccountRepository(context).setActiveAccount(accountId)
                    db.folderDao().insert(FolderEntity(inboxId, accountId, "inbox", "Входящие", "0", FolderType.INBOX))
                    // Тема 1 — самое новое письмо, сверху
                    for (n in 1..14) db.emailDao().insert(email(n, 1_000_000L - n * 1_000L))
                }
                base.evaluate()
            }
        }
    }

    private val activityRule = ActivityScenarioRule<MainActivity>(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(MainActivity.ACTION_SHORTCUT_INBOX)
    )
    private val compose = AndroidComposeTestRule(activityRule) { rule ->
        var activity: MainActivity? = null
        rule.scenario.onActivity { activity = it }
        activity!!
    }

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(seed).around(compose)

    @After
    fun tearDown() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
    }

    private fun pump(frames: Int) = repeat(frames) {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(10) // подготовка модели списка — на Dispatchers.Default, Room — на своих потоках
    }

    private fun center(text: String): Offset {
        val b = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        return Offset(b.center.x, b.center.y)
    }

    /** Отмеченные значки выбора (они на аватаре строки). */
    private fun selectedMarks() = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)
    ).fetchSemanticsNodes()

    private fun selectedRows() = selectedMarks().size

    /** Темы строк, у которых отмечен значок выбора: значок и тема — в одной строке по вертикали. */
    private fun selectedSubjects(): Set<String> {
        val marks = selectedMarks().map { it.boundsInRoot.center.y }
        return (listOf(99) + (1..14)).map { "Тема $it" }.filter { subject ->
            val row = compose.onAllNodesWithText(subject).fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: return@filter false
            marks.any { it in row.top..row.bottom }
        }.toSet()
    }

    @Test
    fun `new mail arriving mid-drag does not stop the selection in Inbox`() {
        // Запуск: навигация по ярлыку ждёт аккаунт и папку (IO) — часы идут сами, пока не появится список
        // Первый кадр — вне ожидания: в холодной JVM (forkEvery) он один грузит классы Compose и нативную графику,
        // на CI это 10–15 с, и waitUntil засчитывал их в таймаут. Таймаут — потолок, а не пауза: ждём, пока список не появится
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitUntil(60_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Тема 3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.autoAdvance = false
        pump(30)
        val start = center("Тема 3")
        val pitch = center("Тема 4").y - start.y
        assertThat(pitch).isGreaterThan(0f)

        compose.onRoot().performTouchInput { down(start) }
        pump(60) // дольше таймаута долгого нажатия
        assertThat(selectedRows()).isEqualTo(1)
        repeat(10) { // ведём на 2 строки вниз: Тема 3–5
            compose.onRoot().performTouchInput { moveBy(Offset(0f, pitch / 5)) }
            pump(2)
        }
        assertThat(selectedRows()).isEqualTo(3)

        // Синхронизация принесла новое письмо — оно встаёт самым первым, все строки сдвигаются на одну вниз
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        runBlocking { MailDatabase.getInstance(context).emailDao().insert(email(99, 2_000_000L)) }
        repeat(5) { pump(4) }

        repeat(15) { // ещё 3 строки вниз
            compose.onRoot().performTouchInput { moveBy(Offset(0f, pitch / 5)) }
            pump(2)
        }
        compose.onRoot().performTouchInput { up() }
        pump(10)

        // Письмо дошло до экрана посреди жеста; выделение продолжилось после него, а не застряло на трёх строках
        compose.onNodeWithText("Всего: 15").assertExists()
        val selected = selectedSubjects()
        // Начало протягивания — на «Тема 3», дальше подряд вниз; письма выше начала и новое письмо не выделены
        assertThat(selected).containsAtLeast("Тема 3", "Тема 4", "Тема 5", "Тема 6", "Тема 7")
        assertThat(selected).containsNoneOf("Тема 1", "Тема 2", "Тема 99")
    }
}

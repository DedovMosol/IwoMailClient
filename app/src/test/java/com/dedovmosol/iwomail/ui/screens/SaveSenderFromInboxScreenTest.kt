package com.dedovmosol.iwomail.ui.screens

import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.ContactSource
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
 * Экран «Входящих» на настоящей БД: выделить письмо долгим нажатием, «Ещё» → «Отправителя в контакты» — отправитель
 * появляется в локальных контактах аккаунта (адрес из формы Exchange 2007 `"Имя" <адрес>`), выделение снимается.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaveSenderFromInboxScreenTest {

    private var accountId = 0L
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val seed = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                MailDatabase.resetForTesting()
                RepositoryProvider.resetForTesting()
                androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
                com.dedovmosol.iwomail.data.security.AppLockManager.init(context)
                runBlocking {
                    RepositoryProvider.getSettingsRepository(context).setAppLockEnabled(false)
                    val db = MailDatabase.getInstance(context)
                    accountId = db.accountDao().insert(
                        AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "127.0.0.1:9" /* без DNS и сети: тихая синхронизация при входе в папку сразу получает отказ */, username = "me")
                    )
                    RepositoryProvider.getAccountRepository(context).setActiveAccount(accountId)
                    val inbox = "${accountId}_inbox"
                    db.folderDao().insert(FolderEntity(inbox, accountId, "inbox", "Входящие", "0", FolderType.INBOX))
                    db.emailDao().insert(
                        EmailEntity(
                            id = "${accountId}_m1", accountId = accountId, folderId = inbox, serverId = "m1",
                            from = "\"Петров Пётр\" <petrov@corp.example>", fromName = "Петров Пётр", to = "me@corp.example",
                            subject = "Отчёт за квартал", body = "", dateReceived = 1_000_000L, read = true
                        )
                    )
                }
                base.evaluate()
            }
        }
    }

    // Экран списка напрямую, без MainActivity: навигация по ярлыку ждёт аккаунт с таймаутами и на CI нестабильна,
    // а проверяем мы меню выделения и сохранение. Данные — настоящая Room через RepositoryProvider, как в приложении
    private val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(seed).around(compose)

    @After
    fun tearDown() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
    }

    /**
     * учные часы после появления списка (как в EmailListDragSelectionScreenTest): с autoAdvance waitForIdle внутри
     * performClick крутит часы, пока Compose не простаивает, а бесконечная анимация (индикатор загрузки/синхронизации)
     * не простаивает никогда — на CI это висело без конца. аждое ожидание — с дедлайном по настенному времени.
     */
    private fun pump(frames: Int) = repeat(frames) {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(5) // Room и подготовка модели списка — на своих потоках
    }

    private fun pumpUntil(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("е дождались: $what за $timeoutMs мс")
            pump(2)
        }
    }

    private fun hasText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun localContacts() = runBlocking {
        MailDatabase.getInstance(context).contactDao().getContactsByAccountList(accountId).filter { it.source == ContactSource.LOCAL }
    }

    @Test
    fun `selection menu saves the sender to local contacts`() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.dedovmosol.iwomail.ui.LocalLanguage provides com.dedovmosol.iwomail.ui.AppLanguage.RUSSIAN) {
                com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    EmailListScreen(folderId = "${accountId}_inbox", onEmailClick = {}, onBackClick = {}, onComposeClick = {})
                }
            }
        }
        // Первый кадр и загрузка списка — с автоматическими часами; waitUntil сам ограничен по времени
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitUntil(60_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            hasText("Отчёт за квартал")
        }
        compose.mainClock.autoAdvance = false
        pump(10)

        // Долгое нажатие: палец держим дольше таймаута долгого нажатия, часы ведём сами
        val row = compose.onNodeWithText("Отчёт за квартал").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(row) }
        pump(60)
        compose.onRoot().performTouchInput { up() }
        pumpUntil("панель выбора") { compose.onAllNodesWithContentDescription("Ещё").fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithContentDescription("Ещё")[0].performClick()
        pumpUntil("меню выбора") { hasText("Отправителя в контакты") }
        compose.onNodeWithText("Отправителя в контакты").performClick()

        pumpUntil("контакт в базе") { localContacts().isNotEmpty() }
        val saved = localContacts().single()
        assertThat(saved.email).isEqualTo("petrov@corp.example")
        assertThat(saved.displayName).isEqualTo("Петров Пётр")
        // Действие снимает выделение: меню и панель выбора закрылись
        pumpUntil("выход из выделения") {
            !hasText("Отправителя в контакты") && compose.onAllNodesWithContentDescription("Ещё").fetchSemanticsNodes().isEmpty()
        }
    }
}

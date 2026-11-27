package com.dedovmosol.iwomail.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Аккаунты» в настройках: нажатие открывает почту аккаунта (главный экран), шестерёнка — его настройки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsAccountsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private var home = 0L
    private var work = 0L

    @Before
    fun seed() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
        runBlocking {
            val dao = MailDatabase.getInstance(context).accountDao()
            home = dao.insert(AccountEntity(email = "home@corp.example", displayName = "Home", serverUrl = "mail.corp.example", username = "home"))
            work = dao.insert(AccountEntity(email = "work@corp.example", displayName = "Work", serverUrl = "mail.corp.example", username = "work"))
            RepositoryProvider.getAccountRepository(context).setActiveAccount(home)
        }
    }

    @After
    fun tearDown() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
    }

    @Test
    fun `tapping an account makes it active and opens its mail, the gear opens its settings`() {
        var opened = 0
        val settingsOpened = mutableListOf<Long>()
        rule.setContent {
            SettingsAccountsScreen(
                onBackClick = {},
                onAddAccount = {},
                onAccountOpened = { opened++ },
                onNavigateToAccountSettings = { settingsOpened += it }
            )
        }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Work").assertExists() }.isSuccess }

        rule.onNodeWithText("Work").performClick()
        rule.waitUntil(5_000) { opened == 1 }
        val active = runBlocking { RepositoryProvider.getAccountRepository(context).getActiveAccountSync()?.id }
        assertThat(active).isEqualTo(work)
        assertThat(settingsOpened).isEmpty()

        rule.onNodeWithContentDescription("Настройки аккаунта: Home").performClick()
        rule.waitForIdle()
        assertThat(settingsOpened).containsExactly(home)
        assertThat(opened).isEqualTo(1)
    }
}

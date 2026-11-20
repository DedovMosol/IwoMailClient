package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.data.security.AppLockManager
import com.dedovmosol.iwomail.data.security.PasswordStorage
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NAV-04: блокировка включена, пароля блокировки на устройстве нет — вход только паролем учётной записи, после
 * него блокировка выключается. Неверный пароль её не снимает.
 */
@RunWith(RobolectricTestRunner::class)
// The screen uses the real database. Starting MailApplication's unrelated background sync
// here can contend with test account insertion before the recovery UI is even shown.
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockRecoveryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
        PasswordStorage.resetForTesting()
        AppLockManager.resetForTesting()
        // Хранилище блокировки без хеша — как после сброса хранилища ключей
        AppLockManager.setStorageForTesting(context.getSharedPreferences("recovery_${System.nanoTime()}", Context.MODE_PRIVATE))
        runBlocking {
            val id = MailDatabase.getInstance(context).accountDao().insert(
                AccountEntity(email = "me@corp.example", displayName = "Me", serverUrl = "mail.corp.example", username = "me")
            )
            RepositoryProvider.getAccountRepository(context).setActiveAccount(id)
            PasswordStorage.getInstance(context).savePassword(id, "account-pass")
            RepositoryProvider.getSettingsRepository(context).setAppLockEnabled(true)
        }
    }

    @After
    fun tearDown() {
        AppLockManager.resetForTesting()
        MailDatabase.resetForTesting()
        RepositoryProvider.resetForTesting()
        PasswordStorage.resetForTesting()
    }

    private val settings get() = RepositoryProvider.getSettingsRepository(context)

    @Test
    fun `only the account password turns the lock off`() {
        assertThat(AppLockManager.gate(AppLockManager.isLocked(), settings.getAppLockEnabledOrNull()))
            .isEqualTo(AppLockManager.Gate.RECOVERY)
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) { LockRecoveryScreen() }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("me@corp.example", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText("Account password").performTextInput("wrong")
        rule.onNodeWithText("Sign in and turn off the lock").performClick()
        rule.waitUntil(5_000) {
            AppLockManager.failedAttempts() == 1 && rule.onAllNodes(
                androidx.compose.ui.test.hasText("Account password") and androidx.compose.ui.test.isEnabled()
            ).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        assertThat(settings.getAppLockEnabledOrNull()).isTrue()

        rule.onNodeWithText("Account password").performTextReplacement("account-pass")
        rule.onNodeWithText("Sign in and turn off the lock").performClick()
        // The synchronous preference cache updates before the suspend DataStore write completes.
        // Wait for the entire recovery, not that intermediate flag alone.
        rule.waitUntil(5_000) { settings.getAppLockEnabledOrNull() == false && !AppLockManager.isLocked() }
        assertThat(AppLockManager.isLocked()).isFalse()
        assertThat(AppLockManager.failedAttempts()).isEqualTo(0)
        assertThat(AppLockManager.gate(AppLockManager.isLocked(), settings.getAppLockEnabledOrNull()))
            .isEqualTo(AppLockManager.Gate.OPEN)
    }
}

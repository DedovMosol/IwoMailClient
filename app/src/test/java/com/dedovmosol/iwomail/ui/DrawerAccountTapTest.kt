package com.dedovmosol.iwomail.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Боковое меню, список аккаунтов: нажатие на аккаунт делает его активным (почта этого аккаунта на главном экране),
 * шестерёнка — настройки этого аккаунта без смены активного. Раньше нажатие на аккаунт открывало его настройки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DrawerAccountTapTest {

    @get:Rule
    val rule = createComposeRule()

    private val work = AccountEntity(id = 1, email = "work@corp.local", displayName = "Работа", serverUrl = "https://mail.corp.local", username = "work")
    private val home = AccountEntity(id = 2, email = "home@corp.local", displayName = "Дом", serverUrl = "https://mail.corp.local", username = "home")

    private val selected = mutableListOf<Long>()
    private val settings = mutableListOf<Long>()

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.RUSSIAN) {
                DrawerContent(
                    accounts = listOf(work, home),
                    activeAccount = work,
                    folders = emptyList(),
                    flaggedCount = 0,
                    showAccountPicker = true,
                    onToggleAccountPicker = {},
                    onAccountSettingsClick = { settings += it },
                    onAccountSelected = { selected += it },
                    onAddAccount = {},
                    onFolderSelected = {},
                    onFavoritesClick = {},
                    onSettingsClick = {}
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `tapping another account selects it and does not open its settings`() {
        show()
        rule.onNodeWithText("home@corp.local", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertThat(selected).containsExactly(2L)
        assertThat(settings).isEmpty()
    }

    @Test
    fun `gear opens settings of that account without switching the active one`() {
        show()
        rule.onNodeWithContentDescription("Настройки аккаунта: Дом").performClick()
        rule.waitForIdle()
        assertThat(settings).containsExactly(2L)
        assertThat(selected).isEmpty()
    }
}

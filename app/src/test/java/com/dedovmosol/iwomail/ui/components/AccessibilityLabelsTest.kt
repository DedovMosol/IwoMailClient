package com.dedovmosol.iwomail.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.dedovmosol.iwomail.data.database.SignatureEntity
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.screens.SignaturesManagementDialog
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SET-13: у кнопок-иконок есть названия, у «Показать пароль» — состояние, которое объявляет TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityLabelsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun toggleState(state: ToggleableState) =
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    @Test
    fun `password toggle is a named switch with its state`() {
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.RUSSIAN) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    var visible by remember { mutableStateOf(false) }
                    PasswordVisibilityToggle(visible) { visible = it }
                }
            }
        }
        val toggle = rule.onNodeWithContentDescription("Показать пароль", useUnmergedTree = false)
        toggle.assert(toggleState(ToggleableState.Off))
        toggle.performClick()
        toggle.assert(toggleState(ToggleableState.On))
    }

    @Test
    fun `each signature delete button names its signature`() {
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    SignaturesManagementDialog(
                        isRu = false,
                        accountId = 1L,
                        signatures = listOf(
                            SignatureEntity(id = 1, accountId = 1, name = "Work", text = "W", isDefault = true),
                            SignatureEntity(id = 2, accountId = 1, name = "Short", text = "S")
                        ),
                        onSignaturesChanged = {},
                        onDismiss = {}
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Delete signature \"Work\"").assertExists()
        rule.onNodeWithContentDescription("Delete signature \"Short\"").assertExists()
    }
}

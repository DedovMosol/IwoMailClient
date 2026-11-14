package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.theme.AppIcons
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHubUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `hub row shows title subtitle and reports the click`() {
        var clicks = 0
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    SettingsGroup {
                        SettingsHubRow(
                            title = "Mail",
                            subtitle = "Conversations, swipes, images",
                            icon = AppIcons.Email,
                            onClick = { clicks++ }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Mail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Conversations, swipes, images").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mail").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `nav row click opens via the callback`() {
        var clicks = 0
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    SettingsNavRow(
                        title = "Swipe right",
                        subtitle = "Delete",
                        icon = AppIcons.ChevronRight,
                        onClick = { clicks++ }
                    )
                }
            }
        }
        composeTestRule.onNodeWithText("Swipe right").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swipe right").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `disabled nav row does not fire click`() {
        var clicks = 0
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    SettingsNavRow(
                        title = "Color theme",
                        subtitle = "Daily themes active",
                        enabled = false,
                        onClick = { clicks++ }
                    )
                }
            }
        }
        composeTestRule.onNodeWithText("Color theme").performClick()
        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun `scroll scaffold pins footer to the bottom of the viewport`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    SettingsScrollScaffold(
                        title = "About",
                        onBackClick = {},
                        footer = { androidx.compose.material3.Text("footer") }
                    ) {
                        androidx.compose.material3.Text("top")
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("top").assertIsDisplayed()
        composeTestRule.onNodeWithText("footer").assertIsDisplayed()
        val footerBottom = composeTestRule.onNodeWithText("footer").fetchSemanticsNode().boundsInRoot.bottom
        val rootBottom = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertThat(rootBottom - footerBottom).isAtMost(48f)
    }
}

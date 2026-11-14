package com.dedovmosol.iwomail.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}

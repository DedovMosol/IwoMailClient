package com.dedovmosol.iwomail.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Иконка запроса совпадает с темой: не батарея на точные будильники. «Позже» у левого края, «Продолжить» у правого —
 * не рядом, мимо не нажать.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppPermissionDialogTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `background work uses the battery icon, exact alarms use the clock`() {
        assertThat(AppPermissionPrompt.BackgroundWork.iconKey()).isEqualTo("battery")
        assertThat(AppPermissionPrompt.ExactAlarms.iconKey()).isEqualTo("schedule")
        assertThat(AppPermissionPrompt.BackgroundWork.iconKey())
            .isNotEqualTo(AppPermissionPrompt.ExactAlarms.iconKey())
    }

    @Test
    fun `later sits at the start and continue at the end`() {
        var dismissed = 0
        var confirmed = 0
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    AppPermissionDialog(
                        prompt = AppPermissionPrompt.BackgroundWork,
                        title = "Background work",
                        text = "The app needs to work in the background.",
                        dismissText = "Later",
                        confirmText = "Continue",
                        onDismiss = { dismissed++ },
                        onConfirm = { confirmed++ }
                    )
                }
            }
        }
        val later = rule.onNodeWithText("Later").fetchSemanticsNode().boundsInRoot
        val proceed = rule.onNodeWithText("Continue").fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithText("The app needs to work in the background.").fetchSemanticsNode().boundsInRoot
        // «Позже» — у левого края текста (с полем кнопки), «Продолжить» — у правого
        assertThat(later.left - text.left).isLessThan(text.width * 0.15f)
        assertThat(text.right - proceed.right).isLessThan(text.width * 0.15f)
        // Между кнопками — заметный промежуток
        assertThat(proceed.left - later.right).isGreaterThan(text.width * 0.2f)

        rule.onNodeWithText("Later").performClick()
        rule.onNodeWithText("Continue").performClick()
        assertThat(dismissed).isEqualTo(1)
        assertThat(confirmed).isEqualTo(1)
    }
}

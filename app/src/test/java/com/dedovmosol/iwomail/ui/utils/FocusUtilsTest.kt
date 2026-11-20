package com.dedovmosol.iwomail.ui.utils

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Восстановление фокуса не роняет экран, когда поля нет в композиции (копия свёрнута после поворота):
 * requestFocus на таком FocusRequester бросает IllegalStateException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusUtilsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `an unattached requester is skipped, the plain call would throw`() {
        val requester = FocusRequester()
        // No composition is required for a requester that has no attached modifier.
        rule.runOnUiThread {
            assertThat(runCatching { requester.requestFocus() }.exceptionOrNull())
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(requester.tryRequestFocus()).isFalse()
        }
    }

    @Test
    fun `an attached requester gets focus`() {
        val requester = FocusRequester()
        var focused = false
        var result: Boolean? = null
        rule.setContent {
            var text by remember { mutableStateOf("") }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.focusRequester(requester).onFocusChanged { focused = it.isFocused }
            )
            LaunchedEffect(Unit) { result = requester.tryRequestFocus() }
        }
        rule.waitForIdle()
        assertThat(result).isTrue()
        assertThat(focused).isTrue()
    }
}

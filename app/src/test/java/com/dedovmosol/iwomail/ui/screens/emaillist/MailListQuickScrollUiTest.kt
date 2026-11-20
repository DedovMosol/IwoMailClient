package com.dedovmosol.iwomail.ui.screens.emaillist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MailListQuickScrollUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `jumps to displayed edges and disappears during selection or on a short list`() {
        val rows = mutableIntStateOf(30)
        val selecting = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                val list = rememberLazyListState()
                Box {
                    LazyColumn(state = list, modifier = Modifier.height(160.dp)) {
                        items(rows.intValue) { Text("Row $it", modifier = Modifier.height(40.dp)) }
                    }
                    MailListQuickScroll(list, enabled = !selecting.value)
                }
            }
        }
        val end = rule.onNodeWithContentDescription("To end of list")
        val start = rule.onNodeWithContentDescription("To start of list")
        end.assertIsDisplayed().performClick()
        rule.onNodeWithText("Row 29").assertIsDisplayed()
        start.assertIsDisplayed().performClick()
        rule.onNodeWithText("Row 0").assertIsDisplayed()
        rule.runOnIdle { selecting.value = true }
        end.assertDoesNotExist()
        start.assertDoesNotExist()
        rule.runOnIdle { selecting.value = false; rows.intValue = 1 }
        rule.onNodeWithText("Row 0").assertIsDisplayed()
        end.assertDoesNotExist()
        start.assertDoesNotExist()
    }
}

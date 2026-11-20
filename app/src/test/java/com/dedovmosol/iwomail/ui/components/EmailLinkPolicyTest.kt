package com.dedovmosol.iwomail.ui.components

import com.dedovmosol.iwomail.ui.components.EmailLinkPolicy.Action
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** MAIL-10: из письма открывается только нажатое пользователем и только безопасными схемами. */
class EmailLinkPolicyTest {

    @Test
    fun `a tapped link opens by its scheme`() {
        assertThat(EmailLinkPolicy.decide("https", isForMainFrame = true, hasGesture = true)).isEqualTo(Action.BROWSE)
        assertThat(EmailLinkPolicy.decide("HTTP", isForMainFrame = true, hasGesture = true)).isEqualTo(Action.BROWSE)
        assertThat(EmailLinkPolicy.decide("mailto", isForMainFrame = true, hasGesture = true)).isEqualTo(Action.MAIL)
        assertThat(EmailLinkPolicy.decide("tel", isForMainFrame = true, hasGesture = true)).isEqualTo(Action.DIAL)
    }

    @Test
    fun `other schemes are refused`() {
        for (scheme in listOf("intent", "javascript", "file", "content", "data", "market", "sms", null)) {
            assertThat(EmailLinkPolicy.decide(scheme, isForMainFrame = true, hasGesture = true)).isEqualTo(Action.BLOCK)
        }
    }

    @Test
    fun `navigation without a tap or from a frame opens nothing`() {
        assertThat(EmailLinkPolicy.decide("https", isForMainFrame = true, hasGesture = false)).isEqualTo(Action.IGNORE)
        assertThat(EmailLinkPolicy.decide("mailto", isForMainFrame = false, hasGesture = true)).isEqualTo(Action.IGNORE)
        assertThat(EmailLinkPolicy.decide("intent", isForMainFrame = true, hasGesture = false)).isEqualTo(Action.IGNORE)
    }
}

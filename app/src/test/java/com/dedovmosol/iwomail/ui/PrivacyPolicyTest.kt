package com.dedovmosol.iwomail.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrivacyPolicyTest {

    @Test
    fun `russian UI opens the russian policy file`() {
        assertThat(PrivacyPolicy.url(russian = true)).isEqualTo(PrivacyPolicy.RU)
        assertThat(PrivacyPolicy.RU).endsWith("PRIVACY_POLICY_RU.md")
    }

    @Test
    fun `english UI opens the english policy file`() {
        assertThat(PrivacyPolicy.url(russian = false)).isEqualTo(PrivacyPolicy.EN)
        assertThat(PrivacyPolicy.EN).endsWith("PRIVACY_POLICY_EN.md")
    }
}

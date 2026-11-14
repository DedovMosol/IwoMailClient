package com.dedovmosol.iwomail.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Иконка запроса совпадает с темой: не батарея на точные будильники. */
class AppPermissionDialogTest {

    @Test
    fun `background work uses the battery icon, exact alarms use the clock`() {
        assertThat(AppPermissionPrompt.BackgroundWork.iconKey()).isEqualTo("battery")
        assertThat(AppPermissionPrompt.ExactAlarms.iconKey()).isEqualTo("schedule")
        assertThat(AppPermissionPrompt.BackgroundWork.iconKey())
            .isNotEqualTo(AppPermissionPrompt.ExactAlarms.iconKey())
    }
}

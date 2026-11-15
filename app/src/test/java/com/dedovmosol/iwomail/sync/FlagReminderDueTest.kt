package com.dedovmosol.iwomail.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FlagReminderDueTest {

    @Test
    fun `isDue matches within a minute and rejects moved flags`() {
        val t = 1_700_000_000_000L
        assertThat(FlagReminderReceiver.isDue(t, t)).isTrue()
        assertThat(FlagReminderReceiver.isDue(t + 60_000L, t)).isTrue()
        assertThat(FlagReminderReceiver.isDue(t + 60_001L, t)).isFalse()
        assertThat(FlagReminderReceiver.isDue(null, t)).isFalse()
    }
}

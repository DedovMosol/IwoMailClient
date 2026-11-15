package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class BackgroundWorkAlarmTest {

    @Test
    fun `pendingCode differs by id kind and slot`() {
        val a = BackgroundWork.pendingCode(BackgroundWork.KIND_CALENDAR, "event-a")
        val b = BackgroundWork.pendingCode(BackgroundWork.KIND_CALENDAR, "event-b")
        assertThat(a).isNotEqualTo(b)
        assertThat(BackgroundWork.pendingCode(BackgroundWork.KIND_CALENDAR, "event-a", 1))
            .isNotEqualTo(BackgroundWork.pendingCode(BackgroundWork.KIND_CALENDAR, "event-a", 2))
        assertThat(BackgroundWork.pendingCode(BackgroundWork.KIND_TASK, "event-a"))
            .isNotEqualTo(a)
        assertThat(BackgroundWork.pendingCode(BackgroundWork.KIND_CALENDAR, "event-a", 0)).isEqualTo(a)
        val id = "account_server-long-id"
        assertThat(BackgroundWork.legacyAlarmCode(id)).isEqualTo((id.hashCode() and 0x7FFFFFFF) % 1_000_000)
        assertThat(BackgroundWork.pendingCode(BackgroundWork.KIND_CALENDAR, id, 0))
            .isNotEqualTo(BackgroundWork.legacyAlarmCode(id))
    }

    @Test
    fun `scheduleRtcWakeup on api 26 reports exact and does not throw`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx,
            42,
            Intent("com.dedovmosol.iwomail.TEST_ALARM"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            val exact = BackgroundWork.scheduleRtcWakeup(am, System.currentTimeMillis() + 60_000L, pi)
            assertThat(exact).isTrue()
        } finally {
            am.cancel(pi)
        }
    }
}

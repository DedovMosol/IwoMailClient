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

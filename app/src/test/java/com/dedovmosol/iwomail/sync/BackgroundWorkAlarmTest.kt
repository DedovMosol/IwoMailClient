package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class BackgroundWorkAlarmTest {

    @Test
    @Config(sdk = [26, 33, 34])
    fun `battery request uses the public package-scoped exemption intent`() {
        val launches = mutableListOf<Intent>()
        assertThat(BackgroundWork.openBatterySettings("test.package") { launches.add(it) }).isTrue()
        val request = launches.single()
        assertThat(request.action).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(request.data.toString()).isEqualTo("package:test.package")
    }

    @Test
    @Config(sdk = [26, 33, 34])
    fun `missing exemption request falls back to optimization settings without a package URI`() {
        val launches = mutableListOf<Intent>()
        assertThat(BackgroundWork.openBatterySettings("test.package") {
            launches.add(it)
            if (launches.size == 1) throw ActivityNotFoundException()
        }).isTrue()
        assertThat(launches.last().action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        assertThat(launches.last().data).isNull()
        assertThat(launches).hasSize(2)
    }

    @Test
    fun `OEM restriction falls back to app details and reports no launch if all handlers fail`() {
        val launches = mutableListOf<Intent>()
        assertThat(BackgroundWork.openBatterySettings("test.package") {
            launches.add(it)
            if (launches.size < 3) throw SecurityException()
        }).isTrue()
        assertThat(launches.last().action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(launches.last().data.toString()).isEqualTo("package:test.package")
        assertThat(BackgroundWork.openBatterySettings("test.package") { throw ActivityNotFoundException() }).isFalse()
    }

    @Test
    @Config(sdk = [33, 34])
    fun `return advances only when exemption is actually granted and exact alarms still need permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        power.setIgnoringBatteryOptimizations(context.packageName, false)
        assertThat(BackgroundWork.needsExactAlarmsPrompt(context)).isFalse()
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        assertThat(BackgroundWork.needsExactAlarmsPrompt(context)).isTrue()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(BackgroundWork.needsExactAlarmsPrompt(context)).isFalse()
    }

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

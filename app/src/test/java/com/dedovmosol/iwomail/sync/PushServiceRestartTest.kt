package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * BG-01: без PUSH-аккаунтов сервис останавливается как явно остановленный и не перезапускает себя каждые 60 с.
 * BG-02 / BG-03: старт из фона и после загрузки на Android 15+ идёт разрешённым путём (точный будильник
 * и WorkManager), а не падает исключением.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushServiceRestartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("push_service", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        resetDatabaseSingleton()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() = resetDatabaseSingleton()

    private fun resetDatabaseSingleton() = com.dedovmosol.iwomail.data.database.MailDatabase.resetForTesting()

    private fun restartWork(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("push_restart").get()
            .filter { !it.state.isFinished }

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Test
    fun `service without push accounts stops as explicit stop and schedules no restart`() {
        val controller = Robolectric.buildService(PushService::class.java).create()
        controller.startCommand(0, 1)
        val service = controller.get()

        // Проверка аккаунтов идёт на IO, остановка — на главном потоке
        val deadline = System.currentTimeMillis() + 10_000
        while (!shadowOf(service).isStoppedBySelf && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertThat(shadowOf(service).isStoppedBySelf).isTrue()
        assertThat(prefs.getBoolean("explicit_stop", false)).isTrue()

        controller.destroy()

        assertThat(restartWork()).isEmpty()
        assertThat(alarms()).isEmpty()
    }

    @Test
    fun `explicit stop cancels a pending restart`() {
        PushService.requestRestart(context, 60)
        assertThat(restartWork()).isNotEmpty()
        assertThat(alarms()).isNotEmpty()

        PushService.stop(context)

        assertThat(restartWork()).isEmpty()
        assertThat(alarms().none { isRestart(it) }).isTrue()
    }

    @Test
    fun `boot on Android 15 schedules an exemption-safe restart instead of starting dataSync FGS`() {
        PushService.startOrSchedule(context, fromBoot = true, sdkInt = 35)

        assertThat(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService).isNull()
        assertThat(restartWork()).hasSize(1)
    }

    @Test
    fun `boot before Android 15 starts the service directly`() {
        PushService.startOrSchedule(context, fromBoot = true, sdkInt = 34)

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService
        assertThat(started?.component).isEqualTo(ComponentName(context, PushService::class.java))
        assertThat(restartWork()).isEmpty()
    }

    @Test
    fun `background start rejected by the system falls back to a scheduled restart`() {
        val background = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent?): ComponentName? =
                throw ForegroundServiceStartNotAllowedException("app is in background")
        }

        PushService.startOrSchedule(background)

        assertThat(restartWork()).hasSize(1)
    }

    @Test
    fun `restart worker does nothing without push accounts`() {
        val worker = androidx.work.testing.TestListenableWorkerBuilder<PushRestartWorker>(context).build()

        val result = kotlinx.coroutines.runBlocking { worker.doWork() }

        assertThat(result).isEqualTo(androidx.work.ListenableWorker.Result.success())
        assertThat(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService).isNull()
    }

    private fun isRestart(alarm: org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm): Boolean {
        val intent = shadowOf(alarm.operation).savedIntent
        return intent.component == ComponentName(context, PushService::class.java)
    }
}

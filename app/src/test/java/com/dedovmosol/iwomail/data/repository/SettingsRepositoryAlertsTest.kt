package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.domain.QuietHours
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Настройки уведомлений о почте: тихие часы, тактильный отклик, стирание списка «Важных отправителей». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryAlertsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(scope = scope) { context.dataStoreFile("alerts_${System.nanoTime()}.preferences_pb") }
    private val repo = SettingsRepository.createForTesting(context, store, startCacheCollector = false)

    @After
    fun tearDown() {
        repo.cleanup()
        scope.cancel()
    }

    @Test
    fun `quiet hours are off by default with a 22-07 window ready`() = runBlocking<Unit> {
        val prefs = repo.mailAlertPrefs.first()

        assertThat(prefs.quietHours).isEqualTo(QuietHours(false, QuietHours.DEFAULT_START, QuietHours.DEFAULT_END))
    }

    @Test
    fun `quiet hours are stored and out-of-range minutes fall back to defaults`() = runBlocking<Unit> {
        repo.setQuietHours(QuietHours(enabled = true, startMinutes = 23 * 60, endMinutes = 6 * 60 + 30))
        assertThat(repo.mailAlertPrefs.first().quietHours).isEqualTo(QuietHours(true, 23 * 60, 6 * 60 + 30))

        repo.setQuietHours(QuietHours(enabled = true, startMinutes = -5, endMinutes = 5_000))
        assertThat(repo.mailAlertPrefs.first().quietHours)
            .isEqualTo(QuietHours(true, QuietHours.DEFAULT_START, QuietHours.DEFAULT_END))
    }

    @Test
    fun `the removed important senders list is erased from storage and other settings stay`() = runBlocking<Unit> {
        val legacy = androidx.datastore.preferences.core.stringSetPreferencesKey("vip_senders")
        store.edit { it[legacy] = setOf("boss@corp.example") }
        repo.setQuietHours(QuietHours(enabled = true, startMinutes = 23 * 60, endMinutes = 7 * 60))

        repo.removeLegacyVipSenders()
        repo.removeLegacyVipSenders() // повторный запуск приложения — без ошибок

        assertThat(store.data.first()[legacy]).isNull()
        assertThat(repo.mailAlertPrefs.first().quietHours).isEqualTo(QuietHours(true, 23 * 60, 7 * 60))
    }

    @Test
    fun `haptic feedback is on by default and the switch is stored`() = runBlocking<Unit> {
        assertThat(repo.hapticsEnabled.first()).isTrue()
        assertThat(repo.getHapticsEnabledSync()).isTrue()

        repo.setHapticsEnabled(false)
        assertThat(repo.hapticsEnabled.first()).isFalse()
        assertThat(repo.getHapticsEnabledSync()).isFalse()
    }
}

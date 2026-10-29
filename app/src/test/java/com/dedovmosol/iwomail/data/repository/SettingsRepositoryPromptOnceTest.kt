package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Вопросы о фоновой работе и точных будильниках — один раз на установку, даже при одновременных запросах. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryPromptOnceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repo = SettingsRepository.createForTesting(
        context,
        PreferenceDataStoreFactory.create(scope = scope) { context.dataStoreFile("prompt_${System.nanoTime()}.preferences_pb") },
        startCacheCollector = false
    )

    @After
    fun tearDown() {
        repo.cleanup()
        scope.cancel()
    }

    @Test
    fun `each prompt is claimed once`() = runBlocking<Unit> {
        assertThat(repo.claimBackgroundWorkPrompt()).isTrue()
        assertThat(repo.claimBackgroundWorkPrompt()).isFalse()
        // Отдельный вопрос — своя отметка
        assertThat(repo.claimExactAlarmsPrompt()).isTrue()
        assertThat(repo.claimExactAlarmsPrompt()).isFalse()
    }

    @Test
    fun `simultaneous claims show the dialog only once`() = runBlocking<Unit> {
        val claims = (1..8).map { async(Dispatchers.IO) { repo.claimBackgroundWorkPrompt() } }.awaitAll()
        assertThat(claims.count { it }).isEqualTo(1)
    }
}

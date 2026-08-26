package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты флагов блокировки приложения в [SettingsRepository]
 * (цель релиза «пароль + дактилоскопия»).
 *
 * Проверяет: дефолты (выключено), персистентность через DataStore, мгновенный
 * синк-кэш (паттерн themeMode: сеттер пишет кэш до ожидания эха коллектора)
 * и независимость двух флагов (блокировка и биометрия). Флаги — только
 * настройки; сам секрет (хеш пароля) в DataStore не попадает — это зона
 * ответственности AppLockManager (SOC).
 *
 * Изоляция по официальному паттерну тестирования DataStore
 * (тот же, что в SettingsRepositoryThemeModeTest): уникальный файл на тест,
 * экземпляр через [SettingsRepository.createForTesting], scope гасится в конце.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryAppLockTest {

    private val testContext: Context = ApplicationProvider.getApplicationContext()

    /** Активные пары (scope → файл), гасятся в конце каждого теста. */
    private val activeScopes = mutableListOf<CoroutineScope>()

    private fun newIsolatedStore(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        activeScopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) {
            testContext.dataStoreFile("app_lock_test_${System.nanoTime()}.preferences_pb")
        }
    }

    private fun newRepo(): SettingsRepository = runBlocking {
        SettingsRepository.createForTesting(testContext, newIsolatedStore())
    }

    private fun finish() {
        activeScopes.forEach { it.cancel() }
        activeScopes.clear()
    }

    @Test
    fun `app lock flags default to disabled`() = runTest {
        val repo = newRepo()
        assertThat(repo.appLockEnabled.first()).isFalse()
        assertThat(repo.appLockBiometricEnabled.first()).isFalse()
        // Холодный кэш до первого эмитта — тоже выключено (детерминированный дефолт).
        assertThat(repo.getAppLockEnabledSync()).isFalse()
        assertThat(repo.getAppLockBiometricEnabledSync()).isFalse()
        repo.cleanup()
        finish()
    }

    @Test
    fun `setAppLockEnabled persists and flow emits true`() = runTest {
        val repo = newRepo()
        repo.setAppLockEnabled(true)
        assertThat(repo.appLockEnabled.first()).isTrue()
        repo.cleanup()
        finish()
    }

    @Test
    fun `setAppLockBiometricEnabled persists independently`() = runTest {
        val repo = newRepo()
        // Биометрию можно включить при выключенной блокировке на уровне данных —
        // гейт в MainActivity всё равно требует оба флага (защита в глубину).
        repo.setAppLockBiometricEnabled(true)
        assertThat(repo.appLockBiometricEnabled.first()).isTrue()
        assertThat(repo.appLockEnabled.first()).isFalse()
        repo.cleanup()
        finish()
    }

    @Test
    fun `sync cache reflects value immediately after set (no race)`() = runTest {
        // Паттерн темы: сеттер пишет кэш ДО ожидания записи в файл — синк-геттер
        // обязан вернуть новое значение сразу (используется гейтом и подпиской
        // ре-лока в MailApplication).
        val repo = newRepo()
        repo.setAppLockEnabled(true)
        assertThat(repo.getAppLockEnabledSync()).isTrue()
        repo.setAppLockBiometricEnabled(true)
        assertThat(repo.getAppLockBiometricEnabledSync()).isTrue()
        repo.setAppLockEnabled(false)
        assertThat(repo.getAppLockEnabledSync()).isFalse()
        repo.cleanup()
        finish()
    }

    @Test
    fun `disable reverts flags`() = runTest {
        val repo = newRepo()
        repo.setAppLockEnabled(true)
        repo.setAppLockBiometricEnabled(true)
        repo.setAppLockBiometricEnabled(false)
        repo.setAppLockEnabled(false)
        assertThat(repo.appLockEnabled.first()).isFalse()
        assertThat(repo.appLockBiometricEnabled.first()).isFalse()
        repo.cleanup()
        finish()
    }
}

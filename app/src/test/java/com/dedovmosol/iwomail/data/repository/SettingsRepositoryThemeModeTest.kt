package com.dedovmosol.iwomail.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-тесты персистентности и синк-кэша ThemeMode.
 *
 * Изоляция по официальному паттерну тестирования DataStore:
 * PreferenceDataStoreFactory.create(...) + уникальный файл на каждый тест,
 * экземпляр репозитория через [SettingsRepository.createForTesting]
 * (Dependency Inversion). Статический прод-синглтон не трогается.
 *
 * Жёсткое правило DataStore: на один файл может существовать только ОДИН
 * активный экземпляр, поэтому каждый тест использует отдельный файл и
 * гасит свой scope после работы (нет утечки корутин, нет «multiple
 * instances of DataStore»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryThemeModeTest {

    private val testContext: Context = ApplicationProvider.getApplicationContext()

    /** Активные пары (scope → файл), гасятся в конце каждого теста. */
    private val activeScopes = mutableListOf<CoroutineScope>()

    /**
     * Изолированный DataStore: уникальный файл + собственный scope.
     * Вызывающий обязан завершить тест через [finish], который отменит
     * все созданные scope (освобождение файла + отсутствие утечки джоб).
     */
    private fun newIsolatedStore(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        activeScopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) {
            testContext.dataStoreFile("theme_test_${System.nanoTime()}.preferences_pb")
        }
    }

    /** Свежий репозиторий поверх чистого изолированного хранилища. */
    private fun newRepo(): SettingsRepository = runBlocking {
        SettingsRepository.createForTesting(testContext, newIsolatedStore())
    }

    /** Остановка всех созданных хранилищ — без этого файл нельзя переиспользовать. */
    private fun finish() {
        activeScopes.forEach { it.cancel() }
        activeScopes.clear()
    }

    @Test
    fun `default theme mode is SYSTEM when key never written`() = runTest {
        val repo = newRepo()
        assertEquals(SettingsRepository.ThemeMode.SYSTEM, repo.themeMode.first())
        assertEquals(SettingsRepository.ThemeMode.SYSTEM, repo.getThemeModeSync())
        repo.cleanup()
        finish()
    }

    @Test
    fun `all three modes roundtrip through DataStore`() = runTest {
        val repo = newRepo()
        for (mode in SettingsRepository.ThemeMode.values()) {
            repo.setThemeMode(mode)
            assertEquals(mode, repo.themeMode.first())
            // Кэш обновляется в том же записывающем потоке — гонки быть не должно
            assertEquals(mode, repo.getThemeModeSync())
        }
        repo.cleanup()
        finish()
    }

    @Test
    fun `theme mode survives repository recreation (app restart)`() = runTest {
        // Эмуляция жизненного цикла приложения:
        // 1) первый запуск — записали режим и корректно остановили хранилище;
        // 2) второй запуск — новый DataStore поверх ТОГО ЖЕ файла.
        // Правило «один активный экземпляр на файл»: гасим первый scope
        // ДО создания второго.
        val fileName = "persistence_${System.nanoTime()}.preferences_pb"

        val job1 = SupervisorJob()
        val scope1 = CoroutineScope(Dispatchers.IO + job1)
        val store1 = PreferenceDataStoreFactory.create(scope = scope1) {
            testContext.dataStoreFile(fileName)
        }
        val repo1 = SettingsRepository.createForTesting(testContext, store1)
        repo1.setThemeMode(SettingsRepository.ThemeMode.LIGHT)
        repo1.cleanup()
        // Реестр DataStore освобождает файл только после ЗАВЕРШЕНИЯ скоупа —
        // без join создание второго экземпляра гонит «multiple DataStores
        // active for the same file».
        job1.cancelAndJoin()

        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        activeScopes += scope2
        val store2 = PreferenceDataStoreFactory.create(scope = scope2) {
            testContext.dataStoreFile(fileName)
        }
        val repo2 = SettingsRepository.createForTesting(testContext, store2)
        assertEquals(SettingsRepository.ThemeMode.LIGHT, repo2.themeMode.first())
        assertEquals(SettingsRepository.ThemeMode.LIGHT, repo2.getThemeModeSync())
        repo2.cleanup()
        finish()
    }

    @Test
    fun `sync cache reflects value immediately after set (no race)`() = runTest {
        val repo = newRepo()
        repo.setThemeMode(SettingsRepository.ThemeMode.DARK)
        // setThemeMode пишет кэш ДО ожидания записи в файл — синк-геттер
        // обязан вернуть новое значение сразу.
        assertEquals(SettingsRepository.ThemeMode.DARK, repo.getThemeModeSync())
        repo.setThemeMode(SettingsRepository.ThemeMode.SYSTEM)
        assertEquals(SettingsRepository.ThemeMode.SYSTEM, repo.getThemeModeSync())
        repo.cleanup()
        finish()
    }
}

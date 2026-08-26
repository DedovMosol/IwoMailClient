package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.data.security.AppLockManager
import com.dedovmosol.iwomail.ui.AppLanguage
import com.dedovmosol.iwomail.ui.LocalLanguage
import com.dedovmosol.iwomail.ui.theme.ExchangeMailTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Автоматические биометрические UI-тесты экрана блокировки (цель релиза
 * «пароль + дактилоскопия») — Compose-рантайм под Robolectric, без эмулятора.
 *
 * Покрывает:
 *  - рендер всех элементов (заголовок, описание, поле пароля, кнопка);
 *  - кнопку отпечатка: видимость только при (включено И доступно), клик
 *    вызывает запрос системного промпта (в продакшене → BiometricPrompt);
 *  - неверный пароль → сообщение об ошибке, сессия остаётся заблокированной;
 *  - верный пароль → разблокировка сессии (состояние синглтона снимается);
 *  - пустой пароль → кнопка разблокировки отключена (защита от пустых прогонов
 *    600k-PBKDF2).
 *
 * Производительность тестов: хеш сажается напрямую с 1 000 итераций
 * ([AppLockManager.buildStoredHash] публичный, [AppLockManager.KEY_LOCK_HASH]
 * internal) — verifyStoredHash берёт итерации ИЗ сохранённого значения,
 * поэтому 600k-итерационный прогон в каждом тесте не нужен.
 *
 * Изоляция: сброс процесс-синглтона до/после каждого теста (паттерн
 * [com.dedovmosol.iwomail.data.security.AppLockManagerTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26]) // minSdk 26
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// NATIVE-режим обязателен для Compose-тестов на JVM: Compose рисует через
// реальный AndroidCanvas/RenderNode, которые работают только на нативном
// рантайме Роботрика. Поддержка Windows x86_64 появилась в Robolectric 4.12
// (robolectric#8312: "robolectric-nativeruntime.dll not found") — поэтому
// robolectric поднят до 4.12.2; бинарная библиотека докачивается
// автоматически с Maven Central при первом запуске.
class LockScreenUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AppLockManager.resetForTesting()
        prefs = context.getSharedPreferences("lock_ui_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        AppLockManager.resetForTesting()
    }

    /** Сажает валидный хеш (быстрые итерации) и подключает хранилище к синглтону. */
    private fun plantPassword(password: String) {
        val salt = ByteArray(16) { it.toByte() }
        val stored = AppLockManager.buildStoredHash(password, salt, iterations = 1_000)
        prefs.edit().putString(AppLockManager.KEY_LOCK_HASH, stored).commit()
        AppLockManager.setStorageForTesting(prefs)
    }

    private fun setContent(
        biometricAvailable: Boolean = false,
        biometricEnabled: Boolean = false,
        onBiometricRequest: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLanguage provides AppLanguage.ENGLISH) {
                ExchangeMailTheme(darkTheme = false, dynamicColor = false) {
                    LockScreen(
                        biometricAvailable = biometricAvailable,
                        biometricEnabled = biometricEnabled,
                        onBiometricRequest = onBiometricRequest
                    )
                }
            }
        }
    }

    @Test
    fun `renders title, description, password field and unlock button`() {
        plantPassword("secret")
        setContent()

        composeTestRule.onNodeWithText("App lock").assertIsDisplayed()
        composeTestRule.onNodeWithText("Protect access with a password and fingerprint")
            .assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeTestRule.onNodeWithText("Unlock").assertIsDisplayed()
        // Без биометрии кнопки отпечатка нет.
        composeTestRule.onNodeWithContentDescription("Unlock with fingerprint").assertDoesNotExist()
    }

    @Test
    fun `unlock button disabled while password empty`() {
        plantPassword("secret")
        setContent()

        composeTestRule.onNodeWithText("Unlock").assertIsNotEnabled()
    }

    @Test
    fun `fingerprint button hidden when biometric disabled but available`() {
        plantPassword("secret")
        setContent(biometricAvailable = true, biometricEnabled = false)

        composeTestRule.onNodeWithContentDescription("Unlock with fingerprint").assertDoesNotExist()
    }

    @Test
    fun `fingerprint button hidden when enabled but device unavailable`() {
        plantPassword("secret")
        setContent(biometricAvailable = false, biometricEnabled = true)

        composeTestRule.onNodeWithContentDescription("Unlock with fingerprint").assertDoesNotExist()
    }

    @Test
    fun `fingerprint button visible when enabled AND available`() {
        plantPassword("secret")
        setContent(biometricAvailable = true, biometricEnabled = true)

        composeTestRule.onNodeWithContentDescription("Unlock with fingerprint").assertIsDisplayed()
    }

    @Test
    fun `fingerprint button click requests biometric prompt`() {
        plantPassword("secret")
        var requested = false
        setContent(
            biometricAvailable = true,
            biometricEnabled = true,
            onBiometricRequest = { requested = true }
        )

        composeTestRule.onNodeWithContentDescription("Unlock with fingerprint").performClick()
        composeTestRule.waitForIdle()

        assertThat(requested).isTrue()
        // Биометрия — только запрос промпта; сессия заблокирована до его результата.
        assertThat(AppLockManager.isLocked()).isTrue()
    }

    @Test
    fun `wrong password shows error and keeps session locked`() {
        plantPassword("correct-pass")
        setContent()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("wrong-pass")
        composeTestRule.onNodeWithText("Unlock").performClick()

        // PBKDF2 идёт на Dispatchers.IO — ждём возврата в композицию.
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Wrong password").fetchSemanticsNodes().isNotEmpty()
        }

        assertThat(AppLockManager.isLocked()).isTrue()
    }

    @Test
    fun `typing after error clears error message`() {
        plantPassword("correct-pass")
        setContent()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("wrong-pass")
        composeTestRule.onNodeWithText("Unlock").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Wrong password").fetchSemanticsNodes().isNotEmpty()
        }

        // Повторный ввод сбрасывает сообщение об ошибке (сразу, без ожидания проверки).
        composeTestRule.onNode(hasSetTextAction()).performTextInput("x")
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Wrong password").fetchSemanticsNodes().isEmpty()
        }
        assertThat(AppLockManager.isLocked()).isTrue()
    }

    @Test
    fun `correct password unlocks session`() {
        plantPassword("correct-pass")
        setContent()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("correct-pass")
        composeTestRule.onNodeWithText("Unlock").performClick()

        composeTestRule.waitUntil(5_000) { !AppLockManager.isLocked() }

        assertThat(AppLockManager.isLocked()).isFalse()
        // Ошибка не показана.
        composeTestRule.onAllNodesWithText("Wrong password").assertCountEquals(0)
    }

    @Test
    fun `biometric unlock path unlocks session without password entry`() {
        plantPassword("correct-pass")
        setContent(
            biometricAvailable = true,
            biometricEnabled = true,
            // Имитация успеха системного BiometricPrompt: MainActivity вызывает
            // AppLockManager.unlock() в onAuthenticationSucceeded.
            onBiometricRequest = { AppLockManager.unlock() }
        )

        composeTestRule.onNodeWithContentDescription("Unlock with fingerprint").performClick()

        composeTestRule.waitUntil(5_000) { !AppLockManager.isLocked() }
        assertThat(AppLockManager.isLocked()).isFalse()
    }
}

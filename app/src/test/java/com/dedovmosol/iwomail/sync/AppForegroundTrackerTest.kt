package com.dedovmosol.iwomail.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты чистой логики [AppForegroundTracker.shouldShowNewMailNotification] —
 * решения «показывать ли системное уведомление о новых письмах».
 *
 * Контракт (практика Gmail/Outlook, цель релиза «мгновенное получение сообщений
 * при открытом клиенте»):
 *  - уведомления включены И приложение в фоне        → показывать (пользователь не видит список);
 *  - уведомления включены И приложение открыто       → подавлять (письма уже видны в списке);
 *  - уведомления выключены пользователем             → всегда подавлять.
 *
 * Чистая функция: без Android-фреймворка, исполняется на любой платформе без эмулятора.
 */
class AppForegroundTrackerTest {

    @Test
    fun `shows notification when enabled and app in background`() {
        assertTrue(
            AppForegroundTracker.shouldShowNewMailNotification(
                notificationsEnabled = true,
                appInForeground = false
            )
        )
    }

    @Test
    fun `suppresses notification when app is in foreground even if enabled`() {
        assertFalse(
            AppForegroundTracker.shouldShowNewMailNotification(
                notificationsEnabled = true,
                appInForeground = true
            )
        )
    }

    @Test
    fun `suppresses notification when disabled and app in background`() {
        assertFalse(
            AppForegroundTracker.shouldShowNewMailNotification(
                notificationsEnabled = false,
                appInForeground = false
            )
        )
    }

    @Test
    fun `suppresses notification when disabled and app in foreground`() {
        assertFalse(
            AppForegroundTracker.shouldShowNewMailNotification(
                notificationsEnabled = false,
                appInForeground = true
            )
        )
    }
}

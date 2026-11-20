package com.dedovmosol.iwomail.data.security

import com.dedovmosol.iwomail.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * SET-01 / BG-08: скрывать ли содержимое писем, событий и задач вне приложения (виджет, уведомления).
 * Скрываем, пока блокировка включена: иначе она обходится взглядом на виджет или шторку. Пропавший хеш пароля
 * содержимое не открывает (NAV-04, [AppLockManager.lockRequired]).
 */
object LockPrivacy {

    /** Until DataStore loads, a missing hash cannot distinguish recovery from lock disabled. */
    fun hides(lockEnabled: Boolean?): Boolean = AppLockManager.lockRequired(lockEnabled)

    fun contentHiddenSync(settings: SettingsRepository): Boolean =
        hides(settings.getAppLockEnabledOrNull())

    suspend fun contentHidden(settings: SettingsRepository): Boolean =
        hides(settings.appLockEnabled.first())
}

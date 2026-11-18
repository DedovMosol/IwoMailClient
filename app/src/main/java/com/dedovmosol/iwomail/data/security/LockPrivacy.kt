package com.dedovmosol.iwomail.data.security

import com.dedovmosol.iwomail.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * SET-01 / BG-08: скрывать ли содержимое писем, событий и задач вне приложения (виджет, уведомления).
 * Скрываем, пока блокировка включена и пароль задан: иначе блокировка обходится взглядом на виджет или шторку.
 */
object LockPrivacy {

    fun hides(lockEnabled: Boolean, passwordSet: Boolean): Boolean = lockEnabled && passwordSet

    /** Для несоставного кода без корутин. До первой загрузки настроек флаг считается включённым (fail-closed). */
    fun contentHiddenSync(settings: SettingsRepository): Boolean =
        hides(settings.getAppLockEnabledSync(), AppLockManager.isPasswordSet())

    suspend fun contentHidden(settings: SettingsRepository): Boolean =
        hides(settings.appLockEnabled.first(), AppLockManager.isPasswordSet())
}

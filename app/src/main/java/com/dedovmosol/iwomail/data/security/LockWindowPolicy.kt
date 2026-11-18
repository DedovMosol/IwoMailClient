package com.dedovmosol.iwomail.data.security

import android.os.Build

/**
 * NAV-02: что окно показывает в «Недавних» и на скриншотах при включённой блокировке.
 *
 * Снимок для «Недавних» система делает при уходе окна с экрана, а сессия блокируется позже, после ON_STOP
 * процесса. Поэтому FLAG_SECURE «только пока заблокировано» не прятал письма: в снимок попадал открытый экран.
 *  - API 33+: [android.app.Activity.setRecentsScreenshotEnabled] (false) — в «Недавних» нет снимка, а скриншоты
 *    в открытом приложении остаются. FLAG_SECURE — только на экране блокировки.
 *  - API < 33 отдельного запрета снимка нет: FLAG_SECURE всё время, пока блокировка включена (скриншоты в
 *    приложении тоже запрещены — цена защиты на старых версиях).
 */
data class LockWindowPolicy(val secure: Boolean, val recentsScreenshot: Boolean) {
    companion object {
        /**
         * [lockActive] — блокировка включена и пароль задан; [locked] — сейчас показан экран блокировки.
         */
        fun of(lockActive: Boolean, locked: Boolean, sdk: Int = Build.VERSION.SDK_INT): LockWindowPolicy =
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                LockWindowPolicy(secure = locked, recentsScreenshot = !lockActive)
            } else {
                LockWindowPolicy(secure = lockActive || locked, recentsScreenshot = true)
            }
    }
}

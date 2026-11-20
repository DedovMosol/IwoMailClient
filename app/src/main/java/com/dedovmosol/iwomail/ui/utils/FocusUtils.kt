package com.dedovmosol.iwomail.ui.utils

import androidx.compose.ui.focus.FocusRequester

/**
 * Запросить фокус, не роняя приложение: FocusRequester, чей Modifier.focusRequester сейчас не в композиции (поле
 * скрыто, экран ещё не разметился после поворота), бросает IllegalStateException. false — фокус не запрошен.
 */
fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
    true
} catch (e: IllegalStateException) {
    android.util.Log.w("FocusUtils", "requestFocus skipped: ${e.message}")
    false
}

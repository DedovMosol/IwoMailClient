package com.dedovmosol.iwomail.ui.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Разворачивает цепочку [ContextWrapper] до [Activity].
 *
 * Compose `LocalContext.current` — обычно `ContextThemeWrapper`, а не сама `Activity`, поэтому для
 * доступа к `Activity` (напр. `isChangingConfigurations`) нужно раскрутить `baseContext`.
 *
 * Единый источник (DRY) вместо приватных копий в экранах. Ключевое применение — отличить реальный
 * уход с экрана от пересоздания при повороте в `onDispose`: `context.findActivity()?.isChangingConfigurations`.
 * `tailrec` компилируется в цикл; порядок веток важен — `Activity` (сама тоже `ContextWrapper`) первой.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

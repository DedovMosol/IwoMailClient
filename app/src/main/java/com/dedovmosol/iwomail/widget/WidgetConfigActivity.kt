package com.dedovmosol.iwomail.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Configuration activity для виджета.
 * Проверяет наличие хотя бы одного аккаунта перед добавлением виджета.
 * Если аккаунтов нет — показывает toast и отменяет добавление.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Всегда разрешаем добавление. Glance сам показывает "Добавьте аккаунт"
        // если аккаунтов нет. RESULT_CANCELED на HyperOS/MIUI вызывает
        // "Не удалось добавить виджет" при любом сбое доступа к БД.
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, result)
        finish()
    }
}

package com.dedovmosol.iwomail.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.dedovmosol.iwomail.data.database.MailDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Configuration activity для виджета.
 * Проверяет наличие хотя бы одного аккаунта перед добавлением виджета.
 * Если аккаунтов нет — показывает toast и отменяет добавление.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // По умолчанию — отмена (если activity закроется без OK)
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val hasAccounts = runBlocking(Dispatchers.IO) {
            try {
                MailDatabase.getInstance(this@WidgetConfigActivity)
                    .accountDao().getAllAccountsList().isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

        if (hasAccounts) {
            // Аккаунт есть — разрешаем добавление виджета
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
        } else {
            // Аккаунтов нет — показываем toast, отменяем
            val locale = resources.configuration.locales[0]
            val msg = if (locale.language == "ru")
                "Сначала добавьте аккаунт"
            else
                "Add an account first"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        finish()
    }
}

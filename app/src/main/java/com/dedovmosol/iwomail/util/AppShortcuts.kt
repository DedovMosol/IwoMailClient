package com.dedovmosol.iwomail.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.R

/**
 * Ярлыки приложения (долгий тап по иконке).
 *
 * Динамические, а не из `res/xml/shortcuts.xml`: статические ярлыки нельзя скрыть, и «Синхронизация»
 * или «Написать письмо» предлагались ещё до входа в аккаунт. Теперь ярлыки публикуются, только когда
 * есть хотя бы один аккаунт, и подписаны на языке приложения (а не системы). Закреплённый на рабочем
 * столе ярлык без аккаунта безопасен: обработчики проверяют наличие аккаунта (AppNavigation —
 * только со стартом на главном экране, SyncAlarmReceiver — перед ручной синхронизацией).
 */
object AppShortcuts {

    private class Label(val short: String, val long: String)

    private class Spec(val id: String, val action: String, val icon: Int, val ru: Label, val en: Label)

    private val SPECS = listOf(
        Spec("widget", MainActivity.ACTION_SHORTCUT_ADD_WIDGET, R.drawable.ic_shortcut_widget,
            Label("Виджет", "Добавить виджет"), Label("Widget", "Add widget")),
        Spec("compose", MainActivity.ACTION_SHORTCUT_COMPOSE, R.drawable.ic_shortcut_compose,
            Label("Написать", "Написать письмо"), Label("Compose", "Write new email")),
        Spec("inbox", MainActivity.ACTION_SHORTCUT_INBOX, R.drawable.ic_shortcut_inbox,
            Label("Входящие", "Открыть Входящие"), Label("Inbox", "Open Inbox")),
        Spec("sync", MainActivity.ACTION_SHORTCUT_SYNC, R.drawable.ic_shortcut_sync,
            Label("Синхронизация", "Синхронизировать"), Label("Sync", "Synchronize"))
    )

    /**
     * Приводит набор ярлыков к состоянию «есть аккаунт / нет». ShortcutManager вызывается только при
     * реальном изменении: setDynamicShortcuts в фоне ограничен по частоте (rate limiting).
     */
    fun update(context: Context, hasAccounts: Boolean, language: String) {
        try {
            val published = ShortcutManagerCompat.getDynamicShortcuts(context)
            if (!hasAccounts) {
                if (published.isNotEmpty()) ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }
            val wanted = build(context, isRussian = language == "ru")
            fun labels(list: List<ShortcutInfoCompat>) = list.associate { it.id to it.shortLabel.toString() }
            if (labels(published) != labels(wanted)) ShortcutManagerCompat.setDynamicShortcuts(context, wanted)
        } catch (e: Exception) {
            // Например, IllegalStateException до разблокировки пользователя: ярлыки — не критичная функция.
            android.util.Log.w("AppShortcuts", "Shortcut update failed: ${e.message}")
        }
    }

    /** Ярлык использован (по action интента): лаунчер учитывает это в подсказках. */
    fun reportUsed(context: Context, action: String?) {
        val id = SPECS.firstOrNull { it.action == action }?.id ?: return
        try {
            ShortcutManagerCompat.reportShortcutUsed(context, id)
        } catch (e: Exception) {
            android.util.Log.w("AppShortcuts", "reportShortcutUsed failed: ${e.message}")
        }
    }

    private fun build(context: Context, isRussian: Boolean): List<ShortcutInfoCompat> =
        SPECS.mapIndexed { rank, spec ->
            val label = if (isRussian) spec.ru else spec.en
            ShortcutInfoCompat.Builder(context, spec.id)
                .setShortLabel(label.short)
                .setLongLabel(label.long)
                .setIcon(IconCompat.createWithResource(context, spec.icon))
                .setIntent(Intent(spec.action).setClass(context, MainActivity::class.java))
                .setRank(rank)
                .build()
        }
}

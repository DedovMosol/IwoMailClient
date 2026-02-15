package com.dedovmosol.iwomail.sync

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver для действий из уведомлений о новых письмах.
 * Обрабатывает "Прочитано" — закрывает уведомление и делегирует серверную работу
 * в MarkEmailReadWorker (WorkManager), что надёжно на MIUI/HyperOS/EMUI.
 */
class MailNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_READ) return

        val accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1L)
        val emailIds = intent.getStringArrayExtra(EXTRA_EMAIL_IDS)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (accountId == -1L || notificationId == -1) return

        // Закрываем уведомление мгновенно (UI-операция)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(notificationId)

        // Серверную работу делегируем WorkManager — надёжно на MIUI/HyperOS/EMUI
        if (!emailIds.isNullOrEmpty()) {
            MarkEmailReadWorker.enqueue(context, emailIds)
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.dedovmosol.iwomail.MAIL_MARK_READ"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_EMAIL_IDS = "email_ids"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val REQUEST_CODE_BASE = 60_000

        fun requestCodeForAccount(accountId: Long): Int = REQUEST_CODE_BASE + accountId.toInt()
    }
}

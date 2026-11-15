package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.database.FlagReminderRow
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.ui.NotificationStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Напоминания по флагам писем (tasks:ReminderTime [MS-ASEMAIL]): то же напоминание, что Outlook покажет на компьютере.
 * Будильники — для ближайших [MAX_SCHEDULED] (лимит будильников на приложение у ряда прошивок). В момент срабатывания
 * флаг проверяется по базе: снятый или перенесённый в Outlook флаг уведомления не даёт.
 */
class FlagReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FLAG_REMINDER) return
        val emailId = intent.getStringExtra(EXTRA_EMAIL_ID) ?: return
        val firedFor = intent.getLongExtra(EXTRA_REMINDER_TIME, 0L)
        val pendingResult = goAsync()
        val scope = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.IO)
        scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    val row = MailDatabase.getInstance(context).emailDao().getFlagReminder(emailId)
                    if (row != null && isDue(row.flagReminder, firedFor)) show(context, row)
                }
            } finally {
                RescheduleRemindersWorker.enqueue(context.applicationContext)
                scope.cancel()
                pendingResult.finish()
            }
        }
    }

    private fun show(context: Context, row: FlagReminderRow) {
        if (!NotificationHelper.canPostNotifications(context)) return
        val ru = SendNotifications.isRussian(context)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, row.id)
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, row.accountId)
        }
        val contentIntent = PendingIntent.getActivity(
            context, code(row.id, CODE_CONTENT), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sender = row.fromName.ifBlank { row.from }
        val notification = NotificationCompat.Builder(context, MailApplication.CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_email)
            .setContentTitle(NotificationStrings.getFlagReminderTitle(ru))
            .setContentText(row.subject.ifBlank { sender })
            .setSubText(sender.takeIf { row.subject.isNotBlank() })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(code(row.id, CODE_NOTIF), notification)
    }

    companion object {
        private const val ACTION_FLAG_REMINDER = "com.dedovmosol.iwomail.FLAG_REMINDER"
        private const val EXTRA_EMAIL_ID = "email_id"
        private const val EXTRA_REMINDER_TIME = "reminder_time"
        private const val CODE_ALARM = 0
        private const val CODE_NOTIF = 1
        private const val CODE_CONTENT = 2
        private fun code(id: String, slot: Int) = BackgroundWork.pendingCode(BackgroundWork.KIND_FLAG, id, slot)
        internal const val MAX_SCHEDULED = BackgroundWork.MAX_REMINDER_ALARMS
        /** Будильник мог сработать чуть раньше или позже, а флаг — смениться: напоминание то же, если время совпало. */
        private const val TOLERANCE_MS = 60_000L

        /** Напоминание [current] из базы — то, на которое ставился будильник [firedFor] (не перенесено и не снято). */
        internal fun isDue(current: Long?, firedFor: Long): Boolean =
            current != null && kotlin.math.abs(current - firedFor) <= TOLERANCE_MS

        /** Будильники для ближайших напоминаний по флагам (вызывает [RescheduleRemindersWorker]). */
        suspend fun rescheduleAll(context: Context) {
            val now = System.currentTimeMillis()
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            for (row in MailDatabase.getInstance(context).emailDao().getFlagReminders(now, MAX_SCHEDULED)) {
                val time = row.flagReminder ?: continue
                val intent = Intent(context, FlagReminderReceiver::class.java)
                    .setAction(ACTION_FLAG_REMINDER)
                    .putExtra(EXTRA_EMAIL_ID, row.id)
                    .putExtra(EXTRA_REMINDER_TIME, time)
                val newCode = code(row.id, CODE_ALARM)
                val legacy = BackgroundWork.legacyAlarmCode(row.id)
                if (legacy != newCode) BackgroundWork.cancelBroadcast(context, legacy, intent)
                val pending = PendingIntent.getBroadcast(
                    context, newCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                BackgroundWork.scheduleRtcWakeup(alarms, time, pending)
            }
        }
    }
}

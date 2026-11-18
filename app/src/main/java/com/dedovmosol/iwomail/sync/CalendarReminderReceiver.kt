package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*

/**
 * BroadcastReceiver для напоминаний о событиях календаря.
 *
 * Использует AlarmManager для точного планирования уведомлений.
 * Каждое событие имеет уникальный requestCode на основе хэша id.
 */
class CalendarReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_CALENDAR_REMINDER &&
            action != ACTION_CALENDAR_MARK_READ &&
            action != ACTION_CALENDAR_SNOOZE_5_MIN
        ) return

        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val pendingResult = goAsync()
        val localScope = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.IO)

        localScope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    val notificationManager = context.getSystemService(NotificationManager::class.java)
                    when (action) {
                        ACTION_CALENDAR_REMINDER -> {
                            val database = MailDatabase.getInstance(context)
                            val event = database.calendarEventDao().getEvent(eventId)
                            if (event != null && event.startTime > System.currentTimeMillis()) {
                                showNotification(context, event)
                            }
                        }

                        ACTION_CALENDAR_MARK_READ -> {
                            notificationManager.cancel(notificationIdForEvent(eventId))
                        }

                        ACTION_CALENDAR_SNOOZE_5_MIN -> {
                            notificationManager.cancel(notificationIdForEvent(eventId))
                            val database = MailDatabase.getInstance(context)
                            val event = database.calendarEventDao().getEvent(eventId)
                            if (event != null) {
                                val now = System.currentTimeMillis()
                                val snoozeAt = now + SNOOZE_5_MIN_MS
                                if (event.startTime > snoozeAt) {
                                    scheduleReminderAt(context, event.id, snoozeAt)
                                }
                            }
                        }
                    }
                }
            } finally {
                if (action == ACTION_CALENDAR_REMINDER) {
                    RescheduleRemindersWorker.enqueue(context.applicationContext)
                }
                localScope.cancel()
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, event: CalendarEventEntity) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Intent для открытия приложения при клике - с переключением на нужный аккаунт
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CALENDAR, true)
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, event.accountId)
            putExtra(MainActivity.EXTRA_CALENDAR_EVENT_ID, event.id)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            code(event.id, CODE_CONTENT),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadIntent = Intent(context, CalendarReminderReceiver::class.java).apply {
            action = ACTION_CALENDAR_MARK_READ
            putExtra(EXTRA_EVENT_ID, event.id)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            code(event.id, CODE_MARK_READ),
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, CalendarReminderReceiver::class.java).apply {
            action = ACTION_CALENDAR_SNOOZE_5_MIN
            putExtra(EXTRA_EVENT_ID, event.id)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            code(event.id, CODE_SNOOZE),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Форматируем время события
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())
        val startDate = Date(event.startTime)

        val timeText = if (event.allDayEvent) {
            context.getString(R.string.all_day)
        } else {
            timeFormat.format(startDate)
        }

        val contentText = buildString {
            append(timeText)
            append(" • ")
            append(dateFormat.format(startDate))
            if (event.location.isNotBlank()) {
                append(" • ")
                append(event.location)
            }
        }

        if (!NotificationHelper.canPostNotifications(context)) return

        val builder = NotificationCompat.Builder(context, MailApplication.CHANNEL_CALENDAR)
            .setSmallIcon(R.drawable.ic_calendar_month)
            .setContentTitle(event.subject.ifBlank { context.getString(R.string.calendar_event) })
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_check, context.getString(R.string.notification_mark_read), markReadPendingIntent)
            .addAction(R.drawable.ic_schedule, context.getString(R.string.notification_remind_in_5_min), snoozePendingIntent)
        // BG-08: время события не секрет, название и место — да
        val notification = NotificationPrivacy.apply(
            context, builder, MailApplication.CHANNEL_CALENDAR, R.drawable.ic_calendar_month,
            publicTitle = context.getString(R.string.calendar_event),
            publicText = timeText,
            hidden = NotificationPrivacy.isContentHidden(context)
        ).build()

        // Уникальный ID уведомления на основе события
        val notificationId = notificationIdForEvent(event.id)
        notificationManager.notify(notificationId, notification)
    }

    companion object {
        const val ACTION_CALENDAR_REMINDER = "com.dedovmosol.iwomail.CALENDAR_REMINDER"
        const val ACTION_CALENDAR_MARK_READ = "com.dedovmosol.iwomail.CALENDAR_MARK_READ"
        const val ACTION_CALENDAR_SNOOZE_5_MIN = "com.dedovmosol.iwomail.CALENDAR_SNOOZE_5_MIN"
        const val EXTRA_EVENT_ID = "event_id"
        private const val CODE_ALARM = 0
        private const val CODE_NOTIF = 1
        private const val CODE_CONTENT = 2
        private const val CODE_MARK_READ = 3
        private const val CODE_SNOOZE = 4
        private const val SNOOZE_5_MIN_MS = 5 * 60 * 1000L

        private fun code(id: String, slot: Int) = BackgroundWork.pendingCode(BackgroundWork.KIND_CALENDAR, id, slot)
        private fun notificationIdForEvent(eventId: String): Int = code(eventId, CODE_NOTIF)

        private fun scheduleReminderAt(context: Context, eventId: String, triggerAtMillis: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CalendarReminderReceiver::class.java).apply {
                action = ACTION_CALENDAR_REMINDER
                putExtra(EXTRA_EVENT_ID, eventId)
            }
            val newCode = code(eventId, CODE_ALARM)
            val legacy = BackgroundWork.legacyAlarmCode(eventId)
            if (legacy != newCode) BackgroundWork.cancelBroadcast(context, legacy, intent)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                newCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            BackgroundWork.scheduleRtcWakeup(alarmManager, triggerAtMillis, pendingIntent)
        }

        /**
         * Планирует напоминание для события.
         * Напоминание сработает за [event.reminder] минут до начала события.
         *
         * @param context Context
         * @param event Событие календаря
         */
        fun scheduleReminder(context: Context, event: CalendarEventEntity) {
            if (event.reminder <= 0) return
            val reminderTime = event.startTime - (event.reminder * 60 * 1000L)
            if (reminderTime <= System.currentTimeMillis()) return
            scheduleReminderAt(context, event.id, reminderTime)
        }

        /**
         * Отменяет напоминание для события.
         *
         * @param context Context
         * @param eventId ID события
         */
        fun cancelReminder(context: Context, eventId: String) {
            val intent = Intent(context, CalendarReminderReceiver::class.java).apply {
                action = ACTION_CALENDAR_REMINDER
                putExtra(EXTRA_EVENT_ID, eventId)
            }
            BackgroundWork.cancelBroadcast(context, code(eventId, CODE_ALARM), intent)
            BackgroundWork.cancelBroadcast(context, BackgroundWork.legacyAlarmCode(eventId), intent)
        }

        const val MAX_SCHEDULED_REMINDERS = BackgroundWork.MAX_REMINDER_ALARMS

        /**
         * Планирует напоминания для ближайших событий (до MAX_SCHEDULED_REMINDERS).
         * Ограничение необходимо из-за лимита 500 AlarmManager на UID
         * (MIUI/HyperOS, Samsung, OPPO, RealMe).
         */
        fun rescheduleAllReminders(context: Context, events: List<CalendarEventEntity>) {
            for (event in events) {
                try {
                    scheduleReminder(context, event)
                } catch (e: Exception) {
                    android.util.Log.w("CalendarReminder", "Failed to schedule alarm for ${event.id}: ${e.message}")
                }
            }
        }
    }
}

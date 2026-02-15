package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.database.CalendarEventEntity
import com.dedovmosol.iwomail.data.database.MailDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        localScope.launch {
            try {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                when (action) {
                    ACTION_CALENDAR_REMINDER -> {
                        val database = MailDatabase.getInstance(context)
                        val event = database.calendarEventDao().getEvent(eventId)
                        if (event != null && event.startTime > System.currentTimeMillis()) {
                            // Событие ещё не началось - показываем уведомление
                            showNotification(context, event)
                        }
                        // Если событие уже прошло - не показываем уведомление
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
            } finally {
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
            putExtra("open_calendar", true)
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, event.accountId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode() + REQUEST_CODE_CONTENT,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markReadIntent = Intent(context, CalendarReminderReceiver::class.java).apply {
            action = ACTION_CALENDAR_MARK_READ
            putExtra(EXTRA_EVENT_ID, event.id)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            event.id.hashCode() + REQUEST_CODE_MARK_READ,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, CalendarReminderReceiver::class.java).apply {
            action = ACTION_CALENDAR_SNOOZE_5_MIN
            putExtra(EXTRA_EVENT_ID, event.id)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            event.id.hashCode() + REQUEST_CODE_SNOOZE,
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
        
        val notification = NotificationCompat.Builder(context, MailApplication.CHANNEL_CALENDAR)
            .setSmallIcon(R.drawable.ic_calendar_month)
            .setContentTitle(event.subject.ifBlank { context.getString(R.string.calendar_event) })
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_check, context.getString(R.string.notification_mark_read), markReadPendingIntent)
            .addAction(R.drawable.ic_schedule, context.getString(R.string.notification_remind_in_5_min), snoozePendingIntent)
            .build()
        
        // Уникальный ID уведомления на основе события
        val notificationId = notificationIdForEvent(event.id)
        notificationManager.notify(notificationId, notification)
    }
    
    companion object {
        const val ACTION_CALENDAR_REMINDER = "com.dedovmosol.iwomail.CALENDAR_REMINDER"
        const val ACTION_CALENDAR_MARK_READ = "com.dedovmosol.iwomail.CALENDAR_MARK_READ"
        const val ACTION_CALENDAR_SNOOZE_5_MIN = "com.dedovmosol.iwomail.CALENDAR_SNOOZE_5_MIN"
        const val EXTRA_EVENT_ID = "event_id"
        // Уникальный диапазон ID: 5000+ (SyncWorker использует 3000+, SyncAlarmReceiver 4000+)
        private const val NOTIFICATION_ID_BASE = 5000
        private const val REQUEST_CODE_CONTENT = 10_000
        private const val REQUEST_CODE_MARK_READ = 20_000
        private const val REQUEST_CODE_SNOOZE = 30_000
        private const val SNOOZE_5_MIN_MS = 5 * 60 * 1000L

        private fun notificationIdForEvent(eventId: String): Int = NOTIFICATION_ID_BASE + eventId.hashCode()

        private fun scheduleReminderAt(context: Context, eventId: String, triggerAtMillis: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CalendarReminderReceiver::class.java).apply {
                action = ACTION_CALENDAR_REMINDER
                putExtra(EXTRA_EVENT_ID, eventId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                eventId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
        
        /**
         * Планирует напоминание для события.
         * Напоминание сработает за [event.reminder] минут до начала события.
         * 
         * @param context Context
         * @param event Событие календаря
         */
        fun scheduleReminder(context: Context, event: CalendarEventEntity) {
            // Не планируем если reminder = 0 или событие уже прошло
            if (event.reminder <= 0) return
            
            val reminderTime = event.startTime - (event.reminder * 60 * 1000L)
            
            // Не планируем если время напоминания уже прошло
            if (reminderTime <= System.currentTimeMillis()) return
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = Intent(context, CalendarReminderReceiver::class.java).apply {
                action = ACTION_CALENDAR_REMINDER
                putExtra(EXTRA_EVENT_ID, event.id)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                event.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        reminderTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        reminderTime,
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                // Нет разрешения на точные alarm'ы - используем неточный
                alarmManager.set(AlarmManager.RTC_WAKEUP, reminderTime, pendingIntent)
            }
        }
        
        /**
         * Отменяет напоминание для события.
         * 
         * @param context Context
         * @param eventId ID события
         */
        fun cancelReminder(context: Context, eventId: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = Intent(context, CalendarReminderReceiver::class.java).apply {
                action = ACTION_CALENDAR_REMINDER
                putExtra(EXTRA_EVENT_ID, eventId)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                eventId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            
            pendingIntent?.let { alarmManager.cancel(it) }
        }
        
        /**
         * Перепланирует все напоминания для аккаунта.
         * Вызывается после синхронизации календаря.
         * 
         * @param context Context
         * @param events Список событий
         */
        fun rescheduleAllReminders(context: Context, events: List<CalendarEventEntity>) {
            val now = System.currentTimeMillis()
            
            events.forEach { event ->
                if (event.reminder > 0 && event.startTime > now) {
                    scheduleReminder(context, event)
                }
            }
        }
    }
}

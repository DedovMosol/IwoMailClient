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
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.data.database.TaskImportance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*

/**
 * BroadcastReceiver для напоминаний о задачах.
 */
class TaskReminderReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_TASK_REMINDER && action != ACTION_TASK_MARK_READ) return
        
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        
        when (action) {
            ACTION_TASK_MARK_READ -> {
                // Закрываем уведомление мгновенно (UI-операция, не требует goAsync)
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.cancel(code(taskId, CODE_NOTIF))
                
                // Серверную работу делегируем WorkManager — надёжно на MIUI/HyperOS/EMUI
                MarkTaskCompleteWorker.enqueue(context, taskId)
            }
            ACTION_TASK_REMINDER -> {
                val pendingResult = goAsync()
                val localScope = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.IO)
                localScope.launch {
                    try {
                        withTimeoutOrNull(8_000) {
                            val database = MailDatabase.getInstance(context)
                            val task = database.taskDao().getTask(taskId)
                            if (task != null && !task.complete) {
                                showNotification(context, task)
                            }
                        }
                    } finally {
                        RescheduleRemindersWorker.enqueue(context.applicationContext)
                        localScope.cancel()
                        pendingResult.finish()
                    }
                }
            }
        }
    }
    
    private fun showNotification(context: Context, task: TaskEntity) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        
        // Intent для открытия приложения при клике
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TASKS, true)
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, task.accountId)
            putExtra(MainActivity.EXTRA_TASK_ID, task.id)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            code(task.id, CODE_CONTENT),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val markReadIntent = Intent(context, TaskReminderReceiver::class.java).apply {
            this.action = ACTION_TASK_MARK_READ
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            code(task.id, CODE_MARK_READ),
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Форматируем срок выполнения
        val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())
        
        val contentText = buildString {
            if (task.dueDate > 0) {
                append(context.getString(R.string.due_date_label))
                append(": ")
                append(dateFormat.format(Date(task.dueDate)))
            }
            if (task.importance == TaskImportance.HIGH.value) {
                if (isNotEmpty()) append(" • ")
                append("⚠️ ")
                append(context.getString(R.string.high_priority))
            }
        }
        
        if (!NotificationHelper.canPostNotifications(context)) return

        val builder = NotificationCompat.Builder(context, MailApplication.CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_task)
            .setContentTitle(task.subject.ifBlank { context.getString(R.string.task) })
            .setContentText(contentText.ifBlank { context.getString(R.string.task_reminder) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (task.body.isNotBlank()) com.dedovmosol.iwomail.util.stripHtmlIfNeeded(task.body) else contentText
            ))
            .setPriority(
                if (task.importance == TaskImportance.HIGH.value) 
                    NotificationCompat.PRIORITY_HIGH 
                else 
                    NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_check, context.getString(R.string.notification_mark_read), markReadPendingIntent)
        // BG-08: на экране блокировки — без названия и текста задачи
        val notification = NotificationPrivacy.apply(
            context, builder, MailApplication.CHANNEL_TASKS, R.drawable.ic_task,
            publicTitle = context.getString(R.string.task_reminder),
            hidden = NotificationPrivacy.isContentHidden(context)
        ).build()
        
        val notificationId = code(task.id, CODE_NOTIF)
        notificationManager.notify(notificationId, notification)
    }
    
    companion object {
        const val ACTION_TASK_REMINDER = "com.dedovmosol.iwomail.TASK_REMINDER"
        const val ACTION_TASK_MARK_READ = "com.dedovmosol.iwomail.TASK_MARK_READ"
        const val EXTRA_TASK_ID = "task_id"
        private const val CODE_ALARM = 0
        private const val CODE_NOTIF = 1
        private const val CODE_CONTENT = 2
        private const val CODE_MARK_READ = 3
        private fun code(id: String, slot: Int) = BackgroundWork.pendingCode(BackgroundWork.KIND_TASK, id, slot)
        
        /**
         * Планирует напоминание для задачи.
         */
        fun scheduleReminder(context: Context, task: TaskEntity) {
            if (!task.reminderSet || task.reminderTime <= 0 || task.complete) return
            
            // Не планируем если время напоминания уже прошло
            if (task.reminderTime <= System.currentTimeMillis()) return
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = Intent(context, TaskReminderReceiver::class.java).apply {
                action = ACTION_TASK_REMINDER
                putExtra(EXTRA_TASK_ID, task.id)
            }
            
            val newCode = code(task.id, CODE_ALARM)
            val legacy = BackgroundWork.legacyAlarmCode(task.id)
            if (legacy != newCode) BackgroundWork.cancelBroadcast(context, legacy, intent)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                newCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            BackgroundWork.scheduleRtcWakeup(alarmManager, task.reminderTime, pendingIntent)
        }
        
        /**
         * Отменяет напоминание для задачи.
         */
        fun cancelReminder(context: Context, taskId: String) {
            val intent = Intent(context, TaskReminderReceiver::class.java).apply {
                action = ACTION_TASK_REMINDER
                putExtra(EXTRA_TASK_ID, taskId)
            }
            BackgroundWork.cancelBroadcast(context, code(taskId, CODE_ALARM), intent)
            BackgroundWork.cancelBroadcast(context, BackgroundWork.legacyAlarmCode(taskId), intent)
        }
        
        const val MAX_SCHEDULED_REMINDERS = BackgroundWork.MAX_REMINDER_ALARMS

        /**
         * Планирует напоминания для ближайших задач (до MAX_SCHEDULED_REMINDERS).
         * Ограничение из-за лимита 500 AlarmManager на UID (MIUI/HyperOS, Samsung, OPPO).
         */
        fun rescheduleAllReminders(context: Context, tasks: List<TaskEntity>) {
            for (task in tasks) {
                try {
                    scheduleReminder(context, task)
                } catch (e: Exception) {
                    android.util.Log.w("TaskReminder", "Failed to schedule alarm for ${task.id}: ${e.message}")
                }
            }
        }
    }
}

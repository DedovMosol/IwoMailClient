package com.iwo.mailclient.sync

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.iwo.mailclient.MainActivity
import com.iwo.mailclient.MailApplication
import com.iwo.mailclient.R
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.database.TaskEntity
import com.iwo.mailclient.data.database.TaskImportance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * BroadcastReceiver для напоминаний о задачах.
 */
class TaskReminderReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TASK_REMINDER) return
        
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val database = MailDatabase.getInstance(context)
                val task = database.taskDao().getTask(taskId)
                
                if (task != null && !task.complete) {
                    // Задача не выполнена - показываем уведомление
                    showNotification(context, task)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
    
    private fun showNotification(context: Context, task: TaskEntity) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        
        // Intent для открытия приложения при клике
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tasks", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            contentIntent,
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
        
        val notification = NotificationCompat.Builder(context, MailApplication.CHANNEL_CALENDAR)
            .setSmallIcon(R.drawable.ic_task)
            .setContentTitle(task.subject.ifBlank { context.getString(R.string.task) })
            .setContentText(contentText.ifBlank { context.getString(R.string.task_reminder) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (task.body.isNotBlank()) task.body else contentText
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        
        val notificationId = NOTIFICATION_ID_BASE + task.id.hashCode()
        notificationManager.notify(notificationId, notification)
    }
    
    companion object {
        const val ACTION_TASK_REMINDER = "com.iwo.mailclient.TASK_REMINDER"
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFICATION_ID_BASE = 4000
        
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
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                task.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.reminderTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        task.reminderTime,
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, task.reminderTime, pendingIntent)
            }
        }
        
        /**
         * Отменяет напоминание для задачи.
         */
        fun cancelReminder(context: Context, taskId: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = Intent(context, TaskReminderReceiver::class.java).apply {
                action = ACTION_TASK_REMINDER
                putExtra(EXTRA_TASK_ID, taskId)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                taskId.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            
            pendingIntent?.let { alarmManager.cancel(it) }
        }
        
        /**
         * Перепланирует все напоминания для задач.
         */
        fun rescheduleAllReminders(context: Context, tasks: List<TaskEntity>) {
            val now = System.currentTimeMillis()
            
            tasks.forEach { task ->
                if (task.reminderSet && task.reminderTime > now && !task.complete) {
                    scheduleReminder(context, task)
                }
            }
        }
    }
}

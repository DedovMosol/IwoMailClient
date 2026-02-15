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
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.TaskEntity
import com.dedovmosol.iwomail.data.database.TaskImportance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
                notificationManager.cancel(NOTIFICATION_ID_BASE + taskId.hashCode())
                
                // Серверную работу делегируем WorkManager — надёжно на MIUI/HyperOS/EMUI
                MarkTaskCompleteWorker.enqueue(context, taskId)
            }
            ACTION_TASK_REMINDER -> {
                // Чтение из БД + показ уведомления — лёгкая операция, goAsync достаточно
                val pendingResult = goAsync()
                val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                localScope.launch {
                    try {
                        val database = MailDatabase.getInstance(context)
                        val task = database.taskDao().getTask(taskId)
                        if (task != null && !task.complete) {
                            showNotification(context, task)
                        }
                    } finally {
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
            putExtra("open_tasks", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            task.id.hashCode() + REQUEST_CODE_CONTENT,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val markReadIntent = Intent(context, TaskReminderReceiver::class.java).apply {
            this.action = ACTION_TASK_MARK_READ
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode() + REQUEST_CODE_MARK_READ,
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
        
        val notification = NotificationCompat.Builder(context, MailApplication.CHANNEL_TASKS)
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
            .addAction(R.drawable.ic_check, context.getString(R.string.notification_mark_read), markReadPendingIntent)
            .build()
        
        val notificationId = NOTIFICATION_ID_BASE + task.id.hashCode()
        notificationManager.notify(notificationId, notification)
    }
    
    companion object {
        const val ACTION_TASK_REMINDER = "com.dedovmosol.iwomail.TASK_REMINDER"
        const val ACTION_TASK_MARK_READ = "com.dedovmosol.iwomail.TASK_MARK_READ"
        const val EXTRA_TASK_ID = "task_id"
        private const val NOTIFICATION_ID_BASE = 4000
        private const val REQUEST_CODE_CONTENT = 40_000
        private const val REQUEST_CODE_MARK_READ = 50_000
        
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

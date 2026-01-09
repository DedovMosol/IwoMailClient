package com.iwo.mailclient.sync

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.repository.CalendarRepository
import com.iwo.mailclient.data.repository.ContactRepository
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.data.repository.NoteRepository
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.data.repository.TaskRepository
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.network.NetworkMonitor
import com.iwo.mailclient.ui.NotificationStrings
import com.iwo.mailclient.widget.MailWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver для синхронизации по AlarmManager
 * Работает даже когда PushService убит системой (Xiaomi, Huawei и др.)
 * 
 * Выполняет синхронизацию напрямую, не полагаясь только на WorkManager
 */
class SyncAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        const val ACTION_SYNC_NOW = "com.iwo.mailclient.SYNC_NOW"
        private const val SYNC_STARTED_NOTIFICATION_ID = 9998
    }
    
    private fun showSyncStartedNotification(context: Context) {
        val settingsRepo = SettingsRepository.getInstance(context)
        val isRussian = settingsRepo.getLanguageSync() == "ru"
        
        val notification = androidx.core.app.NotificationCompat.Builder(
            context,
            com.iwo.mailclient.MailApplication.CHANNEL_SYNC_STATUS
        )
            .setSmallIcon(com.iwo.mailclient.R.drawable.ic_sync)
            .setContentTitle(if (isRussian) "Синхронизация запущена" else "Sync started")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(SYNC_STARTED_NOTIFICATION_ID, notification)
    }
    
    private data class NewEmailInfo(
        val id: String,
        val senderName: String?,
        val senderEmail: String?,
        val subject: String?
    )
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == PushService.ACTION_SYNC_ALARM || action == ACTION_SYNC_NOW) {
            val isManualSync = action == ACTION_SYNC_NOW
            
            // Показываем уведомление только при ручном запуске
            if (isManualSync) {
                // Проверяем сеть ПЕРЕД запуском синхронизации
                if (!NetworkMonitor.isNetworkAvailable(context)) {
                    val settingsRepo = SettingsRepository.getInstance(context)
                    val isRussian = settingsRepo.getLanguageSync() == "ru"
                    val message = if (isRussian) "Нет сети" else "No network"
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                
                showSyncStartedNotification(context)
                
                // Для ручной синхронизации используем WorkManager - он надёжнее
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(
                        androidx.work.Data.Builder()
                            .putBoolean("manual_sync", true)
                            .build()
                    )
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
                return
            }
            
            val pendingResult = goAsync()
            val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            
            localScope.launch {
                try {
                    val database = MailDatabase.getInstance(context)
                    val accounts = database.accountDao().getAllAccountsList()
                    
                    if (accounts.isEmpty()) {
                        return@launch
                    }
                    
                    val settingsRepo = SettingsRepository.getInstance(context)
                    val mailRepo = MailRepository(context)
                    
                    // Выполняем синхронизацию напрямую
                    var lastNotificationCheck = settingsRepo.getLastNotificationCheckTimeSync()
                    // При первом запуске не показываем уведомления для старых писем
                    if (lastNotificationCheck == 0L) {
                        lastNotificationCheck = System.currentTimeMillis() - 60_000
                    }
                    
                    // Собираем новые письма по аккаунтам
                    val newEmailsByAccount = mutableMapOf<Long, MutableList<NewEmailInfo>>()
                    
                    for (account in accounts) {
                        // Синхронизируем папки
                        mailRepo.syncFolders(account.id)
                        
                        // Синхронизируем основные папки с письмами
                        val mainFolderTypes = listOf(2, 5) // Inbox, Sent
                        val folders = database.folderDao().getFoldersByAccountList(account.id)
                        val foldersToSync = folders.filter { it.type in mainFolderTypes }
                        
                        for (folder in foldersToSync) {
                            mailRepo.syncEmails(account.id, folder.id)
                        }
                        
                        // Проверяем новые письма
                        val newEmails = database.emailDao().getNewUnreadEmails(account.id, lastNotificationCheck)
                        if (newEmails.isNotEmpty()) {
                            val accountEmails = newEmailsByAccount.getOrPut(account.id) { mutableListOf() }
                            for (email in newEmails) {
                                accountEmails.add(NewEmailInfo(email.id, email.fromName, email.from, email.subject))
                            }
                        }
                    }
                    
                    // Показываем уведомления для каждого аккаунта отдельно
                    if (newEmailsByAccount.isNotEmpty()) {
                        val notificationsEnabled = settingsRepo.notificationsEnabled.first()
                        if (notificationsEnabled) {
                            for ((accountId, emails) in newEmailsByAccount) {
                                val account = accounts.find { it.id == accountId } ?: continue
                                showNotification(context, emails, account.email, accountId, settingsRepo)
                            }
                        }
                    }
                    
                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    settingsRepo.setLastNotificationCheckTime(System.currentTimeMillis())
                    
                    // Обновляем виджет после синхронизации
                    updateWidget(context)
                    
                    // Синхронизация контактов, заметок, календаря и задач (для Exchange)
                    // Внутри каждого метода проверяется интервал (в днях) из настроек аккаунта
                    syncGalContacts(context, accounts, settingsRepo)
                    syncNotes(context, accounts, settingsRepo)
                    syncCalendar(context, accounts, settingsRepo)
                    syncTasks(context, accounts, settingsRepo)
                    
                    // Пытаемся перезапустить PushService только если есть аккаунты с режимом PUSH
                    val hasExchangePushAccounts = accounts.any { 
                        it.accountType == AccountType.EXCHANGE.name &&
                        it.syncMode == com.iwo.mailclient.data.database.SyncMode.PUSH.name
                    }
                    if (hasExchangePushAccounts) {
                        try {
                            PushService.start(context)
                        } catch (_: Exception) { }
                    }
                    
                    // Планируем следующий alarm
                    val minInterval = SyncWorker.getMinSyncInterval(context)
                    val intervalMinutes = if (minInterval > 0) minInterval else 5
                    PushService.scheduleSyncAlarm(context, intervalMinutes)
                    
                } catch (_: Exception) {
                    PushService.scheduleSyncAlarm(context, 5)
                } finally {
                    localScope.cancel()
                    pendingResult.finish()
                }
            }
        }
    }
    
    private suspend fun showNotification(
        context: Context, 
        newEmails: List<NewEmailInfo>,
        accountEmail: String,
        accountId: Long,
        settingsRepo: SettingsRepository
    ) {
        val count = newEmails.size
        
        val languageCode = settingsRepo.language.first()
        val isRussian = languageCode == "ru"
        
        val latestEmail = newEmails.maxByOrNull { it.id }
        val senderName = latestEmail?.senderName?.takeIf { it.isNotBlank() }
            ?: latestEmail?.senderEmail?.substringBefore("@")
        val subject = latestEmail?.subject?.takeIf { it.isNotBlank() }
        
        val intent = Intent(context, com.iwo.mailclient.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.iwo.mailclient.MainActivity.EXTRA_SWITCH_ACCOUNT_ID, accountId)
            if (count == 1 && latestEmail != null) {
                putExtra(com.iwo.mailclient.MainActivity.EXTRA_OPEN_EMAIL_ID, latestEmail.id)
            } else {
                putExtra(com.iwo.mailclient.MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
            }
        }
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, accountId.toInt(), intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(
            context, 
            com.iwo.mailclient.MailApplication.CHANNEL_NEW_MAIL
        )
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(NotificationStrings.getNewMailTitle(count, senderName, isRussian))
            .setContentText(NotificationStrings.getNewMailText(count, subject, isRussian))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSubText(accountEmail)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_EMAIL)
            .build()
        
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        // Уникальный ID для каждого аккаунта
        val notificationId = 4000 + accountId.toInt()
        notificationManager.notify(notificationId, notification)
        
        // Воспроизводим звук
        com.iwo.mailclient.util.SoundPlayer.playReceiveSound(context)
    }
    
    /**
     * Синхронизация контактов из GAL для Exchange аккаунтов
     */
    private suspend fun syncGalContacts(
        context: Context,
        accounts: List<com.iwo.mailclient.data.database.AccountEntity>,
        settingsRepo: SettingsRepository
    ) {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val contactRepo = ContactRepository(context)
        
        for (account in accounts) {
            if (account.accountType != AccountType.EXCHANGE.name) continue
            if (account.contactsSyncIntervalDays <= 0) continue
            
            val lastSync = settingsRepo.getLastContactsSyncTimeSync(account.id)
            val intervalMs = account.contactsSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            try {
                val result = contactRepo.syncGalContactsToDb(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastContactsSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (_: Exception) { }
        }
    }
    
    /**
     * Синхронизация заметок для Exchange аккаунтов
     */
    private suspend fun syncNotes(
        context: Context,
        accounts: List<com.iwo.mailclient.data.database.AccountEntity>,
        settingsRepo: SettingsRepository
    ) {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val noteRepo = NoteRepository(context)
        
        for (account in accounts) {
            if (account.accountType != AccountType.EXCHANGE.name) continue
            if (account.notesSyncIntervalDays <= 0) continue
            
            val lastSync = settingsRepo.getLastNotesSyncTimeSync(account.id)
            val intervalMs = account.notesSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            try {
                val result = noteRepo.syncNotes(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastNotesSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (_: Exception) { }
        }
    }
    
    /**
     * Синхронизация календаря для Exchange аккаунтов
     */
    private suspend fun syncCalendar(
        context: Context,
        accounts: List<com.iwo.mailclient.data.database.AccountEntity>,
        settingsRepo: SettingsRepository
    ) {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val calendarRepo = CalendarRepository(context)
        
        for (account in accounts) {
            if (account.accountType != AccountType.EXCHANGE.name) continue
            if (account.calendarSyncIntervalDays <= 0) continue
            
            val lastSync = settingsRepo.getLastCalendarSyncTimeSync(account.id)
            val intervalMs = account.calendarSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            try {
                val result = calendarRepo.syncCalendar(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastCalendarSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (_: Exception) { }
        }
    }
    
    /**
     * Синхронизация задач для Exchange аккаунтов
     */
    private suspend fun syncTasks(
        context: Context,
        accounts: List<com.iwo.mailclient.data.database.AccountEntity>,
        settingsRepo: SettingsRepository
    ) {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val taskRepo = TaskRepository(context)
        
        for (account in accounts) {
            if (account.accountType != AccountType.EXCHANGE.name) continue
            if (account.tasksSyncIntervalDays <= 0) continue
            
            val lastSync = settingsRepo.getLastTasksSyncTimeSync(account.id)
            val intervalMs = account.tasksSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            try {
                val result = taskRepo.syncTasks(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastTasksSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (_: Exception) { }
        }
    }
    
    /**
     * Обновление виджета на рабочем столе
     */
    private fun updateWidget(context: Context) {
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, MailWidgetReceiver::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)
            if (widgetIds.isNotEmpty()) {
                val intent = Intent(context, MailWidgetReceiver::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                }
                context.sendBroadcast(intent)
            }
        } catch (_: Exception) { }
    }
}

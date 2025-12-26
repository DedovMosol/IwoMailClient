package com.exchange.mailclient.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.exchange.mailclient.data.database.AccountType
import com.exchange.mailclient.data.database.MailDatabase
import com.exchange.mailclient.data.repository.MailRepository
import com.exchange.mailclient.data.repository.SettingsRepository
import com.exchange.mailclient.eas.EasResult
import com.exchange.mailclient.ui.NotificationStrings
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
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PushService.ACTION_SYNC_ALARM) {
            val pendingResult = goAsync()
            val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            
            localScope.launch {
                try {
                    val database = MailDatabase.getInstance(context)
                    val accounts = database.accountDao().getAllAccountsList()
                    
                    if (accounts.isEmpty()) return@launch
                    
                    val settingsRepo = SettingsRepository.getInstance(context)
                    val mailRepo = MailRepository(context)
                    
                    // Выполняем синхронизацию напрямую
                    val lastNotificationCheck = settingsRepo.getLastNotificationCheckTimeSync()
                    var newEmailCount = 0
                    var latestSenderName: String? = null
                    var latestSubject: String? = null
                    
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
                            newEmailCount += newEmails.size
                            val latest = newEmails.maxByOrNull { it.dateReceived }
                            if (latest != null) {
                                latestSenderName = latest.fromName.takeIf { it.isNotBlank() } 
                                    ?: latest.from.substringBefore("@")
                                latestSubject = latest.subject
                            }
                        }
                    }
                    
                    // Показываем уведомление если есть новые письма
                    if (newEmailCount > 0) {
                        val notificationsEnabled = settingsRepo.notificationsEnabled.first()
                        if (notificationsEnabled) {
                            showNotification(context, newEmailCount, latestSenderName, latestSubject, settingsRepo)
                        }
                    }
                    
                    settingsRepo.setLastSyncTime(System.currentTimeMillis())
                    settingsRepo.setLastNotificationCheckTime(System.currentTimeMillis())
                    
                    // Пытаемся перезапустить PushService
                    val hasExchangeAccounts = accounts.any { 
                        it.accountType == AccountType.EXCHANGE.name 
                    }
                    if (hasExchangeAccounts) {
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
        count: Int, 
        senderName: String?, 
        subject: String?,
        settingsRepo: SettingsRepository
    ) {
        val languageCode = settingsRepo.language.first()
        val isRussian = languageCode == "ru"
        
        val intent = Intent(context, com.exchange.mailclient.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (count > 1) {
                putExtra(com.exchange.mailclient.MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
            }
        }
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, System.currentTimeMillis().toInt(), intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(
            context, 
            com.exchange.mailclient.MailApplication.CHANNEL_NEW_MAIL
        )
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(NotificationStrings.getNewMailTitle(count, senderName, isRussian))
            .setContentText(NotificationStrings.getNewMailText(count, subject, isRussian))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}

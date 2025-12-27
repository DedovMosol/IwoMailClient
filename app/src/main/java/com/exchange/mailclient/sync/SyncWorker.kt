package com.exchange.mailclient.sync

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.exchange.mailclient.MainActivity
import com.exchange.mailclient.MailApplication
import com.exchange.mailclient.data.database.AccountType
import com.exchange.mailclient.data.database.MailDatabase
import com.exchange.mailclient.data.database.SyncMode
import com.exchange.mailclient.data.repository.MailRepository
import com.exchange.mailclient.data.repository.SettingsRepository
import com.exchange.mailclient.eas.EasResult
import com.exchange.mailclient.ui.NotificationStrings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Worker для фоновой синхронизации почты
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    private val mailRepo = MailRepository(applicationContext)
    private val settingsRepo = SettingsRepository.getInstance(applicationContext)
    private val database = MailDatabase.getInstance(applicationContext)
    
    private data class NewEmailInfo(
        val id: String,
        val senderName: String?,
        val senderEmail: String?,
        val subject: String?
    )
    
    override suspend fun doWork(): Result {
        // Debounce: пропускаем если синхронизация была менее 30 сек назад
        val lastSync = settingsRepo.getLastSyncTimeSync()
        if (System.currentTimeMillis() - lastSync < 30_000) {
            return Result.success()
        }
        
        val accounts = database.accountDao().getAllAccountsList()
        if (accounts.isEmpty()) return Result.success()
        
        val lastNotificationCheck = settingsRepo.getLastNotificationCheckTimeSync()
        // Собираем новые письма по аккаунтам
        val newEmailsByAccount = mutableMapOf<Long, MutableList<NewEmailInfo>>()
        var hasErrors = false
        
        for (account in accounts) {
            when (mailRepo.syncFolders(account.id)) {
                is EasResult.Error -> {
                    hasErrors = true
                    continue
                }
                is EasResult.Success -> { }
            }
            
            val mainFolderTypes = listOf(2, 3, 4, 5, 6, 11)
            val allFolders = database.folderDao().getFoldersByAccountList(account.id)
            val foldersToSync = allFolders.filter { it.type in mainFolderTypes }
            
            if (foldersToSync.isEmpty()) continue
            
            for (folder in foldersToSync) {
                mailRepo.syncEmails(account.id, folder.id)
            }
            
            val newEmailEntities = database.emailDao().getNewUnreadEmails(account.id, lastNotificationCheck)
            if (newEmailEntities.isNotEmpty()) {
                val accountEmails = newEmailsByAccount.getOrPut(account.id) { mutableListOf() }
                for (email in newEmailEntities) {
                    accountEmails.add(NewEmailInfo(email.id, email.fromName, email.from, email.subject))
                }
            }
        }
        
        settingsRepo.setLastSyncTime(System.currentTimeMillis())
        
        // Показываем уведомления для каждого аккаунта отдельно
        if (newEmailsByAccount.isNotEmpty()) {
            val prefs = applicationContext.getSharedPreferences("push_service", Context.MODE_PRIVATE)
            val lastPushNotification = prefs.getLong("last_notification_time", 0)
            val timeSinceLastPush = System.currentTimeMillis() - lastPushNotification
            
            if (timeSinceLastPush >= 30_000) {
                val notificationsEnabled = settingsRepo.notificationsEnabled.first()
                if (notificationsEnabled) {
                    for ((accountId, emails) in newEmailsByAccount) {
                        val account = accounts.find { it.id == accountId } ?: continue
                        showNotification(emails, account.email, accountId)
                    }
                }
            }
        }
        
        settingsRepo.setLastNotificationCheckTime(System.currentTimeMillis())
        
        // Автоочистка корзины
        performAutoTrashCleanup()
        
        // Перепланируем с учётом ночного режима (интервал может измениться)
        scheduleWithNightMode(applicationContext)
        
        return if (hasErrors) Result.retry() else Result.success()
    }
    
    private suspend fun showNotification(newEmails: List<NewEmailInfo>, accountEmail: String, accountId: Long) {
        val count = newEmails.size
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }
        
        val languageCode = settingsRepo.language.first()
        val isRussian = languageCode == "ru"
        
        val latestEmail = newEmails.maxByOrNull { it.id }
        val senderName = latestEmail?.senderName?.takeIf { it.isNotBlank() } 
            ?: latestEmail?.senderEmail?.substringBefore("@")
        val subject = latestEmail?.subject?.takeIf { it.isNotBlank() }
        
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (count == 1 && latestEmail != null) {
                putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, latestEmail.id)
            } else {
                putExtra(MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
            }
        }
        
        // Уникальный requestCode для каждого аккаунта
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, accountId.toInt(), intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        
        val builder = NotificationCompat.Builder(applicationContext, MailApplication.CHANNEL_NEW_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(NotificationStrings.getNewMailTitle(count, senderName, isRussian))
            .setContentText(NotificationStrings.getNewMailText(count, subject, isRussian))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSubText(accountEmail)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
        
        if (count > 1) {
            val senders = newEmails.mapNotNull { email ->
                email.senderName?.takeIf { it.isNotBlank() } 
                    ?: email.senderEmail?.substringBefore("@")
            }
            if (senders.isNotEmpty()) {
                builder.setStyle(NotificationCompat.BigTextStyle()
                    .bigText(NotificationStrings.getNewMailBigText(senders, isRussian)))
            }
        } else if (count == 1 && !subject.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(subject))
        }
        
        // Уникальный ID для каждого аккаунта — уведомления не перезаписывают друг друга
        val notificationId = 3000 + accountId.toInt()
        notificationManager.notify(notificationId, builder.build())
        
        // Воспроизводим звук получения письма
        com.exchange.mailclient.util.SoundPlayer.playReceiveSound(applicationContext)
    }
    
    /**
     * Автоматическая очистка корзины — удаляет письма старше N дней
     */
    private suspend fun performAutoTrashCleanup() {
        val autoEmptyDays = settingsRepo.getAutoEmptyTrashDaysSync()
        if (autoEmptyDays <= 0) return // Выключено
        
        // Проверяем раз в день
        val lastCleanup = settingsRepo.getLastTrashCleanupTimeSync()
        val oneDayMs = 24 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - lastCleanup < oneDayMs) return
        
        val cutoffTime = System.currentTimeMillis() - (autoEmptyDays * oneDayMs)
        
        // Получаем все папки корзины (type = 4)
        val accounts = database.accountDao().getAllAccountsList()
        for (account in accounts) {
            val trashFolders = database.folderDao().getFoldersByAccountList(account.id)
                .filter { it.type == 4 } // Deleted Items
            
            for (trashFolder in trashFolders) {
                // Получаем старые письма из корзины
                val oldEmails = database.emailDao().getEmailsOlderThan(trashFolder.id, cutoffTime)
                if (oldEmails.isNotEmpty()) {
                    val emailIds = oldEmails.map { it.id }
                    mailRepo.deleteEmailsPermanently(emailIds)
                }
            }
        }
        
        settingsRepo.setLastTrashCleanupTime(System.currentTimeMillis())
    }
    
    companion object {
        private const val WORK_NAME = "mail_sync"
        private const val NIGHT_MODE_INTERVAL = 60L // 60 минут ночью
        
        fun schedule(context: Context, intervalMinutes: Long = 15, wifiOnly: Boolean = false) {
            if (intervalMinutes <= 0) {
                cancel(context)
                return
            }
            
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
        
        /**
         * Планирует синхронизацию с учётом ночного режима и настроек аккаунтов
         */
        /**
         * Получает минимальный интервал синхронизации из всех аккаунтов с режимом SCHEDULED
         */
        fun getMinSyncInterval(context: Context): Int {
            val database = MailDatabase.getInstance(context)
            
            // Получаем все аккаунты
            val accounts = kotlinx.coroutines.runBlocking {
                database.accountDao().getAllAccountsList()
            }
            
            // Фильтруем аккаунты которым нужна периодическая синхронизация
            val scheduledAccounts = accounts.filter { account ->
                val isExchange = account.accountType == AccountType.EXCHANGE.name
                val isPush = account.syncMode == SyncMode.PUSH.name
                !isExchange || !isPush // Не Exchange, или Exchange но не Push
            }
            
            // Берём минимальный интервал или 15 по умолчанию
            return scheduledAccounts.minOfOrNull { it.syncIntervalMinutes } ?: 15
        }
        
        fun scheduleWithNightMode(context: Context) {
            val settingsRepo = SettingsRepository.getInstance(context)
            val database = MailDatabase.getInstance(context)
            
            // Получаем минимальный интервал из всех аккаунтов с режимом SCHEDULED
            val accounts = kotlinx.coroutines.runBlocking {
                database.accountDao().getAllAccountsList()
            }
            
            // Фильтруем аккаунты которым нужна периодическая синхронизация
            // (не Exchange с Push, или Exchange с SCHEDULED)
            val scheduledAccounts = accounts.filter { account ->
                val isExchange = account.accountType == AccountType.EXCHANGE.name
                val isPush = account.syncMode == SyncMode.PUSH.name
                !isExchange || !isPush // Не Exchange, или Exchange но не Push
            }
            
            if (scheduledAccounts.isEmpty()) {
                // Все аккаунты используют Push — отменяем периодическую синхронизацию
                cancel(context)
                return
            }
            
            // Берём минимальный интервал из всех scheduled аккаунтов
            val minInterval = scheduledAccounts.minOfOrNull { it.syncIntervalMinutes } ?: 15
            
            // Применяем ночной режим
            val nightModeEnabled = settingsRepo.getNightModeEnabledSync()
            val isNightTime = settingsRepo.isNightTime()
            val effectiveInterval = if (nightModeEnabled && isNightTime && minInterval > 0) {
                maxOf(NIGHT_MODE_INTERVAL.toInt(), minInterval)
            } else {
                minInterval
            }
            
            val wifiOnly = settingsRepo.syncOnWifiOnly.let { 
                kotlinx.coroutines.runBlocking { it.first() }
            }
            
            if (effectiveInterval <= 0) {
                cancel(context)
                return
            }
            
            schedule(context, effectiveInterval.toLong(), wifiOnly)
        }
        
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
        
        fun syncNow(context: Context, wifiOnly: Boolean = false) {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .build()
            
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

package com.iwo.mailclient.sync

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
import com.iwo.mailclient.MainActivity
import com.iwo.mailclient.MailApplication
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.database.SyncMode
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.NotificationStrings
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
        
        var lastNotificationCheck = settingsRepo.getLastNotificationCheckTimeSync()
        // При первом запуске (lastNotificationCheck = 0) не показываем уведомления для старых писем
        val isFirstRun = lastNotificationCheck == 0L
        if (isFirstRun) {
            lastNotificationCheck = System.currentTimeMillis() - 60_000 // Только письма за последнюю минуту
        }
        
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
        
        // Синхронизация контактов GAL (для Exchange аккаунтов)
        syncGalContacts()
        
        // Синхронизация заметок (для Exchange аккаунтов)
        syncNotes()
        
        // Синхронизация календаря (для Exchange аккаунтов)
        syncCalendar()
        
        // Синхронизация задач (для Exchange аккаунтов)
        syncTasks()
        
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
        com.iwo.mailclient.util.SoundPlayer.playReceiveSound(applicationContext)
    }
    
    /**
     * Автоматическая очистка папок — удаляет письма старше N дней
     * Настройки берутся из каждого аккаунта индивидуально
     */
    private suspend fun performAutoTrashCleanup() {
        val oneDayMs = 24 * 60 * 60 * 1000L
        
        // Проверяем раз в день
        val lastCleanup = settingsRepo.getLastTrashCleanupTimeSync()
        if (System.currentTimeMillis() - lastCleanup < oneDayMs) return
        
        val accounts = database.accountDao().getAllAccountsList()
        
        for (account in accounts) {
            // Очистка корзины (type = 4)
            if (account.autoCleanupTrashDays > 0) {
                val cutoffTime = System.currentTimeMillis() - (account.autoCleanupTrashDays * oneDayMs)
                val trashFolders = database.folderDao().getFoldersByAccountList(account.id)
                    .filter { it.type == 4 } // Deleted Items
                
                for (trashFolder in trashFolders) {
                    val oldEmails = database.emailDao().getEmailsOlderThan(trashFolder.id, cutoffTime)
                    if (oldEmails.isNotEmpty()) {
                        val emailIds = oldEmails.map { it.id }
                        mailRepo.deleteEmailsPermanently(emailIds)
                    }
                }
            }
            
            // Очистка локальных черновиков (serverId LIKE 'local_draft_%')
            if (account.autoCleanupDraftsDays > 0) {
                val cutoffTime = System.currentTimeMillis() - (account.autoCleanupDraftsDays * oneDayMs)
                val localDrafts = database.emailDao().getLocalDraftEmails(account.id)
                val oldDrafts = localDrafts.filter { it.dateReceived < cutoffTime }
                for (draft in oldDrafts) {
                    // Удаляем вложения
                    database.attachmentDao().deleteByEmail(draft.id)
                    // Удаляем черновик
                    database.emailDao().delete(draft.id)
                }
            }
            
            // Очистка спама (type = 11)
            if (account.autoCleanupSpamDays > 0) {
                val cutoffTime = System.currentTimeMillis() - (account.autoCleanupSpamDays * oneDayMs)
                val spamFolders = database.folderDao().getFoldersByAccountList(account.id)
                    .filter { it.type == 11 } // Junk Email / Spam
                
                for (spamFolder in spamFolders) {
                    val oldEmails = database.emailDao().getEmailsOlderThan(spamFolder.id, cutoffTime)
                    if (oldEmails.isNotEmpty()) {
                        val emailIds = oldEmails.map { it.id }
                        mailRepo.deleteEmailsPermanently(emailIds)
                    }
                }
            }
        }
        
        settingsRepo.setLastTrashCleanupTime(System.currentTimeMillis())
    }
    
    /**
     * Синхронизация контактов из GAL для Exchange аккаунтов
     * Загружает контакты и сохраняет в локальную БД
     */
    private suspend fun syncGalContacts() {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val accounts = database.accountDao().getAllAccountsList()
        val contactRepo = com.iwo.mailclient.data.repository.ContactRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации контактов (0 = отключено)
            if (account.contactsSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastContactsSyncTimeSync(account.id)
            val intervalMs = account.contactsSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            // Синхронизируем контакты
            try {
                val result = contactRepo.syncGalContactsToDb(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastContactsSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // Игнорируем ошибки синхронизации контактов
            }
        }
    }
    
    /**
     * Синхронизация заметок для Exchange аккаунтов
     */
    private suspend fun syncNotes() {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val accounts = database.accountDao().getAllAccountsList()
        val noteRepo = com.iwo.mailclient.data.repository.NoteRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации заметок (0 = отключено)
            if (account.notesSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastNotesSyncTimeSync(account.id)
            val intervalMs = account.notesSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            // Синхронизируем заметки
            try {
                val result = noteRepo.syncNotes(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastNotesSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // Игнорируем ошибки синхронизации заметок
            }
        }
    }
    
    /**
     * Синхронизация календаря для Exchange аккаунтов
     */
    private suspend fun syncCalendar() {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val accounts = database.accountDao().getAllAccountsList()
        val calendarRepo = com.iwo.mailclient.data.repository.CalendarRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации календаря (0 = отключено)
            if (account.calendarSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastCalendarSyncTimeSync(account.id)
            val intervalMs = account.calendarSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            // Синхронизируем календарь
            try {
                val result = calendarRepo.syncCalendar(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastCalendarSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // Игнорируем ошибки синхронизации календаря
            }
        }
    }
    
    /**
     * Синхронизация задач для Exchange аккаунтов
     */
    private suspend fun syncTasks() {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val accounts = database.accountDao().getAllAccountsList()
        val taskRepo = com.iwo.mailclient.data.repository.TaskRepository(applicationContext)
        
        for (account in accounts) {
            // Только для Exchange аккаунтов
            if (account.accountType != AccountType.EXCHANGE.name) continue
            
            // Проверяем интервал синхронизации задач (0 = отключено)
            if (account.tasksSyncIntervalDays <= 0) continue
            
            // Проверяем когда была последняя синхронизация
            val lastSync = settingsRepo.getLastTasksSyncTimeSync(account.id)
            val intervalMs = account.tasksSyncIntervalDays * oneDayMs
            if (System.currentTimeMillis() - lastSync < intervalMs) continue
            
            // Синхронизируем задачи
            try {
                val result = taskRepo.syncTasks(account.id)
                if (result is EasResult.Success) {
                    settingsRepo.setLastTasksSyncTime(account.id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // Игнорируем ошибки синхронизации задач
            }
        }
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
            
            // Применяем Battery Saver (увеличиваем интервал до 60 мин)
            val batterySaverActive = settingsRepo.shouldApplyBatterySaverRestrictions()
            
            val effectiveInterval = when {
                // Battery Saver имеет приоритет
                batterySaverActive && minInterval > 0 -> maxOf(NIGHT_MODE_INTERVAL.toInt(), minInterval)
                // Затем ночной режим
                nightModeEnabled && isNightTime && minInterval > 0 -> maxOf(NIGHT_MODE_INTERVAL.toInt(), minInterval)
                else -> minInterval
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

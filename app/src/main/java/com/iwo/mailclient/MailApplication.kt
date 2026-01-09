package com.iwo.mailclient

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.sync.PushService
import com.iwo.mailclient.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.conscrypt.Conscrypt
import java.security.Security

class MailApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var settingsRepository: SettingsRepository
        private set
    
    override fun onCreate() {
        super.onCreate()
        initConscrypt()
        settingsRepository = SettingsRepository.getInstance(this)
        createNotificationChannels()
        cleanupDuplicateEmails()
        scheduleSync()
        startPushService()
    }
    
    private fun cleanupDuplicateEmails() {
        // Очистка дублей писем в фоне (один раз при запуске)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val database = com.iwo.mailclient.data.database.MailDatabase.getInstance(this@MailApplication)
                database.emailDao().deleteDuplicateEmails()
            } catch (_: Exception) { }
        }
    }
    
    private fun initConscrypt() {
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (_: Exception) { }
    }
    
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Создаём каналы только если их нет (не сбрасываем пользовательские настройки)
        // При первом запуске используем русский по умолчанию
        // Названия каналов не критичны — пользователь видит их только в настройках Android
        val existingChannels = notificationManager.notificationChannels.map { it.id }.toSet()
        
        // Для новых каналов используем русский (дефолт), т.к. runBlocking в onCreate нежелателен
        val isRussian = true
        
        // Канал для новых писем - ВЫСОКИЙ приоритет для показа на заблокированном экране
        if (CHANNEL_NEW_MAIL !in existingChannels) {
            val newMailChannel = NotificationChannel(
                CHANNEL_NEW_MAIL,
                if (isRussian) "Новые письма" else "New emails",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = if (isRussian) "Уведомления о новых письмах" else "New email notifications"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
            }
            notificationManager.createNotificationChannel(newMailChannel)
        }
        
        // Канал для синхронизации - минимальный приоритет (только иконка в статус-баре)
        if (CHANNEL_SYNC !in existingChannels) {
            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                if (isRussian) "Синхронизация" else "Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = if (isRussian) "Статус синхронизации" else "Sync status"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(syncChannel)
        }
        
        // Канал для статуса ручной синхронизации - показывает уведомление
        if (CHANNEL_SYNC_STATUS !in existingChannels) {
            val syncStatusChannel = NotificationChannel(
                CHANNEL_SYNC_STATUS,
                if (isRussian) "Статус синхронизации" else "Sync status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = if (isRussian) "Уведомления о завершении синхронизации" else "Sync completion notifications"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(syncStatusChannel)
        }
        
        // Канал для напоминаний календаря - ВЫСОКИЙ приоритет
        if (CHANNEL_CALENDAR !in existingChannels) {
            val calendarChannel = NotificationChannel(
                CHANNEL_CALENDAR,
                if (isRussian) "Напоминания календаря" else "Calendar reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = if (isRussian) "Напоминания о событиях календаря" else "Calendar event reminders"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(calendarChannel)
        }
    }
    
    private fun scheduleSync() {
        applicationScope.launch(Dispatchers.IO) {
            // Инициализируем кэш имён отправителей из БД
            com.iwo.mailclient.data.repository.MailRepository.initCacheFromDb(this@MailApplication)
            
            val hasAccounts = MailDatabase.getInstance(this@MailApplication)
                .accountDao().getCount() > 0
            
            if (hasAccounts) {
                val minInterval = SyncWorker.getMinSyncInterval(this@MailApplication)
                val wifiOnly = settingsRepository.syncOnWifiOnly.first()
                if (minInterval > 0) {
                    SyncWorker.schedule(
                        this@MailApplication,
                        intervalMinutes = minInterval.toLong(),
                        wifiOnly = wifiOnly
                    )
                }
            }
        }
    }
    
    private fun startPushService() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val accounts = MailDatabase.getInstance(this@MailApplication)
                    .accountDao().getAllAccountsList()
                
                if (accounts.isEmpty()) return@launch
                
                // Запускаем PushService только если есть Exchange аккаунты с режимом PUSH
                val hasExchangePushAccounts = accounts.any { 
                    it.accountType == AccountType.EXCHANGE.name &&
                    it.syncMode == com.iwo.mailclient.data.database.SyncMode.PUSH.name
                }
                
                if (hasExchangePushAccounts) {
                    PushService.start(this@MailApplication)
                }
                
                // Планируем AlarmManager как fallback для ВСЕХ аккаунтов (и PUSH и SCHEDULED)
                // Это гарантирует синхронизацию даже если WorkManager не сработает
                val minInterval = SyncWorker.getMinSyncInterval(this@MailApplication)
                val intervalMinutes = if (minInterval > 0) minInterval else 15
                PushService.scheduleSyncAlarm(this@MailApplication, intervalMinutes)
            } catch (_: Exception) { }
        }
    }
    
    companion object {
        const val CHANNEL_NEW_MAIL = "new_mail"
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_SYNC_STATUS = "sync_status"
        const val CHANNEL_CALENDAR = "calendar_reminders"
    }
}

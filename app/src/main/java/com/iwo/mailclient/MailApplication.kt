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
        scheduleSync()
        startPushService()
    }
    
    private fun initConscrypt() {
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (_: Exception) { }
    }
    
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Удаляем старые каналы чтобы применить новые настройки
        // ВАЖНО: Android кэширует настройки каналов, поэтому нужно удалить и пересоздать
        try {
            notificationManager.deleteNotificationChannel(CHANNEL_SYNC)
            notificationManager.deleteNotificationChannel(CHANNEL_NEW_MAIL)
        } catch (_: Exception) { }
        
        // Канал для новых писем - ВЫСОКИЙ приоритет для показа на заблокированном экране
        val newMailChannel = NotificationChannel(
            CHANNEL_NEW_MAIL,
            "Новые письма",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о новых письмах"
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
        }
        
        // Канал для синхронизации - минимальный приоритет (только иконка в статус-баре)
        val syncChannel = NotificationChannel(
            CHANNEL_SYNC,
            "Синхронизация",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Статус синхронизации"
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        
        notificationManager.createNotificationChannels(listOf(newMailChannel, syncChannel))
    }
    
    private fun scheduleSync() {
        applicationScope.launch(Dispatchers.IO) {
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
                    val minInterval = SyncWorker.getMinSyncInterval(this@MailApplication)
                    val intervalMinutes = if (minInterval > 0) minInterval else 5
                    PushService.scheduleSyncAlarm(this@MailApplication, intervalMinutes)
                }
            } catch (_: Exception) { }
        }
    }
    
    companion object {
        const val CHANNEL_NEW_MAIL = "new_mail"
        const val CHANNEL_SYNC = "sync"
    }
}

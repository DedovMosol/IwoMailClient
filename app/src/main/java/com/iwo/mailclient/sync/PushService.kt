package com.iwo.mailclient.sync

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.iwo.mailclient.MainActivity
import com.iwo.mailclient.MailApplication
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.database.AccountType
import com.iwo.mailclient.data.database.SyncMode
import com.iwo.mailclient.data.database.MailDatabase
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.data.repository.SettingsRepository
import com.iwo.mailclient.eas.EasResult
import com.iwo.mailclient.ui.NotificationStrings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Сервис для Exchange Direct Push
 * Держит соединение с сервером и получает мгновенные уведомления о новых письмах
 * 
 * Оптимизации:
 * - Без WakeLock (Foreground Service + сетевой стек Android достаточно)
 * - Переиспользование EasClient для каждого аккаунта
 * - Кэширование репозиториев и базы данных
 * - AlarmManager fallback для Xiaomi и других агрессивных OEM
 */
class PushService : Service() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null
    
    // Кэшированные зависимости (создаются один раз)
    private lateinit var database: MailDatabase
    private lateinit var mailRepo: MailRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var accountRepo: AccountRepository
    
    //исправляем race condition при одновременном доступе из разных корутин
    private val easClientCache = java.util.Collections.synchronizedMap(mutableMapOf<Long, com.iwo.mailclient.eas.EasClient>())
    
    private val accountPingJobs = java.util.Collections.synchronizedMap(mutableMapOf<Long, Job>())
    
    // Сохранённые heartbeat для каждого аккаунта (восстанавливаются между перезапусками)
    private val accountHeartbeats = java.util.Collections.synchronizedMap(mutableMapOf<Long, Int>())
        
    // NetworkCallback для отслеживания состояния сети
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkAvailable = true
    
    companion object {
        // Адаптивный heartbeat: начинаем с большого значения, уменьшаем при ошибках
        private const val MIN_HEARTBEAT = 120      // Минимум 2 минуты
        private const val DEFAULT_HEARTBEAT = 480  // Начинаем с 8 минут (оптимизация батареи)
        private const val MAX_HEARTBEAT = 900      // Максимум 15 минут
        private const val HEARTBEAT_INCREASE_STEP = 60  // Увеличиваем на 1 минуту
        private const val SUCCESS_COUNT_TO_INCREASE = 3 // После 3 успехов увеличиваем
        
        // Статусы Ping
        private const val STATUS_EXPIRED = 1
        private const val STATUS_CHANGES_FOUND = 2
        private const val STATUS_HEARTBEAT_OUT_OF_BOUNDS = 5
        private const val STATUS_FOLDER_REFRESH_NEEDED = 7
        private const val STATUS_SERVER_ERROR = 8
        
        private const val NOTIFICATION_ID = 2001
        private const val RESTART_REQUEST_CODE = 2002
        private const val SYNC_ALARM_REQUEST_CODE = 2003
        private const val FOREGROUND_NOTIFICATION_REQUEST_CODE = 2004
        private const val NEW_MAIL_NOTIFICATION_REQUEST_CODE = 2005
        
        const val ACTION_SYNC_ALARM = "com.iwo.mailclient.SYNC_ALARM"
        
        fun start(context: Context) {
            val intent = Intent(context, PushService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.getSharedPreferences("push_service", Context.MODE_PRIVATE)
                .edit().putBoolean("explicit_stop", true).apply()
            cancelSyncAlarm(context)
            context.stopService(Intent(context, PushService::class.java))
        }
        
        fun scheduleSyncAlarm(context: Context, intervalMinutes: Int) {
            if (intervalMinutes <= 0) {
                cancelSyncAlarm(context)
                return
            }
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, SyncAlarmReceiver::class.java).apply {
                action = ACTION_SYNC_ALARM
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, SYNC_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val triggerTime = System.currentTimeMillis() + (intervalMinutes * 60 * 1000L)
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                }
            } catch (_: Exception) { }
        }
        
        fun cancelSyncAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, SyncAlarmReceiver::class.java).apply {
                action = ACTION_SYNC_ALARM
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, SYNC_ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }
        
        /**
         * Очищает кэш EasClient для указанного аккаунта
         * Вызывается при удалении аккаунта
         */
        fun clearAccountCache(context: Context, accountId: Long) {
            context.getSharedPreferences("push_heartbeats", Context.MODE_PRIVATE)
                .edit().remove("heartbeat_$accountId").apply()
        }
    }
    
    /**
     * Загружает сохранённый heartbeat для аккаунта
     */
    private fun loadHeartbeat(accountId: Long): Int {
        return getSharedPreferences("push_heartbeats", Context.MODE_PRIVATE)
            .getInt("heartbeat_$accountId", DEFAULT_HEARTBEAT)
    }
    
    /**
     * Сохраняет heartbeat для аккаунта
     */
    private fun saveHeartbeat(accountId: Long, heartbeat: Int) {
        accountHeartbeats[accountId] = heartbeat
        getSharedPreferences("push_heartbeats", Context.MODE_PRIVATE)
            .edit().putInt("heartbeat_$accountId", heartbeat).apply()
    }
    
    /**
     * Очищает кэш для удалённого аккаунта
     */
    private fun clearCacheForAccount(accountId: Long) {
        easClientCache.remove(accountId)
        accountHeartbeats.remove(accountId)
        accountPingJobs[accountId]?.cancel()
        accountPingJobs.remove(accountId)
    }
    
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("push_service", Context.MODE_PRIVATE)
            .edit().putBoolean("explicit_stop", false).apply()
        
        database = MailDatabase.getInstance(applicationContext)
        mailRepo = MailRepository(applicationContext)
        settingsRepo = SettingsRepository.getInstance(applicationContext)
        accountRepo = AccountRepository(applicationContext)
        
        // Подписываемся на изменение языка для обновления уведомления
        serviceScope.launch {
            settingsRepo.language.collect { _ ->
                updateForegroundNotification()
            }
        }
        
        // Регистрируем NetworkCallback для отслеживания состояния сети
        registerNetworkCallback()
    }
    
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isNetworkAvailable) {
                    isNetworkAvailable = true
                    // Сеть появилась — возобновляем Ping
                    serviceScope.launch {
                        startPushForAllAccounts()
                    }
                }
            }
            
            override fun onLost(network: Network) {
                // Проверяем, есть ли ещё активная сеть
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork == null) {
                    isNetworkAvailable = false
                    // Сеть пропала — останавливаем все Ping
                    accountPingJobs.values.forEach { it.cancel() }
                    accountPingJobs.clear()
                }
            }
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        networkCallback?.let { callback ->
            connectivityManager.registerNetworkCallback(networkRequest, callback)
        }
        
        // Проверяем начальное состояние сети
        isNetworkAvailable = connectivityManager.activeNetwork != null
    }
    
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) { }
        }
        networkCallback = null
    }
    
    private fun updateForegroundNotification() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (_: Exception) { }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startPushForAllAccounts()
        
        // Планируем AlarmManager как fallback сразу при старте
        // Это гарантирует синхронизацию даже если сервис будет убит системой
        serviceScope.launch {
            try {
                val minInterval = SyncWorker.getMinSyncInterval(applicationContext)
                val intervalMinutes = if (minInterval > 0) minInterval else 5
                scheduleSyncAlarm(applicationContext, intervalMinutes)
            } catch (_: Exception) {
                scheduleSyncAlarm(applicationContext, 5)
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        unregisterNetworkCallback()
        pushJob?.cancel()
        accountPingJobs.values.forEach { it.cancel() }
        accountPingJobs.clear()
        easClientCache.clear()
        serviceScope.cancel()
        
        val wasExplicitStop = getSharedPreferences("push_service", Context.MODE_PRIVATE)
            .getBoolean("explicit_stop", false)
        
        if (!wasExplicitStop) {
            scheduleRestart()
        }
        super.onDestroy()
    }
    
    private fun scheduleRestart() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(applicationContext, PushService::class.java)
            val pendingIntent = PendingIntent.getService(
                applicationContext, RESTART_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // 30 секунд — достаточно для восстановления, но не агрессивно для батареи
            val triggerTime = System.currentTimeMillis() + 30_000
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            }
        } catch (_: Exception) {
            try {
                val restartIntent = Intent(applicationContext, PushService::class.java)
                restartIntent.setPackage(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restartIntent)
                } else {
                    applicationContext.startService(restartIntent)
                }
            } catch (_: Exception) { }
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        serviceScope.launch {
            try {
                val minInterval = SyncWorker.getMinSyncInterval(applicationContext)
                val intervalMinutes = if (minInterval > 0) minInterval else 5
                scheduleSyncAlarm(applicationContext, intervalMinutes)
            } catch (_: Exception) {
                scheduleSyncAlarm(applicationContext, 5)
            }
        }
        
        try {
            val restartIntent = Intent(applicationContext, PushService::class.java)
            restartIntent.setPackage(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
        } catch (_: Exception) { }
        
        super.onTaskRemoved(rootIntent)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null

    
    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, FOREGROUND_NOTIFICATION_REQUEST_CODE, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val languageCode = settingsRepo.getLanguageSync()
        val isRussian = languageCode == "ru"
        
        return NotificationCompat.Builder(this, MailApplication.CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(NotificationStrings.getPushServiceTitle(isRussian))
            .setContentText(NotificationStrings.getPushServiceText(isRussian))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun startPushForAllAccounts() {
        pushJob = serviceScope.launch {
            val accounts = database.accountDao().getAllAccountsList()
            // Фильтруем только Exchange аккаунты с режимом PUSH
            val exchangePushAccounts = accounts.filter { 
                it.accountType == AccountType.EXCHANGE.name &&
                it.syncMode == SyncMode.PUSH.name
            }
            
            if (exchangePushAccounts.isEmpty()) {
                stopSelf()
                return@launch
            }
            
            for (account in exchangePushAccounts) {
                startPingForAccount(account)
            }
        }
    }
    
    private fun startPingForAccount(account: AccountEntity) {
        accountPingJobs[account.id]?.cancel()
        
        accountPingJobs[account.id] = serviceScope.launch {
            // Загружаем сохранённый heartbeat или используем дефолтный
            var heartbeat = loadHeartbeat(account.id)
            var consecutiveErrors = 0
            var consecutiveSuccesses = 0  // Для адаптивного увеличения heartbeat
            var pingNotSupported = false
            
            syncAccount(account)
            
            var quickPingCount = 0
            
            while (isActive) {
                try {
                    // Ждём пока сеть станет доступна
                    if (!isNetworkAvailable) {
                        delay(5_000L)
                        continue
                    }
                    
                    // При Battery Saver увеличиваем heartbeat до максимума
                    val effectiveHeartbeat = if (settingsRepo.shouldApplyBatterySaverRestrictions()) {
                        MAX_HEARTBEAT
                    } else {
                        heartbeat
                    }
                    
                    if (pingNotSupported) {
                        val minInterval = SyncWorker.getMinSyncInterval(applicationContext)
                        val intervalMinutes = if (minInterval > 0) minInterval else 5
                        scheduleSyncAlarm(applicationContext, intervalMinutes)
                        break
                    }
                    
                    val startTime = System.currentTimeMillis()
                    val result = doPing(account, effectiveHeartbeat)
                    val elapsed = System.currentTimeMillis() - startTime
                    
                    if (elapsed < 10_000 && result == STATUS_EXPIRED) {
                        quickPingCount++
                        if (quickPingCount >= 3) {
                            pingNotSupported = true
                            quickPingCount = 0
                            continue
                        }
                        delay(5_000L)
                        continue
                    }
                    
                    if (elapsed >= 10_000) quickPingCount = 0
                    
                    when (result) {
                        STATUS_EXPIRED -> {
                            consecutiveErrors = 0
                            consecutiveSuccesses++
                            // Адаптивное увеличение heartbeat при стабильной работе
                            if (consecutiveSuccesses >= SUCCESS_COUNT_TO_INCREASE && heartbeat < MAX_HEARTBEAT) {
                                heartbeat = minOf(heartbeat + HEARTBEAT_INCREASE_STEP, MAX_HEARTBEAT)
                                saveHeartbeat(account.id, heartbeat)
                                consecutiveSuccesses = 0
                            }
                        }
                        STATUS_CHANGES_FOUND -> {
                            syncAccount(account)
                            consecutiveErrors = 0
                            consecutiveSuccesses++
                            // Тоже увеличиваем — это успешный Ping
                            if (consecutiveSuccesses >= SUCCESS_COUNT_TO_INCREASE && heartbeat < MAX_HEARTBEAT) {
                                heartbeat = minOf(heartbeat + HEARTBEAT_INCREASE_STEP, MAX_HEARTBEAT)
                                saveHeartbeat(account.id, heartbeat)
                                consecutiveSuccesses = 0
                            }
                        }
                        STATUS_HEARTBEAT_OUT_OF_BOUNDS -> {
                            // Сервер сказал что heartbeat слишком большой — уменьшаем
                            heartbeat = maxOf(heartbeat / 2, MIN_HEARTBEAT)
                            saveHeartbeat(account.id, heartbeat)
                            consecutiveSuccesses = 0
                        }
                        STATUS_FOLDER_REFRESH_NEEDED -> {
                            syncFolders(account)
                            consecutiveSuccesses = 0
                        }
                        else -> {
                            consecutiveErrors++
                            consecutiveSuccesses = 0
                            // При ошибке уменьшаем heartbeat
                            if (heartbeat > MIN_HEARTBEAT) {
                                heartbeat = maxOf(heartbeat - HEARTBEAT_INCREASE_STEP, MIN_HEARTBEAT)
                                saveHeartbeat(account.id, heartbeat)
                            }
                            if (consecutiveErrors >= 3) {
                                pingNotSupported = true
                                consecutiveErrors = 0
                            } else {
                                delay(60 * 1000L)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    consecutiveErrors++
                    consecutiveSuccesses = 0
                    // При исключении тоже уменьшаем heartbeat
                    if (heartbeat > MIN_HEARTBEAT) {
                        heartbeat = maxOf(heartbeat - HEARTBEAT_INCREASE_STEP, MIN_HEARTBEAT)
                        saveHeartbeat(account.id, heartbeat)
                    }
                    if (consecutiveErrors >= 3) {
                        pingNotSupported = true
                        consecutiveErrors = 0
                    } else {
                        delay(60 * 1000L)
                    }
                }
            }
        }
    }
    
    private suspend fun doPing(account: AccountEntity, heartbeat: Int): Int {
        var folders = database.folderDao().getFoldersByAccountList(account.id)
            .filter { it.syncKey != "0" && it.type in listOf(2, 3, 4, 5, 6) }
        
        if (folders.isEmpty()) {
            mailRepo.syncFolders(account.id)
            val allFolders = database.folderDao().getFoldersByAccountList(account.id)
                .filter { it.type in listOf(2, 3, 4, 5, 6) }
            
            if (allFolders.isEmpty()) return STATUS_FOLDER_REFRESH_NEEDED
            
            for (folder in allFolders) {
                mailRepo.syncEmails(account.id, folder.id)
            }
            
            folders = database.folderDao().getFoldersByAccountList(account.id)
                .filter { it.syncKey != "0" && it.type in listOf(2, 3, 4, 5, 6) }
            
            if (folders.isEmpty()) return STATUS_FOLDER_REFRESH_NEEDED
        }
        
        val folderIds = folders.map { it.serverId }
        val client = easClientCache.getOrPut(account.id) {
            accountRepo.createEasClient(account.id) ?: return STATUS_SERVER_ERROR
        }
        
        return when (val result = client.ping(folderIds, heartbeat)) {
            is EasResult.Success -> result.data.status
            is EasResult.Error -> STATUS_SERVER_ERROR
        }
    }
    
    private data class NewEmailInfo(
        val id: String,
        val senderName: String?,
        val senderEmail: String?,
        val subject: String?
    )
    
    private suspend fun syncAccount(account: AccountEntity) {
        var lastNotificationCheck = settingsRepo.getLastNotificationCheckTimeSync()
        // При первом запуске не показываем уведомления для старых писем
        if (lastNotificationCheck == 0L) {
            lastNotificationCheck = System.currentTimeMillis() - 60_000
        }
        
        val folders = database.folderDao().getFoldersByAccountList(account.id)
            .filter { it.type in listOf(2, 3, 4, 5, 6) }
        
        for (folder in folders) {
            mailRepo.syncEmails(account.id, folder.id)
        }
        
        settingsRepo.setLastSyncTime(System.currentTimeMillis())
        
        val newEmailEntities = database.emailDao().getNewUnreadEmails(account.id, lastNotificationCheck)
        val newEmails = newEmailEntities.map { email ->
            NewEmailInfo(email.id, email.fromName, email.from, email.subject)
        }
        
        if (newEmails.isNotEmpty()) {
            val notificationsEnabled = settingsRepo.notificationsEnabled.first()
            if (notificationsEnabled) {
                showNewMailNotification(newEmails, account.id, account.email)
            }
        }
        
        settingsRepo.setLastNotificationCheckTime(System.currentTimeMillis())
    }
    
    private suspend fun syncFolders(account: AccountEntity) {
        mailRepo.syncFolders(account.id)
    }
    
    private suspend fun showNewMailNotification(newEmails: List<NewEmailInfo>, accountId: Long, accountEmail: String) {
        val count = newEmails.size
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }
        
        getSharedPreferences("push_service", Context.MODE_PRIVATE)
            .edit().putLong("last_notification_time", System.currentTimeMillis()).apply()
        
        val languageCode = settingsRepo.language.first()
        val isRussian = languageCode == "ru"
        
        val latestEmail = newEmails.maxByOrNull { it.id }
        val senderName = latestEmail?.senderName?.takeIf { it.isNotBlank() } 
            ?: latestEmail?.senderEmail?.substringBefore("@")
        val subject = latestEmail?.subject?.takeIf { it.isNotBlank() }
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, accountId)
            if (count == 1 && latestEmail != null) {
                putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, latestEmail.id)
            } else {
                putExtra(MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
            }
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, NEW_MAIL_NOTIFICATION_REQUEST_CODE, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val builder = NotificationCompat.Builder(this, MailApplication.CHANNEL_NEW_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(NotificationStrings.getNewMailTitle(count, senderName, isRussian))
            .setContentText(NotificationStrings.getNewMailText(count, subject, isRussian))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSubText(accountEmail)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Показывать на заблокированном экране
            .setCategory(NotificationCompat.CATEGORY_EMAIL) // Категория для правильной обработки системой
        
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
        
        // Фиксированный ID на аккаунт — новые уведомления перезаписывают старые
        val notificationId = (NEW_MAIL_NOTIFICATION_REQUEST_CODE + accountId).toInt()
        notificationManager.notify(notificationId, builder.build())
        
        // Воспроизводим звук получения письма
        com.iwo.mailclient.util.SoundPlayer.playReceiveSound(applicationContext)
    }
}

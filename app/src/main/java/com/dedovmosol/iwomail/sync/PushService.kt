package com.dedovmosol.iwomail.sync

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
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.eas.FolderType
import com.dedovmosol.iwomail.ui.NotificationStrings
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
    private var heartbeatJob: Job? = null  // Job для периодического обновления статуса
    
    // Кэшированные зависимости (создаются один раз)
    private lateinit var database: MailDatabase
    private lateinit var mailRepo: MailRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var accountRepo: AccountRepository
    
    //исправляем race condition при одновременном доступе из разных корутин
    private val easClientCache = java.util.Collections.synchronizedMap(mutableMapOf<Long, com.dedovmosol.iwomail.eas.EasClient>())
    
    private val accountPingJobs = java.util.Collections.synchronizedMap(mutableMapOf<Long, Job>())
    
    // Сохранённые heartbeat для каждого аккаунта (восстанавливаются между перезапусками)
    private val accountHeartbeats = java.util.Collections.synchronizedMap(mutableMapOf<Long, Int>())
        
    // NetworkCallback для отслеживания состояния сети
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkAvailable = true
    
    companion object {
        private const val TAG = "PushService"
        
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
        
        const val ACTION_SYNC_ALARM = "com.dedovmosol.iwomail.SYNC_ALARM"
        
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
                // Неточный alarm — Android может сдвинуть на несколько минут для батч-обработки
                // Для fallback-синхронизации точность не критична
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                } else {
                    alarmManager.set(
                        android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to schedule sync alarm", e)
            }
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
        
        // Запускаем heartbeat для мониторинга работы сервиса
        startHeartbeat()
    }
    
    /**
     * Периодически обновляет статус сервиса для мониторинга
     * Позволяет ServiceWatchdogReceiver определить что сервис работает
     */
    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    getSharedPreferences("push_service", Context.MODE_PRIVATE)
                        .edit().putLong("last_update", System.currentTimeMillis()).apply()
                    delay(120_000) // Обновляем каждые 2 минуты (достаточно для watchdog)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Heartbeat failed", e)
                }
            }
        }
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
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }
    
    private fun updateForegroundNotification() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to update foreground notification", e)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: startForeground ДОЛЖЕН быть вызван в течение 5 секунд после startForegroundService()
        // Вызываем СРАЗУ до любых async операций чтобы избежать ANR на Android 8.0+
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Проверяем наличие PUSH аккаунтов в фоне
        serviceScope.launch {
            val accounts = database.accountDao().getAllAccountsList()
            val hasExchangePushAccounts = accounts.any { 
                it.accountType == AccountType.EXCHANGE.name &&
                it.syncMode == SyncMode.PUSH.name
            }
            
            if (!hasExchangePushAccounts) {
                // Нет PUSH аккаунтов — останавливаемся
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            
            startPushForAllAccounts()
            
            // Планируем AlarmManager как fallback сразу при старте
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
        android.util.Log.i(TAG, "Service being destroyed")
        
        val wasExplicitStop = getSharedPreferences("push_service", Context.MODE_PRIVATE)
            .getBoolean("explicit_stop", false)
        
        unregisterNetworkCallback()
        heartbeatJob?.cancel()
        pushJob?.cancel()
        accountPingJobs.values.forEach { it.cancel() }
        accountPingJobs.clear()
        easClientCache.clear()
        serviceScope.cancel()
        
        // КРИТИЧНО: Перезапускаем сервис если это не явная остановка
        // Это защита от убийства процесса системой
        if (!wasExplicitStop) {
            android.util.Log.i(TAG, "Service destroyed unexpectedly - scheduling restart")
            scheduleRestart()
        } else {
            android.util.Log.i(TAG, "Service stopped explicitly - not restarting")
        }
        
        super.onDestroy()
    }
    
    private fun scheduleRestart() {
        android.util.Log.i(TAG, "Scheduling service restart")
        
        // Стратегия 1: AlarmManager + PendingIntent.getForegroundService()
        // На Android 12+ (API 31) startForegroundService() из фона запрещён,
        // но exact alarm с getForegroundService() — разрешённое исключение
        // (при наличии SCHEDULE_EXACT_ALARM)
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(applicationContext, PushService::class.java)
            // getForegroundService (API 26+) вместо getService — иначе Android не стартует FG service
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext, RESTART_REQUEST_CODE, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                PendingIntent.getService(
                    applicationContext, RESTART_REQUEST_CODE, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            
            // 60 секунд — перезапуск с разумной задержкой (экономия батареи)
            val triggerTime = System.currentTimeMillis() + 60_000
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                )
            }
            android.util.Log.i(TAG, "AlarmManager restart scheduled via getForegroundService")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to schedule restart alarm", e)
        }
        
        // Стратегия 2: WorkManager fallback (работает даже без SCHEDULE_EXACT_ALARM)
        // WorkManager запускает PushService при появлении подходящих условий
        try {
            val restartWork = androidx.work.OneTimeWorkRequestBuilder<PushRestartWorker>()
                .setInitialDelay(15, java.util.concurrent.TimeUnit.SECONDS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(
                    "push_restart",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    restartWork
                )
            android.util.Log.i(TAG, "WorkManager restart fallback scheduled")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to schedule WorkManager restart", e)
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        // КРИТИЧНО: НЕ вызываем super.onTaskRemoved() - это может остановить сервис
        // Вместо этого планируем fallback синхронизацию и продолжаем работу
        
        android.util.Log.i(TAG, "Task removed - ensuring service continues")
        
        serviceScope.launch {
            try {
                val minInterval = SyncWorker.getMinSyncInterval(applicationContext)
                val intervalMinutes = if (minInterval > 0) minInterval else 5
                scheduleSyncAlarm(applicationContext, intervalMinutes)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to get sync interval", e)
                scheduleSyncAlarm(applicationContext, 5)
            }
        }
        
        // Перезапускаем foreground notification чтобы убедиться что он виден
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to update foreground notification", e)
        }
        
        // Планируем перезапуск на случай если система всё же остановит сервис
        scheduleRestart()
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
        // Отменяем предыдущий job чтобы не дублировать ping-циклы
        pushJob?.cancel()
        accountPingJobs.values.forEach { it.cancel() }
        accountPingJobs.clear()
        
        pushJob = serviceScope.launch {
            val accounts = database.accountDao().getAllAccountsList()
            // Фильтруем только Exchange аккаунты с режимом PUSH
            val exchangePushAccounts = accounts.filter { 
                it.accountType == AccountType.EXCHANGE.name &&
                it.syncMode == SyncMode.PUSH.name
            }
            
            // Очищаем кэш для удалённых аккаунтов
            val activeAccountIds = accounts.map { it.id }.toSet()
            easClientCache.keys.toList().forEach { cachedId ->
                if (cachedId !in activeAccountIds) {
                    clearCacheForAccount(cachedId)
                }
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
    
    /**
     * Проверяет, является ли текущее время ночным (23:00-7:00)
     */
    private fun isNightTime(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 7
    }
    
    /**
     * Проверяет, активен ли режим экономии батареи Android
     */
    private fun isBatterySaverActive(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return powerManager?.isPowerSaveMode == true
    }
    
    private fun startPingForAccount(account: AccountEntity) {
        accountPingJobs[account.id]?.cancel()
        
        val accountId = account.id
        
        accountPingJobs[accountId] = serviceScope.launch {
            // Загружаем сохранённый heartbeat или используем дефолтный
            var heartbeat = loadHeartbeat(accountId)
            var consecutiveErrors = 0
            var consecutiveSuccesses = 0  // Для адаптивного увеличения heartbeat
            var pingNotSupported = false
            
            syncAccount(account)
            
            var quickPingCount = 0
            
            while (isActive) {
                try {
                    // Если сети нет — выходим из цикла
                    // Job будет перезапущен когда сеть появится (в onAvailable)
                    if (!isNetworkAvailable) {
                        break
                    }
                    
                    // Перечитываем настройки аккаунта из БД (могли измениться в UI)
                    val currentAccount = database.accountDao().getAccount(accountId)
                    if (currentAccount == null) {
                        // Аккаунт удалён — выходим из цикла
                        break
                    }
                    
                    // Per-account: проверяем нужно ли применять ограничения Battery Saver
                    val batterySaverActive = isBatterySaverActive()
                    val shouldApplyBatterySaver = batterySaverActive && !currentAccount.ignoreBatterySaver
                    
                    // Per-account: проверяем нужно ли применять ночной режим
                    val shouldApplyNightMode = currentAccount.nightModeEnabled && isNightTime()
                    
                    // Увеличиваем heartbeat при Battery Saver или ночном режиме (per-account)
                    val effectiveHeartbeat = if (shouldApplyBatterySaver || shouldApplyNightMode) {
                        MAX_HEARTBEAT
                    } else {
                        heartbeat
                    }
                    
                    if (pingNotSupported) {
                        val minInterval = SyncWorker.getMinSyncIntervalIncludingPush(applicationContext)
                        val intervalMinutes = if (minInterval > 0) minInterval else 5
                        scheduleSyncAlarm(applicationContext, intervalMinutes)
                        break
                    }
                    
                    val startTime = System.currentTimeMillis()
                    val result = doPing(currentAccount, effectiveHeartbeat)
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
                    
                    // Минимальная пауза 2 секунды между ping'ами для экономии батареи
                    // (если ping завершился быстро, например при потоке новых писем)
                    if (elapsed < 2_000) {
                        delay(2_000 - elapsed)
                    }
                    
                    when (result) {
                        STATUS_EXPIRED -> {
                            consecutiveErrors = 0
                            consecutiveSuccesses++
                            // Адаптивное увеличение heartbeat при стабильной работе
                            if (consecutiveSuccesses >= SUCCESS_COUNT_TO_INCREASE && heartbeat < MAX_HEARTBEAT) {
                                heartbeat = minOf(heartbeat + HEARTBEAT_INCREASE_STEP, MAX_HEARTBEAT)
                                saveHeartbeat(accountId, heartbeat)
                                consecutiveSuccesses = 0
                            }
                        }
                        STATUS_CHANGES_FOUND -> {
                            syncAccount(currentAccount)
                            consecutiveErrors = 0
                            consecutiveSuccesses++
                            // Тоже увеличиваем — это успешный Ping
                            if (consecutiveSuccesses >= SUCCESS_COUNT_TO_INCREASE && heartbeat < MAX_HEARTBEAT) {
                                heartbeat = minOf(heartbeat + HEARTBEAT_INCREASE_STEP, MAX_HEARTBEAT)
                                saveHeartbeat(accountId, heartbeat)
                                consecutiveSuccesses = 0
                            }
                        }
                        STATUS_HEARTBEAT_OUT_OF_BOUNDS -> {
                            // Сервер сказал что heartbeat слишком большой — уменьшаем
                            heartbeat = maxOf(heartbeat / 2, MIN_HEARTBEAT)
                            saveHeartbeat(accountId, heartbeat)
                            consecutiveSuccesses = 0
                        }
                        STATUS_FOLDER_REFRESH_NEEDED -> {
                            syncFolders(currentAccount)
                            consecutiveSuccesses = 0
                        }
                        else -> {
                            consecutiveErrors++
                            consecutiveSuccesses = 0
                            // При ошибке уменьшаем heartbeat
                            if (heartbeat > MIN_HEARTBEAT) {
                                heartbeat = maxOf(heartbeat - HEARTBEAT_INCREASE_STEP, MIN_HEARTBEAT)
                                saveHeartbeat(accountId, heartbeat)
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
                } catch (e: Throwable) {
                    // Ловим ВСЕ исключения включая OutOfMemoryError, StackOverflowError
                    android.util.Log.e("PushService", "ERROR in ping loop for account $accountId", e)
                    consecutiveErrors++
                    consecutiveSuccesses = 0
                    // При исключении тоже уменьшаем heartbeat
                    if (heartbeat > MIN_HEARTBEAT) {
                        heartbeat = maxOf(heartbeat - HEARTBEAT_INCREASE_STEP, MIN_HEARTBEAT)
                        saveHeartbeat(accountId, heartbeat)
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
        // Мониторим также пользовательские папки (type 1, USER_CREATED=12) для push-уведомлений.
        // Без этого перемещённые в пользовательские папки письма не обновляются push'ом.
        var folders = database.folderDao().getFoldersByAccountList(account.id)
            .filter { it.syncKey != "0" && it.type in listOf(
                FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                FolderType.SENT_ITEMS, FolderType.OUTBOX, 1, FolderType.USER_CREATED
            ) }
        
        if (folders.isEmpty()) {
            mailRepo.syncFolders(account.id)
            val allFolders = database.folderDao().getFoldersByAccountList(account.id)
                .filter { it.type in listOf(
                FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                FolderType.SENT_ITEMS, FolderType.OUTBOX, 1, FolderType.USER_CREATED
            ) }
            
            if (allFolders.isEmpty()) return STATUS_FOLDER_REFRESH_NEEDED
            
            for (folder in allFolders) {
                // Полная синхронизация при первом запуске Push
                try {
                mailRepo.syncEmails(account.id, folder.id, forceFullSync = true)
                } catch (_: Exception) {
                    // Продолжаем с другими папками
                }
            }
            
            folders = database.folderDao().getFoldersByAccountList(account.id)
                .filter { it.syncKey != "0" && it.type in listOf(
                FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                FolderType.SENT_ITEMS, FolderType.OUTBOX
            ) }
            
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
        // КРИТИЧНО: Debounce - пропускаем если синхронизация была менее 30 сек назад
        val lastSync = settingsRepo.getLastSyncTimeSync()
        if (System.currentTimeMillis() - lastSync < 30_000) {
            return
        }
        
        var lastNotificationCheck = settingsRepo.getLastNotificationCheckTimeSync()
        // При первом запуске не показываем уведомления для старых писем
        if (lastNotificationCheck == 0L) {
            lastNotificationCheck = System.currentTimeMillis() - 60_000
        }
        
        val folders = database.folderDao().getFoldersByAccountList(account.id)
            .filter { it.type in listOf(
                FolderType.INBOX, FolderType.DRAFTS, FolderType.DELETED_ITEMS,
                FolderType.SENT_ITEMS, FolderType.OUTBOX, 1, FolderType.USER_CREATED
            ) }
        
        for (folder in folders) {
            // Сначала инкрементальная синхронизация, при ошибке - полный ресинк
            try {
                val result = mailRepo.syncEmails(account.id, folder.id, forceFullSync = false)
                if (result is EasResult.Error) {
                    // При ошибке делаем полный ресинк как страховку
            mailRepo.syncEmails(account.id, folder.id, forceFullSync = true)
                }
            } catch (_: Exception) {
                // При исключении тоже пробуем полный ресинк
                try {
                    mailRepo.syncEmails(account.id, folder.id, forceFullSync = true)
                } catch (_: Exception) {
                    // Продолжаем с другими папками
                }
            }
        }
        
        settingsRepo.setLastSyncTime(System.currentTimeMillis())
        
        // КРИТИЧНО: Используем getNewEmailsForNotification вместо getNewUnreadEmails
        // Это позволяет показывать уведомления даже если письмо прочитано на другом устройстве
        val newEmailEntities = database.emailDao().getNewEmailsForNotification(account.id, lastNotificationCheck)
        
        // КРИТИЧНО: Фильтруем письма, для которых УЖЕ показывались уведомления на этом устройстве
        // Решает проблему повторных уведомлений на смартфоне после показа на планшете
        val shownNotifications = getShownNotifications()
        val filteredEmails = newEmailEntities.filter { email ->
            val notifKey = "${account.id}_${email.id}"
            !shownNotifications.contains(notifKey)
        }
        
        val newEmails = filteredEmails.map { email ->
            NewEmailInfo(email.id, email.fromName, email.from, email.subject)
        }
        
        if (newEmails.isNotEmpty()) {
            // КРИТИЧНО: Предзагружаем тело письма ДО показа уведомления
            // Это позволяет пользователю сразу видеть текст при переходе по уведомлению
            for (emailInfo in newEmails) {
                try {
                    // Загружаем тело только если оно пустое
                    val email = database.emailDao().getEmail(emailInfo.id)
                    if (email != null && email.body.isEmpty()) {
                        mailRepo.loadEmailBody(emailInfo.id)
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to preload body for ${emailInfo.id}", e)
                    // Продолжаем с другими письмами
                }
            }
            
            val notificationsEnabled = settingsRepo.notificationsEnabled.first()
            if (notificationsEnabled) {
                showNewMailNotification(newEmails, account.id, account.email)
                // Сохраняем что показали уведомления
                markNotificationsAsShown(newEmails.map { "${account.id}_${it.id}" })
            }
        }
        
        settingsRepo.setLastNotificationCheckTime(System.currentTimeMillis())
    }
    
    private suspend fun syncFolders(account: AccountEntity) {
        mailRepo.syncFolders(account.id)
    }
    
    /**
     * Получить список показанных уведомлений (для предотвращения дублей)
     */
    private fun getShownNotifications(): Set<String> {
        val prefs = getSharedPreferences("push_notifications", Context.MODE_PRIVATE)
        return prefs.getStringSet("shown_notifications", emptySet()) ?: emptySet()
    }
    
    /**
     * Пометить уведомления как показанные
     * КРИТИЧНО: Предотвращает повторные уведомления на разных устройствах
     * Очищаем старые записи (>7 дней) для экономии памяти
     */
    private fun markNotificationsAsShown(notificationKeys: List<String>) {
        val prefs = getSharedPreferences("push_notifications", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("shown_notifications", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        // Добавляем новые
        current.addAll(notificationKeys)
        
        // Очищаем старые (сохраняем только последние 500 записей)
        // Это примерно 500 писем = ~1-2 недели для активного пользователя
        if (current.size > 500) {
            val toKeep = current.toList().takeLast(500).toSet()
            prefs.edit().putStringSet("shown_notifications", toKeep).apply()
        } else {
            prefs.edit().putStringSet("shown_notifications", current).apply()
        }
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
        
        // КРИТИЧНО: Используем уникальный requestCode для каждого уведомления
        // Иначе на заблокированном экране extras теряются из-за FLAG_UPDATE_CURRENT
        // Берём hashCode от emailId (или accountId для множественных писем)
        val uniqueRequestCode = if (count == 1 && latestEmail != null) {
            latestEmail.id.hashCode()
        } else {
            accountId.toInt() + 10000
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, uniqueRequestCode, intent, 
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
        
        val notificationId = 3000 + accountId.toInt()
        notificationManager.notify(notificationId, builder.build())
        
        // Воспроизводим звук получения письма
        com.dedovmosol.iwomail.util.SoundPlayer.playReceiveSound(applicationContext)
    }
}

package com.dedovmosol.iwomail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver для синхронизации по AlarmManager.
 * Работает даже когда PushService убит системой (Xiaomi, Huawei и др.)
 * 
 * Делегирует тяжёлую работу в SyncWorker через WorkManager,
 * так как goAsync() даёт BroadcastReceiver только ~10 секунд.
 */
class SyncAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SyncAlarmReceiver"
        const val ACTION_SYNC_NOW = "com.dedovmosol.iwomail.SYNC_NOW"
        private const val SYNC_STARTED_NOTIFICATION_ID = 9997
    }
    
    
    private fun showSyncStartedNotification(context: Context) {
        val settingsRepo = SettingsRepository.getInstance(context)
        val isRussian = settingsRepo.getLanguageSync() == "ru"
        
        val notification = androidx.core.app.NotificationCompat.Builder(
            context,
            com.dedovmosol.iwomail.MailApplication.CHANNEL_SYNC_STATUS
        )
            .setSmallIcon(com.dedovmosol.iwomail.R.drawable.ic_sync)
            .setContentTitle(if (isRussian) "Синхронизация запущена" else "Sync started")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(SYNC_STARTED_NOTIFICATION_ID, notification)
    }
    
    
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
            
            // КРИТИЧНО: НЕ делаем полную синхронизацию в BroadcastReceiver!
            // goAsync() даёт только ~10 секунд, а полная синхронизация всех аккаунтов
            // (папки + письма + контакты + заметки + календарь + задачи) может занимать минуты.
            // Делегируем всю тяжёлую работу в SyncWorker через WorkManager.
            
            val pendingResult = goAsync()
            val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            
            localScope.launch {
                try {
                    // Быстрая проверка сети (миллисекунды)
                    if (!NetworkMonitor.isNetworkAvailable(context)) {
                        return@launch
                    }
                    
                    // Быстрый debounce (миллисекунды)
                    val settingsRepo = SettingsRepository.getInstance(context)
                    val lastSync = settingsRepo.getLastSyncTimeSync()
                    if (System.currentTimeMillis() - lastSync < 30_000) {
                        return@launch
                    }
                    
                    // Делегируем синхронизацию в SyncWorker (без ограничения по времени)
                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                        .setConstraints(
                            androidx.work.Constraints.Builder()
                                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                    androidx.work.WorkManager.getInstance(context)
                        .enqueueUniqueWork(
                            "sync_alarm_triggered",
                            androidx.work.ExistingWorkPolicy.KEEP, // Не дублируем если уже запущен
                            workRequest
                        )
                    
                    // Планируем следующий alarm (быстро — только чтение настроек)
                    val minInterval = SyncWorker.getMinSyncIntervalIncludingPush(context)
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

}

package com.dedovmosol.iwomail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.database.MailDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver для автозапуска синхронизации после перезагрузки устройства
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            val pendingResult = goAsync()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    val database = MailDatabase.getInstance(context)
                    val accounts = database.accountDao().getAllAccountsList()
                    
                    if (accounts.isEmpty()) {
                        return@launch
                    }
                    
                    // Используем эффективный интервал с учётом ночного режима
                    SyncWorker.scheduleWithNightMode(context)
                    
                    // Планируем AlarmManager как fallback для всех аккаунтов
                    val minInterval = SyncWorker.getMinSyncInterval(context)
                    val intervalMinutes = if (minInterval > 0) minInterval else 15
                    PushService.scheduleSyncAlarm(context, intervalMinutes)
                    
                    // Запускаем PushService только если есть Exchange аккаунты с режимом PUSH
                    val hasExchangePushAccounts = accounts.any { 
                        it.accountType == AccountType.EXCHANGE.name &&
                        it.syncMode == SyncMode.PUSH.name
                    }
                    
                    if (hasExchangePushAccounts) {
                        try {
                            PushService.start(context)
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "Failed to start PushService after boot", e)
                        }
                    }
                    
                    RescheduleRemindersWorker.enqueue(context)
                    
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to handle boot completed", e)
                } finally {
                    scope.cancel()
                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to finish pending result", e)
                    }
                }
            }
        }
    }
    
}

package com.dedovmosol.iwomail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.SyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Сторожевой пёс" для PushService
 * Проверяет запущен ли сервис и перезапускает его при необходимости
 * Срабатывает при разблокировке экрана и подключении зарядки
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val DEBOUNCE_MS = 300_000L // 5 минут между проверками
        private const val STALE_THRESHOLD_MS = 600_000L // 10 минут — порог устаревания PushService
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_POWER_CONNECTED
            -> {
                // Debounce: не чаще раза в 5 минут
                val prefs = context.getSharedPreferences("watchdog", Context.MODE_PRIVATE)
                val lastTrigger = prefs.getLong("last_trigger", 0L)
                val now = System.currentTimeMillis()
                if (now - lastTrigger < DEBOUNCE_MS) return
                prefs.edit().putLong("last_trigger", now).apply()
                
                android.util.Log.i(TAG, "Watchdog triggered by ${intent.action}")
                checkAndRestartService(context)
            }
        }
    }
    
    private fun checkAndRestartService(context: Context) {
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        scope.launch {
            try {
                // Проверяем есть ли аккаунты требующие Push
                val database = MailDatabase.getInstance(context)
                val accounts = database.accountDao().getAllAccountsList()
                
                val needsPushService = accounts.any { 
                    it.accountType == AccountType.EXCHANGE.name &&
                    it.syncMode == SyncMode.PUSH.name
                }
                
                if (!needsPushService) {
                    android.util.Log.i(TAG, "No Push accounts - service not needed")
                    return@launch
                }

                val pushPrefs = context.getSharedPreferences("push_service", Context.MODE_PRIVATE)
                val wasExplicitStop = pushPrefs.getBoolean("explicit_stop", false)
                if (wasExplicitStop) {
                    android.util.Log.i(TAG, "Service was explicitly stopped - skipping restart")
                    return@launch
                }
                
                val lastUpdate = pushPrefs.getLong("last_update", 0L)
                val timeSinceUpdate = System.currentTimeMillis() - lastUpdate
                if (lastUpdate > 0 && timeSinceUpdate < STALE_THRESHOLD_MS) {
                    android.util.Log.i(TAG, "PushService alive (last update ${timeSinceUpdate / 1000}s ago)")
                    return@launch
                }
                
                android.util.Log.i(TAG, "PushService stale or dead (last update ${timeSinceUpdate / 1000}s ago) - restarting")
                PushService.start(context)
                
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e(TAG, "Failed to check service status", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to finish pending result", e)
                }
                scope.cancel()
            }
        }
    }
}

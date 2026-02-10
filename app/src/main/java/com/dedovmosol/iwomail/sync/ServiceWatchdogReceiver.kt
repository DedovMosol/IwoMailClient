package com.dedovmosol.iwomail.sync

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT,    // Пользователь разблокировал устройство
            Intent.ACTION_SCREEN_ON,       // Экран включен
            Intent.ACTION_POWER_CONNECTED  // Зарядка подключена
            -> {
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
                
                // Проверяем запущен ли сервис
                val isServiceRunning = isServiceRunning(context, PushService::class.java)
                
                if (!isServiceRunning) {
                    android.util.Log.w(TAG, "PushService not running - restarting")
                    PushService.start(context)
                } else {
                    android.util.Log.i(TAG, "PushService is running - OK")
                }
                
            } catch (e: Exception) {
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
    
    /**
     * Проверяет запущен ли указанный сервис
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // На Android 8+ используем более надёжный метод
                // Проверяем через SharedPreferences когда сервис последний раз обновлял статус
                val prefs = context.getSharedPreferences("push_service", Context.MODE_PRIVATE)
                val lastUpdate = prefs.getLong("last_update", 0)
                val isRunning = (System.currentTimeMillis() - lastUpdate) < 60_000 // Обновление было менее минуты назад
                
                if (!isRunning) {
                    // Fallback: проверяем через getRunningServices (deprecated но работает)
                    @Suppress("DEPRECATION")
                    val services = manager.getRunningServices(Integer.MAX_VALUE)
                    return services.any { it.service.className == serviceClass.name }
                }
                
                isRunning
            } else {
                // На старых версиях используем getRunningServices
                @Suppress("DEPRECATION")
                val services = manager.getRunningServices(Integer.MAX_VALUE)
                services.any { it.service.className == serviceClass.name }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to check if service is running", e)
            false
        }
    }
}

package com.exchange.mailclient.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.exchange.mailclient.data.database.AccountType
import com.exchange.mailclient.data.database.SyncMode
import com.exchange.mailclient.data.database.MailDatabase
import com.exchange.mailclient.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver для автозапуска синхронизации после перезагрузки устройства
 */
class BootReceiver : BroadcastReceiver() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            scope.launch {
                try {
                    val database = MailDatabase.getInstance(context)
                    val accounts = database.accountDao().getAllAccountsList()
                    
                    if (accounts.isEmpty()) return@launch
                    
                    // Используем эффективный интервал с учётом ночного режима
                    SyncWorker.scheduleWithNightMode(context)
                    
                    // Запускаем PushService только если есть Exchange аккаунты с режимом PUSH
                    val hasExchangePushAccounts = accounts.any { 
                        it.accountType == AccountType.EXCHANGE.name &&
                        it.syncMode == SyncMode.PUSH.name
                    }
                    
                    if (hasExchangePushAccounts) {
                        PushService.start(context)
                    }
                    
                } catch (_: Exception) { }
            }
        }
    }
}

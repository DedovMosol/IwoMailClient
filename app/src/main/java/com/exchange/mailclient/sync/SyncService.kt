package com.exchange.mailclient.sync

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.exchange.mailclient.MailApplication
import com.exchange.mailclient.R

class SyncService : Service() {
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // TODO: Реализовать синхронизацию
        
        stopSelf()
        return START_NOT_STICKY
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, MailApplication.CHANNEL_SYNC)
            .setContentTitle("Синхронизация почты")
            .setContentText("Проверка новых писем...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}


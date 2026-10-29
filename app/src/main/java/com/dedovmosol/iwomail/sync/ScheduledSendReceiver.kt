package com.dedovmosol.iwomail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Срок запланированного письма — точный будильник [ScheduledSend]: отправить сейчас. */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dir = ScheduledSend.dirFromAlarm(context, intent) ?: return
        val pending = goAsync()
        val scope = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.IO)
        scope.launch {
            try {
                ScheduledSend.runNow(context.applicationContext, dir)
            } catch (e: Exception) {
                // Не вышло поставить срочную задачу — письмо отправит подстраховка WorkManager
                android.util.Log.w("ScheduledSendReceiver", "runNow failed: ${e.message}")
            } finally {
                scope.cancel()
                pending.finish()
            }
        }
    }
}

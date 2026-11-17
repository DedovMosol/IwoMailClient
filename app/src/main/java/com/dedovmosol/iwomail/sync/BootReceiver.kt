package com.dedovmosol.iwomail.sync

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dedovmosol.iwomail.data.database.AccountType
import com.dedovmosol.iwomail.data.database.SyncMode
import com.dedovmosol.iwomail.data.database.MailDatabase
import kotlinx.coroutines.Dispatchers
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
        val action = intent.action ?: return
        val exactAlarmGranted = action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        val clockChanged = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        val bootOrReplace = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!exactAlarmGranted && !clockChanged && !bootOrReplace) return

        val app = context.applicationContext
        val pendingResult = goAsync()
        val scope = com.dedovmosol.iwomail.util.supervisedScope(Dispatchers.IO)
        scope.launch {
            try {
                if (exactAlarmGranted || clockChanged) {
                    // Грант exact: система стёрла setExact*; broadcast только при выдаче.
                    // TIME_SET / timezone: RTC_WAKEUP в wall-clock — переставить ближайшие.
                    // REPLACE: не ждать backoff KEEP. FGS с этого broadcast разрешён (API 31).
                    if (exactAlarmGranted && !BackgroundWork.exactAlarmsAllowed(app)) {
                        return@launch
                    }
                    RescheduleRemindersWorker.enqueue(app, replace = exactAlarmGranted)
                    ScheduledSend.rearm(app)
                    if (exactAlarmGranted) {
                        val accounts = MailDatabase.getInstance(app).accountDao().getAllAccountsList()
                        val hasExchangePush = accounts.any {
                            it.accountType == AccountType.EXCHANGE.name &&
                                it.syncMode == SyncMode.PUSH.name
                        }
                        if (hasExchangePush) {
                            try {
                                PushService.startOrSchedule(app)
                            } catch (e: Exception) {
                                android.util.Log.w(TAG, "Failed to start PushService after exact-alarm grant", e)
                            }
                        }
                    }
                    return@launch
                }

                val database = MailDatabase.getInstance(app)
                val accounts = database.accountDao().getAllAccountsList()

                if (accounts.isEmpty()) {
                    return@launch
                }

                // Используем эффективный интервал с учётом ночного режима
                SyncWorker.scheduleWithNightMode(app)

                // Планируем AlarmManager как fallback для всех аккаунтов
                val minInterval = SyncWorker.getMinSyncInterval(app)
                val intervalMinutes = if (minInterval > 0) minInterval else 15
                PushService.scheduleSyncAlarm(app, intervalMinutes)

                // Запускаем PushService только если есть Exchange аккаунты с режимом PUSH
                val hasExchangePushAccounts = accounts.any {
                    it.accountType == AccountType.EXCHANGE.name &&
                        it.syncMode == SyncMode.PUSH.name
                }

                if (hasExchangePushAccounts) {
                    try {
                        // BG-02: на Android 15+ из BOOT_COMPLETED dataSync-FGS не запустить — через точный будильник
                        PushService.startOrSchedule(app, fromBoot = action == Intent.ACTION_BOOT_COMPLETED)
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to start PushService after boot", e)
                    }
                }

                RescheduleRemindersWorker.enqueue(app)
                // Будильники отложенной отправки стёрты перезагрузкой (и обновлением) — заново
                ScheduledSend.rearm(app)

                // Обновляем виджет — на ряде устройств ACTION_APPWIDGET_UPDATE
                // не отправляется после MY_PACKAGE_REPLACED
                com.dedovmosol.iwomail.widget.updateMailWidget(app)

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to handle $action", e)
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

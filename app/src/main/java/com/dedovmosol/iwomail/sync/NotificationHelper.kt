package com.dedovmosol.iwomail.sync

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dedovmosol.iwomail.MainActivity
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.NotificationStrings
import kotlinx.coroutines.flow.first

/**
 * Единый хелпер для уведомлений о новых письмах.
 * Используется из PushService и SyncWorker — устраняет дублирование ~90% логики.
 */
object NotificationHelper {

    data class NewEmailInfo(
        val id: String,
        val senderName: String?,
        val senderEmail: String?,
        val subject: String?,
        val dateReceived: Long = 0L
    )

    /**
     * Shared Mutex: атомарный check+show+mark уведомлений.
     * Предотвращает дублирование при одновременном вызове из PushService и SyncWorker.
     */
    val notificationMutex = kotlinx.coroutines.sync.Mutex()

    private const val PREFS_NAME = "push_notifications"
    private const val KEY_SHOWN = "shown_notifications"
    private const val MAX_SHOWN_ENTRIES = 500

    fun getShownNotifications(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SHOWN, emptySet())?.toSet() ?: emptySet()
    }

    /**
     * Пометить уведомления как показанные.
     * LinkedHashSet сохраняет порядок вставки в рамках сессии — при превышении лимита
     * вытесняются самые старые записи (FIFO).
     *
     * Примечание: SharedPreferences не гарантирует порядок при чтении (HashSet),
     * поэтому FIFO гарантия действует только в пределах одного add-batch.
     */
    fun markNotificationsAsShown(context: Context, notificationKeys: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = LinkedHashSet(prefs.getStringSet(KEY_SHOWN, emptySet()) ?: emptySet())
        current.addAll(notificationKeys)

        if (current.size > MAX_SHOWN_ENTRIES) {
            val excess = current.size - MAX_SHOWN_ENTRIES
            val iter = current.iterator()
            repeat(excess) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        prefs.edit().putStringSet(KEY_SHOWN, current).apply()
    }

    suspend fun showNewMailNotification(
        context: Context,
        newEmails: List<NewEmailInfo>,
        accountId: Long,
        accountEmail: String,
        settingsRepo: SettingsRepository
    ) {
        val count = newEmails.size

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }

        val languageCode = settingsRepo.language.first()
        val isRussian = languageCode == "ru"

        val latestEmail = newEmails.maxByOrNull { it.dateReceived }
            ?: newEmails.firstOrNull()
        val senderName = latestEmail?.senderName?.takeIf { it.isNotBlank() }
            ?: latestEmail?.senderEmail?.substringBefore("@")
        val subject = latestEmail?.subject?.takeIf { it.isNotBlank() }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SWITCH_ACCOUNT_ID, accountId)
            if (count == 1 && latestEmail != null) {
                putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, latestEmail.id)
            } else {
                putExtra(MainActivity.EXTRA_OPEN_INBOX_UNREAD, true)
            }
        }

        // КРИТИЧНО: Уникальный requestCode — иначе extras теряются из-за FLAG_UPDATE_CURRENT
        val uniqueRequestCode = if (count == 1 && latestEmail != null) {
            latestEmail.id.hashCode()
        } else {
            100_000 + accountId.toInt()
        }

        val pendingIntent = PendingIntent.getActivity(
            context, uniqueRequestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val builder = NotificationCompat.Builder(context, MailApplication.CHANNEL_NEW_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(NotificationStrings.getNewMailTitle(count, senderName, isRussian))
            .setContentText(NotificationStrings.getNewMailText(count, subject, isRussian))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSubText(accountEmail)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)

        if (count > 1) {
            val senders = newEmails.mapNotNull { email ->
                email.senderName?.takeIf { it.isNotBlank() }
                    ?: email.senderEmail?.substringBefore("@")
            }
            if (senders.isNotEmpty()) {
                builder.setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(NotificationStrings.getNewMailBigText(senders, isRussian))
                )
            }
        } else if (count == 1 && !subject.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(subject))
        }

        val notificationId = 200_000 + accountId.toInt()
        val markReadIntent = Intent(context, MailNotificationActionReceiver::class.java).apply {
            action = MailNotificationActionReceiver.ACTION_MARK_READ
            putExtra(MailNotificationActionReceiver.EXTRA_ACCOUNT_ID, accountId)
            putExtra(MailNotificationActionReceiver.EXTRA_EMAIL_IDS, newEmails.map { it.id }.toTypedArray())
            putExtra(MailNotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            MailNotificationActionReceiver.requestCodeForAccount(accountId),
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            com.dedovmosol.iwomail.R.drawable.ic_check,
            context.getString(com.dedovmosol.iwomail.R.string.notification_mark_read),
            markReadPendingIntent
        )

        notificationManager.notify(notificationId, builder.build())
        com.dedovmosol.iwomail.util.SoundPlayer.playReceiveSound(context)
    }
}

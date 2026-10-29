package com.dedovmosol.iwomail.sync

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.dedovmosol.iwomail.R
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.ui.isRussianLanguage

/**
 * Уведомления об отправке из фона (отложенная, очередь) — один источник (DRY). Язык — из настроек приложения, а не
 * системы: так же, как интерфейс.
 */
object SendNotifications {

    fun isRussian(context: Context): Boolean =
        isRussianLanguage(SettingsRepository.getInstance(context.applicationContext).getLanguageSync())

    fun show(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!NotificationHelper.canPostNotifications(context)) return
        try {
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_email)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (e: Exception) {
            android.util.Log.w("SendNotifications", "notify failed: ${e.message}")
        }
    }

    /** Письмо не отправлено (ошибка не временная или попытки кончились); [savedAsDraft] — лежит в Черновиках. */
    fun notSent(context: Context, channel: String, id: Int, subject: String, reason: String, savedAsDraft: Boolean) {
        val ru = isRussian(context)
        show(
            context, channel, id,
            NotificationStrings.getEmailNotSent(ru),
            NotificationStrings.getEmailNotSentText(subject, reason, savedAsDraft, ru)
        )
    }

    /** Свой id на письмо: два уведомления подряд не затирают друг друга. */
    fun idFor(key: String): Int = NOTIFICATION_ID_BASE + (key.hashCode() and 0xFFFF)

    private const val NOTIFICATION_ID_BASE = 0x5E_0000
}

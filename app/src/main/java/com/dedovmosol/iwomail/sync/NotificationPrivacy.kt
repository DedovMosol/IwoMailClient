package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import com.dedovmosol.iwomail.data.security.LockPrivacy

/**
 * BG-08: что уведомления о письмах, событиях, задачах и отправке показывают на экране блокировки устройства.
 *
 * - Всегда VISIBILITY_PRIVATE с publicVersion без содержимого. На защищённом экране блокировки система покажет
 *   содержимое, только если пользователь сам разрешил это в настройках телефона; иначе — publicVersion. Раньше стоял
 *   VISIBILITY_PUBLIC: отправитель и тема были видны всем, даже при «скрывать содержимое» в системе. Без
 *   publicVersion PRIVATE показывает хотя бы заголовок, а у письма заголовок — отправитель.
 * - Блокировка приложения включена: полное уведомление тоже без содержимого. Иначе пароль приложения обходится
 *   шторкой разблокированного телефона.
 */
object NotificationPrivacy {

    /**
     * [publicTitle] и [publicText] — нейтральный текст без имён, тем и названий. [hidden] — блокировка приложения
     * включена (см. [isContentHidden]).
     */
    fun apply(
        context: Context,
        builder: NotificationCompat.Builder,
        channelId: String,
        @DrawableRes smallIcon: Int,
        publicTitle: String,
        publicText: String? = null,
        hidden: Boolean
    ): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(publicTitle)
            .setContentText(publicText)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
        if (hidden) {
            builder.setContentTitle(publicTitle)
                .setContentText(publicText)
                .setSubText(null)
                .setStyle(null)
        }
        return builder
    }

    /** Блокировка приложения включена и пароль задан — содержимое не показывается и в шторке. */
    fun isContentHidden(context: Context): Boolean = try {
        LockPrivacy.contentHiddenSync(SettingsRepository.getInstance(context.applicationContext))
    } catch (e: Exception) {
        // Настройки не прочитались — скрыть (в безопасную сторону)
        android.util.Log.w("NotificationPrivacy", "lock state unavailable: ${e.javaClass.simpleName}")
        true
    }
}

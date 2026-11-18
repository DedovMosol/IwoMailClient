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

    /**
     * Блокировку только что включили: уже показанные уведомления перевыпускаются без содержимого (иначе
     * отправитель и тема остались бы в шторке разблокированного телефона). Свои уведомления с содержимым — те,
     * у которых есть publicVersion (её ставит [apply]); заголовок и текст берутся из неё. Без звука повторно.
     */
    fun hideActiveContent(context: Context) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        val active = try { nm.activeNotifications } catch (_: Exception) { return }
        for (sbn in active) {
            val notification = sbn.notification ?: continue
            val public = notification.publicVersion ?: continue
            try {
                val builder = android.app.Notification.Builder.recoverBuilder(context, notification)
                    .setContentTitle(public.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
                    .setContentText(public.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
                    .setSubText(null)
                    .setStyle(null)
                    .setOnlyAlertOnce(true)
                // setStyle(null) не стирает данные стиля из extras (build() сливает их с исходными): их читают
                // слушатели уведомлений и часы — убираются из готового уведомления
                val rebuilt = builder.build()
                CONTENT_EXTRAS.forEach { rebuilt.extras.remove(it) }
                nm.notify(sbn.tag, sbn.id, rebuilt)
            } catch (e: Exception) {
                // Не удалось перевыпустить (OEM) — убрать: содержимое не должно остаться видимым
                android.util.Log.w("NotificationPrivacy", "re-post failed: ${e.javaClass.simpleName}")
                nm.cancel(sbn.tag, sbn.id)
            }
        }
    }

    private val CONTENT_EXTRAS = listOf(
        android.app.Notification.EXTRA_BIG_TEXT,
        android.app.Notification.EXTRA_TEMPLATE,
        android.app.Notification.EXTRA_TEXT_LINES,
        android.app.Notification.EXTRA_SUMMARY_TEXT,
        android.app.Notification.EXTRA_INFO_TEXT,
        android.app.Notification.EXTRA_SUB_TEXT,
        android.app.Notification.EXTRA_TITLE_BIG,
        // NotificationCompat кладёт имя класса стиля под этим ключом
        "androidx.core.app.extra.COMPAT_TEMPLATE"
    )

    /** Блокировка приложения включена и пароль задан — содержимое не показывается и в шторке. */
    fun isContentHidden(context: Context): Boolean = try {
        LockPrivacy.contentHiddenSync(SettingsRepository.getInstance(context.applicationContext))
    } catch (e: Exception) {
        // Настройки не прочитались — скрыть (в безопасную сторону)
        android.util.Log.w("NotificationPrivacy", "lock state unavailable: ${e.javaClass.simpleName}")
        true
    }
}

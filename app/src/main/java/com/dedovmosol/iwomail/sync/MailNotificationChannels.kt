package com.dedovmosol.iwomail.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.dedovmosol.iwomail.MailApplication

/**
 * Каналы уведомлений о почте — своя группа на каждый аккаунт (NotificationChannelGroup): звук, важность и режим
 * «Не беспокоить» пользователь настраивает в системе для каждого аккаунта отдельно. В группе два канала:
 * «Новые письма» и «Важные отправители» (звучит и в тихие часы приложения).
 *
 * Прежний общий канал «new_mail»: его настройки (важность, звук, вибрация, индикатор) переносятся в каналы
 * аккаунтов при их создании, затем он удаляется. Звук и важность канала после создания меняет только пользователь,
 * поэтому каналы создаются один раз; обновляются лишь названия (язык) и название группы (адрес аккаунта).
 */
object MailNotificationChannels {

    private const val GROUP_PREFIX = "account_"
    private const val MAIL_PREFIX = "mail_"
    private const val VIP_PREFIX = "mail_vip_"

    fun groupId(accountId: Long): String = "$GROUP_PREFIX$accountId"
    fun mailChannelId(accountId: Long): String = "$MAIL_PREFIX$accountId"
    fun vipChannelId(accountId: Long): String = "$VIP_PREFIX$accountId"

    /** Канал уведомления о письмах аккаунта; группа и каналы создаются при первом обращении. */
    fun channelFor(context: Context, accountId: Long, accountEmail: String, vip: Boolean, ru: Boolean): String {
        ensure(context, accountId, accountEmail, ru)
        return if (vip) vipChannelId(accountId) else mailChannelId(accountId)
    }

    /** Группа и оба канала аккаунта; уже есть — обновляются только названия. */
    fun ensure(context: Context, accountId: Long, accountEmail: String, ru: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val group = groupId(accountId)
        manager.createNotificationChannelGroup(NotificationChannelGroup(group, accountEmail.ifBlank { "#$accountId" }))
        val mailName = if (ru) "Новые письма" else "New emails"
        val vipName = if (ru) "Важные отправители" else "Important senders"
        val legacy = manager.getNotificationChannel(MailApplication.CHANNEL_NEW_MAIL)

        val mail = manager.getNotificationChannel(mailChannelId(accountId))
        if (mail == null) {
            manager.createNotificationChannel(
                NotificationChannel(mailChannelId(accountId), mailName, legacy?.importance ?: NotificationManager.IMPORTANCE_HIGH).apply {
                    this.group = group
                    description = if (ru) "Новые письма аккаунта $accountEmail" else "New emails for $accountEmail"
                    if (legacy != null) {
                        setSound(legacy.sound, legacy.audioAttributes)
                        enableVibration(legacy.shouldVibrate())
                        vibrationPattern = legacy.vibrationPattern
                        enableLights(legacy.shouldShowLights())
                        lightColor = legacy.lightColor
                        setShowBadge(legacy.canShowBadge())
                        lockscreenVisibility = legacy.lockscreenVisibility
                    } else {
                        enableVibration(true)
                        enableLights(true)
                        setShowBadge(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                }
            )
        } else if (mail.name != mailName) {
            mail.name = mailName
            manager.createNotificationChannel(mail)
        }

        val vip = manager.getNotificationChannel(vipChannelId(accountId))
        if (vip == null) {
            manager.createNotificationChannel(
                NotificationChannel(vipChannelId(accountId), vipName, NotificationManager.IMPORTANCE_HIGH).apply {
                    this.group = group
                    description = if (ru) "Письма от отправителей, отмеченных как важные" else "Emails from senders marked as important"
                    enableVibration(true)
                    enableLights(true)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        } else if (vip.name != vipName) {
            vip.name = vipName
            manager.createNotificationChannel(vip)
        }
    }

    /**
     * Каналы — по текущим аккаунтам: недостающие создаются, группы удалённых аккаунтов убираются, прежний общий
     * канал удаляется (его настройки уже перенесены).
     */
    fun sync(context: Context, accounts: List<Pair<Long, String>>, ru: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        accounts.forEach { (id, email) -> ensure(context, id, email, ru) }
        val keep = accounts.mapTo(HashSet()) { (id, _) -> groupId(id) }
        manager.notificationChannelGroups
            .filter { it.id.startsWith(GROUP_PREFIX) && it.id !in keep }
            .forEach { manager.deleteNotificationChannelGroup(it.id) }
        if (accounts.isNotEmpty()) manager.deleteNotificationChannel(MailApplication.CHANNEL_NEW_MAIL)
    }

    /** Аккаунт удалён — его группа и каналы тоже. */
    fun deleteForAccount(context: Context, accountId: Long) {
        context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannelGroup(groupId(accountId))
    }

    /** Системные настройки уведомлений аккаунта (канал «Новые письма»): звук, вибрация, важность. */
    fun settingsIntent(context: Context, accountId: Long): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, mailChannelId(accountId))
}

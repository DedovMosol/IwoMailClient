package com.dedovmosol.iwomail.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.MailApplication
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Каналы уведомлений по аккаунтам: группа на аккаунт, перенос настроек прежнего общего канала, уборка. */
@RunWith(RobolectricTestRunner::class)
// Без MailApplication: её фоновая сверка каналов при запуске шла бы параллельно с тестом
@Config(sdk = [34], application = android.app.Application::class)
class MailNotificationChannelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.notificationChannels.forEach { manager.deleteNotificationChannel(it.id) }
        manager.notificationChannelGroups.forEach { manager.deleteNotificationChannelGroup(it.id) }
    }

    @Test
    fun `each account gets its own group with mail and important-senders channels`() {
        val channel = MailNotificationChannels.channelFor(context, 7L, "me@corp.example", vip = false, ru = true)
        val vip = MailNotificationChannels.channelFor(context, 7L, "me@corp.example", vip = true, ru = true)

        assertThat(channel).isEqualTo("mail_7")
        assertThat(vip).isEqualTo("mail_vip_7")
        assertThat(manager.getNotificationChannelGroup("account_7").name.toString()).isEqualTo("me@corp.example")
        assertThat(manager.getNotificationChannel("mail_7").group).isEqualTo("account_7")
        assertThat(manager.getNotificationChannel("mail_vip_7").importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }

    @Test
    fun `user settings of the old shared channel move to the account channel`() {
        manager.createNotificationChannel(
            NotificationChannel(MailApplication.CHANNEL_NEW_MAIL, "Новые письма", NotificationManager.IMPORTANCE_LOW).apply {
                enableVibration(false)
                setShowBadge(false)
            }
        )

        MailNotificationChannels.sync(context, listOf(1L to "a@corp.example"), ru = true)

        val migrated = manager.getNotificationChannel("mail_1")
        assertThat(migrated.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(migrated.shouldVibrate()).isFalse()
        assertThat(migrated.canShowBadge()).isFalse()
        // Прежний общий канал больше не показывается в настройках системы
        assertThat(manager.getNotificationChannel(MailApplication.CHANNEL_NEW_MAIL)).isNull()
    }

    @Test
    fun `groups of removed accounts are deleted`() {
        MailNotificationChannels.ensure(context, 1L, "a@corp.example", ru = false)
        MailNotificationChannels.ensure(context, 2L, "b@corp.example", ru = false)

        MailNotificationChannels.sync(context, listOf(2L to "b@corp.example"), ru = false)

        assertThat(manager.notificationChannelGroups.map { it.id }).containsExactly("account_2")
        assertThat(manager.getNotificationChannel("mail_1")).isNull()
    }

    @Test
    fun `channel names follow the app language`() {
        MailNotificationChannels.ensure(context, 3L, "c@corp.example", ru = true)
        MailNotificationChannels.ensure(context, 3L, "c@corp.example", ru = false)

        assertThat(manager.getNotificationChannel("mail_3").name.toString()).isEqualTo("New emails")
        assertThat(manager.getNotificationChannel("mail_vip_3").name.toString()).isEqualTo("Important senders")
    }

    @Test
    fun `deleting an account removes its channels`() {
        MailNotificationChannels.ensure(context, 4L, "d@corp.example", ru = false)

        MailNotificationChannels.deleteForAccount(context, 4L)

        assertThat(manager.getNotificationChannel("mail_4")).isNull()
        assertThat(manager.getNotificationChannel("mail_vip_4")).isNull()
    }
}

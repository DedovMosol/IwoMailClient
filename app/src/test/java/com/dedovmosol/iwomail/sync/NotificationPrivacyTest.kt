package com.dedovmosol.iwomail.sync

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BG-08: экран блокировки устройства не показывает отправителя, тему и названия. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class NotificationPrivacyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun mail() = NotificationCompat.Builder(context, "ch")
        .setSmallIcon(R.drawable.ic_email)
        .setContentTitle("Ivan Petrov")
        .setContentText("Salary review")
        .setSubText("user@example.com")
        .setStyle(NotificationCompat.BigTextStyle().bigText("Salary review"))

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.allText() = extras.keySet().mapNotNull { extras.getCharSequence(it)?.toString() }

    @Test
    fun `lock screen gets a contentless public version`() {
        val n = NotificationPrivacy.apply(context, mail(), "ch", R.drawable.ic_email, "New mail", "New email", hidden = false).build()

        assertThat(n.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        val pub = n.publicVersion
        assertThat(pub).isNotNull()
        assertThat(pub.title()).isEqualTo("New mail")
        assertThat(pub.text()).isEqualTo("New email")
        assertThat(pub.allText().joinToString()).doesNotContain("Ivan")
        assertThat(pub.allText().joinToString()).doesNotContain("Salary")
        assertThat(pub.allText().joinToString()).doesNotContain("example.com")
        // Без блокировки приложения полное уведомление прежнее
        assertThat(n.title()).isEqualTo("Ivan Petrov")
        assertThat(n.text()).isEqualTo("Salary review")
    }

    @Test
    fun `with app lock the full notification is neutral too`() {
        val n = NotificationPrivacy.apply(context, mail(), "ch", R.drawable.ic_email, "New mail", "New email", hidden = true).build()

        assertThat(n.title()).isEqualTo("New mail")
        assertThat(n.text()).isEqualTo("New email")
        val all = n.allText().joinToString()
        assertThat(all).doesNotContain("Ivan")
        assertThat(all).doesNotContain("Salary")
        assertThat(all).doesNotContain("example.com")
        assertThat(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).isNull()
    }

    private fun postMail(lockOn: Boolean): Notification {
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val settings = com.dedovmosol.iwomail.data.repository.SettingsRepository.getInstance(context)
        val prefs = context.getSharedPreferences("bg08_${System.nanoTime()}", Context.MODE_PRIVATE)
        com.dedovmosol.iwomail.data.security.AppLockManager.resetForTesting()
        com.dedovmosol.iwomail.data.security.AppLockManager.setStorageForTesting(prefs)
        if (lockOn) {
            prefs.edit().putString(
                com.dedovmosol.iwomail.data.security.AppLockManager.KEY_LOCK_HASH,
                com.dedovmosol.iwomail.data.security.AppLockManager.buildStoredHash("pass", ByteArray(16), 1_000)
            ).commit()
        }
        kotlinx.coroutines.runBlocking {
            settings.setAppLockEnabled(lockOn)
            NotificationHelper.showNewMailNotification(
                context = context,
                displayEmails = listOf(NotificationHelper.NewEmailInfo("e1", "Ivan Petrov", "ivan@example.com", "Salary review", 1L)),
                totalCount = 1,
                markReadEmailIds = listOf("e1"),
                accountId = 42L,
                accountEmail = "user@example.com",
                settingsRepo = settings
            )
        }
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        return org.robolectric.Shadows.shadowOf(nm).allNotifications.last()
    }

    @Test
    fun `posted mail notification keeps the sender off the lock screen`() {
        try {
            val n = postMail(lockOn = false)
            assertThat(n.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
            assertThat(n.publicVersion.allText().joinToString()).doesNotContain("Ivan")
            assertThat(n.publicVersion.allText().joinToString()).doesNotContain("Salary")
            assertThat(n.title()).isEqualTo("Ivan Petrov")
        } finally {
            com.dedovmosol.iwomail.data.security.AppLockManager.resetForTesting()
        }
    }

    @Test
    fun `posted mail notification hides content when the app lock is on`() {
        try {
            val n = postMail(lockOn = true)
            val all = n.allText().joinToString()
            assertThat(all).doesNotContain("Ivan")
            assertThat(all).doesNotContain("Salary")
            assertThat(all).doesNotContain("example.com")
            // «Прочитано» остаётся: действие не раскрывает содержимое
            assertThat(n.actions).hasLength(1)
        } finally {
            com.dedovmosol.iwomail.data.security.AppLockManager.resetForTesting()
        }
    }

    @Test
    fun `public version without text has only the title`() {
        val n = NotificationPrivacy.apply(context, mail(), "ch", R.drawable.ic_task, "Task reminder", hidden = false).build()
        assertThat(n.publicVersion.title()).isEqualTo("Task reminder")
        assertThat(n.publicVersion.text()).isNull()
    }
}

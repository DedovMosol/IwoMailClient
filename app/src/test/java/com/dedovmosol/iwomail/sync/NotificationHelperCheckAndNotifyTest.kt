package com.dedovmosol.iwomail.sync

import android.content.Context
import com.dedovmosol.iwomail.data.database.EmailDao
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.database.NotificationEmailSummary
import com.dedovmosol.iwomail.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты для [NotificationHelper.checkAndNotifyNewMail].
 *
 * Проверяют корректность детекта новых писем по СЕРВЕРНОМУ high-water-mark (а не по часам
 * устройства) — исправление бага, когда письмо синкается, но системное уведомление не всплывает
 * из-за рассинхрона часов Exchange-сервера и устройства.
 *
 * Без Robolectric: БД/репозиторий/контекст — MockK-моки; сам показ уведомления
 * ([showNewMailNotification]) и доступ к SharedPreferences подменены через mockkObject.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationHelperCheckAndNotifyTest {

    private val accountId = 1L
    private val accountEmail = "user@example.com"

    private lateinit var context: Context
    private lateinit var database: MailDatabase
    private lateinit var emailDao: EmailDao
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        database = mockk()
        emailDao = mockk()
        settingsRepo = mockk(relaxed = true)
        every { database.emailDao() } returns emailDao
        // По умолчанию Inbox пуст (клэмп не срабатывает); тесты переопределяют при необходимости.
        coEvery { emailDao.getMaxInboxDateReceived(accountId) } returns 0L

        // Изолируем побочные эффекты Android/SharedPreferences.
        mockkObject(NotificationHelper)
        every { NotificationHelper.getShownNotifications(any()) } returns emptySet()
        every { NotificationHelper.markNotificationsAsShown(any(), any()) } just Runs
        coEvery {
            NotificationHelper.showNewMailNotification(any(), any(), any(), any(), any(), any(), any())
        } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(NotificationHelper)
    }

    private fun summary(id: String, date: Long) =
        NotificationEmailSummary(id = id, from = "s@x.com", fromName = "S", subject = "hi", dateReceived = date)

    @Test
    fun `first run with existing inbox baselines to max dateReceived and does not query candidates`() = runTest {
        coEvery { settingsRepo.getLastNotificationCheckTime(accountId) } returns 0L
        coEvery { emailDao.getMaxInboxDateReceived(accountId) } returns 5_000L

        NotificationHelper.checkAndNotifyNewMail(context, database, settingsRepo, accountId, accountEmail)

        coVerify(exactly = 1) { settingsRepo.setLastNotificationCheckTime(accountId, 5_000L) }
        coVerify(exactly = 0) { emailDao.getNewEmailsForNotification(any(), any()) }
        coVerify(exactly = 0) {
            NotificationHelper.showNewMailNotification(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `first run with empty inbox baselines to sentinel 1 so first future mail notifies`() = runTest {
        coEvery { settingsRepo.getLastNotificationCheckTime(accountId) } returns 0L
        coEvery { emailDao.getMaxInboxDateReceived(accountId) } returns null

        NotificationHelper.checkAndNotifyNewMail(context, database, settingsRepo, accountId, accountEmail)

        coVerify(exactly = 1) { settingsRepo.setLastNotificationCheckTime(accountId, 1L) }
    }

    @Test
    fun `no new candidates keeps high-water-mark unchanged`() = runTest {
        coEvery { settingsRepo.getLastNotificationCheckTime(accountId) } returns 1_000L
        coEvery { emailDao.getNewEmailsForNotification(accountId, 1_000L) } returns emptyList()

        NotificationHelper.checkAndNotifyNewMail(context, database, settingsRepo, accountId, accountEmail)

        coVerify(exactly = 0) { settingsRepo.setLastNotificationCheckTime(any(), any()) }
        coVerify(exactly = 0) {
            NotificationHelper.showNewMailNotification(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `new mail advances hwm to max candidate date even when notifications disabled`() = runTest {
        coEvery { settingsRepo.getLastNotificationCheckTime(accountId) } returns 1_000L
        coEvery { emailDao.getNewEmailsForNotification(accountId, 1_000L) } returns
            listOf(summary("1_a", 3_000L), summary("1_b", 2_000L))
        every { settingsRepo.notificationsEnabled } returns flowOf(false)

        NotificationHelper.checkAndNotifyNewMail(context, database, settingsRepo, accountId, accountEmail)

        coVerify(exactly = 1) { settingsRepo.setLastNotificationCheckTime(accountId, 3_000L) }
        coVerify(exactly = 0) {
            NotificationHelper.showNewMailNotification(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `new mail with notifications enabled shows notification and advances hwm`() = runTest {
        coEvery { settingsRepo.getLastNotificationCheckTime(accountId) } returns 1_000L
        coEvery { emailDao.getNewEmailsForNotification(accountId, 1_000L) } returns
            listOf(summary("1_a", 3_000L), summary("1_b", 2_000L))
        every { settingsRepo.notificationsEnabled } returns flowOf(true)

        NotificationHelper.checkAndNotifyNewMail(context, database, settingsRepo, accountId, accountEmail)

        coVerify(exactly = 1) {
            NotificationHelper.showNewMailNotification(
                context, any(), 2, any(), accountId, accountEmail, settingsRepo
            )
        }
        coVerify(exactly = 1) { NotificationHelper.markNotificationsAsShown(context, any()) }
        coVerify(exactly = 1) { settingsRepo.setLastNotificationCheckTime(accountId, 3_000L) }
    }

    @Test
    fun `stale future checkpoint is clamped down to newest inbox mail`() = runTest {
        // Зависший device-time чекпойнт (9000) выше самого свежего письма (3000) — напр. рассинхрон часов.
        coEvery { settingsRepo.getLastNotificationCheckTime(accountId) } returns 9_000L
        coEvery { emailDao.getMaxInboxDateReceived(accountId) } returns 3_000L
        coEvery { emailDao.getNewEmailsForNotification(accountId, 3_000L) } returns emptyList()

        NotificationHelper.checkAndNotifyNewMail(context, database, settingsRepo, accountId, accountEmail)

        // Запрос идёт по клэмпнутому значению (3000), а не по завышенному 9000.
        coVerify(exactly = 1) { emailDao.getNewEmailsForNotification(accountId, 3_000L) }
        // Клэмп персистится, чтобы будущие письма (>3000) снова уведомляли.
        coVerify(exactly = 1) { settingsRepo.setLastNotificationCheckTime(accountId, 3_000L) }
    }

    @Test
    fun `already shown mail is not renotified but hwm still advances`() = runTest {
        coEvery { settingsRepo.getLastNotificationCheckTime(accountId) } returns 1_000L
        coEvery { emailDao.getNewEmailsForNotification(accountId, 1_000L) } returns
            listOf(summary("1_a", 3_000L))
        every { settingsRepo.notificationsEnabled } returns flowOf(true)
        every { NotificationHelper.getShownNotifications(any()) } returns setOf("1_1_a")

        NotificationHelper.checkAndNotifyNewMail(context, database, settingsRepo, accountId, accountEmail)

        coVerify(exactly = 0) {
            NotificationHelper.showNewMailNotification(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { settingsRepo.setLastNotificationCheckTime(accountId, 3_000L) }
    }
}

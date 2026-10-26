package com.dedovmosol.iwomail.sync

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.dedovmosol.iwomail.MailApplication
import com.dedovmosol.iwomail.data.model.AccountServerHealth
import com.dedovmosol.iwomail.data.model.ErrorKind
import com.dedovmosol.iwomail.data.model.ServerProblemType
import com.dedovmosol.iwomail.data.repository.AccountServerHealthRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * РЕАЛЬНЫЕ тесты фонового уведомления о потере связи с сервером (НЕ моки/заглушки):
 *  - реальный [ServerHealthNotifier];
 *  - реальный [AccountServerHealthRepository] (его гистерезис/деривация проблемы);
 *  - реальный Android [NotificationManager] (Robolectric-shadow) — проверяем ФАКТИЧЕСКИ показанные
 *    уведомления (канал/заголовок/текст/категория), а не вызовы моков.
 *
 * Изоляция: минимальный тест-Application [NotifierTestApp] (не [MailApplication]), чтобы процесс-
 * lifetime наблюдатель из MailApplication.onCreate не трогал общий синглтон во время тестов.
 * Это тест-харнесс, а НЕ подмена кода под тестом. Перед каждым тестом состояние сбрасывается
 * настоящим API (reset репозитория, cancelStale(emptySet) очищает notified + снимает уведомления).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NotifierTestApp::class)
class ServerHealthNotifierTest {

    private lateinit var app: Application
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        nm = app.getSystemService(NotificationManager::class.java)
        // POST_NOTIFICATIONS нужен на SDK 33+ (иначе canPostNotifications=false).
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm.createNotificationChannel(
            NotificationChannel(
                MailApplication.CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        // Сброс общего состояния настоящим API.
        AccountServerHealthRepository.reset()
        ServerHealthNotifier.cancelStale(app, emptySet())
        nm.cancelAll()
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun notifId(accountId: Long) = 300_000 + accountId.toInt()
    private fun posted(accountId: Long): Notification? = shadowOf(nm).getNotification(notifId(accountId))
    private fun title(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun text(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun subText(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    private fun healthOf(type: ServerProblemType, accountId: Long = 1L) =
        AccountServerHealth(accountId = accountId, problemType = type)

    private fun state(accountId: Long, email: String, type: ServerProblemType) =
        ServerHealthNotifier.AccountConnState(accountId, email, healthOf(type, accountId))

    private fun syncOne(
        accountId: Long,
        type: ServerProblemType,
        email: String = "user@contoso.com",
        enabled: Boolean = true,
        isRussian: Boolean = true
    ) = ServerHealthNotifier.sync(app, accountId, email, healthOf(type, accountId), enabled, isRussian)

    // ───────────────────────── sync: показ/контент ─────────────────────────

    @Test
    fun `posts notification on visible problem with correct channel, content, category`() {
        syncOne(1L, ServerProblemType.PrimaryDownNoFallback, email = "a@contoso.com", isRussian = true)

        val n = posted(1L)
        assertThat(n).isNotNull()
        assertThat(shadowOf(nm).size()).isEqualTo(1)
        assertThat(n!!.channelId).isEqualTo(MailApplication.CHANNEL_CONNECTION)
        assertThat(title(n)).isEqualTo("Нет связи с сервером")
        assertThat(text(n)).isEqualTo("Сервер недоступен")
        assertThat(subText(n)).isEqualTo("a@contoso.com")
        assertThat(n.category).isEqualTo(Notification.CATEGORY_ERROR)
    }

    @Test
    fun `english locale title and text`() {
        syncOne(1L, ServerProblemType.Timeout, isRussian = false)

        val n = posted(1L)
        assertThat(title(n)).isEqualTo("No server connection")
        assertThat(text(n)).isEqualTo("Server is not responding")
    }

    @Test
    fun `problem type to text mapping (russian)`() {
        fun textFor(accountId: Long, type: ServerProblemType): String? {
            syncOne(accountId, type, isRussian = true)
            return text(posted(accountId))
        }
        // Разные accountId → независимые уведомления (без срабатывания анти-дубль guard).
        assertThat(textFor(101L, ServerProblemType.PrimaryDownNoFallback)).isEqualTo("Сервер недоступен")
        assertThat(textFor(102L, ServerProblemType.BothServersDown)).isEqualTo("Оба сервера недоступны")
        assertThat(textFor(103L, ServerProblemType.AuthError)).isEqualTo("Ошибка авторизации на сервере")
        assertThat(textFor(104L, ServerProblemType.CertError)).isEqualTo("Ошибка TLS/сертификата сервера")
        assertThat(textFor(105L, ServerProblemType.ServerError)).isEqualTo("Сервер возвращает ошибку")
        assertThat(textFor(106L, ServerProblemType.Timeout)).isEqualTo("Сервер не отвечает")
    }

    // ───────────────────────── sync: условие показа ─────────────────────────

    @Test
    fun `does not post when using fallback successfully (mail still flows)`() {
        syncOne(1L, ServerProblemType.PrimaryDownUsingFallback)
        assertThat(posted(1L)).isNull()
        assertThat(shadowOf(nm).size()).isEqualTo(0)
    }

    @Test
    fun `does not post when healthy (None)`() {
        syncOne(1L, ServerProblemType.None)
        assertThat(posted(1L)).isNull()
    }

    @Test
    fun `cancels notification on recovery`() {
        syncOne(1L, ServerProblemType.ServerError)
        assertThat(posted(1L)).isNotNull()

        syncOne(1L, ServerProblemType.None) // восстановление
        assertThat(posted(1L)).isNull()
    }

    @Test
    fun `respects swipe - does not re-post while same problem persists`() {
        syncOne(1L, ServerProblemType.Timeout)
        assertThat(posted(1L)).isNotNull()

        nm.cancel(notifId(1L)) // пользователь смахнул
        assertThat(posted(1L)).isNull()

        syncOne(1L, ServerProblemType.Timeout) // проблема та же
        assertThat(posted(1L)).isNull() // не навязываемся повторно
    }

    @Test
    fun `re-arms after recovery then new problem notifies again`() {
        syncOne(1L, ServerProblemType.Timeout)
        assertThat(posted(1L)).isNotNull()

        syncOne(1L, ServerProblemType.None) // recovery → снято, guard сброшен
        assertThat(posted(1L)).isNull()

        syncOne(1L, ServerProblemType.Timeout) // новая проблема
        assertThat(posted(1L)).isNotNull() // снова показываем
    }

    // ───────────────────────── sync: гейты ─────────────────────────

    @Test
    fun `master toggle off suppresses and cancels existing`() {
        syncOne(1L, ServerProblemType.AuthError, enabled = true)
        assertThat(posted(1L)).isNotNull()

        syncOne(1L, ServerProblemType.AuthError, enabled = false)
        assertThat(posted(1L)).isNull()
    }

    @Test
    fun `no POST_NOTIFICATIONS permission suppresses`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        syncOne(1L, ServerProblemType.AuthError)
        assertThat(posted(1L)).isNull()
    }

    // ───────────────────────── несколько аккаунтов / stale ─────────────────────────

    @Test
    fun `per-account notifications are independent`() {
        syncOne(1L, ServerProblemType.Timeout, email = "a@c.com")
        syncOne(2L, ServerProblemType.AuthError, email = "b@c.com")

        assertThat(posted(1L)).isNotNull()
        assertThat(posted(2L)).isNotNull()
        assertThat(shadowOf(nm).size()).isEqualTo(2)

        // восстановление аккаунта 1 не трогает аккаунт 2
        syncOne(1L, ServerProblemType.None, email = "a@c.com")
        assertThat(posted(1L)).isNull()
        assertThat(posted(2L)).isNotNull()
    }

    @Test
    fun `cancelStale cancels removed accounts and keeps active`() {
        syncOne(1L, ServerProblemType.Timeout)
        syncOne(2L, ServerProblemType.Timeout)
        syncOne(3L, ServerProblemType.Timeout)
        assertThat(shadowOf(nm).size()).isEqualTo(3)

        ServerHealthNotifier.cancelStale(app, setOf(1L, 3L)) // аккаунт 2 удалён

        assertThat(posted(1L)).isNotNull()
        assertThat(posted(2L)).isNull()
        assertThat(posted(3L)).isNotNull()
    }

    @Test
    fun `clears stale leftover notification on healthy state (process restart)`() {
        // «Висяк» от прошлого процесса: уведомление в системе есть, а in-memory notified пуст
        // (постим напрямую через NotificationManager, минуя ServerHealthNotifier).
        val leftover = NotificationCompat.Builder(app, MailApplication.CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("stale")
            .build()
        nm.notify(notifId(1L), leftover)
        assertThat(posted(1L)).isNotNull()

        // Новый процесс, health=None → sync снимает висяк.
        syncOne(1L, ServerProblemType.None)
        assertThat(posted(1L)).isNull()
    }

    // ───────────────────────── reconcile (снимок → уведомления) ─────────────────────────

    @Test
    fun `reconcile posts problems and skips healthy in one pass`() {
        ServerHealthNotifier.reconcile(
            app,
            listOf(
                state(1L, "a@c.com", ServerProblemType.AuthError),
                state(2L, "b@c.com", ServerProblemType.None)
            ),
            notificationsEnabled = true,
            isRussian = false
        )
        assertThat(posted(1L)).isNotNull()
        assertThat(posted(2L)).isNull()
    }

    @Test
    fun `reconcile cancels accounts dropped from the snapshot`() {
        ServerHealthNotifier.reconcile(
            app,
            listOf(
                state(1L, "a@c.com", ServerProblemType.Timeout),
                state(2L, "b@c.com", ServerProblemType.Timeout)
            ),
            notificationsEnabled = true, isRussian = false
        )
        assertThat(posted(1L)).isNotNull()
        assertThat(posted(2L)).isNotNull()

        // Следующий снимок без аккаунта 2 (аккаунт удалён/отключён).
        ServerHealthNotifier.reconcile(
            app,
            listOf(state(1L, "a@c.com", ServerProblemType.Timeout)),
            notificationsEnabled = true, isRussian = false
        )
        assertThat(posted(1L)).isNotNull()
        assertThat(posted(2L)).isNull()
    }

    // ───────── интеграция: реальный репозиторий (гистерезис/деривация) → уведомление ─────────

    @Test
    fun `network errors notify only after 3-failure hysteresis`() {
        val id = 7L
        AccountServerHealthRepository.reportSyncOutcome(id, success = false, errorKind = ErrorKind.Network)
        syncFromRepo(id)
        assertThat(posted(id)).isNull() // 1 сбой — ниже порога

        AccountServerHealthRepository.reportSyncOutcome(id, success = false, errorKind = ErrorKind.Network)
        syncFromRepo(id)
        assertThat(posted(id)).isNull() // 2

        AccountServerHealthRepository.reportSyncOutcome(id, success = false, errorKind = ErrorKind.Network)
        syncFromRepo(id)
        assertThat(posted(id)).isNotNull() // 3 → PrimaryDownNoFallback → показ
        assertThat(text(posted(id))).isEqualTo("Server unavailable")
    }

    @Test
    fun `auth error notifies immediately (no hysteresis)`() {
        val id = 8L
        AccountServerHealthRepository.reportSyncOutcome(id, success = false, errorKind = ErrorKind.Auth)
        syncFromRepo(id)
        assertThat(posted(id)).isNotNull()
        assertThat(text(posted(id))).isEqualTo("Server authorization error")
    }

    @Test
    fun `success after failures clears the notification`() {
        val id = 9L
        repeat(3) { AccountServerHealthRepository.reportSyncOutcome(id, success = false, errorKind = ErrorKind.Network) }
        syncFromRepo(id)
        assertThat(posted(id)).isNotNull()

        AccountServerHealthRepository.reportSyncOutcome(id, success = true)
        syncFromRepo(id)
        assertThat(posted(id)).isNull()
    }

    @Test
    fun `primary down but fallback working does not notify`() {
        val id = 10L
        repeat(3) {
            AccountServerHealthRepository.reportSyncOutcome(
                id, success = false, errorKind = ErrorKind.Network, hasAlternateUrl = true
            )
        }
        // «Работает резервный» — это УСПЕХ через резервный сервер, а не просто 3 сбоя основного
        // (раньше статус ставился и тогда, когда резервный тоже лежал).
        AccountServerHealthRepository.reportSyncOutcome(id, success = true, usedFallback = true, hasAlternateUrl = true)
        assertThat(AccountServerHealthRepository.healthSnapshot(id).problemType)
            .isEqualTo(ServerProblemType.PrimaryDownUsingFallback)
        syncFromRepo(id)
        assertThat(posted(id)).isNull() // почта идёт по резерву — не тревожим
    }

    @Test
    fun `both servers down notifies`() {
        val id = 11L
        repeat(3) {
            AccountServerHealthRepository.reportSyncOutcome(
                id, success = false, errorKind = ErrorKind.Network, hasAlternateUrl = true
            )
        }
        repeat(3) {
            AccountServerHealthRepository.reportSyncOutcome(
                id, success = false, errorKind = ErrorKind.Network, hasAlternateUrl = true, usedFallback = true
            )
        }
        assertThat(AccountServerHealthRepository.healthSnapshot(id).problemType)
            .isEqualTo(ServerProblemType.BothServersDown)
        syncFromRepo(id)
        assertThat(posted(id)).isNotNull()
        assertThat(text(posted(id))).isEqualTo("Both servers unavailable")
    }

    private fun syncFromRepo(accountId: Long) = ServerHealthNotifier.sync(
        app, accountId, "user@contoso.com",
        AccountServerHealthRepository.healthSnapshot(accountId),
        notificationsEnabled = true, isRussian = false
    )

    // ───────────────────── connectionStates: реальный поток + репозиторий ─────────────────────

    @Test
    fun `connectionStates projects per-account repository health`() = runTest {
        AccountServerHealthRepository.reportSyncOutcome(1L, success = false, errorKind = ErrorKind.Auth)
        val source = MutableStateFlow(listOf(1L to "a@c.com", 2L to "b@c.com"))

        val states = ServerHealthNotifier.connectionStates(source).first()

        assertThat(states.map { it.accountId }).containsExactly(1L, 2L)
        assertThat(states.first { it.accountId == 1L }.health.problemType).isEqualTo(ServerProblemType.AuthError)
        assertThat(states.first { it.accountId == 1L }.email).isEqualTo("a@c.com")
        assertThat(states.first { it.accountId == 2L }.health.problemType).isEqualTo(ServerProblemType.None)
    }

    @Test
    fun `connectionStates empty account list yields empty snapshot`() = runTest {
        val states = ServerHealthNotifier.connectionStates(MutableStateFlow(emptyList())).first()
        assertThat(states).isEmpty()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connectionStates dedups identical account sets and re-subscribes on change`() = runTest {
        AccountServerHealthRepository.reportSyncOutcome(1L, success = false, errorKind = ErrorKind.Auth)
        val source = MutableStateFlow(listOf(1L to "a@c.com"))
        val seen = mutableListOf<List<ServerHealthNotifier.AccountConnState>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ServerHealthNotifier.connectionStates(source).collect { seen.add(it) }
        }
        runCurrent()
        assertThat(seen.last().map { it.accountId }).containsExactly(1L)

        val before = seen.size
        source.value = listOf(1L to "a@c.com") // тот же набор → distinctUntilChanged глушит
        runCurrent()
        assertThat(seen.size).isEqualTo(before)

        source.value = listOf(1L to "a@c.com", 2L to "b@c.com") // набор изменился → переподписка
        runCurrent()
        assertThat(seen.last().map { it.accountId }).containsExactly(1L, 2L)

        job.cancel()
    }
}


/** Минимальный тест-Application: без тяжёлого MailApplication.onCreate (и его health-наблюдателя). */
class NotifierTestApp : Application()

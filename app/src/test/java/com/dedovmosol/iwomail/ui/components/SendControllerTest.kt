package com.dedovmosol.iwomail.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.network.ConnectionFailure
import com.dedovmosol.iwomail.sync.ScheduledSend
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * Отправка с отменой: письмо сразу на диске (подстраховка WorkManager), отмена возвращает его в Черновики, нет связи —
 * его досылает воркер. Сервер, Черновики и сообщения — подмена [SendEnvironment]; файлы и WorkManager — настоящие.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeEnvironment : SendEnvironment {
        var delayMs = 0L
        var result: EasResult<Boolean> = EasResult.Success(true)
        var draftId: String? = "1_new-draft"
        val sent: MutableList<ScheduledSend.Job> = Collections.synchronizedList(mutableListOf())
        val returned: MutableList<ScheduledSend.Job> = Collections.synchronizedList(mutableListOf())
        val afterSent: MutableList<ScheduledSend.Job> = Collections.synchronizedList(mutableListOf())
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var played = 0

        override suspend fun undoDelayMs(context: Context) = delayMs
        override suspend fun draftStamp(context: Context, draftId: String): Long? = 42L
        override suspend fun send(context: Context, job: ScheduledSend.Job): EasResult<Boolean> { sent += job; return result }
        override suspend fun returnToDrafts(context: Context, job: ScheduledSend.Job): String? { returned += job; return draftId }
        override suspend fun afterSent(context: Context, job: ScheduledSend.Job) { afterSent += job }
        override fun playSent(context: Context) { played++ }
        override fun message(context: Context, text: String, long: Boolean) { messages += text }
        override fun isRussian(context: Context) = true
    }

    private val env = FakeEnvironment()
    private val controller = SendController(env, Dispatchers.Default)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        File(context.filesDir, ScheduledSend.ROOT_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, ScheduledSend.ROOT_DIR).deleteRecursively()
    }

    private val account = mockk<AccountEntity>().also { every { it.id } returns 1L }

    private fun email(subject: String = "S", draftId: String? = "1_old-draft") = PendingEmail(
        account = account, to = "a@corp.example", cc = "", bcc = "", subject = subject, body = "<p>B</p>",
        attachments = listOf(AttachmentData("a.pdf", "application/pdf", byteArrayOf(1))),
        importance = 1, requestReadReceipt = false, requestDeliveryReceipt = false, draftId = draftId
    )

    /** Запустить отправку; итог — "success" / "cancel" / "error". */
    private fun start(mail: PendingEmail = email()): CompletableDeferred<String> {
        val outcome = CompletableDeferred<String>()
        controller.startSend(
            mail, "Отправка", context,
            onSuccess = { outcome.complete("success") },
            onCancel = { outcome.complete("cancel") },
            onError = { outcome.complete("error") }
        )
        return outcome
    }

    private suspend fun awaitCountdown() = withTimeout(5_000) {
        while (controller.state.phase != SendPhase.COUNTDOWN) delay(10)
    }

    private fun pending() = ScheduledSend.pending(context)

    @Test
    fun `without the undo window the email is sent at once and leaves nothing behind`() = runBlocking<Unit> {
        val outcome = start()

        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("success")
        val job = env.sent.single()
        assertThat(job.mail.subject).isEqualTo("S")
        assertThat(job.clientId).isNotEmpty()
        assertThat(job.draftId).isEqualTo("1_old-draft")
        assertThat(job.draftStamp).isEqualTo(42L)
        assertThat(pending()).isEmpty()
        assertThat(env.played).isEqualTo(1)
        withTimeout(5_000) { while (env.afterSent.isEmpty()) delay(10) }
        // Плашка гаснет сразу после колбэка (finally контроллера) — ждём, а не проверяем мгновенно
        withTimeout(5_000) { while (controller.state.isActive) delay(10) }
    }

    @Test
    fun `while waiting the email is on disk with a backstop after the window`() = runBlocking<Unit> {
        env.delayMs = 3_000
        val startedAt = System.currentTimeMillis()
        val outcome = start()
        awaitCountdown()

        val entry = pending().single()
        assertThat(entry.undo).isTrue()
        assertThat(entry.sendAt).isAtLeast(startedAt + 3_000 + SendController.UNDO_BACKSTOP_MS)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ScheduledSend.workName(entry.dir)).get()
        assertThat(work.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(controller.state.countdownMs).isEqualTo(3_000L)

        controller.cancel()
        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("cancel")
    }

    @Test
    fun `undo returns the email to Drafts, opens it and nothing is sent`() = runBlocking<Unit> {
        env.delayMs = 3_000
        val opened = async { withTimeout(5_000) { controller.undone.first() } }
        val outcome = start()
        awaitCountdown()

        controller.cancel()

        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("cancel")
        assertThat(opened.await()).isEqualTo("1_new-draft")
        assertThat(env.sent).isEmpty()
        assertThat(env.returned.single().draftId).isEqualTo("1_old-draft")
        assertThat(pending()).isEmpty()
        assertThat(env.messages).contains(NotificationStrings.getSendUndone(true))
    }

    @Test
    fun `undo that cannot save the draft still sends the email`() = runBlocking<Unit> {
        env.delayMs = 3_000
        env.draftId = null
        val outcome = start()
        awaitCountdown()

        controller.cancel()

        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("success")
        // Письмо не потеряно: снова ждёт отправки воркером — и видно в списке запланированных
        assertThat(pending().single().undo).isFalse()
        assertThat(env.messages).contains(NotificationStrings.getUndoDraftFailed(true))
    }

    @Test
    fun `when the window ends the email is sent`() = runBlocking<Unit> {
        env.delayMs = 300
        val outcome = start()

        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("success")
        assertThat(env.sent).hasSize(1)
        assertThat(pending()).isEmpty()
    }

    @Test
    fun `cancel after the window does nothing`() = runBlocking<Unit> {
        val outcome = start()
        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("success")

        controller.cancel()

        assertThat(env.returned).isEmpty()
    }

    @Test
    fun `no connection leaves the email to the worker`() = runBlocking<Unit> {
        env.result = EasResult.Error("${ConnectionFailure.TIMEOUT.tag} java.net.SocketTimeoutException")
        val outcome = start()

        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("success")
        val entry = pending().single()
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ScheduledSend.workName(entry.dir)).get()
        assertThat(work.map { it.state }).contains(WorkInfo.State.ENQUEUED)
        // Ждёт связи уже как обычное ожидающее письмо: видно в списке запланированных, его можно отменить
        assertThat(entry.undo).isFalse()
        assertThat(entry.listed(System.currentTimeMillis())).isTrue()
        assertThat(env.returned).isEmpty()
        assertThat(env.messages).contains(NotificationStrings.getSendQueuedOffline(true))
    }

    @Test
    fun `rejected email goes to Drafts with the reason`() = runBlocking<Unit> {
        env.result = EasResult.Error("Неверный адрес получателя: x")
        val outcome = start()

        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("error")
        assertThat(env.returned).hasSize(1)
        assertThat(pending()).isEmpty()
        assertThat(env.messages.single()).contains("Неверный адрес получателя")
        assertThat(env.played).isEqualTo(0)
    }

    @Test
    fun `a new email sends the waiting one at once instead of losing it`() = runBlocking<Unit> {
        env.delayMs = 10_000
        val first = start(email("first", draftId = "1_a"))
        awaitCountdown()

        val second = start(email("second", draftId = "1_b"))

        assertThat(withTimeout(5_000) { first.await() }).isEqualTo("success")
        assertThat(env.sent.single().mail.subject).isEqualTo("first")
        awaitCountdown()
        controller.cancel()
        assertThat(withTimeout(5_000) { second.await() }).isEqualTo("cancel")
        assertThat(env.sent).hasSize(1)
    }

    @Test
    fun `email cancelled elsewhere during the window is not sent`() = runBlocking<Unit> {
        env.delayMs = 1_000
        val outcome = start()
        awaitCountdown()

        ScheduledSend.take(context, pending().single().dir)

        assertThat(withTimeout(5_000) { outcome.await() }).isEqualTo("success")
        assertThat(env.sent).isEmpty()
        assertThat(env.played).isEqualTo(0)
    }
}

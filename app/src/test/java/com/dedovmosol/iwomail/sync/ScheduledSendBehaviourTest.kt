package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Отложенные письма: планирование, перенос, отмена — с настоящим WorkManager (тестовым) и файлами. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledSendBehaviourTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        root().deleteRecursively()
    }

    @After
    fun tearDown() {
        root().deleteRecursively()
    }

    private fun root() = File(context.filesDir, ScheduledSend.ROOT_DIR)

    private fun job(subject: String, sendAt: Long, draftId: String? = null, undo: Boolean = false) = ScheduledSend.Job(
        accountId = 1L,
        mail = OutgoingMail("a@corp.example", "", "", subject, "<p>B</p>",
            attachments = listOf(Triple("a.pdf", "application/pdf", byteArrayOf(1, 2)))),
        sendAt = sendAt,
        clientId = "cid-$subject",
        draftId = draftId,
        undo = undo
    )

    private fun work(dir: File): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(ScheduledSend.workName(dir)).get()

    private val inAnHour get() = System.currentTimeMillis() + 3_600_000L

    @Test
    fun `scheduled mail is listed without reading attachments and has a backstop task`() = runBlocking<Unit> {
        val before = ScheduledSend.changes.value
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))

        val entry = ScheduledSend.pending(context).single()
        assertThat(entry.dir).isEqualTo(dir)
        assertThat(entry.subject).isEqualTo("S")
        assertThat(entry.attachmentCount).isEqualTo(1)
        assertThat(entry.undo).isFalse()
        assertThat(work(dir).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(ScheduledSend.changes.value).isGreaterThan(before)
    }

    @Test
    fun `pending is sorted by send time`() = runBlocking<Unit> {
        ScheduledSend.schedule(context, job("late", inAnHour + 60_000))
        ScheduledSend.schedule(context, job("soon", inAnHour))

        assertThat(ScheduledSend.pending(context).map { it.subject }).containsExactly("soon", "late").inOrder()
    }

    @Test
    fun `scheduling the same draft again replaces the previous schedule`() = runBlocking<Unit> {
        val first = ScheduledSend.schedule(context, job("v1", inAnHour, draftId = "1_d"))
        val second = ScheduledSend.schedule(context, job("v2", inAnHour, draftId = "1_d"))

        assertThat(ScheduledSend.pending(context).map { it.dir }).containsExactly(second)
        assertThat(first.exists()).isFalse()
        assertThat(work(first).single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `restore returns the complete mail once and cancels its task`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        assertThat(ScheduledSend.restoreDraft(context, dir) {
            assertThat(it.mail.subject).isEqualTo("S")
            assertThat(it.mail.attachments.single().third.toList()).containsExactly(1.toByte(), 2.toByte()).inOrder()
            assertThat(dir.exists()).isTrue()
            true
        }).isEqualTo(ScheduledSend.RestoreResult.RESTORED)
        assertThat(ScheduledSend.restoreDraft(context, dir) { error("unexpected save") }).isEqualTo(ScheduledSend.RestoreResult.GONE)
        assertThat(ScheduledSend.pending(context)).isEmpty()
        assertThat(work(dir).single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `failed or cancelled draft save preserves the original job and its work`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        val originalWork = work(dir).single().id
        assertThat(ScheduledSend.restoreDraft(context, dir) { false }).isEqualTo(ScheduledSend.RestoreResult.DRAFT_SAVE_FAILED)
        val began = kotlinx.coroutines.CompletableDeferred<Unit>()
        val restore = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            ScheduledSend.restoreDraft(context, dir) {
                began.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        began.await()
        assertThat(dir.exists()).isTrue()
        restore.cancelAndJoin()
        assertThat(ScheduledSend.read(dir)?.mail?.attachments).hasSize(1)
        assertThat(work(dir).single().id).isEqualTo(originalWork)
        assertThat(work(dir).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `terminal worker fallback retains payload and cannot be rearmed`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        assertThat(ScheduledSend.retainFailure(context, dir) { false }).isFalse()
        assertThat(ScheduledSend.read(dir)?.mail?.attachments).hasSize(1)
        assertThat(ScheduledSend.pending(context).single().status).isEqualTo(ScheduledSend.Status.FAILED)
        assertThat(ScheduledSend.runNow(context, dir)).isEqualTo(ScheduledSend.RunResult.UNAVAILABLE)
        assertThat(ScheduledSend.reschedule(context, dir, inAnHour)).isFalse()
        val before = work(dir)
        ScheduledSend.rearm(context)
        assertThat(work(dir)).containsExactlyElementsIn(before)
        assertThat(ScheduledSend.restoreDraft(context, dir) { true }).isEqualTo(ScheduledSend.RestoreResult.RESTORED)
    }

    @Test
    fun `worker terminal failure retains payload and repeated invocation does not send`() = runBlocking<Unit> {
        val dir = File(root(), "missing_account")
        ScheduledSend.write(dir, job("S", 0L).copy(accountId = Long.MAX_VALUE))
        val accountRepo = mockk<AccountRepository>()
        // Account is present at each send check, but gone when the final attempt tries to save a draft.
        coEvery { accountRepo.getAccount(Long.MAX_VALUE) } returnsMany listOf(mockk(), mockk(), null)
        coEvery { accountRepo.createEasClient(Long.MAX_VALUE) } returns null
        mockkObject(RepositoryProvider)
        try {
            every { RepositoryProvider.getAccountRepository(any()) } returns accountRepo
            fun worker(attempt: Int) = androidx.work.testing.TestListenableWorkerBuilder<com.dedovmosol.iwomail.ui.screens.ScheduledEmailWorker>(context)
                .setInputData(androidx.work.workDataOf(ScheduledSend.KEY_PAYLOAD_DIR to dir.absolutePath))
                .setRunAttemptCount(attempt)
                .build()
            val lastAttempt = com.dedovmosol.iwomail.ui.screens.ScheduledEmailWorker.MAX_ATTEMPTS - 1
            assertThat(worker(lastAttempt - 1).doWork()).isEqualTo(androidx.work.ListenableWorker.Result.retry())
            assertThat(worker(lastAttempt).doWork()).isEqualTo(androidx.work.ListenableWorker.Result.failure())
            assertThat(ScheduledSend.pending(context).single().status).isEqualTo(ScheduledSend.Status.FAILED)
            assertThat(ScheduledSend.read(dir)?.mail?.attachments).hasSize(1)
            assertThat(worker(lastAttempt).doWork()).isEqualTo(androidx.work.ListenableWorker.Result.failure())
            assertThat(dir.exists()).isTrue()
            io.mockk.coVerify(exactly = 2) { accountRepo.createEasClient(Long.MAX_VALUE) }
        } finally {
            unmockkObject(RepositoryProvider)
        }
    }

    @Test
    fun `legacy outbox makes missing account rows recoverable without redelivery`() = runBlocking<Unit> {
        val file = File(context.filesDir, "outbox.json")
        val row = org.json.JSONObject().put("accountId", Long.MAX_VALUE).put("body", "original")
        file.writeText(org.json.JSONArray().put(row).toString())
        val accountRepo = mockk<AccountRepository>()
        coEvery { accountRepo.createEasClient(Long.MAX_VALUE) } returns null
        coEvery { accountRepo.getAccount(Long.MAX_VALUE) } returns null
        mockkObject(RepositoryProvider)
        try {
            every { RepositoryProvider.getAccountRepository(any()) } returns accountRepo
            repeat(2) {
                val worker = androidx.work.testing.TestListenableWorkerBuilder<OutboxWorker>(context).build()
                assertThat(worker.doWork()).isEqualTo(androidx.work.ListenableWorker.Result.success())
                assertThat(org.json.JSONArray(file.readText()).length()).isEqualTo(0)
                val retained = ScheduledSend.pending(context).single()
                assertThat(retained.status).isEqualTo(ScheduledSend.Status.FAILED)
                assertThat(ScheduledSend.read(retained.dir)?.mail?.body).isEqualTo("original")
            }
            io.mockk.coVerify(exactly = 1) { accountRepo.createEasClient(Long.MAX_VALUE) }
        } finally {
            unmockkObject(RepositoryProvider)
            file.delete()
        }
    }

    @Test
    fun `restart recovers terminal legacy outbox with no account or network lookup`() = runBlocking<Unit> {
        val file = File(context.filesDir, "outbox.json")
        val key = java.util.UUID.randomUUID()
        val row = org.json.JSONObject().put("accountId", 1L).put("body", "recover me")
            .put("failed", true).put("recoveryId", key.toString())
        val interruptedQueue = org.json.JSONArray().put(row).toString()
        mockkObject(RepositoryProvider)
        try {
            every { RepositoryProvider.getAccountRepository(any()) } throws AssertionError("must not deliver")
            file.writeText(interruptedQueue)
            assertThat(androidx.work.testing.TestListenableWorkerBuilder<OutboxWorker>(context).build().doWork())
                .isEqualTo(androidx.work.ListenableWorker.Result.success())
            val retained = ScheduledSend.pending(context).single()
            assertThat(ScheduledSend.restoreDraft(context, retained.dir) { it.mail.body == "recover me" })
                .isEqualTo(ScheduledSend.RestoreResult.RESTORED)
            // Replay the old row as if compaction had not committed before process death.
            file.writeText(interruptedQueue)
            assertThat(androidx.work.testing.TestListenableWorkerBuilder<OutboxWorker>(context).build().doWork())
                .isEqualTo(androidx.work.ListenableWorker.Result.success())
            assertThat(ScheduledSend.pending(context)).isEmpty()
            assertThat(org.json.JSONArray(file.readText()).length()).isEqualTo(0)
        } finally {
            unmockkObject(RepositoryProvider)
            file.delete()
        }
    }

    @Test
    fun `inline terminal marker without metadata resumes only recovery`() = runBlocking<Unit> {
        val id = java.util.UUID.randomUUID()
        val dir = File(root(), "legacy_${id.toString().replace("-", "")}").apply { mkdirs() }
        ScheduledSend.markFailed(context, dir)
        val data = androidx.work.workDataOf("accountId" to 1L, "to" to "a@b.cd", "body" to "recover inline")
        fun worker() = androidx.work.testing.TestListenableWorkerBuilder<com.dedovmosol.iwomail.ui.screens.ScheduledEmailWorker>(context)
            .setId(id).setInputData(data).build()
        mockkObject(RepositoryProvider)
        try {
            every { RepositoryProvider.getAccountRepository(any()) } throws AssertionError("must not deliver")
            assertThat(worker().doWork()).isEqualTo(androidx.work.ListenableWorker.Result.failure())
            assertThat(ScheduledSend.pending(context).single().status).isEqualTo(ScheduledSend.Status.FAILED)
            assertThat(ScheduledSend.restoreDraft(context, dir) { it.mail.body == "recover inline" })
                .isEqualTo(ScheduledSend.RestoreResult.RESTORED)
            assertThat(worker().doWork()).isEqualTo(androidx.work.ListenableWorker.Result.success())
            assertThat(ScheduledSend.pending(context)).isEmpty()
        } finally {
            unmockkObject(RepositoryProvider)
        }
    }

    @Test
    fun `saved draft remains successful when EAS replaces its EWS id`() = runBlocking<Unit> {
        val accountRepo = mockk<AccountRepository>()
        coEvery { accountRepo.getAccount(1L) } returns mockk(relaxed = true)
        val mailRepo = mockk<com.dedovmosol.iwomail.data.repository.MailRepository>()
        val ews = "ews-${java.util.UUID.randomUUID()}"
        coEvery {
            mailRepo.saveDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), requireCompleteAttachments = true)
        } coAnswers {
            com.dedovmosol.iwomail.data.repository.DraftSuccessors.record("1_$ews", "1_eas-successor")
            ews
        }
        mockkObject(RepositoryProvider)
        try {
            every { RepositoryProvider.getAccountRepository(any()) } returns accountRepo
            every { RepositoryProvider.getMailRepository(any()) } returns mailRepo
            val dir = ScheduledSend.schedule(context, job("S", inAnHour))
            assertThat(ScheduledSend.restoreDraft(context, dir, stopDeliveryOnFailure = true) {
                com.dedovmosol.iwomail.domain.saveUnsentAsDraft(context, it.accountId, it.mail) == "1_eas-successor"
            }).isEqualTo(ScheduledSend.RestoreResult.RESTORED)
            assertThat(dir.exists()).isFalse()
        } finally {
            unmockkObject(RepositoryProvider)
        }
    }

    @Test
    fun `delivery mutex pool stays bounded and preserves identity`() {
        val dirs = (1..5000).map { File(root(), "job_$it") }
        assertThat(dirs.map(ScheduledSend::lock).toSet().size).isAtMost(128)
        for (dir in dirs.take(20)) assertThat(ScheduledSend.lock(dir)).isSameInstanceAs(ScheduledSend.lock(File(dir.path)))
    }

    @Test
    fun `failed schedule write leaves prior job intact`() = runBlocking<Unit> {
        val first = ScheduledSend.schedule(context, job("old", inAnHour, draftId = "draft"))
        val replacement = job("new", inAnHour, draftId = "draft").let {
            it.copy(mail = it.mail.copy(attachments = listOf(Triple("a".repeat(500), "text/plain", byteArrayOf(7)))))
        }
        assertThat(runCatching { ScheduledSend.schedule(context, replacement) }.isFailure).isTrue()
        assertThat(ScheduledSend.pending(context).single().dir).isEqualTo(first)
        assertThat(ScheduledSend.read(first)?.mail?.subject).isEqualTo("old")
        assertThat(work(first).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `reschedule moves the time and replaces the backstop`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        val oldWork = work(dir).single().id
        val later = inAnHour + 86_400_000L

        assertThat(ScheduledSend.reschedule(context, dir, later)).isTrue()

        assertThat(ScheduledSend.pending(context).single().sendAt).isEqualTo(later)
        assertThat(ScheduledSend.read(dir)?.mail?.attachments).hasSize(1)
        val infos = work(dir)
        assertThat(infos.single { it.state == WorkInfo.State.ENQUEUED }.id).isNotEqualTo(oldWork)
    }

    @Test
    fun `reschedule of a sent or cancelled mail is refused`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        ScheduledSend.restoreDraft(context, dir) { true }

        assertThat(ScheduledSend.reschedule(context, dir, inAnHour)).isFalse()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `undo window mail is marked and kept on disk for the backstop`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", System.currentTimeMillis() + 65_000, undo = true))

        assertThat(ScheduledSend.pending(context).single().undo).isTrue()
        assertThat(ScheduledSend.read(dir)?.undo).isTrue()
        assertThat(work(dir).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `undo mail handed to the worker is listed at once and stays an undo mail`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", System.currentTimeMillis() + 65_000, undo = true))
        assertThat(ScheduledSend.pending(context).single().listed(System.currentTimeMillis())).isFalse()

        assertThat(ScheduledSend.queueNow(context, dir)).isTrue()

        val entry = ScheduledSend.pending(context).single()
        // Видно в «Запланированных» (можно отменить), уведомление воркера — «письмо отправлено», не «запланированное»
        assertThat(entry.listed(System.currentTimeMillis())).isTrue()
        assertThat(entry.undo).isTrue()
        assertThat(work(dir).map { it.state }).contains(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `queueNow of a mail that is gone does nothing`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        ScheduledSend.restoreDraft(context, dir) { true }

        assertThat(ScheduledSend.queueNow(context, dir)).isFalse()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `finish removes the files and reports the change`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        val before = ScheduledSend.changes.value

        ScheduledSend.finish(context, dir)

        assertThat(dir.exists()).isFalse()
        assertThat(ScheduledSend.changes.value).isGreaterThan(before)
    }
}

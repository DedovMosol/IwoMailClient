package com.dedovmosol.iwomail.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.google.common.truth.Truth.assertThat
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
    fun `take returns the mail once and cancels its task`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))

        val taken = ScheduledSend.take(context, dir)

        assertThat(taken?.mail?.subject).isEqualTo("S")
        assertThat(taken?.mail?.attachments?.single()?.third?.toList()).containsExactly(1.toByte(), 2.toByte()).inOrder()
        assertThat(ScheduledSend.take(context, dir)).isNull()
        assertThat(ScheduledSend.pending(context)).isEmpty()
        assertThat(work(dir).single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `remove reports whether the mail was still waiting`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        assertThat(ScheduledSend.isPending(dir)).isTrue()

        assertThat(ScheduledSend.remove(context, dir)).isTrue()
        assertThat(ScheduledSend.remove(context, dir)).isFalse()
        assertThat(ScheduledSend.isPending(dir)).isFalse()
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
        ScheduledSend.take(context, dir)

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
    fun `finish removes the files and reports the change`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        val before = ScheduledSend.changes.value

        ScheduledSend.finish(context, dir)

        assertThat(dir.exists()).isFalse()
        assertThat(ScheduledSend.changes.value).isGreaterThan(before)
    }
}

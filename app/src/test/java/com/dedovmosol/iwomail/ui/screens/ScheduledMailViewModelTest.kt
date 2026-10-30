package com.dedovmosol.iwomail.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dedovmosol.iwomail.domain.OutgoingMail
import com.dedovmosol.iwomail.sync.ScheduledSend
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** Экран запланированных писем: список, перенос, «отправить сейчас», отмена в Черновики. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledMailViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val kept: MutableList<ScheduledSend.Job> = Collections.synchronizedList(mutableListOf())
    private var keepResult = true

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        File(context.filesDir, ScheduledSend.ROOT_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, ScheduledSend.ROOT_DIR).deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ScheduledMailViewModel(
        ApplicationProvider.getApplicationContext(), Dispatchers.IO, keepAsDraft = { job -> kept += job; keepResult }
    )

    private fun job(subject: String, sendAt: Long, undo: Boolean = false) = ScheduledSend.Job(
        accountId = 1L, mail = OutgoingMail("a@corp.example", "", "", subject, "B"),
        sendAt = sendAt, clientId = "cid-$subject", draftId = "1_$subject", draftStamp = 7L, undo = undo
    )

    private val inAnHour get() = System.currentTimeMillis() + 3_600_000L

    private suspend fun ScheduledMailViewModel.awaitItems(predicate: (List<ScheduledSend.Entry>) -> Boolean) =
        withTimeout(5_000) { items.first { it != null && predicate(it) }!! }

    @Test
    fun `list shows scheduled emails by time and hides ones waiting for undo`() = runBlocking<Unit> {
        ScheduledSend.schedule(context, job("late", inAnHour + 60_000))
        ScheduledSend.schedule(context, job("soon", inAnHour))
        ScheduledSend.schedule(context, job("undo", System.currentTimeMillis() + 65_000, undo = true))

        val items = viewModel().awaitItems { it.size == 2 }

        assertThat(items.map { it.subject }).containsExactly("soon", "late").inOrder()
    }

    @Test
    fun `undo email whose backstop is overdue is listed so it can be cancelled`() = runBlocking<Unit> {
        // Приложение закрыли во время отсчёта, а связи нет: письмо ждёт воркера — пользователь должен его видеть
        ScheduledSend.schedule(context, job("stuck", System.currentTimeMillis() - 1_000, undo = true))

        val items = viewModel().awaitItems { it.isNotEmpty() }

        assertThat(items.map { it.subject }).containsExactly("stuck")
    }

    @Test
    fun `cancel returns the email to Drafts and removes it from the list`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        val vm = viewModel()
        val entry = vm.awaitItems { it.size == 1 }.single()

        vm.cancel(entry)

        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ScheduledMailEvent.ReturnedToDrafts)
        assertThat(kept.single().draftStamp).isEqualTo(7L)
        assertThat(vm.awaitItems { it.isEmpty() }).isEmpty()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `cancel that cannot save keeps the email scheduled`() = runBlocking<Unit> {
        keepResult = false
        ScheduledSend.schedule(context, job("S", inAnHour))
        val vm = viewModel()
        val entry = vm.awaitItems { it.size == 1 }.single()

        vm.cancel(entry)

        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ScheduledMailEvent.DraftSaveFailed)
        assertThat(ScheduledSend.pending(context).single().subject).isEqualTo("S")
    }

    @Test
    fun `reschedule moves the send time`() = runBlocking<Unit> {
        ScheduledSend.schedule(context, job("S", inAnHour))
        val vm = viewModel()
        val entry = vm.awaitItems { it.size == 1 }.single()
        val later = inAnHour + 86_400_000L

        vm.reschedule(entry, later)

        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ScheduledMailEvent.Rescheduled)
        assertThat(vm.awaitItems { list -> list.singleOrNull()?.sendAt == later }.single().sendAt).isEqualTo(later)
    }

    @Test
    fun `actions on an email that is already gone say so`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        val vm = viewModel()
        val entry = vm.awaitItems { it.size == 1 }.single()
        ScheduledSend.take(context, dir)

        vm.reschedule(entry, inAnHour)
        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ScheduledMailEvent.AlreadyGone)
        vm.cancel(entry)
        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ScheduledMailEvent.AlreadyGone)
        vm.sendNow(entry)
        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ScheduledMailEvent.AlreadyGone)
        assertThat(kept).isEmpty()
    }

    @Test
    fun `send now queues an expedited send`() = runBlocking<Unit> {
        val dir = ScheduledSend.schedule(context, job("S", inAnHour))
        val vm = viewModel()
        val entry = vm.awaitItems { it.size == 1 }.single()

        vm.sendNow(entry)

        assertThat(withTimeout(5_000) { vm.events.first() }).isEqualTo(ScheduledMailEvent.SendingNow)
        withTimeout(5_000) {
            while (WorkManager.getInstance(context).getWorkInfosForUniqueWork(ScheduledSend.workName(dir)).get()
                    .none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            ) delay(10)
        }
    }
}

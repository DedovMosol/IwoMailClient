package com.dedovmosol.iwomail.ui.screens

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dedovmosol.iwomail.domain.keepScheduledAsDraft
import com.dedovmosol.iwomail.sync.ScheduledSend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Одноразовые события экрана запланированных писем (локализация — в UI). */
sealed interface ScheduledMailEvent {
    data object SendingNow : ScheduledMailEvent
    data object Rescheduled : ScheduledMailEvent
    data object ReturnedToDrafts : ScheduledMailEvent
    /** Письма уже нет: отправлено или отменено в другом месте. */
    data object AlreadyGone : ScheduledMailEvent
    data object DraftSaveFailed : ScheduledMailEvent
    data object Unavailable : ScheduledMailEvent
    data object QueueError : ScheduledMailEvent
}

/**
 * Запланированные письма: список ([ScheduledSend.pending], без ожидающих отмены отправки) обновляется при каждом
 * изменении набора ([ScheduledSend.changes]); операции — в [viewModelScope] (переживают поворот экрана).
 */
class ScheduledMailViewModel(
    /** Application, а не Context: ViewModel живёт дольше экрана (без утечки Activity). */
    private val context: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Отменённое письмо — в Черновики (подменяется в тестах). */
    private val keepAsDraft: suspend (ScheduledSend.Job) -> Boolean = { job ->
        keepScheduledAsDraft(context, job.accountId, job.mail, job.draftId, job.draftStamp)
    },
    changes: Flow<Long> = ScheduledSend.changes
) : ViewModel() {

    val items: StateFlow<List<ScheduledSend.Entry>?> = changes
        .map { withContext(ioDispatcher) { ScheduledSend.pending(context).filter { it.listed(System.currentTimeMillis()) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<ScheduledMailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun sendNow(entry: ScheduledSend.Entry) = execute {
        when (ScheduledSend.runNow(context, entry.dir)) {
            ScheduledSend.RunResult.ENQUEUED, ScheduledSend.RunResult.RUNNING -> ScheduledMailEvent.SendingNow
            ScheduledSend.RunResult.GONE, ScheduledSend.RunResult.NOT_DUE -> ScheduledMailEvent.AlreadyGone
            ScheduledSend.RunResult.UNAVAILABLE -> ScheduledMailEvent.Unavailable
        }
    }

    fun reschedule(entry: ScheduledSend.Entry, sendAt: Long) = execute {
        if (ScheduledSend.reschedule(context, entry.dir, sendAt)) ScheduledMailEvent.Rescheduled
        else when (withContext(ioDispatcher) { ScheduledSend.status(entry.dir) }) {
            ScheduledSend.Status.GONE -> ScheduledMailEvent.AlreadyGone
            else -> ScheduledMailEvent.Unavailable
        }
    }

    fun cancel(entry: ScheduledSend.Entry) = execute {
        when (ScheduledSend.restoreDraft(context, entry.dir) { withContext(ioDispatcher) { keepAsDraft(it) } }) {
            ScheduledSend.RestoreResult.RESTORED -> ScheduledMailEvent.ReturnedToDrafts
            ScheduledSend.RestoreResult.DRAFT_SAVE_FAILED -> ScheduledMailEvent.DraftSaveFailed
            ScheduledSend.RestoreResult.UNAVAILABLE -> ScheduledMailEvent.Unavailable
            ScheduledSend.RestoreResult.GONE -> ScheduledMailEvent.AlreadyGone
        }
    }

    private fun execute(action: suspend () -> ScheduledMailEvent) {
        viewModelScope.launch {
            val event = try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("ScheduledMail", "Queue action failed", e)
                ScheduledMailEvent.QueueError
            }
            _events.send(event)
        }
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ScheduledMailViewModel(application) as T
            }
    }
}

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
    /** Отменённое письмо не удалось сохранить в Черновики — отправка осталась запланированной. */
    data object DraftSaveFailed : ScheduledMailEvent
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
        .map { withContext(ioDispatcher) { ScheduledSend.pending(context).filterNot { it.undo } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<ScheduledMailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun sendNow(entry: ScheduledSend.Entry) {
        viewModelScope.launch {
            if (!entry.dir.exists()) return@launch _events.send(ScheduledMailEvent.AlreadyGone)
            ScheduledSend.runNow(context, entry.dir)
            _events.send(ScheduledMailEvent.SendingNow)
        }
    }

    fun reschedule(entry: ScheduledSend.Entry, sendAt: Long) {
        viewModelScope.launch {
            val ok = ScheduledSend.reschedule(context, entry.dir, sendAt)
            _events.send(if (ok) ScheduledMailEvent.Rescheduled else ScheduledMailEvent.AlreadyGone)
        }
    }

    /** Отменить отправку: письмо — в Черновики. Уже уходит — дождаться и сообщить, что отменять поздно. */
    fun cancel(entry: ScheduledSend.Entry) {
        viewModelScope.launch {
            val job = ScheduledSend.take(context, entry.dir) ?: return@launch _events.send(ScheduledMailEvent.AlreadyGone)
            if (withContext(ioDispatcher) { keepAsDraft(job) }) {
                _events.send(ScheduledMailEvent.ReturnedToDrafts)
            } else {
                // Не терять письмо: остаётся запланированным
                ScheduledSend.schedule(context, job)
                _events.send(ScheduledMailEvent.DraftSaveFailed)
            }
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

package com.dedovmosol.iwomail.ui.components

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dedovmosol.iwomail.data.database.MailDatabase
import com.dedovmosol.iwomail.data.repository.RepositoryErrors
import com.dedovmosol.iwomail.data.repository.RepositoryProvider
import com.dedovmosol.iwomail.domain.deleteDraftQuietly
import com.dedovmosol.iwomail.domain.isRetryableSendError
import com.dedovmosol.iwomail.domain.refreshSentFolder
import com.dedovmosol.iwomail.domain.returnToDrafts
import com.dedovmosol.iwomail.domain.send
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.network.ConnectionFailure
import com.dedovmosol.iwomail.sync.ScheduledSend
import com.dedovmosol.iwomail.sync.SendNotifications
import com.dedovmosol.iwomail.ui.NotificationStrings
import com.dedovmosol.iwomail.util.SafeToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Что контроллер отправки берёт извне: настройки, сервер, Черновики, звук и сообщения (в тестах — подмена). */
interface SendEnvironment {
    /** Сколько ждать отмены (настройка «Отмена отправки»). */
    suspend fun undoDelayMs(context: Context): Long
    /** Время изменения черновика — чтобы после отправки удалить именно его, а не более позднюю правку. */
    suspend fun draftStamp(context: Context, draftId: String): Long?
    suspend fun send(context: Context, job: ScheduledSend.Job): EasResult<Boolean>
    /** Письмо — в Черновики вместо черновика, из которого отправляли; id нового черновика или null. */
    suspend fun returnToDrafts(context: Context, job: ScheduledSend.Job): String?
    /** Отправлено: удалить отправленный черновик, обновить «Отправленные». */
    suspend fun afterSent(context: Context, job: ScheduledSend.Job)
    fun playSent(context: Context)
    fun message(context: Context, text: String, long: Boolean)
    /** Язык сообщений — как у интерфейса. */
    fun isRussian(context: Context): Boolean
}

/** Рабочее окружение: репозитории, EAS, звук, тосты. */
object DefaultSendEnvironment : SendEnvironment {
    override suspend fun undoDelayMs(context: Context): Long =
        RepositoryProvider.getSettingsRepository(context).undoSendSeconds.first() * 1000L

    override suspend fun draftStamp(context: Context, draftId: String): Long? =
        withContext(Dispatchers.IO) { MailDatabase.getInstance(context).emailDao().getEmail(draftId)?.dateReceived }

    override suspend fun send(context: Context, job: ScheduledSend.Job): EasResult<Boolean> {
        val client = RepositoryProvider.getAccountRepository(context).createEasClient(job.accountId)
            ?: return EasResult.Error(RepositoryErrors.CLIENT_CREATE_FAILED)
        return withContext(Dispatchers.IO) { client.send(job.mail, stableClientId = job.clientId) }
    }

    override suspend fun returnToDrafts(context: Context, job: ScheduledSend.Job): String? =
        withContext(Dispatchers.IO) { returnToDrafts(context, job.accountId, job.mail, job.draftId) }

    override suspend fun afterSent(context: Context, job: ScheduledSend.Job) {
        job.draftId?.let { deleteDraftQuietly(context, it) }
        refreshSentFolder(context, job.accountId)
    }

    override fun playSent(context: Context) = com.dedovmosol.iwomail.util.SoundPlayer.playSendSound(context)

    override fun message(context: Context, text: String, long: Boolean) =
        if (long) SafeToast.long(context, text) else SafeToast.short(context, text)

    override fun isRussian(context: Context): Boolean = SendNotifications.isRussian(context)
}

/**
 * Отправка письма с отменой («Отменить» в течение нескольких секунд, настройка «Отмена отправки»).
 *
 * Письмо сразу записывается на диск как отложенное ([ScheduledSend], undo): если приложение закроют во время
 * ожидания, его отправит подстраховка WorkManager — письмо не теряется. В срок его отправляет этот контроллер (под
 * той же блокировкой, что и воркер: дважды не уйдёт).
 * - Отменили — письмо возвращается в Черновики (вместо черновика, из которого отправляли) и открывается в редакторе
 *   ([undone]).
 * - Нет связи или сервер перегружен — отправит воркер, когда связь появится (с вложениями, с повторами).
 * - Ошибка, которую повтор не исправит, — письмо в Черновиках, причина в сообщении.
 *
 * Один экземпляр на процесс ([shared]): поворот экрана не теряет ни отправку, ни кнопку отмены.
 */
class SendController(
    private val env: SendEnvironment = DefaultSendEnvironment,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    var state by mutableStateOf(SendState())
        private set

    // Собственный scope для отправки (не зависит от UI)
    private val sendScope = com.dedovmosol.iwomail.util.supervisedScope(mainDispatcher)
    private var sendJob: Job? = null
    /** Решение во время ожидания: true — отменить, false — отправить сразу. */
    @Volatile
    private var decision: CompletableDeferred<Boolean>? = null

    private val _undone = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** id черновика, в который вернулось отменённое письмо, — открыть его в редакторе. */
    val undone: SharedFlow<String> = _undone.asSharedFlow()

    /**
     * Отправить [email] после ожидания из настроек.
     *
     * @param onSuccess письмо отправлено или поставлено в очередь
     * @param onCancel отправку отменили
     * @param onError письмо не отправлено (оно в Черновиках) — вызывающий снимает блокировку отправки
     */
    fun startSend(
        email: PendingEmail,
        message: String,
        context: Context,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
        onError: () -> Unit = {}
    ) {
        // Прежнее письмо ещё ждёт — уходит сразу, а не теряется; новое — следом
        decision?.complete(false)
        val previous = sendJob
        val appContext = context.applicationContext
        sendJob = sendScope.launch {
            previous?.join()
            try {
                when (run(appContext, email, message)) {
                    Outcome.SENT, Outcome.QUEUED -> onSuccess()
                    Outcome.UNDONE -> onCancel()
                    Outcome.FAILED -> onError()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SendController", "Send failed", e)
                env.message(appContext, e.message ?: "Send error", long = true)
                onError()
            } finally {
                state = SendState()
                decision = null
            }
        }
    }

    /** «Отменить» на плашке: только пока письмо ждёт. */
    fun cancel() {
        if (state.isActive && state.phase == SendPhase.COUNTDOWN) decision?.complete(true)
    }

    private enum class Outcome { SENT, QUEUED, UNDONE, FAILED }

    private class Delivery(val outcome: Outcome, val sent: Boolean = false, val error: String? = null)

    private suspend fun run(context: Context, email: PendingEmail, message: String): Outcome {
        state = SendState(isActive = true, message = message, phase = SendPhase.SENDING)
        val ru = env.isRussian(context)
        val delayMs = env.undoDelayMs(context)
        val draftId = email.draftId?.takeIf { it.isNotBlank() && it != "synced" }
        val job = ScheduledSend.Job(
            accountId = email.account.id,
            mail = email.toOutgoingMail(),
            sendAt = System.currentTimeMillis() + delayMs + UNDO_BACKSTOP_MS,
            clientId = java.util.UUID.randomUUID().toString().replace("-", ""),
            draftId = draftId,
            draftStamp = draftId?.let { env.draftStamp(context, it) } ?: -1L,
            undo = true
        )
        val dir = try {
            ScheduledSend.schedule(context, job)
        } catch (e: java.io.IOException) {
            // Не записать на диск (нет места) — отправить сразу, без ожидания: письмо не теряется
            android.util.Log.w("SendController", "Can't persist the email, sending at once: ${e.message}")
            return deliver(context, job, null, ru)
        }

        if (delayMs > 0) {
            val choice = CompletableDeferred<Boolean>().also { decision = it }
            state = state.copy(phase = SendPhase.COUNTDOWN, countdownStartedAt = SystemClock.elapsedRealtime(), countdownMs = delayMs)
            val undo = withTimeoutOrNull(delayMs) { choice.await() } ?: false
            decision = null
            if (undo) return undo(context, job, dir, ru)
            state = state.copy(phase = SendPhase.SENDING)
        }
        return deliver(context, job, dir, ru)
    }

    /** Отменили: письмо — в Черновики и в редактор. */
    private suspend fun undo(context: Context, job: ScheduledSend.Job, dir: File, ru: Boolean): Outcome {
        state = SendState()
        if (!ScheduledSend.remove(context, dir)) {
            // Уже отправил воркер (приложение уходило в фон) — вернуть нечего
            env.message(context, NotificationStrings.getSendUndoTooLate(ru), long = true)
            return Outcome.SENT
        }
        val draftId = env.returnToDrafts(context, job)
        if (draftId == null) {
            // Не сохранить — не терять: письмо уходит, как и собирались
            ScheduledSend.schedule(context, job.copy(sendAt = System.currentTimeMillis()))
            env.message(context, NotificationStrings.getUndoDraftFailed(ru), long = true)
            return Outcome.QUEUED
        }
        env.message(context, NotificationStrings.getSendUndone(ru), long = false)
        _undone.tryEmit(draftId)
        return Outcome.UNDONE
    }

    /**
     * Отправить письмо сейчас — под блокировкой его каталога [dir]: воркер-подстраховка его уже не отправит.
     * [dir] = null — письмо не записано на диск: отправка без очереди (не ушло — письмо в Черновики).
     */
    private suspend fun deliver(context: Context, job: ScheduledSend.Job, dir: File?, ru: Boolean): Outcome {
        val delivery = if (dir == null) sendOnce(context, job, null, ru) else ScheduledSend.lock(dir).withLock {
            // Отправлено или отменено в другом месте (воркер, список запланированных)
            if (!withContext(Dispatchers.IO) { ScheduledSend.isPending(dir) }) Delivery(Outcome.SENT)
            else sendOnce(context, job, dir, ru)
        }
        when (delivery.outcome) {
            Outcome.SENT -> if (delivery.sent) {
                env.playSent(context)
                sendScope.launch { env.afterSent(context, job) }
            }
            Outcome.QUEUED -> {
                // Отправит воркер, как только будет связь (с вложениями и повторами); до тех пор письмо в списке
                // запланированных — его видно и можно отменить
                dir?.let { ScheduledSend.queueNow(context, it) }
                val offline = ConnectionFailure.fromMessage(delivery.error)?.connectionLevel == true
                env.message(
                    context,
                    if (offline) NotificationStrings.getSendQueuedOffline(ru) else NotificationStrings.getSendQueuedServerBusy(ru),
                    long = true
                )
            }
            else -> Unit
        }
        return delivery.outcome
    }

    /** Одна попытка отправки; временная ошибка с [dir] — в очередь, иначе (или без [dir]) — в Черновики. */
    private suspend fun sendOnce(context: Context, job: ScheduledSend.Job, dir: File?, ru: Boolean): Delivery {
        val result = env.send(context, job)
        if (result is EasResult.Success) {
            dir?.let { ScheduledSend.cancel(context, it) }
            return Delivery(Outcome.SENT, sent = true)
        }
        val error = (result as EasResult.Error).message
        if (dir != null && isRetryableSendError(error)) return Delivery(Outcome.QUEUED, error = error)
        val saved = env.returnToDrafts(context, job) != null
        dir?.let { ScheduledSend.cancel(context, it) }
        env.message(context, NotificationStrings.getEmailNotSentText(job.mail.subject, error, saved, ru), long = true)
        return Delivery(Outcome.FAILED, error = error)
    }

    companion object {
        /**
         * Подстраховка воркера — позже окончания ожидания: если экран отправки жив, письмо отправит он, а воркер
         * сработает, только если приложение закрыли раньше.
         */
        internal const val UNDO_BACKSTOP_MS = 60_000L

        /** Один на процесс: отправка и отмена переживают пересоздание Activity. */
        val shared: SendController by lazy { SendController() }
    }
}

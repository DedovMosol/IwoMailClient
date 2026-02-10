package com.dedovmosol.iwomail.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.dedovmosol.iwomail.ui.theme.AppIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.data.database.AccountEntity
import com.dedovmosol.iwomail.data.repository.AccountRepository
import com.dedovmosol.iwomail.data.repository.MailRepository
import com.dedovmosol.iwomail.eas.EasResult
import com.dedovmosol.iwomail.network.NetworkMonitor
import com.dedovmosol.iwomail.sync.OutboxWorker
import kotlinx.coroutines.*

/**
 * Данные вложения для отправки
 */
data class AttachmentData(
    val name: String,
    val mimeType: String,
    val data: ByteArray
)

/**
 * Данные для отправки письма
 */
data class PendingEmail(
    val account: AccountEntity,
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    val attachments: List<AttachmentData>,
    val importance: Int,
    val requestReadReceipt: Boolean,
    val requestDeliveryReceipt: Boolean,
    val draftId: String? = null // ID черновика для удаления после отправки
)

/**
 * Состояние отправки
 */
data class SendState(
    val isActive: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val phase: SendPhase = SendPhase.COUNTDOWN
)

enum class SendPhase {
    COUNTDOWN,  // Обратный отсчёт (можно отменить)
    SENDING     // Реальная отправка (нельзя отменить)
}

/**
 * Контроллер отправки с обратным отсчётом
 */
class SendController {
    var state by mutableStateOf(SendState())
        private set
    
    private var sendJob: Job? = null
    private var isCancelled = false
    private var pendingEmail: PendingEmail? = null
    private var onCancelCallback: (() -> Unit)? = null
    
    // Собственный scope для отправки (не зависит от UI)
    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    /**
     * Запускает отправку с обратным отсчётом
     */
    fun startSend(
        email: PendingEmail,
        message: String,
        context: Context,
        mailRepo: MailRepository,
        onSuccess: () -> Unit,
        onCancel: () -> Unit
    ) {
        // Отменяем предыдущую отправку если была
        cancel()
        isCancelled = false
        pendingEmail = email
        onCancelCallback = onCancel
        
        state = SendState(
            isActive = true,
            progress = 0f,
            message = message,
            phase = SendPhase.COUNTDOWN
        )
        
        // Обратный отсчёт: 3 секунды
        val countdownMs = 3000L
        val stepMs = 50L
        val steps = (countdownMs / stepMs).toInt()
        
        val accountRepo = AccountRepository(context)
        
        sendJob = sendScope.launch {
            try {
                // Фаза 1: Обратный отсчёт
                for (i in 1..steps) {
                    if (isCancelled) return@launch
                    delay(stepMs)
                    state = state.copy(progress = i.toFloat() / steps)
                }
                
                if (isCancelled) return@launch
                
                // Фаза 2: Реальная отправка
                state = state.copy(
                    phase = SendPhase.SENDING,
                    progress = 0f
                )
                
                val accountRepo = AccountRepository(context)
                val client = accountRepo.createEasClient(email.account.id)
                if (client == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to create client", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                val attachmentDataList = email.attachments.map { Triple(it.name, it.mimeType, it.data) }
                
                val result = withContext(Dispatchers.IO) {
                    if (attachmentDataList.isEmpty()) {
                        client.sendMail(
                            to = email.to,
                            subject = email.subject,
                            body = email.body,
                            cc = email.cc,
                            bcc = email.bcc,
                            importance = email.importance,
                            requestReadReceipt = email.requestReadReceipt,
                            requestDeliveryReceipt = email.requestDeliveryReceipt
                        )
                    } else {
                        client.sendMailWithAttachments(
                            to = email.to,
                            subject = email.subject,
                            body = email.body,
                            cc = email.cc,
                            bcc = email.bcc,
                            attachments = attachmentDataList,
                            requestReadReceipt = email.requestReadReceipt,
                            requestDeliveryReceipt = email.requestDeliveryReceipt,
                            importance = email.importance
                        )
                    }
                }
                
                when (result) {
                    is EasResult.Success -> {
                        // Удаляем черновик если был
                        email.draftId?.let { draftId ->
                            withContext(Dispatchers.IO) {
                                val database = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                                val emailEntity = database.emailDao().getEmail(draftId)
                                if (emailEntity != null) {
                                    // Удаляем черновик
                                    database.emailDao().delete(draftId)
                                    // Обновляем счетчики папки черновиков
                                    val draftsFolder = database.folderDao().getFolderByType(emailEntity.accountId, 3)
                                    if (draftsFolder != null) {
                                        val totalCount = database.emailDao().getCountByFolder(draftsFolder.id)
                                        val unreadCount = database.emailDao().getUnreadCount(draftsFolder.id)
                                        database.folderDao().updateCounts(draftsFolder.id, unreadCount, totalCount)
                                    }
                                }
                            }
                        }
                        
                        // Воспроизводим звук отправки
                        com.dedovmosol.iwomail.util.SoundPlayer.playSendSound(context)
                        // Синхронизируем папку "Отправленные" в фоне с retry.
                        // Exchange может не сразу поместить письмо в Sent Items
                        // (обработка на CAS/MBX занимает время).
                        // Стратегия: 3 попытки с возрастающей задержкой (3с, 6с, 10с).
                        val accountId = email.account.id
                        launch(Dispatchers.IO) {
                            try {
                                val database = com.dedovmosol.iwomail.data.database.MailDatabase.getInstance(context)
                                val sentFolder = database.folderDao().getFoldersByAccountList(accountId)
                                    .find { it.type == 5 }
                                
                                sentFolder?.let { folder ->
                                    val delays = longArrayOf(3000L, 6000L, 10000L)
                                    for (attempt in delays.indices) {
                                        delay(delays[attempt])
                                        try {
                                            if (email.account.accountType == "EXCHANGE") {
                                                // Инкрементальная синхронизация: быстрее и безопаснее.
                                                // syncSentFull (syncKey="0") перезагружает ВСЮ папку — медленно,
                                                // плюс при конфликте с SyncWorker может give up.
                                                // Инкрементальный sync подхватывает новое письмо за 1 batch.
                                                val syncResult = mailRepo.syncEmails(accountId, folder.id)
                                                if (syncResult is EasResult.Success && syncResult.data > 0) {
                                                    // Инкрементальный sync нашёл новое письмо → готово
                                                    break
                                                }
                                                if (syncResult is EasResult.Error) {
                                                    // Fallback: полная ресинхронизация при ошибке.
                                                    // syncSentFull возвращает TOTAL count (не new),
                                                    // поэтому не проверяем result.data > 0 для неё.
                                                    mailRepo.syncSentFull(accountId, folder.id)
                                                    break // syncSentFull сделала всё что могла
                                                }
                                                // syncResult is Success с data=0 → письмо ещё не на сервере
                                                // Продолжаем retry с увеличенной задержкой
                                            } else {
                                                val result = mailRepo.syncEmails(accountId, folder.id)
                                                if (result is EasResult.Success && result.data > 0) {
                                                    break
                                                }
                                            }
                                        } catch (_: Exception) {
                                            // Следующая попытка
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Ошибки sync не должны крашить UI
                            }
                        }
                        
                        onSuccess()
                    }
                    is EasResult.Error -> {
                        // Проверяем, связана ли ошибка с сетью
                        val isNetworkError = result.message.contains("UnknownHost", ignoreCase = true) ||
                            result.message.contains("SocketTimeout", ignoreCase = true) ||
                            result.message.contains("ConnectException", ignoreCase = true) ||
                            result.message.contains("NoRouteToHost", ignoreCase = true) ||
                            result.message.contains("Network", ignoreCase = true) ||
                            !NetworkMonitor.isNetworkAvailable(context)
                        
                        if (isNetworkError && email.attachments.isEmpty()) {
                            // Добавляем в очередь отправки (только без вложений)
                            OutboxWorker.enqueue(
                                context = context,
                                accountId = email.account.id,
                                to = email.to,
                                cc = email.cc,
                                bcc = email.bcc,
                                subject = email.subject,
                                body = email.body,
                                requestReadReceipt = email.requestReadReceipt
                            )
                            withContext(Dispatchers.Main) {
                                val isRu = java.util.Locale.getDefault().language == "ru"
                                val msg = if (isRu) "Нет сети. Письмо добавлено в очередь отправки" 
                                    else "No network. Email added to outbox queue"
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                            onSuccess() // Закрываем экран — письмо в очереди
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                
                delay(300)
            } finally {
                state = SendState()
                pendingEmail = null
                onCancelCallback = null
            }
        }
    }
    
    /**
     * Отменяет отправку (только в фазе обратного отсчёта)
     */
    fun cancel() {
        if (state.isActive && state.phase == SendPhase.COUNTDOWN) {
            isCancelled = true
            sendJob?.cancel()
            sendJob = null
            state = SendState()
            onCancelCallback?.invoke()
            pendingEmail = null
            onCancelCallback = null
        }
    }
}

/**
 * CompositionLocal для доступа к контроллеру отправки
 */
val LocalSendController = compositionLocalOf { SendController() }

// Зелёные цвета для прогресс-бара отправки
private val SendContainerColor = Color(0xFFE8F5E9) // Light green background
private val SendContentColor = Color(0xFF2E7D32) // Dark green text
private val SendProgressColor = Color(0xFF4CAF50) // Green progress

/**
 * Плашка прогресса отправки (зелёная)
 */
@Composable
fun SendProgressBar(
    controller: SendController,
    modifier: Modifier = Modifier
) {
    val state = controller.state
    
    if (state.isActive) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = 80.dp),
            color = SendContainerColor,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = SendContentColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (state.phase) {
                                SendPhase.COUNTDOWN -> "${(state.progress * 100).toInt()}%"
                                SendPhase.SENDING -> Strings.sending
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = SendContentColor.copy(alpha = 0.7f)
                        )
                    }
                    
                    // Кнопка отмены только в фазе обратного отсчёта
                    if (state.phase == SendPhase.COUNTDOWN) {
                        TextButton(
                            onClick = { controller.cancel() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = SendContentColor
                            )
                        ) {
                            Icon(
                                AppIcons.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Strings.cancel)
                        }
                    } else {
                        // Индикатор отправки
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = SendProgressColor,
                            strokeWidth = 2.dp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LinearProgressIndicator(
                    progress = { if (state.phase == SendPhase.SENDING) 1f else state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = SendProgressColor,
                    trackColor = SendContentColor.copy(alpha = 0.2f)
                )
            }
        }
    }
}

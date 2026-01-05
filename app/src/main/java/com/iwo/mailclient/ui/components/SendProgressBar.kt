package com.iwo.mailclient.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.iwo.mailclient.ui.theme.AppIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iwo.mailclient.ui.Strings
import com.iwo.mailclient.data.database.AccountEntity
import com.iwo.mailclient.data.repository.AccountRepository
import com.iwo.mailclient.data.repository.MailRepository
import com.iwo.mailclient.eas.EasClient
import com.iwo.mailclient.eas.EasResult
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
                
                val password = accountRepo.getPassword(email.account.id)
                if (password == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Auth error", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                val client = EasClient(
                    serverUrl = email.account.serverUrl,
                    username = email.account.username,
                    password = password,
                    domain = email.account.domain,
                    acceptAllCerts = email.account.acceptAllCerts,
                    deviceIdSuffix = email.account.email,
                    certificatePath = email.account.certificatePath
                )
                
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
                                val database = com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                                database.emailDao().delete(draftId)
                            }
                        }
                        
                        // Воспроизводим звук отправки
                        com.iwo.mailclient.util.SoundPlayer.playSendSound(context)
                        // Синхронизируем папку "Отправленные" в фоне
                        val accountId = email.account.id
                        launch(Dispatchers.IO) {
                            try {
                                delay(2000)
                                val database = com.iwo.mailclient.data.database.MailDatabase.getInstance(context)
                                val sentFolder = database.folderDao().getFoldersByAccountList(accountId)
                                    .find { it.type == 5 }
                                sentFolder?.let { folder ->
                                    mailRepo.syncEmails(accountId, folder.id)
                                }
                            } catch (_: Exception) { }
                        }
                        
                        onSuccess()
                    }
                    is EasResult.Error -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
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
        if (state.phase == SendPhase.COUNTDOWN) {
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
